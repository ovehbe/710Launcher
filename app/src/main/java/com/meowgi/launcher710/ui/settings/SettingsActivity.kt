package com.meowgi.launcher710.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.meowgi.launcher710.R
import com.meowgi.launcher710.ai.AiSecrets
import com.meowgi.launcher710.util.IconPackManager
import com.meowgi.launcher710.util.LauncherPrefs

/**
 * Settings host: a home list of categories, each opening a sub-screen built by [buildScreen].
 * Screens live in SettingsScreens.kt, row widgets in SettingsRows.kt, dialogs in Settings*Dialogs.kt.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        /** Set to true to show Key Shortcuts section and key mapping UI again. */
        internal const val KEY_MAP_SETTINGS_VISIBLE = false
        private const val BB_BLUE = 0xFF0073BC.toInt()
        private const val STATE_SCREEN = "settings_screen"
        private const val STATE_HOME_SCROLL = "settings_home_scroll"

        /**
         * Settings runs in its own task (see its taskAffinity). Inside the launcher's home task it
         * never appears in Recents, and every Home press clears it off the singleTask launcher.
         */
        fun intent(context: Context): Intent =
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Opens (or brings back) Settings directly on [screen]. */
        internal fun intent(context: Context, screen: SettingsScreen): Intent =
            intent(context)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SCREEN, screen.name)

        private const val EXTRA_SCREEN = "com.meowgi.launcher710.extra.SETTINGS_SCREEN"

        private fun screenNamed(name: String?) = SettingsScreen.values().firstOrNull { it.name == name }
    }

    internal lateinit var prefs: LauncherPrefs
    internal lateinit var iconPackManager: IconPackManager
    internal val aiSecrets by lazy { AiSecrets(this) }
    internal lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var titleView: TextView
    internal val font: Typeface? by lazy { ResourcesCompat.getFont(this, R.font.bbalphas) }
    internal val dialogTheme = R.style.SettingsDialogTheme

    /** Launcher accent, or BB Blue when the accent is too dark to read on the settings background. */
    internal var accent: Int = BB_BLUE
        private set

    /** Card that rows are currently added to; null means straight into [container]. */
    internal var rowTarget: LinearLayout? = null

    private var screen = SettingsScreen.HOME
    private var homeScrollY = 0

    private val backToHome = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showScreen(SettingsScreen.HOME)
    }

    private var recordingKeyCallback: ((Int) -> Unit)? = null
    private var recordingDialog: AlertDialog? = null

    internal var pendingCommandIndexForShortcut: Int? = null
    internal var onCommandShortcutSet: (() -> Unit)? = null

    internal val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) runBackup(uri)
    }

    internal val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readBackup(uri)
    }

    internal val searchShortcutLauncher = shortcutLauncher("Search") { uri, name ->
        prefs.searchEngineShortcutIntentUri = uri
        prefs.searchEngineShortcutName = name
    }

    internal val launchInjectShortcutLauncher = shortcutLauncher("Search") { uri, name ->
        prefs.searchEngineLaunchInjectIntentUri = uri
        prefs.searchEngineLaunchInjectName = name
    }

    internal val tapActionShortcutLaunchers = TapAction.values().associateWith { slot ->
        shortcutLauncher("Shortcut") { uri, name -> prefs.writeTapAction(slot, 2, null, uri, name) }
    }

    internal val commandShortcutLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val idx = pendingCommandIndexForShortcut ?: return@registerForActivityResult
        pendingCommandIndexForShortcut = null
        val (intentUri, name) = result.shortcut("Shortcut") ?: run {
            onCommandShortcutSet = null
            return@registerForActivityResult
        }
        val commands = prefs.getSearchCommands().toMutableList()
        if (idx in commands.indices) {
            commands[idx] = commands[idx].copy(actionType = 1, actionPackage = null, intentUri = intentUri, actionName = name)
            prefs.setSearchCommands(commands)
        }
        onCommandShortcutSet?.invoke()
        onCommandShortcutSet = null
        rebuildSettings()
    }

    /** (intent URI, name) from an ACTION_CREATE_SHORTCUT result, or null if cancelled. */
    private fun ActivityResult.shortcut(defaultName: String): Pair<String, String>? {
        if (resultCode != RESULT_OK) return null
        val data = data ?: return null
        @Suppress("DEPRECATION")
        val shortcutIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent::class.java)
        } else {
            data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT)
        } ?: return null
        val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME) ?: defaultName
        return shortcutIntent.toUri(Intent.URI_INTENT_SCHEME) to name
    }

    private fun shortcutLauncher(defaultName: String, onShortcut: (intentUri: String, name: String) -> Unit) =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val (intentUri, name) = result.shortcut(defaultName) ?: return@registerForActivityResult
            onShortcut(intentUri, name)
            rebuildSettings()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Theme.BBOS7 sets windowTranslucentStatus for the home screen, which lays this window out
        // under the status bar; settings draws edge-to-edge and pads for the bars itself.
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)
        applySystemBarInsets(findViewById(R.id.settingsRoot), findViewById(R.id.settingsToolbar))

        prefs = LauncherPrefs(this)
        iconPackManager = IconPackManager(this)
        container = findViewById(R.id.settingsContainer)
        scroll = findViewById(R.id.settingsScroll)
        titleView = findViewById(R.id.settingsTitle)

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, backToHome)

        homeScrollY = savedInstanceState?.getInt(STATE_HOME_SCROLL) ?: 0
        val restored = screenNamed(savedInstanceState?.getString(STATE_SCREEN))
        showScreen(restored ?: screenNamed(intent.getStringExtra(EXTRA_SCREEN)) ?: SettingsScreen.HOME)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        screenNamed(intent.getStringExtra(EXTRA_SCREEN))?.let { showScreen(it) }
    }

    private fun applySystemBarInsets(root: View, toolbar: View) {
        val toolbarTop = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            toolbar.setPadding(toolbar.paddingLeft, toolbarTop + bars.top, toolbar.paddingRight, toolbar.paddingBottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SCREEN, screen.name)
        outState.putInt(STATE_HOME_SCROLL, if (screen == SettingsScreen.HOME) scroll.scrollY else homeScrollY)
    }

    internal fun showScreen(target: SettingsScreen) {
        if (screen == SettingsScreen.HOME && target != SettingsScreen.HOME) homeScrollY = scroll.scrollY
        screen = target
        titleView.text = if (target == SettingsScreen.HOME) getString(R.string.settings_title) else target.title
        backToHome.isEnabled = target != SettingsScreen.HOME
        render()
        val y = if (target == SettingsScreen.HOME) homeScrollY else 0
        scroll.post { scroll.scrollTo(0, y) }
    }

    /** Rebuilds the current screen in place, keeping the scroll position (values or visibility changed). */
    internal fun rebuildSettings() {
        val y = scroll.scrollY
        render()
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun render() {
        accent = prefs.accentColor.let { if (ColorUtils.calculateLuminance(it) < 0.05) BB_BLUE else it or Color.BLACK }
        container.removeAllViews()
        rowTarget = null
        buildScreen(screen)
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

    internal fun showRecordKeyDialog(role: String, onRecorded: (Int) -> Unit) {
        recordingKeyCallback = onRecorded
        val d = AlertDialog.Builder(this, dialogTheme)
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

    internal fun makeLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.bb_text_primary))
        textSize = 14f
        typeface = font
    }

    internal fun showResetToDefaultsDialog() {
        AlertDialog.Builder(this, dialogTheme)
            .setTitle("Reset to default style")
            .setMessage("This will reset all launcher settings to the factory defaults. Your apps and data are not affected.")
            .setPositiveButton("Reset") { _, _ ->
                com.meowgi.launcher710.util.DefaultProfile.reset(this)
                Toast.makeText(this, "Launcher reset to defaults. Restart to apply.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, com.meowgi.launcher710.RestartLauncherActivity::class.java))
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    internal fun copyVoiceDiagnostics() {
        val diagnostics = com.meowgi.launcher710.ui.search.getVoiceDiagnosticLog(this)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Voice diagnostics", diagnostics))
        Toast.makeText(this, "Voice diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
