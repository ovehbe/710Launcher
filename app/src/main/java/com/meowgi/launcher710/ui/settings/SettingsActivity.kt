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
        addButton("Icon Pack: ${getIconPackName()}") { showIconPackPicker() }
        addChoice("Fallback Icon Shape",
            listOf("Circle", "Rounded Square", "Square", "Squircle"),
            prefs.iconFallbackShape) { prefs.iconFallbackShape = it }

        addSection("Pages")
        addButton("Manage Pages") { showPageManager() }

        addSection("Behavior")
        val pageOrder = prefs.getPageOrder()
        val pageNames = pageOrder.map { when(it) { "frequent" -> "Frequent"; "favorites" -> "Favorites"; "all" -> "All"; else -> it.removePrefix("custom_") } }
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
                        setTextColor(getColor(R.color.bb_tab_active))
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
                        setTextColor(getColor(R.color.bb_tab_active))
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

    // --- Section header ---
    private fun addSection(title: String) {
        val tv = TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.bb_tab_active))
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
            setTextColor(getColor(R.color.bb_tab_active))
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

    private fun rebuildIconPackLabel() {
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
