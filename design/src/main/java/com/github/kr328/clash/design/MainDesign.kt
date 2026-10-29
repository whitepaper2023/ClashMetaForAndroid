package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    enum class Request {
        ToggleStatus,
        OpenProxy,
        // FEATURE-2B BEGIN: homepage entry for per-app node picks.
        // The page opens while stopped too (it only needs the stored
        // picks then); picking a node still requires a
        // running Clash, as before.
        OpenAppNodes,
        // FEATURE-1C: homepage entry for the app blocklist. The page
        // opens while stopped too (picks are stored and applied at the
        // next load); while running they apply on leaving the page.
        OpenBlockedApps,
        // FEATURE-2B END
        OpenProfiles,
        OpenProviders,
        OpenSettings,
    }

    private val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.profileName = name
        }
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            binding.clashRunning = running
        }
    }

    suspend fun setForwarded(value: Long) {
        withContext(Dispatchers.Main) {
            binding.forwarded = value.trafficTotal()
        }
    }

    // FEATURE-CURNODE: current node shown on the running card; null means
    // no node to show (stopped, direct mode, or no selector group) and the
    // card falls back to the traffic-only subtext.
    suspend fun setCurrentNode(node: String?) {
        withContext(Dispatchers.Main) {
            binding.currentNode = node
        }
    }

    // FEATURE-EXITIP: second line of the running card (egress IP, country,
    // datacenter/residential), including its leading newline when present.
    // Null is normalized to "" here and in init: a null binding variable
    // would render as the literal text "null" in the subtext.
    suspend fun setExitIpLine(line: String?) {
        withContext(Dispatchers.Main) {
            binding.exitIpLine = line ?: ""
        }
    }

    suspend fun setMode(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            binding.mode = when (mode) {
                TunnelState.Mode.Direct -> context.getString(R.string.direct_mode)
                TunnelState.Mode.Global -> context.getString(R.string.global_mode)
                TunnelState.Mode.Rule -> context.getString(R.string.rule_mode)
                else -> context.getString(R.string.rule_mode)
            }
        }
    }

    suspend fun setHasProviders(has: Boolean) {
        withContext(Dispatchers.Main) {
            binding.hasProviders = has
        }
    }

    suspend fun showAbout(versionName: String) {
        withContext(Dispatchers.Main) {
            val binding = DesignAboutBinding.inflate(context.layoutInflater).apply {
                this.versionName = versionName
            }

            AlertDialog.Builder(context)
                .setView(binding.root)
                .show()
        }
    }

    init {
        binding.self = this

        // FEATURE-EXITIP: never leave the second subtext line null (see
        // the variable comment in design_main.xml).
        binding.exitIpLine = ""

        binding.colorClashStarted = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        binding.colorClashStopped = context.resolveThemedColor(R.attr.colorClashStopped)
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}