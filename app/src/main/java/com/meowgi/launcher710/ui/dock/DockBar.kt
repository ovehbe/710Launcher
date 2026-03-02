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
        val allApps = repo.getAllApps()
        // Apps not loaded yet — bail so we never prune against an empty list and wipe the saved order.
        if (allApps.isEmpty()) return
        val lp = launcherPrefs
        val allShortcuts = if (shortcutHelper != null && lp != null)
            shortcutHelper!!.getIntentShortcutsForPage("dock", lp) else emptyList()

        // If the stored order is empty (first run after feature addition, or previously corrupted),
        // rebuild from the legacy separate prefs so existing docks are recovered.
        val stored = getUnifiedDockOrder()
        val rebuiltFromDefault = stored.isEmpty()
        val rawOrder = if (rebuiltFromDefault) buildDefaultUnifiedOrder() else stored

        // Prune keys whose backing item no longer exists (e.g. uninstalled app)
        val order = rawOrder.filter { key ->
            when {
                key.startsWith("app:") -> allApps.any { "${it.packageName}/${it.activityName}" == key.removePrefix("app:") }
                key.startsWith("shortcut:") -> allShortcuts.any { it.intentUri == key.removePrefix("shortcut:") }
                else -> false
            }
        }

        // Persist the order when it changed or when we just rebuilt from default.
        // If everything was pruned away, remove the pref so the next load rebuilds from default.
        if (rebuiltFromDefault || order.size != rawOrder.size) {
            if (order.isEmpty()) dockPrefs.edit().remove("dock_unified_order").apply()
            else saveUnifiedDockOrder(order)
        }

        if (order.isEmpty()) return

        dockAppCount = order.count { it.startsWith("app:") }

        for ((idx, key) in order.withIndex()) {
            when {
                key.startsWith("app:") -> {
                    val cn = key.removePrefix("app:")
                    val app = allApps.find { "${it.packageName}/${it.activityName}" == cn } ?: continue
                    val icon = dockIconResolver?.invoke(app) ?: app.icon
                    addView(makeDockIconView(icon, idx, { onAppLaunch?.invoke(app) }, { onDockIconLongClick?.invoke(app, idx) }), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
                }
                key.startsWith("shortcut:") -> {
                    val intentUri = key.removePrefix("shortcut:")
                    val info = allShortcuts.find { it.intentUri == intentUri } ?: continue
                    val icon = repo.getIconForIntentShortcut(info, "dock")
                    addView(makeDockIconView(icon, idx, { onIntentShortcutLaunch?.invoke(info) }, { onIntentShortcutLongClick?.invoke(info, idx) }), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
                }
            }
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
        val order = getUnifiedDockOrder().toMutableList()
        if (dragIndex < 0 || dropIndex < 0 || dragIndex >= order.size || dropIndex >= order.size) return
        val tmp = order[dragIndex]
        order[dragIndex] = order[dropIndex]
        order[dropIndex] = tmp
        saveUnifiedDockOrder(order)
        loadDock()
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
            isFocusable = true
            isClickable = true
            isFocusableInTouchMode = false
            defaultFocusHighlightEnabled = false
            launcherPrefs?.let { foreground = it.getClickHighlightRipple(context) }
            setOnClickListener { onClick() }
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
                            performClick()
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
        val key = "app:${app.packageName}/${app.activityName}"
        val order = getUnifiedDockOrder().toMutableList().ifEmpty { buildDefaultUnifiedOrder().toMutableList() }
        if (order.contains(key)) return
        if (order.size >= maxSlots) return
        // Keep legacy dock_apps pref in sync
        val current = getDockApps(repo).toMutableList()
        if (current.none { it.packageName == app.packageName && it.activityName == app.activityName }) {
            current.add(app)
            saveDock(current)
        }
        order.add(key)
        saveUnifiedDockOrder(order)
        loadDock()
    }

    fun removeFromDock(app: AppInfo) {
        val repo = repository ?: return
        val current = getDockApps(repo).filterNot {
            it.packageName == app.packageName && it.activityName == app.activityName
        }
        saveDock(current)
        val key = "app:${app.packageName}/${app.activityName}"
        val order = getUnifiedDockOrder().toMutableList().ifEmpty { buildDefaultUnifiedOrder().toMutableList() }
        order.remove(key)
        saveUnifiedDockOrder(order)
        loadDock()
    }

    fun getDockIntentShortcutsList(): List<IntentShortcutInfo> {
        val helper = shortcutHelper ?: return emptyList()
        val prefs = launcherPrefs ?: return emptyList()
        return helper.getIntentShortcutsForPage("dock", prefs)
    }

    fun addIntentShortcutToDock(info: IntentShortcutInfo) {
        val prefs = launcherPrefs ?: return
        val key = "shortcut:${info.intentUri}"
        val order = getUnifiedDockOrder().toMutableList().ifEmpty { buildDefaultUnifiedOrder().toMutableList() }
        if (order.contains(key)) return
        if (order.size >= maxSlots) return
        prefs.addIntentShortcutToPage("dock", info.label.toString(), info.intentUri, null)
        order.add(key)
        saveUnifiedDockOrder(order)
        loadDock()
    }

    fun removeIntentShortcutFromDock(info: IntentShortcutInfo) {
        launcherPrefs?.removeIntentShortcutFromPage("dock", info.intentUri)
        val key = "shortcut:${info.intentUri}"
        val order = getUnifiedDockOrder().toMutableList().ifEmpty { buildDefaultUnifiedOrder().toMutableList() }
        order.remove(key)
        saveUnifiedDockOrder(order)
        loadDock()
    }

    private fun getUnifiedDockOrder(): List<String> {
        val saved = dockPrefs.getString("dock_unified_order", null)
            ?: return buildDefaultUnifiedOrder()
        return try {
            val arr = JSONArray(saved)
            val list = (0 until arr.length()).map { arr.getString(it) }
            // Fall back to rebuilding from dock_apps/shortcuts if the saved order was
            // previously wiped to empty by a premature prune during startup.
            list.ifEmpty { buildDefaultUnifiedOrder() }
        } catch (_: Exception) { buildDefaultUnifiedOrder() }
    }

    private fun buildDefaultUnifiedOrder(): List<String> {
        val repo = repository ?: return emptyList()
        val apps = getDockApps(repo)
        val shortcuts = if (shortcutHelper != null && launcherPrefs != null)
            shortcutHelper!!.getIntentShortcutsForPage("dock", launcherPrefs!!) else emptyList()
        return apps.map { "app:${it.packageName}/${it.activityName}" } +
               shortcuts.map { "shortcut:${it.intentUri}" }
    }

    private fun saveUnifiedDockOrder(order: List<String>) {
        val arr = JSONArray()
        order.forEach { arr.put(it) }
        dockPrefs.edit().putString("dock_unified_order", arr.toString()).apply()
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
