package com.meowgi.launcher710.ui.dialogs

import android.app.Activity
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.LinearLayout
import android.view.Gravity
import androidx.appcompat.app.AlertDialog
import com.meowgi.launcher710.R
import com.meowgi.launcher710.util.IconPackManager

class IconPickerDialog(
    private val activity: Activity,
    private val onIconSelected: (String, String?) -> Unit, // drawableName, packPackage
    private val onReset: () -> Unit
) {

    fun showPackPicker() {
        val tempManager = IconPackManager(activity)
        val packs = tempManager.getAvailableIconPacks()
        if (packs.isEmpty()) {
            AlertDialog.Builder(activity, R.style.BBDialogTheme)
                .setTitle("Change Icon")
                .setMessage("No icon packs installed. Install an icon pack first.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val names = mutableListOf("Default (System)")
        val packages = mutableListOf<String?>(null)
        for ((pkg, label) in packs) {
            names.add(label)
            packages.add(pkg)
        }

        AlertDialog.Builder(activity, R.style.BBDialogTheme)
            .setTitle("Choose Icon Pack")
            .setItems(names.toTypedArray()) { _, which ->
                val selectedPkg = packages[which]
                if (selectedPkg != null) {
                    show(selectedPkg)
                } else {
                    AlertDialog.Builder(activity, R.style.BBDialogTheme)
                        .setTitle("Change Icon")
                        .setMessage("Cannot change icon without an icon pack.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun show(packPackage: String?) {
        if (packPackage == null) {
            AlertDialog.Builder(activity, R.style.BBDialogTheme)
                .setTitle("Change Icon")
                .setMessage("Load an icon pack first in Settings.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val tempManager = IconPackManager(activity)
        if (!tempManager.loadIconPack(packPackage)) {
            AlertDialog.Builder(activity, R.style.BBDialogTheme)
                .setTitle("Change Icon")
                .setMessage("Failed to load icon pack.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val names = tempManager.getAllIconNames()
        if (names.isEmpty()) {
            AlertDialog.Builder(activity, R.style.BBDialogTheme)
                .setTitle("Change Icon")
                .setMessage("No icons found in the selected icon pack.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val scroll = ScrollView(activity)
        val grid = GridLayout(activity).apply {
            columnCount = 5
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        var dialog: AlertDialog? = null
        val iconSize = dp(48)
        val margin = dp(4)

        for (name in names) {
            val icon = tempManager.getIconByName(name) ?: continue
            val iv = ImageView(activity).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener {
                    onIconSelected(name, packPackage)
                    dialog?.dismiss()
                }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = iconSize
                height = iconSize
                setMargins(margin, margin, margin, margin)
            }
            grid.addView(iv, lp)
        }

        scroll.addView(grid)

        dialog = AlertDialog.Builder(activity, R.style.BBDialogTheme)
            .setTitle("Choose Icon")
            .setView(scroll)
            .setNeutralButton("Reset to Default") { _, _ -> onReset() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
