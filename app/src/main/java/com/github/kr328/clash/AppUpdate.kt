// FEATURE-UPDATE BEGIN (whole file): manual in-app update check.
// Flow (manual only, never on launch): Settings -> "检查更新" fetches
// a small JSON manifest from test-node-1's subscription server, compares
// its versionCode with the installed one, and if newer, downloads the
// APK (hosted on Drive), verifies its SHA-256, then hands it to the
// system installer. The install itself always needs the user's tap in
// the system installer UI, and Android refuses to install over this app
// unless the APK carries the same signing key, so a tampered package
// cannot replace the installed one even if the manifest were spoofed.
// The manifest URL is the published 18080 port of test-node-1, the same
// host that already serves the standalone subscription file; no
// Cloudflare Worker change is needed for it.
package com.github.kr328.clash

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val notes: String,
)

object AppUpdate {
    // Small JSON manifest, overwritten on every release (see delivery
    // notes): {"versionCode":211040,"versionName":"2.11.40",
    // "url":"https://drive.google.com/uc?id=...&export=download",
    // "size":47935273,"sha256":"...","notes":"..."}.
    const val MANIFEST_URL =
        "https://18080-agqwb2qcphfftrg4pfe35udry4.instabox.tech/bash-update.json"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    @Suppress("DEPRECATION")
    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    suspend fun fetchUpdateInfo(): UpdateInfo = withContext(Dispatchers.IO) {
        val conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("update manifest HTTP ${conn.responseCode}")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            UpdateInfo(
                versionCode = json.getLong("versionCode"),
                versionName = json.getString("versionName"),
                url = json.getString("url"),
                size = json.optLong("size", 0),
                sha256 = json.getString("sha256"),
                notes = json.optString("notes", ""),
            )
        } finally {
            conn.disconnect()
        }
    }

    // Downloads the APK to cacheDir/update/, reporting progress on the
    // calling thread's handler via onProgress. Throws on HTTP error,
    // size mismatch (when the manifest states a size), SHA-256 mismatch,
    // or cancellation; a partial file is always deleted on failure so a
    // later tap starts clean.
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        cancelled: AtomicBoolean,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val out = File(dir, "bash-update.apk")
        if (out.exists()) out.delete()
        val conn = (URL(info.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            // Drive download links redirect; follow them.
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("apk download HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
            var downloaded = 0L
            conn.inputStream.use { input ->
                FileOutputStream(out).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (cancelled.get()) throw IOException("download cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            if (info.size > 0 && out.length() != info.size) {
                throw IOException("apk size mismatch")
            }
            if (!verifySha256(out, info.sha256)) {
                throw IOException("apk SHA-256 mismatch")
            }
            out
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    fun verifySha256(file: File, expectedHex: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedHex.trim(), ignoreCase = true)
    }

    // Android 8+: the user must allow this app to install unknown apps
    // once, in system settings. Returns false when that grant is missing
    // so the caller can send the user to the setting first.
    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openUnknownSourcesSettings(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}"),
        )
        activity.startActivity(intent)
    }

    fun installApk(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
    }
}
// FEATURE-UPDATE END
