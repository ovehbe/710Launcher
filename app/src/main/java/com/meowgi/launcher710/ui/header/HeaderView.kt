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
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import java.util.*

class HeaderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    val dateText: TextView
    val clockText: TextView
    private val font: Typeface? = ResourcesCompat.getFont(context, R.font.bbalphas)
    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val ticker = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, msToNextMinute(System.currentTimeMillis()))
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(4))

        dateText = TextView(context).apply {
            id = R.id.header_date_text
            textSize = 14f
            setTextColor(resources.getColor(R.color.bb_text_primary, null))
            typeface = font
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        addView(dateText, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        clockText = TextView(context).apply {
            id = R.id.header_clock_text
            textSize = 38f
            setTextColor(resources.getColor(R.color.bb_text_primary, null))
            typeface = font
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            letterSpacing = 0.05f
        }
        addView(clockText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    // The next tick is planned for a minute boundary computed once, so a manual time change or a
    // time zone change would leave the clock wrong for up to a minute. Resync on both.
    private var receiverRegistered = false
    private val timeChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            dateFormat.timeZone = TimeZone.getDefault()
            timeFormat.timeZone = TimeZone.getDefault()
            handler.removeCallbacks(ticker)
            ticker.run()
        }
    }

    // Ticks only while the window is visible, so nothing runs once another app is on top.
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        handler.removeCallbacks(ticker)
        if (isVisible) {
            dateFormat.timeZone = TimeZone.getDefault()
            timeFormat.timeZone = TimeZone.getDefault()
            ticker.run()
            if (!receiverRegistered) {
                receiverRegistered = true
                context.registerReceiver(timeChangedReceiver, IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                })
            }
        } else if (receiverRegistered) {
            receiverRegistered = false
            try { context.unregisterReceiver(timeChangedReceiver) } catch (_: Exception) {}
        }
    }

    companion object {
        /** Delay until the next wall-clock minute boundary (the clock only shows HH:mm). */
        fun msToNextMinute(nowMs: Long): Long = 60_000L - nowMs % 60_000L
    }

    private fun updateTime() {
        val now = Date()
        dateText.text = dateFormat.format(now)
        clockText.text = timeFormat.format(now)
    }

    fun applyOpacity(prefs: LauncherPrefs) {
        background?.mutate()?.alpha = prefs.headerAlpha
    }

    fun refresh(prefs: LauncherPrefs) {
        dateText.visibility = if (prefs.headerShowDate) android.view.View.VISIBLE else android.view.View.GONE
        clockText.visibility = if (prefs.headerShowClock) android.view.View.VISIBLE else android.view.View.GONE
        applyOpacity(prefs)
        when (prefs.headerLayout) {
            0 -> {
                removeAllViews()
                val dateLp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
                val spacerLp = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                val clockLp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
                dateText.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                clockText.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                addView(dateText, dateLp)
                addView(android.view.View(context), spacerLp)
                addView(clockText, clockLp)
            }
            1 -> {
                removeAllViews()
                val clockLp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
                val spacerLp = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                val dateLp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
                clockText.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                dateText.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                addView(clockText, clockLp)
                addView(android.view.View(context), spacerLp)
                addView(dateText, dateLp)
            }
            2 -> {
                removeAllViews()
                val centerLp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
                dateText.gravity = Gravity.CENTER
                clockText.gravity = Gravity.CENTER
                addView(dateText, centerLp)
                addView(clockText, centerLp)
                gravity = Gravity.CENTER
            }
            else -> { }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
