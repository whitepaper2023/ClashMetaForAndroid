package com.github.kr328.clash

import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.core.bridge.*
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.service.remote.IClashManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import com.github.kr328.clash.design.R as DesignR

class MainActivity : BaseActivity<MainDesign>() {
    // FEATURE-CURNODE: cached first-Selector group name, so the 1-second
    // ticker only queries one group per tick and rescans only when the
    // cached group stops being a Selector (e.g. after a profile switch).
    private var currentNodeGroup: String? = null

    // FEATURE-EXITIP: egress-IP pieces on the running card.
    // FEATURE-RUNCARD-V2 splits the old single line in two: exitIpType
    // is the datacenter/residential word shown after the node name,
    // exitIpAddr is "IP Country/City" on its own line. Either is null
    // when its piece is not shown. Refresh triggers: node change, a
    // failed lookup retried after 60 s, and a successful lookup refreshed
    // after 10 min. The lookup itself runs in lifecycleScope so the 1 s
    // ticker never stalls on the 5 s network timeouts.
    private var exitIpType: String? = null
    private var exitIpAddr: String? = null
    private var exitIpNode: String? = null
    private var exitIpLastAttemptMs: Long = 0L
    private var exitIpLastSuccessMs: Long = 0L
    private var exitIpFetching: Boolean = false

    override suspend fun main() {
        val design = MainDesign(this)

        setContentDesign(design)

        design.fetch()

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning)
                                stopClashService()
                            else
                                design.startClash()
                        }
                        MainDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        // FEATURE-2B BEGIN: homepage entry for per-app nodes.
                        MainDesign.Request.OpenAppNodes ->
                            startActivity(AppNodeActivity::class.intent)
                        // FEATURE-2B END
                        // FEATURE-1C BEGIN: homepage entry for app blocklist.
                        MainDesign.Request.OpenBlockedApps ->
                            startActivity(BlockedAppsActivity::class.intent)
                        // FEATURE-1C END
                        MainDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()
                    }
                }
            }
        }
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }

        setMode(state.mode)
        setHasProviders(providers.isNotEmpty())

        withProfile {
            setProfileName(queryActive()?.name)
        }
    }

    private suspend fun MainDesign.fetchTraffic() {
        val node = withClash {
            setForwarded(queryTrafficTotal())
            resolveCurrentNode().also { setCurrentNode(it) }
        }
        refreshExitIpIfNeeded(node)
    }

    // FEATURE-EXITIP: decides whether the egress-IP line needs a (re)fetch
    // and launches it. The app's own traffic always goes through the
    // tunnel — TunService never disallows its own package, in any
    // access-control mode — so the lookup returns the node's egress IP,
    // never the phone's real IP. Direct mode has no node, so the line is
    // hidden there: a direct real IP is never presented as an exit IP.
    private fun MainDesign.refreshExitIpIfNeeded(node: String?) {
        if (node == null) {
            if (exitIpType != null || exitIpAddr != null) {
                exitIpType = null
                exitIpAddr = null
                exitIpNode = null
                lifecycleScope.launch {
                    setExitIpType(null)
                    setExitIpAddr(null)
                }
            }
            return
        }
        val now = System.currentTimeMillis()
        val stale = exitIpAddr != null && now - exitIpLastSuccessMs > 600_000
        val retryDue = exitIpAddr == null && now - exitIpLastAttemptMs > 60_000
        if (!exitIpFetching && (node != exitIpNode || stale || retryDue)) {
            val nodeChanged = node != exitIpNode
            exitIpFetching = true
            exitIpLastAttemptMs = now
            exitIpNode = node
            lifecycleScope.launch {
                // Never show the previous node's IP while the new lookup
                // is in flight; the lines reappear once it succeeds.
                if (nodeChanged) {
                    exitIpType = null
                    exitIpAddr = null
                    setExitIpType(null)
                    setExitIpAddr(null)
                }
                val info = fetchExitIpInfo()
                if (info != null) exitIpLastSuccessMs = System.currentTimeMillis()
                exitIpType = info?.first
                exitIpAddr = info?.second
                exitIpFetching = false
                setExitIpType(exitIpType)
                setExitIpAddr(exitIpAddr)
            }
        }
    }

    // FEATURE-EXITIP: one lookup of the tunnel's egress IP, its country,
    // city and hosting flag, via ip-api's free endpoint (HTTP-only tier;
    // the request travels inside the VPN tunnel). This HTTP call only
    // works because network_security_config.xml permits cleartext for
    // ip-api.com alone (FEATURE-EXITIP-CLEARTEXT); without that entry
    // Android 9+ blocks it and every lookup fails. Country prefers the
    // localized name (lang=zh-CN); when the service ignores the language
    // and returns an ASCII name, the country code is used instead.
    // FEATURE-RUNCARD-V2: returns (type, addr), where type is the
    // datacenter/residential word (or null when hosting is unknown)
    // and addr is "IP Country/City", "IP Country", or just "IP". Any
    // failure yields null and the card simply shows no IP lines.
    private suspend fun fetchExitIpInfo(): Pair<String?, String?>? = withContext(Dispatchers.IO) {
        val conn = URL("http://ip-api.com/json?fields=status,query,country,countryCode,city,regionName,hosting&lang=zh-CN")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (json.optString("status") != "success") return@withContext null
            val ip = json.optString("query").takeIf { it.isNotEmpty() } ?: return@withContext null
            val countryRaw = json.optString("country")
            val country = when {
                countryRaw.any { it.code > 127 } -> countryRaw
                json.optString("countryCode").isNotEmpty() -> json.optString("countryCode")
                else -> null
            }
            val city = json.optString("city").takeIf { it.isNotEmpty() }
                ?: json.optString("regionName").takeIf { it.isNotEmpty() }
            val addr = when {
                country != null && city != null -> "$ip $country/$city"
                country != null -> "$ip $country"
                else -> ip
            }
            val hosting = if (json.has("hosting")) json.optBoolean("hosting") else null
            val type = hosting?.let {
                getString(if (it) DesignR.string.exit_ip_datacenter else DesignR.string.exit_ip_residential)
            }
            type to addr
        } catch (e: Exception) {
            // FEATURE-EXITIP-CLEARTEXT: log the reason instead of
            // swallowing it, so a future failure (e.g. cleartext still
            // blocked) is diagnosable from logcat instead of silently
            // missing lines. The card still just hides the IP lines.
            Log.w("bash-exitip", "fetchExitIpInfo failed", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    // FEATURE-CURNODE: the node shown on the running card. Global mode
    // shows GLOBAL's current node; rule mode shows the current node of
    // the first manually-selectable (Selector) group, which is the main
    // selector in this app's configs; direct mode and configs without a
    // Selector show no node. Blank `now` means nothing selected yet.
    private suspend fun IClashManager.resolveCurrentNode(): String? {
        return when (queryTunnelState().mode) {
            TunnelState.Mode.Direct -> null
            TunnelState.Mode.Global ->
                queryProxyGroup("GLOBAL", ProxySort.Default).now.takeIf { it.isNotBlank() }
            else -> {
                currentNodeGroup?.let { name ->
                    val group = queryProxyGroup(name, ProxySort.Default)
                    if (group.type == "Selector")
                        return group.now.takeIf { it.isNotBlank() }
                    currentNodeGroup = null
                }
                for (name in queryProxyGroupNames(false)) {
                    if (name == "GLOBAL")
                        continue
                    val group = queryProxyGroup(name, ProxySort.Default)
                    if (group.type == "Selector") {
                        currentNodeGroup = name
                        return group.now.takeIf { it.isNotBlank() }
                    }
                }
                null
            }
        }
    }

    private suspend fun MainDesign.startClash() {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(DesignR.string.no_profile_selected, ToastDuration.Long) {
                setAction(DesignR.string.profiles) {
                    startActivity(ProfilesActivity::class.intent)
                }
            }

            return
        }

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK)
                    startClashService()
            }
        } catch (e: Exception) {
            design?.showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName + "\n" + Bridge.nativeCoreVersion().replace("_", "-")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setupShortcuts()
    }

    private fun setupShortcuts() {
        // Skip dynamic shortcut setup when the app icon is hidden.
        if (uiStore.hideAppIcon) return

        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION

        val toggle = ShortcutInfoCompat.Builder(this, "toggle_clash")
            .setShortLabel(getString(DesignR.string.shortcut_toggle_short))
            .setLongLabel(getString(DesignR.string.shortcut_toggle_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_all))
            .setIntent(
                Intent(Intents.ACTION_TOGGLE_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(0)
            .build()

        val start = ShortcutInfoCompat.Builder(this, "start_clash")
            .setShortLabel(getString(DesignR.string.shortcut_start_short))
            .setLongLabel(getString(DesignR.string.shortcut_start_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_on))
            .setIntent(
                Intent(Intents.ACTION_START_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(1)
            .build()

        val stop = ShortcutInfoCompat.Builder(this, "stop_clash")
            .setShortLabel(getString(DesignR.string.shortcut_stop_short))
            .setLongLabel(getString(DesignR.string.shortcut_stop_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_off))
            .setIntent(
                Intent(Intents.ACTION_STOP_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(2)
            .build()

        ShortcutManagerCompat.setDynamicShortcuts(this, listOf(toggle, start, stop))
    }
}
