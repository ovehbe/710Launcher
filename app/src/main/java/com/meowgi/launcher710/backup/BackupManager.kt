package com.meowgi.launcher710.backup

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import com.meowgi.launcher710.model.AppDatabase
import com.meowgi.launcher710.model.AppStats
import com.meowgi.launcher710.ui.widgets.WidgetHost
import com.meowgi.launcher710.util.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Collects and applies the launcher's complete user state: both SharedPreferences files, the
 * app_stats table (launch counts, favourites) and the icon files pinned shortcuts point at.
 * Call everything here off the main thread.
 */
class BackupManager(context: Context) {

    data class RestoreReport(
        val prefCount: Int,
        val statCount: Int?,
        val fileCount: Int,
        val missingPackages: List<String>,
        val droppedWidgets: Int
    )

    private val context = context.applicationContext
    private val db = AppDatabase.get(this.context)

    private fun prefsFile(name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private val iconDir: File get() = File(context.filesDir, ICON_DIR).apply { mkdirs() }

    suspend fun createBackup(): LauncherBackup {
        val prefs = BackupCodec.PREF_FILES.associateWith { name -> snapshotPrefs(prefsFile(name)) }
        val stats = db.appStatsDao().getAll().map {
            AppStatRecord(it.componentName, it.launchCount, it.isFavorite, it.lastLaunched, it.frecency, it.frecencyAt)
        }
        val usedNames = HashSet<String>()
        val files = BackupCodec.referencedIconPaths(prefs[BackupCodec.SETTINGS_PREFS].orEmpty()).mapNotNull { path ->
            val f = File(path)
            if (!f.isFile || f.length() > BackupCodec.MAX_FILE_BYTES) return@mapNotNull null
            val bytes = try { f.readBytes() } catch (e: Exception) { return@mapNotNull null }
            BackupFile(uniqueName(f.name, usedNames), path, bytes)
        }
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return LauncherBackup(
            createdAtMillis = System.currentTimeMillis(),
            appVersionName = info.versionName,
            appVersionCode = versionCode,
            sourcePackage = context.packageName,
            prefs = prefs,
            appStats = stats,
            files = files
        )
    }

    /** Encodes, round-trips the result through the validator, then writes it to [uri]. */
    suspend fun exportTo(uri: Uri): LauncherBackup {
        val backup = createBackup()
        val json = BackupCodec.encode(backup)
        BackupCodec.decode(json)
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw BackupException("Could not open the chosen file for writing.")
        out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return backup
    }

    fun read(uri: Uri): LauncherBackup {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw BackupException("Could not open the chosen file.")
        val bytes = input.use { stream ->
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > BackupCodec.MAX_BACKUP_BYTES) throw BackupException("The file is too large to be a 710 Launcher backup.")
            }
            buf.toByteArray()
        }
        return BackupCodec.decode(String(bytes, Charsets.UTF_8))
    }

    fun missingPackages(backup: LauncherBackup): List<String> {
        val pm = context.packageManager
        return BackupCodec.referencedPackages(backup).filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                false
            } catch (_: PackageManager.NameNotFoundException) {
                true
            }
        }.sorted()
    }

    /**
     * All-or-nothing: the database is replaced in one transaction and each prefs file with one
     * `commit()`. If any step fails, the snapshot taken first is written back and the staged icon
     * files are deleted, so the launcher is left exactly as it was.
     */
    suspend fun restore(backup: LauncherBackup): RestoreReport {
        val snapshot = createBackup()
        saveSafetySnapshot(snapshot)

        val stamp = System.currentTimeMillis()
        val staged = mutableListOf<File>()
        val pathMap = LinkedHashMap<String, String>()
        try {
            for (f in backup.files) {
                val target = File(iconDir, "r${stamp}_${f.name}")
                target.writeBytes(f.bytes)
                staged += target
                f.originalPath?.let { pathMap[it] = target.absolutePath }
            }
        } catch (e: Exception) {
            staged.forEach { it.delete() }
            throw BackupException("Could not write the backup's icon files; nothing was changed.", e)
        }

        val prefs = backup.prefs.toMutableMap()
        var droppedWidgets = 0
        prefs[BackupCodec.SETTINGS_PREFS]?.let { settings ->
            val relocated = BackupCodec.relocateIconPaths(settings, pathMap)
            val (validated, dropped) = dropForeignWidgets(relocated)
            prefs[BackupCodec.SETTINGS_PREFS] = validated
            droppedWidgets = dropped
        }

        try {
            applyState(prefs, backup.appStats)
        } catch (e: Exception) {
            Log.e(TAG, "restore failed, rolling back", e)
            try {
                applyState(snapshot.prefs.filterKeys { it in prefs.keys }, snapshot.appStats.takeIf { backup.appStats != null })
            } catch (rollback: Exception) {
                Log.e(TAG, "rollback failed", rollback)
            }
            staged.forEach { it.delete() }
            throw BackupException("Restore failed and was rolled back; your current setup is unchanged.", e)
        }

        deleteOrphanIcons(prefs[BackupCodec.SETTINGS_PREFS] ?: prefsFile(BackupCodec.SETTINGS_PREFS).all.filterNonNull())
        withContext(Dispatchers.Main) {
            AppRepository.get(context).apply {
                forgetIconPacks()
                invalidate()
            }
        }
        return RestoreReport(
            prefCount = prefs.values.sumOf { it.size },
            statCount = backup.appStats?.size,
            fileCount = staged.size,
            missingPackages = missingPackages(backup),
            droppedWidgets = droppedWidgets
        )
    }

    private suspend fun applyState(prefs: Map<String, Map<String, Any>>, stats: List<AppStatRecord>?) {
        if (stats != null) {
            db.withTransaction {
                val dao = db.appStatsDao()
                dao.deleteAll()
                dao.insertAll(stats.map { AppStats(it.componentName, it.launchCount, it.isFavorite, it.lastLaunched, it.frecency, it.frecencyAt) })
            }
        }
        for ((name, values) in prefs) {
            val edit = prefsFile(name).edit().clear()
            for ((k, v) in values) {
                when (v) {
                    is String -> edit.putString(k, v)
                    is Int -> edit.putInt(k, v)
                    is Long -> edit.putLong(k, v)
                    is Float -> edit.putFloat(k, v)
                    is Boolean -> edit.putBoolean(k, v)
                    is Set<*> -> edit.putStringSet(k, v.mapTo(LinkedHashSet()) { it.toString() })
                }
            }
            if (!edit.commit()) throw BackupException("Could not save preferences \"$name\".")
        }
    }

    /**
     * Widget ids are allocated per device and per host, so ids from another install would point
     * at nothing (or at the wrong widget). Only ids this host currently owns survive.
     */
    private fun dropForeignWidgets(settings: Map<String, Any>): Pair<Map<String, Any>, Int> {
        val owned = try {
            AppWidgetHost(context, WidgetHost.HOST_ID).appWidgetIds.toSet()
        } catch (_: Exception) { emptySet() }
        val awm = AppWidgetManager.getInstance(context)
        val out = LinkedHashMap(settings)
        var dropped = 0
        for ((k, v) in settings) {
            if (!(k == "widgetData" || k.startsWith("widgetData_")) || v !is String) continue
            val arr = try { JSONArray(v) } catch (_: Exception) { continue }
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val id = arr.optInt(i, -1)
                if (id in owned && awm.getAppWidgetInfo(id) != null) kept.put(id) else dropped++
            }
            out[k] = kept.toString()
        }
        return out to dropped
    }

    private fun deleteOrphanIcons(settings: Map<String, Any>) {
        val referenced = BackupCodec.referencedIconPaths(settings)
        val candidates = (iconDir.listFiles()?.toList() ?: emptyList()) +
            (context.cacheDir.listFiles { f -> f.name.startsWith("shortcut_icon_") }?.toList() ?: emptyList())
        candidates.filter { it.isFile && it.absolutePath !in referenced }.forEach { it.delete() }
    }

    private fun saveSafetySnapshot(snapshot: LauncherBackup) {
        try {
            val dir = File(context.filesDir, SNAPSHOT_DIR).apply { mkdirs() }
            File(dir, "pre-restore-${snapshot.createdAtMillis}.json").writeText(BackupCodec.encode(snapshot))
            dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP_SNAPSHOTS)?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "could not save pre-restore snapshot", e)
        }
    }

    private fun snapshotPrefs(sp: SharedPreferences): Map<String, Any> =
        sp.all.filterNonNull().mapValues { (_, v) -> if (v is Set<*>) v.mapTo(LinkedHashSet()) { it.toString() } else v }

    private fun Map<String, *>.filterNonNull(): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for ((k, v) in this) if (v != null) out[k] = v
        return out
    }

    private fun uniqueName(raw: String, used: MutableSet<String>): String {
        val base = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifEmpty { "icon" }.trimStart('.').ifEmpty { "icon" }
        var name = base
        var i = 1
        while (!used.add(name)) name = "${i++}_$base"
        return name
    }

    companion object {
        private const val TAG = "LauncherBackup"
        /** Pinned-shortcut icons live here (not cacheDir, which the system may clear). */
        const val ICON_DIR = "shortcut_icons"
        private const val SNAPSHOT_DIR = "backups"
        private const val KEEP_SNAPSHOTS = 3
    }
}
