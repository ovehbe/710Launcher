package com.meowgi.launcher710.ui.settings

import androidx.appcompat.app.AlertDialog
import com.meowgi.launcher710.util.LauncherPrefs

/**
 * Configurable tap targets. Each stores action (0 = default, 1 = app, 2 = shortcut) plus
 * package / intent URI / shortcut name in its own existing pref keys.
 */
internal enum class TapAction(val title: String, val defaultLabel: String) {
    HEADER_DATE("Date tap action", "Nothing"),
    HEADER_CLOCK("Clock tap action", "Nothing"),
    ACTION_BAR_TAP("Action bar tap", "Notification hub"),
    ACTION_BAR_LONG_PRESS("Action bar long press", "Notification hub"),
    VOICE_SEARCH_KEY("Voice search key", "Long-press 0")
}

internal class TapActionValue(val action: Int, val pkg: String?, val intentUri: String?, val name: String?)

internal fun LauncherPrefs.readTapAction(slot: TapAction): TapActionValue = when (slot) {
    TapAction.HEADER_DATE -> TapActionValue(headerDateAction, headerDateActionPackage, headerDateActionIntentUri, headerDateActionName)
    TapAction.HEADER_CLOCK -> TapActionValue(headerClockAction, headerClockActionPackage, headerClockActionIntentUri, headerClockActionName)
    TapAction.ACTION_BAR_TAP -> TapActionValue(actionBarCenterAction, actionBarCenterActionPackage, actionBarCenterActionIntentUri, actionBarCenterActionName)
    TapAction.ACTION_BAR_LONG_PRESS -> TapActionValue(
        actionBarCenterLongPressAction, actionBarCenterLongPressActionPackage,
        actionBarCenterLongPressActionIntentUri, actionBarCenterLongPressActionName
    )
    TapAction.VOICE_SEARCH_KEY -> TapActionValue(0, null, null, null) // Not user-assignable slot, managed by prefs
}

internal fun LauncherPrefs.writeTapAction(slot: TapAction, action: Int, pkg: String?, intentUri: String?, name: String?) {
    when (slot) {
        TapAction.HEADER_DATE -> {
            headerDateAction = action
            headerDateActionPackage = pkg
            headerDateActionIntentUri = intentUri
            headerDateActionName = name
        }
        TapAction.HEADER_CLOCK -> {
            headerClockAction = action
            headerClockActionPackage = pkg
            headerClockActionIntentUri = intentUri
            headerClockActionName = name
        }
        TapAction.ACTION_BAR_TAP -> {
            actionBarCenterAction = action
            actionBarCenterActionPackage = pkg
            actionBarCenterActionIntentUri = intentUri
            actionBarCenterActionName = name
        }
        TapAction.ACTION_BAR_LONG_PRESS -> {
            actionBarCenterLongPressAction = action
            actionBarCenterLongPressActionPackage = pkg
            actionBarCenterLongPressActionIntentUri = intentUri
            actionBarCenterLongPressActionName = name
        }
        TapAction.VOICE_SEARCH_KEY -> {
            // Not user-assignable, managed separately via voiceSearchLongPress0 pref
        }
    }
}

internal fun SettingsActivity.tapActionLabel(slot: TapAction): String {
    val v = prefs.readTapAction(slot)
    return when (v.action) {
        1 -> v.pkg?.let { appLabel(it) } ?: "Choose app"
        2 -> v.name ?: (if (v.intentUri != null) "Shortcut" else "Choose shortcut")
        else -> slot.defaultLabel
    }
}

internal fun SettingsActivity.showTapActionPicker(slot: TapAction) {
    AlertDialog.Builder(this, dialogTheme)
        .setTitle(slot.title)
        .setItems(arrayOf(slot.defaultLabel, "App", "Shortcut")) { _, which ->
            when (which) {
                0 -> {
                    prefs.writeTapAction(slot, 0, null, null, null)
                    rebuildSettings()
                }
                1 -> pickInstalledApp("Choose app") { pkg, _ ->
                    prefs.writeTapAction(slot, 1, pkg, null, null)
                    rebuildSettings()
                }
                2 -> launchShortcutPicker(tapActionShortcutLaunchers.getValue(slot))
            }
        }
        .show()
}
