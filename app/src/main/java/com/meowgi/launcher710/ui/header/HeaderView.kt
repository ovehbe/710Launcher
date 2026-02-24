package com.meowgi.launcher710.ui.header

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.meowgi.launcher710.R
import com.meowgi.launcher710.util.LauncherPrefs
import java.text.SimpleDateFormat
import java.util.*

class HeaderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val dateText: TextView
    private val clockText: TextView
    private val font: Typeface? = ResourcesCompat.getFont(context, R.font.bbalphas)
    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val ticker = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, 1000)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(4))

        dateText = TextView(context).apply {
            textSize = 14f
            setTextColor(resources.getColor(R.color.bb_text_primary, null))
            typeface = font
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        addView(dateText, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        clockText = TextView(context).apply {
            textSize = 38f
            setTextColor(resources.getColor(R.color.bb_text_primary, null))
            typeface = font
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            letterSpacing = 0.05f
        }
        addView(clockText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ticker.run()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(ticker)
    }

    private fun updateTime() {
        val now = Date()
        dateText.text = dateFormat.format(now)
        clockText.text = timeFormat.format(now)
    }

    fun applyOpacity() {
        background?.alpha = LauncherPrefs(context).headerAlpha
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
