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