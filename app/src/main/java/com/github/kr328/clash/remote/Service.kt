package com.github.kr328.clash.remote

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
            // FATAL EXCEPTION) into the logs dir as a clash-<ts>.log file,
            // which the Logs page lists and can export. Never throws: a
            // failure here must not take down the main process as well.
            val now = System.currentTimeMillis()
            val rapid = now - lastCrashed < TOGGLE_CRASHED_INTERVAL

            runCatching {
                Global.launch(Dispatchers.IO) {
                    runCatching { writeDeathRecord(now, rapid) }
                }
            }

            if (rapid) {
                unbind()

                crashed()
            }

            lastCrashed = now
            Log.w("RemoteService killed or crashed")
        }

        private fun writeDeathRecord(now: Long, rapid: Boolean) {
            // NOTE: Sequence has no takeLast; lines() returns a List, and
            // only List.takeLast exists. The previous lineSequence() form
            // failed CI compilation with "Unresolved reference 'takeLast'".
            val crash = SystemLogcat.dumpCrash()
                .lines()
                .takeLast(3000)
                .joinToString("\n")

            val text = buildString {
                appendLine("=== Service death record ===")
                appendLine("time: ${Date(now)} ($now)")
                appendLine("rapid_disconnect_within_10s: $rapid")
                appendLine("note: RemoteService disconnected unexpectedly (killed or crashed).")
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
    }

    fun bind() {
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