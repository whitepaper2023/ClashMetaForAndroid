package com.github.kr328.clash.remote

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.log.SystemLogcat
import com.github.kr328.clash.service.RemoteService
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.unwrap
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.logsDir
import com.github.kr328.clash.util.unbindServiceSilent
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class Service(private val context: Application, val crashed: () -> Unit) {
    val remote = Resource<IRemoteService>()

    private val connection = object : ServiceConnection {
        private var lastCrashed: Long = -1

        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            remote.set(service.unwrap(IRemoteService::class))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote.set(null)

            // FEATURE-DIAG: record every unexpected service death. The main
            // process survives a :background crash, so it persists the death
            // time plus the system log (Go panic / native tombstone / Java
            // FATAL EXCEPTION) into the logs dir as a bash-<ts>.log file,
            // which the Logs page lists and can export. Never throws: a
            // failure here must not take down the main process as well.
            val now = System.currentTimeMillis()
            val rapid = now - lastCrashed < TOGGLE_CRASHED_INTERVAL

            runCatching {
                Global.launch(Dispatchers.IO) {
                    runCatching { writeDeathRecord(now, rapid, "service_disconnected") }
                }
            }

            if (rapid) {
                unbind()

                crashed()
            }

            lastCrashed = now
            Log.w("RemoteService killed or crashed")
        }
    }

    // NOTE: this must live at Service level, not inside the connection
    // object: both onServiceDisconnected and checkDeathOnForeground call
    // it, and a private fun inside the anonymous connection object would
    // not be visible to checkDeathOnForeground.
    private fun writeDeathRecord(now: Long, rapid: Boolean, detected: String) {
        // NOTE: Sequence has no takeLast; lines() returns a List, and
        // only List.takeLast exists. The previous lineSequence() form
        // failed CI compilation with "Unresolved reference 'takeLast'".
        // A dumpCrash failure must not lose the whole record: fall back
        // to empty and let the note below explain it.
        val crash = runCatching {
            SystemLogcat.dumpCrash()
                .lines()
                .takeLast(3000)
                .joinToString("\n")
        }.getOrElse { "" }

        val text = buildString {
            appendLine("=== Service death record ===")
            appendLine("time: ${Date(now)} ($now)")
            appendLine("rapid_disconnect_within_10s: $rapid")
            appendLine("detected: $detected")
            if (detected == "foreground_return") {
                appendLine("note: service was expected running (shouldRun) but its :background process is gone on foreground return; it died while the app was in background (or the device rebooted).")
            } else {
                appendLine("note: RemoteService disconnected unexpectedly (killed or crashed).")
            }
            appendLine("--- system log (Go / DEBUG / AndroidRuntime / ClashMetaForAndroid / LwIP) ---")
            if (crash.isBlank()) {
                appendLine("(system log empty: no crash record, process may have been killed by the system instead of crashing)")
            } else {
                appendLine(crash)
            }
        }

        context.logsDir.mkdirs()
        context.logsDir.resolve(LogFile.generate().fileName).writeText(text)
    }

    // FEATURE-DIAG2: deaths while the app is in background never reach
    // onServiceDisconnected, because Remote.launch unbinds when the app
    // becomes invisible. Detect them here, when the app returns to
    // foreground, BEFORE re-binding: bind with BIND_AUTO_CREATE would
    // recreate the service and hide the death.
    // ORDER MATTERS: check the :background process FIRST, and only then
    // read shouldRun. shouldRun is read via MultiProcessPreference whose
    // ContentProvider runs in :background itself, so reading it first
    // could restart that process's Application and mask the very death
    // being detected. After the process check has already decided the
    // service is gone, a provider restart is harmless. shouldRun true
    // (user never stopped the VPN) + :background absent = died while
    // away. A reboot looks the same; the logcat section will then be
    // empty and the note covers it. The reported flag stops one record
    // per foreground return while the service stays dead; it resets
    // whenever the service is found alive or shouldRun is false, so a
    // later real death is reported again.
    private fun checkDeathOnForeground() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val procs = am?.runningAppProcesses ?: return
        // The list must contain our own main process; an empty (or
        // self-less) list is untrustworthy, not evidence of death.
        if (procs.none { it.processName == context.packageName }) return
        val store = ServiceStore(context)
        if (procs.any { it.processName == "${context.packageName}:background" }) {
            store.foregroundDeathReported = false
            return
        }
        if (!store.shouldRun) {
            store.foregroundDeathReported = false
            return
        }
        if (store.foregroundDeathReported) return
        store.foregroundDeathReported = true
        val now = System.currentTimeMillis()
        Global.launch(Dispatchers.IO) {
            runCatching { writeDeathRecord(now, false, "foreground_return") }
        }
    }

    fun bind() {
        runCatching { checkDeathOnForeground() }

        try {
            context.bindService(RemoteService::class.intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            unbind()

            crashed()
        }
    }

    fun unbind() {
        context.unbindServiceSilent(connection)

        remote.set(null)
    }

    companion object {
        private val TOGGLE_CRASHED_INTERVAL = TimeUnit.SECONDS.toMillis(10)
    }
}