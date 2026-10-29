package com.github.kr328.clash

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.store.PastedProxyEntry
import com.github.kr328.clash.service.store.encodePastedProxies
import com.github.kr328.clash.service.store.parsePastedProxies
import com.github.kr328.clash.service.store.parseProxyFilterRules
import com.github.kr328.clash.service.util.sendOverrideChanged
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ProxyActivity : BaseActivity<ProxyDesign>() {
    override suspend fun main() {
        val mode = withClash { queryOverride(Clash.OverrideSlot.Session).mode }
        val names = withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) }
        val states = List(names.size) { ProxyState("?") }
        val unorderedStates = names.indices.map { names[it] to states[it] }.toMap()
        val reloadLock = Semaphore(10)

        val design = ProxyDesign(
            this,
            mode,
            names,
            uiStore
        )

        setContentDesign(design)

        design.requests.send(ProxyDesign.Request.ReloadAll)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        // FEATURE-5 AUDIT: returning to an already-open
                        // Proxy page (e.g. back from the filter picker)
                        // must re-apply the filter; otherwise the page
                        // keeps showing pre-filter rows until re-entered.
                        // ReloadAll is idempotent (re-queries groups),
                        // so a duplicate reload on first start is harmless.
                        Event.ActivityStart -> {
                            design.requests.send(ProxyDesign.Request.ReloadAll)
                        }
                        Event.ProfileLoaded -> {
                            val newNames = withClash {
                                queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
                            }

                            if (newNames != names) {
                                startActivity(ProxyActivity::class.intent)

                                finish()
                            } else {
                                // FEATURE-PASTE: a paste/delete only changes
                                // group MEMBERSHIP, not group names, so the
                                // page must re-query rows after the service
                                // reloads the profile (ReloadAll is
                                // idempotent, so the duplicate on first
                                // start is harmless).
                                design.requests.send(ProxyDesign.Request.ReloadAll)
                            }
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProxyDesign.Request.ReLaunch -> {
                            startActivity(ProxyActivity::class.intent)

                            finish()
                        }
                        ProxyDesign.Request.ReloadAll -> {
                            names.indices.forEach { idx ->
                                design.requests.trySend(ProxyDesign.Request.Reload(idx))
                            }
                        }
                        is ProxyDesign.Request.Reload -> {
                            launch {
                                val group = reloadLock.withPermit {
                                    withClash {
                                        queryProxyGroup(names[it.index], uiStore.proxySort)
                                    }
                                }
                                val state = states[it.index]

                                state.now = group.now

                                // FEATURE-5 BEGIN: per-profile node filter.
                                // The active profile's filter set, if any,
                                // hides leaf rows whose names are not in
                                // it. Group rows are never filtered out
                                // (a hidden group would hide its kept
                                // members too); an empty set, a null
                                // active profile, or no entry for this
                                // profile all mean "show all". Select /
                                // TestProxy / UrlTest branches are untouched.
                                val filter = ServiceStore(this@ProxyActivity).let { store ->
                                    store.activeProfile?.toString()?.let { uuidText ->
                                        parseProxyFilterRules(store.proxyFilterRules)[uuidText]
                                    } ?: emptySet()
                                }
                                val shown = if (filter.isEmpty()) {
                                    group.proxies
                                } else {
                                    group.proxies.filter { proxy ->
                                        proxy.isGroup || proxy.name in filter
                                    }
                                }
                                // FEATURE-5 END

                                design.updateGroup(
                                    it.index,
                                    shown,
                                    group.type == "Selector",
                                    state,
                                    unorderedStates
                                )
                            }
                        }
                        is ProxyDesign.Request.Select -> {
                            withClash {
                                patchSelector(names[it.index], it.name)

                                states[it.index].now = it.name
                            }

                            design.requestRedrawVisible()
                        }
                        is ProxyDesign.Request.UrlTest -> {
                            launch {
                                withClash {
                                    healthCheck(names[it.index])
                                }

                                design.requests.send(ProxyDesign.Request.Reload(it.index))
                            }
                        }
                        // FEATURE-4: long-press test. A group row reuses the
                        // group health check on the TAPPED group (not the
                        // page's group: rows can be nested groups); a single
                        // proxy gets the new single test (which records a
                        // history entry, so the following Reload shows
                        // 未测 -> Nms or -1).
                        is ProxyDesign.Request.TestProxy -> {
                            launch {
                                withClash {
                                    if (it.isGroup)
                                        healthCheck(it.name)
                                    else
                                        testProxy(it.name)
                                }

                                design.requests.send(ProxyDesign.Request.Reload(it.index))
                            }
                        }
                        is ProxyDesign.Request.PatchMode -> {
                            design.showModeSwitchTips()

                            withClash {
                                val o = queryOverride(Clash.OverrideSlot.Session)

                                o.mode = it.mode

                                patchOverride(Clash.OverrideSlot.Session, o)
                            }
                        }
                        // FEATURE-PASTE: paste-a-node dialog.
                        is ProxyDesign.Request.PasteProxy -> {
                            showPasteDialog()
                        }
                    }
                }
            }
        }
    }

    // FEATURE-PASTE: paste-a-node dialog. Parses the link (see
    // PasteProxyParser), renames on collision with live names, stores the
    // YAML block per profile, and signals the service to reload: the
    // loader injects the block into the staging config, so the node
    // appears in every Selector group and survives subscription updates.
    // The dialog also lists this profile's pasted nodes with a delete
    // button each. Parse errors are shown inline and keep the dialog
    // open; the positive button is wired manually for exactly that
    // reason (a default positive button would dismiss on any tap).
    // Rows refresh through the ProfileLoaded -> ReloadAll path after the
    // service reloads, so no direct design handle is needed here.
    private fun showPasteDialog() {
        val context = this@ProxyActivity
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val input = EditText(context).apply {
            hint = getString(DesignR.string.paste_proxy_hint)
        }
        layout.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val error = TextView(context).apply {
            setTextColor(Color.RED)
            visibility = View.GONE
        }
        layout.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val clipboardButton = Button(context).apply {
            text = getString(DesignR.string.paste_from_clipboard)
        }
        clipboardButton.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!text.isNullOrBlank()) {
                input.setText(text)
                error.visibility = View.GONE
            } else {
                Toast.makeText(
                    context,
                    DesignR.string.paste_clipboard_empty,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        layout.addView(
            clipboardButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val listTitle = TextView(context).apply {
            text = getString(DesignR.string.paste_existing)
            setPadding(0, dp(16), 0, dp(4))
        }
        layout.addView(listTitle)
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        layout.addView(listContainer)

        fun refreshList() {
            listContainer.removeAllViews()
            val store = ServiceStore(context)
            val uuid = store.activeProfile
            val entries = uuid?.let { parsePastedProxies(store.pastedProxies, it) }.orEmpty()
            if (entries.isEmpty()) {
                listContainer.addView(TextView(context).apply {
                    text = getString(DesignR.string.paste_none)
                })
                return
            }
            for (entry in entries) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                row.addView(
                    TextView(context).apply { text = entry.name },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                val del = Button(context).apply {
                    text = getString(DesignR.string.paste_delete)
                }
                del.setOnClickListener {
                    val s = ServiceStore(context)
                    val u = s.activeProfile ?: return@setOnClickListener
                    val remaining = parsePastedProxies(s.pastedProxies, u)
                        .filter { it.name != entry.name }
                    s.pastedProxies = encodePastedProxies(s.pastedProxies, u, remaining)
                    sendOverrideChanged()
                    Toast.makeText(
                        context,
                        getString(DesignR.string.paste_deleted, entry.name),
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshList()
                }
                row.addView(del)
                listContainer.addView(row)
            }
        }
        refreshList()

        val dialog = AlertDialog.Builder(context)
            .setTitle(DesignR.string.paste_proxy)
            .setView(layout)
            .setPositiveButton(DesignR.string.paste_add, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val link = input.text.toString()
            if (link.isBlank()) {
                error.text = getString(DesignR.string.paste_empty)
                error.visibility = View.VISIBLE
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val store = ServiceStore(context)
                val uuid = store.activeProfile
                if (uuid == null) {
                    error.text = getString(DesignR.string.paste_no_profile)
                    error.visibility = View.VISIBLE
                    return@launch
                }
                // Live names for collision rename: group names plus every
                // member of every group (the pasted node must not shadow
                // or duplicate any of them).
                val taken = HashSet<String>()
                try {
                    withClash {
                        val groupNames = queryProxyGroupNames(false)
                        taken.addAll(groupNames)
                        for (groupName in groupNames) {
                            val group = queryProxyGroup(groupName, uiStore.proxySort)
                            for (proxy in group.proxies)
                                taken.add(proxy.name)
                        }
                    }
                } catch (_: Exception) {
                    // Live names are best-effort: without them the parser
                    // still adds the node (the loader tolerates it).
                }
                val parsed = try {
                    PasteProxyParser.parse(link, taken)
                } catch (e: PasteParseException) {
                    error.text = e.message
                    error.visibility = View.VISIBLE
                    return@launch
                }
                val current = parsePastedProxies(store.pastedProxies, uuid)
                store.pastedProxies = encodePastedProxies(
                    store.pastedProxies,
                    uuid,
                    current + PastedProxyEntry(parsed.name, parsed.yamlLines)
                )
                sendOverrideChanged()
                Toast.makeText(
                    context,
                    getString(DesignR.string.paste_added, parsed.name),
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
        }
    }
}
