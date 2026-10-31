package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doOnTextChanged
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.databinding.DesignSettingsBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class SettingsDesign(context: Context) : Design<SettingsDesign.Request>(context) {
    enum class Request {
        StartApp, StartNetwork, StartOverride, StartMetaFeature,
        // Moved here from the homepage: Help, Logs and About now live
        // in Settings.
        OpenHelp, OpenLogs, OpenAbout,
        // FEATURE-BACKUP: export/import of profiles + walash settings.
        Backup,
    }

    enum class BackupChoice {
        Export, Import, Cancel,
    }

    private val binding = DesignSettingsBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
    }

    fun request(request: Request) {
        requests.trySend(request)
    }

    // FEATURE-BACKUP: export/import choice dialog. The message states
    // what a backup contains (subscription URLs and keys included) and
    // that it is password-encrypted, so the user knows to keep both the
    // file and the password private — and that a forgotten password
    // cannot be recovered.
    suspend fun showBackupChoice(): BackupChoice {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.backup)
                    .setMessage(R.string.backup_dialog_message)
                    .setPositiveButton(R.string.backup_export) { _, _ -> ctx.resume(BackupChoice.Export) }
                    .setNegativeButton(R.string.backup_import) { _, _ -> ctx.resume(BackupChoice.Import) }
                    .setNeutralButton(R.string.cancel) { _, _ -> ctx.resume(BackupChoice.Cancel) }
                    .create()
                ctx.invokeOnCancellation { dialog.dismiss() }
                dialog.show()
                dialog.setOnDismissListener { if (!ctx.isCompleted) ctx.resume(BackupChoice.Cancel) }
            }
        }
    }

    // FEATURE-BACKUP: password for a new export, entered twice. The OK
    // button stays disabled until both fields are non-empty and equal,
    // so a mistyped password cannot silently lock the user out of
    // their own backup. Returns null on cancel.
    suspend fun showBackupExportPassword(): String? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                val binding = com.github.kr328.clash.design.databinding.DialogBackupPasswordBinding
                    .inflate(context.layoutInflater, context.root, false)
                binding.passwordLayout.hint = context.getString(R.string.backup_password)
                binding.confirmLayout.hint = context.getString(R.string.backup_password_confirm)
                val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.backup_export_password_title)
                    .setMessage(R.string.backup_password_warning)
                    .setView(binding.root)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        ctx.resume(binding.passwordField.text?.toString() ?: "")
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> ctx.resume(null) }
                    .create()
                ctx.invokeOnCancellation { dialog.dismiss() }
                dialog.show()
                val updateOk = {
                    val password = binding.passwordField.text?.toString() ?: ""
                    val confirm = binding.confirmField.text?.toString() ?: ""
                    val ok = password.isNotEmpty() && password == confirm
                    binding.confirmLayout.error =
                        if (confirm.isNotEmpty() && password != confirm)
                            context.getString(R.string.backup_password_mismatch) else null
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = ok
                }
                binding.passwordField.doOnTextChanged { _, _, _, _ -> updateOk() }
                binding.confirmField.doOnTextChanged { _, _, _, _ -> updateOk() }
                updateOk()
                dialog.setOnDismissListener { if (!ctx.isCompleted) ctx.resume(null) }
            }
        }
    }

    // FEATURE-BACKUP: password for an import, entered once and masked.
    // Returns null on cancel.
    suspend fun showBackupImportPassword(): String? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                val binding = com.github.kr328.clash.design.databinding.DialogTextFieldBinding
                    .inflate(context.layoutInflater, context.root, false)
                binding.textLayout.hint = context.getString(R.string.backup_password)
                binding.textField.inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                binding.textField.transformationMethod =
                    android.text.method.PasswordTransformationMethod.getInstance()
                val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.backup_import_password_title)
                    .setView(binding.root)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        ctx.resume(binding.textField.text?.toString())
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> ctx.resume(null) }
                    .create()
                ctx.invokeOnCancellation { dialog.dismiss() }
                dialog.show()
                val updateOk = {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled =
                        !(binding.textField.text?.toString().isNullOrEmpty())
                }
                binding.textField.doOnTextChanged { _, _, _, _ -> updateOk() }
                updateOk()
                dialog.setOnDismissListener { if (!ctx.isCompleted) ctx.resume(null) }
            }
        }
    }

    // FEATURE-BACKUP: confirm dialog shown after the picked backup file
    // has been parsed, so the user sees exactly which profiles will be
    // added before anything is written.
    suspend fun showBackupImportConfirm(profileNames: List<String>): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.backup_import)
                    .setMessage(
                        context.getString(
                            R.string.backup_import_confirm,
                            profileNames.joinToString("、").ifEmpty { "-" }
                        )
                    )
                    .setPositiveButton(R.string.ok) { _, _ -> ctx.resume(true) }
                    .setNegativeButton(R.string.cancel) { _, _ -> ctx.resume(false) }
                    .create()
                ctx.invokeOnCancellation { dialog.dismiss() }
                dialog.show()
                dialog.setOnDismissListener { if (!ctx.isCompleted) ctx.resume(false) }
            }
        }
    }

    // Same About dialog the homepage used to show (version + core).
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
}