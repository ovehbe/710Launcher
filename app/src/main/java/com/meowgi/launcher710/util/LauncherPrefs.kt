package com.meowgi.launcher710.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

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
        get() = prefs.getInt("dockAlpha", 166) // 65%
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
        get() = prefs.getInt("iconSizeIndex", 2) // 0 = small, 1 = medium, 2 = large
        set(v) = prefs.edit().putInt("iconSizeIndex", v).apply()

    val iconSizeDp: Int
        get() = when (iconSizeIndex) { 0 -> 38; 2 -> 54; else -> 46 }

    // --- App View Mode ---
    var appViewMode: Int
        get() = prefs.getInt("appViewMode", 1) // 0 = grid, 1 = list (old school)
        set(v) = prefs.edit().putInt("appViewMode", v).apply()

    var appViewModeAllOnly: Boolean
        get() = prefs.getBoolean("appViewModeAllOnly", false)
        set(v) = prefs.edit().putBoolean("appViewModeAllOnly", v).apply()

    fun getListViewPages(): Set<String> {
        val data = prefs.getStringSet("listViewPages", null) ?: return setOf("frequent", "all")
        return data
    }

    fun setListViewPages(pages: Set<String>) {
        prefs.edit().putStringSet("listViewPages", pages).apply()
    }

    var listViewColumns: Int
        get() = prefs.getInt("listViewColumns", 3)
        set(v) = prefs.edit().putInt("listViewColumns", v).apply()

    var listViewBgAlpha: Int
        get() = prefs.getInt("listViewBgAlpha", 200)
        set(v) = prefs.edit().putInt("listViewBgAlpha", v).apply()

    /** true = use accent color for list icon bar; false = use listViewCustomColor */
    var listViewUseAccent: Boolean
        get() = prefs.getBoolean("listViewUseAccent", false)
        set(v) = prefs.edit().putBoolean("listViewUseAccent", v).apply()

    var listViewCustomColor: Int
        get() = prefs.getInt("listViewCustomColor", 0xFF000000.toInt())
        set(v) = prefs.edit().putInt("listViewCustomColor", v).apply()

    /** Opacity (0–255) for the colored icon bar background in list view */
    var listViewIconBarAlpha: Int
        get() = prefs.getInt("listViewIconBarAlpha", 77) // 30%
        set(v) = prefs.edit().putInt("listViewIconBarAlpha", v).apply()

    /** Opacity (0–255) for the name bar background in list view */
    var listViewNameBarAlpha: Int
        get() = prefs.getInt("listViewNameBarAlpha", prefs.getInt("listViewBgAlpha", 199)) // 78% default
        set(v) = prefs.edit().putInt("listViewNameBarAlpha", v).apply()

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

    fun getDockSwipeAction(slotIndex: Int): String? =
        prefs.getString("dockSwipeAction_$slotIndex", null)

    fun setDockSwipeAction(slotIndex: Int, value: String?) =
        prefs.edit().putString("dockSwipeAction_$slotIndex", value).apply()

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
        get() = prefs.getBoolean("statusBarVisible", false)
        set(v) = prefs.edit().putBoolean("statusBarVisible", v).apply()

    var statusBarShowClock: Boolean
        get() = prefs.getBoolean("statusBarShowClock", true)
        set(v) = prefs.edit().putBoolean("statusBarShowClock", v).apply()
    var statusBarShowBattery: Boolean
        get() = prefs.getBoolean("statusBarShowBattery", true)
        set(v) = prefs.edit().putBoolean("statusBarShowBattery", v).apply()
    var statusBarShowNetwork: Boolean
        get() = prefs.getBoolean("statusBarShowNetwork", true)
        set(v) = prefs.edit().putBoolean("statusBarShowNetwork", v).apply()
    var statusBarShowBluetooth: Boolean
        get() = prefs.getBoolean("statusBarShowBluetooth", false)
        set(v) = prefs.edit().putBoolean("statusBarShowBluetooth", v).apply()
    var statusBarShowAlarm: Boolean
        get() = prefs.getBoolean("statusBarShowAlarm", false)
        set(v) = prefs.edit().putBoolean("statusBarShowAlarm", v).apply()
    var statusBarShowDND: Boolean
        get() = prefs.getBoolean("statusBarShowDND", false)
        set(v) = prefs.edit().putBoolean("statusBarShowDND", v).apply()

    /** Native Android system status bar visibility. */
    var systemStatusBarVisible: Boolean
        get() = prefs.getBoolean("systemStatusBarVisible", true)
        set(v) = prefs.edit().putBoolean("systemStatusBarVisible", v).apply()

    var systemStatusBarAlpha: Int
        get() = prefs.getInt("systemStatusBarAlpha", 0)
        set(v) = prefs.edit().putInt("systemStatusBarAlpha", v).apply()

    /** Show system navigation bar; false = hidden (default, e.g. Zinwa Q25). */
    var navigationBarVisible: Boolean
        get() = prefs.getBoolean("navigationBarVisible", false)
        set(v) = prefs.edit().putBoolean("navigationBarVisible", v).apply()

    /** Opacity (0–255) for the action bar (ticker bar / top icons row). */
    var actionBarAlpha: Int
        get() = prefs.getInt("actionBarAlpha", 69) // 27%
        set(v) = prefs.edit().putInt("actionBarAlpha", v).apply()

    /** Opacity (0–255) for the notification hub overlay. */
    var notificationHubAlpha: Int
        get() = prefs.getInt("notificationHubAlpha", 179) // 70%
        set(v) = prefs.edit().putInt("notificationHubAlpha", v).apply()

    /** Opacity (0–255) for the search overlay. */
    var searchOverlayAlpha: Int
        get() = prefs.getInt("searchOverlayAlpha", 179) // 70%
        set(v) = prefs.edit().putInt("searchOverlayAlpha", v).apply()

    /** Package names to show in hub/ticker; empty = all. */
    fun getNotificationAppWhitelist(): Set<String> =
        prefs.getStringSet("notificationAppWhitelist", null) ?: emptySet()

    fun setNotificationAppWhitelist(packages: Set<String>) =
        prefs.edit().putStringSet("notificationAppWhitelist", packages).apply()

    // --- Behavior ---
    /** 0 = bottom bar only, 1 = anywhere on screen */
    var swipeMode: Int
        get() = prefs.getInt("swipeMode", 0)
        set(v) = prefs.edit().putInt("swipeMode", v).apply()

    var defaultTab: Int
        get() = prefs.getInt("defaultTab", 1)
        set(v) = prefs.edit().putInt("defaultTab", v).apply()

    /** 0 = alphabetical, 1 = last opened, 2 = last installed, 3 = most used */
    var appSortMode: Int
        get() = prefs.getInt("appSortMode", 0)
        set(v) = prefs.edit().putInt("appSortMode", v).apply()

    /** Empty or "__all__" = apply sort everywhere; else set of page IDs. */
    fun getSortApplyPages(): Set<String> {
        val data = prefs.getStringSet("sortApplyPages", null) ?: return emptySet()
        return data
    }

    fun setSortApplyPages(pages: Set<String>) {
        prefs.edit().putStringSet("sortApplyPages", pages).apply()
    }

    var doubleTapAction: Int
        get() = prefs.getInt("doubleTapAction", 0)
        set(v) = prefs.edit().putInt("doubleTapAction", v).apply()

    var searchOnType: Boolean
        get() = prefs.getBoolean("searchOnType", true)
        set(v) = prefs.edit().putBoolean("searchOnType", v).apply()

    /** 0 = built-in, 1 = launch app, 2 = launch with query, 3 = launch shortcut, 4 = launch and inject key, 5 = disabled */
    var searchEngineMode: Int
        get() = prefs.getInt("searchEngineMode", 0)
        set(v) = prefs.edit().putInt("searchEngineMode", v).apply()

    var searchEnginePackage: String?
        get() = prefs.getString("searchEnginePackage", null)
        set(v) = prefs.edit().putString("searchEnginePackage", v).apply()

    var searchEngineIntentUri: String?
        get() = prefs.getString("searchEngineIntentUri", null)
        set(v) = prefs.edit().putString("searchEngineIntentUri", v).apply()

    var searchEngineShortcutIntentUri: String?
        get() = prefs.getString("searchEngineShortcutIntentUri", null)
        set(v) = prefs.edit().putString("searchEngineShortcutIntentUri", v).apply()

    var searchEngineShortcutName: String?
        get() = prefs.getString("searchEngineShortcutName", null)
        set(v) = prefs.edit().putString("searchEngineShortcutName", v).apply()

    var searchEngineLaunchInjectIntentUri: String?
        get() = prefs.getString("searchEngineLaunchInjectIntentUri", null)
        set(v) = prefs.edit().putString("searchEngineLaunchInjectIntentUri", v).apply()

    var searchEngineLaunchInjectName: String?
        get() = prefs.getString("searchEngineLaunchInjectName", null)
        set(v) = prefs.edit().putString("searchEngineLaunchInjectName", v).apply()

    /** Delay in ms before injecting the key (default 50). Used only when wait-for-focus is off. Max 5000. */
    var searchEngineLaunchInjectDelayMs: Int
        get() = prefs.getInt("searchEngineLaunchInjectDelayMs", 50).coerceIn(0, 5000)
        set(v) = prefs.edit().putInt("searchEngineLaunchInjectDelayMs", v.coerceIn(0, 5000)).apply()

    /** When true, inject key when the launched app has an input focused (recommended). When false, use fixed delay. */
    var searchEngineLaunchInjectWaitForFocus: Boolean
        get() = prefs.getBoolean("searchEngineLaunchInjectWaitForFocus", true)
        set(v) = prefs.edit().putBoolean("searchEngineLaunchInjectWaitForFocus", v).apply()

    /** When true, use root shell "input keyevent" to inject (requires root). Ignores accessibility inject. */
    var searchEngineLaunchInjectUseRoot: Boolean
        get() = prefs.getBoolean("searchEngineLaunchInjectUseRoot", false)
        set(v) = prefs.edit().putBoolean("searchEngineLaunchInjectUseRoot", v).apply()

    /** When true (and root injection on), capture keys in a short window and inject as text. Off by default. */
    var searchEngineLaunchInjectAlternativeListener: Boolean
        get() = prefs.getBoolean("searchEngineLaunchInjectAlternativeListener", false)
        set(v) = prefs.edit().putBoolean("searchEngineLaunchInjectAlternativeListener", v).apply()

    /** Burst window (ms) for alternative listener: after this much idle time, inject captured text. 0–500, default 120. */
    var searchEngineLaunchInjectAlternativeWindowMs: Int
        get() = prefs.getInt("searchEngineLaunchInjectAlternativeWindowMs", 120).coerceIn(0, 500)
        set(v) = prefs.edit().putInt("searchEngineLaunchInjectAlternativeWindowMs", v.coerceIn(0, 500)).apply()

    // --- Key shortcuts (recorded key codes) ---
    var keyShortcutsEnabled: Boolean
        get() = prefs.getBoolean("keyShortcutsEnabled", false)
        set(v) = prefs.edit().putBoolean("keyShortcutsEnabled", v).apply()

    var keyCodeHome: Int
        get() = prefs.getInt("keyCodeHome", android.view.KeyEvent.KEYCODE_HOME)
        set(v) = prefs.edit().putInt("keyCodeHome", v).apply()

    var keyCodeBack: Int
        get() = prefs.getInt("keyCodeBack", android.view.KeyEvent.KEYCODE_BACK)
        set(v) = prefs.edit().putInt("keyCodeBack", v).apply()

    var keyCodeRecents: Int
        get() = prefs.getInt("keyCodeRecents", android.view.KeyEvent.KEYCODE_APP_SWITCH)
        set(v) = prefs.edit().putInt("keyCodeRecents", v).apply()

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

    // --- Custom labels (same pattern as custom icons: per-page or global) ---
    fun getCustomLabel(componentName: String, pageId: String? = null): String? {
        if (pageId != null) {
            val pageMap = prefs.getString("customLabels_$pageId", null) ?: return null
            return try { org.json.JSONObject(pageMap).optString(componentName, "").takeIf { it.isNotEmpty() } } catch (_: Exception) { null }
        }
        val map = prefs.getString("customLabels", null) ?: return null
        return try { org.json.JSONObject(map).optString(componentName, "").takeIf { it.isNotEmpty() } } catch (_: Exception) { null }
    }

    fun setCustomLabel(componentName: String, label: String?, pageId: String? = null) {
        if (pageId != null) {
            val obj = try { org.json.JSONObject(prefs.getString("customLabels_$pageId", null) ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
            if (label != null) obj.put(componentName, label) else obj.remove(componentName)
            prefs.edit().putString("customLabels_$pageId", obj.toString()).apply()
        } else {
            val obj = try { org.json.JSONObject(prefs.getString("customLabels", null) ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
            if (label != null) obj.put(componentName, label) else obj.remove(componentName)
            prefs.edit().putString("customLabels", obj.toString()).apply()
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

    /** Ordered list of favorite app component names (for drag-to-reorder on Favorites tab). */
    fun getFavoriteOrder(): List<String> {
        val data = prefs.getString("favoriteOrder", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(data)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    fun setFavoriteOrder(componentNames: List<String>) {
        val arr = org.json.JSONArray()
        componentNames.forEach { arr.put(it) }
        prefs.edit().putString("favoriteOrder", arr.toString()).apply()
    }

    /** Ordered list of app component names for custom pages (drag-to-reorder). */
    fun getPageAppOrder(pageId: String): List<String> {
        val data = prefs.getString("pageAppOrder_$pageId", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(data)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    fun setPageAppOrder(pageId: String, componentNames: List<String>) {
        val arr = org.json.JSONArray()
        componentNames.forEach { arr.put(it) }
        prefs.edit().putString("pageAppOrder_$pageId", arr.toString()).apply()
    }

    fun isPageScrollable(pageId: String): Boolean = prefs.getBoolean("pageScroll_$pageId", false)
    fun setPageScrollable(pageId: String, value: Boolean) = prefs.edit().putBoolean("pageScroll_$pageId", value).apply()

    /** true = widgets appear below app grid; false = above (default) */
    fun isPageWidgetsBelowApps(pageId: String): Boolean = prefs.getBoolean("widgetsBelowApps_$pageId", false)
    fun setPageWidgetsBelowApps(pageId: String, value: Boolean) = prefs.edit().putBoolean("widgetsBelowApps_$pageId", value).apply()

    // --- Per-page app shortcuts (ShortcutInfo pins) ---
    /** Returns list of "packageName|shortcutId" for the page. */
    fun getPageShortcuts(pageId: String): List<String> {
        val data = prefs.getString("pageShortcuts_$pageId", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(data)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    fun setPageShortcuts(pageId: String, items: List<String>) {
        val arr = org.json.JSONArray()
        items.forEach { arr.put(it) }
        prefs.edit().putString("pageShortcuts_$pageId", arr.toString()).apply()
    }

    fun addShortcutToPage(pageId: String, packageName: String, shortcutId: String) {
        val key = "$packageName|$shortcutId"
        val current = getPageShortcuts(pageId).toMutableList()
        if (key !in current) {
            current.add(key)
            setPageShortcuts(pageId, current)
        }
    }

    fun removeShortcutFromPage(pageId: String, packageName: String, shortcutId: String) {
        val key = "$packageName|$shortcutId"
        val current = getPageShortcuts(pageId).filter { it != key }
        setPageShortcuts(pageId, current)
    }

    // --- Per-page intent shortcuts (from system "Add shortcut" / "All shortcuts" flow) ---
    /** Returns JSON array of { "name", "intentUri", "iconFile"? } */
    fun getPageIntentShortcuts(pageId: String): String? =
        prefs.getString("pageIntentShortcuts_$pageId", null)

    fun setPageIntentShortcuts(pageId: String, json: String) =
        prefs.edit().putString("pageIntentShortcuts_$pageId", json).apply()

    fun addIntentShortcutToPage(pageId: String, name: String, intentUri: String, iconFilePath: String? = null) {
        val arr = try {
            org.json.JSONArray(getPageIntentShortcuts(pageId) ?: "[]")
        } catch (_: Exception) { org.json.JSONArray() }
        val obj = org.json.JSONObject().apply {
            put("name", name)
            put("intentUri", intentUri)
            if (iconFilePath != null) put("iconFile", iconFilePath)
        }
        arr.put(obj)
        setPageIntentShortcuts(pageId, arr.toString())
    }

    fun removeIntentShortcutFromPage(pageId: String, intentUri: String) {
        val arr = try {
            org.json.JSONArray(getPageIntentShortcuts(pageId) ?: "[]")
        } catch (_: Exception) { org.json.JSONArray() }
        val newArr = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("intentUri") != intentUri) newArr.put(obj)
        }
        setPageIntentShortcuts(pageId, newArr.toString())
    }

    // --- Widgets (per-page) ---
    fun getPageWidgetData(pageId: String): String? = prefs.getString("widgetData_$pageId", null)
    fun setPageWidgetData(pageId: String, data: String?) = prefs.edit().putString("widgetData_$pageId", data).apply()
    fun getPageWidgetHeights(pageId: String): String? = prefs.getString("widgetHeights_$pageId", null)
    fun setPageWidgetHeights(pageId: String, data: String?) = prefs.edit().putString("widgetHeights_$pageId", data).apply()
    /** JSON object: {"widgetId":{"w":widthPx,"h":heightPx}}. Used for resize; falls back to widgetHeights for h if missing. */
    fun getPageWidgetSizes(pageId: String): String? = prefs.getString("widgetSizes_$pageId", null)
    fun setPageWidgetSizes(pageId: String, data: String?) = prefs.edit().putString("widgetSizes_$pageId", data).apply()

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

    /** Set of keys that have fixed names (not dynamic like pageApps_*). Export explicitly includes these so defaults are captured. */
    private val fixedExportKeys: Set<String> = setOf(
        "mainBackgroundAlpha", "statusBarAlpha", "headerAlpha", "tabBarAlpha", "dockAlpha", "dockBackgroundColor", "accentColor",
        "gridColumns", "iconSizeIndex", "appViewMode", "appViewModeAllOnly", "listViewPages", "listViewColumns", "listViewBgAlpha",
        "listViewUseAccent", "listViewCustomColor", "listViewIconBarAlpha", "listViewNameBarAlpha",
        "iconPackPackage", "allPageIconPackPackage", "dockIconPackPackage", "iconFallbackShape",
        "statusBarVisible", "statusBarShowClock", "statusBarShowBattery", "statusBarShowNetwork", "statusBarShowBluetooth",
        "statusBarShowAlarm", "statusBarShowDND", "systemStatusBarVisible", "systemStatusBarAlpha", "navigationBarVisible",
        "actionBarAlpha", "notificationHubAlpha", "searchOverlayAlpha", "notificationAppWhitelist",
        "swipeMode", "defaultTab", "appSortMode", "sortApplyPages", "doubleTapAction", "searchOnType",
        "searchEngineMode", "searchEnginePackage", "searchEngineIntentUri", "searchEngineShortcutIntentUri", "searchEngineShortcutName",
        "searchEngineLaunchInjectIntentUri", "searchEngineLaunchInjectName", "searchEngineLaunchInjectDelayMs",
        "searchEngineLaunchInjectWaitForFocus", "searchEngineLaunchInjectUseRoot", "searchEngineLaunchInjectAlternativeListener",
        "searchEngineLaunchInjectAlternativeWindowMs", "keyShortcutsEnabled", "keyCodeHome", "keyCodeBack", "keyCodeRecents",
        "customIcons", "customLabels", "customPages", "pageOrder", "favoriteOrder", "verticalScrollEnabled",
        "widgetData", "widgetHeights"
    )

    private fun putEntry(arr: JSONArray, k: String, t: String, v: Any?) {
        if (v == null && t != "s") return
        val obj = JSONObject()
        obj.put("k", k)
        obj.put("t", t)
        when (t) {
            "s" -> obj.put("v", v?.toString() ?: "")
            "i" -> obj.put("v", (v as? Number)?.toInt() ?: 0)
            "l" -> obj.put("v", (v as? Number)?.toLong() ?: 0L)
            "f" -> obj.put("v", (v as? Number)?.toDouble() ?: 0.0)
            "b" -> obj.put("v", v as? Boolean ?: false)
            "set" -> obj.put("v", JSONArray((v as? Set<*>)?.map { it.toString() }?.toList() ?: emptyList<String>()))
            else -> return
        }
        arr.put(obj)
    }

    /** Export all settings to a JSON string (for backup/restore and import on another device). Includes every fixed key at current value plus all dynamic keys from prefs. */
    fun exportToJson(): String {
        val arr = JSONArray()
        // 1) Export every fixed key with its current value (so defaults are included)
        putEntry(arr, "mainBackgroundAlpha", "i", mainBackgroundAlpha)
        putEntry(arr, "statusBarAlpha", "i", statusBarAlpha)
        putEntry(arr, "headerAlpha", "i", headerAlpha)
        putEntry(arr, "tabBarAlpha", "i", tabBarAlpha)
        putEntry(arr, "dockAlpha", "i", dockAlpha)
        putEntry(arr, "dockBackgroundColor", "i", dockBackgroundColor)
        putEntry(arr, "accentColor", "i", accentColor)
        putEntry(arr, "gridColumns", "i", gridColumns)
        putEntry(arr, "iconSizeIndex", "i", iconSizeIndex)
        putEntry(arr, "appViewMode", "i", appViewMode)
        putEntry(arr, "appViewModeAllOnly", "b", appViewModeAllOnly)
        putEntry(arr, "listViewPages", "set", getListViewPages())
        putEntry(arr, "listViewColumns", "i", listViewColumns)
        putEntry(arr, "listViewBgAlpha", "i", listViewBgAlpha)
        putEntry(arr, "listViewUseAccent", "b", listViewUseAccent)
        putEntry(arr, "listViewCustomColor", "i", listViewCustomColor)
        putEntry(arr, "listViewIconBarAlpha", "i", listViewIconBarAlpha)
        putEntry(arr, "listViewNameBarAlpha", "i", listViewNameBarAlpha)
        putEntry(arr, "iconPackPackage", "s", iconPackPackage)
        putEntry(arr, "allPageIconPackPackage", "s", allPageIconPackPackage)
        putEntry(arr, "dockIconPackPackage", "s", dockIconPackPackage)
        putEntry(arr, "iconFallbackShape", "i", iconFallbackShape)
        putEntry(arr, "statusBarVisible", "b", statusBarVisible)
        putEntry(arr, "statusBarShowClock", "b", statusBarShowClock)
        putEntry(arr, "statusBarShowBattery", "b", statusBarShowBattery)
        putEntry(arr, "statusBarShowNetwork", "b", statusBarShowNetwork)
        putEntry(arr, "statusBarShowBluetooth", "b", statusBarShowBluetooth)
        putEntry(arr, "statusBarShowAlarm", "b", statusBarShowAlarm)
        putEntry(arr, "statusBarShowDND", "b", statusBarShowDND)
        putEntry(arr, "systemStatusBarVisible", "b", systemStatusBarVisible)
        putEntry(arr, "systemStatusBarAlpha", "i", systemStatusBarAlpha)
        putEntry(arr, "navigationBarVisible", "b", navigationBarVisible)
        putEntry(arr, "actionBarAlpha", "i", actionBarAlpha)
        putEntry(arr, "notificationHubAlpha", "i", notificationHubAlpha)
        putEntry(arr, "searchOverlayAlpha", "i", searchOverlayAlpha)
        putEntry(arr, "notificationAppWhitelist", "set", getNotificationAppWhitelist())
        putEntry(arr, "swipeMode", "i", swipeMode)
        putEntry(arr, "defaultTab", "i", defaultTab)
        putEntry(arr, "appSortMode", "i", appSortMode)
        putEntry(arr, "sortApplyPages", "set", getSortApplyPages())
        putEntry(arr, "doubleTapAction", "i", doubleTapAction)
        putEntry(arr, "searchOnType", "b", searchOnType)
        putEntry(arr, "searchEngineMode", "i", searchEngineMode)
        putEntry(arr, "searchEnginePackage", "s", searchEnginePackage)
        putEntry(arr, "searchEngineIntentUri", "s", searchEngineIntentUri)
        putEntry(arr, "searchEngineShortcutIntentUri", "s", searchEngineShortcutIntentUri)
        putEntry(arr, "searchEngineShortcutName", "s", searchEngineShortcutName)
        putEntry(arr, "searchEngineLaunchInjectIntentUri", "s", searchEngineLaunchInjectIntentUri)
        putEntry(arr, "searchEngineLaunchInjectName", "s", searchEngineLaunchInjectName)
        putEntry(arr, "searchEngineLaunchInjectDelayMs", "i", searchEngineLaunchInjectDelayMs)
        putEntry(arr, "searchEngineLaunchInjectWaitForFocus", "b", searchEngineLaunchInjectWaitForFocus)
        putEntry(arr, "searchEngineLaunchInjectUseRoot", "b", searchEngineLaunchInjectUseRoot)
        putEntry(arr, "searchEngineLaunchInjectAlternativeListener", "b", searchEngineLaunchInjectAlternativeListener)
        putEntry(arr, "searchEngineLaunchInjectAlternativeWindowMs", "i", searchEngineLaunchInjectAlternativeWindowMs)
        putEntry(arr, "keyShortcutsEnabled", "b", keyShortcutsEnabled)
        putEntry(arr, "keyCodeHome", "i", keyCodeHome)
        putEntry(arr, "keyCodeBack", "i", keyCodeBack)
        putEntry(arr, "keyCodeRecents", "i", keyCodeRecents)
        putEntry(arr, "customIcons", "s", prefs.getString("customIcons", null))
        putEntry(arr, "customLabels", "s", prefs.getString("customLabels", null))
        putEntry(arr, "customPages", "s", customPages)
        putEntry(arr, "pageOrder", "s", prefs.getString("pageOrder", null))
        putEntry(arr, "favoriteOrder", "s", prefs.getString("favoriteOrder", null))
        putEntry(arr, "verticalScrollEnabled", "b", verticalScrollEnabled)
        putEntry(arr, "widgetData", "s", prefs.getString("widgetData", null))
        putEntry(arr, "widgetHeights", "s", prefs.getString("widgetHeights", null))
        // 2) Export all dynamic keys from prefs (pageApps_*, pageAppOrder_*, dockSwipeAction_*, iconPack_*, etc.) that aren't fixed
        for ((k, v) in prefs.all) {
            if (k in fixedExportKeys) continue
            val obj = JSONObject()
            obj.put("k", k)
            when (v) {
                is String -> { obj.put("t", "s"); obj.put("v", v) }
                is Int, is java.lang.Integer -> { obj.put("t", "i"); obj.put("v", (v as Number).toInt()) }
                is Long, is java.lang.Long -> { obj.put("t", "l"); obj.put("v", (v as Number).toLong()) }
                is Float, is Double, is java.lang.Float, is java.lang.Double -> { obj.put("t", "f"); obj.put("v", (v as Number).toDouble()) }
                is Boolean -> { obj.put("t", "b"); obj.put("v", v) }
                is Set<*> -> {
                    obj.put("t", "set")
                    obj.put("v", JSONArray(v.map { it.toString() }))
                }
                else -> continue
            }
            arr.put(obj)
        }
        return JSONObject().put("entries", arr).put("version", 1).toString()
    }

    /** Import settings from a JSON string (from exportToJson). Clears current prefs first. Returns true on success. */
    fun importFromJson(json: String): Boolean {
        return try {
            val root = JSONObject(json)
            val entries = root.getJSONArray("entries")
            val edit = prefs.edit()
            edit.clear()
            for (i in 0 until entries.length()) {
                val o = entries.getJSONObject(i)
                val k = o.getString("k")
                when (o.getString("t")) {
                    "s" -> edit.putString(k, o.getString("v"))
                    "i" -> edit.putInt(k, o.getInt("v"))
                    "l" -> edit.putLong(k, o.getLong("v"))
                    "f" -> edit.putFloat(k, o.getDouble("v").toFloat())
                    "b" -> edit.putBoolean(k, o.getBoolean("v"))
                    "set" -> {
                        val arr = o.getJSONArray("v")
                        val set = (0 until arr.length()).mapTo(mutableSetOf()) { arr.getString(it) }
                        edit.putStringSet(k, set)
                    }
                    else -> { }
                }
            }
            edit.apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val SHAPE_CIRCLE = 0
        const val SHAPE_ROUNDED_SQUARE = 1
        const val SHAPE_SQUARE = 2
        const val SHAPE_SQUIRCLE = 3
    }
}
