package com.meowgi.launcher710.ui.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.util.Log

class WidgetHost(context: Context) {

    companion object {
        const val HOST_ID = 1024
        const val TAG_WIDGET = "BBWidget"
        private val TAG get() = TAG_WIDGET
    }

    val host = object : AppWidgetHost(context, HOST_ID) {
        override fun onCreateView(
            context: Context,
            appWidgetId: Int,
            appWidgetInfo: AppWidgetProviderInfo
        ): AppWidgetHostView = LauncherAppWidgetHostView(context)
    }

    fun startListening() {
        try {
            host.startListening()
            Log.d(TAG, "startListening OK")
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
        }
    }

    fun stopListening() {
        try {
            host.stopListening()
            Log.d(TAG, "stopListening OK")
        } catch (e: Exception) {
            Log.e(TAG, "stopListening failed", e)
        }
    }

    fun allocateId(): Int = host.allocateAppWidgetId()

    fun createView(context: Context, widgetId: Int, info: AppWidgetProviderInfo): AppWidgetHostView {
        val provider = info.provider?.flattenToString() ?: "null"
        Log.d(TAG, "createView widgetId=$widgetId provider=$provider")
        val view = host.createView(context, widgetId, info)
        Log.d(TAG, "createView done widgetId=$widgetId")
        return view
    }

    fun deleteWidgetId(widgetId: Int) {
        try {
            host.deleteAppWidgetId(widgetId)
            Log.d(TAG, "deleteWidgetId widgetId=$widgetId")
        } catch (e: Exception) {
            Log.e(TAG, "deleteWidgetId failed widgetId=$widgetId", e)
        }
    }
}
