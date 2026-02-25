package com.meowgi.launcher710.model

import android.graphics.drawable.Drawable

/**
 * A shortcut created via the system "Create shortcut" flow (ACTION_CREATE_SHORTCUT).
 * Shows "All shortcuts", app-specific pickers (e.g. MacroDroid macros), Activity, etc.
 */
data class IntentShortcutInfo(
    val label: String,
    val intentUri: String,
    val icon: Drawable
) {
    /** Stable key for custom icon prefs (same intent => same key). */
    val shortcutKey: String get() = "intent_shortcut:${intentUri.hashCode()}"
}
