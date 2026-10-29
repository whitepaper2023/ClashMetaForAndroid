// FEATURE-2 BEGIN (whole file): per-app designated node page. The user
// picks an app and then a node/group in a dialog; nothing here edits YAML
// by hand. The pick is stored in ServiceStore.appNodeRules and turned into
// PROCESS-NAME rules by ConfigurationModule at load time. Picks are saved
// and applied (via sendOverrideChanged) when leaving this page, mirroring
// BlockedAppsActivity. FEATURE-1 REJECT rules always win over these rules:
// see ConfigurationModule.buildBlockRuleLines + buildAppNodeRuleLines order.
package com.github.kr328.clash

import android.Manifest.permission.INTERNET
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.design.AppNodeDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.toAppInfo
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.store.encodeAppNodeRulesForProfile
import com.github.kr328.clash.service.store.parseAppNodeRulesForProfile
import com.github.kr328.clash.service.util.sendOverrideChanged
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class AppNodeActivity : BaseActivity<AppNodeDesign>() {
    override suspend fun main() {
        val service = ServiceStore(this)

        // FEATURE-2D: rules are per profile. Without a selected profile
        // there is nothing to show or save; the page still opens (and
        // while stopped, clearing still works on the empty map).
        val profileUuid = withContext(Dispatchers.IO) { service.activeProfile }
        val rules = withContext(Dispatchers.IO) {
            if (profileUuid != null)
                parseAppNodeRulesForProfile(service.appNodeRules, profileUuid).toMutableMap()
            else
                mutableMapOf()
        }
        val blocked = withContext(Dispatchers.IO) {
            service.blockedPackages
        }

        // Live node/group targets only exist while Clash is running. While
        // running, also prune rules pointing at targets that no longer
        // exist (e.g. after a subscription change); the prune is saved by
        // the defer block below when leaving the page.
        // FEATURE-2B BEGIN: recursive cross-group expansion. The old code
        // only took the top-level groups' direct non-group members, so
        // nodes nested inside a subgroup (a group inside a group) never
        // appeared. Now every group is walked breadth-first: the group
        // name itself is a target, subgroup members are queued, and
        // visited guards against group cycles. A name containing ','
        // can never be expressed in a comma-separated PROCESS-NAME rule
        // line: never offer it. Displays run parallel to values so the
        // picker shows where each node belongs; first occurrence wins
        // for duplicate node names.
        var targets: List<String> = emptyList()
        var targetDisplays: List<String> = emptyList()
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
                // GLOBAL holds the direct proxies in global mode; only
                // its non-group members are extra targets here, labelled
                // "全局" (nodes already seen in a group keep that group).
                val global = queryProxyGroup("GLOBAL", ProxySort.Default)
                for (proxy in global.proxies) {
                    if (!proxy.isGroup)
                        offer(proxy.name, "${proxy.name} @ 全局")
                }
                valueList to displayList
            }
            targets = collected.first
            targetDisplays = collected.second
            val targetSet = targets.toSet()
            rules.entries.removeAll { it.value !in targetSet }
        }
        // FEATURE-2B END

        Toast.makeText(this, "选择后退出本页生效", Toast.LENGTH_LONG).show()

        defer {
            val changed = withContext(Dispatchers.IO) {
                if (profileUuid == null)
                    return@withContext false
                val changed = rules != parseAppNodeRulesForProfile(service.appNodeRules, profileUuid)
                service.appNodeRules = encodeAppNodeRulesForProfile(service.appNodeRules, profileUuid, rules)
                changed
            }
            if (clashRunning && changed) {
                sendOverrideChanged()
            }
        }

        val design = AppNodeDesign(this)

        setContentDesign(design)

        val apps = loadApps(rules)
        design.patchApps(apps, rules, blocked)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        is AppNodeDesign.Request.Pick -> {
                            val pkg = it.packageName
                            if (!clashRunning) {
                                if (rules.remove(pkg) != null) {
                                    Toast.makeText(
                                        this@AppNodeActivity,
                                        R.string.app_node_clear,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    design.patchApps(apps, rules, blocked)
                                } else {
                                    Toast.makeText(
                                        this@AppNodeActivity,
                                        "请先启动 Clash 才能选择节点",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } else {
                                showNodePicker(design, apps, rules, blocked, targets, targetDisplays, pkg)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun showNodePicker(
        design: AppNodeDesign,
        apps: List<AppInfo>,
        rules: MutableMap<String, String>,
        blocked: Set<String>,
        targets: List<String>,
        targetDisplays: List<String>,
        pkg: String,
    ) {
        val label = apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
        val clearText = getString(R.string.app_node_clear)
        val items = ArrayList<String>()
        items.add(clearText)
        // FEATURE-2B: show the display strings, but index back into the
        // value list: displays and values are built in parallel, and the
        // clear row stays at index 0, so checked/which map 1:1.
        items.addAll(targetDisplays)
        val current = rules[pkg]
        val checked = if (current == null) 0 else targets.indexOf(current) + 1

        // The dialog is asynchronous: the row refresh MUST happen inside
        // the click callback, not after show() (which returns immediately).
        withContext(Dispatchers.Main) {
            AlertDialog.Builder(this@AppNodeActivity)
                .setTitle(label)
                .setSingleChoiceItems(
                    items.map { it as CharSequence }.toTypedArray(),
                    checked
                ) { dialog, which ->
                    if (which == 0)
                        rules.remove(pkg)
                    else
                        rules[pkg] = targets[which - 1]
                    dialog.dismiss()
                    launch {
                        design.patchApps(apps, rules, blocked)
                    }
                }
                .show()
        }
    }

    private suspend fun loadApps(rules: Map<String, String>): List<AppInfo> =
        withContext(Dispatchers.IO) {
            val pm = packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

            packages.asSequence()
                .filter {
                    it.packageName != packageName
                }
                .filter {
                    it.applicationInfo != null
                }
                .filter {
                    it.requestedPermissions?.contains(INTERNET) == true || it.applicationInfo!!.uid < android.os.Process.FIRST_APPLICATION_UID
                }
                .map {
                    it.toAppInfo(pm)
                }
                .sortedWith(
                    compareByDescending<AppInfo> { it.packageName in rules }
                        .thenBy { it.label }
                )
                .toList()
        }
}
// FEATURE-2 END
