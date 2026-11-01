package com.meowgi.launcher710.ui.settings

import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog
import com.meowgi.launcher710.R
import com.meowgi.launcher710.RestartLauncherActivity
import com.meowgi.launcher710.ai.AiSearchTrigger
import com.meowgi.launcher710.util.ContactSearchHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class SettingsScreen(val title: String, val summary: String) {
    HOME("Settings", ""),
    APPEARANCE("Appearance", "Accent, highlights, wallpaper, icons"),
    HOME_SCREEN("Home screen & dock", "Status bar, header, action bar, tabs, dock"),
    APPS("Apps & pages", "Pages, grid or list layout, sorting, hidden apps"),
    SEARCH("Search", "Keyboard search, results, contacts, commands"),
    AI("AI answers", "Answers in search from an OpenAI-compatible API"),
    GESTURES("Gestures & actions", "Swipes, double tap, tap actions"),
    NOTIFICATIONS("Notifications", "Notification hub and applets"),
    BACKUP("Backup & restore", "Back up or restore the whole launcher"),
    ABOUT("About & system", "Version, permissions, restart, reset");
}

internal fun SettingsActivity.buildScreen(screen: SettingsScreen) {
    when (screen) {
        SettingsScreen.HOME -> buildHome()
        SettingsScreen.APPEARANCE -> buildAppearance()
        SettingsScreen.HOME_SCREEN -> buildHomeScreen()
        SettingsScreen.APPS -> buildApps()
        SettingsScreen.SEARCH -> buildSearch()
        SettingsScreen.AI -> buildAi()
        SettingsScreen.GESTURES -> buildGestures()
        SettingsScreen.NOTIFICATIONS -> buildNotifications()
        SettingsScreen.BACKUP -> buildBackup()
        SettingsScreen.ABOUT -> buildAbout()
    }
}

private fun SettingsActivity.buildHome() {
    group(null) {
        for (screen in listOf(
            SettingsScreen.APPEARANCE, SettingsScreen.HOME_SCREEN, SettingsScreen.APPS,
            SettingsScreen.SEARCH, SettingsScreen.AI, SettingsScreen.GESTURES, SettingsScreen.NOTIFICATIONS
        )) categoryRow(screen)
    }
    group(null) {
        categoryRow(SettingsScreen.BACKUP)
        categoryRow(SettingsScreen.ABOUT)
    }
}

private val SHAPES = listOf("Default", "Circle", "Rounded Square", "Square", "Squircle")

private fun SettingsActivity.buildAppearance() {
    group("Colors") {
        presetColorRow("Accent color", ACCENT_PRESETS, prefs.accentColor, "Choose color…") { prefs.accentColor = it }
        colorChoiceRow("Click highlight color", "Use accent", prefs.accentColor, prefs.clickHighlightUseAccent, prefs.clickHighlightCustomColor,
            { prefs.clickHighlightUseAccent = it }, { prefs.clickHighlightCustomColor = it })
        sliderRow("Click highlight opacity", prefs.clickHighlightAlpha) { prefs.clickHighlightAlpha = it }
        sliderRow("Main background opacity", prefs.mainBackgroundAlpha) { prefs.mainBackgroundAlpha = it }
    }
    group("Wallpaper") {
        actionRow("Choose wallpaper", "Pick a static wallpaper") { pickWallpaper() }
        actionRow("Live wallpaper", "Pick an animated wallpaper") { pickLiveWallpaper() }
    }
    group("Icons") {
        actionRow("Global icon pack", getIconPackName()) { showIconPackPicker() }
        choiceRow("Icon shape", SHAPES, prefs.iconGlobalShape.coerceIn(0, 4)) { prefs.iconGlobalShape = it }
        choiceRow("Fallback icon shape", SHAPES.drop(1), prefs.iconFallbackShape) { prefs.iconFallbackShape = it }
        choiceRow("Icon size", listOf("Small", "Medium", "Large"), prefs.iconSizeIndex) { prefs.iconSizeIndex = it }
    }
    group("Icon pack per page", footer = "Unset pages use the global icon pack.") {
        for (pid in prefs.getPageOrder()) {
            val name = pageName(pid)
            actionRow(name, getPageIconPackName(pid)) { showPageIconPackPicker(pid, name) }
        }
        actionRow("Dock", getPageIconPackName("dock")) { showPageIconPackPicker("dock", "Dock") }
        actionRow("Search", getPageIconPackName("search")) { showPageIconPackPicker("search", "Search") }
    }
    group("Sound profile overlay") {
        sliderRow("Overlay opacity", prefs.soundProfileOverlayAlpha) { prefs.soundProfileOverlayAlpha = it }
        colorChoiceRow("Overlay background", "Default (black)", 0xFF000000.toInt(),
            prefs.soundProfileOverlayUseDefaultBackground, prefs.soundProfileOverlayCustomBackgroundColor,
            { prefs.soundProfileOverlayUseDefaultBackground = it }, { prefs.soundProfileOverlayCustomBackgroundColor = it })
        colorChoiceRow("Highlight color", "Use accent", prefs.accentColor,
            prefs.soundProfileHighlightUseAccent, prefs.soundProfileHighlightCustomColor,
            { prefs.soundProfileHighlightUseAccent = it }, { prefs.soundProfileHighlightCustomColor = it })
        sliderRow("Highlight opacity", prefs.soundProfileHighlightAlpha) { prefs.soundProfileHighlightAlpha = it }
    }
}

private fun SettingsActivity.buildHomeScreen() {
    group("Status bar") {
        toggleRow("Show BB status bar", prefs.statusBarVisible) { prefs.statusBarVisible = it }
        sliderRow("Status bar opacity", prefs.statusBarAlpha) { prefs.statusBarAlpha = it }
    }
    group("Status bar indicators") {
        toggleRow("Clock", prefs.statusBarShowClock) { prefs.statusBarShowClock = it }
        toggleRow("Battery", prefs.statusBarShowBattery) { prefs.statusBarShowBattery = it }
        toggleRow("Network", prefs.statusBarShowNetwork) { prefs.statusBarShowNetwork = it }
        toggleRow("Bluetooth", prefs.statusBarShowBluetooth) { prefs.statusBarShowBluetooth = it }
        toggleRow("Alarm", prefs.statusBarShowAlarm) { prefs.statusBarShowAlarm = it }
        toggleRow("Do Not Disturb", prefs.statusBarShowDND) { prefs.statusBarShowDND = it }
    }
    group("System bars") {
        toggleRow("Show system status bar", prefs.systemStatusBarVisible) { prefs.systemStatusBarVisible = it }
        sliderRow("System status bar opacity", prefs.systemStatusBarAlpha) { prefs.systemStatusBarAlpha = it }
        toggleRow("Show navigation bar", prefs.navigationBarVisible) { prefs.navigationBarVisible = it }
    }
    group("Header") {
        toggleRow("Show header", prefs.headerVisible, "Date and clock above the pages") { prefs.headerVisible = it; rebuildSettings() }
        sliderRow("Header opacity", prefs.headerAlpha) { prefs.headerAlpha = it }
        if (prefs.headerVisible) {
            toggleRow("Show date", prefs.headerShowDate) { prefs.headerShowDate = it }
            toggleRow("Show clock", prefs.headerShowClock) { prefs.headerShowClock = it }
            choiceRow("Header layout", listOf("Date left, Clock right", "Clock left, Date right", "Centered"), prefs.headerLayout.coerceIn(0, 2)) { prefs.headerLayout = it }
        }
    }
    group("Action bar & tabs") {
        sliderRow("Action bar (ticker) opacity", prefs.actionBarAlpha) { prefs.actionBarAlpha = it }
        sliderRow("Tab bar opacity", prefs.tabBarAlpha) { prefs.tabBarAlpha = it }
        colorChoiceRow("Tab bar highlight color", "Use accent", prefs.accentColor, prefs.tabBarHighlightUseAccent, prefs.tabBarHighlightCustomColor,
            { prefs.tabBarHighlightUseAccent = it }, { prefs.tabBarHighlightCustomColor = it })
        sliderRow("Tab bar highlight opacity", prefs.tabBarHighlightAlpha) { prefs.tabBarHighlightAlpha = it }
    }
    group("Dock") {
        presetColorRow("Dock color", DOCK_COLOR_PRESETS, prefs.dockBackgroundColor, "Custom…") { prefs.dockBackgroundColor = it }
        sliderRow("Dock opacity", prefs.dockAlpha) { prefs.dockAlpha = it }
    }
}

private fun SettingsActivity.buildApps() {
    group("Pages") {
        toggleRow("Show All apps page", !prefs.hideAllPage) { prefs.hideAllPage = !it; rebuildSettings() }
        toggleRow("Show Frequent apps page", !prefs.hideFrequentPage) { prefs.hideFrequentPage = !it; rebuildSettings() }
        val filteredOrder = prefs.getFilteredPageOrder()
        val pageNames = filteredOrder.map { pageName(it) }
        val defaultIdx = filteredOrder.indexOf(prefs.defaultTabPageId).coerceAtLeast(0)
        choiceRow("Default home tab", pageNames, defaultIdx.coerceIn(0, (pageNames.size - 1).coerceAtLeast(0))) {
            prefs.defaultTabPageId = filteredOrder[it.coerceIn(0, filteredOrder.size - 1)]
        }
        actionRow("Manage pages", "${prefs.getPageOrder().size} pages · reorder, add, scrolling") { showPageManager() }
    }
    group("Layout") {
        choiceRow("App view mode", listOf("Grid", "Old School List"), prefs.appViewMode) {
            prefs.appViewMode = it
            rebuildSettings()
        }
        if (prefs.appViewMode == 1) {
            actionRow("List view applies to", getListViewAppliesLabel()) { showListViewPagesPicker() }
            choiceRow("List view columns", listOf("1", "2", "3"), prefs.listViewColumns - 1) { prefs.listViewColumns = it + 1 }
            colorChoiceRow("List icon bar color", "Use accent", prefs.accentColor, prefs.listViewUseAccent, prefs.listViewCustomColor,
                { prefs.listViewUseAccent = it }, { prefs.listViewCustomColor = it })
            sliderRow("List icon bar opacity", prefs.listViewIconBarAlpha) { prefs.listViewIconBarAlpha = it }
            sliderRow("List name bar opacity", prefs.listViewNameBarAlpha) { prefs.listViewNameBarAlpha = it }
        }
        choiceRow("Grid columns", listOf("3", "4", "5", "6"), prefs.gridColumns - 3) { prefs.gridColumns = it + 3 }
    }
    group("Sorting") {
        choiceRow("Sort apps by", listOf("Alphabetical", "Last Opened", "Last Installed", "Most Used"), prefs.appSortMode.coerceIn(0, 3)) {
            prefs.appSortMode = it
        }
        actionRow("Sort applies to", getSortAppliesLabel()) { showSortPagesPicker() }
    }
    group("Hidden apps") {
        val hidden = prefs.getHiddenApps().size
        actionRow("Hidden apps", if (hidden == 0) "None" else "$hidden hidden") { showHiddenAppsPicker() }
    }
}

private fun SettingsActivity.buildSearch() {
    // Old 3 = shortcut with no URI becomes 5 (disabled) so it doesn't read "Launch shortcut" with nothing set.
    if (prefs.searchEngineMode == 3 && prefs.searchEngineShortcutIntentUri == null) {
        prefs.searchEngineMode = 5
    }
    group("Physical keyboard") {
        toggleRow("Search on physical keyboard", prefs.searchOnType, "Typing on the home screen starts a search") { prefs.searchOnType = it }
        choiceRow("Physical keyboard search",
            listOf("Built-in search", "Launch app", "Launch app with query", "Launch shortcut", "Launch app/shortcut and inject key", "Disabled"),
            prefs.searchEngineMode.coerceIn(0, 5)) {
            prefs.searchEngineMode = it
            rebuildSettings()
        }
        when (prefs.searchEngineMode) {
            1, 2 -> {
                actionRow("Search app", getSearchEngineAppLabel()) { showSearchEngineAppPicker() }
                if (prefs.searchEngineMode == 2) {
                    actionRow("Custom intent URI (optional)", prefs.searchEngineIntentUri?.take(30)?.let { "$it…" } ?: "None") { showSearchEngineIntentUriDialog() }
                }
            }
            3 -> {
                actionRow("Search shortcut", prefs.searchEngineShortcutName ?: "Choose shortcut") { launchShortcutPicker(searchShortcutLauncher) }
                actionRow("Clear search shortcut", showChevron = false) {
                    prefs.searchEngineShortcutIntentUri = null
                    prefs.searchEngineShortcutName = null
                    rebuildSettings()
                }
            }
            4 -> {
                actionRow("App/shortcut", prefs.searchEngineLaunchInjectName ?: "Choose app or shortcut") { showLaunchInjectAppOrShortcutMenu() }
                actionRow("Clear app/shortcut", showChevron = false) {
                    prefs.searchEngineLaunchInjectIntentUri = null
                    prefs.searchEngineLaunchInjectName = null
                    rebuildSettings()
                }
                toggleRow("Wait for input focus", prefs.searchEngineLaunchInjectWaitForFocus, "Recommended") {
                    prefs.searchEngineLaunchInjectWaitForFocus = it
                }
                toggleRow("Use root injection", prefs.searchEngineLaunchInjectUseRoot, "Requires root") {
                    prefs.searchEngineLaunchInjectUseRoot = it
                    rebuildSettings()
                }
                if (prefs.searchEngineLaunchInjectUseRoot) {
                    toggleRow("Alternative listener", prefs.searchEngineLaunchInjectAlternativeListener, "Capture a short burst, inject as text") {
                        prefs.searchEngineLaunchInjectAlternativeListener = it
                        rebuildSettings()
                    }
                    numberRow("Burst window", prefs.searchEngineLaunchInjectAlternativeWindowMs.coerceIn(0, 500), 0, 500, 120, "ms", "120 (0–500)") {
                        prefs.searchEngineLaunchInjectAlternativeWindowMs = it
                    }
                }
                numberRow("Inject key delay", prefs.searchEngineLaunchInjectDelayMs.coerceIn(0, 5000), 0, 5000, 0, "ms", "0–5000") {
                    prefs.searchEngineLaunchInjectDelayMs = it
                }
            }
        }
    }
    group("Results") {
        toggleRow("Google suggestions when nothing matches", prefs.searchWebSuggestions) { prefs.searchWebSuggestions = it }
        toggleRow("Rank results by usage", prefs.searchUsageRanking) { prefs.searchUsageRanking = it }
        actionRow("Reset usage data", "Launch counts and last-opened times; favorites are kept", showChevron = false) { confirmResetUsageData() }
        choiceRow(getString(R.string.search_contacts), listOf("Enabled", "Disabled", "Enable only with @"), prefs.searchContactsMode.coerceIn(0, 2)) {
            prefs.searchContactsMode = it
            rebuildSettings()
        }
        val sourceOptions = ContactSearchHelper.getContactSourceOptions(this)
        val sourceValues = sourceOptions.map { it.second }
        val sourceSelected = sourceValues.indexOf(prefs.searchContactsSource ?: "all").coerceIn(0, (sourceValues.size - 1).coerceAtLeast(0))
        choiceRow(getString(R.string.contact_source), sourceOptions.map { it.first }, sourceSelected) {
            prefs.searchContactsSource = sourceValues.getOrElse(it) { "all" }
        }
        actionRow(getString(R.string.contact_icon), getContactIconLabel()) { showContactIconPicker() }
        choiceRow(getString(R.string.dialer_number_layout),
            listOf(getString(R.string.dialer_layout_qwerty), getString(R.string.dialer_layout_t9)), prefs.dialerNumberLayout) {
            prefs.dialerNumberLayout = it
        }
    }
    group("Commands") {
        actionRow("Command trigger", prefs.searchCommandTrigger?.takeIf { it.isNotEmpty() }?.let { "\"$it\"" } ?: "Not set") { showCommandTriggerDialog() }
        actionRow("Command icon", getCommandIconLabel()) { showCommandIconPicker() }
        actionRow("Manage commands", "${prefs.getSearchCommands().size} commands") { showSearchCommandsManager() }
        if (!prefs.searchCommandTrigger.isNullOrEmpty()) {
            actionRow("Clear command trigger", showChevron = false) {
                prefs.searchCommandTrigger = null
                rebuildSettings()
            }
        }
    }
    group("Search overlay") {
        sliderRow("Overlay opacity", prefs.searchOverlayAlpha) { prefs.searchOverlayAlpha = it }
        colorChoiceRow("Overlay background", "Default (black)", 0xFF000000.toInt(),
            prefs.searchOverlayUseDefaultBackground, prefs.searchOverlayCustomBackgroundColor,
            { prefs.searchOverlayUseDefaultBackground = it }, { prefs.searchOverlayCustomBackgroundColor = it })
    }
}

private fun SettingsActivity.buildAi() {
    group(null, footer = "Ask from search with Alt+Enter, or fire ${AiSearchTrigger.ACTION} (e.g. from Key Mapper) " +
        "to answer the current query. Plain Enter still searches the web.") {
        toggleRow("AI answers in search", prefs.aiEnabled) { prefs.aiEnabled = it }
        toggleRow("Alt+Enter asks AI", prefs.aiAltEnter, "On the search field; Alt held, tapped or locked") { prefs.aiAltEnter = it }
    }
    group("Connection", footer = "Any OpenAI-compatible API. The key is encrypted on this device and never included in backups.") {
        actionRow("Base URL", prefs.aiBaseUrl) { showAiBaseUrlDialog() }
        actionRow("API key", if (aiSecrets.hasApiKey) "Saved" else "Not set") { showAiApiKeyDialog() }
    }
    group("Model") {
        actionRow("Model", prefs.aiModel.ifBlank { "Not set" }) { showAiModelPicker() }
        actionRow("Fetch models", prefs.aiFetchedModels.size.let { if (it == 0) "Load the list from the server" else "$it available" }) {
            fetchAiModels()
        }
        actionRow("System prompt", prefs.aiSystemPrompt.ifBlank { "None" }.let { if (it.length > 60) it.take(60) + "…" else it }) {
            showAiSystemPromptDialog()
        }
    }
    group(null) {
        actionRow("Test", "Send a short request with these settings", showChevron = false) { testAi() }
    }
}

private const val KEY_SHORTCUTS_HELP = """Home short: go home or lock screen
Home long: launch assistant
Back short: default back
Back double: quick settings
Back long: refresh launcher
Recents short: default (not intercepted)
Recents long: settings popup
Recents double: notification hub"""

private fun keyCodeLabel(keyCode: Int): String {
    if (keyCode == 0) return "Not set"
    val name = KeyEvent.keyCodeToString(keyCode)
    return if (name.startsWith("KEYCODE_")) name.drop(8) else keyCode.toString()
}

private fun SettingsActivity.buildGestures() {
    group("Gestures") {
        choiceRow("Swipe to switch pages", listOf("Bottom bar only", "Anywhere on screen"), prefs.swipeMode) { prefs.swipeMode = it }
        toggleRow("Swipe down for notifications", prefs.swipeDownNotifications) { prefs.swipeDownNotifications = it }
        choiceRow("Double-tap action", listOf("None", "Lock Screen", "Notifications"), prefs.doubleTapAction) { prefs.doubleTapAction = it }
    }
    group("Tap actions", footer = if (prefs.headerVisible) null else "Date and clock actions appear when the header is shown.") {
        if (prefs.headerVisible) {
            actionRow(TapAction.HEADER_DATE.title, tapActionLabel(TapAction.HEADER_DATE)) { showTapActionPicker(TapAction.HEADER_DATE) }
            actionRow(TapAction.HEADER_CLOCK.title, tapActionLabel(TapAction.HEADER_CLOCK)) { showTapActionPicker(TapAction.HEADER_CLOCK) }
        }
        actionRow(TapAction.ACTION_BAR_TAP.title, tapActionLabel(TapAction.ACTION_BAR_TAP)) { showTapActionPicker(TapAction.ACTION_BAR_TAP) }
        actionRow(TapAction.ACTION_BAR_LONG_PRESS.title, tapActionLabel(TapAction.ACTION_BAR_LONG_PRESS)) { showTapActionPicker(TapAction.ACTION_BAR_LONG_PRESS) }
    }
    if (SettingsActivity.KEY_MAP_SETTINGS_VISIBLE) {
        group("Key shortcuts") {
            toggleRow("Enable key shortcuts", prefs.keyShortcutsEnabled) { prefs.keyShortcutsEnabled = it }
            actionRow("Record Home key", keyCodeLabel(prefs.keyCodeHome)) { showRecordKeyDialog("Home") { prefs.keyCodeHome = it } }
            actionRow("Record Back key", keyCodeLabel(prefs.keyCodeBack)) { showRecordKeyDialog("Back") { prefs.keyCodeBack = it } }
            actionRow("Record Recents key", keyCodeLabel(prefs.keyCodeRecents)) { showRecordKeyDialog("Recents") { prefs.keyCodeRecents = it } }
            noteRow(KEY_SHORTCUTS_HELP)
        }
    }
}

private fun SettingsActivity.buildNotifications() {
    group("Notification hub") {
        actionRow("Apps in notification hub", getNotificationAppsLabel()) { showNotificationAppsPicker() }
        sliderRow("Hub opacity", prefs.notificationHubAlpha) { prefs.notificationHubAlpha = it }
        colorChoiceRow("Hub background", "Default (black)", 0xFF000000.toInt(),
            prefs.notificationHubUseDefaultBackground, prefs.notificationHubCustomBackgroundColor,
            { prefs.notificationHubUseDefaultBackground = it }, { prefs.notificationHubCustomBackgroundColor = it })
    }
    group("Action bar display") {
        choiceRow("Notification display", listOf("Count text", "App icons"), if (prefs.useNotificationApplets) 1 else 0) {
            prefs.useNotificationApplets = (it == 1)
            rebuildSettings()
        }
        if (prefs.useNotificationApplets) {
            actionRow("Manage applets", "${prefs.getNotificationApplets().size} apps") { showAppletManager() }
            toggleRow("Auto-hide when count is 0", prefs.notificationAppletsAutoHide) { prefs.notificationAppletsAutoHide = it }
            choiceRow("Applet icon shape", listOf("Default", "Circle", "Rounded square", "Square", "Squircle"), prefs.appletIconShape.coerceIn(0, 4)) { prefs.appletIconShape = it }
            rangeRow("Space between applets", 0, 20, prefs.notificationAppletsSpacingDp, { "${it}dp" }) { prefs.notificationAppletsSpacingDp = it }
            choiceRow("Applet icon size", listOf("Small", "Medium", "Large"), prefs.notificationAppletSizeIndex) { prefs.notificationAppletSizeIndex = it }
            actionRow("Applet icon pack", getPageIconPackName("applets")) { showPageIconPackPicker("applets", "Applets") }
        }
    }
}

private fun SettingsActivity.buildBackup() {
    group("Launcher backup", footer = "One file with all settings, the dock and pages, shortcuts and their icons, favorites and usage. " +
        "Restoring checks the whole file first and changes nothing if anything is wrong. Older settings exports can be restored too.") {
        actionRow("Back up launcher", "Save everything to a backup file") {
            backupLauncher.launch("710launcher-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.zip")
        }
        actionRow("Restore from backup", "Replace everything from a backup file") {
            restoreLauncher.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*"))
        }
    }
}

private fun SettingsActivity.buildAbout() {
    group("App") {
        infoRow("Version", run {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
        })
    }
    group("Permissions") {
        actionRow("Notification access", "Needed for the hub, ticker and applets") {
            openSystemScreen(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), "Could not open notification settings")
        }
        actionRow("Key capture", "Accessibility service used for key recording") {
            openSystemScreen(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "Could not open accessibility settings",
                hint = "Find \"BB 710 Launcher\" and enable Key capture")
        }
        actionRow("App permissions & info", "System app info page") {
            openSystemScreen(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }, "Could not open app settings")
        }
    }
    group("Maintenance") {
        actionRow("Restart launcher", "Reload the home screen", showChevron = false) {
            startActivity(Intent(this, RestartLauncherActivity::class.java))
            finish()
        }
        actionRow("Reset all settings", "Restore every setting to its default", danger = true, showChevron = false) {
            AlertDialog.Builder(this, dialogTheme)
                .setTitle("Reset Settings?")
                .setMessage("All settings will be restored to defaults.")
                .setPositiveButton("Reset") { _, _ ->
                    prefs.resetAll()
                    recreate()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
