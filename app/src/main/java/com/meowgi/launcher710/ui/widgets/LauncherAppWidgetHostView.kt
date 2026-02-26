package com.meowgi.launcher710.ui.widgets

import android.content.Context
import android.util.Log
import android.widget.RemoteViews

/**
 * Custom host view that logs when RemoteViews are received so we can see
 * if a widget stays on "Loading" because the provider never sent content.
 */
class LauncherAppWidgetHostView(context: Context) : android.appwidget.AppWidgetHostView(context) {

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        val widgetId = appWidgetId
        val hasContent = remoteViews != null
        Log.d(WidgetHost.TAG_WIDGET, "updateAppWidget widgetId=$widgetId hasRemoteViews=$hasContent")
        super.updateAppWidget(remoteViews)
    }
}
