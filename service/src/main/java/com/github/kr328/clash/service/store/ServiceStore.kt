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

    // FEATURE-DIAG2 BEGIN: foreground-return death detection marker.
    // Set true after a foreground-return death record is written; cleared
    // whenever the :background service is found alive or shouldRun is
    // false. Prevents one record per foreground return while the service
    // stays dead and the user has not restarted it.
    var foregroundDeathReported by store.boolean(
        key = "foreground_death_reported",
        defaultValue = false
    )
    // FEATURE-DIAG2 END



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

// FEATURE-2D BEGIN: app-node rules are stored PER PROFILE, not globally.
// Format: "<profileUuid>=<package>=<target>" (split with limit 3, so a
// target containing '=' survives; a UUID and a package name never
// contain '='). The old global format "<package>=<target>" is legacy:
// it is ignored on read and dropped on the next save, because a rule
// recorded under one subscription must never be injected into another
// subscription's config -- mihomo fails the whole load with
// "proxy [...] not found" and the service stops (reported 2026-10-29:
// AlipayHK -> instabox-2 survived a switch to a profile without that
// node and broke the load). Switching profiles therefore shows that
// profile's own picks (empty until the user picks for it) instead of
// carrying stale picks over.
fun parseAppNodeRulesForProfile(raw: Set<String>, uuid: java.util.UUID): Map<String, String> {
    val prefix = uuid.toString() + "="
    val result = LinkedHashMap<String, String>()
    for (entry in raw) {
        if (!entry.startsWith(prefix))
            continue
        val rest = entry.substring(prefix.length)
        val sep = rest.indexOf('=')
        if (sep <= 0 || sep == rest.length - 1)
            continue
        result[rest.substring(0, sep)] = rest.substring(sep + 1)
    }
    return result
}

fun encodeAppNodeRulesForProfile(
    raw: Set<String>,
    uuid: java.util.UUID,
    rules: Map<String, String>,
): Set<String> {
    val prefix = uuid.toString() + "="
    // Keep only new-format entries of OTHER profiles; legacy entries
    // (fewer than 3 parts, or a non-UUID first part) are dropped here.
    val kept = raw.filter { entry ->
        val parts = entry.split("=", limit = 3)
        parts.size == 3 && runCatching { java.util.UUID.fromString(parts[0]) }.isSuccess &&
            !entry.startsWith(prefix)
    }
    return (kept + rules.entries
        .filter { it.key.isNotBlank() && it.value.isNotBlank() }
        .map { "$prefix${it.key}=${it.value}" }).toSet()
}
// FEATURE-2D END

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

