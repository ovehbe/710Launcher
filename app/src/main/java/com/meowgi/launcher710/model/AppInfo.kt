package com.meowgi.launcher710.model

import android.content.ComponentName
import android.graphics.drawable.Drawable

data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable,
    val rawIcon: Drawable? = null,
    var launchCount: Int = 0,
    var isFavorite: Boolean = false,
    var isDocked: Boolean = false,
    val firstInstallTime: Long = 0L,
    /** Pre-computed normalized label for search (avoids per-query normalization). */
    val normalizedLabel: String = "",
    /** Pre-computed first-letter-of-each-word for prefix-per-word search (e.g. "Trendyol Go" → "tg"). */
    val initials: String = ""
) {
    val componentName: ComponentName
        get() = ComponentName(packageName, activityName)
}
