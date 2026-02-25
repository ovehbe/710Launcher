package com.meowgi.launcher710.util

import android.content.*
import android.content.pm.PackageManager
import com.meowgi.launcher710.model.AppDatabase
import com.meowgi.launcher710.model.AppInfo
import com.meowgi.launcher710.model.AppStats
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
                val finalIcon = if (customIcon != null) {
                    customIcon
                } else if (themedIcon != null) {
                    themedIcon
                } else if (iconPackManager?.isLoaded() == true) {
                    val sizePx = (prefs.iconSizeDp * context.resources.displayMetrics.density).toInt()
                    iconPackManager!!.applyFallbackShape(rawIconDrawable, prefs.iconFallbackShape, sizePx)
                } else {
                    rawIconDrawable
                }

                AppInfo(
                    label = ri.loadLabel(pm).toString(),
                    packageName = ri.activityInfo.packageName,
                    activityName = ri.activityInfo.name,
                    icon = finalIcon,
                    rawIcon = rawIconDrawable,
                    launchCount = stat?.launchCount ?: 0,
                    isFavorite = stat?.isFavorite ?: false
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    fun getAllApps() = apps

    fun getFrequentApps() = apps
        .filter { it.launchCount > 0 }
        .sortedByDescending { it.launchCount }

    fun getFavoriteApps() = apps.filter { it.isFavorite }

    fun getAppsForPage(pageId: String): List<AppInfo> {
        val members = prefs.getPageApps(pageId)
        return apps.filter { members.contains(it.componentName.flattenToString()) }
    }

    fun searchApps(query: String): List<AppInfo> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        return apps.filter { it.label.lowercase().contains(q) }
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
            if (customIcon != null) return customIcon
        }

        val packManager = getPackManagerForPage(pageId)

        val themedIcon = packManager?.getIconForApp(component)
        if (themedIcon != null) return themedIcon

        if (packManager?.isLoaded() == true) {
            val sizePx = (prefs.iconSizeDp * context.resources.displayMetrics.density).toInt()
            return packManager.applyFallbackShape(rawIcon, prefs.iconFallbackShape, sizePx)
        }

        return rawIcon
    }

    fun getIconForDock(app: AppInfo) = getIconForPage(app, "dock")

    fun getIconForContext(app: AppInfo, isAllPage: Boolean) =
        getIconForPage(app, if (isAllPage) "all" else "__global__")
}
