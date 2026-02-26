package com.meowgi.launcher710.ui.dialogs

import android.app.Dialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import com.meowgi.launcher710.R

class SoundProfileDialog(context: Context) : Dialog(context, R.style.BBDialogTheme) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val font: Typeface? = ResourcesCompat.getFont(context, R.font.bbalphas)

    var onDismissed: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val currentMode = audioManager.ringerMode
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val curPct = if (maxVol > 0) curVol.toFloat() / maxVol else 0f
        val isDnd = isDndActive()

        // Figure out which single volume row to highlight
        val activeVolume = if (currentMode == AudioManager.RINGER_MODE_NORMAL) {
            listOf(1.0f to "Loud", 0.7f to "Normal", 0.5f to "Medium")
                .minByOrNull { kotlin.math.abs(it.first - curPct) }?.second
        } else null

        // --- Volume section ---
        layout.addView(makeRow("Loud", activeVolume == "Loud",
            R.drawable.ic_sound_normal) { applyVolume(1.0f) })
        layout.addView(makeRow("Normal", activeVolume == "Normal",
            R.drawable.ic_sound_normal) { applyVolume(0.7f) })
        layout.addView(makeRow("Medium", activeVolume == "Medium",
            R.drawable.ic_sound_normal) { applyVolume(0.5f) })

        layout.addView(makeDivider())

        // --- Silent / Vibrate ---
        layout.addView(makeRow("Silent",
            currentMode == AudioManager.RINGER_MODE_SILENT && !isDnd,
            R.drawable.ic_sound_silent) { applySilent() })
        layout.addView(makeRow("Vibrate Only",
            currentMode == AudioManager.RINGER_MODE_VIBRATE,
            R.drawable.ic_sound_vibrate) { applyVibrate() })

        layout.addView(makeDivider())

        // --- All Alerts Off / Do Not Disturb ---
        layout.addView(makeRow("All Alerts Off",
            currentMode == AudioManager.RINGER_MODE_SILENT && isDnd,
            R.drawable.ic_sound_alerts_off) { applyAlertsOff() })

        layout.addView(makeDivider())

        val changeBtn = TextView(context).apply {
            text = context.getString(R.string.profile_change_sounds)
            textSize = 14f
            setTextColor(context.getColor(R.color.bb_text_primary))
            typeface = font
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.tab_active_bg)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener {
                context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                dismiss()
            }
        }
        layout.addView(changeBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(layout)
        window?.setLayout(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun dismiss() {
        super.dismiss()
        onDismissed?.invoke()
    }

    private fun isDndActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val filter = notificationManager?.currentInterruptionFilter
                ?: NotificationManager.INTERRUPTION_FILTER_ALL
            filter != NotificationManager.INTERRUPTION_FILTER_ALL
        } catch (_: Exception) { false }
    }

    private fun makeRow(label: String, isSelected: Boolean, iconRes: Int, onClick: () -> Unit): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            if (isSelected) setBackgroundResource(R.drawable.profile_item_selected)

            val icon = ImageView(context).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(2), dp(2), dp(8), dp(2))
            }
            addView(icon, LinearLayout.LayoutParams(dp(32), dp(28)))

            val tv = TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(context.getColor(R.color.bb_text_primary))
                typeface = font
            }
            addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (isSelected) {
                val check = TextView(context).apply {
                    text = "✓"
                    textSize = 18f
                    setTextColor(context.getColor(R.color.bb_text_primary))
                }
                addView(check)
            }

            setOnClickListener { onClick() }
        }
    }

    private fun makeDivider(): View {
        val d = View(context).apply {
            setBackgroundColor(context.getColor(R.color.bb_divider))
        }
        d.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        }
        return d
    }

    /** Set ringer to normal at given volume. Turns off DND if active. */
    private fun applyVolume(pct: Float) {
        try {
            disableDndIfActive()
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            audioManager.setStreamVolume(
                AudioManager.STREAM_RING, (max * pct).toInt().coerceAtLeast(1), 0
            )
        } catch (_: Exception) {}
        dismiss()
    }

    /** Set ringer to silent. Does NOT touch DND. */
    private fun applySilent() {
        try {
            disableDndIfActive()
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        } catch (_: Exception) {}
        dismiss()
    }

    /** Set ringer to vibrate. Does NOT touch DND. */
    private fun applyVibrate() {
        try {
            disableDndIfActive()
            audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        } catch (_: Exception) {}
        dismiss()
    }

    /** Silence everything: silent ringer + DND on. */
    private fun applyAlertsOff() {
        try {
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        } catch (_: Exception) {}
        enableDnd()
        dismiss()
    }

    /** Toggle DND without changing ringer mode. */
    private fun toggleDnd() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) { dismiss(); return }
        val nm = notificationManager ?: run { dismiss(); return }
        if (!nm.isNotificationPolicyAccessGranted) {
            try {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (_: Exception) {}
            dismiss()
            return
        }
        try {
            if (isDndActive()) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            } else {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            }
        } catch (_: Exception) {}
        dismiss()
    }

    private fun enableDnd() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val nm = notificationManager ?: return
        if (!nm.isNotificationPolicyAccessGranted) {
            try {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
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
