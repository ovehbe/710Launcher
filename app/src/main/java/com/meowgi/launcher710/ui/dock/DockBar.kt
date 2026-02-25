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
import com.meowgi.launcher710.util.AppRepository
import com.meowgi.launcher710.util.LauncherPrefs

class DockBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val prefs: SharedPreferences = context.getSharedPreferences("dock", Context.MODE_PRIVATE)
    private val iconSize = (42 * resources.displayMetrics.density).toInt()
    private val maxSlots = 6
    var repository: AppRepository? = null
    var onAppLaunch: ((AppInfo) -> Unit)? = null
    var dockIconResolver: ((AppInfo) -> android.graphics.drawable.Drawable)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    fun loadDock() {
        removeAllViews()
        val repo = repository ?: return
        val docked = getDockApps(repo)

        val moveThreshold = dp(10).toFloat()
        for (app in docked) {
            val icon = dockIconResolver?.invoke(app) ?: app.icon
            val iv = ImageView(context).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(6), dp(2), dp(6), dp(2))
                setOnClickListener { onAppLaunch?.invoke(app) }
                setOnLongClickListener {
                    onDockIconLongClick?.invoke(app)
                    true
                }
                var downX = 0f
                var downY = 0f
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX
                            downY = event.rawY
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = kotlin.math.abs(event.rawX - downX)
                            val dy = kotlin.math.abs(event.rawY - downY)
                            if (dx > moveThreshold || dy > moveThreshold) {
                                v.cancelLongPress()
                                v.isPressed = false
                            }
                        }
                    }
                    false
                }
            }
            addView(iv, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }

    var onDockIconLongClick: ((AppInfo) -> Unit)? = null

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

    private fun getDockApps(repo: AppRepository): List<AppInfo> {
        val saved = prefs.getString("dock_apps", null)
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
        prefs.edit().putString("dock_apps", str).apply()
    }

    fun applyOpacity() {
        val prefs = LauncherPrefs(context)
        setBackgroundColor(prefs.dockBackgroundColor)
        background?.alpha = prefs.dockAlpha
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
