package com.meowgi.launcher710.ui.settings

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

internal val ACCENT_PRESETS = listOf(
    "BB Blue" to 0xFF0073BC.toInt(),
    "Teal" to 0xFF00BFA5.toInt(),
    "Red" to 0xFFFF5252.toInt(),
    "Green" to 0xFF4CAF50.toInt(),
    "Purple" to 0xFF9C27B0.toInt(),
    "Orange" to 0xFFFF9800.toInt(),
    "White" to 0xFFFFFFFF.toInt()
)

internal val DOCK_COLOR_PRESETS = listOf(
    "Black" to 0xFF000000.toInt(),
    "Dark Gray" to 0xFF1A1A1A.toInt(),
    "Blue" to 0xFF001A33.toInt(),
    "Navy" to 0xFF0A1628.toInt()
)

internal fun colorHex(color: Int) = "#%06X".format(color and 0xFFFFFF)

/** Shows presets + custom (RGB sliders); calls onColor with 0xFFRRGGBB. */
internal fun SettingsActivity.showColorPicker(initialColor: Int, onColor: (Int) -> Unit) {
    val presets: List<Pair<String, Int?>> = ACCENT_PRESETS + ("Custom…" to null)
    val options = presets.map { it.first }.toTypedArray()
    AlertDialog.Builder(this, dialogTheme)
        .setTitle("Choose color")
        .setItems(options) { _, which ->
            val color = presets[which].second
            if (color != null) {
                onColor(color)
            } else {
                showCustomColorPicker(initialColor, onColor)
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun SettingsActivity.showCustomColorPicker(initialColor: Int, onColor: (Int) -> Unit) {
    var r = Color.red(initialColor)
    var g = Color.green(initialColor)
    var b = Color.blue(initialColor)
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(16))
    }
    val sliders = mutableListOf<Pair<SeekBar, TextView>>()
    fun makeSlider(label: String, value: Int, onChanged: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(makeLabel(label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueText = TextView(this).apply {
            text = value.toString()
            setTextColor(accent)
            textSize = 12f
        }
        header.addView(valueText)
        row.addView(header)
        val seek = SeekBar(this).apply {
            max = 255
            progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    valueText.text = p.toString()
                    if (fromUser) onChanged(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        sliders.add(seek to valueText)
        row.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    val preview = View(this).apply {
        setBackgroundColor(Color.rgb(r, g, b))
        minimumHeight = dp(48)
    }
    val hexEdit = EditText(this).apply {
        setText("#%02X%02X%02X".format(r, g, b))
        hint = "#RRGGBB"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setTextColor(accent)
        textSize = 14f
    }
    val hsvPicker = HsvColorPickerView(this).apply {
        setColor(Color.rgb(r, g, b))
        minimumHeight = dp(220)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
    }
    fun syncFromRgb() {
        preview.setBackgroundColor(Color.rgb(r, g, b))
        hexEdit.setText("#%02X%02X%02X".format(r, g, b))
        if (sliders.size >= 3) {
            sliders[0].first.progress = r
            sliders[0].second.text = r.toString()
            sliders[1].first.progress = g
            sliders[1].second.text = g.toString()
            sliders[2].first.progress = b
            sliders[2].second.text = b.toString()
        }
        hsvPicker.setColor(Color.rgb(r, g, b))
    }
    fun applyHexFromInput() {
        var raw = hexEdit.text.toString().trim()
        if (raw.isNotEmpty() && !raw.startsWith("#")) raw = "#$raw"
        if (raw.length !in 7..9) return
        try {
            val c = Color.parseColor(raw)
            r = Color.red(c)
            g = Color.green(c)
            b = Color.blue(c)
            syncFromRgb()
        } catch (_: Exception) {
            Toast.makeText(this, "Invalid hex", Toast.LENGTH_SHORT).show()
        }
    }

    content.addView(hsvPicker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
    content.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(12) })
    val hexRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(4))
    }
    hexRow.addView(makeLabel("Hex"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    hexRow.addView(hexEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f))
    val applyHexBtn = TextView(this).apply {
        text = "Apply"
        setTextColor(accent)
        textSize = 14f
        setPadding(dp(16), dp(8), dp(8), dp(8))
        setOnClickListener { applyHexFromInput() }
    }
    hexRow.addView(applyHexBtn)
    content.addView(hexRow)
    hexEdit.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) applyHexFromInput() }
    hexEdit.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
            applyHexFromInput()
            hexEdit.clearFocus()
            true
        } else false
    }
    hsvPicker.onColorChanged = { color ->
        r = Color.red(color)
        g = Color.green(color)
        b = Color.blue(color)
        syncFromRgb()
    }
    content.addView(makeSlider("Red", r) { r = it; syncFromRgb() })
    content.addView(makeSlider("Green", g) { g = it; syncFromRgb() })
    content.addView(makeSlider("Blue", b) { b = it; syncFromRgb() })
    syncFromRgb()

    AlertDialog.Builder(this, dialogTheme)
        .setTitle("Custom color")
        .setView(content)
        .setPositiveButton("OK") { _, _ -> onColor(Color.rgb(r, g, b)) }
        .setNegativeButton("Cancel", null)
        .show()
}
