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
import com.meowgi.launcher710.ui.dialogs.AppPickerWithSearchDialog
import com.meowgi.launcher710.ui.dialogs.IconPickerDialog
import com.meowgi.launcher710.ui.dialogs.SoundProfileOverlay
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
    private lateinit var soundProfileOverlay: SoundProfileOverlay
    private lateinit var dockBar: DockBar
    private lateinit var tabBarContainer: android.widget.LinearLayout
    private lateinit var actionBar: View
    private lateinit var rootFrame: View
    private lateinit var mainLayout: android.widget.LinearLayout

    private lateinit var notificationTickerBar: View
    private lateinit var notificationAppletsContainer: android.widget.LinearLayout
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
    private var currentTickerNotificationKey: String? = null
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
            isDnd && am.ringerMode == AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_sound_alerts_off_custom
            isDnd -> R.drawable.ic_sound_dnd
            am.ringerMode == AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_sound_silent_custom
            am.ringerMode == AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_sound_vibrate_custom
            else -> R.drawable.ic_sound_normal_custom
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
        soundProfileOverlay = findViewById(R.id.soundProfileOverlay)
        dockBar = findViewById(R.id.dockBar)
        tabBarContainer = findViewById(R.id.tabBar)
        actionBar = findViewById(R.id.actionBar)
        notificationTickerBar = findViewById(R.id.notificationTickerBar)
        notificationAppletsContainer = findViewById(R.id.notificationAppletsContainer)
        notificationTickerText = findViewById(R.id.notificationTickerText)
        notificationCount = findViewById(R.id.notificationCount)
        searchInputInBar = findViewById(R.id.searchInputInBar)
        btnSoundProfile = findViewById(R.id.btnSoundProfile)

        setupSearchInputBar()

        val onActionBarCenterClick: () -> Unit = {
            when (prefs.actionBarCenterAction) {
                0 -> {
                    if (notificationHub.visibility == View.VISIBLE) notificationHub.hide()
                    else {
                        dismissOtherOverlays()
                        notificationHub.show(prefs.getNotificationAppWhitelist())
                    }
                }
                1, 2 -> launchHeaderAction(
                    prefs.actionBarCenterAction,
                    prefs.actionBarCenterActionPackage,
                    prefs.actionBarCenterActionIntentUri
                )
                else -> { }
            }
        }
        val onActionBarCenterLongPress: () -> Unit = {
            when (prefs.actionBarCenterLongPressAction) {
                0 -> {
                    if (notificationHub.visibility == View.VISIBLE) notificationHub.hide()
                    else {
                        dismissOtherOverlays()
                        notificationHub.show(prefs.getNotificationAppWhitelist())
                    }
                }
                1, 2 -> launchHeaderAction(
                    prefs.actionBarCenterLongPressAction,
                    prefs.actionBarCenterLongPressActionPackage,
                    prefs.actionBarCenterLongPressActionIntentUri
                )
                else -> { }
            }
        }
        // Single touch overlay so one view gets the tap and ripple (avoids wrong highlight on touch vs trackpad)
        findViewById<View>(R.id.actionBarCenter).apply {
            isClickable = false
            isFocusable = false
        }
        notificationTickerBar.apply {
            isClickable = false
            isFocusable = false
        }
        findViewById<View>(R.id.actionBarCenterTouchOverlay).apply {
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = false
            defaultFocusHighlightEnabled = false
            foreground = prefs.getClickHighlightRipple(this@LauncherActivity)
            setOnClickListener { onActionBarCenterClick() }
            setOnLongClickListener { onActionBarCenterLongPress(); true }
        }
        notificationHub.onClearAll = { keyHandler.postDelayed({ refreshNotificationTicker() }, 500) }

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
                is LaunchableItem.LauncherSettings -> startActivity(Intent(this, SettingsActivity::class.java))
                is LaunchableItem.Contact -> item.phoneNumbers.firstOrNull()?.let { num ->
                    try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Exception) {}
                }
                else -> {}
            }
        }
        searchOverlay.onItemLongClick = { item, view ->
            when (item) {
                is LaunchableItem.App -> showAppContextMenu(item.app, view)
                is LaunchableItem.Shortcut -> showShortcutContextMenu(item.shortcut, view)
                is LaunchableItem.IntentShortcut -> showIntentShortcutContextMenu(item.info, view)
                is LaunchableItem.LauncherSettings -> { /* no context menu */ }
                is LaunchableItem.Contact -> { }
                else -> {}
            }
        }
        searchOverlay.dialerLayoutProvider = { prefs.dialerNumberLayout }
        searchOverlay.contactSearchEnabledProvider = { prefs.searchContactsEnabled }
        searchOverlay.contactSourceProvider = { prefs.searchContactsSource ?: "all" }
        searchOverlay.contactIconProvider = { repository.getContactIconForSearch() }

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
        setupHeader()
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
            setupFocusOrder()
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
        val appletsPkg = prefs.getPageIconPackPackage("applets")
        if (appletsPkg != null) {
            val mgr = IconPackManager(this)
            if (mgr.loadIconPack(appletsPkg)) {
                repository.pageIconPackManagers["applets"] = mgr
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
        headerView.visibility = if (prefs.headerVisible) View.VISIBLE else View.GONE
        headerView.refresh(prefs)
        actionBar.setBackgroundColor(Color.argb(prefs.actionBarAlpha, 0, 0, 0))
        tabBarContainer.background?.mutate()?.alpha = prefs.tabBarAlpha
        dockBar.applyOpacity()
        notificationHub.applyOpacity(prefs.notificationHubAlpha)
        searchOverlay.applyOpacity(prefs.searchOverlayAlpha)
        soundProfileOverlay.applyOpacity(prefs.soundProfileOverlayAlpha)
    }

    private fun setupPager() {
        pagerAdapter = AppPagerAdapter(
            this, repository, shortcutHelper, widgetHost,
            onItemLongClick = { item, view ->
                when (item) {
                    is LaunchableItem.App -> showAppContextMenu(item.app, view)
                    is LaunchableItem.Shortcut -> showShortcutContextMenu(item.shortcut, view)
                    is LaunchableItem.IntentShortcut -> showIntentShortcutContextMenu(item.info, view)
                    is LaunchableItem.LauncherSettings -> { /* no context menu */ }
                    is LaunchableItem.Contact -> { }
                }
            },
            onEmptySpaceLongClick = { showHomeContextMenu(tabBarContainer) }
        )
        appPager.adapter = pagerAdapter
        appPager.isUserInputEnabled = false
        buildTabBar()
        setupFocusOrder()
        setupBottomSwipe()
        val startTab = pagerAdapter.getPositionForPageId(prefs.defaultTabPageId)
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
                id = View.generateViewId()
                text = pagerAdapter.getPageName(i)
                setTextColor(getColor(R.color.bb_text_secondary))
                textSize = 13f
                typeface = font
                gravity = android.view.Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = false
                defaultFocusHighlightEnabled = false
                foreground = prefs.getClickHighlightRipple(this@LauncherActivity)
                setOnClickListener { appPager.setCurrentItem(i, true) }
            }
            val lp = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            tabBarContainer.addView(tv, lp)
            tabViews.add(tv)
        }
    }

    /** Sets explicit focus order for trackpad/keyboard: date → clock → action bar → tabs → dock. */
    private fun setupFocusOrder() {
        tabBarContainer.isFocusable = false
        tabBarContainer.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        dockBar.isFocusable = false
        dockBar.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        headerView.dateText.nextFocusForwardId = R.id.header_clock_text
        headerView.dateText.nextFocusDownId = R.id.actionBarCenterTouchOverlay
        headerView.clockText.nextFocusDownId = R.id.actionBarCenterTouchOverlay
        findViewById<View>(R.id.actionBarCenterTouchOverlay).apply {
            nextFocusForwardId = R.id.btnSearch
            nextFocusDownId = R.id.tabBar
        }
        findViewById<View>(R.id.btnSearch).apply {
            isFocusable = true
            isFocusableInTouchMode = false
            nextFocusForwardId = R.id.btnSoundProfile
            nextFocusDownId = R.id.tabBar
        }
        findViewById<View>(R.id.btnSoundProfile).apply {
            isFocusable = true
            isFocusableInTouchMode = false
            nextFocusDownId = R.id.tabBar
        }
        for (i in tabViews.indices) {
            val tab = tabViews[i]
            tab.nextFocusDownId = R.id.dockBar
            if (i == 0) tab.nextFocusUpId = R.id.btnSoundProfile
        }
        for (i in 1 until tabViews.size) {
            tabViews[i - 1].nextFocusForwardId = tabViews[i].id
        }
        if (dockBar.childCount > 0 && tabViews.isNotEmpty()) {
            dockBar.getChildAt(0).nextFocusUpId = tabViews.last().id
        }
    }

    private fun updateTabHighlight(selected: Int) {
        val baseColor = if (prefs.tabBarHighlightUseAccent) prefs.accentColor else prefs.tabBarHighlightCustomColor
        val alpha = prefs.tabBarHighlightAlpha
        val lighterAccent = Color.argb(
            alpha,
            kotlin.math.min(255, (Color.red(baseColor) * 1.15).toInt()),
            kotlin.math.min(255, (Color.green(baseColor) * 1.15).toInt()),
            kotlin.math.min(255, (Color.blue(baseColor) * 1.15).toInt())
        )
        val darkerAccent = Color.argb(
            alpha,
            (Color.red(baseColor) * 0.78).toInt(),
            (Color.green(baseColor) * 0.78).toInt(),
            (Color.blue(baseColor) * 0.78).toInt()
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

    /** Applies the current click highlight ripple to a view and disables focus highlight (avoids stuck highlight when using trackpad). */
    private fun applyClickHighlight(view: View?) {
        view ?: return
        view.isClickable = true
        view.defaultFocusHighlightEnabled = false
        view.foreground = prefs.getClickHighlightRipple(this)
    }

    /** Re-applies click highlight color/opacity to all launcher elements (e.g. after accent color change). */
    private fun refreshClickHighlights() {
        val ripple = prefs.getClickHighlightRipple(this)
        applyClickHighlight(findViewById(R.id.actionBarCenterTouchOverlay))
        applyClickHighlight(findViewById(R.id.btnSearch))
        applyClickHighlight(findViewById(R.id.btnSoundProfile))
        findViewById<View>(R.id.actionBarCenterTouchOverlay).defaultFocusHighlightEnabled = false
        findViewById<View>(R.id.btnSearch).defaultFocusHighlightEnabled = false
        findViewById<View>(R.id.btnSoundProfile).defaultFocusHighlightEnabled = false
        headerView.dateText.apply {
            isClickable = true
            defaultFocusHighlightEnabled = false
            foreground = prefs.getClickHighlightRipple(this@LauncherActivity)
        }
        headerView.clockText.apply {
            isClickable = true
            defaultFocusHighlightEnabled = false
            foreground = prefs.getClickHighlightRipple(this@LauncherActivity)
        }
        for (tab in tabViews) {
            tab.defaultFocusHighlightEnabled = false
            tab.foreground = prefs.getClickHighlightRipple(this)
        }
        for (i in 0 until dockBar.childCount) {
            dockBar.getChildAt(i).apply {
                defaultFocusHighlightEnabled = false
                foreground = ripple
            }
        }
        if (::pagerAdapter.isInitialized) {
            pagerAdapter.notifyDataSetChanged()
            setupFocusOrder()
        }
    }

    /** Recursively clears focus and pressed state from a view and its descendants. */
    private fun clearViewStateRecursive(view: View) {
        if (view.isClickable || view.isFocusable) {
            view.setPressed(false)
            view.clearFocus()
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                clearViewStateRecursive(view.getChildAt(i))
            }
        }
    }

    /** Clears focus and pressed state from all interactive views (header, action bar, tabs, dock, app grid) so no highlight "traces" remain. */
    private fun clearHighlightTraces() {
        window.decorView.findFocus()?.clearFocus()
        clearViewStateRecursive(headerView)
        findViewById<View>(R.id.actionBar)?.let { clearViewStateRecursive(it) }
        for (tab in tabViews) {
            tab.setPressed(false)
            tab.clearFocus()
            tab.foreground = prefs.getClickHighlightRipple(this)
        }
        clearViewStateRecursive(tabBarContainer)
        clearViewStateRecursive(dockBar)
        findViewById<View>(R.id.contentArea)?.let { clearViewStateRecursive(it) }
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

    /** Close all overlays so only one can be visible at a time. Call before showing any overlay. */
    private fun dismissOtherOverlays() {
        if (soundProfileOverlay.visibility == View.VISIBLE) soundProfileOverlay.hide()
        if (searchOverlay.visibility == View.VISIBLE) searchOverlay.dismiss()
        if (notificationHub.visibility == View.VISIBLE) notificationHub.hide()
    }

    private fun showSearchPageAware(initialChar: Char? = null, preserveText: Boolean = false) {
        searchApplyRunnable?.let { keyHandler.removeCallbacks(it) }
        searchApplyRunnable = null

        dismissOtherOverlays()

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
        searchInputInBar.alpha = 1f
        if (initialChar != null) {
            searchInputInBar.setSelection(0)
            searchInputInBar.setText(initialChar.toString())
            searchInputInBar.setSelection(searchInputInBar.text.length)
        } else if (!preserveText) {
            searchInputInBar.setText("")
        }
        // Post focus so it works when opening by touch (not only trackpad)
        searchInputInBar.post {
            searchInputInBar.requestFocus()
            if (searchOverlay.visibility == View.VISIBLE) {
                searchOverlay.applyQuery(searchInputInBar.text.toString())
            }
        }
    }

    private fun setupHeader() {
        headerView.isFocusable = false
        headerView.isClickable = false
        headerView.descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        headerView.dateText.apply {
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = false
            defaultFocusHighlightEnabled = false
            foreground = prefs.getClickHighlightRipple(this@LauncherActivity)
            setOnClickListener {
                launchHeaderAction(prefs.headerDateAction, prefs.headerDateActionPackage, prefs.headerDateActionIntentUri)
            }
        }
        headerView.clockText.apply {
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = false
            defaultFocusHighlightEnabled = false
            foreground = prefs.getClickHighlightRipple(this@LauncherActivity)
            setOnClickListener {
                launchHeaderAction(prefs.headerClockAction, prefs.headerClockActionPackage, prefs.headerClockActionIntentUri)
            }
        }
    }

    private fun launchHeaderAction(action: Int, pkg: String?, intentUri: String?) {
        if (action == 0) return
        try {
            when (action) {
                1 -> {
                    if (pkg != null) {
                        val intent = packageManager.getLaunchIntentForPackage(pkg)
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                        }
                    }
                }
                2 -> {
                    if (intentUri != null) {
                        val intent = Intent.parseUri(intentUri, 0)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun setupSearchInputBar() {
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
                    } else if (prefs.searchOnType && text.isNotEmpty()) {
                        handleTypeToSearchFromText(text)
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
        searchInputInBar.visibility = View.GONE
    }

    private fun handleTypeToSearchFromText(text: CharSequence) {
        val firstChar = text.firstOrNull() ?: return
        when (prefs.searchEngineMode) {
            0 -> showSearchPageAware(initialChar = null, preserveText = true)
            1 -> {
                val pkg = prefs.searchEnginePackage
                if (pkg != null) {
                    try {
                        startActivity(packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {}
                }
            }
            2 -> {
                val pkg = prefs.searchEnginePackage
                val query = firstChar.toString()
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
                } else if (pkg != null) {
                    launchSearchAppWithQuery(pkg, query)
                }
            }
            3 -> {
                val uri = prefs.searchEngineShortcutIntentUri
                if (uri != null) {
                    try {
                        shortcutHelper.launchIntentShortcut(uri)
                    } catch (_: Exception) {}
                }
            }
            4, 5 -> { /* inject modes need KeyEvent; use search button */ }
            else -> {}
        }
    }

    private fun handleTypeToSearch(event: KeyEvent, typingChar: Char) {
        if (searchOverlay.visibility == View.VISIBLE) return
        if (injectCaptureActive) {
            injectCaptureBuffer.append(typingChar)
            injectCaptureRunnable?.let { keyHandler.removeCallbacks(it) }
            injectCaptureRunnable = Runnable { flushInjectCapture() }
            val elapsed = SystemClock.uptimeMillis() - injectCaptureStartTime
            val delayMs = prefs.searchEngineLaunchInjectDelayMs.toLong()
            val windowMs = prefs.searchEngineLaunchInjectAlternativeWindowMs.toLong()
            val remaining = (delayMs - elapsed).coerceAtLeast(0)
            keyHandler.postDelayed(injectCaptureRunnable!!, maxOf(remaining, windowMs))
            return
        }
        when (prefs.searchEngineMode) {
            0 -> showSearchPageAware(initialChar = typingChar)
            1 -> {
                val pkg = prefs.searchEnginePackage
                if (pkg != null) {
                    try {
                        startActivity(packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {}
                }
            }
            2 -> {
                val pkg = prefs.searchEnginePackage
                val query = typingChar.toString()
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
                } else if (pkg != null) {
                    launchSearchAppWithQuery(pkg, query)
                }
            }
            3 -> {
                val uri = prefs.searchEngineShortcutIntentUri
                if (uri != null) {
                    try {
                        shortcutHelper.launchIntentShortcut(uri)
                    } catch (_: Exception) {}
                }
            }
            4 -> {
                val uri = prefs.searchEngineLaunchInjectIntentUri
                if (uri != null) {
                    val keyToInject = event.keyCode
                    val firstChar = typingChar.code
                    try {
                        shortcutHelper.launchIntentShortcut(uri)
                    } catch (_: Exception) {}
                    val delayMs = prefs.searchEngineLaunchInjectDelayMs.toLong()
                    if (prefs.searchEngineLaunchInjectUseRoot && prefs.searchEngineLaunchInjectAlternativeListener && firstChar > 0) {
                        injectCaptureActive = true
                        injectCaptureStartTime = SystemClock.uptimeMillis()
                        injectCaptureBuffer.setLength(0)
                        injectCaptureBuffer.append(typingChar)
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
                }
            }
            5 -> { /* disabled */ }
            else -> {}
        }
    }

    private fun setupActionBar() {
        applyClickHighlight(findViewById(R.id.btnSoundProfile))
        findViewById<View>(R.id.btnSoundProfile).setOnClickListener {
            dismissOtherOverlays()
            soundProfileOverlay.onDismissed = { updateSoundProfileIcon() }
            soundProfileOverlay.show()
        }
        applyClickHighlight(findViewById(R.id.btnSearch))
        findViewById<View>(R.id.btnSearch).setOnClickListener { showSearchPageAware() }

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
                        else {
                            dismissOtherOverlays()
                            notificationHub.show(prefs.getNotificationAppWhitelist())
                        }
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
        val items = appsWithShortcuts.map { it.packageName to it.label }
        AppPickerWithSearchDialog.show(this, getString(R.string.add_app_shortcut), items, onSelected = { pkg, _ ->
            val app = appsWithShortcuts.find { it.packageName == pkg } ?: return@show
            val shortcuts = shortcutHelper.getShortcutsForPackage(app.packageName)
            if (shortcuts.isEmpty()) return@show
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
        })
    }

    private fun showShortcutContextMenu(shortcut: ShortcutDisplayInfo, anchor: View) {
        val pageId = if (::pagerAdapter.isInitialized) pagerAdapter.getPageId(appPager.currentItem) else "favorites"
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Remove from page")
        popup.menu.add("Change Name")
        popup.menu.add("Change Icon")
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
                listOf(150L, 400L, 1000L, 2000L).forEach { delay ->
                    keyHandler.postDelayed({ refreshNotificationTicker() }, delay)
                }
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
        val useApplets = prefs.useNotificationApplets
        val hasApplets = useApplets && prefs.getNotificationApplets().isNotEmpty()

        if (notifs.isEmpty()) {
            tickerTypingRunnable?.let { keyHandler.removeCallbacks(it) }
            tickerDismissRunnable?.let { keyHandler.removeCallbacks(it) }
            tickerTypingRunnable = null
            tickerDismissRunnable = null
            currentTickerNotificationKey = null
            notificationTickerText.text = ""
            notificationTickerText.visibility = View.GONE
            notificationCount.visibility = View.GONE
            if (hasApplets) {
                notificationTickerBar.visibility = View.VISIBLE
                refreshNotificationApplets(emptyList())
                notificationAppletsContainer.visibility = View.VISIBLE
            } else {
                notificationAppletsContainer.visibility = View.GONE
                notificationTickerBar.visibility = View.GONE
            }
            return
        }

        val first = notifs.firstOrNull() ?: return
        val fullText = getTickerDisplayText(first)

        if (fullText.isNotEmpty() && first.key == currentTickerNotificationKey && (tickerTypingRunnable != null || tickerDismissRunnable != null)) {
            notificationTickerBar.visibility = View.VISIBLE
            notificationTickerText.visibility = View.VISIBLE
            notificationCount.visibility = View.GONE
            notificationAppletsContainer.visibility = View.GONE
            return
        }

        notificationTickerBar.visibility = View.VISIBLE
        if (useApplets) {
            refreshNotificationApplets(notifs)
            notificationAppletsContainer.visibility = View.VISIBLE
            notificationCount.visibility = View.GONE
        } else {
            notificationAppletsContainer.visibility = View.GONE
            notificationCount.text = if (notifs.size == 1) "1 Notification" else "${notifs.size} Notifications"
            notificationCount.visibility = View.VISIBLE
        }

        tickerTypingRunnable?.let { keyHandler.removeCallbacks(it) }
        tickerDismissRunnable?.let { keyHandler.removeCallbacks(it) }
        tickerTypingRunnable = null
        tickerDismissRunnable = null

        notificationTickerText.text = ""
        notificationTickerText.visibility = View.VISIBLE
        notificationCount.visibility = View.GONE
        notificationAppletsContainer.visibility = View.GONE

        if (fullText.isEmpty()) {
            notificationTickerText.visibility = View.GONE
            if (useApplets) {
                notificationAppletsContainer.visibility = View.VISIBLE
                notificationCount.visibility = View.GONE
            } else {
                notificationAppletsContainer.visibility = View.GONE
                notificationCount.visibility = View.VISIBLE
            }
            return
        }

        currentTickerNotificationKey = first.key
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
                        if (prefs.useNotificationApplets) {
                            notificationAppletsContainer.visibility = View.VISIBLE
                            notificationCount.visibility = View.GONE
                        } else {
                            notificationAppletsContainer.visibility = View.GONE
                            notificationCount.visibility = View.VISIBLE
                        }
                    }
                    keyHandler.postDelayed(tickerDismissRunnable!!, tickerDisplayDurationMs)
                }
            }
        }
        keyHandler.postDelayed(tickerTypingRunnable!!, tickerTypingDelayMs)
    }

    private fun refreshNotificationApplets(notifs: List<android.service.notification.StatusBarNotification>) {
        val packages = prefs.getNotificationApplets()
        if (packages.isEmpty()) {
            notificationAppletsContainer.visibility = View.GONE
            return
        }
        val counts = packages.associateWith { pkg -> notifs.count { it.packageName == pkg } }
        val autoHide = prefs.notificationAppletsAutoHide
        val iconSizePx = (prefs.notificationAppletSizeDp * resources.displayMetrics.density).toInt()
        val spacingPx = (prefs.notificationAppletsSpacingDp * resources.displayMetrics.density).toInt()
        notificationAppletsContainer.removeAllViews()
        var first = true
        for (pkg in packages) {
            val count = counts[pkg] ?: 0
            if (autoHide && count == 0) continue
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val iconView = android.widget.ImageView(this).apply {
                setImageDrawable(repository.getAppletIcon(pkg, iconSizePx))
                layoutParams = android.widget.LinearLayout.LayoutParams(iconSizePx, iconSizePx)
            }
            val countText = TextView(this).apply {
                text = count.toString()
                setTextColor(resources.getColor(R.color.bb_text_secondary, null))
                textSize = 12f
                setTypeface(resources.getFont(R.font.bbalphas), android.graphics.Typeface.BOLD)
                setPadding((4 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt(), 0)
            }
            row.addView(iconView)
            row.addView(countText)
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            )
            if (!first) lp.marginStart = spacingPx
            first = false
            notificationAppletsContainer.addView(row, lp)
        }
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
            add("Hide app")
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
                title == "Hide app" -> {
                    val currentPageId = if (::pagerAdapter.isInitialized) pagerAdapter.getPageId(appPager.currentItem) else "favorites"
                    val canHideFromPageOnly = currentPageId in listOf("favorites") || currentPageId.startsWith("custom_")
                    val options = if (canHideFromPageOnly) arrayOf("Hide everywhere", "Hide from this page only") else arrayOf("Hide everywhere")
                    AlertDialog.Builder(this, R.style.BBDialogTheme)
                        .setTitle("Hide app")
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> {
                                    prefs.hideApp(cn)
                                    reloadAppsAndRefresh()
                                }
                                1 -> if (canHideFromPageOnly) {
                                    prefs.toggleAppOnPage(cn, currentPageId)
                                    reloadAppsAndRefresh()
                                }
                            }
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
                        val items = apps.map { it.componentName.flattenToString() to it.label }
                        AppPickerWithSearchDialog.show(this, "Choose app", items, onSelected = { cn, _ ->
                            prefs.setDockSwipeAction(slotIndex, cn)
                            dockBar.loadDock()
                        })
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
            soundProfileOverlay.visibility == View.VISIBLE -> soundProfileOverlay.hide()
            searchOverlay.visibility == View.VISIBLE -> searchOverlay.dismiss()
            notificationHub.visibility == View.VISIBLE -> notificationHub.hide()
            ::pagerAdapter.isInitialized && appPager.currentItem != getDefaultTabPosition() ->
                appPager.setCurrentItem(getDefaultTabPosition(), true)
        }
    }

    private fun getDefaultTabPosition(): Int =
        if (::pagerAdapter.isInitialized) pagerAdapter.getPositionForPageId(prefs.defaultTabPageId) else 0

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

    /** Returns a printable character from a key event, respecting Shift/Alt for capitals and symbols. Includes numbers (main and numpad). Alt lock (double-tap Alt) is also applied. */
    private fun getTypingChar(event: KeyEvent): Char? {
        var c = event.getUnicodeChar(event.metaState)
        if (c <= 0) {
            val keyMap = try {
                android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.BUILT_IN_KEYBOARD)
            } catch (_: Exception) {
                try { android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD) } catch (_: Exception) { null }
            }
            if (keyMap != null) {
                c = keyMap.get(event.keyCode, event.metaState)
            }
        }
        if (c > 0) {
            val ch = c.toChar()
            if (ch.code in 32..126 || ch.isLetterOrDigit()) return ch
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> return '0'
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> return '1'
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> return '2'
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> return '3'
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> return '4'
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> return '5'
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> return '6'
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> return '7'
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> return '8'
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> return '9'
            else -> return null
        }
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

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        event ?: return false

        // When a full-screen overlay (notification hub or sound profile) is visible, make sure
        // DPAD / trackpad navigation keeps focus inside that overlay instead of "behind" it.
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val overlay: View? = when {
                        ::notificationHub.isInitialized && notificationHub.visibility == View.VISIBLE -> notificationHub
                        ::soundProfileOverlay.isInitialized && soundProfileOverlay.visibility == View.VISIBLE -> soundProfileOverlay
                        else -> null
                    }
                    if (overlay != null) {
                        val focused = currentFocus
                        if (focused == null || !isDescendantOf(focused, overlay)) {
                            overlay.requestFocus()
                        }
                    }
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun isDescendantOf(view: View, root: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === root) return true
            val parent = v.parent
            v = if (parent is View) parent else null
        }
        return false
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
        // Focus is on content so trackpad works natively. Intercept first typing key (incl. Alt+key for
        // symbols) and show search; modes 1-5 always handled here. No double-tap Alt (IME) support.
        val typingChar = event?.let { getTypingChar(it) }
        if (searchOverlay.visibility != View.VISIBLE && prefs.searchOnType && typingChar != null && event != null) {
            if (prefs.searchEngineMode == 0) {
                searchInputInBar.requestFocus()
                searchInputInBar.setText(typingChar.toString())
                searchInputInBar.setSelection(1)
                showSearchPageAware(initialChar = null, preserveText = true)
                return true
            }
            handleTypeToSearch(event, typingChar)
            return true
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
                        val defPos = getDefaultTabPosition()
                        if (::pagerAdapter.isInitialized && appPager.currentItem == defPos) lockScreen()
                        else if (::pagerAdapter.isInitialized) appPager.setCurrentItem(defPos, true)
                    }
                    KEY_ROLE_BACK -> if (count >= 2) openQuickSettings() else doBackAction()
                    KEY_ROLE_RECENTS -> if (count >= 2) {
                        if (notificationHub.visibility == View.VISIBLE) notificationHub.hide()
                        else {
                            dismissOtherOverlays()
                            notificationHub.show(prefs.getNotificationAppWhitelist())
                        }
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
        if (soundProfileOverlay.visibility == View.VISIBLE) soundProfileOverlay.hide()
        if (searchOverlay.visibility == View.VISIBLE) searchOverlay.dismiss()
        if (notificationHub.visibility == View.VISIBLE) notificationHub.hide()
        if (::pagerAdapter.isInitialized && appPager.currentItem != getDefaultTabPosition()) {
            appPager.setCurrentItem(getDefaultTabPosition(), true)
        }
    }

    override fun onPause() {
        super.onPause()
        clearHighlightTraces()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) clearHighlightTraces()
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post { clearHighlightTraces() }
        NotifListenerService.onNotificationsChanged = {
            runOnUiThread {
                notificationHub.refresh(prefs.getNotificationAppWhitelist())
                refreshNotificationTicker()
                listOf(150L, 400L, 1000L, 2000L).forEach { delay ->
                    keyHandler.postDelayed({ refreshNotificationTicker() }, delay)
                }
            }
        }
        updateSoundProfileIcon()
        refreshWallpaper()
        applySystemUI()
        statusBar.refresh()
        refreshNotificationTicker()
        listOf(300L, 800L, 1800L).forEach { delay ->
            keyHandler.postDelayed({ refreshNotificationTicker() }, delay)
        }
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
        if (::pagerAdapter.isInitialized) {
            pagerAdapter.reloadPageOrder()
            pagerAdapter.notifyDataSetChanged()
            buildTabBar()
            updateTabHighlight(appPager.currentItem)
        }
        reloadAppsAndRefresh()
        refreshClickHighlights()
        window.decorView.post { clearHighlightTraces() }
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.unregister()
        NotifListenerService.onNotificationsChanged = null
        try { unregisterReceiver(wallpaperChangedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(ringerModeReceiver) } catch (_: Exception) {}
    }
}
