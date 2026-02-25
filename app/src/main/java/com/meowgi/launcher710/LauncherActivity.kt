package com.meowgi.launcher710

import android.graphics.Color
import android.app.Activity
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.view.*
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
import kotlinx.coroutines.*

class LauncherActivity : AppCompatActivity() {

    companion object {
        const val OPEN_HOME_MENU_EXTRA = "open_home_menu"
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

    private val tabViews = mutableListOf<TextView>()

    private var pendingWidgetId = -1

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

    private val wallpaperChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            refreshWallpaper()
        }
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
        searchOverlay.onDismiss = { appPager.visibility = View.VISIBLE }

        dockBar.repository = repository
        dockBar.launcherPrefs = prefs
        dockBar.shortcutHelper = shortcutHelper
        dockBar.onAppLaunch = { app -> repository.launchApp(app) }
        dockBar.onIntentShortcutLaunch = { shortcutHelper.launchIntentShortcut(it.intentUri) }
        dockBar.onDockIconLongClick = { app -> showDockIconContextMenu(app) }
        dockBar.onIntentShortcutLongClick = { showDockIntentShortcutContextMenu(it) }
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
        androidx.core.content.ContextCompat.registerReceiver(
            this, wallpaperChangedReceiver,
            IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
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
        // Also load dock page pack if set via per-page system
        val dockPkg = prefs.getPageIconPackPackage("dock")
        if (dockPkg != null) {
            val mgr = IconPackManager(this)
            if (mgr.loadIconPack(dockPkg)) {
                repository.pageIconPackManagers["dock"] = mgr
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
                bottomSwipeTracking = isInBottomBar(ev.rawY)
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

    private fun setupActionBar() {
        findViewById<View>(R.id.btnSoundProfile).setOnClickListener {
            SoundProfileDialog(this).show()
        }
        findViewById<View>(R.id.btnSearch).setOnClickListener {
            searchOverlay.show()
        }

        findViewById<View>(R.id.contentArea).setOnLongClickListener {
            showHomeContextMenu(tabBarContainer)
            true
        }
    }

    private fun showHomeContextMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(getString(R.string.add_widget))
        val pageId = if (::pagerAdapter.isInitialized) pagerAdapter.getPageId(appPager.currentItem) else "favorites"
        val canAddShortcut = pageId == "favorites" || pageId.startsWith("custom_")
        if (canAddShortcut) {
            popup.menu.add(getString(R.string.add_app_shortcut))
            popup.menu.add(getString(R.string.add_shortcut_all))
        }
        popup.menu.add("Choose Wallpaper")
        popup.menu.add(getString(R.string.settings_title))
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                getString(R.string.add_widget) -> {
                    showWidgetPicker()
                    true
                }
                getString(R.string.add_app_shortcut) -> {
                    showAppShortcutPicker()
                    true
                }
                getString(R.string.add_shortcut_all) -> {
                    try {
                        createShortcutLauncher.launch(Intent(Intent.ACTION_CREATE_SHORTCUT))
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(this, "No shortcut handler found", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                "Choose Wallpaper" -> {
                    try {
                        startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SET_WALLPAPER), "Choose Wallpaper"))
                    } catch (_: Exception) {}
                    true
                }
                getString(R.string.settings_title) -> {
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

    private fun showDockIntentShortcutContextMenu(info: IntentShortcutInfo) {
        val popup = PopupMenu(this, dockBar)
        popup.menu.add(getString(R.string.unpin_from_dock))
        popup.menu.add("Change Name")
        popup.menu.add("Change Icon")
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                getString(R.string.unpin_from_dock) -> {
                    dockBar.removeIntentShortcutFromDock(info)
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

    private fun refreshNotificationTicker() {
        val service = NotifListenerService.instance
        var notifs = service?.getNotifications()?.filter {
            it.notification.extras.getCharSequence("android.title") != null
        } ?: emptyList()
        val whitelist = prefs.getNotificationAppWhitelist()
        if (whitelist.isNotEmpty()) notifs = notifs.filter { it.packageName in whitelist }
        if (notifs.isEmpty()) {
            notificationTickerBar.visibility = View.GONE
        } else {
            notificationTickerBar.visibility = View.VISIBLE
            notificationTickerText.text = notifs.firstOrNull()?.notification?.extras?.getCharSequence("android.title")?.toString() ?: ""
            notificationCount.text = notifs.size.toString()
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

    private fun showDockIconContextMenu(app: AppInfo) {
        val popup = PopupMenu(this, dockBar)
        popup.menu.add(getString(R.string.unpin_from_dock))
        popup.menu.add("Change Name")
        popup.menu.add("Change Icon")
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                getString(R.string.unpin_from_dock) -> {
                    dockBar.removeFromDock(app)
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            notificationHub.let {
                if (it.visibility == View.VISIBLE) it.hide() else it.show(prefs.getNotificationAppWhitelist())
            }
            return true
        }
        if (searchOverlay.visibility != View.VISIBLE && prefs.searchOnType) {
            if (event != null && event.isPrintingKey) {
                searchOverlay.show()
                return super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            searchOverlay.visibility == View.VISIBLE -> searchOverlay.dismiss()
            notificationHub.visibility == View.VISIBLE -> notificationHub.hide()
            appPager.currentItem != prefs.defaultTab ->
                appPager.currentItem = prefs.defaultTab
            else -> super.onBackPressed()
        }
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
    }
}
