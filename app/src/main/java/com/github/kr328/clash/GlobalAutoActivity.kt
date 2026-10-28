// FEATURE-4B BEGIN (whole file): manual global-auto subscription picker.
// The user ticks the subscriptions the "🚀 全局自动" group should span;
// the pick is stored in ServiceStore.globalAutoProviders and turned into
// the group's `use:` list by ConfigurationModule at load time. Picks are
// saved and applied (via sendOverrideChanged) when leaving this page,
// mirroring AppNodeActivity. Unlike the old switch-only path, the list
// comes from the active profile's config.yaml on disk (shared
// parseProviderNames), so the page works while stopped too. An empty
// pick falls back to the old switch: on = all providers, off = no group.
// Stale picks for providers no longer in the config are pruned on open.
// AUDIT FIX: when the pick actually changes on this page, the old switch
// is synced (non-empty pick -> on, cleared pick -> off), so clearing all
// picks cannot silently fall back to "switch on = all providers". Opening
// the page without changing anything leaves the legacy switch untouched.
package com.github.kr328.clash

import android.widget.Toast
import com.github.kr328.clash.design.GlobalAutoDesign
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.store.parseProviderNames
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class GlobalAutoActivity : BaseActivity<GlobalAutoDesign>() {
    override suspend fun main() {
        val service = ServiceStore(this)

        val selected = withContext(Dispatchers.IO) {
            service.globalAutoProviders.toMutableSet()
        }

        // AUDIT FIX: distinguish "no profile" / "no config file" from
        // "config has no providers", so the empty-list toast is accurate.
        val profileState = withContext(Dispatchers.IO) {
            val uuid = service.activeProfile
            when {
                uuid == null -> "no-profile"
                !this@GlobalAutoActivity.importedDir.resolve(uuid.toString())
                    .resolve("config.yaml").exists() -> "no-config"
                else -> "ok"
            }
        }

        val providers = withContext(Dispatchers.IO) {
            val uuid = service.activeProfile
            // Qualified explicitly: inside withContext the lambda receiver
            // is CoroutineScope, so a bare `importedDir` would rely on
            // outer-receiver fallback; spell it out instead.
            val configFile = uuid?.let {
                this@GlobalAutoActivity.importedDir.resolve(it.toString()).resolve("config.yaml")
            }
            if (configFile == null || !configFile.exists()) {
                emptyList()
            } else {
                parseProviderNames(configFile.readLines())
            }
        }

        if (providers.isEmpty()) {
            Toast.makeText(
                this,
                when (profileState) {
                    "no-profile" -> "请先选择配置文件"
                    "no-config" -> "配置文件不存在，无法读取订阅"
                    else -> "当前配置文件没有订阅（proxy-providers），无法选择"
                },
                Toast.LENGTH_LONG
            ).show()
        } else {
            // Prune picks for providers that left the config; the defer
            // block below saves the pruned set when leaving the page.
            selected.retainAll(providers.toSet())
        }

        Toast.makeText(this, "勾选后退出本页生效", Toast.LENGTH_LONG).show()

        defer {
            val changed = withContext(Dispatchers.IO) {
                val changed = selected != service.globalAutoProviders
                service.globalAutoProviders = selected
                // AUDIT FIX: sync the legacy switch only on a real change,
                // so clearing picks turns global-auto off instead of
                // silently reverting to all providers, and picking turns
                // it on. No change -> leave the legacy switch alone.
                if (changed)
                    service.globalAutoEnabled = selected.isNotEmpty()
                changed
            }
            if (clashRunning && changed) {
                sendOverrideChanged()
            }
        }

        val design = GlobalAutoDesign(this)

        setContentDesign(design)

        design.patchProviders(providers, selected)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        is GlobalAutoDesign.Request.Toggle -> {
                            if (it.providerName in selected)
                                selected.remove(it.providerName)
                            else
                                selected.add(it.providerName)
                            design.patchProviders(providers, selected)
                        }
                    }
                }
            }
        }
    }
}
// FEATURE-4B END
