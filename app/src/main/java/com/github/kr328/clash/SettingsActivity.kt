package com.github.kr328.clash

import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.bridge.Bridge
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.SettingsDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : BaseActivity<SettingsDesign>() {
    override suspend fun main() {
        val design = SettingsDesign(this)

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        SettingsDesign.Request.StartApp ->
                            startActivity(AppSettingsActivity::class.intent)
                        SettingsDesign.Request.StartNetwork ->
                            startActivity(NetworkSettingsActivity::class.intent)
                        SettingsDesign.Request.StartOverride ->
                            startActivity(OverrideSettingsActivity::class.intent)
                        SettingsDesign.Request.StartMetaFeature ->
                            startActivity(MetaFeatureSettingsActivity::class.intent)
                        SettingsDesign.Request.OpenHelp ->
                            startActivity(HelpActivity::class.intent)
                        SettingsDesign.Request.OpenLogs -> {
                            if (LogcatService.running) {
                                startActivity(LogcatActivity::class.intent)
                            } else {
                                startActivity(LogsActivity::class.intent)
                            }
                        }
                        SettingsDesign.Request.OpenAbout ->
                            design.showAbout(queryAppVersionName())
                        SettingsDesign.Request.Backup ->
                            handleBackup(design)
                    }
                }
            }
        }
    }

    // FEATURE-BACKUP: Settings entry -> choice dialog -> export (save
    // an encrypted backup file via the system file picker) or import
    // (pick a backup file, enter its password, parse it, confirm with
    // the profile names, then import). A cancelled picker or a
    // cancelled confirmation leaves nothing behind.
    private suspend fun handleBackup(design: SettingsDesign) {
        when (design.showBackupChoice()) {
            SettingsDesign.BackupChoice.Export -> {
                // FEATURE-BACKUP-ENCRYPTED: the file is encrypted, so
                // a password is required before any file is written,
                // and the file is not a zip (other apps must not be
                // able to open it as one).
                val password = design.showBackupExportPassword() ?: return
                val fileName = "bash-backup-" +
                    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
                    ".bashbackup"
                val uri = startActivityForResult(
                    ActivityResultContracts.CreateDocument("application/octet-stream"),
                    fileName
                ) ?: return
                try {
                    val count = withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            exportBackup(this@SettingsActivity, out, password)
                        } ?: throw IllegalStateException("Cannot open output file")
                    }
                    design.showToast(getString(R.string.backup_exported, count), ToastDuration.Long)
                } catch (e: Exception) {
                    design.showExceptionToast(e)
                }
            }
            SettingsDesign.BackupChoice.Import -> {
                val uri = startActivityForResult(
                    ActivityResultContracts.GetContent(),
                    "*/*"
                ) ?: return
                val password = design.showBackupImportPassword() ?: return
                var preview: BackupPreview? = null
                try {
                    preview = try {
                        parseBackupPreview(this@SettingsActivity, uri, password)
                    } catch (e: WrongBackupPasswordException) {
                        design.showToast(R.string.backup_wrong_password, ToastDuration.Long)
                        return
                    } catch (_: Exception) {
                        design.showToast(R.string.backup_invalid_file, ToastDuration.Long)
                        return
                    }
                    if (!design.showBackupImportConfirm(preview.manifest.profiles.map { it.name }))
                        return
                    // preview is non-null here, but a var captured by the
                    // non-inline withContext lambda loses smart-cast, so
                    // copy it to a val first (otherwise this may not compile).
                    val previewToImport = preview
                    val result = withContext(Dispatchers.IO) {
                        importBackup(this@SettingsActivity, previewToImport)
                    }
                    if (result.failedNames.isEmpty()) {
                        design.showToast(
                            getString(R.string.backup_imported, result.importedNames.size),
                            ToastDuration.Long
                        )
                    } else {
                        design.showToast(
                            getString(
                                R.string.backup_import_partial,
                                result.importedNames.size,
                                result.failedNames.joinToString("、")
                            ),
                            ToastDuration.Long
                        )
                    }
                } catch (e: Exception) {
                    design.showExceptionToast(e)
                } finally {
                    preview?.dir?.deleteRecursively()
                }
            }
            SettingsDesign.BackupChoice.Cancel -> Unit
        }
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName + "\n" + Bridge.nativeCoreVersion().replace("_", "-")
        }
    }
}