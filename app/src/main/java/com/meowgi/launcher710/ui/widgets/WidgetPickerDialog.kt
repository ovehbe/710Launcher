package com.meowgi.launcher710.ui.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.meowgi.launcher710.R

class WidgetPickerDialog(
    private val activity: Activity,
    private val onWidgetSelected: (AppWidgetProviderInfo) -> Unit
) {

    fun show() {
        val awm = AppWidgetManager.getInstance(activity)
        val widgets = awm.installedProviders
        val font = ResourcesCompat.getFont(activity, R.font.bbalphas)

        val grouped = widgets.groupBy { it.provider.packageName }
            .toSortedMap(compareBy { getAppName(activity, it) })

        val scroll = ScrollView(activity)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        for ((pkg, widgetList) in grouped) {
            val appName = getAppName(activity, pkg)

            val header = TextView(activity).apply {
                text = appName
                setTextColor(activity.getColor(R.color.bb_tab_active))
                textSize = 13f
                typeface = font?.let { Typeface.create(it, Typeface.BOLD) }
                setPadding(dp(4), dp(10), dp(4), dp(4))
            }
            layout.addView(header)

            for (info in widgetList) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setBackgroundResource(android.R.drawable.list_selector_background)
                }

                val icon = ImageView(activity).apply {
                    val d = info.loadIcon(activity, activity.resources.displayMetrics.densityDpi)
                    setImageDrawable(d)
                    setPadding(dp(2), dp(2), dp(8), dp(2))
                }
                row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))

                val labelLayout = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val label = TextView(activity).apply {
                    text = info.loadLabel(activity.packageManager)
                    setTextColor(activity.getColor(R.color.bb_text_primary))
                    textSize = 13f
                    typeface = font
                }
                labelLayout.addView(label)

                val desc = TextView(activity).apply {
                    text = "${info.minWidth}x${info.minHeight}"
                    setTextColor(activity.getColor(R.color.bb_text_dim))
                    textSize = 10f
                    typeface = font
                }
                labelLayout.addView(desc)

                row.addView(labelLayout, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                row.setOnClickListener {
                    onWidgetSelected(info)
                }
                layout.addView(row)

                val divider = View(activity).apply {
                    setBackgroundColor(activity.getColor(R.color.bb_divider))
                }
                layout.addView(divider, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            }
        }

        scroll.addView(layout)

        AlertDialog.Builder(activity, R.style.BBDialogTheme)
            .setTitle("Add Widget")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getAppName(context: Context, packageName: String): String {
        return try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (_: Exception) { packageName }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
