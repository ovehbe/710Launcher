package com.meowgi.launcher710.util

import android.content.*
import android.content.pm.PackageManager
import com.meowgi.launcher710.R
import com.meowgi.launcher710.model.AppDatabase
import com.meowgi.launcher710.model.AppInfo
import com.meowgi.launcher710.model.AppStats
import com.meowgi.launcher710.model.IntentShortcutInfo
import com.meowgi.launcher710.model.LaunchableItem
import com.meowgi.launcher710.model.ShortcutDisplayInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide: obtain it through [get]. The launcher activity is recreated on every density,
 * resolution, font-scale or locale change, and a per-activity repository came back empty each
 * time — icons vanished until a full PackageManager scan and icon-pack parse finished. Holding the
 * app list, icon packs and icon cache here lets the recreated activity render synchronously.
 */
class AppRepository private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: AppRepository? = null

        fun get(context: Context): AppRepository =
            instance ?: synchronized(this) {
                instance ?: AppRepository(context.applicationContext).also {
                    it.register()
                    instance = it
                }
            }
    }

    private val pm = context.packageManager
    private val dao = AppDatabase.get(context).appStatsDao()
    val iconPackManager = IconPackManager(context)
    val allPageIconPackManager = IconPackManager(context)
    val dockIconPackManager = IconPackManager(context)
    val pageIconPackManagers = mutableMapOf<String, IconPackManager>()
    /** Page-pack assignment that [pageIconPackManagers] was built from; null until first built. */
    var pageIconPackSignature: String? = null
    private val prefs = LauncherPrefs(context)

    /** Holds no pack state; exists only so shape rendering doesn't allocate a manager per call. */
    private val shapeRenderer = IconPackManager(context)

    /**
     * Rendered icons, keyed by component + page + shape + size. Without this, every RecyclerView
     * bind ran the icon through [applyGlobalShape], which allocates two bitmaps — scrolling the
     * All Apps page churned tens of megabytes a second and kept the process a prime LMK target.
     * Invalidated explicitly via [clearIconCache] whenever packs, custom icons, or shapes change.
     */
    private val iconCache = object : android.util.LruCache<String, android.graphics.drawable.Drawable>(
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(2L * 1024 * 1024, 16L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: android.graphics.drawable.Drawable): Int {
            val bmp = (value as? android.graphics.drawable.BitmapDrawable)?.bitmap
            return bmp?.byteCount ?: (value.intrinsicWidth * value.intrinsicHeight * 4).coerceAtLeast(1024)
        }
    }

    /** Icon packs referenced only by per-item custom icons, parsed once and reused. */
    private val adHocPacks = mutableMapOf<String, IconPackManager?>()

    /** Serializes [loadApps] so overlapping triggers can't run two full re-index passes at once. */
    private val loadMutex = kotlinx.coroutines.sync.Mutex()

    fun clearIconCache() {
        iconCache.evictAll()
        adHocPacks.clear()
    }

    private fun adHocPack(packageName: String): IconPackManager? =
        adHocPacks.getOrPut(packageName) {
            IconPackManager(context).takeIf { it.loadIconPack(packageName) }
        }

    var apps: List<AppInfo> = emptyList()
        private set
    /** Cached for search/sort by frequency and last opened. */
    private var statsMap: Map<String, AppStats> = emptyMap()
    var onAppsChanged: (() -> Unit)? = null

    /** Lives as long as the process, like the repository itself. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The attached activity, so app launches keep starting from an activity context. */
    private var owner: java.lang.ref.WeakReference<android.app.Activity>? = null

    /** densityDpi the loaded raw icons were decoded at; 0 until the first scan completes. */
    @Volatile private var loadedDensityDpi = 0
    /** Set by [invalidate]; the next [needsReload] forces a rescan. */
    @Volatile private var stale = false

    /** Main-thread confined. Non-null while a background reload is scanning or publishing. */
    private var reloadJob: Job? = null
    private val reloadPending = java.util.concurrent.atomic.AtomicBoolean(false)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            reloadInBackground()
        }
    }

    private fun register() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun attach(activity: android.app.Activity, onChanged: () -> Unit) {
        owner = java.lang.ref.WeakReference(activity)
        onAppsChanged = onChanged
    }

    /** No-op unless [activity] is still the attached one; a recreated activity may attach first. */
    fun detach(activity: android.app.Activity) {
        if (owner?.get() !== activity) return
        owner = null
        onAppsChanged = null
    }

    private fun currentDensityDpi() = context.resources.configuration.densityDpi

    /**
     * True when [apps] can't be shown as-is for long: nothing loaded yet, the display density
     * changed since the raw icons were decoded (they'd render scaled), or [invalidate] was called.
     * The loaded list is still safe to render immediately while the reload runs.
     */
    fun needsReload() = stale || apps.isEmpty() || loadedDensityDpi != currentDensityDpi()

    /**
     * For when packs, shapes or custom icons may have changed behind the repository's back (e.g.
     * returning from Settings): drops rendered icons and makes [needsReload] report true.
     */
    fun invalidate() {
        clearIconCache()
        stale = true
    }

    /** Forces every icon pack to be re-parsed on the next sync, as "Refresh launcher" expects. */
    fun forgetIconPacks() {
        iconPackManager.clearIconPack()
        allPageIconPackManager.clearIconPack()
        dockIconPackManager.clearIconPack()
        pageIconPackManagers.clear()
        pageIconPackSignature = null
    }

    /**
     * Rescans off the main thread without clearing [apps] first, then swaps in the result and
     * notifies [onAppsChanged]. Overlapping requests are conflated into one follow-up scan.
     * Main thread only.
     */
    fun reloadInBackground() {
        reloadPending.set(true)
        if (reloadJob == null) startReload()
    }

    private fun startReload() {
        reloadJob = appScope.launch {
            while (reloadPending.getAndSet(false)) loadApps()
            // Cache invalidation touches main-thread-owned state, so it stays on main.
            withContext(Dispatchers.Main) {
                reloadJob = null
                clearIconCache()
                onAppsChanged?.invoke()
                // Density can flip again mid-scan (toggling resolution back and forth).
                if (loadedDensityDpi != currentDensityDpi()) reloadPending.set(true)
                if (reloadPending.get()) startReload()
            }
        }
    }

    suspend fun loadApps() = loadMutex.withLock { loadAppsLocked() }

    /**
     * Re-reads launch counts and favourites from the database, reusing the already-loaded icons and
     * labels. This is what keeps the Frequent page current on resume now that the full re-index
     * (PackageManager query plus an icon load per app) no longer runs there.
     */
    suspend fun refreshStats() = loadMutex.withLock {
        val stats = dao.getAll().associateBy { it.componentName }
        statsMap = stats
        for (app in apps) {
            val stat = stats[app.componentName.flattenToString()]
            app.launchCount = stat?.launchCount ?: 0
            app.isFavorite = stat?.isFavorite ?: false
        }
    }

    private suspend fun loadAppsLocked() {
        stale = false
        val densityDpi = currentDensityDpi()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        val stats = dao.getAll().associateBy { it.componentName }
        statsMap = stats

        apps = resolveInfos
            .filter { it.activityInfo.packageName != context.packageName }
            .map { ri ->
                val cn = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name).flattenToString()
                val stat = stats[cn]
                val component = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
                val rawIconDrawable = ri.loadIcon(pm) // Store the raw system icon
                val customDrawableName = prefs.getCustomIcon(cn)
                val customIcon = if (customDrawableName != null) iconPackManager.getIconByName(customDrawableName) else null
                val themedIcon = customIcon ?: iconPackManager.getIconForApp(component)
                val baseIcon = if (customIcon != null) {
                    customIcon
                } else if (themedIcon != null) {
                    themedIcon
                } else if (iconPackManager.isLoaded()) {
                    val sizePx = (prefs.iconSizeDp * context.resources.displayMetrics.density).toInt()
                    iconPackManager.applyFallbackShape(rawIconDrawable, prefs.iconFallbackShape, sizePx)
                } else {
                    rawIconDrawable
                }
                val finalIcon = applyGlobalShape(baseIcon)

                val appLabel = ri.loadLabel(pm).toString()
                AppInfo(
                    label = appLabel,
                    packageName = ri.activityInfo.packageName,
                    activityName = ri.activityInfo.name,
                    icon = finalIcon,
                    rawIcon = rawIconDrawable,
                    launchCount = stat?.launchCount ?: 0,
                    isFavorite = stat?.isFavorite ?: false,
                    firstInstallTime = try {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(ri.activityInfo.packageName, 0).firstInstallTime
                    } catch (_: Exception) { 0L },
                    normalizedLabel = SearchNormalizer.normalize(appLabel),
                    normalizedPackage = SearchNormalizer.normalize(ri.activityInfo.packageName),
                    initials = SearchNormalizer.initials(appLabel),
                    normalizedWords = SearchNormalizer.words(appLabel)
                )
            }
            .sortedBy { it.label.lowercase() }
        loadedDensityDpi = densityDpi
    }

    private fun sortApps(apps: List<AppInfo>, mode: Int): List<AppInfo> {
        return when (mode) {
            1 -> apps.sortedWith(
                compareByDescending<AppInfo> { statsMap[it.componentName.flattenToString()]?.lastLaunched ?: 0L }
                    .thenBy { it.label.lowercase() }
            )
            2 -> apps.sortedWith(
                compareByDescending<AppInfo> { it.firstInstallTime }
                    .thenBy { it.label.lowercase() }
            )
            3 -> apps.sortedWith(
                compareByDescending<AppInfo> { it.launchCount }
                    .thenBy { it.label.lowercase() }
            )
            else -> apps.sortedBy { it.label.lowercase() }
        }
    }

    private fun applyGlobalShape(icon: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable {
        if (prefs.iconGlobalShape <= 0) return icon
        val shapeMapped = when (prefs.iconGlobalShape) {
            1 -> LauncherPrefs.SHAPE_CIRCLE
            2 -> LauncherPrefs.SHAPE_ROUNDED_SQUARE
            3 -> LauncherPrefs.SHAPE_SQUARE
            4 -> LauncherPrefs.SHAPE_SQUIRCLE
            else -> return icon
        }
        return shapeRenderer.applyFallbackShape(icon, shapeMapped, iconSizePx())
    }

    private fun iconSizePx() = (prefs.iconSizeDp * context.resources.displayMetrics.density).toInt()

    private fun sortAppliesToPage(pageId: String): Boolean {
        val pages = prefs.getSortApplyPages()
        return pages.isEmpty() || pages.contains("__all__") || pages.contains(pageId)
    }

    private fun filterHidden(list: List<AppInfo>): List<AppInfo> {
        val hidden = prefs.getHiddenApps()
        if (hidden.isEmpty()) return list
        return list.filter { it.componentKey !in hidden }
    }

    fun getAllApps(): List<AppInfo> {
        if (!sortAppliesToPage("all")) return filterHidden(apps)
        return filterHidden(sortApps(apps, prefs.appSortMode))
    }

    fun getFrequentApps() = filterHidden(
        apps.filter { it.launchCount > 0 }.sortedByDescending { it.launchCount }
    )

    fun getFavoriteApps(): List<AppInfo> {
        val order = prefs.getFavoriteOrder()
        val favoriteApps = apps.filter { it.isFavorite }
        val base = if (order.isEmpty()) {
            if (sortAppliesToPage("favorites")) sortApps(favoriteApps, prefs.appSortMode) else favoriteApps
        } else {
            val ordered = order.mapNotNull { cn -> apps.find { it.componentName.flattenToString() == cn && it.isFavorite } }
            val rest = apps.filter { it.isFavorite && it.componentName.flattenToString() !in order }
            val sortedRest = if (sortAppliesToPage("favorites")) sortApps(rest, prefs.appSortMode) else rest
            ordered + sortedRest
        }
        return filterHidden(base)
    }

    fun getAppsForPage(pageId: String): List<AppInfo> {
        val members = prefs.getPageApps(pageId)
        val pageApps = apps.filter { members.contains(it.componentName.flattenToString()) }
        val order = if (pageId == "favorites") prefs.getFavoriteOrder() else prefs.getPageAppOrder(pageId)
        val base = if (order.isNotEmpty()) {
            val ordered = order.mapNotNull { cn -> apps.find { it.componentName.flattenToString() == cn } }
                .filter { members.contains(it.componentName.flattenToString()) }
            val rest = apps.filter { members.contains(it.componentName.flattenToString()) && it.componentName.flattenToString() !in order }
            val sortedRest = if (sortAppliesToPage(pageId)) sortApps(rest, prefs.appSortMode) else rest
            ordered + sortedRest
        } else {
            if (sortAppliesToPage(pageId)) sortApps(pageApps, prefs.appSortMode) else pageApps
        }
        return filterHidden(base)
    }

    /**
     * Matches app names and package names, so "com.whatsapp" or "whatsapp" both find WhatsApp.
     * Apps matched only by package name are ranked after the name matches.
     *
     * @param alsoMatchDialDigits If non-empty, apps whose label contains this digit string are also included (e.g. "710" so "zw0" finds "710 Launcher").
     */
    fun searchApps(query: String, alsoMatchDialDigits: String? = null): List<AppInfo> {
        if (query.isBlank() && (alsoMatchDialDigits.isNullOrBlank())) return emptyList()

        val normalizedQuery = SearchNormalizer.normalize(query)
        val digitsOnly = alsoMatchDialDigits
            ?.filter { it in '0'..'9' }
            ?.takeIf { it.isNotEmpty() }

        if (normalizedQuery.isEmpty() && digitsOnly.isNullOrEmpty()) return emptyList()

        val hidden = prefs.getHiddenApps()
        val stats = statsMap
        val nameMatches = mutableListOf<RankedApp>()
        val packageOnlyMatches = mutableListOf<RankedApp>()
        for (app in apps) {
            val kind = SearchNormalizer.match(
                normalizedQuery, digitsOnly, app.normalizedLabel, app.normalizedWords, app.normalizedPackage
            )
            if (kind == SearchNormalizer.NO_MATCH || app.componentKey in hidden) continue
            val stat = stats[app.componentKey]
            val ranked = RankedApp(app, stat?.launchCount ?: app.launchCount, stat?.lastLaunched ?: 0L)
            if (kind == SearchNormalizer.NAME_MATCH) nameMatches += ranked else packageOnlyMatches += ranked
        }

        nameMatches.sortWith(BY_FREQUENCY)
        packageOnlyMatches.sortWith(BY_FREQUENCY)
        val out = ArrayList<AppInfo>(nameMatches.size + packageOnlyMatches.size)
        nameMatches.mapTo(out) { it.app }
        packageOnlyMatches.mapTo(out) { it.app }
        return out
    }

    /** Sort keys resolved once per match, not on every comparison as the sort runs. */
    private class RankedApp(val app: AppInfo, val launchCount: Int, val lastLaunched: Long) {
        val sortLabel: String = app.label.lowercase()
    }

    private val BY_FREQUENCY = compareByDescending<RankedApp> { it.launchCount }
        .thenByDescending { it.lastLaunched }
        .thenBy { it.sortLabel }

    suspend fun recordLaunch(app: AppInfo) {
        val cn = app.componentName.flattenToString()
        val existing = dao.get(cn)
        if (existing != null) {
            dao.incrementLaunch(cn, System.currentTimeMillis())
        } else {
            dao.upsert(AppStats(cn, 1, false, System.currentTimeMillis()))
        }
        app.launchCount++
    }

    suspend fun toggleFavorite(app: AppInfo) {
        val cn = app.componentName.flattenToString()
        app.isFavorite = !app.isFavorite
        val existing = dao.get(cn)
        if (existing != null) {
            dao.setFavorite(cn, app.isFavorite)
        } else {
            dao.upsert(AppStats(cn, 0, app.isFavorite, 0))
        }
    }

    fun launchApp(app: AppInfo) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = app.componentName
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        (owner?.get() ?: context).startActivity(intent)
        appScope.launch { recordLaunch(app) }
    }

    private fun resolveCustomIconFromAnyPack(drawableName: String): android.graphics.drawable.Drawable? {
        val managers = mutableListOf(iconPackManager, allPageIconPackManager, dockIconPackManager)
        managers.addAll(pageIconPackManagers.values)
        for (mgr in managers) {
            if (mgr.isLoaded()) {
                val icon = mgr.getIconByName(drawableName)
                if (icon != null) return icon
            }
        }
        return null
    }

    private fun getPackManagerForPage(pageId: String): IconPackManager? {
        val pageMgr = pageIconPackManagers[pageId]
        if (pageMgr?.isLoaded() == true) return pageMgr
        // Legacy fallback for "all" and "dock"
        if (pageId == "all" && allPageIconPackManager.isLoaded()) return allPageIconPackManager
        if (pageId == "dock" && dockIconPackManager.isLoaded()) return dockIconPackManager
        // Fall back to global
        if (iconPackManager.isLoaded()) return iconPackManager
        return null
    }

    fun getIconForPage(app: AppInfo, pageId: String): android.graphics.drawable.Drawable {
        val component = app.componentName
        val cn = component.flattenToString()
        val cacheKey = "$cn|$pageId|${prefs.iconGlobalShape}|${prefs.iconFallbackShape}|${iconSizePx()}"
        iconCache.get(cacheKey)?.let { return it }

        val rawIcon = app.rawIcon ?: app.icon
        val resolved = resolveIconForPage(component, cn, pageId, rawIcon)
        iconCache.put(cacheKey, resolved)
        return resolved
    }

    private fun resolveIconForPage(
        component: ComponentName,
        cn: String,
        pageId: String,
        rawIcon: android.graphics.drawable.Drawable
    ): android.graphics.drawable.Drawable {
        val customDrawableName = prefs.getCustomIcon(cn, pageId)
        if (customDrawableName != null) {
            val customIcon = resolveCustomIconFromAnyPack(customDrawableName)
            if (customIcon != null) return applyGlobalShape(customIcon)
        }

        val packManager = getPackManagerForPage(pageId)

        val themedIcon = packManager?.getIconForApp(component)
        if (themedIcon != null) return applyGlobalShape(themedIcon)

        if (packManager?.isLoaded() == true) {
            return applyGlobalShape(packManager.applyFallbackShape(rawIcon, prefs.iconFallbackShape, iconSizePx()))
        }

        return applyGlobalShape(rawIcon)
    }

    fun getIconForDock(app: AppInfo) = getIconForPage(app, "dock")

    fun getIconForContext(app: AppInfo, isAllPage: Boolean) =
        getIconForPage(app, if (isAllPage) "all" else "__global__")

    fun getDisplayLabel(app: AppInfo, pageId: String?): CharSequence =
        prefs.getCustomLabel(app.componentName.flattenToString(), pageId) ?: app.label

    fun getIconForShortcut(shortcut: ShortcutDisplayInfo, pageId: String): android.graphics.drawable.Drawable {
        val customDrawableName = prefs.getCustomIcon(shortcut.shortcutKey, pageId)
        if (customDrawableName != null) {
            val customIcon = resolveCustomIconFromAnyPack(customDrawableName)
            if (customIcon != null) return customIcon
        }
        return shortcut.icon
    }

    fun getIconForIntentShortcut(info: IntentShortcutInfo, pageId: String): android.graphics.drawable.Drawable {
        val customDrawableName = prefs.getCustomIcon(info.shortcutKey, pageId)
        if (customDrawableName != null) {
            val customIcon = resolveCustomIconFromAnyPack(customDrawableName)
            if (customIcon != null) return customIcon
        }
        return info.icon
    }

    /** Icon for notification applet by package name: custom drawable, applet pack, or system. */
    fun getAppletIcon(packageName: String, iconSizePx: Int): android.graphics.drawable.Drawable {
        var icon: android.graphics.drawable.Drawable? = null
        val customName = prefs.getAppletCustomIcon(packageName)
        if (customName != null) {
            val customPack = prefs.getAppletCustomIconPack(packageName)
            if (customPack != null) icon = adHocPack(customPack)?.getIconByName(customName)
            if (icon == null) icon = resolveCustomIconFromAnyPack(customName)
        }
        if (icon == null) {
            val component = pm.getLaunchIntentForPackage(packageName)?.component
            if (component != null) icon = getPackManagerForPage("applets")?.getIconForApp(component)
        }
        if (icon == null) {
            icon = try { pm.getApplicationIcon(pm.getApplicationInfo(packageName, 0)) } catch (_: Exception) { pm.defaultActivityIcon }
        }
        val shape = prefs.appletIconShape
        return if (shape > 0 && icon != null) {
            val shapeMapped = when (shape) { 1 -> LauncherPrefs.SHAPE_CIRCLE; 2 -> LauncherPrefs.SHAPE_ROUNDED_SQUARE; 3 -> LauncherPrefs.SHAPE_SQUARE; 4 -> LauncherPrefs.SHAPE_SQUIRCLE; else -> LauncherPrefs.SHAPE_SQUARE }
            shapeRenderer.applyFallbackShape(icon, shapeMapped, iconSizePx)
        } else icon!!
    }

    /** Icon for contact results in search: custom from icon pack, or Contacts app icon from search page pack, or system Contacts icon. */
    fun getContactIconForSearch(): android.graphics.drawable.Drawable {
        val customName = prefs.contactIconDrawableName
        val customPack = prefs.contactIconPackPackage
        if (!customName.isNullOrBlank() && !customPack.isNullOrBlank()) {
            adHocPack(customPack)?.getIconByName(customName)?.let { return applyGlobalShape(it) }
        }
        val contactsComponent = pm.getLaunchIntentForPackage("com.android.contacts")?.component
        if (contactsComponent != null) {
            val packManager = getPackManagerForPage("search")
            val themedIcon = packManager?.getIconForApp(contactsComponent)
            if (themedIcon != null) return applyGlobalShape(themedIcon)
            if (packManager?.isLoaded() == true) {
                val rawIcon = try { pm.getApplicationIcon(pm.getApplicationInfo("com.android.contacts", 0)) } catch (_: Exception) { null }
                if (rawIcon != null) {
                    val sizePx = (prefs.iconSizeDp * context.resources.displayMetrics.density).toInt()
                    return applyGlobalShape(packManager.applyFallbackShape(rawIcon, prefs.iconFallbackShape, sizePx))
                }
            }
        }
        return try {
            applyGlobalShape(pm.getApplicationIcon(pm.getApplicationInfo("com.android.contacts", 0)))
        } catch (_: Exception) {
            applyGlobalShape(pm.defaultActivityIcon)
        }
    }

    /** Icon for search command results: custom from icon pack, or search page pack (e.g. code icon), or system default. */
    fun getCommandIconForSearch(): android.graphics.drawable.Drawable {
        val customName = prefs.commandIconDrawableName
        val customPack = prefs.commandIconPackPackage
        if (!customName.isNullOrBlank() && !customPack.isNullOrBlank()) {
            adHocPack(customPack)?.getIconByName(customName)?.let { return applyGlobalShape(it) }
        }
        val packManager = getPackManagerForPage("search")
        if (packManager?.isLoaded() == true) {
            val codeIcon = packManager.getIconByName("terminal")
                ?: packManager.getIconByName("code")
                ?: packManager.getIconByName("ic_code")
                ?: packManager.getIconByName("console")
            if (codeIcon != null) return applyGlobalShape(codeIcon)
        }
        return applyGlobalShape(
            context.resources.getDrawable(android.R.drawable.ic_menu_manage, context.theme)
        )
    }

    /** Built-in shortcut to open Launcher settings. Uses icon pack settings icon when available. */
    fun createLauncherSettingsItem(): LaunchableItem.LauncherSettings {
        val label = context.getString(R.string.launcher_settings_label)
        var icon: android.graphics.drawable.Drawable? = null
        val settingsComponent = ComponentName("com.android.settings", "com.android.settings.Settings")
        val packManager = getPackManagerForPage("all") ?: iconPackManager
        if (packManager?.isLoaded() == true) {
            icon = packManager.getIconForApp(settingsComponent)
                ?: packManager.getIconByName("settings")
                ?: packManager.getIconByName("ic_settings")
                ?: packManager.getIconByName("com_android_settings")
        }
        if (icon == null) {
            try {
                icon = pm.getApplicationIcon(pm.getApplicationInfo("com.android.settings", 0))
            } catch (_: Exception) {
                icon = pm.defaultActivityIcon
            }
        }
        return LaunchableItem.LauncherSettings(label, applyGlobalShape(icon!!))
    }

    fun createRefreshCacheItem(): LaunchableItem.RefreshCache {
        val label = context.getString(R.string.refresh_cache_label)
        var icon: android.graphics.drawable.Drawable? = null
        val packManager = getPackManagerForPage("all") ?: iconPackManager
        if (packManager?.isLoaded() == true) {
            icon = packManager.getIconByName("ic_refresh")
                ?: packManager.getIconByName("refresh")
                ?: packManager.getIconByName("ic_sync")
                ?: packManager.getIconByName("sync")
        }
        if (icon == null) {
            icon = context.resources.getDrawable(android.R.drawable.stat_notify_sync, context.theme)
        }
        return LaunchableItem.RefreshCache(label, applyGlobalShape(icon!!))
    }
}
