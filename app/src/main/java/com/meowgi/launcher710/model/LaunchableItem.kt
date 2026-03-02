package com.meowgi.launcher710.model

import android.graphics.drawable.Drawable

/** One item in the app grid: either a normal app or a pinned app shortcut. */
sealed class LaunchableItem {
    abstract val label: CharSequence
    abstract val icon: Drawable

    data class App(val app: AppInfo) : LaunchableItem() {
        override val label: CharSequence get() = app.label
        override val icon: Drawable get() = app.icon
    }

    data class Shortcut(val shortcut: ShortcutDisplayInfo) : LaunchableItem() {
        override val label: CharSequence get() = shortcut.label
        override val icon: Drawable get() = shortcut.icon
    }

    /** Shortcut from system "Add shortcut" / "All shortcuts" (e.g. MacroDroid macros, Activity). */
    data class IntentShortcut(val info: IntentShortcutInfo) : LaunchableItem() {
        override val label: CharSequence get() = info.label
        override val icon: Drawable get() = info.icon
    }

    /** Contact from device address book, shown in extended search. Tap to dial first number. */
    data class Contact(
        val displayName: CharSequence,
        val phoneNumbers: List<String>,
        override val icon: Drawable
    ) : LaunchableItem() {
        override val label: CharSequence get() = displayName
    }

    /** Built-in shortcut to open Launcher settings. Shown on All apps page and in search. */
    data class LauncherSettings(override val label: CharSequence, override val icon: Drawable) : LaunchableItem()
}
