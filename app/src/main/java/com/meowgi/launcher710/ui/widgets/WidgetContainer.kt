package com.meowgi.launcher710.ui.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.meowgi.launcher710.util.LauncherPrefs
import org.json.JSONArray
import org.json.JSONObject

class WidgetContainer @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val widgetIds = mutableListOf<Int>()
    private var widgetHost: WidgetHost? = null
    private val prefs = LauncherPrefs(context)
    private var pageId: String = "favorites"
    private val snapDp = 8

    init {
        orientation = VERTICAL
    }

    fun setup(host: WidgetHost, pageId: String) {
        this.widgetHost = host
        this.pageId = pageId
        loadSavedWidgets()
    }

    private fun getSavedHeight(widgetId: Int, defaultPx: Int): Int {
        val data = prefs.getPageWidgetHeights(pageId) ?: return defaultPx
        return try {
            JSONObject(data).optInt(widgetId.toString(), defaultPx)
        } catch (_: Exception) { defaultPx }
    }

    private fun saveWidgetHeight(widgetId: Int, heightPx: Int) {
        val obj = try { JSONObject(prefs.getPageWidgetHeights(pageId) ?: "{}") } catch (_: Exception) { JSONObject() }
        obj.put(widgetId.toString(), heightPx)
        prefs.setPageWidgetHeights(pageId, obj.toString())
    }

    fun addWidget(widgetId: Int, info: AppWidgetProviderInfo) {
        val host = widgetHost ?: return
        try {
            val hostView = host.createView(context, widgetId, info)
            hostView.setAppWidget(widgetId, info)
            addWidgetView(widgetId, hostView, info)
        } catch (_: Exception) {
            widgetHost?.deleteWidgetId(widgetId)
        }
    }

    private var activeResizeFrame: ResizableWidgetFrame? = null

    fun exitResizeMode() {
        activeResizeFrame?.exitResizeMode()
        activeResizeFrame = null
    }

    private fun addWidgetView(widgetId: Int, hostView: android.appwidget.AppWidgetHostView, info: AppWidgetProviderInfo) {
        val defaultH = dp(info.minHeight.coerceAtLeast(100).coerceAtMost(600))
        val heightPx = getSavedHeight(widgetId, defaultH).coerceAtLeast(dp(48))
        val density = resources.displayMetrics.density
        val widthPx = resources.displayMetrics.widthPixels
        val widthDp = (widthPx / density).toInt()
        val heightDp = (heightPx / density).toInt()
        val opts = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
        }
        AppWidgetManager.getInstance(context).updateAppWidgetOptions(widgetId, opts)

        val wrapper = ResizableWidgetFrame(context, null, widgetId, heightPx, snapDp, prefs) { newH ->
            saveWidgetHeight(widgetId, newH)
        }
        wrapper.addView(hostView, 0, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        wrapper.onRemoveWidget = { removeWidget(widgetId, wrapper) }
        wrapper.setOnLongClickListener {
            activeResizeFrame?.exitResizeMode()
            activeResizeFrame = wrapper
            wrapper.enterResizeMode()
            true
        }

        val lp = LayoutParams(LayoutParams.MATCH_PARENT, heightPx).apply {
            bottomMargin = dp(2)
        }
        addView(wrapper, lp)
        widgetIds.add(widgetId)
        saveWidgets()
        updateVisibility()
    }

    fun removeWidget(widgetId: Int, view: View) {
        removeView(view)
        widgetIds.remove(widgetId)
        widgetHost?.deleteWidgetId(widgetId)
        saveWidgets()
        updateVisibility()
    }

    private fun loadSavedWidgets() {
        removeAllViews()
        widgetIds.clear()
        val data = prefs.getPageWidgetData(pageId) ?: return

        try {
            val arr = JSONArray(data)
            val awm = AppWidgetManager.getInstance(context)
            for (i in 0 until arr.length()) {
                val id = arr.getInt(i)
                val info = awm.getAppWidgetInfo(id) ?: continue
                val host = widgetHost ?: continue
                val defaultH = dp(info.minHeight.coerceAtLeast(100).coerceAtMost(600))
                val heightPx = getSavedHeight(id, defaultH).coerceAtLeast(dp(48))
                val density = resources.displayMetrics.density
                val widthDp = (resources.displayMetrics.widthPixels / density).toInt()
                val heightDp = (heightPx / density).toInt()
                val opts = Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
                }
                awm.updateAppWidgetOptions(id, opts)
                val hostView = try {
                    host.createView(context, id, info).also { it.setAppWidget(id, info) }
                } catch (_: Exception) { continue }

                val wrapper = ResizableWidgetFrame(context, null, id, heightPx, snapDp, prefs) { newH ->
                    saveWidgetHeight(id, newH)
                }
                wrapper.addView(hostView, 0, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
                wrapper.onRemoveWidget = { removeWidget(id, wrapper) }
                wrapper.setOnLongClickListener {
                    activeResizeFrame?.exitResizeMode()
                    activeResizeFrame = wrapper
                    wrapper.enterResizeMode()
                    true
                }

                val lp = LayoutParams(LayoutParams.MATCH_PARENT, heightPx).apply {
                    bottomMargin = dp(2)
                }
                addView(wrapper, lp)
                widgetIds.add(id)
            }
        } catch (_: Exception) {}
        updateVisibility()
    }

    private fun saveWidgets() {
        val arr = JSONArray()
        widgetIds.forEach { arr.put(it) }
        prefs.setPageWidgetData(pageId, arr.toString())
    }

    fun hasWidgets(): Boolean = widgetIds.isNotEmpty()

    private fun updateVisibility() {
        visibility = if (widgetIds.isNotEmpty()) VISIBLE else GONE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
