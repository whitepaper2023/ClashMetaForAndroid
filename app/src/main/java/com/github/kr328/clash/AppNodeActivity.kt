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
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.toAppInfo
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.store.encodeAppNodeRules
import com.github.kr328.clash.service.store.parseAppNodeRules
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

        val rules = withContext(Dispatchers.IO) {
            parseAppNodeRules(service.appNodeRules).toMutableMap()
        }
        val blocked = withContext(Dispatchers.IO) {
            service.blockedPackages
        }

        // Live node/group targets only exist while Clash is running. While
        // running, also prune rules pointing at targets that no longer
        // exist (e.g. after a subscription change); the prune is saved by
        // the defer block below when leaving the page.
        var targets: List<String> = emptyList()
        if (clashRunning) {
            targets = withClash {
                val names = queryProxyGroupNames(false)
                    .filter { it != "GLOBAL" }
                val result = LinkedHashSet<String>()
                for (name in names) {
                    // A name containing ',' cannot be expressed in a
                    // comma-separated PROCESS-NAME rule line: never offer it.
                    if (',' !in name)
                        result.add(name)
                    val group = queryProxyGroup(name, ProxySort.Default)
                    for (proxy in group.proxies) {
                        if (!proxy.isGroup && ',' !in proxy.name)
                            result.add(proxy.name)
                    }
                }
                result.toList()
            }
            val targetSet = targets.toSet()
            rules.entries.removeAll { it.value !in targetSet }
        }

        Toast.makeText(this, "选择后退出本页生效", Toast.LENGTH_LONG).show()

        defer {
            val changed = withContext(Dispatchers.IO) {
                val changed = rules != parseAppNodeRules(service.appNodeRules)
                service.appNodeRules = encodeAppNodeRules(rules)
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
                                showNodePicker(design, apps, rules, blocked, targets, pkg)
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
        pkg: String,
    ) {
        val label = apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
        val clearText = getString(R.string.app_node_clear)
        val items = ArrayList<String>()
        items.add(clearText)
        items.addAll(targets)
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
