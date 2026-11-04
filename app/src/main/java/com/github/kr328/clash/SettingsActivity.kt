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
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : BaseActivity<SettingsDesign>() {
    // FEATURE-UPDATE: APK downloaded while the unknown-sources grant
    // was missing. Installed from onResume once the grant is present,
    // so the user does not have to re-download after enabling it.
    private var pendingInstallFile: java.io.File? = null

    override fun onResume() {
        super.onResume()
        val apk = pendingInstallFile
        if (apk != null && apk.exists() && AppUpdate.canInstall(this)) {
            pendingInstallFile = null
            AppUpdate.installApk(this, apk)
        }
        // FEATURE-BACKUP-IMPORT-BG: returning to Settings after a
        // background import finished refreshes the persistent note.
        // design is null until main() sets it; the safe call covers
        // the first onResume before that.
        (design as? SettingsDesign)?.refreshLastImportResult()
    }

    override suspend fun main() {
        val design = SettingsDesign(this, uiStore)

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
                        SettingsDesign.Request.CheckUpdate ->
                            handleUpdate(design)
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
                // FEATURE-BACKUP-IMPORT-BG: never run two imports at
                // once (each run creates NEW profiles, so a double run
                // would duplicate every profile).
                if (BackupImportRunner.isRunning) {
                    design.showToast("正在导入备份，请稍候", ToastDuration.Long)
                    return
                }
                val uri = startActivityForResult(
                    ActivityResultContracts.GetContent(),
                    "*/*"
                ) ?: return
                val password = design.showBackupImportPassword() ?: return
                var preview: BackupPreview? = null
                // True once the preview has been handed to the runner:
                // only the runner may then delete preview.dir, in its
                // own finally after the import finishes. Deleting it
                // here would pull the source out from under a running
                // background import.
                var handedOff = false
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
                    // preview is non-null here, but a var captured by a
                    // lambda loses smart-cast, so copy it to a val first.
                    val previewToImport = preview
                    // FEATURE-BACKUP-IMPORT-BG: hand the import to the
                    // process-level runner and return at once. Leaving
                    // this page (or a page recreation) no longer cancels
                    // it; the runner writes the result to the store and
                    // the Settings page shows it persistently.
                    if (!BackupImportRunner.startImport(applicationContext, previewToImport)) {
                        design.showToast("正在导入备份，请稍候", ToastDuration.Long)
                        return
                    }
                    handedOff = true
                    design.showToast("备份正在后台导入，完成后会在设置页显示结果", ToastDuration.Long)
                    // If this page is still open when the import
                    // finishes, show the result as a dialog (not a
                    // toast — the old toast was easy to miss). This
                    // launch is a child of the page scope, so leaving
                    // the page cancels only the dialog, never the import.
                    launch {
                        try {
                            BackupImportRunner.currentJob?.join()
                        } catch (_: Exception) {
                            return@launch
                        }
                        design.refreshLastImportResult()
                        design.showBackupImportResult(
                            uiStore.lastBackupImportResult
                        )
                    }
                } catch (e: Exception) {
                    design.showExceptionToast(e)
                } finally {
                    if (!handedOff) {
                        preview?.dir?.deleteRecursively()
                    }
                }
            }
            SettingsDesign.BackupChoice.Cancel -> Unit
        }
    }

    // FEATURE-UPDATE: manual check only — nothing here runs on launch
    // or in the background. Manifest fetch, version compare, download
    // with progress, SHA-256 verify, then the system installer. A
    // failed check or download reports via toast and leaves no partial
    // file behind (downloadApk deletes it on any failure).
    private suspend fun handleUpdate(design: SettingsDesign) {
        val info = try {
            AppUpdate.fetchUpdateInfo()
        } catch (e: Exception) {
            design.showExceptionToast(e)
            return
        }
        if (info.versionCode <= AppUpdate.currentVersionCode(this)) {
            design.showToast(getString(R.string.update_up_to_date), ToastDuration.Long)
            return
        }
        val message = "新版本 ${info.versionName}" +
            (if (info.notes.isNotBlank()) "\n\n${info.notes}" else "")
        if (!design.showUpdateAvailable(message)) return
        val progress = design.showUpdateProgress()
        val apk = try {
            AppUpdate.downloadApk(this, info, progress.cancelled) { downloaded, total ->
                progress.setProgress(downloaded, total)
            }
        } catch (e: Exception) {
            progress.dismiss()
            if (!progress.cancelled.get()) design.showExceptionToast(e)
            return
        }
        progress.dismiss()
        if (!AppUpdate.canInstall(this)) {
            pendingInstallFile = apk
            if (design.showUpdateNeedPermission()) {
                AppUpdate.openUnknownSourcesSettings(this)
            }
            return
        }
        AppUpdate.installApk(this, apk)
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName + "\n" + Bridge.nativeCoreVersion().replace("_", "-")
        }
    }
}