package com.meowgi.launcher710.ui.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.meowgi.launcher710.R

/**
 * Building blocks for settings screens. Rows go into the card opened by the enclosing [group];
 * every row shows a title plus a one-line summary of its current value.
 */

internal fun SettingsActivity.group(title: String?, footer: String? = null, rows: () -> Unit) {
    val header = title?.let {
        TextView(this).apply {
            text = it.uppercase()
            setTextColor(accent)
            textSize = 12f
            letterSpacing = 0.08f
            typeface = font?.let { f -> Typeface.create(f, Typeface.BOLD) }
            setPadding(dp(28), dp(20), dp(28), dp(8))
        }
    }
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(getColor(R.color.settings_card))
            cornerRadius = dp(14).toFloat()
        }
        clipToOutline = true
    }
    val previous = rowTarget
    rowTarget = card
    try {
        rows()
    } finally {
        rowTarget = previous
    }
    if (card.childCount == 0) return
    header?.let { container.addView(it) }
    container.addView(card, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        marginStart = dp(12)
        marginEnd = dp(12)
        if (header == null) topMargin = dp(16)
    })
    footer?.let {
        container.addView(TextView(this).apply {
            text = it
            setTextColor(getColor(R.color.settings_summary))
            textSize = 12f
            typeface = font
            setPadding(dp(28), dp(8), dp(28), 0)
        })
    }
}

private fun SettingsActivity.addRow(row: View) {
    val target = rowTarget ?: container
    if (target !== container && target.childCount > 0) {
        target.addView(View(this).apply { setBackgroundColor(getColor(R.color.settings_card_divider)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(16) })
    }
    target.addView(row)
}

private fun SettingsActivity.baseRow(title: String, summary: CharSequence?, danger: Boolean = false): View {
    val row = layoutInflater.inflate(R.layout.item_setting, rowTarget ?: container, false)
    row.findViewById<TextView>(R.id.settingTitle).apply {
        text = title
        if (danger) setTextColor(getColor(R.color.settings_danger))
    }
    row.setSummary(summary)
    return row
}

private fun View.setSummary(summary: CharSequence?) {
    findViewById<TextView>(R.id.settingSubtitle).apply {
        text = summary
        visibility = if (summary.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
}

private fun View.setWidget(widget: View) {
    findViewById<FrameLayout>(R.id.settingWidget).apply {
        removeAllViews()
        addView(widget)
        visibility = View.VISIBLE
    }
}

private fun SettingsActivity.chevron() = TextView(this).apply {
    text = "›"
    textSize = 22f
    setTextColor(getColor(R.color.settings_summary))
}

private fun SettingsActivity.swatch(color: Int) = View(this).apply {
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1), 0x66FFFFFF)
    }
    layoutParams = FrameLayout.LayoutParams(dp(22), dp(22))
}

private fun SettingsActivity.tintSeekBar(seek: SeekBar) {
    val tint = ColorStateList.valueOf(accent)
    seek.progressTintList = tint
    seek.thumbTintList = tint
    seek.progressBackgroundTintList = ColorStateList.valueOf(0xFF4A4A4D.toInt())
}

/** Entry on the home screen that opens a category. */
internal fun SettingsActivity.categoryRow(screen: SettingsScreen) {
    val row = baseRow(screen.title, screen.summary)
    row.setWidget(chevron())
    row.setOnClickListener { showScreen(screen) }
    addRow(row)
}

internal fun SettingsActivity.toggleRow(title: String, checked: Boolean, summary: String? = null, onChanged: (Boolean) -> Unit) {
    val row = baseRow(title, summary)
    val toggle = SwitchCompat(this).apply {
        isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(accent, 0xFFBDBDBD.toInt()))
        trackTintList = ColorStateList(states, intArrayOf((accent and 0x00FFFFFF) or 0x80000000.toInt(), 0xFF4A4A4D.toInt()))
        setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
    }
    row.setWidget(toggle)
    row.setOnClickListener { toggle.toggle() }
    addRow(row)
}

/** Single-choice row; the summary is the selected option. */
internal fun SettingsActivity.choiceRow(title: String, options: List<String>, selected: Int, onPicked: (Int) -> Unit) {
    var current = selected
    val row = baseRow(title, options.getOrElse(current) { options.firstOrNull() })
    row.setOnClickListener {
        AlertDialog.Builder(this, dialogTheme)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), if (current in options.indices) current else -1) { dialog, which ->
                dialog.dismiss()
                current = which
                row.setSummary(options[which])
                onPicked(which)
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }
    addRow(row)
}

/** Opens a dialog or sub-flow; the summary describes the current value. */
internal fun SettingsActivity.actionRow(
    title: String,
    summary: String? = null,
    danger: Boolean = false,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    val row = baseRow(title, summary, danger)
    if (showChevron) row.setWidget(chevron())
    row.setOnClickListener { onClick() }
    addRow(row)
}

internal fun SettingsActivity.infoRow(title: String, value: String) {
    val row = baseRow(title, value)
    row.isClickable = false
    row.background = null
    addRow(row)
}

internal fun SettingsActivity.noteRow(text: String) {
    addRow(TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.settings_summary))
        textSize = 12f
        typeface = font
        setPadding(dp(16), dp(12), dp(16), dp(12))
    })
}

/** "Default vs custom color" row, e.g. "Use accent" / "Choose color…". */
internal fun SettingsActivity.colorChoiceRow(
    title: String,
    defaultLabel: String,
    defaultColor: Int,
    useDefault: Boolean,
    customColor: Int,
    setUseDefault: (Boolean) -> Unit,
    setCustomColor: (Int) -> Unit
) {
    val row = baseRow(title, if (useDefault) defaultLabel else "Custom · ${colorHex(customColor)}")
    row.setWidget(swatch(if (useDefault) defaultColor else customColor))
    row.setOnClickListener {
        AlertDialog.Builder(this, dialogTheme)
            .setTitle(title)
            .setSingleChoiceItems(arrayOf(defaultLabel, "Choose color…"), if (useDefault) 0 else 1) { dialog, which ->
                dialog.dismiss()
                setUseDefault(which == 0)
                if (which == 1) showColorPicker(customColor) { setCustomColor(it); rebuildSettings() }
                rebuildSettings()
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }
    addRow(row)
}

/** Named color presets plus "Custom…", which opens the color picker. */
internal fun SettingsActivity.presetColorRow(
    title: String,
    presets: List<Pair<String, Int>>,
    current: Int,
    customLabel: String,
    onColor: (Int) -> Unit
) {
    val presetIndex = presets.indexOfFirst { it.second == current }
    val row = baseRow(title, if (presetIndex >= 0) presets[presetIndex].first else "Custom · ${colorHex(current)}")
    row.setWidget(swatch(current))
    val options = presets.map { it.first } + customLabel
    row.setOnClickListener {
        AlertDialog.Builder(this, dialogTheme)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), if (presetIndex >= 0) presetIndex else presets.size) { dialog, which ->
                dialog.dismiss()
                if (which == presets.size) {
                    showColorPicker(current) { onColor(it); rebuildSettings() }
                } else {
                    onColor(presets[which].second)
                    rebuildSettings()
                }
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }
    addRow(row)
}

/** Opacity slider over 0–255, shown as a percentage. */
internal fun SettingsActivity.sliderRow(title: String, current: Int, onChanged: (Int) -> Unit) =
    rangeRow(title, 0, 255, current, { "${(it * 100) / 255}%" }, onChanged)

internal fun SettingsActivity.rangeRow(title: String, min: Int, max: Int, current: Int, format: (Int) -> String, onChanged: (Int) -> Unit) {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, dp(6))
    }
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(16), 0)
    }
    val label = makeLabel(title).apply { textSize = 15f }
    val valueText = TextView(this).apply {
        text = format(current.coerceIn(min, max))
        setTextColor(accent)
        textSize = 13f
        typeface = font
    }
    header.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    header.addView(valueText)
    row.addView(header)
    val slider = SeekBar(this).apply {
        this.max = max - min
        progress = current.coerceIn(min, max) - min
        tintSeekBar(this)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = min + progress
                valueText.text = format(v)
                if (fromUser) onChanged(v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }
    row.addView(slider, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(4) })
    addRow(row)
}

/** Integer entered in a dialog; invalid input saves [fallback], out-of-range input is clamped. */
internal fun SettingsActivity.numberRow(
    title: String,
    current: Int,
    min: Int,
    max: Int,
    fallback: Int,
    unit: String,
    hint: String,
    onSave: (Int) -> Unit
) {
    val row = baseRow(title, "$current $unit")
    row.setOnClickListener {
        val input = EditText(this).apply {
            setText(current.toString())
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER
            setSelectAllOnFocus(true)
            setTextColor(Color.WHITE)
        }
        val frame = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this, dialogTheme)
            .setTitle(title)
            .setView(frame)
            .setPositiveButton("Save") { _, _ ->
                onSave(input.text.toString().toIntOrNull()?.coerceIn(min, max) ?: fallback)
                rebuildSettings()
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
        input.requestFocus()
    }
    addRow(row)
}
