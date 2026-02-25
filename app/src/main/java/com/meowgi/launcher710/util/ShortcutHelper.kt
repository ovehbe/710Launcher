package com.meowgi.launcher710.util

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Process
import com.meowgi.launcher710.model.IntentShortcutInfo
import com.meowgi.launcher710.model.ShortcutDisplayInfo

/**
 * Resolves app shortcuts (ShortcutInfo) via LauncherApps for display and launch.
 */
class ShortcutHelper(private val context: Context) {

    private val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
    private val user = Process.myUserHandle()

    /** All shortcuts for an app: static (long-press), dynamic (e.g. per-macro), pinned, and cached. */
    fun getShortcutsForPackage(packageName: String): List<android.content.pm.ShortcutInfo> {
        if (launcherApps == null) return emptyList()
        val query = LauncherApps.ShortcutQuery().apply {
            setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED_BY_ANY_LAUNCHER
            )
            setPackage(packageName)
        }
        return try {
            launcherApps.getShortcuts(query, user) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun resolveShortcut(packageName: String, shortcutId: String): ShortcutDisplayInfo? {
        if (launcherApps == null) return null
        val query = LauncherApps.ShortcutQuery().apply {
            setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED
            )
            setPackage(packageName)
            setShortcutIds(listOf(shortcutId))
        }
        val list = try {
            launcherApps.getShortcuts(query, user) ?: emptyList()
        } catch (_: Exception) { emptyList() }
        val info = list.firstOrNull() ?: return null
        val density = context.resources.displayMetrics.densityDpi
        val icon = try {
            launcherApps.getShortcutIconDrawable(info, density)
                ?: context.packageManager.getDefaultActivityIcon()
        } catch (_: Exception) { context.packageManager.getDefaultActivityIcon() }
        return ShortcutDisplayInfo(
            packageName = packageName,
            shortcutId = info.id,
            label = info.shortLabel ?: info.longLabel ?: info.id,
            icon = icon
        )
    }

    fun getShortcutsForPage(pageId: String, prefs: LauncherPrefs): List<ShortcutDisplayInfo> {
        val keys = prefs.getPageShortcuts(pageId)
        return keys.mapNotNull { key ->
            val parts = key.split("|", limit = 2)
            if (parts.size != 2) null else resolveShortcut(parts[0], parts[1])
        }
    }

    fun launchShortcut(packageName: String, shortcutId: String): Boolean {
        if (launcherApps == null) return false
        return try {
            launcherApps.startShortcut(packageName, shortcutId, null, null, user)
            true
        } catch (_: Exception) { false }
    }

    /** Intent shortcuts from system "Add shortcut" / "All shortcuts" flow. */
    fun getIntentShortcutsForPage(pageId: String, prefs: LauncherPrefs): List<IntentShortcutInfo> {
        val json = prefs.getPageIntentShortcuts(pageId) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name", "")
                val intentUri = obj.optString("intentUri", "")
                val iconFile = obj.optString("iconFile", "").takeIf { it.isNotEmpty() }
                if (intentUri.isEmpty()) return@mapNotNull null
                val icon = loadIntentShortcutIcon(iconFile, intentUri)
                IntentShortcutInfo(label = name.ifEmpty { "Shortcut" }, intentUri = intentUri, icon = icon)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun loadIntentShortcutIcon(iconFilePath: String?, intentUri: String): android.graphics.drawable.Drawable {
        if (!iconFilePath.isNullOrEmpty()) {
            val f = java.io.File(iconFilePath)
            if (f.exists()) {
                try {
                    val bmp = BitmapFactory.decodeFile(f.absolutePath)
                    if (bmp != null) return BitmapDrawable(context.resources, bmp)
                } catch (_: Exception) { }
            }
        }
        return try {
            val intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME)
            val pkg = intent.`package` ?: intent.component?.packageName
            if (pkg != null) context.packageManager.getApplicationIcon(pkg) else context.packageManager.getDefaultActivityIcon()
        } catch (_: Exception) { context.packageManager.getDefaultActivityIcon() }
    }

    fun launchIntentShortcut(intentUri: String): Boolean {
        return try {
            val intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) { false }
    }
}
