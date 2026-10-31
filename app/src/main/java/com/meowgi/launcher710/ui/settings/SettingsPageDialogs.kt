package com.meowgi.launcher710.ui.settings

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.meowgi.launcher710.R

internal fun SettingsActivity.showHiddenAppsPicker() {
    val hidden = prefs.getHiddenApps().toMutableSet()
    if (hidden.isEmpty()) {
        Toast.makeText(this, "No hidden apps", Toast.LENGTH_SHORT).show()
        return
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
    dialogRef = AlertDialog.Builder(this, dialogTheme)
        .setTitle("Hidden apps")
        .setView(cappedScroll(layout))
        .setNegativeButton("Done") { _, _ -> rebuildSettings() }
        .create()
    dialogRef!!.show()
}

internal fun SettingsActivity.showPageManager() {
    val pageOrder = prefs.getPageOrder().toMutableList()
    val builtIn = setOf("frequent", "favorites", "all")
    var currentDialog: AlertDialog? = null

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
                    setTextColor(accent)
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
                    setTextColor(accent)
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

        currentDialog = AlertDialog.Builder(this, dialogTheme)
            .setTitle("Manage Pages")
            .setView(scroll)
            .setPositiveButton("Add Page") { _, _ ->
                val input = android.widget.EditText(this).apply {
                    hint = "Page name"
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                }
                AlertDialog.Builder(this, dialogTheme)
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

// --- Icon pack ---
internal fun SettingsActivity.getIconPackName(): String {
    val pkg = prefs.iconPackPackage ?: return "Default (System)"
    return try {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (_: Exception) { "Default (System)" }
}

internal fun SettingsActivity.showIconPackPicker() {
    val packs = iconPackManager.getAvailableIconPacks()
    val names = mutableListOf("Default (System)")
    val packages = mutableListOf<String?>(null)
    for ((pkg, label) in packs) {
        names.add(label)
        packages.add(pkg)
    }

    AlertDialog.Builder(this, dialogTheme)
        .setTitle("Select Icon Pack")
        .setItems(names.toTypedArray()) { _, which ->
            val selectedPkg = packages[which]
            prefs.iconPackPackage = selectedPkg
            if (selectedPkg != null) {
                iconPackManager.loadIconPack(selectedPkg)
            } else {
                iconPackManager.clearIconPack()
            }
            rebuildSettings()
        }
        .show()
}

internal fun SettingsActivity.getPageIconPackName(pageId: String): String {
    val pkg = prefs.getPageIconPackPackage(pageId) ?: return "Default (Use Global)"
    return try {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (_: Exception) { "Default (Use Global)" }
}

internal fun SettingsActivity.showPageIconPackPicker(pageId: String, displayName: String) {
    val packs = iconPackManager.getAvailableIconPacks()
    val names = mutableListOf("Default (Use Global)")
    val packages = mutableListOf<String?>(null)
    for ((pkg, label) in packs) {
        names.add(label)
        packages.add(pkg)
    }

    AlertDialog.Builder(this, dialogTheme)
        .setTitle("$displayName Icon Pack")
        .setItems(names.toTypedArray()) { _, which ->
            val selectedPkg = packages[which]
            prefs.setPageIconPackPackage(pageId, selectedPkg)
            rebuildSettings()
        }
        .show()
}
internal fun pageName(pageId: String) = when (pageId) {
    "frequent" -> "Frequent"; "favorites" -> "Favorites"; "all" -> "All"
    else -> pageId.removePrefix("custom_")
}

/** Summary for a page set stored with the "__all__" sentinel (list view pages, sort pages). */
internal fun SettingsActivity.pageSetLabel(pages: Set<String>): String {
    if (pages.isEmpty() || pages.contains("__all__")) return "Everywhere"
    val pageOrder = prefs.getPageOrder()
    val names = pages.filter { pageOrder.contains(it) }.map { pageName(it) }
    return if (names.isEmpty()) "Everywhere" else names.joinToString(", ")
}

internal fun SettingsActivity.getListViewAppliesLabel() = pageSetLabel(prefs.getListViewPages())

internal fun SettingsActivity.getSortAppliesLabel() = pageSetLabel(prefs.getSortApplyPages())

internal fun SettingsActivity.showListViewPagesPicker() =
    showPageSetPicker("List View Applies To", prefs.getListViewPages()) { prefs.setListViewPages(it) }

internal fun SettingsActivity.showSortPagesPicker() =
    showPageSetPicker("Sort Applies To", prefs.getSortApplyPages()) { prefs.setSortApplyPages(it) }

private fun SettingsActivity.showPageSetPicker(title: String, currentPages: Set<String>, onSave: (Set<String>) -> Unit) {
    val pageOrder = prefs.getPageOrder()
    val isEverywhere = currentPages.isEmpty() || currentPages.contains("__all__")

    val options = mutableListOf("Everywhere")
    val pageIds = mutableListOf("__all__")
    for (pid in pageOrder) {
        pageIds.add(pid)
        options.add(pageName(pid))
    }

    val checked = BooleanArray(options.size) { i ->
        if (i == 0) isEverywhere
        else !isEverywhere && currentPages.contains(pageIds[i])
    }

    var dialog: AlertDialog? = null
    dialog = AlertDialog.Builder(this, dialogTheme)
        .setTitle(title)
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
            onSave(result)
            rebuildSettings()
        }
        .setNegativeButton("Cancel", null)
        .show()
}
