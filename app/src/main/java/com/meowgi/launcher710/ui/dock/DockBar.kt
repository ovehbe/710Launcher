package com.meowgi.launcher710.ui.dock

import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.LinearLayout
import com.meowgi.launcher710.R
import com.meowgi.launcher710.model.AppInfo
import com.meowgi.launcher710.model.IntentShortcutInfo
import com.meowgi.launcher710.util.AppRepository
import com.meowgi.launcher710.util.LauncherPrefs
import com.meowgi.launcher710.util.ShortcutHelper

class DockBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val dockPrefs: SharedPreferences = context.getSharedPreferences("dock", Context.MODE_PRIVATE)
    private val iconSize = (42 * resources.displayMetrics.density).toInt()
    private val maxSlots = 6
    var repository: AppRepository? = null
    var shortcutHelper: ShortcutHelper? = null
    var launcherPrefs: LauncherPrefs? = null
    var onAppLaunch: ((AppInfo) -> Unit)? = null
    var onIntentShortcutLaunch: ((IntentShortcutInfo) -> Unit)? = null
    var dockIconResolver: ((AppInfo) -> android.graphics.drawable.Drawable)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    fun loadDock() {
        removeAllViews()
        val repo = repository ?: return
        val dockedApps = getDockApps(repo)
        val prefs = launcherPrefs
        val dockedShortcuts = if (shortcutHelper != null && prefs != null) shortcutHelper!!.getIntentShortcutsForPage("dock", prefs) else emptyList()
        val total = dockedApps.size + dockedShortcuts.size
        if (total == 0) return

        val moveThreshold = dp(10).toFloat()
        for (app in dockedApps) {
            val icon = dockIconResolver?.invoke(app) ?: app.icon
            addView(makeDockIconView(icon, { onAppLaunch?.invoke(app) }, { onDockIconLongClick?.invoke(app) }, moveThreshold), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        for (info in dockedShortcuts) {
            val icon = repo.getIconForIntentShortcut(info, "dock")
            addView(makeDockIconView(icon, { onIntentShortcutLaunch?.invoke(info) }, { onIntentShortcutLongClick?.invoke(info) }, moveThreshold), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun makeDockIconView(
        icon: android.graphics.drawable.Drawable,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
        moveThreshold: Float
    ): ImageView {
        return ImageView(context).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener { onClick() }
            setOnLongClickListener { onLongClick(); true }
            var downX = 0f
            var downY = 0f
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(event.rawX - downX) > moveThreshold || kotlin.math.abs(event.rawY - downY) > moveThreshold) {
                            cancelLongPress()
                            isPressed = false
                        }
                    }
                }
                false
            }
        }
    }

    var onDockIconLongClick: ((AppInfo) -> Unit)? = null
    var onIntentShortcutLongClick: ((IntentShortcutInfo) -> Unit)? = null

    fun getDockAppsList(): List<AppInfo> {
        val repo = repository ?: return emptyList()
        return getDockApps(repo)
    }

    fun addToDock(app: AppInfo) {
        val repo = repository ?: return
        val current = getDockApps(repo).toMutableList()
        if (current.size >= maxSlots) return
        if (current.any { it.packageName == app.packageName && it.activityName == app.activityName }) return
        current.add(app)
        saveDock(current)
        loadDock()
    }

    fun removeFromDock(app: AppInfo) {
        val repo = repository ?: return
        val current = getDockApps(repo).filterNot {
            it.packageName == app.packageName && it.activityName == app.activityName
        }
        saveDock(current)
        loadDock()
    }

    fun getDockIntentShortcutsList(): List<IntentShortcutInfo> {
        val helper = shortcutHelper ?: return emptyList()
        val prefs = launcherPrefs ?: return emptyList()
        return helper.getIntentShortcutsForPage("dock", prefs)
    }

    fun addIntentShortcutToDock(info: IntentShortcutInfo) {
        val prefs = launcherPrefs ?: return
        val current = getDockIntentShortcutsList()
        if (current.size + getDockAppsList().size >= maxSlots) return
        if (current.any { it.intentUri == info.intentUri }) return
        prefs.addIntentShortcutToPage("dock", info.label.toString(), info.intentUri, null)
        loadDock()
    }

    fun removeIntentShortcutFromDock(info: IntentShortcutInfo) {
        launcherPrefs?.removeIntentShortcutFromPage("dock", info.intentUri)
        loadDock()
    }

    private fun getDockApps(repo: AppRepository): List<AppInfo> {
        val saved = dockPrefs.getString("dock_apps", null)
        if (saved != null) {
            val components = saved.split("|")
            return components.mapNotNull { cn ->
                repo.getAllApps().find {
                    "${it.packageName}/${it.activityName}" == cn
                }
            }.take(maxSlots)
        }
        return getDefaultDock(repo)
    }

    private fun getDefaultDock(repo: AppRepository): List<AppInfo> {
        val defaults = listOf("dialer", "phone", "messaging", "sms", "browser", "chrome",
            "contacts", "camera", "email", "mail")
        val all = repo.getAllApps()
        val result = mutableListOf<AppInfo>()
        for (keyword in defaults) {
            if (result.size >= maxSlots) break
            val match = all.find {
                it.label.lowercase().contains(keyword) ||
                it.packageName.lowercase().contains(keyword)
            }
            if (match != null && match !in result) result.add(match)
        }
        if (result.size < maxSlots) {
            for (app in all) {
                if (result.size >= maxSlots) break
                if (app !in result) result.add(app)
            }
        }
        return result
    }

    fun saveDock(apps: List<AppInfo>) {
        val str = apps.joinToString("|") { "${it.packageName}/${it.activityName}" }
        dockPrefs.edit().putString("dock_apps", str).apply()
    }

    fun applyOpacity() {
        val prefs = LauncherPrefs(context)
        setBackgroundColor(prefs.dockBackgroundColor)
        background?.alpha = prefs.dockAlpha
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
