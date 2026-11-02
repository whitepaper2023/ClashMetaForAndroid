// FEATURE-DOMAINNODE BEGIN (whole file): domain/keyword designated
// node page. The user adds a domain pattern (exact, suffix or
// keyword) and a node/group target; ConfigurationModule turns them
// into DOMAIN / DOMAIN-SUFFIX / DOMAIN-KEYWORD rules at load time.
// Priority (Lu, 2026-11-02): app block REJECT > subscription REJECT
// (ads) > domain rules > remaining subscription rules > per-app node
// rules (fallback) > MATCH. Within domain rules: DOMAIN >
// DOMAIN-SUFFIX > DOMAIN-KEYWORD, first-added wins within a type.
// Non-silent stale handling (Lu, 2026-11-02, deliberately unlike
// AppNode's silent prune): when the target list is complete, rules
// whose target no longer exists are removed as soon as the page
// opens AND named in a toast, so a dead rule is never silently kept
// or silently dropped. A provider-backed config's disk list is
// incomplete (provider nodes are missing until the profile runs), so
// against it nothing is pruned or marked stale -- same safety rule as
// NodeFilterActivity. Rules are saved and applied (via
// sendOverrideChanged) when leaving the page, mirroring AppNode.
package com.github.kr328.clash

import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.design.DomainNodeDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.adapter.DomainRow
import com.github.kr328.clash.service.store.DomainRule
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.store.encodeDomainNodeRulesForProfile
import com.github.kr328.clash.service.store.parseDomainNodeRulesForProfile
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendOverrideChanged
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class DomainNodeActivity : BaseActivity<DomainNodeDesign>() {
    override suspend fun main() {
        val service = ServiceStore(this)

        val profileUuid = withContext(Dispatchers.IO) { service.activeProfile }
        val rules = withContext(Dispatchers.IO) {
            if (profileUuid != null)
                parseDomainNodeRulesForProfile(service.domainNodeRules, profileUuid).toMutableList()
            else
                mutableListOf()
        }

        // Target list: live core names while running (complete), else
        // the profile's config file on disk (complete only when the
        // config has no proxy-providers; see below).
        var targets: List<String> = emptyList()
        var targetDisplays: List<String> = emptyList()
        var listComplete = false
        if (clashRunning) {
            val collected = withClash {
                val valueList = ArrayList<String>()
                val displayList = ArrayList<String>()
                val seenValues = HashSet<String>()
                fun offer(value: String, display: String) {
                    if (',' !in value && seenValues.add(value)) {
                        valueList.add(value)
                        displayList.add(display)
                    }
                }
                val visited = HashSet<String>()
                val queue = ArrayDeque<String>()
                queryProxyGroupNames(false)
                    .filter { it != "GLOBAL" }
                    .forEach { queue.addLast(it) }
                while (queue.isNotEmpty()) {
                    val name = queue.removeFirst()
                    if (!visited.add(name))
                        continue
                    offer(name, "$name（分组）")
                    val group = queryProxyGroup(name, ProxySort.Default)
                    for (proxy in group.proxies) {
                        if (',' in proxy.name)
                            continue
                        if (proxy.isGroup) {
                            if (proxy.name !in visited)
                                queue.addLast(proxy.name)
                        } else {
                            offer(proxy.name, "${proxy.name} @ $name")
                        }
                    }
                }
                val global = queryProxyGroup("GLOBAL", ProxySort.Default)
                for (proxy in global.proxies) {
                    if (!proxy.isGroup)
                        offer(proxy.name, "${proxy.name} @ 全局")
                }
                valueList to displayList
            }
            targets = collected.first
            targetDisplays = collected.second
            listComplete = true
        } else if (profileUuid != null) {
            val parsed = withContext(Dispatchers.IO) {
                val configFile = this@DomainNodeActivity.importedDir
                    .resolve(profileUuid.toString()).resolve("config.yaml")
                if (!configFile.exists())
                    Pair(emptyList<String>(), false)
                else
                    parseConfigNodeNames(configFile.readLines())
            }
            targets = parsed.first
            targetDisplays = parsed.first
            // Disk list without providers is complete; with providers
            // it is incomplete and must never prune or mark stale.
            listComplete = parsed.second
        }

        // Non-silent prune on open (see file header): only against a
        // complete list, and always named in a toast.
        if (listComplete && profileUuid != null) {
            val known = targets.toSet()
            val pruned = rules.filter { it.target !in known }
            if (pruned.isNotEmpty()) {
                rules.removeAll(pruned.toSet())
                Toast.makeText(
                    this,
                    getString(
                        R.string.domain_node_pruned,
                        pruned.joinToString("、") { "${it.type},${it.pattern}" }
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        // Stale markers: only meaningful against a complete list.
        // Kept as the live known-set (not a snapshot of stale rules) so
        // a freshly typed unknown target is marked stale at once, not
        // only after reopening the page.
        val knownSet: Set<String> = if (listComplete) targets.toSet() else emptySet()

        Toast.makeText(this, R.string.domain_node_saved, Toast.LENGTH_LONG).show()

        defer {
            val changed = withContext(Dispatchers.IO) {
                if (profileUuid == null)
                    return@withContext false
                val changed = rules != parseDomainNodeRulesForProfile(
                    service.domainNodeRules, profileUuid
                )
                service.domainNodeRules = encodeDomainNodeRulesForProfile(
                    service.domainNodeRules, profileUuid, rules
                )
                changed
            }
            if (clashRunning && changed) {
                sendOverrideChanged()
            }
        }

        val design = DomainNodeDesign(this)

        setContentDesign(design)

        suspend fun refresh() {
            design.patchEntries(rules.map {
                DomainRow(
                    it.type, it.pattern, it.target,
                    listComplete && it.target !in knownSet
                )
            })
        }
        refresh()

        while (isActive) {
            select<Unit> {
                events.onReceive {
                }
                design.requests.onReceive {
                    when (it) {
                        is DomainNodeDesign.Request.Add -> {
                            if (profileUuid == null) {
                                Toast.makeText(
                                    this@DomainNodeActivity,
                                    R.string.not_selected,
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                showEditDialog(rules, targets, targetDisplays, null) {
                                    lifecycleScope.launch { refresh() }
                                }
                            }
                        }
                        is DomainNodeDesign.Request.Pick -> {
                            val rule = rules.getOrNull(it.index) ?: return@onReceive
                            showEditDialog(rules, targets, targetDisplays, it.index to rule) {
                                lifecycleScope.launch { refresh() }
                            }
                        }
                        is DomainNodeDesign.Request.Delete -> {
                            val rule = rules.getOrNull(it.index) ?: return@onReceive
                            AlertDialog.Builder(this@DomainNodeActivity)
                                .setMessage(R.string.domain_node_delete)
                                .setTitle("${rule.type},${rule.pattern} → ${rule.target}")
                                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                                    rules.removeAt(it.index)
                                    dialog.dismiss()
                                    lifecycleScope.launch { refresh() }
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }
                }
            }
        }
    }

    // Disk config node names: top-level `proxies:` / `proxy-groups:`
    // `name:` entries (column-0 lines are the only section boundaries,
    // mirroring ConfigurationModule's parser). Returns the names and
    // whether the list is complete (false when proxy-providers exist).
    private fun parseConfigNodeNames(lines: List<String>): Pair<List<String>, Boolean> {
        val values = ArrayList<String>()
        val seen = HashSet<String>()
        var section = ""
        var hasProviders = false
        for (raw in lines) {
            if (raw.isBlank())
                continue
            val trimmed = raw.trimStart()
            if (trimmed.startsWith("#"))
                continue
            if (!raw.startsWith(" ") && !raw.startsWith("\t")) {
                val colon = trimmed.indexOf(':')
                section = if (colon > 0) trimmed.substring(0, colon) else ""
                if (section == "proxy-providers")
                    hasProviders = true
                continue
            }
            if (section != "proxies" && section != "proxy-groups")
                continue
            if (!trimmed.startsWith("- name:"))
                continue
            var name = trimmed.removePrefix("- name:").trim()
            if (name.startsWith("\"") || name.startsWith("'"))
                name = name.trim('"', '\'').trim()
            else
                name = name.substringBefore("#").trim()
            if (name.isNotBlank() && seen.add(name))
                values.add(name)
        }
        return values to !hasProviders
    }

    private fun showEditDialog(
        rules: MutableList<DomainRule>,
        targets: List<String>,
        targetDisplays: List<String>,
        editing: Pair<Int, DomainRule>?,
        onSaved: () -> Unit,
    ) {
        val context = this@DomainNodeActivity
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val patternInput = EditText(context).apply {
            hint = getString(R.string.domain_node_pattern_hint)
            setText(editing?.second?.pattern ?: "")
        }
        layout.addView(
            patternInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val typeGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }
        val typeExact = RadioButton(context).apply {
            text = getString(R.string.domain_node_type_exact)
        }
        val typeSuffix = RadioButton(context).apply {
            text = getString(R.string.domain_node_type_suffix)
        }
        val typeKeyword = RadioButton(context).apply {
            text = getString(R.string.domain_node_type_keyword)
        }
        typeGroup.addView(typeExact)
        typeGroup.addView(typeSuffix)
        typeGroup.addView(typeKeyword)
        when (editing?.second?.type) {
            "DOMAIN" -> typeExact.isChecked = true
            "DOMAIN-KEYWORD" -> typeKeyword.isChecked = true
            else -> typeSuffix.isChecked = true
        }
        layout.addView(
            typeGroup,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val targetInput = EditText(context).apply {
            hint = getString(R.string.domain_node_target_hint)
            setText(editing?.second?.target ?: "")
        }
        layout.addView(
            targetInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        if (clashRunning && targets.isNotEmpty()) {
            val pickButton = Button(context).apply {
                text = getString(R.string.domain_node_pick_target)
            }
            pickButton.setOnClickListener {
                val current = targetInput.text.toString()
                val checked = targets.indexOf(current)
                AlertDialog.Builder(context)
                    .setTitle(R.string.domain_node_pick_target)
                    .setSingleChoiceItems(
                        targetDisplays.map { it as CharSequence }.toTypedArray(),
                        checked
                    ) { dialog, which ->
                        targetInput.setText(targets[which])
                        dialog.dismiss()
                    }
                    .show()
            }
            layout.addView(
                pickButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(
                if (editing == null) R.string.domain_node_add
                else R.string.domain_node_title
            )
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // Validate inside the button handler so a bad input keeps the
        // dialog open instead of dismissing and losing what was typed.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            var pattern = patternInput.text.toString().trim()
            val type = when {
                typeExact.isChecked -> "DOMAIN"
                typeKeyword.isChecked -> "DOMAIN-KEYWORD"
                else -> "DOMAIN-SUFFIX"
            }
            if (type == "DOMAIN-SUFFIX" && pattern.startsWith("."))
                pattern = pattern.removePrefix(".")
            val target = targetInput.text.toString().trim()
            // '|' would break the TYPE|pattern|target storage split and
            // a newline would break the staging config YAML, so both
            // are rejected here (and dropped again on read, defence in
            // depth); a comma in the target cannot be expressed in a
            // rule line at all.
            val illegal = listOf(' ', ',', '|', '\n', '\r', '\t')
            if (pattern.isBlank() || target.isBlank() ||
                illegal.any { pattern.contains(it) || target.contains(it) }
            ) {
                Toast.makeText(
                    context,
                    R.string.domain_node_invalid,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val duplicate = rules.withIndex().any { (index, rule) ->
                rule.type == type && rule.pattern == pattern &&
                    index != editing?.first
            }
            if (duplicate) {
                Toast.makeText(
                    context,
                    R.string.domain_node_exists,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val rule = DomainRule(type, pattern, target)
            if (editing == null)
                rules.add(rule)
            else
                rules[editing.first] = rule
            dialog.dismiss()
            onSaved()
        }
    }
}
// FEATURE-DOMAINNODE END
