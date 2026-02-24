package com.meowgi.launcher710.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import com.meowgi.launcher710.R

class SoundProfileDialog(context: Context) : Dialog(context, R.style.BBDialogTheme) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val font: Typeface? = ResourcesCompat.getFont(context, R.font.bbalphas)

    data class Profile(val name: String, val ringerMode: Int, val volumePct: Float)

    private val profiles = listOf(
        Profile("All Alerts Off", AudioManager.RINGER_MODE_SILENT, 0f),
        Profile("Normal", AudioManager.RINGER_MODE_NORMAL, 0.7f),
        Profile("Loud", AudioManager.RINGER_MODE_NORMAL, 1.0f),
        Profile("Medium", AudioManager.RINGER_MODE_NORMAL, 0.5f),
        Profile("Silent", AudioManager.RINGER_MODE_SILENT, 0f),
        Profile("Vibrate Only", AudioManager.RINGER_MODE_VIBRATE, 0f)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val title = TextView(context).apply {
            text = context.getString(R.string.profile_title)
            textSize = 16f
            setTextColor(context.getColor(R.color.bb_text_primary))
            typeface = font?.let { Typeface.create(it, Typeface.BOLD) }
            setPadding(0, 0, 0, dp(8))
        }
        layout.addView(title)

        val currentMode = audioManager.ringerMode
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val curPct = if (maxVol > 0) curVol.toFloat() / maxVol else 0f

        for (profile in profiles) {
            val isSelected = isProfileActive(profile, currentMode, curPct)
            val row = makeProfileRow(profile, isSelected)
            layout.addView(row)
        }

        val divider = android.view.View(context).apply {
            setBackgroundColor(context.getColor(R.color.bb_divider))
        }
        layout.addView(divider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })

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

    private fun isProfileActive(profile: Profile, mode: Int, volPct: Float): Boolean {
        if (profile.ringerMode != mode) return false
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            return kotlin.math.abs(profile.volumePct - volPct) < 0.2f
        }
        return true
    }

    private fun makeProfileRow(profile: Profile, isSelected: Boolean): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            if (isSelected) setBackgroundResource(R.drawable.profile_item_selected)

            val icon = ImageView(context).apply {
                val res = when {
                    profile.ringerMode == AudioManager.RINGER_MODE_VIBRATE ->
                        android.R.drawable.ic_lock_silent_mode
                    profile.ringerMode == AudioManager.RINGER_MODE_SILENT ->
                        android.R.drawable.ic_lock_silent_mode
                    else -> android.R.drawable.ic_lock_silent_mode_off
                }
                setImageResource(res)
                setPadding(dp(2), dp(2), dp(8), dp(2))
            }
            addView(icon, LinearLayout.LayoutParams(dp(32), dp(28)))

            val label = TextView(context).apply {
                text = profile.name
                textSize = 14f
                setTextColor(context.getColor(R.color.bb_text_primary))
                typeface = font
            }
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (isSelected) {
                val check = TextView(context).apply {
                    text = "✓"
                    textSize = 18f
                    setTextColor(context.getColor(R.color.bb_text_primary))
                }
                addView(check)
            }

            setOnClickListener { applyProfile(profile) }
        }
    }

    private fun applyProfile(profile: Profile) {
        try {
            audioManager.ringerMode = profile.ringerMode
            if (profile.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
                audioManager.setStreamVolume(
                    AudioManager.STREAM_RING,
                    (max * profile.volumePct).toInt().coerceAtLeast(1),
                    0
                )
            }
        } catch (_: Exception) {}
        dismiss()
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
