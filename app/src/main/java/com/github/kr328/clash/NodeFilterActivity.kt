// FEATURE-5 BEGIN (whole file): per-profile node filter picker. The user
// ticks the nodes of ONE profile that the Proxy page should show; the
// pick is stored per profile UUID in ServiceStore.proxyFilterRules and is
// display-only (it never edits the config). An empty pick means "no
// filter, show all" for that profile. Picks are saved when leaving this
// page, mirroring AppNodeActivity's save-on-leave; no config reload is
// needed, because
// ProxyActivity applies the filter when it reloads a group.
// Node list sources, in order:
// (a) this profile is the active profile AND Clash is running: live core,
//     breadth-first over groups like AppNodeActivity's FEATURE-2B walk
//     (visited guards against group cycles), leaf nodes only, displayed
//     as "node @ group". Group names themselves are NOT filter targets.
//     Unlike AppNode picks, a node name containing ',' is fine here: the
//     filter compares proxy names for equality, it never builds a
//     comma-separated PROCESS-NAME rule line.
// (b) otherwise: the profile's importedDir/<uuid>/config.yaml on disk:
//     `proxies:` section "- name:" entries plus each proxy-group's
//     `proxies:` member list. Provider-backed nodes cannot be listed
//     from disk, so when the config has proxy-providers the page toasts
//     that subscription nodes need this profile started to be listed, and
//     only the nodes already parseable from disk are offered. Nothing is
//     downloaded and no profile is activated to build this list.
// A missing config.yaml or an empty node list toasts and shows an empty
// list; the page never crashes on those paths.
package com.github.kr328.clash

import android.widget.Toast
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.design.NodeFilterDesign
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.store.encodeProxyFilterRules
import com.github.kr328.clash.service.store.parseProxyFilterRules
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class NodeFilterActivity : BaseActivity<NodeFilterDesign>() {
    override suspend fun main() {
        val uuid = intent.uuid ?: return finish()
        val uuidText = uuid.toString()
        val service = ServiceStore(this)

        val stored = withContext(Dispatchers.IO) {
            parseProxyFilterRules(service.proxyFilterRules)
        }
        val selected = withContext(Dispatchers.IO) {
            (stored[uuidText] ?: emptySet()).toMutableSet()
        }
        val activeUuid = withContext(Dispatchers.IO) {
            service.activeProfile
        }

        // Node values and their display strings, built in parallel (the
        // display may carry " @ group", the value is the bare node name).
        val nodeValues: List<String>
        val nodeDisplays: List<String>
        var hasProviders = false
        if (uuid == activeUuid && clashRunning) {
            val live = withClash {
                val valueList = ArrayList<String>()
                val displayList = ArrayList<String>()
                val seen = HashSet<String>()
                fun offer(value: String, display: String) {
                    if (seen.add(value)) {
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
                    val group = queryProxyGroup(name, ProxySort.Default)
                    for (proxy in group.proxies) {
                        if (proxy.isGroup) {
                            if (proxy.name !in visited)
                                queue.addLast(proxy.name)
                        } else {
                            offer(proxy.name, "${proxy.name} @ $name")
                        }
                    }
                }
                // GLOBAL holds the direct proxies in global mode; only
                // its non-group members are extra targets here.
                val global = queryProxyGroup("GLOBAL", ProxySort.Default)
                for (proxy in global.proxies) {
                    if (!proxy.isGroup)
                        offer(proxy.name, "${proxy.name} @ 全局")
                }
                valueList to displayList
            }
            nodeValues = live.first
            nodeDisplays = live.second
        } else {
            val parsed = withContext(Dispatchers.IO) {
                val configFile = this@NodeFilterActivity.importedDir
                    .resolve(uuidText).resolve("config.yaml")
                if (!configFile.exists()) {
                    Triple(emptyList(), emptyList(), false)
                } else {
                    parseConfigNodes(configFile.readLines())
                }
            }
            nodeValues = parsed.first
            nodeDisplays = parsed.second
            hasProviders = parsed.third
        }

        if (nodeValues.isEmpty()) {
            Toast.makeText(
                this,
                "该配置暂无可筛选的节点",
                Toast.LENGTH_LONG
            ).show()
            if (hasProviders) {
                Toast.makeText(
                    this,
                    "订阅节点需启动该配置后才能列出",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            // AUDIT FIX: prune only when the list is complete. A disk
            // list for a provider-backed config is INCOMPLETE (provider
            // nodes are missing until this profile runs), so pruning
            // against it would silently delete the user's saved picks
            // for those provider nodes. Live lists and provider-free
            // disk lists are complete and safe to prune against. With
            // an empty list the stored pick is left untouched (the
            // config may simply be unreadable right now).
            val listIsComplete = (uuid == activeUuid && clashRunning) || !hasProviders
            if (listIsComplete)
                selected.retainAll(nodeValues.toSet())
            Toast.makeText(
                this,
                "不勾选任何节点 = 不筛选，显示全部；勾选后退出本页生效",
                Toast.LENGTH_LONG
            ).show()
            if (hasProviders) {
                Toast.makeText(
                    this,
                    "订阅节点需启动该配置后才能列出",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Deliberately no sendOverrideChanged here: the filter is display
        // only, ProxyActivity reads it on its next group Reload.
        defer {
            withContext(Dispatchers.IO) {
                val updated = stored.toMutableMap()
                updated[uuidText] = selected.toSet()
                service.proxyFilterRules = encodeProxyFilterRules(updated)
            }
        }

        val design = NodeFilterDesign(this)

        setContentDesign(design)

        design.patchNodes(nodeValues, nodeDisplays, selected)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        is NodeFilterDesign.Request.Toggle -> {
                            if (it.nodeName in selected)
                                selected.remove(it.nodeName)
                            else
                                selected.add(it.nodeName)
                            design.patchNodes(nodeValues, nodeDisplays, selected)
                        }
                    }
                }
            }
        }
    }

    // Text-based config node parser. Returns (node values, display
    // strings, hasProxyProviders). Values come from the `proxies:`
    // section "- name:" entries (display = bare name) and from each
    // proxy-group's `proxies:` member list (display = "name @ group").
    // A group header "- name:" is a group name, never a node. Comments
    // and blank lines never reset the group-proxies state; any other
    // group field line (url, interval, use, filter, ...) ends it.
    private fun parseConfigNodes(lines: List<String>): Triple<List<String>, List<String>, Boolean> {
        val values = ArrayList<String>()
        val displays = ArrayList<String>()
        val seen = HashSet<String>()
        fun offer(value: String, display: String) {
            if (value.isNotBlank() && seen.add(value)) {
                values.add(value)
                displays.add(display)
            }
        }
        fun stripQuotes(raw: String): String {
            val text = raw.trim()
            return if (text.length >= 2 &&
                ((text.startsWith("\"") && text.endsWith("\"")) ||
                    (text.startsWith("'") && text.endsWith("'")))
            ) {
                text.substring(1, text.length - 1)
            } else {
                text
            }
        }

        var section = ""
        var hasProviders = false
        var currentGroup = ""
        var inGroupProxies = false
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
                currentGroup = ""
                inGroupProxies = false
                continue
            }
            when (section) {
                "proxies" -> {
                    if (trimmed.startsWith("- name:")) {
                        val name = stripQuotes(trimmed.removePrefix("- name:"))
                        offer(name, name)
                    }
                }
                "proxy-groups" -> {
                    if (trimmed.startsWith("- name:")) {
                        currentGroup = stripQuotes(trimmed.removePrefix("- name:"))
                        inGroupProxies = false
                    } else if (trimmed.startsWith("proxies:")) {
                        val rest = trimmed.removePrefix("proxies:").trim()
                        if (rest.startsWith("[")) {
                            // One-line flow list: members live in brackets.
                            val inner = rest.removePrefix("[").substringBefore("]").trim()
                            if (inner.isNotEmpty()) {
                                for (part in inner.split(",")) {
                                    val name = stripQuotes(part)
                                    offer(name, "$name @ $currentGroup")
                                }
                            }
                            inGroupProxies = false
                        } else {
                            inGroupProxies = true
                        }
                    } else if (trimmed.startsWith("- ") && inGroupProxies) {
                        val name = stripQuotes(trimmed.removePrefix("- "))
                        offer(name, "$name @ $currentGroup")
                    } else if (trimmed.contains(":") && !trimmed.startsWith("- ")) {
                        inGroupProxies = false
                    }
                }
            }
        }
        return Triple(values, displays, hasProviders)
    }
}
// FEATURE-5 END
