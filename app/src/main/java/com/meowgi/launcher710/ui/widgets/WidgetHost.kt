package com.meowgi.launcher710.ui.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context

class WidgetHost(context: Context) {

    companion object {
        const val HOST_ID = 1024
    }

    val host = AppWidgetHost(context, HOST_ID)

    fun startListening() {
        try { host.startListening() } catch (_: Exception) {}
    }

    fun stopListening() {
        try { host.stopListening() } catch (_: Exception) {}
    }

    fun allocateId(): Int = host.allocateAppWidgetId()

    fun createView(context: Context, widgetId: Int, info: AppWidgetProviderInfo): AppWidgetHostView {
        return host.createView(context, widgetId, info)
    }

    fun deleteWidgetId(widgetId: Int) {
        try { host.deleteAppWidgetId(widgetId) } catch (_: Exception) {}
    }
}
