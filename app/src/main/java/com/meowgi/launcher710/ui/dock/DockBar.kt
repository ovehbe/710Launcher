package com.meowgi.launcher710.ui.dock

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import com.meowgi.launcher710.R
import com.meowgi.launcher710.model.AppInfo
import com.meowgi.launcher710.model.IntentShortcutInfo
import com.meowgi.launcher710.util.AppRepository
import com.meowgi.launcher710.util.LauncherPrefs
import com.meowgi.launcher710.util.ShortcutHelper
import org.json.JSONArray

class DockBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val dockPrefs: SharedPreferences = context.getSharedPreferences("dock", Context.MODE_PRIVATE)
    private val iconSize = (42 * resources.displayMetrics.density).toInt()
    private val maxSlots = 6
    private var dockAppCount = 0
    private val longPressMs = 500L
    private val dragThresholdPx = dp(20).toFloat()
    private val mainHandler = Handler(Looper.getMainLooper())
    var repository: AppRepository? = null
    var shortcutHelper: ShortcutHelper? = null
    var launcherPrefs: LauncherPrefs? = null
    var onAppLaunch: ((AppInfo) -> Unit)? = null
    var onIntentShortcutLaunch: ((IntentShortcutInfo) -> Unit)? = null
    var dockIconResolver: ((AppInfo) -> android.graphics.drawable.Drawable)? = null
    var onDockSwipeUp: ((slotIndex: Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    fun loadDock() {
        removeAllViews()
        val repo = repository ?: return
        val dockedApps = getDockApps(repo)
        dockAppCount = dockedApps.size
        val prefs = launcherPrefs
        val dockedShortcuts = if (shortcutHelper != null && prefs != null) shortcutHelper!!.getIntentShortcutsForPage("dock", prefs) else emptyList()
        val total = dockedApps.size + dockedShortcuts.size
        if (total == 0) return

        var viewIndex = 0
        for (app in dockedApps) {
            val icon = dockIconResolver?.invoke(app) ?: app.icon
            val idx = viewIndex++
            addView(makeDockIconView(icon, idx, { onAppLaunch?.invoke(app) }, { onDockIconLongClick?.invoke(app, idx) }), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        for (info in dockedShortcuts) {
            val icon = repo.getIconForIntentShortcut(info, "dock")
            val idx = viewIndex++
            addView(makeDockIconView(icon, idx, { onIntentShortcutLaunch?.invoke(info) }, { onIntentShortcutLongClick?.invoke(info, idx) }), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }

    fun getSlotIndexAt(screenX: Float): Int {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val x = screenX - loc[0]
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x >= c.left && x <= c.right) return i
        }
        return -1
    }

    private fun reorderDockSlots(dragIndex: Int, dropIndex: Int) {
        if (dragIndex == dropIndex) return
        if (dragIndex < dockAppCount && dropIndex < dockAppCount) {
            val repo = repository ?: return
            val apps = getDockApps(repo).toMutableList()
            if (dragIndex >= apps.size || dropIndex >= apps.size) return
            val a = apps[dragIndex]
            apps[dragIndex] = apps[dropIndex]
            apps[dropIndex] = a
            saveDock(apps)
            loadDock()
        } else if (dragIndex >= dockAppCount && dropIndex >= dockAppCount) {
            val prefs = launcherPrefs ?: return
            val json = prefs.getPageIntentShortcuts("dock") ?: return
            try {
                val arr = JSONArray(json)
                val a = dragIndex - dockAppCount
                val b = dropIndex - dockAppCount
                if (a < 0 || b < 0 || a >= arr.length() || b >= arr.length()) return
                val oa = arr.getJSONObject(a)
                val ob = arr.getJSONObject(b)
                arr.put(a, ob)
                arr.put(b, oa)
                prefs.setPageIntentShortcuts("dock", arr.toString())
                loadDock()
            } catch (_: Exception) {}
        }
    }

    private val swipeUpThresholdPx = dp(40).toFloat()

    private fun makeDockIconView(
        icon: android.graphics.drawable.Drawable,
        viewIndex: Int,
        onClick: () -> Unit,
        onLongClick: () -> Unit
    ): ImageView {
        return ImageView(context).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(2), dp(6), dp(2))
            tag = viewIndex
            var longPressScheduled = false
            var longPressFired = false
            var dragging = false
            var longClickConsumed = false
            var swipeUpDetected = false
            var movedBeyondTapSlop = false
            var downX = 0f
            var downY = 0f
            var lastDropIndex = viewIndex
            val longPressRunnable = Runnable {
                longPressScheduled = false
                longPressFired = true
            }
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        longPressFired = false
                        dragging = false
                        longClickConsumed = false
                        swipeUpDetected = false
                        movedBeyondTapSlop = false
                        lastDropIndex = viewIndex
                        if (!longPressScheduled) {
                            longPressScheduled = true
                            mainHandler.postDelayed(longPressRunnable, longPressMs)
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = kotlin.math.abs(event.rawX - downX)
                        val dy = kotlin.math.abs(event.rawY - downY)
                        if (dx > dragThresholdPx || dy > dragThresholdPx) movedBeyondTapSlop = true
                        if (downY - event.rawY > swipeUpThresholdPx && (downY - event.rawY) > dx) swipeUpDetected = true
                        if (longPressScheduled && movedBeyondTapSlop) {
                            mainHandler.removeCallbacks(longPressRunnable)
                            longPressScheduled = false
                        }
                        if (longPressFired && !dragging) {
                            if (kotlin.math.abs(event.rawX - downX) > dragThresholdPx || kotlin.math.abs(event.rawY - downY) > dragThresholdPx) {
                                dragging = true
                            }
                        }
                        if (dragging && this@DockBar.parent != null) {
                            val drop = this@DockBar.getSlotIndexAt(event.rawX)
                            if (drop >= 0 && drop != lastDropIndex) {
                                val dragIdx = (tag as? Int) ?: viewIndex
                                reorderDockSlots(dragIdx, drop)
                                lastDropIndex = drop
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        mainHandler.removeCallbacks(longPressRunnable)
                        longPressScheduled = false
                        if (swipeUpDetected && !longPressFired && !dragging) {
                            onDockSwipeUp?.invoke(viewIndex)
                            playBounceAnimation(this)
                        } else if (longPressFired && !dragging && !longClickConsumed) {
                            longClickConsumed = true
                            onLongClick()
                        } else if (!longPressFired && !swipeUpDetected && !dragging && !movedBeyondTapSlop) {
                            onClick()
                        }
                        longPressFired = false
                        dragging = false
                    }
                }
                true
            }
        }
    }

    private fun playBounceAnimation(view: View) {
        val movePx = dp(20).toFloat()
        view.animate().cancel()
        view.translationY = 0f
        view.animate()
            .translationY(-movePx)
            .setDuration(100)
            .withEndAction {
                view.animate()
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(OvershootInterpolator(2f))
                    .start()
            }
            .start()
    }

    var onDockIconLongClick: ((AppInfo, Int) -> Unit)? = null
    var onIntentShortcutLongClick: ((IntentShortcutInfo, Int) -> Unit)? = null

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
