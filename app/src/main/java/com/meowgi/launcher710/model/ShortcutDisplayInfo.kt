package com.meowgi.launcher710.model

import android.graphics.drawable.Drawable

/**
 * Display info for an app shortcut (from ShortcutInfo) pinned to a page.
 * Key for persistence/custom icon: "shortcut:$packageName|$shortcutId"
 */
data class ShortcutDisplayInfo(
    val packageName: String,
    val shortcutId: String,
    val label: CharSequence,
    val icon: Drawable
) {
    val shortcutKey: String get() = "shortcut:$packageName|$shortcutId"
}
