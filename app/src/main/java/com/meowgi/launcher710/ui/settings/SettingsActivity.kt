package com.meowgi.launcher710.ui.settings

import android.app.WallpaperManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.graphics.Typeface
import android.os.Bundle
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import com.meowgi.launcher710.R
import com.meowgi.launcher710.ui.notifications.NotifListenerService
import com.meowgi.launcher710.ui.dialogs.IconPickerDialog
import com.meowgi.launcher710.util.IconPackManager
import com.meowgi.launcher710.util.ContactSearchHelper
import com.meowgi.launcher710.util.LauncherPrefs
import java.text.SimpleDateFormat
import java.util.*

/** FrameLayout that never exceeds maxHeightPx so a child ScrollView can scroll. */
private class MaxHeightFrameLayout(
    context: android.content.Context,
    private val maxHeightPx: Int
) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hMode = View.MeasureSpec.getMode(heightMeasureSpec)
        val hSize = View.MeasureSpec.getSize(heightMeasureSpec)
        val capped = if (hMode == View.MeasureSpec.UNSPECIFIED || hSize > maxHeightPx) {
            View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.EXACTLY)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, capped)
    }
}

class SettingsActivity : AppCompatActivity() {

    companion object {
        /** Set to true to show Key Shortcuts section and key mapping UI again. */
        private const val KEY_MAP_SETTINGS_VISIBLE = false
        /** Hardcoded accent for Settings UI only (BB Blue); launcher accent is in prefs. */
        private val SETTINGS_ACCENT_COLOR = 0xFF0073BC.toInt()
    }

    private lateinit var prefs: LauncherPrefs
    private lateinit var iconPackManager: IconPackManager
    private lateinit var container: LinearLayout
    private val font: Typeface? by lazy { ResourcesCompat.getFont(this, R.font.bbalphas) }

    private var recordingKeyCallback: ((Int) -> Unit)? = null
    private var recordingDialog: AlertDialog? = null

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(prefs.exportToJson().toByteArray(Charsets.UTF_8)) }
                Toast.makeText(this, "Settings exported", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val searchShortcutLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
        val data = result.data!!
        @Suppress("DEPRECATION")
        val shortcutIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent::class.java)
        } else {
            data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT)
        } ?: return@registerForActivityResult
        val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME) ?: "Search"
        val intentUri = shortcutIntent.toUri(Intent.URI_INTENT_SCHEME)
        prefs.searchEngineShortcutIntentUri = intentUri
        prefs.searchEngineShortcutName = name
        rebuildSettings()
    }

    private val headerDateShortcutLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
        val data = result.data!!
        @Suppress("DEPRECATION")
        val shortcutIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent::class.java)
        } else {
            data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT)
        } ?: return@registerForActivityResult
        val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME) ?: "Shortcut"
        prefs.headerDateAction = 2
        prefs.headerDateActionPackage = null
        prefs.headerDateActionIntentUri = shortcutIntent.toUri(Intent.URI_INTENT_SCHEME)
        prefs.headerDateActionName = name
        rebuildSettings()
    }

    private val headerClockShortcutLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
        val data = result.data!!
        @Suppress("DEPRECATION")
        val shortcutIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent::class.java)
        } else {
            data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT)
        } ?: return@registerForActivityResult
        val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME) ?: "Shortcut"
        prefs.headerClockAction = 2
        prefs.headerClockActionPackage = null
        prefs.headerClockActionIntentUri = shortcutIntent.toUri(Intent.URI_INTENT_SCHEME)
        prefs.headerClockActionName = name
        rebuildSettings()
    }

    private val actionBarCenterShortcutLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
        val data = result.data!!
        @Suppress("DEPRECATION")
        val shortcutIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent::class.java)
        } else {
            data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT)
        } ?: return@registerForActivityResult
        val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME) ?: "Shortcut"
        prefs.actionBarCenterAction = 2
        prefs.actionBarCenterActionPackage = null
        prefs.actionBarCenterActionIntentUri = shortcutIntent.toUri(Intent.URI_INTENT_SCHEME)
        prefs.actionBarCenterActionName = name
        rebuildSettings()
    }

    private val launchInjectShortcutLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
        val data = result.data!!
        @Suppress("DEPRECATION")
        val shortcutIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent::class.java)
        } else {
            data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT)
        } ?: return@registerForActivityResult
        val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME) ?: "Search"
        val intentUri = shortcutIntent.toUri(Intent.URI_INTENT_SCHEME)
        prefs.searchEngineLaunchInjectIntentUri = intentUri
        prefs.searchEngineLaunchInjectName = name
        rebuildSettings()
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val json = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@registerForActivityResult
                val backup = prefs.exportToJson()
                val backupName = "launcher_backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"
                openFileOutput(backupName, MODE_PRIVATE).use { it.write(backup.toByteArray(Charsets.UTF_8)) }
                if (prefs.importFromJson(json)) {
                    Toast.makeText(this, "Settings imported. Launcher will restart.", Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    Toast.makeText(this, "Import failed (invalid file)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Import failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_settings)

        prefs = LauncherPrefs(this)
        iconPackManager = IconPackManager(this)
        container = findViewById(R.id.settingsContainer)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        buildSettings()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (recordingKeyCallback != null && event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) return false
            finishRecording(event.keyCode)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun finishRecording(keyCode: Int) {
        recordingKeyCallback?.invoke(keyCode)
        recordingKeyCallback = null
        recordingDialog?.dismiss()
        recordingDialog = null
        KeyCaptureAccessibilityService.KeyCaptureReceiver.setRecording(false, null)
        Toast.makeText(this, "Recorded: ${KeyEvent.keyCodeToString(keyCode)}", Toast.LENGTH_SHORT).show()
        rebuildSettings()
    }

    private fun buildSettings() {
        addSection("Appearance")
        addToggle("Show BB Status Bar", prefs.statusBarVisible) { prefs.statusBarVisible = it }
        addToggle("Show System Status Bar", prefs.systemStatusBarVisible) { prefs.systemStatusBarVisible = it }
        addSlider("System Status Bar Opacity", prefs.systemStatusBarAlpha) { prefs.systemStatusBarAlpha = it }
        addSection("Status Bar indicators")
        addToggle("Show clock", prefs.statusBarShowClock) { prefs.statusBarShowClock = it }
        addToggle("Show battery", prefs.statusBarShowBattery) { prefs.statusBarShowBattery = it }
        addToggle("Show network", prefs.statusBarShowNetwork) { prefs.statusBarShowNetwork = it }
        addToggle("Show Bluetooth", prefs.statusBarShowBluetooth) { prefs.statusBarShowBluetooth = it }
        addToggle("Show alarm", prefs.statusBarShowAlarm) { prefs.statusBarShowAlarm = it }
        addToggle("Show Do Not Disturb", prefs.statusBarShowDND) { prefs.statusBarShowDND = it }
        addToggle("Show Navigation Bar", prefs.navigationBarVisible) { prefs.navigationBarVisible = it }
        addSlider("Main Background Opacity", prefs.mainBackgroundAlpha) { prefs.mainBackgroundAlpha = it }
        addSlider("Status Bar Opacity", prefs.statusBarAlpha) { prefs.statusBarAlpha = it }
        addSlider("Header Opacity", prefs.headerAlpha) { prefs.headerAlpha = it }

        addSection("Header")
        addToggle("Show header", prefs.headerVisible) { prefs.headerVisible = it; rebuildSettings() }
        if (prefs.headerVisible) {
            addToggle("Show date", prefs.headerShowDate) { prefs.headerShowDate = it }
            addToggle("Show clock", prefs.headerShowClock) { prefs.headerShowClock = it }
            addChoice("Header layout", listOf("Date left, Clock right", "Clock left, Date right", "Centered"), prefs.headerLayout.coerceIn(0, 2)) { prefs.headerLayout = it }
            addButton("Date tap action: ${getHeaderActionLabel(prefs.headerDateAction, prefs.headerDateActionPackage, prefs.headerDateActionIntentUri, prefs.headerDateActionName)}") { showHeaderDateActionPicker() }
            addButton("Clock tap action: ${getHeaderActionLabel(prefs.headerClockAction, prefs.headerClockActionPackage, prefs.headerClockActionIntentUri, prefs.headerClockActionName)}") { showHeaderClockActionPicker() }
        }

        addSlider("Action bar (ticker) opacity", prefs.actionBarAlpha) { prefs.actionBarAlpha = it }
        addButton("Action bar tap: ${getActionBarCenterActionLabel()}") { showActionBarCenterActionPicker() }
        addSlider("Tab Bar Opacity", prefs.tabBarAlpha) { prefs.tabBarAlpha = it }
        addChoice("Tab bar highlight color", listOf("Use accent", "Choose color…"), if (prefs.tabBarHighlightUseAccent) 0 else 1) {
            prefs.tabBarHighlightUseAccent = (it == 0)
            if (it == 1) showColorPicker(prefs.tabBarHighlightCustomColor) { prefs.tabBarHighlightCustomColor = it }
        }
        addSlider("Tab Bar Highlight Opacity", prefs.tabBarHighlightAlpha) { prefs.tabBarHighlightAlpha = it }
        addChoice("Click highlight color", listOf("Use accent", "Choose color…"), if (prefs.clickHighlightUseAccent) 0 else 1) {
            prefs.clickHighlightUseAccent = (it == 0)
            if (it == 1) showColorPicker(prefs.clickHighlightCustomColor) { prefs.clickHighlightCustomColor = it }
        }
        addSlider("Click highlight opacity", prefs.clickHighlightAlpha) { prefs.clickHighlightAlpha = it }
        addSlider("Dock Opacity", prefs.dockAlpha) { prefs.dockAlpha = it }
        addChoice("Dock Color", listOf("Black", "Dark Gray", "Blue", "Navy", "Custom…"), getDockColorIndex()) {
            if (it == 4) {
                showColorPicker(prefs.dockBackgroundColor) { prefs.dockBackgroundColor = it }
            } else {
                prefs.dockBackgroundColor = when (it) {
                    0 -> 0xFF000000.toInt()
                    1 -> 0xFF1A1A1A.toInt()
                    2 -> 0xFF001A33.toInt()
                    3 -> 0xFF0A1628.toInt()
                    else -> 0xFF000000.toInt()
                }
            }
        }
        addChoice("Accent Color", listOf("BB Blue", "Teal", "Red", "Green", "Purple", "Orange", "White", "Choose color…"), getAccentColorIndex()) {
            if (it == 7) {
                showColorPicker(prefs.accentColor) { prefs.accentColor = it }
            } else {
                prefs.accentColor = when (it) {
                    0 -> 0xFF0073BC.toInt() // BB Blue
                    1 -> 0xFF00BFA5.toInt() // Teal
                    2 -> 0xFFFF5252.toInt() // Red
                    3 -> 0xFF4CAF50.toInt() // Green
                    4 -> 0xFF9C27B0.toInt() // Purple
                    5 -> 0xFFFF9800.toInt() // Orange
                    else -> 0xFFFFFFFF.toInt() // White
                }
            }
        }
        addChoice("App View Mode", listOf("Grid", "Old School List"), prefs.appViewMode) {
            prefs.appViewMode = it
            rebuildSettings()
        }
        if (prefs.appViewMode == 1) {
            addButton("List View Applies To: ${getListViewAppliesLabel()}") { showListViewPagesPicker() }
            addChoice("List View Columns", listOf("1", "2", "3"), prefs.listViewColumns - 1) {
                prefs.listViewColumns = it + 1
            }
            addChoice("List icon bar color", listOf("Use accent", "Choose color"), if (prefs.listViewUseAccent) 0 else 1) {
                prefs.listViewUseAccent = (it == 0)
                if (it == 1) showColorPicker(prefs.listViewCustomColor) { prefs.listViewCustomColor = it }
            }
            addSlider("List icon bar opacity", prefs.listViewIconBarAlpha) { prefs.listViewIconBarAlpha = it }
            addSlider("List name bar opacity", prefs.listViewNameBarAlpha) { prefs.listViewNameBarAlpha = it }
        }
        addChoice("Grid Columns", listOf("3", "4", "5", "6"), prefs.gridColumns - 3) {
            prefs.gridColumns = it + 3
        }
        addChoice("Icon Size", listOf("Small", "Medium", "Large"), prefs.iconSizeIndex) {
            prefs.iconSizeIndex = it
        }

        addSection("Wallpaper")
        addButton("Choose Wallpaper") { pickWallpaper() }
        addButton("Live Wallpaper") { pickLiveWallpaper() }

        addSection("Icons")
        addButton("Global Icon Pack: ${getIconPackName()}") { showIconPackPicker() }
        // Per-page icon pack pickers
        val pageOrder = prefs.getPageOrder()
        for (pid in pageOrder) {
            val name = when (pid) {
                "frequent" -> "Frequent"; "favorites" -> "Favorites"; "all" -> "All"
                else -> pid.removePrefix("custom_")
            }
            val packName = getPageIconPackName(pid)
            addButton("$name Icon Pack: $packName") { showPageIconPackPicker(pid, name) }
        }
        addButton("Dock Icon Pack: ${getPageIconPackName("dock")}") { showPageIconPackPicker("dock", "Dock") }
        addButton("Search Icon Pack: ${getPageIconPackName("search")}") { showPageIconPackPicker("search", "Search") }
        addChoice("Icon shape", listOf("Default", "Circle", "Rounded Square", "Square", "Squircle"), prefs.iconGlobalShape.coerceIn(0, 4)) { prefs.iconGlobalShape = it }
        addChoice("Fallback Icon Shape",
            listOf("Circle", "Rounded Square", "Square", "Squircle"),
            prefs.iconFallbackShape) { prefs.iconFallbackShape = it }

        addSection("Notifications")
        addSlider("Notification hub opacity", prefs.notificationHubAlpha) { prefs.notificationHubAlpha = it }
        addSlider("Search overlay opacity", prefs.searchOverlayAlpha) { prefs.searchOverlayAlpha = it }
        addButton("Apps in notification hub: ${getNotificationAppsLabel()}") { showNotificationAppsPicker() }

        addSection("Notification Applets")
        addChoice("Notification display", listOf("Count text", "App icons"), if (prefs.useNotificationApplets) 1 else 0) {
            prefs.useNotificationApplets = (it == 1)
            rebuildSettings()
        }
        if (prefs.useNotificationApplets) {
            addToggle("Auto-hide when count is 0", prefs.notificationAppletsAutoHide) { prefs.notificationAppletsAutoHide = it }
            addChoice("Applet icon shape", listOf("Default", "Circle", "Rounded square", "Square", "Squircle"), prefs.appletIconShape.coerceIn(0, 4)) { prefs.appletIconShape = it }
            addButton("Applet Icon Pack: ${getPageIconPackName("applets")}") { showPageIconPackPicker("applets", "Applets") }
            addButton("Manage applets (${prefs.getNotificationApplets().size})") { showAppletManager() }
        }

        addSection("Permissions")
        addButton("Notification access") {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Could not open notification settings", Toast.LENGTH_SHORT).show()
            }
        }
        addButton("Key capture (for key recording)") {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                Toast.makeText(this, "Find \"BB 710 Launcher\" and enable Key capture", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Could not open accessibility settings", Toast.LENGTH_SHORT).show()
            }
        }
        addButton("App permissions & info") {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "Could not open app settings", Toast.LENGTH_SHORT).show()
            }
        }

        addSection("Hidden Apps")
        addButton("Hidden apps (${prefs.getHiddenApps().size})") { showHiddenAppsPicker() }

        addSection("Pages")
        addToggle("Show All apps page", !prefs.hideAllPage) { prefs.hideAllPage = !it; rebuildSettings() }
        addToggle("Show Frequent apps page", !prefs.hideFrequentPage) { prefs.hideFrequentPage = !it; rebuildSettings() }
        addButton("Manage Pages") { showPageManager() }

        addSection("Behavior")
        addChoice("Swipe to switch pages", listOf("Bottom bar only", "Anywhere on screen"), prefs.swipeMode) {
            prefs.swipeMode = it
        }
        val filteredOrder = prefs.getFilteredPageOrder()
        val pageNames = filteredOrder.map { when(it) { "frequent" -> "Frequent"; "favorites" -> "Favorites"; "all" -> "All"; else -> it.removePrefix("custom_") } }
        val defaultIdx = filteredOrder.indexOf(prefs.defaultTabPageId).coerceAtLeast(0)
        addChoice("Default Home Tab", pageNames, defaultIdx.coerceIn(0, (pageNames.size - 1).coerceAtLeast(0))) {
            prefs.defaultTabPageId = filteredOrder[it.coerceIn(0, filteredOrder.size - 1)]
        }
        addChoice("Double-Tap Action", listOf("None", "Lock Screen", "Notifications"), prefs.doubleTapAction) {
            prefs.doubleTapAction = it
        }
        addSection("Sorting")
        addChoice("Sort apps by", listOf("Alphabetical", "Last Opened", "Last Installed", "Most Used"), prefs.appSortMode.coerceIn(0, 3)) {
            prefs.appSortMode = it
        }
        addButton("Sort applies to: ${getSortAppliesLabel()}") { showSortPagesPicker() }
        addSection("Search engine")
        // Migrate: old 3=shortcut no uri -> 5 (disabled) so they don't see "Launch shortcut" with nothing set
        if (prefs.searchEngineMode == 3 && prefs.searchEngineShortcutIntentUri == null) {
            prefs.searchEngineMode = 5
        }
        addChoice("Physical keyboard search", listOf("Built-in search", "Launch app", "Launch app with query", "Launch shortcut", "Launch app/shortcut and inject key", "Disabled"), prefs.searchEngineMode.coerceIn(0, 5)) {
            prefs.searchEngineMode = it
            rebuildSettings()
        }
        if (prefs.searchEngineMode == 1 || prefs.searchEngineMode == 2) {
            addButton("Search app: ${getSearchEngineAppLabel()}") { showSearchEngineAppPicker() }
            if (prefs.searchEngineMode == 2) {
                addButton("Custom intent URI (optional): ${prefs.searchEngineIntentUri?.take(30)?.let { "$it…" } ?: "None"}") { showSearchEngineIntentUriDialog() }
            }
        }
        if (prefs.searchEngineMode == 3) {
            addButton("Search shortcut: ${prefs.searchEngineShortcutName ?: "Choose shortcut"}") {
                try {
                    searchShortcutLauncher.launch(Intent(Intent.ACTION_CREATE_SHORTCUT))
                } catch (_: Exception) {
                    Toast.makeText(this, "No shortcut handler found", Toast.LENGTH_SHORT).show()
                }
            }
            addButton("Clear search shortcut") {
                prefs.searchEngineShortcutIntentUri = null
                prefs.searchEngineShortcutName = null
                rebuildSettings()
            }
        }
        if (prefs.searchEngineMode == 4) {
            addButton("App/shortcut: ${prefs.searchEngineLaunchInjectName ?: "Choose app or shortcut"}") {
                showLaunchInjectAppOrShortcutMenu()
            }
            addButton("Clear app/shortcut") {
                prefs.searchEngineLaunchInjectIntentUri = null
                prefs.searchEngineLaunchInjectName = null
                rebuildSettings()
            }
            addToggle("Wait for input focus (recommended)", prefs.searchEngineLaunchInjectWaitForFocus) {
                prefs.searchEngineLaunchInjectWaitForFocus = it
            }
            addToggle("Use root injection (requires root)", prefs.searchEngineLaunchInjectUseRoot) {
                prefs.searchEngineLaunchInjectUseRoot = it
                rebuildSettings()
            }
            if (prefs.searchEngineLaunchInjectUseRoot) {
                addToggle("Alternative listener (capture short burst, inject as text)", prefs.searchEngineLaunchInjectAlternativeListener) {
                    prefs.searchEngineLaunchInjectAlternativeListener = it
                    rebuildSettings()
                }
                addInjectAlternativeWindowInput()
            }
            addInjectDelayInput()
        }
        addToggle("Search on Physical Keyboard", prefs.searchOnType) { prefs.searchOnType = it }
        addChoice(getString(R.string.dialer_number_layout), listOf(getString(R.string.dialer_layout_qwerty), getString(R.string.dialer_layout_t9)), prefs.dialerNumberLayout) { prefs.dialerNumberLayout = it }
        addToggle(getString(R.string.search_contacts), prefs.searchContactsEnabled) { prefs.searchContactsEnabled = it }
        run {
            val sourceOptions = ContactSearchHelper.getContactSourceOptions(this)
            val sourceLabels = sourceOptions.map { it.first }
            val sourceValues = sourceOptions.map { it.second }
            val currentSource = prefs.searchContactsSource ?: "all"
            val sourceSelected = sourceValues.indexOf(currentSource).coerceIn(0, (sourceValues.size - 1).coerceAtLeast(0))
            addChoice(getString(R.string.contact_source), sourceLabels, sourceSelected) { prefs.searchContactsSource = sourceValues.getOrElse(it) { "all" } }
        }

        // Key map settings hidden for now; enable key shortcuts stays off by default
        if (KEY_MAP_SETTINGS_VISIBLE) {
            addSection("Key Shortcuts")
            addToggle("Enable key shortcuts", prefs.keyShortcutsEnabled) { prefs.keyShortcutsEnabled = it }
            addButton("Record Home key${getKeyCodeLabel(prefs.keyCodeHome)}") { showRecordKeyDialog("Home") { prefs.keyCodeHome = it } }
            addButton("Record Back key${getKeyCodeLabel(prefs.keyCodeBack)}") { showRecordKeyDialog("Back") { prefs.keyCodeBack = it } }
            addButton("Record Recents key${getKeyCodeLabel(prefs.keyCodeRecents)}") { showRecordKeyDialog("Recents") { prefs.keyCodeRecents = it } }
            addKeyShortcutsInfo()
        }

        addSection("Backup & restore")
        addButton("Export settings") {
            exportLauncher.launch("bb_launcher_settings_${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.json")
        }
        addButton("Import settings") {
            AlertDialog.Builder(this, R.style.BBDialogTheme)
                .setTitle("Import settings?")
                .setMessage("Current settings will be backed up to app storage first, then replaced. Launcher will close after import.")
                .setPositiveButton("Import") { _, _ -> importLauncher.launch(arrayOf("application/json", "*/*")) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        addSection("About")
        addInfo("Version", "1.0")
        addButton("Reset All Settings") {
            AlertDialog.Builder(this)
                .setTitle("Reset Settings?")
                .setMessage("All settings will be restored to defaults.")
                .setPositiveButton("Reset") { _, _ ->
                    prefs.resetAll()
                    recreate()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        addButton("Restart Launcher") {
            val intent = android.content.Intent(this, com.meowgi.launcher710.RestartLauncherActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    private fun showHiddenAppsPicker() {
        val hidden = prefs.getHiddenApps().toMutableSet()
        if (hidden.isEmpty()) {
            Toast.makeText(this, "No hidden apps", Toast.LENGTH_SHORT).show()
            return
        }
        val maxHeightPx = (resources.displayMetrics.heightPixels * 0.6).toInt().coerceAtLeast(dp(240))
        val wrapper = MaxHeightFrameLayout(this, maxHeightPx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        fun getLabelForComponent(cn: String): String {
            return try {
                val component = android.content.ComponentName.unflattenFromString(cn) ?: return cn
                val info = packageManager.getActivityInfo(component, 0)
                packageManager.getApplicationLabel(info.applicationInfo).toString()
            } catch (_: Exception) { cn }
        }
        var dialogRef: AlertDialog? = null
        for (cn in hidden.sortedBy { getLabelForComponent(it).lowercase() }) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            val label = TextView(this).apply {
                text = getLabelForComponent(cn)
                setTextColor(getColor(R.color.bb_text_primary))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                typeface = font
            }
            val unhideBtn = Button(this).apply {
                text = "Unhide"
                setOnClickListener {
                    prefs.unhideApp(cn)
                    hidden.remove(cn)
                    dialogRef?.dismiss()
                    rebuildSettings()
                }
            }
            row.addView(label)
            row.addView(unhideBtn)
            layout.addView(row)
        }
        scroll.addView(layout)
        wrapper.addView(scroll)
        dialogRef = AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("Hidden apps")
            .setView(wrapper)
            .setNegativeButton("Done") { _, _ -> rebuildSettings() }
            .create()
        dialogRef!!.show()
    }

    private fun showPageManager() {
        val pageOrder = prefs.getPageOrder().toMutableList()
        val builtIn = setOf("frequent", "favorites", "all")
        var currentDialog: AlertDialog? = null

        fun pageName(id: String) = when(id) {
            "frequent" -> "Frequent"; "favorites" -> "Favorites"; "all" -> "All"
            else -> id.removePrefix("custom_")
        }

        fun rebuildDialog() {
            currentDialog?.dismiss()
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }

            for ((index, pageId) in pageOrder.withIndex()) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                }
                val leftCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val label = TextView(this).apply {
                    text = pageName(pageId)
                    textSize = 14f
                    setTextColor(getColor(R.color.bb_text_primary))
                    typeface = font
                }
                leftCol.addView(label)

                val scrollToggle = android.widget.CheckBox(this).apply {
                    text = "Scrollable"
                    setTextColor(getColor(R.color.bb_text_dim))
                    textSize = 11f
                    isChecked = prefs.isPageScrollable(pageId)
                    setOnCheckedChangeListener { _, checked ->
                        prefs.setPageScrollable(pageId, checked)
                    }
                }
                leftCol.addView(scrollToggle)
                row.addView(leftCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                if (index > 0) {
                    val upBtn = TextView(this).apply {
                        text = "▲"; textSize = 16f; setPadding(dp(8), 0, dp(8), 0)
                        setTextColor(SETTINGS_ACCENT_COLOR)
                        setOnClickListener {
                            pageOrder[index] = pageOrder[index - 1].also { pageOrder[index - 1] = pageOrder[index] }
                            prefs.setPageOrder(pageOrder)
                            rebuildDialog()
                        }
                    }
                    row.addView(upBtn)
                }
                if (index < pageOrder.size - 1) {
                    val downBtn = TextView(this).apply {
                        text = "▼"; textSize = 16f; setPadding(dp(8), 0, dp(8), 0)
                        setTextColor(SETTINGS_ACCENT_COLOR)
                        setOnClickListener {
                            pageOrder[index] = pageOrder[index + 1].also { pageOrder[index + 1] = pageOrder[index] }
                            prefs.setPageOrder(pageOrder)
                            rebuildDialog()
                        }
                    }
                    row.addView(downBtn)
                }
                if (pageId !in builtIn) {
                    val deleteBtn = TextView(this).apply {
                        text = "✕"; textSize = 16f; setPadding(dp(8), 0, dp(8), 0)
                        setTextColor(Color.parseColor("#FF4444"))
                        setOnClickListener {
                            pageOrder.remove(pageId)
                            prefs.setPageOrder(pageOrder)
                            rebuildDialog()
                        }
                    }
                    row.addView(deleteBtn)
                }
                layout.addView(row)
            }

            val scroll = android.widget.ScrollView(this)
            scroll.addView(layout)

            currentDialog = AlertDialog.Builder(this, R.style.BBDialogTheme)
                .setTitle("Manage Pages")
                .setView(scroll)
                .setPositiveButton("Add Page") { _, _ ->
                    val input = android.widget.EditText(this).apply {
                        hint = "Page name"
                        setPadding(dp(16), dp(16), dp(16), dp(16))
                    }
                    AlertDialog.Builder(this, R.style.BBDialogTheme)
                        .setTitle("New Page")
                        .setView(input)
                        .setPositiveButton("Add") { _, _ ->
                            val name = input.text.toString().trim()
                            if (name.isNotEmpty()) {
                                pageOrder.add("custom_$name")
                                prefs.setPageOrder(pageOrder)
                                rebuildDialog()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                .setNegativeButton("Done", null)
                .show()
        }

        rebuildDialog()
    }

    private fun getNotificationAppsLabel(): String {
        val w = prefs.getNotificationAppWhitelist()
        return if (w.isEmpty()) "All apps" else "${w.size} app(s)"
    }

    private fun showNotificationAppsPicker() {
        val whitelist = prefs.getNotificationAppWhitelist()
        val installed = packageManager.getInstalledApplications(0)
        val pkgList = installed
            .mapNotNull { info ->
                val label = try { packageManager.getApplicationLabel(info).toString() } catch (_: Exception) { null } ?: return@mapNotNull null
                if (label.isBlank()) null else (info.packageName to label)
            }
            .sortedBy { it.second.lowercase() }
        if (pkgList.isEmpty()) {
            Toast.makeText(this, "No installed apps found.", Toast.LENGTH_SHORT).show()
            return
        }
        val maxHeightPx = (resources.displayMetrics.heightPixels * 0.6).toInt().coerceAtLeast(dp(240))
        val wrapper = MaxHeightFrameLayout(this, maxHeightPx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val hint = TextView(this).apply {
            text = "When none selected, all apps are shown. Select apps to show only their notifications."
            setPadding(0, 0, 0, dp(8))
            setTextColor(Color.GRAY)
            textSize = 12f
        }
        layout.addView(hint)
        val checks = mutableMapOf<String, CheckBox>()
        for ((pkg, label) in pkgList) {
            val cb = CheckBox(this).apply {
                text = label
                isChecked = pkg in whitelist
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            checks[pkg] = cb
            layout.addView(cb)
        }
        scroll.addView(layout)
        wrapper.addView(scroll)
        val dialog = AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("Apps in notification hub")
            .setView(wrapper)
            .setPositiveButton("OK") { _, _ ->
                val selected = checks.filter { it.value.isChecked }.keys
                prefs.setNotificationAppWhitelist(selected)
            }
            .setNegativeButton("Clear (show all)") { _, _ -> prefs.setNotificationAppWhitelist(emptySet()) }
            .setNeutralButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun showAppletManager() {
        val packages = prefs.getNotificationApplets().toMutableList()
        val maxHeightPx = (resources.displayMetrics.heightPixels * 0.6).toInt().coerceAtLeast(dp(240))
        val wrapper = MaxHeightFrameLayout(this, maxHeightPx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val rowsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(rowsContainer)

        fun getAppletIconForDisplay(pkg: String): android.graphics.drawable.Drawable? {
            val customName = prefs.getAppletCustomIcon(pkg)
            val customPack = prefs.getAppletCustomIconPack(pkg)
            if (customName != null && customPack != null) {
                val mgr = IconPackManager(this@SettingsActivity)
                if (mgr.loadIconPack(customPack)) {
                    mgr.getIconByName(customName)?.let { return it }
                }
            }
            return try { packageManager.getApplicationIcon(packageManager.getApplicationInfo(pkg, 0)) } catch (_: Exception) { null }
        }

        fun refreshList() {
            rowsContainer.removeAllViews()
            for (pkg in packages) {
                val label = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) { pkg }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                val iconView = ImageView(this).apply {
                    setImageDrawable(getAppletIconForDisplay(pkg))
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
                }
                val labelView = TextView(this).apply {
                    text = label
                    setPadding(dp(8), 0, dp(8), 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val changeIconBtn = Button(this).apply {
                    text = "Change icon"
                    setOnClickListener {
                        val picker = IconPickerDialog(this@SettingsActivity,
                            onIconSelected = { drawableName, packPackage ->
                                prefs.setAppletCustomIcon(pkg, drawableName, packPackage)
                                refreshList()
                            },
                            onReset = {
                                prefs.setAppletCustomIcon(pkg, null)
                                refreshList()
                            }
                        )
                        picker.showPackPicker()
                    }
                }
                val removeBtn = Button(this).apply {
                    text = "Remove"
                    setOnClickListener {
                        packages.remove(pkg)
                        prefs.setNotificationApplets(packages)
                        refreshList()
                    }
                }
                row.addView(iconView)
                row.addView(labelView)
                row.addView(changeIconBtn)
                row.addView(removeBtn)
                rowsContainer.addView(row)
            }
        }
        val addBtn = Button(this).apply {
            text = "Add app"
            setOnClickListener {
                val currentSet = packages.toSet()
                val installed = packageManager.getInstalledApplications(0)
                    .mapNotNull { info ->
                        val label = try { packageManager.getApplicationLabel(info).toString() } catch (_: Exception) { null } ?: return@mapNotNull null
                        if (label.isBlank() || info.packageName in currentSet) null else (info.packageName to label)
                    }
                    .sortedBy { it.second.lowercase() }
                if (installed.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "No more apps to add.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val items = installed.map { it.second }.toTypedArray()
                AlertDialog.Builder(this@SettingsActivity, R.style.BBDialogTheme)
                    .setTitle("Add applet")
                    .setItems(items) { _, which ->
                        val (pkg, _) = installed[which]
                        packages.add(pkg)
                        prefs.setNotificationApplets(packages)
                        refreshList()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        layout.addView(addBtn)
        refreshList()
        wrapper.addView(scroll)
        scroll.addView(layout)
        AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("Notification applets")
            .setView(wrapper)
            .setNegativeButton("Done", null)
            .show()
    }

    private fun getDockColorIndex(): Int {
        return when (prefs.dockBackgroundColor) {
            0xFF000000.toInt() -> 0
            0xFF1A1A1A.toInt() -> 1
            0xFF001A33.toInt() -> 2
            0xFF0A1628.toInt() -> 3
            else -> 4 // Custom
        }
    }

    private fun getAccentColorIndex(): Int {
        return when (prefs.accentColor) {
            0xFF0073BC.toInt() -> 0 // BB Blue
            0xFF00BFA5.toInt() -> 1 // Teal
            0xFFFF5252.toInt() -> 2 // Red
            0xFF4CAF50.toInt() -> 3 // Green
            0xFF9C27B0.toInt() -> 4 // Purple
            0xFFFF9800.toInt() -> 5 // Orange
            0xFFFFFFFF.toInt() -> 6 // White
            else -> 7 // Custom
        }
    }

    /** Shows presets + custom (RGB sliders); calls onColor with 0xFFRRGGBB. */
    private fun showColorPicker(initialColor: Int, onColor: (Int) -> Unit) {
        val presets = listOf(
            "BB Blue" to 0xFF0073BC.toInt(),
            "Teal" to 0xFF00BFA5.toInt(),
            "Red" to 0xFFFF5252.toInt(),
            "Green" to 0xFF4CAF50.toInt(),
            "Purple" to 0xFF9C27B0.toInt(),
            "Orange" to 0xFFFF9800.toInt(),
            "White" to 0xFFFFFFFF.toInt(),
            "Custom…" to null
        )
        val options = presets.map { it.first }.toTypedArray()
        AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("Choose color")
            .setItems(options) { _, which ->
                val color = presets[which].second
                if (color != null) {
                    onColor(color)
                } else {
                    showCustomColorPicker(initialColor, onColor)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomColorPicker(initialColor: Int, onColor: (Int) -> Unit) {
        var r = Color.red(initialColor)
        var g = Color.green(initialColor)
        var b = Color.blue(initialColor)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        val sliders = mutableListOf<Pair<SeekBar, TextView>>()
        fun makeSlider(label: String, value: Int, onChanged: (Int) -> Unit): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            header.addView(makeLabel(label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val valueText = TextView(this).apply {
                text = value.toString()
                setTextColor(SETTINGS_ACCENT_COLOR)
                textSize = 12f
            }
            header.addView(valueText)
            row.addView(header)
            val seek = SeekBar(this).apply {
                max = 255
                progress = value
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        valueText.text = p.toString()
                        if (fromUser) onChanged(p)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            sliders.add(seek to valueText)
            row.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return row
        }

        val preview = View(this).apply {
            setBackgroundColor(Color.rgb(r, g, b))
            minimumHeight = dp(48)
        }
        val hexEdit = EditText(this).apply {
            setText("#%02X%02X%02X".format(r, g, b))
            hint = "#RRGGBB"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextColor(SETTINGS_ACCENT_COLOR)
            textSize = 14f
        }
        val hsvPicker = HsvColorPickerView(this).apply {
            setColor(Color.rgb(r, g, b))
            minimumHeight = dp(220)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
        }
        fun syncFromRgb() {
            preview.setBackgroundColor(Color.rgb(r, g, b))
            hexEdit.setText("#%02X%02X%02X".format(r, g, b))
            if (sliders.size >= 3) {
                sliders[0].first.progress = r
                sliders[0].second.text = r.toString()
                sliders[1].first.progress = g
                sliders[1].second.text = g.toString()
                sliders[2].first.progress = b
                sliders[2].second.text = b.toString()
            }
            hsvPicker.setColor(Color.rgb(r, g, b))
        }
        fun applyHexFromInput() {
            var raw = hexEdit.text.toString().trim()
            if (raw.isNotEmpty() && !raw.startsWith("#")) raw = "#$raw"
            if (raw.length !in 7..9) return
            try {
                val c = Color.parseColor(raw)
                r = Color.red(c)
                g = Color.green(c)
                b = Color.blue(c)
                syncFromRgb()
            } catch (_: Exception) {
                Toast.makeText(this, "Invalid hex", Toast.LENGTH_SHORT).show()
            }
        }

        content.addView(hsvPicker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        content.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(12) })
        val hexRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        hexRow.addView(makeLabel("Hex"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hexRow.addView(hexEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f))
        val applyHexBtn = TextView(this).apply {
            text = "Apply"
            setTextColor(SETTINGS_ACCENT_COLOR)
            textSize = 14f
            setPadding(dp(16), dp(8), dp(8), dp(8))
            setOnClickListener { applyHexFromInput() }
        }
        hexRow.addView(applyHexBtn)
        content.addView(hexRow)
        hexEdit.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) applyHexFromInput() }
        hexEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                applyHexFromInput()
                hexEdit.clearFocus()
                true
            } else false
        }
        hsvPicker.onColorChanged = { color ->
            r = Color.red(color)
            g = Color.green(color)
            b = Color.blue(color)
            syncFromRgb()
        }
        content.addView(makeSlider("Red", r) { r = it; syncFromRgb() })
        content.addView(makeSlider("Green", g) { g = it; syncFromRgb() })
        content.addView(makeSlider("Blue", b) { b = it; syncFromRgb() })
        syncFromRgb()

        AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("Custom color")
            .setView(content)
            .setPositiveButton("OK") { _, _ -> onColor(Color.rgb(r, g, b)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Section header ---
    private fun addSection(title: String) {
        val tv = TextView(this).apply {
            text = title
            setTextColor(SETTINGS_ACCENT_COLOR)
            textSize = 12f
            typeface = font?.let { Typeface.create(it, Typeface.BOLD) }
            setPadding(dp(16), dp(16), dp(16), dp(6))
        }
        container.addView(tv)
        addDivider()
    }

    // --- Opacity slider ---
    private fun addSlider(title: String, current: Int, onChanged: (Int) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = makeLabel(title)
        val valueText = TextView(this).apply {
            text = "${(current * 100) / 255}%"
            setTextColor(SETTINGS_ACCENT_COLOR)
            textSize = 12f
            typeface = font
        }
        header.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(valueText)
        row.addView(header)

        val slider = SeekBar(this).apply {
            max = 255
            progress = current
            setPadding(0, dp(4), 0, dp(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    valueText.text = "${(progress * 100) / 255}%"
                    if (fromUser) onChanged(progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        row.addView(slider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        container.addView(row)
        addDivider()
    }

    private fun addInjectDelayInput() {
        val minMs = 0
        val maxMs = 5000
        val current = prefs.searchEngineLaunchInjectDelayMs.coerceIn(minMs, maxMs)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val label = makeLabel("Inject key delay (ms)")
        val input = EditText(this).apply {
            setText(current.toString())
            hint = "0–5000"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minimumWidth = dp(80)
        }
        val saveBtn = TextView(this).apply {
            text = "Save"
            setTextColor(SETTINGS_ACCENT_COLOR)
            textSize = 14f
            typeface = font
            setPadding(dp(16), dp(8), dp(8), dp(8))
            setOnClickListener {
                applyDelayFromInput(input, minMs, maxMs)
                input.clearFocus()
            }
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(input)
        row.addView(saveBtn)
        container.addView(row)
        addDivider()
    }

    private fun applyDelayFromInput(edit: EditText, minMs: Int, maxMs: Int) {
        val v = edit.text.toString().toIntOrNull()?.coerceIn(minMs, maxMs) ?: 0
        prefs.searchEngineLaunchInjectDelayMs = v
        edit.setText(v.toString())
    }

    private fun addInjectAlternativeWindowInput() {
        val minMs = 0
        val maxMs = 500
        val current = prefs.searchEngineLaunchInjectAlternativeWindowMs.coerceIn(minMs, maxMs)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val label = makeLabel("Burst window (ms)")
        val input = EditText(this).apply {
            setText(current.toString())
            hint = "120 (0–500)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minimumWidth = dp(80)
        }
        val saveBtn = TextView(this).apply {
            text = "Save"
            setTextColor(SETTINGS_ACCENT_COLOR)
            textSize = 14f
            typeface = font
            setPadding(dp(16), dp(8), dp(8), dp(8))
            setOnClickListener {
                val v = input.text.toString().toIntOrNull()?.coerceIn(minMs, maxMs) ?: 120
                prefs.searchEngineLaunchInjectAlternativeWindowMs = v
                input.setText(v.toString())
                input.clearFocus()
            }
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(input)
        row.addView(saveBtn)
        container.addView(row)
        addDivider()
    }

    private fun showLaunchInjectAppOrShortcutMenu() {
        AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("App or shortcut?")
            .setItems(arrayOf("App", "Shortcut")) { _, which ->
                when (which) {
                    0 -> showLaunchInjectAppPicker()
                    1 -> {
                        try {
                            launchInjectShortcutLauncher.launch(Intent(Intent.ACTION_CREATE_SHORTCUT))
                        } catch (_: Exception) {
                            Toast.makeText(this, "No shortcut handler found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun showLaunchInjectAppPicker() {
        val installed = packageManager.getInstalledApplications(0)
        val pkgList = installed
            .mapNotNull { info ->
                val label = try { packageManager.getApplicationLabel(info).toString() } catch (_: Exception) { null } ?: return@mapNotNull null
                if (label.isBlank()) null else (info.packageName to label)
            }
            .sortedBy { it.second.lowercase() }
        val options = pkgList.map { it.second }.toTypedArray()
        AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("Choose app")
            .setItems(options) { _, which ->
                val pkg = pkgList[which].first
                val name = pkgList[which].second
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    prefs.searchEngineLaunchInjectIntentUri = intent.toUri(Intent.URI_INTENT_SCHEME)
                    prefs.searchEngineLaunchInjectName = name
                    rebuildSettings()
                } else {
                    Toast.makeText(this, "Could not get launch intent", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    // --- Choice picker ---
    private fun addChoice(title: String, options: List<String>, selected: Int, onPicked: (Int) -> Unit) {
        val row = layoutInflater.inflate(R.layout.item_setting, container, false)
        row.findViewById<TextView>(R.id.settingTitle).apply {
            text = title
            typeface = font
        }
        row.findViewById<TextView>(R.id.settingValue).apply {
            visibility = View.VISIBLE
            text = options.getOrElse(selected) { options[0] }
            typeface = font
        }
        row.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(options.toTypedArray()) { _, which ->
                    onPicked(which)
                    row.findViewById<TextView>(R.id.settingValue).text = options[which]
                }
                .show()
        }
        container.addView(row)
        addDivider()
    }

    // --- Toggle ---
    private fun addToggle(title: String, current: Boolean, onChanged: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val label = makeLabel(title)
        val toggle = Switch(this).apply {
            isChecked = current
            setOnCheckedChangeListener { _, checked -> onChanged(checked) }
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(toggle)
        container.addView(row)
        addDivider()
    }

    // --- Simple button ---
    private fun addButton(title: String, onClick: () -> Unit) {
        val row = layoutInflater.inflate(R.layout.item_setting, container, false)
        row.findViewById<TextView>(R.id.settingTitle).apply {
            text = title
            typeface = font
        }
        row.setOnClickListener { onClick() }
        container.addView(row)
        addDivider()
    }

    // --- Info display ---
    private fun addInfo(title: String, value: String) {
        val row = layoutInflater.inflate(R.layout.item_setting, container, false)
        row.findViewById<TextView>(R.id.settingTitle).apply {
            text = title
            typeface = font
        }
        row.findViewById<TextView>(R.id.settingValue).apply {
            visibility = View.VISIBLE
            text = value
            typeface = font
        }
        container.addView(row)
        addDivider()
    }

    private fun getKeyCodeLabel(keyCode: Int): String {
        if (keyCode == 0) return ""
        val name = KeyEvent.keyCodeToString(keyCode)
        return if (name.startsWith("KEYCODE_")) " (${name.drop(8)})" else " ($keyCode)"
    }

    private fun showRecordKeyDialog(role: String, onRecorded: (Int) -> Unit) {
        recordingKeyCallback = onRecorded
        val d = AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("Record $role key")
            .setMessage("Press the physical key you want to use as $role. Enable \"Key capture\" in Permissions if Home/other system keys are not detected.")
            .setCancelable(true)
            .create()
        recordingDialog = d
        KeyCaptureAccessibilityService.KeyCaptureReceiver.setRecording(true) { keyCode ->
            runOnUiThread { finishRecording(keyCode) }
        }
        d.setOnDismissListener {
            recordingKeyCallback = null
            recordingDialog = null
            KeyCaptureAccessibilityService.KeyCaptureReceiver.setRecording(false, null)
        }
        d.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode != KeyEvent.KEYCODE_BACK) {
                finishRecording(keyCode)
                true
            } else false
        }
        d.show()
    }

    private fun getSearchEngineAppLabel(): String {
        val pkg = prefs.searchEnginePackage ?: return "Choose app"
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }

    private fun getHeaderActionLabel(action: Int, pkg: String?, intentUri: String?, name: String?): String {
        return when (action) {
            0 -> "Nothing"
            1 -> {
                if (pkg != null) {
                    try {
                        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                    } catch (_: Exception) { pkg }
                } else "Choose app"
            }
            2 -> name ?: (if (intentUri != null) "Shortcut" else "Choose shortcut")
            else -> "Nothing"
        }
    }

    private fun getActionBarCenterActionLabel(): String = when (prefs.actionBarCenterAction) {
        0 -> "Notification hub"
        1 -> {
            val pkg = prefs.actionBarCenterActionPackage
            if (pkg != null) {
                try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) { pkg }
            } else "Choose app"
        }
        2 -> prefs.actionBarCenterActionName ?: (if (prefs.actionBarCenterActionIntentUri != null) "Shortcut" else "Choose shortcut")
        else -> "Notification hub"
    }

    private fun showHeaderDateActionPicker() {
        AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("Date tap action")
            .setItems(arrayOf("Nothing", "App", "Shortcut")) { _, which ->
                when (which) {
                    0 -> {
                        prefs.headerDateAction = 0
                        prefs.headerDateActionPackage = null
                        prefs.headerDateActionIntentUri = null
                        prefs.headerDateActionName = null
                        rebuildSettings()
                    }
                    1 -> showHeaderDateAppPicker()
                    2 -> {
                        try {
                            headerDateShortcutLauncher.launch(Intent(Intent.ACTION_CREATE_SHORTCUT))
                        } catch (_: Exception) {
                            Toast.makeText(this, "No shortcut handler found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun showActionBarCenterActionPicker() {
        AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("Action bar tap")
            .setItems(arrayOf("Notification hub", "App", "Shortcut")) { _, which ->
                when (which) {
                    0 -> {
                        prefs.actionBarCenterAction = 0
                        prefs.actionBarCenterActionPackage = null
                        prefs.actionBarCenterActionIntentUri = null
                        prefs.actionBarCenterActionName = null
                        rebuildSettings()
                    }
                    1 -> showActionBarCenterAppPicker()
                    2 -> {
                        try {
                            actionBarCenterShortcutLauncher.launch(Intent(Intent.ACTION_CREATE_SHORTCUT))
                        } catch (_: Exception) {
                            Toast.makeText(this, "No shortcut handler found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun showActionBarCenterAppPicker() {
        val installed = packageManager.getInstalledApplications(0)
        val pkgList = installed
            .mapNotNull { info ->
                val label = try { packageManager.getApplicationLabel(info).toString() } catch (_: Exception) { null } ?: return@mapNotNull null
                if (label.isBlank()) null else (info.packageName to label)
            }
            .sortedBy { it.second.lowercase() }
        val options = pkgList.map { it.second }.toTypedArray()
        AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("Choose app")
            .setItems(options) { _, which ->
                val (pkg) = pkgList[which]
                prefs.actionBarCenterAction = 1
                prefs.actionBarCenterActionPackage = pkg
                prefs.actionBarCenterActionIntentUri = null
                prefs.actionBarCenterActionName = null
                rebuildSettings()
            }
            .show()
    }

    private fun showHeaderClockActionPicker() {
        AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("Clock tap action")
            .setItems(arrayOf("Nothing", "App", "Shortcut")) { _, which ->
                when (which) {
                    0 -> {
                        prefs.headerClockAction = 0
                        prefs.headerClockActionPackage = null
                        prefs.headerClockActionIntentUri = null
                        prefs.headerClockActionName = null
                        rebuildSettings()
                    }
                    1 -> showHeaderClockAppPicker()
                    2 -> {
                        try {
                            headerClockShortcutLauncher.launch(Intent(Intent.ACTION_CREATE_SHORTCUT))
                        } catch (_: Exception) {
                            Toast.makeText(this, "No shortcut handler found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun showHeaderDateAppPicker() {
        val installed = packageManager.getInstalledApplications(0)
        val pkgList = installed
            .mapNotNull { info ->
                val label = try { packageManager.getApplicationLabel(info).toString() } catch (_: Exception) { null } ?: return@mapNotNull null
                if (label.isBlank()) null else (info.packageName to label)
            }
            .sortedBy { it.second.lowercase() }
        val options = pkgList.map { it.second }.toTypedArray()
        AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("Choose app")
            .setItems(options) { _, which ->
                val (pkg) = pkgList[which]
                prefs.headerDateAction = 1
                prefs.headerDateActionPackage = pkg
                prefs.headerDateActionIntentUri = null
                prefs.headerDateActionName = null
                rebuildSettings()
            }
            .show()
    }

    private fun showHeaderClockAppPicker() {
        val installed = packageManager.getInstalledApplications(0)
        val pkgList = installed
            .mapNotNull { info ->
                val label = try { packageManager.getApplicationLabel(info).toString() } catch (_: Exception) { null } ?: return@mapNotNull null
                if (label.isBlank()) null else (info.packageName to label)
            }
            .sortedBy { it.second.lowercase() }
        val options = pkgList.map { it.second }.toTypedArray()
        AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("Choose app")
            .setItems(options) { _, which ->
                val (pkg) = pkgList[which]
                prefs.headerClockAction = 1
                prefs.headerClockActionPackage = pkg
                prefs.headerClockActionIntentUri = null
                prefs.headerClockActionName = null
                rebuildSettings()
            }
            .show()
    }

    private fun showSearchEngineAppPicker() {
        val installed = packageManager.getInstalledApplications(0)
        val pkgList = installed
            .mapNotNull { info ->
                val label = try { packageManager.getApplicationLabel(info).toString() } catch (_: Exception) { null } ?: return@mapNotNull null
                if (label.isBlank()) null else (info.packageName to label)
            }
            .sortedBy { it.second.lowercase() }
        val options = pkgList.map { it.second }.toTypedArray()
        AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("Search app")
            .setItems(options) { _, which ->
                prefs.searchEnginePackage = pkgList[which].first
                rebuildSettings()
            }
            .setNegativeButton("Clear") { _, _ ->
                prefs.searchEnginePackage = null
                rebuildSettings()
            }
            .show()
    }

    private fun showSearchEngineIntentUriDialog() {
        val input = EditText(this).apply {
            setText(prefs.searchEngineIntentUri ?: "")
            hint = "intent:#Intent;..."
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("Custom intent URI")
            .setMessage("Optional. Use %s for query placeholder if the app supports it.")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                prefs.searchEngineIntentUri = input.text.toString().trim().takeIf { it.isNotEmpty() }
                rebuildSettings()
            }
            .setNegativeButton("Clear") { _, _ ->
                prefs.searchEngineIntentUri = null
                rebuildSettings()
            }
            .show()
    }

    private fun addKeyShortcutsInfo() {
        val text = """
            Home short: go home or lock screen
            Home long: launch assistant
            Back short: default back
            Back double: quick settings
            Back long: refresh launcher
            Recents short: default (not intercepted)
            Recents long: settings popup
            Recents double: notification hub
        """.trimIndent()
        val tv = TextView(this).apply {
            setTextColor(Color.GRAY)
            textSize = 11f
            setPadding(dp(16), dp(4), dp(16), dp(12))
            this.text = text
            typeface = font
        }
        container.addView(tv)
        addDivider()
    }

    // --- Wallpaper ---
    private fun pickWallpaper() {
        try {
            val intent = Intent(Intent.ACTION_SET_WALLPAPER)
            startActivity(Intent.createChooser(intent, "Choose Wallpaper"))
        } catch (_: Exception) {
            Toast.makeText(this, "No wallpaper picker found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickLiveWallpaper() {
        try {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        } catch (_: Exception) {
            Toast.makeText(this, "No live wallpaper picker found", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Icon pack ---
    private fun getIconPackName(): String {
        val pkg = prefs.iconPackPackage ?: return "Default (System)"
        return try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: Exception) { "Default (System)" }
    }

    private fun showIconPackPicker() {
        val packs = iconPackManager.getAvailableIconPacks()
        val names = mutableListOf("Default (System)")
        val packages = mutableListOf<String?>(null)
        for ((pkg, label) in packs) {
            names.add(label)
            packages.add(pkg)
        }

        AlertDialog.Builder(this)
            .setTitle("Select Icon Pack")
            .setItems(names.toTypedArray()) { _, which ->
                val selectedPkg = packages[which]
                prefs.iconPackPackage = selectedPkg
                if (selectedPkg != null) {
                    iconPackManager.loadIconPack(selectedPkg)
                } else {
                    iconPackManager.clearIconPack()
                }
                rebuildIconPackLabel()
            }
            .show()
    }

    private fun getPageIconPackName(pageId: String): String {
        val pkg = prefs.getPageIconPackPackage(pageId) ?: return "Default (Use Global)"
        return try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: Exception) { "Default (Use Global)" }
    }

    private fun showPageIconPackPicker(pageId: String, displayName: String) {
        val packs = iconPackManager.getAvailableIconPacks()
        val names = mutableListOf("Default (Use Global)")
        val packages = mutableListOf<String?>(null)
        for ((pkg, label) in packs) {
            names.add(label)
            packages.add(pkg)
        }

        AlertDialog.Builder(this)
            .setTitle("$displayName Icon Pack")
            .setItems(names.toTypedArray()) { _, which ->
                val selectedPkg = packages[which]
                prefs.setPageIconPackPackage(pageId, selectedPkg)
                rebuildIconPackLabel()
            }
            .show()
    }

    private fun getListViewAppliesLabel(): String {
        val pages = prefs.getListViewPages()
        if (pages.isEmpty() || pages.contains("__all__")) return "Everywhere"
        val pageOrder = prefs.getPageOrder()
        val names = pages.mapNotNull { pid ->
            if (pageOrder.contains(pid)) {
                when (pid) {
                    "frequent" -> "Frequent"; "favorites" -> "Favorites"; "all" -> "All"
                    else -> pid.removePrefix("custom_")
                }
            } else null
        }
        return if (names.isEmpty()) "Everywhere" else names.joinToString(", ")
    }

    private fun showListViewPagesPicker() {
        val pageOrder = prefs.getPageOrder()
        val currentPages = prefs.getListViewPages().toMutableSet()
        val isEverywhere = currentPages.isEmpty() || currentPages.contains("__all__")

        val options = mutableListOf("Everywhere")
        val pageIds = mutableListOf("__all__")
        for (pid in pageOrder) {
            pageIds.add(pid)
            options.add(when (pid) {
                "frequent" -> "Frequent"; "favorites" -> "Favorites"; "all" -> "All"
                else -> pid.removePrefix("custom_")
            })
        }

        val checked = BooleanArray(options.size) { i ->
            if (i == 0) isEverywhere
            else !isEverywhere && currentPages.contains(pageIds[i])
        }

        var dialog: AlertDialog? = null
        dialog = AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("List View Applies To")
            .setMultiChoiceItems(options.toTypedArray(), checked) { _, which, isChecked ->
                if (which == 0 && isChecked) {
                    for (j in 1 until checked.size) checked[j] = false
                    dialog?.listView?.let { lv ->
                        for (j in 1 until checked.size) lv.setItemChecked(j, false)
                    }
                } else if (which > 0 && isChecked) {
                    checked[0] = false
                    dialog?.listView?.setItemChecked(0, false)
                }
                checked[which] = isChecked
            }
            .setPositiveButton("OK") { _, _ ->
                val result = mutableSetOf<String>()
                if (checked[0]) {
                    result.add("__all__")
                } else {
                    for (i in 1 until checked.size) {
                        if (checked[i]) result.add(pageIds[i])
                    }
                }
                if (result.isEmpty()) result.add("__all__")
                prefs.setListViewPages(result)
                rebuildSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getSortAppliesLabel(): String {
        val pages = prefs.getSortApplyPages()
        if (pages.isEmpty() || pages.contains("__all__")) return "Everywhere"
        val pageOrder = prefs.getPageOrder()
        val names = pages.mapNotNull { pid ->
            if (pageOrder.contains(pid)) {
                when (pid) {
                    "frequent" -> "Frequent"; "favorites" -> "Favorites"; "all" -> "All"
                    else -> pid.removePrefix("custom_")
                }
            } else null
        }
        return if (names.isEmpty()) "Everywhere" else names.joinToString(", ")
    }

    private fun showSortPagesPicker() {
        val pageOrder = prefs.getPageOrder()
        val currentPages = prefs.getSortApplyPages().toMutableSet()
        val isEverywhere = currentPages.isEmpty() || currentPages.contains("__all__")

        val options = mutableListOf("Everywhere")
        val pageIds = mutableListOf("__all__")
        for (pid in pageOrder) {
            pageIds.add(pid)
            options.add(when (pid) {
                "frequent" -> "Frequent"; "favorites" -> "Favorites"; "all" -> "All"
                else -> pid.removePrefix("custom_")
            })
        }

        val checked = BooleanArray(options.size) { i ->
            if (i == 0) isEverywhere
            else !isEverywhere && currentPages.contains(pageIds[i])
        }

        var dialog: AlertDialog? = null
        dialog = AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("Sort Applies To")
            .setMultiChoiceItems(options.toTypedArray(), checked) { _, which, isChecked ->
                if (which == 0 && isChecked) {
                    for (j in 1 until checked.size) checked[j] = false
                    dialog?.listView?.let { lv ->
                        for (j in 1 until checked.size) lv.setItemChecked(j, false)
                    }
                } else if (which > 0 && isChecked) {
                    checked[0] = false
                    dialog?.listView?.setItemChecked(0, false)
                }
                checked[which] = isChecked
            }
            .setPositiveButton("OK") { _, _ ->
                val result = mutableSetOf<String>()
                if (checked[0]) {
                    result.add("__all__")
                } else {
                    for (i in 1 until checked.size) {
                        if (checked[i]) result.add(pageIds[i])
                    }
                }
                if (result.isEmpty()) result.add("__all__")
                prefs.setSortApplyPages(result)
                rebuildSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rebuildIconPackLabel() {
        container.removeAllViews()
        buildSettings()
    }

    private fun rebuildSettings() {
        container.removeAllViews()
        buildSettings()
    }

    // --- Helpers ---
    private fun makeLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.bb_text_primary))
        textSize = 14f
        typeface = font
    }

    private fun addDivider() {
        val v = View(this).apply {
            setBackgroundColor(getColor(R.color.bb_divider))
        }
        container.addView(v, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
