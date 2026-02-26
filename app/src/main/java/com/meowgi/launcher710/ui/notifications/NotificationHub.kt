package com.meowgi.launcher710.ui.notifications

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.content.Intent
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import com.meowgi.launcher710.R
import com.meowgi.launcher710.util.LauncherPrefs

class NotificationHub @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val scrollView: ScrollView
    private val container: LinearLayout
    private val emptyText: TextView
    private val font: Typeface? = ResourcesCompat.getFont(context, R.font.bbalphas)
    private val prefs = LauncherPrefs(context)

    init {
        setBackgroundColor(resources.getColor(R.color.bb_overlay_dark, null))

        emptyText = TextView(context).apply {
            text = context.getString(R.string.no_notifications)
            textSize = 18f
            setTextColor(resources.getColor(R.color.bb_notification_empty, null))
            typeface = font
            gravity = Gravity.CENTER
        }
        addView(emptyText, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })

        scrollView = ScrollView(context).apply {
            isClickable = true
            setOnClickListener { /* consume so root doesn't close */ }
        }
        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        scrollView.addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        setOnClickListener { hide() }
    }

    fun show(appWhitelist: Set<String> = emptySet()) {
        visibility = VISIBLE
        refresh(appWhitelist)
    }

    fun hide() {
        visibility = GONE
    }

    /** Exclude system, ongoing/sticky, and silent-style notifications. */
    private fun shouldShowNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == "android") return false
        val n = sbn.notification
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) return false
        return true
    }

    /** @param appWhitelist only show these packages; empty = show all */
    fun refresh(appWhitelist: Set<String> = emptySet()) {
        container.removeAllViews()
        val service = NotifListenerService.instance
        var notifs = service?.getNotifications() ?: emptyList()
        notifs = notifs.filter { shouldShowNotification(it) }
        if (appWhitelist.isNotEmpty()) notifs = notifs.filter { it.packageName in appWhitelist }
        val filtered = notifs.filter { it.notification.extras.getCharSequence("android.title") != null }

        if (filtered.isEmpty()) {
            emptyText.visibility = VISIBLE
            scrollView.visibility = GONE
        } else {
            emptyText.visibility = GONE
            scrollView.visibility = VISIBLE

            val primaryColor = resources.getColor(R.color.bb_text_primary, null)
            val secondaryColor = resources.getColor(R.color.bb_text_secondary, null)
            val dimColor = resources.getColor(R.color.bb_text_dim, null)
            val borderColor = resources.getColor(R.color.bb_divider, null)

            for (sbn in filtered) {
                val extras = sbn.notification.extras
                val title = extras.getCharSequence("android.title")?.toString() ?: ""
                val text = extras.getCharSequence("android.text")?.toString() ?: ""
                val appName = try {
                    context.packageManager.getApplicationLabel(
                        context.packageManager.getApplicationInfo(sbn.packageName, 0)
                    ).toString()
                } catch (_: Exception) { sbn.packageName }

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    setBackgroundColor(resources.getColor(R.color.bb_overlay, null))
                    setOnClickListener {
                        var opened = false
                        try {
                            val intent = sbn.notification.contentIntent
                            if (intent != null) {
                                intent.send(context, 0, null, null, null)
                                opened = true
                            }
                        } catch (_: Exception) { }
                        if (!opened) {
                            try {
                                val launch = context.packageManager.getLaunchIntentForPackage(sbn.packageName)
                                if (launch != null) {
                                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(launch)
                                }
                            } catch (_: Exception) { }
                        }
                        hide()
                    }
                }

                val leftBorder = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        marginEnd = dp(8)
                    }
                    setBackgroundColor(borderColor)
                }
                row.addView(leftBorder)

                val appIcon = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) }
                    try {
                        setImageDrawable(context.packageManager.getApplicationIcon(sbn.packageName))
                    } catch (_: Exception) { setImageResource(android.R.drawable.sym_def_app_icon) }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                row.addView(appIcon)

                val textColumn = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val appLabel = TextView(context).apply {
                    this.text = appName
                    textSize = 11f
                    setTextColor(dimColor)
                    typeface = font
                }
                textColumn.addView(appLabel)

                val titleLabel = TextView(context).apply {
                    this.text = title
                    textSize = 14f
                    setTextColor(primaryColor)
                    typeface = font?.let { Typeface.create(it, Typeface.BOLD) }
                }
                textColumn.addView(titleLabel)

                if (text.isNotBlank()) {
                    val bodyLabel = TextView(context).apply {
                        this.text = text
                        textSize = 12f
                        setTextColor(secondaryColor)
                        typeface = font
                        maxLines = 2
                    }
                    textColumn.addView(bodyLabel)
                }

                row.addView(textColumn)

                container.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(2) })
            }

            val clearBtn = TextView(context).apply {
                text = context.getString(R.string.clear_all)
                textSize = 11f
                setTextColor(dimColor)
                typeface = font
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    NotifListenerService.instance?.dismissAllNotifications()
                    refresh(appWhitelist)
                }
            }
            container.addView(clearBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); marginStart = dp(4) })
        }
    }

    fun applyOpacity(alpha: Int) {
        setBackgroundColor(android.graphics.Color.argb(alpha, 0, 0, 0))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
