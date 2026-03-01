package com.meowgi.launcher710.ui.dialogs

import android.app.Activity
import android.graphics.drawable.Drawable
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
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

        val searchInput = EditText(activity).apply {
            hint = "Search icons"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val scroll = ScrollView(activity)
        val grid = GridLayout(activity).apply {
            columnCount = 5
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        var dialog: AlertDialog? = null
        val iconSize = dp(48)
        val margin = dp(4)

        val iconViews = mutableListOf<Pair<String, ImageView>>()
        for (name in names) {
            val icon = tempManager.getIconByName(name) ?: continue
            val iv = ImageView(activity).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
                tag = name
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
            iconViews.add(name to iv)
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = (s?.toString() ?: "").trim().lowercase()
                for ((name, iv) in iconViews) {
                    iv.visibility = if (query.isEmpty() || name.lowercase().contains(query)) {
                        android.view.View.VISIBLE
                    } else {
                        android.view.View.GONE
                    }
                }
            }
        })

        scroll.addView(grid)

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchInput)
            addView(scroll)
        }

        dialog = AlertDialog.Builder(activity, R.style.BBDialogTheme)
            .setTitle("Choose Icon")
            .setView(content)
            .setNeutralButton("Reset to Default") { _, _ -> onReset() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
