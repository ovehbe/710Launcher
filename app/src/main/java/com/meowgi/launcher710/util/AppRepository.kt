package com.meowgi.launcher710.util

import android.content.*
import android.content.pm.PackageManager
import com.meowgi.launcher710.model.AppDatabase
import com.meowgi.launcher710.model.AppInfo
import com.meowgi.launcher710.model.AppStats
import com.meowgi.launcher710.model.IntentShortcutInfo
import com.meowgi.launcher710.model.ShortcutDisplayInfo
import kotlinx.coroutines.*

class AppRepository(private val context: Context) {

    private val pm = context.packageManager
    private val dao = AppDatabase.get(context).appStatsDao()
    var iconPackManager: IconPackManager? = null
    var allPageIconPackManager: IconPackManager? = null
    var dockIconPackManager: IconPackManager? = null
    val pageIconPackManagers = mutableMapOf<String, IconPackManager>()
    private val prefs = LauncherPrefs(context)

    var apps: List<AppInfo> = emptyList()
        private set
    /** Cached for search/sort by frequency and last opened. */
    private var statsMap: Map<String, AppStats> = emptyMap()
    var onAppsChanged: (() -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            CoroutineScope(Dispatchers.Main).launch {
                loadApps()
                onAppsChanged?.invoke()
            }
        }
    }

    fun register() {
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

    fun unregister() {
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    suspend fun loadApps() {
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
                val customIcon = if (customDrawableName != null) iconPackManager?.getIconByName(customDrawableName) else null
                val themedIcon = customIcon ?: iconPackManager?.getIconForApp(component)
                val baseIcon = if (customIcon != null) {
                    customIcon
                } else if (themedIcon != null) {
                    themedIcon
                } else if (iconPackManager?.isLoaded() == true) {
                    val sizePx = (prefs.iconSizeDp * context.resources.displayMetrics.density).toInt()
                    iconPackManager!!.applyFallbackShape(rawIconDrawable, prefs.iconFallbackShape, sizePx)
                } else {
                    rawIconDrawable
                }
                val finalIcon = applyGlobalShape(baseIcon)

                AppInfo(
                    label = ri.loadLabel(pm).toString(),
                    packageName = ri.activityInfo.packageName,
                    activityName = ri.activityInfo.name,
                    icon = finalIcon,
                    rawIcon = rawIconDrawable,
                    launchCount = stat?.launchCount ?: 0,
                    isFavorite = stat?.isFavorite ?: false,
                    firstInstallTime = try {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(ri.activityInfo.packageName, 0).firstInstallTime
                    } catch (_: Exception) { 0L }
                )
            }
            .sortedBy { it.label.lowercase() }
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
        val sizePx = (prefs.iconSizeDp * context.resources.displayMetrics.density).toInt()
        return IconPackManager(context).applyFallbackShape(icon, shapeMapped, sizePx)
    }

    private fun sortAppliesToPage(pageId: String): Boolean {
        val pages = prefs.getSortApplyPages()
        return pages.isEmpty() || pages.contains("__all__") || pages.contains(pageId)
    }

    private fun filterHidden(list: List<AppInfo>): List<AppInfo> {
        val hidden = prefs.getHiddenApps()
        if (hidden.isEmpty()) return list
        return list.filter { it.componentName.flattenToString() !in hidden }
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
     * @param alsoMatchDialDigits If non-empty, apps whose label contains this digit string are also included (e.g. "710" so "zw0" finds "710 Launcher").
     */
    fun searchApps(query: String, alsoMatchDialDigits: String? = null): List<AppInfo> {
        if (query.isBlank() && (alsoMatchDialDigits.isNullOrBlank())) return emptyList()
        val q = query.lowercase().replace(Regex("[^a-z0-9]"), "")
        val digitsOnly = alsoMatchDialDigits?.filter { it in '0'..'9' }?.takeIf { it.isNotEmpty() }
        if (q.isEmpty() && digitsOnly.isNullOrEmpty()) return emptyList()
        return filterHidden(apps.filter {
            val normalized = it.label.lowercase().replace(Regex("[^a-z0-9]"), "")
            normalized.contains(q) || (digitsOnly != null && normalized.contains(digitsOnly))
        }).sortedWith(
            compareByDescending<AppInfo> { statsMap[it.componentName.flattenToString()]?.launchCount ?: it.launchCount }
                .thenByDescending { statsMap[it.componentName.flattenToString()]?.lastLaunched ?: 0L }
                .thenBy { it.label.lowercase() }
        )
    }

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
        context.startActivity(intent)
        CoroutineScope(Dispatchers.IO).launch { recordLaunch(app) }
    }

    private fun resolveCustomIconFromAnyPack(drawableName: String): android.graphics.drawable.Drawable? {
        val managers = mutableListOf<IconPackManager>()
        iconPackManager?.let { managers.add(it) }
        allPageIconPackManager?.let { managers.add(it) }
        dockIconPackManager?.let { managers.add(it) }
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
        if (pageId == "all" && allPageIconPackManager?.isLoaded() == true) return allPageIconPackManager
        if (pageId == "dock" && dockIconPackManager?.isLoaded() == true) return dockIconPackManager
        // Fall back to global
        if (iconPackManager?.isLoaded() == true) return iconPackManager
        return null
    }

    fun getIconForPage(app: AppInfo, pageId: String): android.graphics.drawable.Drawable {
        val component = app.componentName
        val cn = component.flattenToString()
        val rawIcon = app.rawIcon ?: app.icon

        val customDrawableName = prefs.getCustomIcon(cn, pageId)
        if (customDrawableName != null) {
            val customIcon = resolveCustomIconFromAnyPack(customDrawableName)
            if (customIcon != null) return applyGlobalShape(customIcon)
        }

        val packManager = getPackManagerForPage(pageId)

        val themedIcon = packManager?.getIconForApp(component)
        if (themedIcon != null) return applyGlobalShape(themedIcon)

        if (packManager?.isLoaded() == true) {
            val sizePx = (prefs.iconSizeDp * context.resources.displayMetrics.density).toInt()
            return applyGlobalShape(packManager.applyFallbackShape(rawIcon, prefs.iconFallbackShape, sizePx))
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
            if (customPack != null) {
                val tempMgr = IconPackManager(context)
                if (tempMgr.loadIconPack(customPack)) {
                    icon = tempMgr.getIconByName(customName)
                }
            }
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
            IconPackManager(context).applyFallbackShape(icon, shapeMapped, iconSizePx)
        } else icon!!
    }

    /** Icon for contact results in search: custom from icon pack, or Contacts app icon from search page pack, or system Contacts icon. */
    fun getContactIconForSearch(): android.graphics.drawable.Drawable {
        val customName = prefs.contactIconDrawableName
        val customPack = prefs.contactIconPackPackage
        if (!customName.isNullOrBlank() && !customPack.isNullOrBlank()) {
            val tempMgr = IconPackManager(context)
            if (tempMgr.loadIconPack(customPack)) {
                tempMgr.getIconByName(customName)?.let { return applyGlobalShape(it) }
            }
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
}
