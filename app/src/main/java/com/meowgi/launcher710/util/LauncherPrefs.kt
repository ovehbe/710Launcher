package com.meowgi.launcher710.util

import android.content.Context
import android.content.SharedPreferences

class LauncherPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("meowgi_launcher710_settings", Context.MODE_PRIVATE)

    // --- Opacity (0–255 alpha) ---
    /** Main background overlay alpha. 0 = fully transparent (wallpaper visible), 255 = solid black. */
    var mainBackgroundAlpha: Int
        get() = prefs.getInt("mainBackgroundAlpha", 0)
        set(v) = prefs.edit().putInt("mainBackgroundAlpha", v).apply()

    var statusBarAlpha: Int
        get() = prefs.getInt("statusBarAlpha", 125)
        set(v) = prefs.edit().putInt("statusBarAlpha", v).apply()

    var headerAlpha: Int
        get() = prefs.getInt("headerAlpha", 125)
        set(v) = prefs.edit().putInt("headerAlpha", v).apply()

    var tabBarAlpha: Int
        get() = prefs.getInt("tabBarAlpha", 125)
        set(v) = prefs.edit().putInt("tabBarAlpha", v).apply()

    var dockAlpha: Int
        get() = prefs.getInt("dockAlpha", 230)
        set(v) = prefs.edit().putInt("dockAlpha", v).apply()

    var dockBackgroundColor: Int
        get() = prefs.getInt("dockBackgroundColor", 0xFF000000.toInt())
        set(v) = prefs.edit().putInt("dockBackgroundColor", v).apply()

    var accentColor: Int
        get() = prefs.getInt("accentColor", 0xFF0073BC.toInt())
        set(v) = prefs.edit().putInt("accentColor", v).apply()

    // --- Grid ---
    var gridColumns: Int
        get() = prefs.getInt("gridColumns", 6)
        set(v) = prefs.edit().putInt("gridColumns", v).apply()

    var iconSizeIndex: Int
        get() = prefs.getInt("iconSizeIndex", 1)
        set(v) = prefs.edit().putInt("iconSizeIndex", v).apply()

    val iconSizeDp: Int
        get() = when (iconSizeIndex) { 0 -> 38; 2 -> 54; else -> 46 }

    // --- App View Mode ---
    var appViewMode: Int
        get() = prefs.getInt("appViewMode", 0) // 0 = grid, 1 = list
        set(v) = prefs.edit().putInt("appViewMode", v).apply()

    var appViewModeAllOnly: Boolean
        get() = prefs.getBoolean("appViewModeAllOnly", false)
        set(v) = prefs.edit().putBoolean("appViewModeAllOnly", v).apply()

    fun getListViewPages(): Set<String> {
        val data = prefs.getStringSet("listViewPages", null) ?: return emptySet()
        return data
    }

    fun setListViewPages(pages: Set<String>) {
        prefs.edit().putStringSet("listViewPages", pages).apply()
    }

    var listViewColumns: Int
        get() = prefs.getInt("listViewColumns", 2)
        set(v) = prefs.edit().putInt("listViewColumns", v).apply()

    var listViewBgAlpha: Int
        get() = prefs.getInt("listViewBgAlpha", 200)
        set(v) = prefs.edit().putInt("listViewBgAlpha", v).apply()

    // --- Icon pack ---
    var iconPackPackage: String?
        get() = prefs.getString("iconPackPackage", null)
        set(v) = prefs.edit().putString("iconPackPackage", v).apply()

    var allPageIconPackPackage: String?
        get() = prefs.getString("allPageIconPackPackage", null)
        set(v) = prefs.edit().putString("allPageIconPackPackage", v).apply()

    var dockIconPackPackage: String?
        get() = prefs.getString("dockIconPackPackage", null)
        set(v) = prefs.edit().putString("dockIconPackPackage", v).apply()

    fun getPageIconPackPackage(pageId: String): String? {
        return prefs.getString("iconPack_$pageId", null)
    }

    fun setPageIconPackPackage(pageId: String, pkg: String?) {
        prefs.edit().putString("iconPack_$pageId", pkg).apply()
    }

    var iconFallbackShape: Int
        get() = prefs.getInt("iconFallbackShape", SHAPE_ROUNDED_SQUARE)
        set(v) = prefs.edit().putInt("iconFallbackShape", v).apply()

    // --- UI ---
    /** In-app BB-style status bar visibility. */
    var statusBarVisible: Boolean
        get() = prefs.getBoolean("statusBarVisible", true)
        set(v) = prefs.edit().putBoolean("statusBarVisible", v).apply()

    /** Native Android system status bar visibility. */
    var systemStatusBarVisible: Boolean
        get() = prefs.getBoolean("systemStatusBarVisible", true)
        set(v) = prefs.edit().putBoolean("systemStatusBarVisible", v).apply()

    var systemStatusBarAlpha: Int
        get() = prefs.getInt("systemStatusBarAlpha", 0)
        set(v) = prefs.edit().putInt("systemStatusBarAlpha", v).apply()

    // --- Behavior ---
    var defaultTab: Int
        get() = prefs.getInt("defaultTab", 1)
        set(v) = prefs.edit().putInt("defaultTab", v).apply()

    var doubleTapAction: Int
        get() = prefs.getInt("doubleTapAction", 0)
        set(v) = prefs.edit().putInt("doubleTapAction", v).apply()

    var searchOnType: Boolean
        get() = prefs.getBoolean("searchOnType", true)
        set(v) = prefs.edit().putBoolean("searchOnType", v).apply()

    // --- Custom icons ---
    fun getCustomIcon(componentName: String, pageId: String? = null): String? {
        // First check per-page custom icon
        if (pageId != null) {
            val pageMap = prefs.getString("customIcons_$pageId", null)
            if (pageMap != null) {
                try {
                    val obj = org.json.JSONObject(pageMap)
                    if (obj.has(componentName)) return obj.getString(componentName)
                } catch (_: Exception) { }
            }
        }
        // Fall back to global custom icon
        val map = prefs.getString("customIcons", null) ?: return null
        return try {
            val obj = org.json.JSONObject(map)
            if (obj.has(componentName)) obj.getString(componentName) else null
        } catch (_: Exception) { null }
    }

    fun setCustomIcon(componentName: String, drawableName: String?, pageId: String? = null) {
        if (pageId != null) {
            // Store per-page custom icon
            val obj = try { org.json.JSONObject(prefs.getString("customIcons_$pageId", null) ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
            if (drawableName != null) obj.put(componentName, drawableName) else obj.remove(componentName)
            prefs.edit().putString("customIcons_$pageId", obj.toString()).apply()
        } else {
            // Store global custom icon
            val obj = try { org.json.JSONObject(prefs.getString("customIcons", null) ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
            if (drawableName != null) obj.put(componentName, drawableName) else obj.remove(componentName)
            prefs.edit().putString("customIcons", obj.toString()).apply()
        }
    }

    // --- Pages ---
    var customPages: String?
        get() = prefs.getString("customPages", null)
        set(v) = prefs.edit().putString("customPages", v).apply()

    fun getPageOrder(): List<String> {
        val data = prefs.getString("pageOrder", null) ?: return listOf("frequent", "favorites", "all")
        return try { val arr = org.json.JSONArray(data); (0 until arr.length()).map { arr.getString(it) } }
        catch (_: Exception) { listOf("frequent", "favorites", "all") }
    }

    fun setPageOrder(order: List<String>) {
        val arr = org.json.JSONArray()
        order.forEach { arr.put(it) }
        prefs.edit().putString("pageOrder", arr.toString()).apply()
    }

    // --- Per-page app membership (for Favorites and custom pages) ---
    fun getPageApps(pageId: String): Set<String> {
        return prefs.getStringSet("pageApps_$pageId", null) ?: emptySet()
    }

    fun setPageApps(pageId: String, apps: Set<String>) {
        prefs.edit().putStringSet("pageApps_$pageId", apps).apply()
    }

    fun isAppOnPage(componentName: String, pageId: String): Boolean {
        if (pageId == "favorites") {
            // Legacy: check the Room DB isFavorite flag too (handled in AppRepository)
            return getPageApps(pageId).contains(componentName)
        }
        return getPageApps(pageId).contains(componentName)
    }

    fun toggleAppOnPage(componentName: String, pageId: String): Boolean {
        val current = getPageApps(pageId).toMutableSet()
        val added = if (current.contains(componentName)) {
            current.remove(componentName)
            false
        } else {
            current.add(componentName)
            true
        }
        setPageApps(pageId, current)
        return added
    }

    fun isPageScrollable(pageId: String): Boolean = prefs.getBoolean("pageScroll_$pageId", false)
    fun setPageScrollable(pageId: String, value: Boolean) = prefs.edit().putBoolean("pageScroll_$pageId", value).apply()

    // --- Widgets (per-page) ---
    fun getPageWidgetData(pageId: String): String? = prefs.getString("widgetData_$pageId", null)
    fun setPageWidgetData(pageId: String, data: String?) = prefs.edit().putString("widgetData_$pageId", data).apply()
    fun getPageWidgetHeights(pageId: String): String? = prefs.getString("widgetHeights_$pageId", null)
    fun setPageWidgetHeights(pageId: String, data: String?) = prefs.edit().putString("widgetHeights_$pageId", data).apply()

    @Deprecated("Use per-page widget data")
    var widgetData: String?
        get() = prefs.getString("widgetData", null)
        set(v) = prefs.edit().putString("widgetData", v).apply()

    @Deprecated("Use per-page widget heights")
    var widgetHeights: String?
        get() = prefs.getString("widgetHeights", null)
        set(v) = prefs.edit().putString("widgetHeights", v).apply()

    // --- Vertical scroll ---
    var verticalScrollEnabled: Boolean
        get() = prefs.getBoolean("verticalScrollEnabled", false)
        set(v) = prefs.edit().putBoolean("verticalScrollEnabled", v).apply()

    fun resetAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val SHAPE_CIRCLE = 0
        const val SHAPE_ROUNDED_SQUARE = 1
        const val SHAPE_SQUARE = 2
        const val SHAPE_SQUIRCLE = 3
    }
}
