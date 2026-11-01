package com.meowgi.launcher710.util

import android.annotation.SuppressLint
import android.content.Context

object NotificationShade {

    /**
     * Expands the system notification shade through the hidden
     * `StatusBarManager.expandNotificationsPanel()` (needs `EXPAND_STATUS_BAR`). Returns false when
     * the service or method is unavailable or refuses, so the caller can fall back.
     */
    @SuppressLint("WrongConstant")
    fun expand(context: Context): Boolean = try {
        val statusBar = context.getSystemService("statusbar")
        if (statusBar == null) {
            false
        } else {
            statusBar.javaClass.getMethod("expandNotificationsPanel").invoke(statusBar)
            true
        }
    } catch (_: Throwable) {
        false
    }
}
