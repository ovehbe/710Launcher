package com.meowgi.launcher710.ui.notifications

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.content.Intent
import android.graphics.Typeface
import android.util.AttributeSet
import android.app.ActivityOptions
import android.os.Build
import android.view.FocusFinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import com.meowgi.launcher710.R
import com.meowgi.launcher710.util.LauncherPrefs

class NotificationHub @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onClearAll: (() -> Unit)? = null

    private val scrollView: ScrollView
    private val container: LinearLayout
    private val emptyText: TextView
    private val font: Typeface? = ResourcesCompat.getFont(context, R.font.bbalphas)
    private val prefs = LauncherPrefs(context)

    init {
        setBackgroundColor(resources.getColor(R.color.bb_overlay_dark, null))
        isFocusable = true
        isFocusableInTouchMode = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

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
            isFocusable = false
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

    /**
     * While the hub is visible, keep D-pad / trackpad focus trapped inside it.
     * [FocusFinder] only searches within this ViewGroup; if no next focusable child
     * exists in the requested direction the current focus simply stays put.
     */
    override fun focusSearch(focused: View?, direction: Int): View? {
        if (visibility != VISIBLE) return super.focusSearch(focused, direction)
        val next = FocusFinder.getInstance().findNextFocus(this, focused, direction)
        return next ?: focused
    }

    /**
     * When the hub is visible, only expose our own descendants as focusable so the
     * system-level focus search never jumps to views behind the overlay.
     */
    override fun addFocusables(views: ArrayList<View>?, direction: Int, focusableMode: Int) {
        if (visibility == VISIBLE) {
            super.addFocusables(views, direction, focusableMode)
        }
    }

    /**
     * Intercept touch: if the hub is visible, consume all touches so nothing
     * leaks to views behind the overlay. Children still receive their events
     * normally via [dispatchTouchEvent].
     */
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

    fun show(appWhitelist: Set<String> = emptySet()) {
        visibility = VISIBLE
        requestFocus()
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
                    isFocusable = true
                    isFocusableInTouchMode = false
                    isClickable = true
                    id = View.generateViewId()
                    setOnClickListener {
                        launchNotification(sbn)
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
                textSize = 13f
                setTextColor(dimColor)
                typeface = font
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                isFocusable = true
                isFocusableInTouchMode = false
                isClickable = true
                id = View.generateViewId()
                setOnClickListener {
                    NotifListenerService.instance?.dismissAllNotifications()
                    refresh(appWhitelist)
                    onClearAll?.invoke()
                }
            }
            container.addView(clearBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                gravity = Gravity.CENTER_HORIZONTAL
            })

            chainAndWrapFocus()

            container.post { container.getChildAt(0)?.requestFocus() }
        }
    }

    private fun launchNotification(sbn: StatusBarNotification) {
        val ci = sbn.notification.contentIntent

        // Try the notification's own content intent first — this is the correct way
        // to open a specific chat/screen as set by the originating app.
        if (ci != null) {
            val launched = tryLaunchPendingIntent(ci)
            if (launched) return
        }

        // Fallback: open the app's main activity
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(sbn.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
            }
        } catch (_: Exception) { }
    }

    private fun tryLaunchPendingIntent(ci: android.app.PendingIntent): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+: use ActivityOptions to explicitly permit background start
                val opts = ActivityOptions.makeBasic().apply {
                    @Suppress("NewApi")
                    pendingIntentBackgroundActivityStartMode =
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
                ci.send(context, 0, null, null, null, null, opts.toBundle())
            } else {
                // API < 34: plain send; the launcher is foregrounded so the OS allows it
                ci.send(context, 0, null, null, null)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Build a circular focus chain among all focusable children so DPAD/trackpad
     * cannot escape the overlay. The last item wraps to the first and vice-versa.
     */
    private fun chainAndWrapFocus() {
        val focusable = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filter { it.isFocusable }
        if (focusable.isEmpty()) return

        for (i in focusable.indices) {
            val curr = focusable[i]
            val next = focusable[(i + 1) % focusable.size]
            val prev = focusable[(i - 1 + focusable.size) % focusable.size]
            curr.nextFocusDownId = next.id
            curr.nextFocusUpId = prev.id
            curr.nextFocusLeftId = curr.id
            curr.nextFocusRightId = curr.id
        }
    }

    fun applyOpacity(alpha: Int) {
        setBackgroundColor(android.graphics.Color.argb(alpha, 0, 0, 0))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
