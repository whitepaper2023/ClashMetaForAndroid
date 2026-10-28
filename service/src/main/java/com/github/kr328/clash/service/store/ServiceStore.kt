package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.model.AccessControlMode
import java.util.*

class ServiceStore(context: Context) {
    private val store = Store(
        PreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
    )

    var activeProfile: UUID? by store.typedString(
        key = "active_profile",
        from = { if (it.isBlank()) null else UUID.fromString(it) },
        to = { it?.toString() ?: "" }
    )

    var bypassPrivateNetwork: Boolean by store.boolean(
        key = "bypass_private_network",
        defaultValue = true
    )

    var accessControlMode: AccessControlMode by store.enum(
        key = "access_control_mode",
        defaultValue = AccessControlMode.AcceptAll,
        values = AccessControlMode.values()
    )

    var accessControlPackages by store.stringSet(
        key = "access_control_packages",
        defaultValue = emptySet()
    )

    // FEATURE-1 BEGIN: packages whose traffic is REJECTed via
    // auto-generated PROCESS-NAME rules at profile load time.
    var blockedPackages by store.stringSet(
        key = "blocked_packages",
        defaultValue = emptySet()
    )
    // FEATURE-1 END

    // FEATURE-2 BEGIN: per-app designated node/group. Stored as raw strings
    // "packageName=nodeOrGroupName" (split on the FIRST '=': node/group
    // names may themselves contain '='). The map helpers below are shared
    // by the UI (AppNodeActivity) and the loader (ConfigurationModule).
    var appNodeRules by store.stringSet(
        key = "app_node_rules",
        defaultValue = emptySet()
    )
    // FEATURE-2 END

    // FEATURE-3 BEGIN: fault auto-restart marker. Set true on every    // explicit start; cleared only on a manual stop. The service is
    // START_STICKY while shouldRun is true (system kill -> recreated) and
    // START_NOT_STICKY otherwise (manual stop -> never comes back).
    // lastAutoRestartAt bounds fault restarts to one per 60 s so a
    // persistent fault (e.g. VPN permission revoked) cannot hot-loop.
    var shouldRun by store.boolean(
        key = "should_run",
        defaultValue = false
    )
    var lastAutoRestartAt by store.long(
        key = "last_auto_restart_at",
        defaultValue = 0L
    )
    // FEATURE-3 END

    // FEATURE-4 BEGIN: cross-provider fastest group. Off by default, so
    // baseline configs load untouched. Takes effect at next profile load
    // (the NetworkSettings toggle is only editable while stopped).
    var globalAutoEnabled by store.boolean(
        key = "global_auto_enabled",
        defaultValue = false
    )
    // FEATURE-4 END

    // FEATURE-4B BEGIN: manually picked subscriptions for the global-auto
    // group (GlobalAutoActivity). Empty means "no manual pick": the old
    // globalAutoEnabled switch then decides (on = all providers, off =
    // no group). A non-empty set narrows the group to the intersection
    // with the providers present in the active config; names that are no
    // longer in the config are pruned when the page is opened.
    var globalAutoProviders by store.stringSet(
        key = "global_auto_providers",
        defaultValue = emptySet()
    )
    // FEATURE-4B END

    // FEATURE-5 BEGIN: per-profile proxy filter for the Proxy page.
    // Stored as one entry per (profile, node) pair, "<uuid>=<nodeName>";
    // a profile with no entries means "no filter, show all" (see
    // parseProxyFilterRules / encodeProxyFilterRules below). This is a
    // display-only filter: it never edits the config, it only hides rows
    // in ProxyActivity.
    var proxyFilterRules by store.stringSet(
        key = "proxy_filter_rules",
        defaultValue = emptySet()
    )
    // FEATURE-5 END

    var dnsHijacking by store.boolean(
        key = "dns_hijacking",
        defaultValue = true
    )

    var systemProxy by store.boolean(
        key = "system_proxy",
        defaultValue = true
    )

    var allowBypass by store.boolean(
        key = "allow_bypass",
        defaultValue = true
    )

    var allowIpv6 by store.boolean(
        key = "allow_ipv6",
        defaultValue = false
    )

    var tunStackMode by store.string(
        key = "tun_stack_mode",
        defaultValue = "mips"
    )

    var dynamicNotification by store.boolean(
        key = "dynamic_notification",
        defaultValue = true
    )
}

// FEATURE-2 BEGIN: encode/decode for appNodeRules entries. A package name
// can never contain '=' (Android package names are [A-Za-z0-9._]), so the
// first '=' is a safe separator even if the node/group name contains '='.
fun parseAppNodeRules(raw: Set<String>): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (entry in raw) {
        val sep = entry.indexOf('=')
        if (sep <= 0 || sep == entry.length - 1)
            continue
        result[entry.substring(0, sep)] = entry.substring(sep + 1)
    }
    return result
}

fun encodeAppNodeRules(rules: Map<String, String>): Set<String> {
    return rules.entries
        .filter { it.key.isNotBlank() && it.value.isNotBlank() }
        .map { "${it.key}=${it.value}" }
        .toSet()
}
// FEATURE-2 END

// FEATURE-5 BEGIN: encode/decode for proxyFilterRules. Format mirrors
// appNodeRules ("<uuid>=<nodeName>"), split on the FIRST '=' only: a UUID
// never contains '=', but a node name may, so everything after the first
// '=' is the node name. One entry per (profile, node) pair, so a profile
// maps to a SET of node names. Blank keys, blank node names and entries
// without '=' are dropped. An absent/empty set for a profile means "no
// filter for that profile" (ProxyActivity shows all rows).
fun parseProxyFilterRules(raw: Set<String>): Map<String, Set<String>> {
    val result = LinkedHashMap<String, MutableSet<String>>()
    for (entry in raw) {
        val sep = entry.indexOf('=')
        if (sep <= 0 || sep == entry.length - 1)
            continue
        val uuid = entry.substring(0, sep)
        val node = entry.substring(sep + 1)
        if (uuid.isBlank() || node.isBlank())
            continue
        result.getOrPut(uuid) { LinkedHashSet() }.add(node)
    }
    return result
}

fun encodeProxyFilterRules(rules: Map<String, Set<String>>): Set<String> {
    val result = LinkedHashSet<String>()
    for ((uuid, nodes) in rules) {
        if (uuid.isBlank())
            continue
        for (node in nodes) {
            if (node.isNotBlank())
                result.add("$uuid=$node")
        }
    }
    return result
}
// FEATURE-5 END

// FEATURE-4B BEGIN: proxy-provider names, moved here verbatim from
// ConfigurationModule so the loader and GlobalAutoActivity share one
// definition (same package, no import needed inside service.store).
// Provider names are the exactly-2-space-indented keys directly under
// the top-level `proxy-providers:` key. Deeper-indented lines are the
// providers' own options and must be SKIPPED (not break on: breaking
// at the first option line would collect only the first provider).
// The scan ends only at the next column-0 line (the next top-level
// key) or end of file. A flow-style `proxy-providers: {...}` line has
// no such following lines and yields an empty list.
fun parseProviderNames(lines: List<String>): List<String> {
    val start = lines.indexOfFirst {
        it.startsWith("proxy-providers:") && it.removePrefix("proxy-providers:").trim().let { rest ->
            rest.isEmpty() || rest.startsWith("#")
        }
    }
    if (start < 0)
        return emptyList()

    val result = mutableListOf<String>()
    for (i in start + 1 until lines.size) {
        val line = lines[i]
        if (line.isBlank())
            continue
        if (!line.startsWith(" "))
            break
        val trimmed = line.trimStart()
        if (trimmed.startsWith("#"))
            continue
        val indent = line.length - trimmed.length
        if (indent != 2)
            continue
        val colon = trimmed.indexOf(':')
        if (colon <= 0)
            continue
        val name = trimmed.substring(0, colon).trim().trim('"', '\'')
        if (name.isNotEmpty())
            result.add(name)
    }
    return result
}
// FEATURE-4B END