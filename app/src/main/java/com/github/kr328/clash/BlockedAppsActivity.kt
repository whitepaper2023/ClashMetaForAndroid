// FEATURE-1 BEGIN (whole file): app blocklist page. Delete this file to
// roll back the page itself; also remove its FEATURE-1 blocks in
// AndroidManifest.xml, NetworkSettingsDesign.kt, strings.xml,
// ServiceStore.kt and ConfigurationModule.kt.
// FEATURE-1 END (whole file marker; class follows)
package com.github.kr328.clash

import android.Manifest.permission.INTERNET
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.getSystemService
import com.github.kr328.clash.design.AccessControlDesign
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.toAppInfo
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

// Firewall (feature 1): block apps from the internet via UI selection.
// Checked apps are stored in ServiceStore.blockedPackages; ConfigurationModule
// turns them into PROCESS-NAME REJECT rules at profile load time.
// This is NOT the VPN allow/disallow screen (AccessControlActivity): it never
// touches accessControlPackages, and it reloads the config instead of
// restarting the whole service. Changes apply when leaving this screen.
class BlockedAppsActivity : BaseActivity<AccessControlDesign>() {
    override suspend fun main() {
        val service = ServiceStore(this)

        val blocked = withContext(Dispatchers.IO) {
            service.blockedPackages.toMutableSet()
        }

        Toast.makeText(this, "切换后退出本页生效", Toast.LENGTH_LONG).show()

        defer {
            val changed = withContext(Dispatchers.IO) {
                val changed = blocked != service.blockedPackages
                service.blockedPackages = blocked
                changed
            }
            if (clashRunning && changed) {
                sendOverrideChanged()
            }
        }

        val design = AccessControlDesign(this, uiStore, blocked)

        setContentDesign(design)

        design.requests.send(AccessControlDesign.Request.ReloadApps)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        AccessControlDesign.Request.ReloadApps -> {
                            design.patchApps(loadApps(blocked))
                        }

                        AccessControlDesign.Request.SelectAll -> {
                            val all = withContext(Dispatchers.Default) {
                                design.apps.map(AppInfo::packageName)
                            }

                            blocked.clear()
                            blocked.addAll(all)

                            design.rebindAll()
                        }

                        AccessControlDesign.Request.SelectNone -> {
                            blocked.clear()

                            design.rebindAll()
                        }

                        AccessControlDesign.Request.SelectInvert -> {
                            val all = withContext(Dispatchers.Default) {
                                design.apps.map(AppInfo::packageName).toSet() - blocked
                            }

                            blocked.clear()
                            blocked.addAll(all)

                            design.rebindAll()
                        }

                        AccessControlDesign.Request.Import -> {
                            val clipboard = getSystemService<ClipboardManager>()
                            val data = clipboard?.primaryClip

                            if (data != null && data.itemCount > 0) {
                                val packages = data.getItemAt(0).text.split("\n").toSet()
                                val all = design.apps.map(AppInfo::packageName).intersect(packages)

                                blocked.clear()
                                blocked.addAll(all)
                            }

                            design.rebindAll()
                        }

                        AccessControlDesign.Request.Export -> {
                            val clipboard = getSystemService<ClipboardManager>()

                            val data = ClipData.newPlainText(
                                "packages",
                                blocked.joinToString("\n")
                            )

                            clipboard?.setPrimaryClip(data)
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadApps(blocked: Set<String>): List<AppInfo> =
        withContext(Dispatchers.IO) {
            val reverse = uiStore.accessControlReverse
            val sort = uiStore.accessControlSort
            val systemApp = uiStore.accessControlSystemApp

            val base = compareByDescending<AppInfo> { it.packageName in blocked }
            val comparator = if (reverse) base.thenDescending(sort) else base.then(sort)

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
                .filter {
                    systemApp || !it.isSystemApp
                }
                .map {
                    it.toAppInfo(pm)
                }
                .sortedWith(comparator)
                .toList()
        }

    private val PackageInfo.isSystemApp: Boolean
        get() {
            return applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0
        }
}
