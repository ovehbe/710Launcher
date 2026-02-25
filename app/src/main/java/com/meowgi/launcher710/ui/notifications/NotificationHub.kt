package com.meowgi.launcher710.ui.notifications

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import com.meowgi.launcher710.R

class NotificationHub @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val scrollView: ScrollView
    private val container: LinearLayout
    private val emptyText: TextView
    private val font: Typeface? = ResourcesCompat.getFont(context, R.font.bbalphas)

    init {
        setBackgroundColor(resources.getColor(R.color.bb_overlay_dark, null))

        emptyText = TextView(context).apply {
            text = context.getString(R.string.no_notifications)
            textSize = 16f
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

    /** @param appWhitelist only show these packages; empty = show all */
    fun refresh(appWhitelist: Set<String> = emptySet()) {
        container.removeAllViews()
        val service = NotifListenerService.instance
        var notifs = service?.getNotifications() ?: emptyList()
        if (appWhitelist.isNotEmpty()) notifs = notifs.filter { it.packageName in appWhitelist }
        val filtered = notifs.filter { it.notification.extras.getCharSequence("android.title") != null }

        if (filtered.isEmpty()) {
            emptyText.visibility = VISIBLE
            scrollView.visibility = GONE
        } else {
            emptyText.visibility = GONE
            scrollView.visibility = VISIBLE

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
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
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

                val appLabel = TextView(context).apply {
                    this.text = appName
                    textSize = 11f
                    setTextColor(resources.getColor(R.color.bb_text_dim, null))
                    typeface = font
                }
                row.addView(appLabel)

                val titleLabel = TextView(context).apply {
                    this.text = title
                    textSize = 13f
                    setTextColor(resources.getColor(R.color.bb_text_primary, null))
                    typeface = font?.let { Typeface.create(it, Typeface.BOLD) }
                }
                row.addView(titleLabel)

                if (text.isNotBlank()) {
                    val bodyLabel = TextView(context).apply {
                        this.text = text
                        textSize = 12f
                        setTextColor(resources.getColor(R.color.bb_text_secondary, null))
                        typeface = font
                        maxLines = 2
                    }
                    row.addView(bodyLabel)
                }

                container.addView(row)

                val divider = android.view.View(context).apply {
                    setBackgroundColor(resources.getColor(R.color.bb_divider, null))
                }
                container.addView(divider, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                ))
            }

            val clearDivider = android.view.View(context).apply {
                setBackgroundColor(resources.getColor(R.color.bb_divider, null))
            }
            container.addView(clearDivider, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ))
            val clearRow = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener {
                    NotifListenerService.instance?.dismissAllNotifications()
                    refresh(appWhitelist)
                }
            }
            val clearLabel = TextView(context).apply {
                text = context.getString(R.string.clear_all)
                textSize = 13f
                setTextColor(resources.getColor(R.color.bb_text_primary, null))
                typeface = font?.let { Typeface.create(it, Typeface.BOLD) }
            }
            clearRow.addView(clearLabel)
            container.addView(clearRow)
        }
    }

    fun applyOpacity(alpha: Int) {
        setBackgroundColor(android.graphics.Color.argb(alpha, 0, 0, 0))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
