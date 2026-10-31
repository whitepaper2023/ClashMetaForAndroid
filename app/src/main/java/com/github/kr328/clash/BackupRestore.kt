package com.github.kr328.clash

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.sendOverrideChanged
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Thrown when the backup file's GCM tag does not verify: in practice
// a wrong password (a corrupted file fails the same way, and the UI
// message covers both).
class WrongBackupPasswordException : Exception("Wrong backup password")

private val BACKUP_MAGIC = "BASHBACK".toByteArray(Charsets.US_ASCII)
private const val BACKUP_VERSION: Byte = 1
private const val BACKUP_SALT_LEN = 16
private const val BACKUP_NONCE_LEN = 12
private const val BACKUP_PBKDF2_ITERATIONS = 100_000

private fun deriveBackupKey(password: String, salt: ByteArray): SecretKeySpec {
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val spec = PBEKeySpec(password.toCharArray(), salt, BACKUP_PBKDF2_ITERATIONS, 256)
    try {
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    } finally {
        spec.clearPassword()
    }
}

private fun readExact(input: InputStream, size: Int): ByteArray {
    val buf = ByteArray(size)
    var read = 0
    while (read < size) {
        val n = input.read(buf, read, size - read)
        if (n < 0) throw IllegalArgumentException("Truncated backup file")
        read += n
    }
    return buf
}

// FEATURE-BACKUP: export/import of bash data as a single ENCRYPTED
// file. The file is NOT a zip: anyone opening it without bash (or
// without the password) sees only ciphertext, never a config, a
// subscription URL, or a key.
//
// File format (all multi-byte fields are raw bytes, no text header):
//   "BASHBACK" (8 bytes magic) | version (1 byte, currently 1)
//   | salt (16 bytes) | GCM nonce (12 bytes)
//   | AES-256-GCM ciphertext of the zip described below.
// The key is PBKDF2WithHmacSHA256(password, salt, 100_000 rounds,
// 256 bits). No key is stored in the file and no key is stored in the
// app: only bash knows this format, and only the password that was
// set at export time can decrypt it. Consequence, stated in the UI:
// a forgotten password cannot be recovered by bash either, on any
// device, so the backup becomes unusable.
//
// The encrypted zip contains:
//   manifest.json — format/version, one entry per saved profile
//     (name, type, source, interval, ageSecretKey), and the bash
//     settings (blocked apps, per-app nodes, proxy filters, pasted
//     nodes, profile order, and the global service settings).
//   profiles/<oldUuid>/... — the profile's files from importedDir
//     (config.yaml and providers/*), as they were last saved.
//
// Export only covers imported (saved) profiles; a pending-only profile
// (created but never committed) is not saved yet, so it is skipped.
//
// Import never deletes existing data: profiles are always created as
// NEW profiles (fresh UUIDs, bash rules remapped old -> new), the
// bash sets are merged with the existing ones, and only the single-
// value global settings are overwritten. The active profile is never
// switched by an import. A profile whose commit fails (e.g. a Url
// profile with no network at import time) is left as a pending profile
// and reported as failed, so the user can commit it later from the
// Profiles page instead of losing it silently.

data class BackupProfile(
    val oldUuid: String,
    val name: String,
    val type: String,
    val source: String,
    val interval: Long,
    val ageSecretKey: String?,
)

data class BackupSettings(
    val blockedPackages: Set<String>,
    val appNodeRules: Set<String>,
    val proxyFilterRules: Set<String>,
    val pastedProxies: Set<String>,
    val profileOrder: String,
    val accessControlMode: String,
    val accessControlPackages: Set<String>,
    val bypassPrivateNetwork: Boolean,
    val dnsHijacking: Boolean,
    val systemProxy: Boolean,
    val allowBypass: Boolean,
    val allowIpv6: Boolean,
    val tunStackMode: String,
    val dynamicNotification: Boolean,
)

data class BackupManifest(
    val exportedAt: Long,
    val profiles: List<BackupProfile>,
    val settings: BackupSettings,
)

data class BackupPreview(
    val manifest: BackupManifest,
    val dir: File,
)

data class ImportResult(
    val importedNames: List<String>,
    val failedNames: List<String>,
)

private fun Set<String>.toJsonArray(): JSONArray {
    val arr = JSONArray()
    for (item in this) arr.put(item)
    return arr
}

private fun JSONArray.toStringSet(): Set<String> {
    val out = LinkedHashSet<String>()
    for (i in 0 until length()) {
        val item = optString(i)
        if (item.isNotEmpty()) out.add(item)
    }
    return out
}

private fun BackupManifest.toJson(): JSONObject {
    val profilesJson = JSONArray()
    for (p in profiles) {
        val obj = JSONObject()
        obj.put("oldUuid", p.oldUuid)
        obj.put("name", p.name)
        obj.put("type", p.type)
        obj.put("source", p.source)
        obj.put("interval", p.interval)
        if (p.ageSecretKey != null) obj.put("ageSecretKey", p.ageSecretKey)
        profilesJson.put(obj)
    }
    val settingsJson = JSONObject()
    settingsJson.put("blockedPackages", settings.blockedPackages.toJsonArray())
    settingsJson.put("appNodeRules", settings.appNodeRules.toJsonArray())
    settingsJson.put("proxyFilterRules", settings.proxyFilterRules.toJsonArray())
    settingsJson.put("pastedProxies", settings.pastedProxies.toJsonArray())
    settingsJson.put("profileOrder", settings.profileOrder)
    settingsJson.put("accessControlMode", settings.accessControlMode)
    settingsJson.put("accessControlPackages", settings.accessControlPackages.toJsonArray())
    settingsJson.put("bypassPrivateNetwork", settings.bypassPrivateNetwork)
    settingsJson.put("dnsHijacking", settings.dnsHijacking)
    settingsJson.put("systemProxy", settings.systemProxy)
    settingsJson.put("allowBypass", settings.allowBypass)
    settingsJson.put("allowIpv6", settings.allowIpv6)
    settingsJson.put("tunStackMode", settings.tunStackMode)
    settingsJson.put("dynamicNotification", settings.dynamicNotification)

    val root = JSONObject()
    root.put("format", "bash-backup")
    root.put("version", 1)
    root.put("exportedAt", exportedAt)
    root.put("profiles", profilesJson)
    root.put("settings", settingsJson)
    return root
}

private fun parseBackupManifest(json: JSONObject): BackupManifest {
    if (json.optString("format") != "bash-backup" || json.optInt("version") != 1)
        throw IllegalArgumentException("Not a bash backup file")
    val profilesJson = json.optJSONArray("profiles") ?: JSONArray()
    val profiles = ArrayList<BackupProfile>()
    for (i in 0 until profilesJson.length()) {
        val obj = profilesJson.optJSONObject(i) ?: continue
        val oldUuid = obj.optString("oldUuid")
        val name = obj.optString("name")
        if (oldUuid.isEmpty() || name.isEmpty()) continue
        profiles.add(
            BackupProfile(
                oldUuid = oldUuid,
                name = name,
                type = obj.optString("type", "File"),
                source = obj.optString("source", ""),
                interval = obj.optLong("interval", 0L),
                ageSecretKey = obj.optString("ageSecretKey").takeIf { it.isNotEmpty() },
            )
        )
    }
    val s = json.optJSONObject("settings") ?: JSONObject()
    val settings = BackupSettings(
        blockedPackages = s.optJSONArray("blockedPackages")?.toStringSet() ?: emptySet(),
        appNodeRules = s.optJSONArray("appNodeRules")?.toStringSet() ?: emptySet(),
        proxyFilterRules = s.optJSONArray("proxyFilterRules")?.toStringSet() ?: emptySet(),
        pastedProxies = s.optJSONArray("pastedProxies")?.toStringSet() ?: emptySet(),
        profileOrder = s.optString("profileOrder", ""),
        accessControlMode = s.optString("accessControlMode", "AcceptAll"),
        accessControlPackages = s.optJSONArray("accessControlPackages")?.toStringSet() ?: emptySet(),
        bypassPrivateNetwork = s.optBoolean("bypassPrivateNetwork", true),
        dnsHijacking = s.optBoolean("dnsHijacking", true),
        systemProxy = s.optBoolean("systemProxy", true),
        allowBypass = s.optBoolean("allowBypass", true),
        allowIpv6 = s.optBoolean("allowIpv6", false),
        tunStackMode = s.optString("tunStackMode", "mips"),
        dynamicNotification = s.optBoolean("dynamicNotification", true),
    )
    return BackupManifest(
        exportedAt = json.optLong("exportedAt", 0L),
        profiles = profiles,
        settings = settings,
    )
}

// Export: writes the encrypted backup file to [out]. Returns the
// number of profiles exported. Runs the file IO off the main thread;
// the profile list itself comes from the service via withProfile.
// The zip is streamed through the cipher, never held whole in memory;
// closing the zip closes the cipher stream, which writes the GCM tag.
suspend fun exportBackup(context: Context, out: OutputStream, password: String): Int =
    withContext(Dispatchers.IO) {
        // Only profiles whose saved files still exist are exported: a
        // profile counted in the manifest but without files would fail
        // on import, so it is skipped here instead of promised.
        val profiles = withProfile { queryAll() }.filter {
            it.imported && context.importedDir.resolve(it.uuid.toString()).exists()
        }
        val service = ServiceStore(context)
        val ui = UiStore(context)
        val manifest = BackupManifest(
            exportedAt = System.currentTimeMillis(),
            profiles = profiles.map {
                BackupProfile(
                    oldUuid = it.uuid.toString(),
                    name = it.name,
                    type = it.type.name,
                    source = it.source,
                    interval = it.interval,
                    ageSecretKey = it.ageSecretKey,
                )
            },
            settings = BackupSettings(
                blockedPackages = service.blockedPackages,
                appNodeRules = service.appNodeRules,
                proxyFilterRules = service.proxyFilterRules,
                pastedProxies = service.pastedProxies,
                profileOrder = ui.profileOrder,
                accessControlMode = service.accessControlMode.name,
                accessControlPackages = service.accessControlPackages,
                bypassPrivateNetwork = service.bypassPrivateNetwork,
                dnsHijacking = service.dnsHijacking,
                systemProxy = service.systemProxy,
                allowBypass = service.allowBypass,
                allowIpv6 = service.allowIpv6,
                tunStackMode = service.tunStackMode,
                dynamicNotification = service.dynamicNotification,
            ),
        )
        val random = SecureRandom()
        val salt = ByteArray(BACKUP_SALT_LEN).also { random.nextBytes(it) }
        val nonce = ByteArray(BACKUP_NONCE_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveBackupKey(password, salt),
            GCMParameterSpec(128, nonce)
        )
        out.write(BACKUP_MAGIC)
        out.write(BACKUP_VERSION.toInt())
        out.write(salt)
        out.write(nonce)
        // CipherOutputStream is closed by ZipOutputStream.close(), which
        // runs doFinal and appends the GCM tag to the file.
        ZipOutputStream(CipherOutputStream(out, cipher).buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toJson().toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (p in profiles) {
                val dir = context.importedDir.resolve(p.uuid.toString())
                if (!dir.exists()) continue
                dir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeTo(dir).path.replace('\\', '/')
                    zip.putNextEntry(ZipEntry("profiles/${p.uuid}/$rel"))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        profiles.size
    }

// Parse: verifies the encrypted header, decrypts the zip, and
// validates the manifest. Throws WrongBackupPasswordException when
// the GCM tag does not verify (a wrong password, or a corrupted file
// — the UI message covers both), and IllegalArgumentException on a
// non-backup file or a malformed zip; all temp files are removed on
// failure. The caller must delete preview.dir after the import (or
// after a cancelled confirmation) so a rejected file leaves nothing
// behind. Zip-slip entries (../, absolute paths, or paths escaping
// the temp dir) are rejected instead of written.
//
// Decryption happens FIRST, to a temp file, and only after it
// succeeds is the zip opened. This ordering matters: with a wrong
// password the decrypted bytes are random, so unzipping them directly
// would fail with a zip-header error long before the GCM tag is ever
// checked, and the user would wrongly be told the file is invalid
// instead of the password being wrong. Reading the cipher stream to
// EOF forces doFinal, which is where the tag is actually verified.
// The decrypted temp zip lives only in the app-private cacheDir and
// is deleted before this function returns, on every path.
suspend fun parseBackupPreview(context: Context, uri: Uri, password: String): BackupPreview =
    withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "backup-preview-${System.currentTimeMillis()}")
        val decrypted = File(context.cacheDir, "backup-decrypted-${System.currentTimeMillis()}.tmp")
        dir.mkdirs()
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Cannot open backup file")
            try {
                input.use { raw ->
                    val magic = readExact(raw, BACKUP_MAGIC.size)
                    if (!magic.contentEquals(BACKUP_MAGIC))
                        throw IllegalArgumentException("Not a bash backup file")
                    val version = readExact(raw, 1)[0]
                    if (version != BACKUP_VERSION)
                        throw IllegalArgumentException("Unsupported backup version")
                    val salt = readExact(raw, BACKUP_SALT_LEN)
                    val nonce = readExact(raw, BACKUP_NONCE_LEN)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        deriveBackupKey(password, salt),
                        GCMParameterSpec(128, nonce)
                    )
                    // Decrypt fully to the temp file first: copyTo reads
                    // to EOF, which runs doFinal and verifies the tag.
                    CipherInputStream(raw, cipher).use { cipherIn ->
                        FileOutputStream(decrypted).use { cipherIn.copyTo(it) }
                    }
                }
            } catch (e: AEADBadTagException) {
                throw WrongBackupPasswordException()
            } catch (e: Exception) {
                // A tag failure can also surface wrapped in an IOException.
                var cause: Throwable? = e
                while (cause != null) {
                    if (cause is AEADBadTagException) throw WrongBackupPasswordException()
                    cause = cause.cause
                }
                throw e
            }
            FileInputStream(decrypted).use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\"))
                            throw IllegalArgumentException("Bad zip entry: $name")
                        if (!entry.isDirectory) {
                            val outFile = File(dir, name)
                            if (!outFile.canonicalPath.startsWith(dir.canonicalPath + File.separator))
                                throw IllegalArgumentException("Bad zip entry: $name")
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { zip.copyTo(it) }
                        }
                        entry = zip.nextEntry
                    }
                }
            }
            val manifestFile = File(dir, "manifest.json")
            if (!manifestFile.exists())
                throw IllegalArgumentException("Not a bash backup file")
            val manifest = parseBackupManifest(
                JSONObject(manifestFile.readText(Charsets.UTF_8))
            )
            decrypted.delete()
            BackupPreview(manifest, dir)
        } catch (e: Exception) {
            dir.deleteRecursively()
            decrypted.delete()
            throw e
        }
    }

// Import: creates every backup profile as a NEW profile (never
// overwriting an existing one), copies its saved files into the new
// pending dir, patches and commits it, then merges the bash settings
// (rules remapped oldUuid -> newUuid) and overwrites the single-value
// global settings. Existing profiles are never deleted and the active
// profile is never switched. Returns per-profile success/failure names.
suspend fun importBackup(context: Context, preview: BackupPreview): ImportResult {
    val manifest = preview.manifest
    val service = ServiceStore(context)
    val ui = UiStore(context)
    val mapping = LinkedHashMap<String, String>()
    val importedNames = ArrayList<String>()
    val failedNames = ArrayList<String>()
    for (p in manifest.profiles) {
        val type = try {
            Profile.Type.valueOf(p.type)
        } catch (_: Exception) {
            failedNames.add(p.name)
            continue
        }
        try {
            val newUuid: UUID = withProfile { create(type, p.name, p.source, p.ageSecretKey) }
            mapping[p.oldUuid] = newUuid.toString()
            val srcDir = File(preview.dir, "profiles/${p.oldUuid}")
            val dstDir = context.pendingDir.resolve(newUuid.toString())
            if (srcDir.exists()) {
                dstDir.deleteRecursively()
                srcDir.copyRecursively(dstDir, overwrite = true)
            }
            withProfile {
                patch(newUuid, p.name, p.source, p.interval, p.ageSecretKey)
                try {
                    commit(newUuid, null)
                    importedNames.add(p.name)
                } catch (_: Exception) {
                    // Left as a pending profile: the user can commit it
                    // later from the Profiles page (e.g. once online).
                    failedNames.add(p.name)
                }
            }
        } catch (_: Exception) {
            failedNames.add(p.name)
        }
    }
    // Remap a "<uuid>=..." rule set from backup UUIDs to the new UUIDs;
    // entries for profiles that were not created are dropped.
    fun remap(set: Set<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (entry in set) {
            val sep = entry.indexOf('=')
            if (sep <= 0) continue
            val mapped = mapping[entry.substring(0, sep)] ?: continue
            out.add(mapped + entry.substring(sep))
        }
        return out
    }
    val s = manifest.settings
    service.blockedPackages = service.blockedPackages + s.blockedPackages
    service.appNodeRules = service.appNodeRules + remap(s.appNodeRules)
    service.proxyFilterRules = service.proxyFilterRules + remap(s.proxyFilterRules)
    service.pastedProxies = service.pastedProxies + remap(s.pastedProxies)
    try {
        service.accessControlMode = AccessControlMode.valueOf(s.accessControlMode)
    } catch (_: Exception) {
        // Keep the current mode on an unknown value.
    }
    service.accessControlPackages = s.accessControlPackages
    service.bypassPrivateNetwork = s.bypassPrivateNetwork
    service.dnsHijacking = s.dnsHijacking
    service.systemProxy = s.systemProxy
    service.allowBypass = s.allowBypass
    service.allowIpv6 = s.allowIpv6
    service.tunStackMode = s.tunStackMode
    service.dynamicNotification = s.dynamicNotification
    val existingOrder = ui.profileOrder.split(",").filter { it.isNotBlank() }
    val newOrder = manifest.profiles.mapNotNull { mapping[it.oldUuid] }
    ui.profileOrder = (existingOrder + newOrder).distinct().joinToString(",")
    context.sendOverrideChanged()
    return ImportResult(importedNames, failedNames)
}
