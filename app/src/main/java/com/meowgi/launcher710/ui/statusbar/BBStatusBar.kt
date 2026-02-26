package com.meowgi.launcher710.ui.statusbar

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.*
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.meowgi.launcher710.R
import com.meowgi.launcher710.util.LauncherPrefs
import java.util.Calendar
import java.util.Locale

class BBStatusBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val prefs = LauncherPrefs(context)
    private val font: Typeface? = ResourcesCompat.getFont(context, R.font.bbalphas)

    private lateinit var leftGroup: LinearLayout
    private lateinit var batteryText: TextView
    private lateinit var btText: TextView
    private lateinit var alarmText: TextView
    private lateinit var dndText: TextView
    private lateinit var centerText: TextView
    private lateinit var networkText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 30_000) // every 30s
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (scale > 0) (level * 100) / scale else 0
            post { updateBattery(pct) }
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(2), dp(8), dp(2))

        leftGroup = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        batteryText = makeText("")
        btText = makeText("BT")
        alarmText = makeText("⏰")
        dndText = makeText("DND")
        leftGroup.addView(batteryText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        leftGroup.addView(btText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        leftGroup.addView(alarmText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        leftGroup.addView(dndText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(leftGroup, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        centerText = makeText("")
        centerText.gravity = Gravity.CENTER
        addView(centerText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 2f))

        networkText = makeText("")
        networkText.gravity = Gravity.END
        addView(networkText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ContextCompat.registerReceiver(
            context, batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        handler.post(clockRunnable)
        refresh()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(clockRunnable)
        try { context.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
    }

    fun refresh() {
        updateBatteryVisibility()
        updateBatteryFromSystem()
        updateBtVisibility()
        updateBt()
        updateAlarmVisibility()
        updateAlarm()
        updateDndVisibility()
        updateDnd()
        updateCenterContent()
        updateNetworkVisibility()
        updateNetwork()
        applyOpacity()
    }

    fun applyOpacity() {
        val alpha = prefs.statusBarAlpha
        background?.alpha = alpha
    }

    private fun updateBatteryVisibility() {
        batteryText.visibility = if (prefs.statusBarShowBattery) VISIBLE else GONE
    }

    private fun updateBatteryFromSystem() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        updateBattery(pct)
    }

    private fun updateBattery(pct: Int) {
        batteryText.text = "$pct%"
        val color = when {
            pct > 50 -> 0xFF4CAF50.toInt()   // green
            pct in 21..50 -> 0xFFFFC107.toInt() // yellow
            else -> 0xFFF44336.toInt()       // red
        }
        batteryText.setTextColor(color)
    }

    private fun updateBtVisibility() {
        btText.visibility = if (prefs.statusBarShowBluetooth) VISIBLE else GONE
    }

    private fun updateBt() {
        val enabled = try {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        } catch (_: Exception) { false }
        btText.visibility = if (prefs.statusBarShowBluetooth && enabled) VISIBLE else if (prefs.statusBarShowBluetooth) INVISIBLE else GONE
    }

    private fun updateAlarmVisibility() {
        alarmText.visibility = if (prefs.statusBarShowAlarm) VISIBLE else GONE
    }

    private fun updateAlarm() {
        var show = false
        if (prefs.statusBarShowAlarm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                val next = am?.nextAlarmClock
                show = next != null
            } catch (_: Exception) {}
        }
        alarmText.visibility = if (prefs.statusBarShowAlarm) if (show) VISIBLE else INVISIBLE else GONE
    }

    private fun updateDndVisibility() {
        dndText.visibility = if (prefs.statusBarShowDND) VISIBLE else GONE
    }

    @SuppressLint("WrongConstant")
    private fun updateDnd() {
        var show = false
        if (prefs.statusBarShowDND && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val filter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
                show = filter == NotificationManager.INTERRUPTION_FILTER_NONE
            } catch (_: Exception) {}
        }
        dndText.visibility = if (prefs.statusBarShowDND) if (show) VISIBLE else INVISIBLE else GONE
    }

    private fun updateCenterContent() {
        if (prefs.statusBarShowClock) {
            updateClock()
            centerText.visibility = VISIBLE
        } else {
            updateCarrier()
            centerText.visibility = VISIBLE
        }
    }

    private fun updateClock() {
        if (!prefs.statusBarShowClock) return
        val time = java.text.SimpleDateFormat("h:mm", Locale.getDefault()).format(Calendar.getInstance().time)
        centerText.text = time
    }

    private fun updateCarrier() {
        if (prefs.statusBarShowClock) return
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val name = tm.networkOperatorName
            centerText.text = if (name.isNullOrBlank()) "No Service" else name
        } catch (_: Exception) {
            centerText.text = "BBOS7"
        }
    }

    private fun updateNetworkVisibility() {
        networkText.visibility = if (prefs.statusBarShowNetwork) VISIBLE else GONE
    }

    private fun updateNetwork() {
        if (!prefs.statusBarShowNetwork) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(net)
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiEnabled = wm.isWifiEnabled

            networkText.text = when {
                caps == null -> {
                    if (wifiEnabled) "WiFi" else ""
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getMobileTypeWithSignal()
                else -> ""
            }
        } catch (_: Exception) {
            networkText.text = ""
        }
    }

    private fun getMobileTypeWithSignal(): String {
        val type = getMobileType()
        val level = getSignalLevel()
        return if (level >= 0) "$type ${level + 1}/4" else type
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

    private fun getSignalLevel(): Int {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            val infos = tm.allCellInfo ?: return -1
            var maxLevel = -1
            for (info in infos) {
                val strength = when (info) {
                    is android.telephony.CellInfoLte -> info.cellSignalStrength?.level ?: -1
                    is android.telephony.CellInfoGsm -> info.cellSignalStrength?.level ?: -1
                    is android.telephony.CellInfoWcdma -> info.cellSignalStrength?.level ?: -1
                    else -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is android.telephony.CellInfoNr)
                            info.cellSignalStrength?.level ?: -1
                        else -1
                    }
                }
                if (strength in 0..4) maxLevel = maxOf(maxLevel, strength)
            }
            maxLevel
        } catch (_: Exception) { -1 }
    }

    private fun makeText(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(resources.getColor(R.color.bb_text_primary, null))
        textSize = 11f
        typeface = font
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
