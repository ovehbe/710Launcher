package com.meowgi.launcher710.ui.settings

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.meowgi.launcher710.ui.dialogs.AppPickerWithSearchDialog
import com.meowgi.launcher710.ui.dialogs.IconPickerDialog
import com.meowgi.launcher710.util.IconPackManager

internal fun SettingsActivity.getNotificationAppsLabel(): String {
    val w = prefs.getNotificationAppWhitelist()
    return if (w.isEmpty()) "All apps" else "${w.size} app(s)"
}

internal fun SettingsActivity.showNotificationAppsPicker() {
    val whitelist = prefs.getNotificationAppWhitelist()
    val pkgList = installedApps()
    if (pkgList.isEmpty()) {
        Toast.makeText(this, "No installed apps found.", Toast.LENGTH_SHORT).show()
        return
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
    val dialog = AlertDialog.Builder(this, dialogTheme)
        .setTitle("Apps in notification hub")
        .setView(cappedScroll(layout))
        .setPositiveButton("OK") { _, _ ->
            val selected = checks.filter { it.value.isChecked }.keys
            prefs.setNotificationAppWhitelist(selected)
            rebuildSettings()
        }
        .setNegativeButton("Clear (show all)") { _, _ ->
            prefs.setNotificationAppWhitelist(emptySet())
            rebuildSettings()
        }
        .setNeutralButton("Cancel", null)
        .create()
    dialog.show()
}

internal fun SettingsActivity.showAppletManager() {
    val packages = prefs.getNotificationApplets().toMutableList()
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
            val mgr = IconPackManager(this@showAppletManager)
            if (mgr.loadIconPack(customPack)) {
                mgr.getIconByName(customName)?.let { return it }
            }
        }
        return try { packageManager.getApplicationIcon(packageManager.getApplicationInfo(pkg, 0)) } catch (_: Exception) { null }
    }

    fun refreshList() {
        rowsContainer.removeAllViews()
        for ((index, pkg) in packages.withIndex()) {
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
            if (index > 0) {
                val upBtn = TextView(this).apply {
                    text = "▲"
                    textSize = 16f
                    setPadding(dp(8), 0, dp(8), 0)
                    setTextColor(accent)
                    setOnClickListener {
                        packages[index] = packages[index - 1].also { packages[index - 1] = packages[index] }
                        prefs.setNotificationApplets(packages)
                        refreshList()
                    }
                }
                row.addView(upBtn)
            }
            if (index < packages.size - 1) {
                val downBtn = TextView(this).apply {
                    text = "▼"
                    textSize = 16f
                    setPadding(dp(8), 0, dp(8), 0)
                    setTextColor(accent)
                    setOnClickListener {
                        packages[index] = packages[index + 1].also { packages[index + 1] = packages[index] }
                        prefs.setNotificationApplets(packages)
                        refreshList()
                    }
                }
                row.addView(downBtn)
            }
            val changeIconBtn = Button(this).apply {
                text = "Change icon"
                setOnClickListener {
                    val picker = IconPickerDialog(this@showAppletManager,
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
            val installed = installedApps(exclude = packages.toSet())
            if (installed.isEmpty()) {
                Toast.makeText(this@showAppletManager, "No more apps to add.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AppPickerWithSearchDialog.show(this@showAppletManager, "Add applet", installed, onSelected = { pkg, _ ->
                packages.add(pkg)
                prefs.setNotificationApplets(packages)
                refreshList()
            })
        }
    }
    layout.addView(addBtn)
    refreshList()
    AlertDialog.Builder(this, dialogTheme)
        .setTitle("Notification applets")
        .setView(cappedScroll(layout))
        .setNegativeButton("Done") { _, _ -> rebuildSettings() }
        .show()
}
