package com.meowgi.launcher710.ui.statusbar

import android.content.*
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.telephony.*
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.meowgi.launcher710.R
import com.meowgi.launcher710.util.LauncherPrefs

class BBStatusBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private lateinit var batteryText: TextView
    private lateinit var carrierText: TextView
    private lateinit var networkText: TextView
    private val font: Typeface? = ResourcesCompat.getFont(context, R.font.bbalphas)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = (level * 100) / scale
            batteryText.text = "$pct%"
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(2), dp(8), dp(2))

        batteryText = makeText("100%")
        addView(batteryText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        carrierText = makeText("")
        carrierText.gravity = Gravity.CENTER
        addView(carrierText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 2f))

        networkText = makeText("")
        networkText.gravity = Gravity.END
        addView(networkText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        androidx.core.content.ContextCompat.registerReceiver(
            context, batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        updateCarrier()
        updateNetwork()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try { context.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
    }

    fun refresh() {
        updateCarrier()
        updateNetwork()
        applyOpacity()
    }

    fun applyOpacity() {
        val alpha = LauncherPrefs(context).statusBarAlpha
        background?.alpha = alpha
    }

    private fun updateCarrier() {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val name = tm.networkOperatorName
            carrierText.text = if (name.isNullOrBlank()) "No Service" else name
        } catch (_: Exception) {
            carrierText.text = "BBOS7"
        }
    }

    private fun updateNetwork() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(net)
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            networkText.text = when {
                caps == null -> ""
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getMobileType()
                else -> ""
            }
        } catch (_: Exception) {
            networkText.text = ""
        }
    }

    private fun getMobileType(): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return when (tm.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE -> "edge"
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            else -> "3G"
        }
    }

    private fun makeText(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(resources.getColor(R.color.bb_text_primary, null))
        textSize = 11f
        typeface = font
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
