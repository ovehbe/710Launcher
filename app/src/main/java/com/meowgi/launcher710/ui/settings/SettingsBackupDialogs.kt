package com.meowgi.launcher710.ui.settings

import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.meowgi.launcher710.LauncherActivity
import com.meowgi.launcher710.backup.BackupCodec
import com.meowgi.launcher710.backup.BackupException
import com.meowgi.launcher710.backup.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun SettingsActivity.runBackup(uri: Uri) {
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { BackupManager(this@runBackup).export(uri) } }
        result.onSuccess { p ->
            showBackupMessage("Backup saved", "Saved ${p.prefKeyCount} settings in ${p.prefs.size} files, " +
                "${p.rowCount} app records and ${p.assets.size} icon files.")
        }.onFailure { e ->
            showBackupMessage("Backup failed", backupError(e))
        }
    }
}

internal fun SettingsActivity.readBackup(uri: Uri) {
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { BackupManager(this@readBackup).read(uri) } }
        result.onSuccess { confirmRestore(it) }.onFailure { e ->
            showBackupMessage("Can't restore this file", "${backupError(e)}\n\nNothing was changed.")
        }
    }
}

private fun SettingsActivity.confirmRestore(prepared: BackupManager.Prepared) {
    val p = prepared.payload
    val made = if (p.createdAtMillis > 0) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(p.createdAtMillis))
    } else "unknown date"
    val msg = buildString {
        append("Backup from $made")
        p.source.versionName?.let { append(" (710 Launcher $it)") }
        append(".\n\nIt replaces ${p.prefKeyCount} settings in ${p.prefs.size} files")
        if (p.tables.isNotEmpty()) append(", ${p.rowCount} app records")
        if (p.assets.isNotEmpty()) append(" and ${p.assets.size} icon files")
        append(". Anything not in the backup stays as it is.")
        if (p.originalSchemaVersion < BackupCodec.CURRENT_SCHEMA_VERSION) {
            append("\n\nThis is an older settings-only export; the dock and app records are kept.")
        }
        appendMissing(prepared.missingPackages)
    }
    AlertDialog.Builder(this, dialogTheme)
        .setTitle("Restore backup?")
        .setMessage(msg)
        .setPositiveButton("Restore") { _, _ -> applyRestore(prepared) }
        .setNegativeButton("Cancel", null)
        .show()
}

private fun SettingsActivity.applyRestore(prepared: BackupManager.Prepared) {
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { BackupManager(this@applyRestore).restore(prepared) } }
        result.onSuccess { r ->
            val msg = buildString {
                append("Restored ${r.prefKeys} settings in ${r.prefFiles} files, ${r.rows} app records and ${r.assets} icon files.")
                if (r.skippedTables.isNotEmpty() || r.droppedColumns.isNotEmpty()) {
                    append("\n\nIgnored data this version doesn't use: ")
                    append((r.skippedTables + r.droppedColumns).joinToString())
                    append(".")
                }
                appendMissing(r.missingPackages)
                append("\n\nThe launcher will reload.")
            }
            AlertDialog.Builder(this@applyRestore, dialogTheme)
                .setTitle("Restore complete")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ ->
                    LauncherActivity.reloadRequested = true
                    finish()
                }
                .show()
        }.onFailure { e ->
            showBackupMessage("Restore failed", backupError(e))
        }
    }
}

private fun StringBuilder.appendMissing(packages: List<String>) {
    if (packages.isEmpty()) return
    append("\n\n${packages.size} app${if (packages.size == 1) " isn't" else "s aren't"} installed on this device; ")
    append("their dock, page and shortcut entries are skipped and come back if you install them:\n")
    append(packages.take(15).joinToString("\n") { "• $it" })
    if (packages.size > 15) append("\n• … and ${packages.size - 15} more")
}

private fun SettingsActivity.backupError(e: Throwable): String =
    if (e is BackupException) e.message ?: "Unknown error" else "Unexpected error: ${e.message ?: e.javaClass.simpleName}"

private fun SettingsActivity.showBackupMessage(title: String, message: String) {
    AlertDialog.Builder(this, dialogTheme)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show()
}
