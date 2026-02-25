package com.meowgi.launcher710.ui.settings

import android.app.WallpaperManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.meowgi.launcher710.R
import com.meowgi.launcher710.util.IconPackManager
import com.meowgi.launcher710.util.LauncherPrefs

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var iconPackManager: IconPackManager
    private lateinit var container: LinearLayout
    private val font: Typeface? by lazy { ResourcesCompat.getFont(this, R.font.bbalphas) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = LauncherPrefs(this)
        iconPackManager = IconPackManager(this)
        container = findViewById(R.id.settingsContainer)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        buildSettings()
    }

    private fun buildSettings() {
        addSection("Appearance")
        addToggle("Show BB Status Bar", prefs.statusBarVisible) { prefs.statusBarVisible = it }
        addToggle("Show System Status Bar", prefs.systemStatusBarVisible) { prefs.systemStatusBarVisible = it }
        addSlider("System Status Bar Opacity", prefs.systemStatusBarAlpha) { prefs.systemStatusBarAlpha = it }
        addSlider("Main Background Opacity", prefs.mainBackgroundAlpha) { prefs.mainBackgroundAlpha = it }
        addSlider("Status Bar Opacity", prefs.statusBarAlpha) { prefs.statusBarAlpha = it }
        addSlider("Header Opacity", prefs.headerAlpha) { prefs.headerAlpha = it }
        addSlider("Tab Bar Opacity", prefs.tabBarAlpha) { prefs.tabBarAlpha = it }
        addSlider("Dock Opacity", prefs.dockAlpha) { prefs.dockAlpha = it }
        addChoice("Dock Color", listOf("Black", "Dark Gray", "Blue", "Navy"), getDockColorIndex()) {
            prefs.dockBackgroundColor = when (it) {
                0 -> 0xFF000000.toInt()
                1 -> 0xFF1A1A1A.toInt()
                2 -> 0xFF001A33.toInt()
                else -> 0xFF0A1628.toInt()
            }
        }
        addChoice("Accent Color", listOf("BB Blue", "Teal", "Red", "Green", "Purple", "Orange", "White"), getAccentColorIndex()) {
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
        addChoice("App View Mode", listOf("Grid", "Old School List"), prefs.appViewMode) {
            prefs.appViewMode = it
            rebuildSettings()
        }
        if (prefs.appViewMode == 1) {
            addButton("List View Applies To: ${getListViewAppliesLabel()}") { showListViewPagesPicker() }
            addChoice("List View Columns", listOf("1", "2", "3"), prefs.listViewColumns - 1) {
                prefs.listViewColumns = it + 1
            }
            addSlider("List Bar Opacity", prefs.listViewBgAlpha) { prefs.listViewBgAlpha = it }
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
        addChoice("Fallback Icon Shape",
            listOf("Circle", "Rounded Square", "Square", "Squircle"),
            prefs.iconFallbackShape) { prefs.iconFallbackShape = it }

        addSection("Pages")
        addButton("Manage Pages") { showPageManager() }

        addSection("Behavior")
        val behaviorPageOrder = prefs.getPageOrder()
        val pageNames = behaviorPageOrder.map { when(it) { "frequent" -> "Frequent"; "favorites" -> "Favorites"; "all" -> "All"; else -> it.removePrefix("custom_") } }
        addChoice("Default Home Tab", pageNames, prefs.defaultTab.coerceIn(0, pageNames.size - 1)) {
            prefs.defaultTab = it
        }
        addChoice("Double-Tap Action", listOf("None", "Lock Screen", "Notifications"), prefs.doubleTapAction) {
            prefs.doubleTapAction = it
        }
        addToggle("Search on Physical Keyboard", prefs.searchOnType) { prefs.searchOnType = it }

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
                        setTextColor(prefs.accentColor)
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
                        setTextColor(prefs.accentColor)
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

    private fun getDockColorIndex(): Int {
        return when (prefs.dockBackgroundColor) {
            0xFF1A1A1A.toInt() -> 1
            0xFF001A33.toInt() -> 2
            0xFF0A1628.toInt() -> 3
            else -> 0
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
            else -> 0
        }
    }

    // --- Section header ---
    private fun addSection(title: String) {
        val tv = TextView(this).apply {
            text = title
            setTextColor(prefs.accentColor)
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
            setTextColor(prefs.accentColor)
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
