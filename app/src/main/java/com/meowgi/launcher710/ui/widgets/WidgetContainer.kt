package com.meowgi.launcher710.ui.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
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

    companion object {
        private const val TAG = "BBWidget"
    }

    private fun providerStr(info: AppWidgetProviderInfo?) = info?.provider?.flattenToString() ?: "null"

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    fun setup(host: WidgetHost, pageId: String) {
        this.widgetHost = host
        this.pageId = pageId
        loadSavedWidgets()
    }

    /** Returns (widthPx, heightPx). Uses provider-aware bounds; avoids overly strict height clamp. */
    private fun getSavedSize(widgetId: Int, defaultWidthPx: Int, defaultHeightPx: Int, maxHeightPx: Int = dp(800)): Pair<Int, Int> {
        val sizesJson = prefs.getPageWidgetSizes(pageId) ?: run {
            val h = getSavedHeightLegacy(widgetId, defaultHeightPx)
            return Pair(defaultWidthPx, h)
        }
        return try {
            val obj = JSONObject(sizesJson).optJSONObject(widgetId.toString())
            if (obj != null) {
                val w = obj.optInt("w", defaultWidthPx).coerceIn(dp(80), defaultWidthPx)
                val h = obj.optInt("h", defaultHeightPx).coerceIn(dp(48), maxHeightPx)
                Pair(w, h)
            } else {
                Pair(defaultWidthPx, getSavedHeightLegacy(widgetId, defaultHeightPx))
            }
        } catch (e: Exception) {
            Log.w(TAG, "getSavedSize parse failed widgetId=$widgetId using defaults", e)
            Pair(defaultWidthPx, getSavedHeightLegacy(widgetId, defaultHeightPx))
        }
    }

    private fun getSavedHeightLegacy(widgetId: Int, defaultPx: Int): Int {
        val data = prefs.getPageWidgetHeights(pageId) ?: return defaultPx
        return try {
            JSONObject(data).optInt(widgetId.toString(), defaultPx)
        } catch (e: Exception) {
            Log.w(TAG, "getSavedHeightLegacy failed widgetId=$widgetId using default", e)
            defaultPx
        }
    }

    private fun saveWidgetSize(widgetId: Int, widthPx: Int, heightPx: Int) {
        val current = try {
            JSONObject(prefs.getPageWidgetSizes(pageId) ?: "{}")
        } catch (e: Exception) {
            Log.w(TAG, "saveWidgetSize parse failed widgetId=$widgetId", e)
            JSONObject()
        }
        current.put(widgetId.toString(), JSONObject().apply {
            put("w", widthPx)
            put("h", heightPx)
        })
        prefs.setPageWidgetSizes(pageId, current.toString())
    }

    fun addWidget(widgetId: Int, info: AppWidgetProviderInfo) {
        Log.d(TAG, "addWidget widgetId=$widgetId provider=${providerStr(info)}")
        val host = widgetHost ?: return
        try {
            val hostView = host.createView(context, widgetId, info)
            hostView.setAppWidget(widgetId, info)
            Log.d(TAG, "addWidget setAppWidget done widgetId=$widgetId")
            val fullWidth = resources.displayMetrics.widthPixels
            val defaultH = dp(info.minHeight.coerceAtLeast(100).coerceAtMost(800))
            val (widthPx, heightPx) = getSavedSize(widgetId, fullWidth, defaultH)
            updateWidgetSizeOnHostView(hostView, info, widthPx, heightPx)
            addWidgetView(widgetId, hostView, info)
            Log.d(TAG, "addWidget complete widgetId=$widgetId provider=${providerStr(info)}")
        } catch (e: Exception) {
            Log.e(TAG, "addWidget FAILED widgetId=$widgetId provider=${providerStr(info)}", e)
            widgetHost?.deleteWidgetId(widgetId)
        }
    }

    private var activeResizeFrame: ResizableWidgetFrame? = null

    fun exitResizeMode() {
        activeResizeFrame?.exitResizeMode()
        activeResizeFrame = null
    }

    /**
     * Use the framework HostView API with provider-aware min/max (do not force min=max).
     * Gives providers a valid range instead of a single fixed size to improve compatibility.
     */
    @Suppress("DEPRECATION")
    private fun updateWidgetSizeOnHostView(
        hostView: android.appwidget.AppWidgetHostView,
        info: AppWidgetProviderInfo,
        widthPx: Int,
        heightPx: Int
    ) {
        val density = resources.displayMetrics.density
        val screenWdp = (resources.displayMetrics.widthPixels / density).toInt()
        val screenHdp = (resources.displayMetrics.heightPixels / density).toInt()
        val minW = info.minWidth.coerceAtLeast(40)
        val minH = info.minHeight.coerceAtLeast(40)
        val maxW = (widthPx / density).toInt().coerceIn(minW, screenWdp)
        val maxH = (heightPx / density).toInt().coerceIn(minH, screenHdp.coerceAtLeast(800))
        hostView.updateAppWidgetSize(Bundle(), minW, minH, maxW, maxH)
        Log.d(TAG, "updateWidgetSizeOnHostView widgetId=${hostView.appWidgetId} minDp=${minW}x${minH} maxDp=${maxW}x${maxH}")
    }

    private fun addWidgetView(widgetId: Int, hostView: android.appwidget.AppWidgetHostView, info: AppWidgetProviderInfo) {
        val fullWidth = resources.displayMetrics.widthPixels
        val defaultH = dp(info.minHeight.coerceAtLeast(100).coerceAtMost(800))
        val (widthPx, heightPx) = getSavedSize(widgetId, fullWidth, defaultH)
        Log.d(TAG, "addWidgetView widgetId=$widgetId provider=${providerStr(info)} widthPx=$widthPx heightPx=$heightPx")

        val wrapper = ResizableWidgetFrame(
            context, null, widgetId, widthPx, heightPx, snapDp, prefs,
            onSizeChanged = { w, h -> saveWidgetSize(widgetId, w, h) },
            onResizeEnd = { w, h ->
                saveWidgetSize(widgetId, w, h)
                updateWidgetSizeOnHostView(hostView, info, w, h)
            }
        )
        wrapper.addView(hostView, 0, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        wrapper.onRemoveWidget = { removeWidget(widgetId, wrapper) }
        wrapper.onReorderRequest = { delta ->
            val idx = indexOfChild(wrapper)
            if (idx >= 0) reorderWidget(idx, (idx + delta).coerceIn(0, widgetIds.size - 1))
        }
        wrapper.setOnLongClickListener {
            activeResizeFrame?.exitResizeMode()
            activeResizeFrame = wrapper
            wrapper.enterResizeMode()
            true
        }

        val lp = LayoutParams(widthPx, heightPx).apply {
            bottomMargin = dp(2)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        addView(wrapper, lp)
        widgetIds.add(widgetId)
        saveWidgets()
        updateVisibility()
        val handler = Handler(Looper.getMainLooper())
        hostView.post {
            updateWidgetSizeOnHostView(hostView, info, widthPx, heightPx)
            handler.postDelayed({ updateWidgetSizeOnHostView(hostView, info, widthPx, heightPx) }, 350)
        }
        // Help collection widgets: notify multiple common view IDs (widget 3 had "Loading" inside)
        handler.postDelayed({
            notifyCollectionWidgetDataChanged(widgetId)
        }, 500)
    }

    /** Call notifyAppWidgetViewDataChanged for common list/grid view IDs so content loads. */
    private fun notifyCollectionWidgetDataChanged(widgetId: Int) {
        val awm = AppWidgetManager.getInstance(context)
        val viewIds = intArrayOf(
            android.R.id.list,
            android.R.id.primary,
            android.R.id.content,
            android.R.id.empty
        )
        for (viewId in viewIds) {
            try {
                awm.notifyAppWidgetViewDataChanged(widgetId, viewId)
            } catch (e: Exception) {
                Log.w(TAG, "notifyAppWidgetViewDataChanged failed widgetId=$widgetId viewId=0x${Integer.toHexString(viewId)}", e)
            }
        }
    }

    fun removeWidget(widgetId: Int, view: View) {
        removeView(view)
        widgetIds.remove(widgetId)
        widgetHost?.deleteWidgetId(widgetId)
        saveWidgets()
        updateVisibility()
    }

    fun reorderWidget(fromIndex: Int, toIndex: Int) {
        if (fromIndex < 0 || toIndex < 0 || fromIndex >= widgetIds.size || toIndex >= widgetIds.size || fromIndex == toIndex) return
        val id = widgetIds.removeAt(fromIndex)
        widgetIds.add(toIndex, id)
        val view = getChildAt(fromIndex)
        val lp = view.layoutParams
        removeViewAt(fromIndex)
        addView(view, toIndex.coerceIn(0, childCount), lp)
        saveWidgets()
    }

    private fun loadSavedWidgets() {
        removeAllViews()
        widgetIds.clear()
        val data = prefs.getPageWidgetData(pageId) ?: return
        Log.d(TAG, "loadSavedWidgets pageId=$pageId data=$data")

        try {
            val arr = JSONArray(data)
            val awm = AppWidgetManager.getInstance(context)
            for (i in 0 until arr.length()) {
                val id = arr.getInt(i)
                val info = awm.getAppWidgetInfo(id)
                if (info == null) {
                    Log.w(TAG, "loadSavedWidgets widgetId=$id getAppWidgetInfo=null (stale/uninstalled), pruning from saved list")
                    continue
                }
                Log.d(TAG, "loadSavedWidgets widgetId=$id provider=${providerStr(info)}")
                val host = widgetHost ?: continue
                val fullWidth = resources.displayMetrics.widthPixels
                val defaultH = dp(info.minHeight.coerceAtLeast(100).coerceAtMost(800))
                val (widthPx, heightPx) = getSavedSize(id, fullWidth, defaultH)

                val hostView = try {
                    host.createView(context, id, info).also { it.setAppWidget(id, info) }
                } catch (e: Exception) {
                    Log.e(TAG, "loadSavedWidgets createView/setAppWidget FAILED widgetId=$id provider=${providerStr(info)}", e)
                    continue
                }

                val wrapper = ResizableWidgetFrame(
                    context, null, id, widthPx, heightPx, snapDp, prefs,
                    onSizeChanged = { w, h -> saveWidgetSize(id, w, h) },
                    onResizeEnd = { w, h ->
                        saveWidgetSize(id, w, h)
                        updateWidgetSizeOnHostView(hostView, info, w, h)
                    }
                )
                wrapper.addView(hostView, 0, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
                wrapper.onRemoveWidget = { removeWidget(id, wrapper) }
                wrapper.onReorderRequest = { delta ->
                    val idx = indexOfChild(wrapper)
                    if (idx >= 0) reorderWidget(idx, (idx + delta).coerceIn(0, widgetIds.size - 1))
                }
                wrapper.setOnLongClickListener {
                    activeResizeFrame?.exitResizeMode()
                    activeResizeFrame = wrapper
                    wrapper.enterResizeMode()
                    true
                }

                val lp = LayoutParams(widthPx, heightPx).apply {
                    bottomMargin = dp(2)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                addView(wrapper, lp)
                widgetIds.add(id)
                Log.d(TAG, "loadSavedWidgets added widgetId=$id provider=${providerStr(info)}")
                val handler = Handler(Looper.getMainLooper())
                hostView.post {
                    updateWidgetSizeOnHostView(hostView, info, widthPx, heightPx)
                    handler.postDelayed({ updateWidgetSizeOnHostView(hostView, info, widthPx, heightPx) }, 350)
                }
                handler.postDelayed({
                    notifyCollectionWidgetDataChanged(id)
                }, 500)
            }
            saveWidgets()
        } catch (e: Exception) {
            Log.e(TAG, "loadSavedWidgets exception pageId=$pageId", e)
        }
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
