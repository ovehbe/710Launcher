package com.meowgi.launcher710.ui.dialogs

import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import com.meowgi.launcher710.R
import com.meowgi.launcher710.util.LauncherPrefs

class SoundProfileOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onDismissed: (() -> Unit)? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val prefs = LauncherPrefs(context)
    private val font: Typeface? = ResourcesCompat.getFont(context, R.font.bbalphas)

    private val container: LinearLayout

    init {
        updateBackground()
        isFocusable = true
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setOnClickListener { hide() }

        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = true
            setOnClickListener { /* consume so root doesn't close */ }
        }
        addView(container)
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        if (visibility != VISIBLE) return super.focusSearch(focused, direction)
        val next = FocusFinder.getInstance().findNextFocus(this, focused, direction)
        return next ?: focused
    }

    override fun addFocusables(views: ArrayList<View>?, direction: Int, focusableMode: Int) {
        if (visibility == VISIBLE) {
            super.addFocusables(views, direction, focusableMode)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return false
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (visibility == VISIBLE) {
            if (event?.action == MotionEvent.ACTION_UP) hide()
            return true
        }
        return super.onTouchEvent(event)
    }

    fun show() {
        visibility = VISIBLE
        requestFocus()
        refresh()
        container.post { container.getChildAt(0)?.requestFocus() }
    }

    fun hide() {
        visibility = GONE
        onDismissed?.invoke()
    }

    fun applyOpacity(alpha: Int) {
        updateBackground(alpha)
    }

    private fun updateBackground(alpha: Int = prefs.soundProfileOverlayAlpha) {
        val color = if (prefs.soundProfileOverlayUseDefaultBackground) {
            Color.argb(alpha, 0, 0, 0)
        } else {
            val c = prefs.soundProfileOverlayCustomBackgroundColor
            Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
        }
        setBackgroundColor(color)
    }

    private fun refresh() {
        container.removeAllViews()

        val currentMode = audioManager.ringerMode
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val curPct = if (maxVol > 0) curVol.toFloat() / maxVol else 0f
        val isDnd = isDndActive()

        val isRingMuted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            audioManager.isStreamMute(AudioManager.STREAM_RING)
        val activeVolume = if (currentMode == AudioManager.RINGER_MODE_NORMAL && !isRingMuted) {
            listOf(1.0f to "Loud", 0.7f to "Normal", 0.2f to "Low")
                .minByOrNull { kotlin.math.abs(it.first - curPct) }?.second
        } else null

        val isSilent = (currentMode == AudioManager.RINGER_MODE_NORMAL && isRingMuted) ||
            (currentMode == AudioManager.RINGER_MODE_SILENT && !isDnd)

        val rowParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )

        container.addView(makeRow("Loud", activeVolume == "Loud", R.drawable.ic_sound_loud_custom) { applyVolume(1.0f) }, rowParams)
        container.addView(makeDivider())
        container.addView(makeRow("Normal", activeVolume == "Normal", R.drawable.ic_sound_normal_custom) { applyVolume(0.7f) }, rowParams)
        container.addView(makeDivider())
        container.addView(makeRow("Low", activeVolume == "Low", R.drawable.ic_sound_low_custom) { applyVolume(0.2f) }, rowParams)
        container.addView(makeDivider())
        container.addView(makeRow("Silent", isSilent, R.drawable.ic_sound_silent_custom) { applySilent() }, rowParams)
        container.addView(makeDivider())
        container.addView(makeRow("Vibrate Only", currentMode == AudioManager.RINGER_MODE_VIBRATE, R.drawable.ic_sound_vibrate_custom) { applyVibrate() }, rowParams)
        container.addView(makeDivider())
        container.addView(makeRow("All Alerts Off", currentMode == AudioManager.RINGER_MODE_SILENT && isDnd, R.drawable.ic_sound_alerts_off_custom) { applyAlertsOff() }, rowParams)

        val focusableRows = mutableListOf<View>()
        for (i in 0 until container.childCount step 2) {
            val row = container.getChildAt(i)
            row.id = View.generateViewId()
            row.isFocusable = true
            row.isFocusableInTouchMode = false
            row.isClickable = true
            focusableRows.add(row)
        }

        for (i in focusableRows.indices) {
            val curr = focusableRows[i]
            val next = focusableRows[(i + 1) % focusableRows.size]
            val prev = focusableRows[(i - 1 + focusableRows.size) % focusableRows.size]
            curr.nextFocusDownId = next.id
            curr.nextFocusUpId = prev.id
            curr.nextFocusLeftId = curr.id
            curr.nextFocusRightId = curr.id
        }
    }

    private fun makeRow(label: String, isSelected: Boolean, iconRes: Int, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            if (isSelected) {
                val baseColor = if (prefs.soundProfileHighlightUseAccent) prefs.accentColor else prefs.soundProfileHighlightCustomColor
                val alpha = prefs.soundProfileHighlightAlpha
                val lighter = Color.argb(
                    alpha,
                    kotlin.math.min(255, (Color.red(baseColor) * 1.15).toInt()),
                    kotlin.math.min(255, (Color.green(baseColor) * 1.15).toInt()),
                    kotlin.math.min(255, (Color.blue(baseColor) * 1.15).toInt())
                )
                val darker = Color.argb(
                    alpha,
                    (Color.red(baseColor) * 0.78).toInt(),
                    (Color.green(baseColor) * 0.78).toInt(),
                    (Color.blue(baseColor) * 0.78).toInt()
                )
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(lighter, darker)
                )
            }
            setOnClickListener {
                onClick()
            }
        }

        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(2), dp(2), dp(8), dp(2))
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(32), dp(28)))

        val tv = TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(context.getColor(R.color.bb_text_primary))
            typeface = font
        }
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (isSelected) {
            val check = TextView(context).apply {
                text = "✓"
                textSize = 18f
                setTextColor(context.getColor(R.color.bb_text_primary))
            }
            row.addView(check)
        }

        return row
    }

    private fun makeDivider(): View {
        val d = View(context).apply {
            setBackgroundColor(context.getColor(R.color.bb_divider))
        }
        d.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
        return d
    }

    private fun isDndActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val filter = notificationManager?.currentInterruptionFilter
                ?: NotificationManager.INTERRUPTION_FILTER_ALL
            filter != NotificationManager.INTERRUPTION_FILTER_ALL
        } catch (_: Exception) { false }
    }

    private fun applyVolume(pct: Float) {
        try {
            disableDndIfActive()
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
            }
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            audioManager.setStreamVolume(
                AudioManager.STREAM_RING, (max * pct).toInt().coerceAtLeast(1), 0
            )
        } catch (_: Exception) {}
        hide()
    }

    /** Silent = mute ring stream only; does not use RINGER_MODE_SILENT so DND is not triggered. */
    private fun applySilent() {
        try {
            disableDndIfActive()
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            } else {
                audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            }
        } catch (_: Exception) {}
        hide()
    }

    private fun applyVibrate() {
        try {
            disableDndIfActive()
            audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        } catch (_: Exception) {}
        hide()
    }

    private fun applyAlertsOff() {
        try {
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        } catch (_: Exception) {}
        enableDnd()
        hide()
    }

    private fun enableDnd() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val nm = notificationManager ?: return
        if (!nm.isNotificationPolicyAccessGranted) {
            try {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (_: Exception) {}
            return
        }
        try { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE) } catch (_: Exception) {}
    }

    private fun disableDndIfActive() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (!isDndActive()) return
        val nm = notificationManager ?: return
        if (!nm.isNotificationPolicyAccessGranted) return
        try { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) } catch (_: Exception) {}
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
