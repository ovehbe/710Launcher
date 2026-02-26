package com.meowgi.launcher710

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.app.Activity
import android.app.NotificationManager
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.app.SearchManager
import android.content.*
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.GestureDetector
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.viewpager2.widget.ViewPager2
import com.meowgi.launcher710.model.AppInfo
import com.meowgi.launcher710.ui.appgrid.AppGridFragment
import com.meowgi.launcher710.ui.appgrid.AppPagerAdapter
import com.meowgi.launcher710.ui.dialogs.IconPickerDialog
import com.meowgi.launcher710.ui.dialogs.SoundProfileDialog
import com.meowgi.launcher710.ui.notifications.NotifListenerService
import com.meowgi.launcher710.ui.notifications.NotificationHub
import com.meowgi.launcher710.ui.search.SearchOverlay
import com.meowgi.launcher710.ui.dock.DockBar
import com.meowgi.launcher710.ui.header.HeaderView
import com.meowgi.launcher710.ui.settings.KeyCaptureAccessibilityService
import com.meowgi.launcher710.ui.settings.SettingsActivity
import com.meowgi.launcher710.ui.statusbar.BBStatusBar
import com.meowgi.launcher710.ui.widgets.WidgetHost
import com.meowgi.launcher710.ui.widgets.WidgetPickerDialog
import com.meowgi.launcher710.model.IntentShortcutInfo
import com.meowgi.launcher710.model.LaunchableItem
import com.meowgi.launcher710.model.ShortcutDisplayInfo
import com.meowgi.launcher710.util.AppRepository
import com.meowgi.launcher710.util.IconPackManager
import com.meowgi.launcher710.util.LauncherPrefs
import com.meowgi.launcher710.util.ShortcutHelper
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.*

class LauncherActivity : AppCompatActivity() {

    companion object {
        const val OPEN_HOME_MENU_EXTRA = "open_home_menu"
        private const val KEY_ROLE_HOME = 1
        private const val KEY_ROLE_BACK = 2
        private const val KEY_ROLE_RECENTS = 3
        private const val LONG_PRESS_MS = 500L
        private const val DOUBLE_PRESS_MS = 300L
    }

    private lateinit var prefs: LauncherPrefs
    private lateinit var repository: AppRepository
    private lateinit var iconPackManager: IconPackManager
    private lateinit var allPageIconPackManager: IconPackManager
    private lateinit var dockIconPackManager: IconPackManager
    private lateinit var pagerAdapter: AppPagerAdapter
    private lateinit var widgetHost: WidgetHost
    private lateinit var shortcutHelper: ShortcutHelper

    private lateinit var statusBar: BBStatusBar
    private lateinit var headerView: HeaderView
    private lateinit var appPager: ViewPager2
    private lateinit var searchOverlay: SearchOverlay
    private lateinit var notificationHub: NotificationHub
    private lateinit var dockBar: DockBar
    private lateinit var tabBarContainer: android.widget.LinearLayout
    private lateinit var actionBar: View
    private lateinit var rootFrame: View
    private lateinit var mainLayout: android.widget.LinearLayout

    private lateinit var notificationTickerBar: View
    private lateinit var notificationTickerText: TextView
    private lateinit var notificationCount: TextView
    private lateinit var searchInputInBar: EditText
    private lateinit var btnSoundProfile: ImageView

    private val tabViews = mutableListOf<TextView>()

    private var pendingWidgetId = -1

    private val keyHandler = Handler(Looper.getMainLooper())
    private var keyRolePressCount = 0
    private var keyRoleLongPressTriggered = false
    private var keyRoleLongPressRunnable: Runnable? = null
    private var keyRoleDoublePressRunnable: Runnable? = null

    private var searchApplyRunnable: Runnable? = null
    private val searchApplyDelayMs = 80L

    private var tickerTypingRunnable: Runnable? = null
    private var tickerDismissRunnable: Runnable? = null
    private val tickerTypingDelayMs = 45L
    private val tickerDisplayDurationMs = 5000L

    private var injectCaptureActive = false
    private val injectCaptureBuffer = StringBuilder()
    private var injectCaptureRunnable: Runnable? = null
    private var injectCaptureStartTime = 0L

    private val widgetBindLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && pendingWidgetId != -1) {
            val awm = AppWidgetManager.getInstance(this)
            val info = awm.getAppWidgetInfo(pendingWidgetId)
            if (info != null) {
                configureOrAddWidget(pendingWidgetId, info)
            }
        } else if (pendingWidgetId != -1) {
            widgetHost.deleteWidgetId(pendingWidgetId)
        }
        pendingWidgetId = -1
    }

    private val widgetConfigLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && pendingWidgetId != -1) {
            val awm = AppWidgetManager.getInstance(this)
            val info = awm.getAppWidgetInfo(pendingWidgetId)
            if (info != null) {
                addWidgetToCurrentPage(pendingWidgetId, info)
            }
        } else if (pendingWidgetId != -1) {
            widgetHost.deleteWidgetId(pendingWidgetId)
        }
        pendingWidgetId = -1
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) recreate()
    }

    private val createShortcutLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) return@registerForActivityResult
        val data = result.data!!
        val shortcutIntent = data.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT) ?: return@registerForActivityResult
        val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME) ?: "Shortcut"
        val pageId = if (::pagerAdapter.isInitialized) pagerAdapter.getPageId(appPager.currentItem) else "favorites"
        val intentUri = shortcutIntent.toUri(Intent.URI_INTENT_SCHEME)
        var iconPath: String? = null
        val iconBmp = data.getParcelableExtra<android.graphics.Bitmap>(Intent.EXTRA_SHORTCUT_ICON)
        if (iconBmp != null) {
            try {
                val f = java.io.File(cacheDir, "shortcut_icon_${System.currentTimeMillis()}.png")
                java.io.FileOutputStream(f).use { iconBmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                iconPath = f.absolutePath
            } catch (_: Exception) { }
        }
        prefs.addIntentShortcutToPage(pageId, name, intentUri, iconPath)
        pagerAdapter.refreshAll()
    }

    private var pendingDockSwipeSlotIndex = -1
    private val dockSwipeShortcutLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (pendingDockSwipeSlotIndex < 0) return@registerForActivityResult
        val slotIndex = pendingDockSwipeSlotIndex
        pendingDockSwipeSlotIndex = -1
        if (result.resultCode != Activity.RESULT_OK || result.data == null) return@registerForActivityResult
        val shortcutIntent = result.data!!.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)
        if (shortcutIntent != null) {
            prefs.setDockSwipeAction(slotIndex, shortcutIntent.toUri(Intent.URI_INTENT_SCHEME))
            dockBar.loadDock()
        }
    }

    private val wallpaperChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            refreshWallpaper()
        }
    }

    private val ringerModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            updateSoundProfileIcon()
        }
    }

    private fun updateSoundProfileIcon() {
        if (!::btnSoundProfile.isInitialized) return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        val isDnd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val filter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
            filter != NotificationManager.INTERRUPTION_FILTER_ALL
        } else false

        val iconRes = when {
            isDnd && am.ringerMode == AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_sound_alerts_off
            isDnd -> R.drawable.ic_sound_dnd
            am.ringerMode == AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_sound_silent
            am.ringerMode == AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_sound_vibrate
            else -> R.drawable.ic_sound_normal
        }
        btnSoundProfile.setImageResource(iconRes)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = LauncherPrefs(this)
        applySystemUI()

        setContentView(R.layout.activity_launcher)
        refreshWallpaper()

        iconPackManager = IconPackManager(this)
        val savedPack = prefs.iconPackPackage
        if (savedPack != null) iconPackManager.loadIconPack(savedPack)

        allPageIconPackManager = IconPackManager(this)
        val savedAllPagePack = prefs.allPageIconPackPackage
        if (savedAllPagePack != null) allPageIconPackManager.loadIconPack(savedAllPagePack)

        dockIconPackManager = IconPackManager(this)
        val savedDockPack = prefs.dockIconPackPackage
        if (savedDockPack != null) dockIconPackManager.loadIconPack(savedDockPack)

        repository = AppRepository(this)
        repository.iconPackManager = iconPackManager
        repository.allPageIconPackManager = allPageIconPackManager
        repository.dockIconPackManager = dockIconPackManager
        repository.register()

        loadPageIconPacks()

        widgetHost = WidgetHost(this)
        shortcutHelper = ShortcutHelper(this)

        rootFrame = findViewById(R.id.rootFrame)
        mainLayout = findViewById(R.id.mainLayout)
        statusBar = findViewById(R.id.statusBar)
        headerView = findViewById(R.id.headerView)
        appPager = findViewById(R.id.appPager)
        searchOverlay = findViewById(R.id.searchOverlay)
        notificationHub = findViewById(R.id.notificationHub)
        dockBar = findViewById(R.id.dockBar)
        tabBarContainer = findViewById(R.id.tabBar)
        actionBar = findViewById(R.id.actionBar)
        notificationTickerBar = findViewById(R.id.notificationTickerBar)
        notificationTickerText = findViewById(R.id.notificationTickerText)
        notificationCount = findViewById(R.id.notificationCount)
        searchInputInBar = findViewById(R.id.searchInputInBar)
        btnSoundProfile = findViewById(R.id.btnSoundProfile)

        val toggleHub: () -> Unit = {
            if (notificationHub.visibility == View.VISIBLE) {
                notificationHub.hide()
            } else {
                notificationHub.show(prefs.getNotificationAppWhitelist())
            }
        }
        findViewById<View>(R.id.actionBarCenter).setOnClickListener { toggleHub() }
        notificationTickerBar.setOnClickListener { toggleHub() }

        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { view, insets ->
            val systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val topPad = if (prefs.systemStatusBarVisible) systemBarInsets.top else 0
            val bottomPad = if (prefs.navigationBarVisible) navBarInsets.bottom else 0
            view.setPadding(view.paddingLeft, topPad, view.paddingRight, bottomPad)
            insets
        }

        searchOverlay.repository = repository
        searchOverlay.iconResolver = { item ->
            when (item) {
                is LaunchableItem.App -> repository.getIconForPage(item.app, "search")
                else -> item.icon
            }
        }
        searchOverlay.onDismiss = {
            searchApplyRunnable?.let { keyHandler.removeCallbacks(it) }
            searchApplyRunnable = null
            searchInputInBar.visibility = View.GONE
            searchInputInBar.setText("")
            refreshNotificationTicker()
            appPager.visibility = View.VISIBLE
        }
        searchOverlay.onLaunchItem = { item ->
            when (item) {
                is LaunchableItem.App -> repository.launchApp(item.app)
                is LaunchableItem.Shortcut -> shortcutHelper.launchShortcut(item.shortcut.packageName, item.shortcut.shortcutId)
                is LaunchableItem.IntentShortcut -> shortcutHelper.launchIntentShortcut(item.info.intentUri)
                else -> {}
            }
        }
        searchOverlay.onItemLongClick = { item, view ->
            when (item) {
                is LaunchableItem.App -> showAppContextMenu(item.app, view)
                is LaunchableItem.Shortcut -> showShortcutContextMenu(item.shortcut, view)
                is LaunchableItem.IntentShortcut -> showIntentShortcutContextMenu(item.info, view)
                else -> {}
            }
        }

        dockBar.repository = repository
        dockBar.launcherPrefs = prefs
        dockBar.shortcutHelper = shortcutHelper
        dockBar.onAppLaunch = { app -> repository.launchApp(app) }
        dockBar.onIntentShortcutLaunch = { shortcutHelper.launchIntentShortcut(it.intentUri) }
        dockBar.onDockIconLongClick = { app, slotIndex -> showDockIconContextMenu(app, slotIndex) }
        dockBar.onIntentShortcutLongClick = { info, slotIndex -> showDockIntentShortcutContextMenu(info, slotIndex) }
        dockBar.onDockSwipeUp = { slotIndex -> launchDockSwipeAction(slotIndex) }
        dockBar.dockIconResolver = { app -> repository.getIconForDock(app) }

        repository.onAppsChanged = {
            pagerAdapter.refreshAll()
            dockBar.loadDock()
        }

        setupActionBar()
        setupNotifications()
        if (intent?.getBooleanExtra(OPEN_HOME_MENU_EXTRA, false) == true) {
            tabBarContainer.post { showHomeContextMenu(tabBarContainer) }
            intent?.removeExtra(OPEN_HOME_MENU_EXTRA)
        }
        applyOpacitySettings()
        applySystemUI()
        updateSoundProfileIcon()
        androidx.core.content.ContextCompat.registerReceiver(
            this, wallpaperChangedReceiver,
            IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        androidx.core.content.ContextCompat.registerReceiver(
            this, ringerModeReceiver,
            IntentFilter("android.media.RINGER_MODE_CHANGED"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.IO) { repository.loadApps() }
            setupPager()
            dockBar.loadDock()
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemUI() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (prefs.systemStatusBarVisible) {
            controller.show(WindowInsetsCompat.Type.statusBars())
            window.statusBarColor = Color.argb(prefs.systemStatusBarAlpha, 0, 0, 0)
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
        if (prefs.navigationBarVisible) {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = false
        // Re-request insets so the padding listener re-evaluates
        if (::mainLayout.isInitialized) {
            ViewCompat.requestApplyInsets(mainLayout)
        }
    }

    private fun loadPageIconPacks() {
        repository.pageIconPackManagers.clear()
        for (pageId in prefs.getPageOrder()) {
            val pkg = prefs.getPageIconPackPackage(pageId) ?: continue
            val mgr = IconPackManager(this)
            if (mgr.loadIconPack(pkg)) {
                repository.pageIconPackManagers[pageId] = mgr
            }
        }
        // Also load dock and search page packs if set via per-page system
        val dockPkg = prefs.getPageIconPackPackage("dock")
        if (dockPkg != null) {
            val mgr = IconPackManager(this)
            if (mgr.loadIconPack(dockPkg)) {
                repository.pageIconPackManagers["dock"] = mgr
            }
        }
        val searchPkg = prefs.getPageIconPackPackage("search")
        if (searchPkg != null) {
            val mgr = IconPackManager(this)
            if (mgr.loadIconPack(searchPkg)) {
                repository.pageIconPackManagers["search"] = mgr
            }
        }
    }

    private fun refreshWallpaper() {
        // Wallpaper is shown via theme's windowShowWallpaper (avoids READ_EXTERNAL_STORAGE).
        // On wallpaper change, invalidate so the window redraws.
        window.decorView.postInvalidate()
    }

    fun applyOpacitySettings() {
        rootFrame.setBackgroundColor(Color.argb(prefs.mainBackgroundAlpha, 0, 0, 0))
        statusBar.visibility = if (prefs.statusBarVisible) View.VISIBLE else View.GONE
        statusBar.applyOpacity()
        headerView.applyOpacity()
        actionBar.setBackgroundColor(Color.argb(prefs.actionBarAlpha, 0, 0, 0))
        tabBarContainer.background?.alpha = prefs.tabBarAlpha
        dockBar.applyOpacity()
        notificationHub.applyOpacity(prefs.notificationHubAlpha)
        searchOverlay.applyOpacity(prefs.searchOverlayAlpha)
    }

    private fun setupPager() {
        pagerAdapter = AppPagerAdapter(
            this, repository, shortcutHelper, widgetHost,
            onItemLongClick = { item, view ->
                when (item) {
                    is LaunchableItem.App -> showAppContextMenu(item.app, view)
                    is LaunchableItem.Shortcut -> showShortcutContextMenu(item.shortcut, view)
                    is LaunchableItem.IntentShortcut -> showIntentShortcutContextMenu(item.info, view)
                }
            },
            onEmptySpaceLongClick = { showHomeContextMenu(tabBarContainer) }
        )
        appPager.adapter = pagerAdapter
        appPager.isUserInputEnabled = false
        buildTabBar()
        setupBottomSwipe()
        val startTab = prefs.defaultTab.coerceIn(0, pagerAdapter.itemCount - 1)
        appPager.setCurrentItem(startTab, false)
        updateTabHighlight(startTab)

        appPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTabHighlight(position)
            }
        })
    }

    private fun buildTabBar() {
        tabBarContainer.removeAllViews()
        tabViews.clear()
        val font = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.bbalphas)
        for (i in 0 until pagerAdapter.itemCount) {
            val tv = TextView(this).apply {
                text = pagerAdapter.getPageName(i)
                setTextColor(getColor(R.color.bb_text_secondary))
                textSize = 13f
                typeface = font
                gravity = android.view.Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { appPager.setCurrentItem(i, true) }
            }
            val lp = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            tabBarContainer.addView(tv, lp)
            tabViews.add(tv)
        }
    }

    private fun updateTabHighlight(selected: Int) {
        val accent = prefs.accentColor
        val lighterAccent = Color.argb(
            Color.alpha(accent),
            kotlin.math.min(255, (Color.red(accent) * 1.15).toInt()),
            kotlin.math.min(255, (Color.green(accent) * 1.15).toInt()),
            kotlin.math.min(255, (Color.blue(accent) * 1.15).toInt())
        )
        val darkerAccent = Color.argb(
            Color.alpha(accent),
            (Color.red(accent) * 0.78).toInt(),
            (Color.green(accent) * 0.78).toInt(),
            (Color.blue(accent) * 0.78).toInt()
        )
        for ((i, tab) in tabViews.withIndex()) {
            if (i == selected) {
                val gd = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(lighterAccent, darkerAccent)
                )
                tab.background = gd
                tab.setTextColor(getColor(R.color.bb_text_primary))
            } else {
                tab.setBackgroundColor(0)
                tab.setTextColor(getColor(R.color.bb_text_secondary))
            }
        }
    }

    private var bottomSwipeStartX = 0f
    private var bottomSwipeStartY = 0f
    private var bottomSwipeTracking = false

    private fun isInBottomBar(y: Float): Boolean {
        val loc = IntArray(2)
        tabBarContainer.getLocationOnScreen(loc)
        return y >= loc[1]
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                bottomSwipeTracking = (prefs.swipeMode == 1) || isInBottomBar(ev.rawY)
                bottomSwipeStartX = ev.rawX
                bottomSwipeStartY = ev.rawY
            }
            MotionEvent.ACTION_UP -> {
                if (bottomSwipeTracking) {
                    val dx = ev.rawX - bottomSwipeStartX
                    val dy = ev.rawY - bottomSwipeStartY
                    if (kotlin.math.abs(dx) > dp(70) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 2f) {
                        if (dx < 0 && appPager.currentItem < pagerAdapter.itemCount - 1) {
                            appPager.setCurrentItem(appPager.currentItem + 1, true)
                            bottomSwipeTracking = false
                            return true
                        } else if (dx > 0 && appPager.currentItem > 0) {
                            appPager.setCurrentItem(appPager.currentItem - 1, true)
                            bottomSwipeTracking = false
                            return true
                        }
                    }
                }
                bottomSwipeTracking = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setupBottomSwipe() { /* handled in dispatchTouchEvent */ }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun launchSearchAppWithQuery(pkg: String, query: String) {
        try {
            val i = Intent(Intent.ACTION_MAIN).setPackage(pkg)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            i.putExtra(SearchManager.QUERY, query)
            i.putExtra("query", query)
            startActivity(i)
        } catch (_: Exception) {}
    }

    /** Injects a key event via root shell "input keyevent". Requires root. Runs on background thread. */
    private fun injectKeyViaRoot(keyCode: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent $keyCode")).waitFor()
            } catch (_: Exception) { }
        }
    }

    /** Injects text via root shell "input text". Requires root. Escapes space as %s and shell single-quotes. */
    private fun injectTextViaRoot(text: String) {
        if (text.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val forInput = text.replace(" ", "%s").replace("'", "'\\''")
                Runtime.getRuntime().exec(arrayOf("su", "-c", "input text '$forInput'")).waitFor()
            } catch (_: Exception) { }
        }
    }

    private fun flushInjectCapture() {
        injectCaptureRunnable?.let { keyHandler.removeCallbacks(it) }
        injectCaptureRunnable = null
        injectCaptureActive = false
        val s = injectCaptureBuffer.toString()
        injectCaptureBuffer.setLength(0)
        if (s.isNotEmpty()) injectTextViaRoot(s)
    }

    private fun showSearchPageAware(initialChar: Char? = null) {
        searchApplyRunnable?.let { keyHandler.removeCallbacks(it) }
        searchApplyRunnable = null

        val pageId = if (::pagerAdapter.isInitialized) pagerAdapter.getPageId(appPager.currentItem) else null
        searchOverlay.setSearchContextLabel(
            when (pageId) {
                "frequent" -> "Frequents"
                "all" -> "All Apps"
                else -> "Extended"
            }
        )
        if (!::pagerAdapter.isInitialized) {
            searchOverlay.show()
        } else {
            if (pageId == "all" || pageId == "frequent") {
                val frag = pagerAdapter.getFragment(appPager.currentItem)
                val items = frag?.getCurrentItems() ?: emptyList()
                if (items.isNotEmpty()) searchOverlay.showFilter(items) else searchOverlay.show()
            } else {
                searchOverlay.show()
            }
        }
        notificationTickerBar.visibility = View.GONE
        searchInputInBar.visibility = View.VISIBLE
        if (initialChar != null) {
            searchInputInBar.setSelection(0)
            searchInputInBar.setText(initialChar.toString())
            searchInputInBar.setSelection(searchInputInBar.text.length)
        } else {
            searchInputInBar.setText("")
        }
        searchInputInBar.requestFocus()
        searchInputInBar.post {
            if (searchOverlay.visibility == View.VISIBLE) {
                searchOverlay.applyQuery(searchInputInBar.text.toString())
            }
        }
    }

    private fun setupActionBar() {
        findViewById<View>(R.id.btnSoundProfile).setOnClickListener {
            val dlg = SoundProfileDialog(this)
            dlg.onDismissed = { updateSoundProfileIcon() }
            dlg.show()
        }
        findViewById<View>(R.id.btnSearch).setOnClickListener {
            showSearchPageAware()
        }

        searchInputInBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                searchApplyRunnable?.let { keyHandler.removeCallbacks(it) }
                searchApplyRunnable = Runnable {
                    searchApplyRunnable = null
                    if (searchOverlay.visibility == View.VISIBLE) {
                        searchOverlay.applyQuery(text)
                    }
                }
                keyHandler.postDelayed(searchApplyRunnable!!, searchApplyDelayMs)
            }
        })
        searchInputInBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchOverlay.performExtendedSearch()
                true
            } else false
        }

        val contentArea = findViewById<View>(R.id.contentArea)
        contentArea.setOnLongClickListener {
            showHomeContextMenu(tabBarContainer)
            true
        }
        val doubleTapGesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                when (prefs.doubleTapAction) {
                    1 -> lockScreen()
                    2 -> {
                        if (notificationHub.visibility == View.VISIBLE) notificationHub.hide()
                        else notificationHub.show(prefs.getNotificationAppWhitelist())
                    }
                    else -> { /* 0 = None */ }
                }
                return true
            }
        })
        contentArea.setOnTouchListener { _, ev -> doubleTapGesture.onTouchEvent(ev) }
    }

    private fun showHomeContextMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val pageId = if (::pagerAdapter.isInitialized) pagerAdapter.getPageId(appPager.currentItem) else "favorites"
        val supportsWidgets = pageId == "favorites" || pageId.startsWith("custom_")
        popup.menu.add(getString(R.string.add_widget))
        if (supportsWidgets) {
            val below = prefs.isPageWidgetsBelowApps(pageId)
            popup.menu.add(if (below) "Widget position: Below apps" else "Widget position: Above apps")
        }
        val canAddShortcut = pageId == "favorites" || pageId.startsWith("custom_")
        if (canAddShortcut) {
            popup.menu.add(getString(R.string.add_app_shortcut))
            popup.menu.add(getString(R.string.add_shortcut_all))
        }
        popup.menu.add("Choose Wallpaper")
        popup.menu.add(getString(R.string.settings_title))
        popup.setOnMenuItemClickListener { item ->
            val title = item.title?.toString() ?: return@setOnMenuItemClickListener false
            when {
                title == getString(R.string.add_widget) -> {
                    showWidgetPicker()
                    true
                }
                title.startsWith("Widget position: ") -> {
                    val pageIdForWidget = if (::pagerAdapter.isInitialized) pagerAdapter.getPageId(appPager.currentItem) else "favorites"
                    val currentlyBelow = prefs.isPageWidgetsBelowApps(pageIdForWidget)
                    prefs.setPageWidgetsBelowApps(pageIdForWidget, !currentlyBelow)
                    pagerAdapter.refreshAll()
                    true
                }
                title == getString(R.string.add_app_shortcut) -> {
                    showAppShortcutPicker()
                    true
                }
                title == getString(R.string.add_shortcut_all) -> {
                    try {
                        createShortcutLauncher.launch(Intent(Intent.ACTION_CREATE_SHORTCUT))
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(this, "No shortcut handler found", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                title == "Choose Wallpaper" -> {
                    try {
                        startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SET_WALLPAPER), "Choose Wallpaper"))
                    } catch (_: Exception) {}
                    true
                }
                title == getString(R.string.settings_title) -> {
                    settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showAppShortcutPicker() {
        val pageId = if (::pagerAdapter.isInitialized) pagerAdapter.getPageId(appPager.currentItem) else "favorites"
        // Apps that have at least one shortcut (manifest/dynamic/pinned)
        val appsWithShortcuts = repository.apps
            .filter { shortcutHelper.getShortcutsForPackage(it.packageName).isNotEmpty() }
            .sortedBy { it.label.lowercase() }
        if (appsWithShortcuts.isEmpty()) {
            android.widget.Toast.makeText(this, "No app shortcuts available", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val appNames = appsWithShortcuts.map { it.label }.toTypedArray()
        AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle(getString(R.string.add_app_shortcut))
            .setItems(appNames) { _, which ->
                val app = appsWithShortcuts[which]
                val shortcuts = shortcutHelper.getShortcutsForPackage(app.packageName)
                if (shortcuts.isEmpty()) return@setItems
                val shortcutLabels = shortcuts.map { it.shortLabel?.toString() ?: it.longLabel?.toString() ?: it.id }.toTypedArray()
                AlertDialog.Builder(this, R.style.BBDialogTheme)
                    .setTitle(app.label)
                    .setItems(shortcutLabels) { _, idx ->
                        val info = shortcuts[idx]
                        prefs.addShortcutToPage(pageId, app.packageName, info.id)
                        pagerAdapter.refreshAll()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showShortcutContextMenu(shortcut: ShortcutDisplayInfo, anchor: View) {
        val pageId = if (::pagerAdapter.isInitialized) pagerAdapter.getPageId(appPager.currentItem) else "favorites"
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Remove from page")
        popup.menu.add("Change Name")
        popup.menu.add("Change Icon")
        popup.menu.add("Uninstall")
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Remove from page" -> {
                    prefs.removeShortcutFromPage(pageId, shortcut.packageName, shortcut.shortcutId)
                    pagerAdapter.refreshAll()
                    true
                }
                "Change Name" -> {
                    AlertDialog.Builder(this, R.style.BBDialogTheme)
                        .setTitle("Change name for...")
                        .setItems(arrayOf("This page only", "Everywhere (global)")) { _, which ->
                            val pageIdForName = if (which == 0) pageId else null
                            val current = prefs.getCustomLabel(shortcut.shortcutKey, pageIdForName) ?: shortcut.label
                            val input = android.widget.EditText(this).apply {
                                setText(current.toString())
                                setPadding(dp(24), dp(16), dp(24), dp(16))
                                hint = "Name"
                            }
                            AlertDialog.Builder(this, R.style.BBDialogTheme)
                                .setTitle("Name")
                                .setView(input)
                                .setPositiveButton("OK") { _, _ ->
                                    val name = input.text.toString().trim()
                                    prefs.setCustomLabel(shortcut.shortcutKey, if (name.isEmpty()) null else name, pageIdForName)
                                    pagerAdapter.refreshAll()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
                "Change Icon" -> {
                    AlertDialog.Builder(this, R.style.BBDialogTheme)
                        .setTitle("Change icon")
                        .setItems(arrayOf("This page only", "Everywhere (global)")) { _, which ->
                            val isPageOnly = which == 0
                            val pageIdForIcon = if (isPageOnly) pageId else null
                            val pickerDialog = IconPickerDialog(this,
                                onIconSelected = { drawableName, _ ->
                                    prefs.setCustomIcon(shortcut.shortcutKey, drawableName, pageIdForIcon)
                                    pagerAdapter.refreshAll()
                                },
                                onReset = {
                                    prefs.setCustomIcon(shortcut.shortcutKey, null, pageIdForIcon)
                                    pagerAdapter.refreshAll()
                                }
                            )
                            pickerDialog.showPackPicker()
                        }
                        .setNegativeButton("Cancel", null)
                                .show()
                    true
                }
                "Uninstall" -> {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${shortcut.packageName}")))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showIntentShortcutContextMenu(info: IntentShortcutInfo, anchor: View) {
        val pageId = if (::pagerAdapter.isInitialized) pagerAdapter.getPageId(appPager.currentItem) else "favorites"
        val inDock = dockBar.getDockIntentShortcutsList().any { it.intentUri == info.intentUri }
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Remove from page")
        if (inDock) popup.menu.add(getString(R.string.unpin_from_dock))
        else popup.menu.add(getString(R.string.pin_to_dock))
        popup.menu.add("Change Name")
        popup.menu.add("Change Icon")
        popup.menu.add("Uninstall")
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Remove from page" -> {
                    prefs.removeIntentShortcutFromPage(pageId, info.intentUri)
                    pagerAdapter.refreshAll()
                    true
                }
                getString(R.string.pin_to_dock) -> {
                    dockBar.addIntentShortcutToDock(info)
                    true
                }
                getString(R.string.unpin_from_dock) -> {
                    dockBar.removeIntentShortcutFromDock(info)
                    true
                }
                "Change Name" -> {
                    AlertDialog.Builder(this, R.style.BBDialogTheme)
                        .setTitle("Change name for...")
                        .setItems(arrayOf("This page only", "Everywhere (global)")) { _, which ->
                            val pageIdForName = if (which == 0) pageId else null
                            val current = prefs.getCustomLabel(info.shortcutKey, pageIdForName) ?: info.label
                            val input = android.widget.EditText(this).apply {
                                setText(current.toString())
                                setPadding(dp(24), dp(16), dp(24), dp(16))
                                hint = "Name"
                            }
                            AlertDialog.Builder(this, R.style.BBDialogTheme)
                                .setTitle("Name")
                                .setView(input)
                                .setPositiveButton("OK") { _, _ ->
                                    val name = input.text.toString().trim()
                                    prefs.setCustomLabel(info.shortcutKey, if (name.isEmpty()) null else name, pageIdForName)
                                    pagerAdapter.refreshAll()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
                "Change Icon" -> {
                    AlertDialog.Builder(this, R.style.BBDialogTheme)
                        .setTitle("Change icon")
                        .setItems(arrayOf("This page only", "Everywhere (global)")) { _, which ->
                            val isPageOnly = which == 0
                            val pageIdForIcon = if (isPageOnly) pageId else null
                            val pickerDialog = IconPickerDialog(this,
                                onIconSelected = { drawableName, _ ->
                                    prefs.setCustomIcon(info.shortcutKey, drawableName, pageIdForIcon)
                                    pagerAdapter.refreshAll()
                                },
                                onReset = {
                                    prefs.setCustomIcon(info.shortcutKey, null, pageIdForIcon)
                                    pagerAdapter.refreshAll()
                                }
                            )
                            pickerDialog.showPackPicker()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                    true
                }
                "Uninstall" -> {
                    try {
                        val intent = Intent.parseUri(info.intentUri, 0)
                        val pkg = intent.`package` ?: intent.component?.packageName
                        if (pkg != null) {
                            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                        }
                    } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showDockIntentShortcutContextMenu(info: IntentShortcutInfo, slotIndex: Int) {
        val popup = PopupMenu(this, dockBar)
        popup.menu.add(getString(R.string.unpin_from_dock))
        popup.menu.add("Change Name")
        popup.menu.add("Change Icon")
        popup.menu.add("Set swipe-up action")
        if (prefs.getDockSwipeAction(slotIndex) != null) popup.menu.add("Clear swipe-up action")
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                getString(R.string.unpin_from_dock) -> {
                    dockBar.removeIntentShortcutFromDock(info)
                    true
                }
                "Set swipe-up action" -> {
                    showDockSwipeActionPicker(slotIndex)
                    true
                }
                "Clear swipe-up action" -> {
                    prefs.setDockSwipeAction(slotIndex, null)
                    dockBar.loadDock()
                    true
                }
                "Change Name" -> {
                    AlertDialog.Builder(this, R.style.BBDialogTheme)
                        .setTitle("Change name for...")
                        .setItems(arrayOf("Dock only", "Everywhere (global)")) { _, which ->
                            val pageIdForName = if (which == 0) "dock" else null
                            val current = prefs.getCustomLabel(info.shortcutKey, pageIdForName) ?: info.label
                            val input = android.widget.EditText(this).apply {
                                setText(current.toString())
                                setPadding(dp(24), dp(16), dp(24), dp(16))
                                hint = "Name"
                            }
                            AlertDialog.Builder(this, R.style.BBDialogTheme)
                                .setTitle("Name")
                                .setView(input)
                                .setPositiveButton("OK") { _, _ ->
                                    val name = input.text.toString().trim()
                                    prefs.setCustomLabel(info.shortcutKey, if (name.isEmpty()) null else name, pageIdForName)
                                    dockBar.loadDock()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
                "Change Icon" -> {
                    AlertDialog.Builder(this, R.style.BBDialogTheme)
                        .setTitle("Change icon")
                        .setItems(arrayOf("Dock only", "Everywhere (global)")) { _, which ->
                            val pageId = if (which == 0) "dock" else null
                            val pickerDialog = IconPickerDialog(this,
                                onIconSelected = { drawableName, _ ->
                                    prefs.setCustomIcon(info.shortcutKey, drawableName, pageId)
                                    dockBar.loadDock()
                                },
                                onReset = {
                                    prefs.setCustomIcon(info.shortcutKey, null, pageId)
                                    dockBar.loadDock()
                                }
                            )
                            pickerDialog.showPackPicker()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showWidgetPicker() {
        WidgetPickerDialog(this) { info ->
            val widgetId = widgetHost.allocateId()
            val awm = AppWidgetManager.getInstance(this)

            if (!awm.bindAppWidgetIdIfAllowed(widgetId, info.provider)) {
                pendingWidgetId = widgetId
                val bindIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                }
                widgetBindLauncher.launch(bindIntent)
            } else {
                configureOrAddWidget(widgetId, info)
            }
        }.show()
    }

    private fun configureOrAddWidget(widgetId: Int, info: android.appwidget.AppWidgetProviderInfo) {
        if (info.configure != null) {
            pendingWidgetId = widgetId
            val configIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = info.configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            widgetConfigLauncher.launch(configIntent)
        } else {
            addWidgetToCurrentPage(widgetId, info)
        }
    }

    private fun addWidgetToCurrentPage(widgetId: Int, info: android.appwidget.AppWidgetProviderInfo) {
        val frag = pagerAdapter.getFragment(appPager.currentItem)
        frag?.addWidgetToPage(widgetId, info)
    }

    private fun setupNotifications() {
        NotifListenerService.onNotificationsChanged = {
            runOnUiThread {
                notificationHub.refresh(prefs.getNotificationAppWhitelist())
                refreshNotificationTicker()
            }
        }
        refreshNotificationTicker()
    }

    /** Build user-friendly ticker text: "Message from X" for messaging apps, etc. */
    private fun getTickerDisplayText(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()?.trim() ?: ""
        val text = extras.getCharSequence("android.text")?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence("android.subText")?.toString()?.trim() ?: ""
        val pkg = sbn.packageName.lowercase()

        val isMessaging = pkg.contains("whatsapp") || pkg.contains("messenger") || pkg.contains("telegram") ||
            pkg.contains("signal") || pkg.contains("viber") || pkg.contains("discord") || pkg.contains("skype") ||
            pkg == "com.google.android.apps.messaging" || pkg == "com.android.mms" || pkg.contains("sms") ||
            pkg.contains("thoughtcrime.securesms") || pkg.contains("facebook.orca")

        return when {
            isMessaging && title.isNotEmpty() -> "Message from $title"
            isMessaging && title.isEmpty() && text.isNotEmpty() -> "New message"
            isMessaging && title.isEmpty() -> "New message"
            title.isNotEmpty() && text.isNotEmpty() && !isMessaging -> "$title: ${text.take(40)}${if (text.length > 40) "…" else ""}"
            subText.isNotEmpty() && title.isEmpty() -> subText
            title.isNotEmpty() -> title
            text.isNotEmpty() -> text
            else -> "Notification"
        }
    }

    private fun refreshNotificationTicker() {
        tickerTypingRunnable?.let { keyHandler.removeCallbacks(it) }
        tickerDismissRunnable?.let { keyHandler.removeCallbacks(it) }
        tickerTypingRunnable = null
        tickerDismissRunnable = null

        val service = NotifListenerService.instance
        var notifs = service?.getNotifications()?.filter {
            it.packageName != "android" &&
            it.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT == 0 &&
            it.notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE == 0 &&
            (it.notification.extras.getCharSequence("android.title") != null ||
             it.notification.extras.getCharSequence("android.text") != null)
        } ?: emptyList()
        val whitelist = prefs.getNotificationAppWhitelist()
        if (whitelist.isNotEmpty()) notifs = notifs.filter { it.packageName in whitelist }
        if (notifs.isEmpty()) {
            notificationTickerBar.visibility = View.GONE
            return
        }

        notificationTickerBar.visibility = View.VISIBLE
        notificationCount.text = notifs.size.toString()
        val first = notifs.firstOrNull() ?: return
        val fullText = getTickerDisplayText(first)
        notificationTickerText.text = ""
        notificationTickerText.visibility = View.VISIBLE
        notificationCount.visibility = View.GONE

        if (fullText.isEmpty()) {
            notificationTickerText.visibility = View.GONE
            notificationCount.visibility = View.VISIBLE
            return
        }

        val L = fullText.length
        val midLeft = (L - 1) / 2
        val midRight = L / 2
        val maxSpread = minOf(midLeft, L - 1 - midRight).coerceAtLeast(0)
        var spread = 0

        tickerTypingRunnable = object : Runnable {
            override fun run() {
                if (spread <= maxSpread) {
                    val left = (midLeft - spread).coerceAtLeast(0)
                    val right = (midRight + spread).coerceAtMost(L - 1)
                    notificationTickerText.text = fullText.substring(left, right + 1)
                    spread++
                    tickerTypingRunnable = this
                    keyHandler.postDelayed(this, tickerTypingDelayMs)
                } else {
                    tickerTypingRunnable = null
                    tickerDismissRunnable = Runnable {
                        notificationTickerText.visibility = View.GONE
                        notificationTickerText.text = ""
                        tickerDismissRunnable = null
                        notificationCount.visibility = View.VISIBLE
                    }
                    keyHandler.postDelayed(tickerDismissRunnable!!, tickerDisplayDurationMs)
                }
            }
        }
        keyHandler.postDelayed(tickerTypingRunnable!!, tickerTypingDelayMs)
    }

    private fun getPageDisplayName(pageId: String): String {
        return when (pageId) {
            "frequent" -> "Frequent"; "favorites" -> "Favorites"; "all" -> "All"
            else -> pageId.removePrefix("custom_")
        }
    }

    private fun showAppContextMenu(app: AppInfo, anchor: View) {
        val popup = PopupMenu(this, anchor)
        val cn = app.componentName.flattenToString()
        val inDock = dockBar.getDockAppsList().any { it.packageName == app.packageName && it.activityName == app.activityName }

        // Build add/remove entries for Favorites and all custom pages
        val pageOrder = prefs.getPageOrder()
        val assignablePages = pageOrder.filter { it != "frequent" && it != "all" }

        popup.menu.apply {
            // Favorites toggle
            if (app.isFavorite) {
                add("Remove from Favorites")
            } else {
                add("Add to Favorites")
            }
            // Custom page toggles
            for (pid in assignablePages) {
                if (pid == "favorites") continue // already handled above
                val name = getPageDisplayName(pid)
                val isOnPage = prefs.isAppOnPage(cn, pid)
                if (isOnPage) {
                    add("Remove from $name")
                } else {
                    add("Add to $name")
                }
            }
            if (inDock) {
                add(getString(R.string.unpin_from_dock))
            } else {
                add(getString(R.string.pin_to_dock))
            }
            add("Change Name")
            add("Change Icon")
            add(getString(R.string.app_info))
            add("Uninstall")
        }
        popup.setOnMenuItemClickListener { item ->
            val title = item.title?.toString() ?: return@setOnMenuItemClickListener false
            when {
                title == "Add to Favorites" || title == "Remove from Favorites" -> {
                    CoroutineScope(Dispatchers.IO).launch {
                        repository.toggleFavorite(app)
                        withContext(Dispatchers.Main) { pagerAdapter.refreshAll() }
                    }
                    true
                }
                title.startsWith("Add to ") || title.startsWith("Remove from ") -> {
                    // Find which custom page this refers to
                    val pageName = if (title.startsWith("Add to ")) title.removePrefix("Add to ")
                                   else title.removePrefix("Remove from ")
                    val pid = assignablePages.find { getPageDisplayName(it) == pageName }
                    if (pid != null) {
                        prefs.toggleAppOnPage(cn, pid)
                        pagerAdapter.refreshAll()
                    }
                    true
                }
                title == getString(R.string.pin_to_dock) -> {
                    dockBar.addToDock(app)
                    true
                }
                title == getString(R.string.unpin_from_dock) -> {
                    dockBar.removeFromDock(app)
                    true
                }
                title == "Change Name" -> {
                    val currentPageId = if (::pagerAdapter.isInitialized) pagerAdapter.getPageId(appPager.currentItem) else "favorites"
                    AlertDialog.Builder(this, R.style.BBDialogTheme)
                        .setTitle("Change name for...")
                        .setItems(arrayOf("This page only", "Everywhere (global)")) { _, which ->
                            val pageId = if (which == 0) currentPageId else null
                            val current = prefs.getCustomLabel(cn, pageId) ?: app.label
                            val input = android.widget.EditText(this).apply {
                                setText(current.toString())
                                setPadding(dp(24), dp(16), dp(24), dp(16))
                                hint = "Name"
                            }
                            AlertDialog.Builder(this, R.style.BBDialogTheme)
                                .setTitle("Name")
                                .setView(input)
                                .setPositiveButton("OK") { _, _ ->
                                    val name = input.text.toString().trim()
                                    prefs.setCustomLabel(cn, if (name.isEmpty()) null else name, pageId)
                                    reloadAppsAndRefresh()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
                title == "Change Icon" -> {
                    val currentPageId = if (::pagerAdapter.isInitialized) {
                        pagerAdapter.getPageId(appPager.currentItem)
                    } else {
                        "favorites"
                    }
                    AlertDialog.Builder(this, R.style.BBDialogTheme)
                        .setTitle("Change icon for...")
                        .setItems(arrayOf("This page only", "Everywhere (global)")) { _, which ->
                            val isPageOnly = which == 0
                            val pageId = if (isPageOnly) currentPageId else null
                            val pickerDialog = IconPickerDialog(this,
                                onIconSelected = { drawableName, _ ->
                                    prefs.setCustomIcon(cn, drawableName, pageId)
                                    reloadAppsAndRefresh()
                                },
                                onReset = {
                                    prefs.setCustomIcon(cn, null, pageId)
                                    reloadAppsAndRefresh()
                                }
                            )
                            pickerDialog.showPackPicker()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
                title == getString(R.string.app_info) -> {
                    startActivity(Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    ).apply { data = Uri.parse("package:${app.packageName}") })
                    true
                }
                title == "Uninstall" -> {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun reloadAppsAndRefresh() {
        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.IO) { repository.loadApps() }
            if (::pagerAdapter.isInitialized) pagerAdapter.refreshAll()
            dockBar.loadDock()
        }
    }

    private fun launchDockSwipeAction(slotIndex: Int) {
        val value = prefs.getDockSwipeAction(slotIndex) ?: return
        try {
            if (value.startsWith("intent:") || value.contains("#")) {
                val intent = Intent.parseUri(value, 0)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                val component = ComponentName.unflattenFromString(value) ?: return
                startActivity(Intent(Intent.ACTION_MAIN).apply {
                    setComponent(component)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        } catch (_: Exception) {}
    }

    private fun showDockSwipeActionPicker(slotIndex: Int) {
        val options = mutableListOf("Choose app…", "Choose shortcut…")
        if (prefs.getDockSwipeAction(slotIndex) != null) options.add("Clear swipe-up action")
        AlertDialog.Builder(this, R.style.BBDialogTheme)
            .setTitle("Swipe-up action for this slot")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        val apps = repository.getAllApps().sortedBy { it.label.lowercase() }
                        val labels = apps.map { it.label }.toTypedArray()
                        AlertDialog.Builder(this, R.style.BBDialogTheme)
                            .setTitle("Choose app")
                            .setItems(labels) { _, idx ->
                                val app = apps[idx]
                                prefs.setDockSwipeAction(slotIndex, app.componentName.flattenToString())
                                dockBar.loadDock()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    1 -> {
                        pendingDockSwipeSlotIndex = slotIndex
                        try {
                            dockSwipeShortcutLauncher.launch(Intent(Intent.ACTION_CREATE_SHORTCUT))
                        } catch (_: Exception) {
                            pendingDockSwipeSlotIndex = -1
                            android.widget.Toast.makeText(this, "No shortcut handler found", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> {
                        prefs.setDockSwipeAction(slotIndex, null)
                        dockBar.loadDock()
                    }
                    else -> {}
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDockIconContextMenu(app: AppInfo, slotIndex: Int) {
        val popup = PopupMenu(this, dockBar)
        popup.menu.add(getString(R.string.unpin_from_dock))
        popup.menu.add("Change Name")
        popup.menu.add("Change Icon")
        popup.menu.add("Set swipe-up action")
        if (prefs.getDockSwipeAction(slotIndex) != null) popup.menu.add("Clear swipe-up action")
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                getString(R.string.unpin_from_dock) -> {
                    dockBar.removeFromDock(app)
                    true
                }
                "Set swipe-up action" -> {
                    showDockSwipeActionPicker(slotIndex)
                    true
                }
                "Clear swipe-up action" -> {
                    prefs.setDockSwipeAction(slotIndex, null)
                    dockBar.loadDock()
                    true
                }
                "Change Name" -> {
                    val cn = app.componentName.flattenToString()
                    AlertDialog.Builder(this, R.style.BBDialogTheme)
                        .setTitle("Change name for...")
                        .setItems(arrayOf("Dock only", "Everywhere (global)")) { _, which ->
                            val pageId = if (which == 0) "dock" else null
                            val current = prefs.getCustomLabel(cn, pageId) ?: app.label
                            val input = android.widget.EditText(this).apply {
                                setText(current.toString())
                                setPadding(dp(24), dp(16), dp(24), dp(16))
                                hint = "Name"
                            }
                            AlertDialog.Builder(this, R.style.BBDialogTheme)
                                .setTitle("Name")
                                .setView(input)
                                .setPositiveButton("OK") { _, _ ->
                                    val name = input.text.toString().trim()
                                    prefs.setCustomLabel(cn, if (name.isEmpty()) null else name, pageId)
                                    reloadAppsAndRefresh()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
                "Change Icon" -> {
                    val cn = app.componentName.flattenToString()
                    AlertDialog.Builder(this, R.style.BBDialogTheme)
                        .setTitle("Change icon for...")
                        .setItems(arrayOf("Dock only", "Everywhere (global)")) { _, which ->
                            val pageId = if (which == 0) "dock" else null
                            val pickerDialog = IconPickerDialog(this,
                                onIconSelected = { drawableName, _ ->
                                    prefs.setCustomIcon(cn, drawableName, pageId)
                                    reloadAppsAndRefresh()
                                },
                                onReset = {
                                    prefs.setCustomIcon(cn, null, pageId)
                                    reloadAppsAndRefresh()
                                }
                            )
                            pickerDialog.showPackPicker()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun getKeyRole(keyCode: Int): Int? {
        if (!prefs.keyShortcutsEnabled) return null
        return when (keyCode) {
            prefs.keyCodeHome -> KEY_ROLE_HOME
            prefs.keyCodeBack -> KEY_ROLE_BACK
            prefs.keyCodeRecents -> KEY_ROLE_RECENTS
            else -> null
        }
    }

    private fun doBackAction() {
        when {
            searchOverlay.visibility == View.VISIBLE -> searchOverlay.dismiss()
            notificationHub.visibility == View.VISIBLE -> notificationHub.hide()
            ::pagerAdapter.isInitialized && appPager.currentItem != prefs.defaultTab ->
                appPager.setCurrentItem(prefs.defaultTab, true)
        }
    }

    private fun lockScreen() {
        val a11y = KeyCaptureAccessibilityService.instance
        if (a11y != null) {
            val ok = a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            if (ok) return
        }
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
            val admins = dpm?.activeAdmins ?: emptyList<android.content.ComponentName>()
            if (admins.isNotEmpty()) {
                dpm?.lockNow()
                return
            }
        } catch (_: Exception) {}
        startActivity(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    private fun launchAssistant() {
        try {
            startActivity(Intent("android.intent.action.VOICE_ASSIST").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
    }

    private fun openQuickSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    private fun refreshLauncher() {
        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.IO) { repository.loadApps() }
            if (::pagerAdapter.isInitialized) pagerAdapter.refreshAll()
            dockBar.loadDock()
            loadPageIconPacks()
            recreate()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val role = getKeyRole(keyCode)
        if (role != null) {
            keyRoleLongPressTriggered = false
            keyRoleLongPressRunnable?.let { keyHandler.removeCallbacks(it) }
            keyRoleLongPressRunnable = Runnable {
                keyRoleLongPressTriggered = true
                when (role) {
                    KEY_ROLE_HOME -> launchAssistant()
                    KEY_ROLE_BACK -> refreshLauncher()
                    KEY_ROLE_RECENTS -> showHomeContextMenu(tabBarContainer)
                    else -> {}
                }
            }
            keyHandler.postDelayed(keyRoleLongPressRunnable!!, LONG_PRESS_MS)
            return true
        }
        if (searchOverlay.visibility != View.VISIBLE && prefs.searchOnType && event != null && event.isPrintingKey) {
            if (injectCaptureActive && event.unicodeChar.toInt() > 0) {
                injectCaptureBuffer.append(event.unicodeChar.toChar())
                injectCaptureRunnable?.let { keyHandler.removeCallbacks(it) }
                injectCaptureRunnable = Runnable { flushInjectCapture() }
                val elapsed = SystemClock.uptimeMillis() - injectCaptureStartTime
                val delayMs = prefs.searchEngineLaunchInjectDelayMs.toLong()
                val windowMs = prefs.searchEngineLaunchInjectAlternativeWindowMs.toLong()
                val remaining = (delayMs - elapsed).coerceAtLeast(0)
                keyHandler.postDelayed(injectCaptureRunnable!!, maxOf(remaining, windowMs))
                return true
            }
            when (prefs.searchEngineMode) {
                0 -> {
                    showSearchPageAware(initialChar = event.unicodeChar.toChar())
                    return true
                }
                1 -> {
                    val pkg = prefs.searchEnginePackage
                    if (pkg != null) {
                        try {
                            startActivity(packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {}
                        return true
                    }
                }
                2 -> {
                    val pkg = prefs.searchEnginePackage
                    val query = event.unicodeChar.toChar().toString()
                    val uriRaw = prefs.searchEngineIntentUri
                    if (uriRaw != null && uriRaw.isNotEmpty()) {
                        try {
                            val uri = uriRaw.replace("%s", Uri.encode(query))
                            val intent = Intent.parseUri(uri, 0)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            if (!uri.contains("query", ignoreCase = true)) {
                                intent.putExtra(SearchManager.QUERY, query)
                                intent.putExtra("query", query)
                            }
                            startActivity(intent)
                        } catch (_: Exception) {
                            if (pkg != null) launchSearchAppWithQuery(pkg, query)
                        }
                        return true
                    }
                    if (pkg != null) {
                        launchSearchAppWithQuery(pkg, query)
                        return true
                    }
                }
                3 -> {
                    val uri = prefs.searchEngineShortcutIntentUri
                    if (uri != null) {
                        try {
                            shortcutHelper.launchIntentShortcut(uri)
                        } catch (_: Exception) {}
                        return true
                    }
                }
                4 -> {
                    val uri = prefs.searchEngineLaunchInjectIntentUri
                    if (uri != null) {
                        val keyToInject = event.keyCode
                        val firstChar = event.unicodeChar.toInt()
                        try {
                            shortcutHelper.launchIntentShortcut(uri)
                        } catch (_: Exception) {}
                        val delayMs = prefs.searchEngineLaunchInjectDelayMs.toLong()
                        if (prefs.searchEngineLaunchInjectUseRoot && prefs.searchEngineLaunchInjectAlternativeListener && firstChar > 0) {
                            injectCaptureActive = true
                            injectCaptureStartTime = SystemClock.uptimeMillis()
                            injectCaptureBuffer.setLength(0)
                            injectCaptureBuffer.append(firstChar.toChar())
                            injectCaptureRunnable?.let { keyHandler.removeCallbacks(it) }
                            injectCaptureRunnable = Runnable { flushInjectCapture() }
                            keyHandler.postDelayed(injectCaptureRunnable!!, delayMs)
                        } else if (prefs.searchEngineLaunchInjectUseRoot) {
                            keyHandler.postDelayed({ injectKeyViaRoot(keyToInject) }, delayMs)
                        } else if (prefs.searchEngineLaunchInjectWaitForFocus) {
                            KeyCaptureAccessibilityService.setPendingKeyInject(keyToInject)
                            keyHandler.postDelayed({ KeyCaptureAccessibilityService.clearPendingKeyInject() }, 15_000L)
                        } else {
                            keyHandler.postDelayed({
                                KeyCaptureAccessibilityService.instance?.injectKey(keyToInject)
                            }, delayMs)
                        }
                        return true
                    }
                }
                5 -> { /* disabled */ }
                else -> {}
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val role = getKeyRole(keyCode)
        if (role != null) {
            keyRoleLongPressRunnable?.let { keyHandler.removeCallbacks(it) }
            keyRoleLongPressRunnable = null
            if (keyRoleLongPressTriggered) {
                keyRolePressCount = 0
                keyRoleDoublePressRunnable?.let { keyHandler.removeCallbacks(it) }
                keyRoleDoublePressRunnable = null
                return true
            }
            keyRolePressCount++
            keyRoleDoublePressRunnable?.let { keyHandler.removeCallbacks(it) }
            val capturedRole = role
            keyRoleDoublePressRunnable = Runnable {
                val count = keyRolePressCount
                keyRolePressCount = 0
                keyRoleDoublePressRunnable = null
                when (capturedRole) {
                    KEY_ROLE_HOME -> {
                        if (::pagerAdapter.isInitialized && appPager.currentItem == prefs.defaultTab) lockScreen()
                        else if (::pagerAdapter.isInitialized) appPager.setCurrentItem(prefs.defaultTab, true)
                    }
                    KEY_ROLE_BACK -> if (count >= 2) openQuickSettings() else doBackAction()
                    KEY_ROLE_RECENTS -> if (count >= 2) {
                        if (notificationHub.visibility == View.VISIBLE) notificationHub.hide()
                        else notificationHub.show(prefs.getNotificationAppWhitelist())
                    }
                    else -> {}
                }
            }
            keyHandler.postDelayed(keyRoleDoublePressRunnable!!, DOUBLE_PRESS_MS)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        doBackAction()
    }

    override fun onStart() {
        super.onStart()
        widgetHost.startListening()
    }

    override fun onStop() {
        super.onStop()
        widgetHost.stopListening()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(OPEN_HOME_MENU_EXTRA, false)) {
            tabBarContainer.post { showHomeContextMenu(tabBarContainer) }
            intent.removeExtra(OPEN_HOME_MENU_EXTRA)
            return
        }
        if (searchOverlay.visibility == View.VISIBLE) searchOverlay.dismiss()
        if (notificationHub.visibility == View.VISIBLE) notificationHub.hide()
        if (::pagerAdapter.isInitialized && appPager.currentItem != prefs.defaultTab) {
            appPager.setCurrentItem(prefs.defaultTab, true)
        }
    }

    override fun onResume() {
        super.onResume()
        updateSoundProfileIcon()
        refreshWallpaper()
        applySystemUI()
        statusBar.refresh()
        refreshNotificationTicker()
        if (notificationHub.visibility == View.VISIBLE) notificationHub.refresh(prefs.getNotificationAppWhitelist())
        applyOpacitySettings()

        // Reload icon packs if changed
        val savedPack = prefs.iconPackPackage
        val savedAllPagePack = prefs.allPageIconPackPackage
        val savedDockPack = prefs.dockIconPackPackage
        val packChanged = savedPack != iconPackManager.currentPackage
        val allPagePackChanged = savedAllPagePack != allPageIconPackManager.currentPackage
        val dockPackChanged = savedDockPack != dockIconPackManager.currentPackage

        if (packChanged) {
            if (savedPack != null) iconPackManager.loadIconPack(savedPack)
            else iconPackManager.clearIconPack()
        }
        if (allPagePackChanged) {
            if (savedAllPagePack != null) allPageIconPackManager.loadIconPack(savedAllPagePack)
            else allPageIconPackManager.clearIconPack()
        }
        if (dockPackChanged) {
            if (savedDockPack != null) dockIconPackManager.loadIconPack(savedDockPack)
            else dockIconPackManager.clearIconPack()
        }

        // Always reload per-page packs (cheap check)
        loadPageIconPacks()

        if (packChanged || allPagePackChanged || dockPackChanged) {
            CoroutineScope(Dispatchers.Main).launch {
                withContext(Dispatchers.IO) { repository.loadApps() }
                if (::pagerAdapter.isInitialized) pagerAdapter.refreshAll()
                dockBar.loadDock()
            }
        } else {
            if (::pagerAdapter.isInitialized) pagerAdapter.refreshAll()
            dockBar.loadDock()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.unregister()
        NotifListenerService.onNotificationsChanged = null
        try { unregisterReceiver(wallpaperChangedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(ringerModeReceiver) } catch (_: Exception) {}
    }
}
