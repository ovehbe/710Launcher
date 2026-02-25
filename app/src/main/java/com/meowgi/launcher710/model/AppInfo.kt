package com.meowgi.launcher710.model

import android.content.ComponentName
import android.graphics.drawable.Drawable

data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable,
    val rawIcon: Drawable? = null, // Original icon without any pack applied
    var launchCount: Int = 0,
    var isFavorite: Boolean = false,
    var isDocked: Boolean = false
) {
    val componentName: ComponentName
        get() = ComponentName(packageName, activityName)
}
