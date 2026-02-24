package com.meowgi.launcher710.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.meowgi.launcher710.R
import com.meowgi.launcher710.util.LauncherPrefs

class ResizableWidgetFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val widgetId: Int = 0,
    private var currentHeight: Int = 0,
    private val snapDp: Int = 8,
    private val prefs: LauncherPrefs? = null,
    private val onHeightChanged: ((Int) -> Unit)? = null
) : FrameLayout(context, attrs) {

    var onRemoveWidget: (() -> Unit)? = null
    var isResizeMode = false
        private set

    private val minHeightPx = dp(48)
    private val maxHeightPx = dp(600)
    private val handleSize = dp(24)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.resources.getColor(R.color.bb_tab_active, null)
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
    }

    private var activeEdge = Edge.NONE
    private var dragStartY = 0f
    private var dragStartHeight = 0

    private val removeBtn: ImageView

    enum class Edge { NONE, TOP, BOTTOM }

    init {
        setWillNotDraw(true)

        removeBtn = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(context.resources.getColor(R.color.bb_text_primary, null))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            visibility = View.GONE
            setOnClickListener { onRemoveWidget?.invoke() }
        }
        val removeLp = LayoutParams(dp(36), dp(36)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(4)
            marginEnd = dp(4)
        }
        addView(removeBtn, removeLp)
    }

    fun enterResizeMode() {
        isResizeMode = true
        setWillNotDraw(false)
        removeBtn.visibility = View.VISIBLE
        invalidate()
    }

    fun exitResizeMode() {
        isResizeMode = false
        setWillNotDraw(true)
        removeBtn.visibility = View.GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isResizeMode) {
            val r = RectF(borderPaint.strokeWidth, borderPaint.strokeWidth,
                width - borderPaint.strokeWidth, height - borderPaint.strokeWidth)
            canvas.drawRect(r, borderPaint)
            val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = context.resources.getColor(R.color.bb_tab_active, null)
                style = Paint.Style.FILL
            }
            val hw = dp(20).toFloat()
            val hh = dp(4).toFloat()
            canvas.drawRoundRect(RectF(width / 2f - hw, 0f, width / 2f + hw, hh * 2), hh, hh, handlePaint)
            canvas.drawRoundRect(RectF(width / 2f - hw, height - hh * 2, width / 2f + hw, height.toFloat()), hh, hh, handlePaint)
        }
    }

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var scrolling = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = ev.x
                touchStartY = ev.y
                scrolling = false
                if (isResizeMode) {
                    activeEdge = getEdge(ev.y)
                    if (activeEdge != Edge.NONE) {
                        dragStartY = ev.rawY
                        dragStartHeight = height
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = kotlin.math.abs(ev.y - touchStartY)
                val dx = kotlin.math.abs(ev.x - touchStartX)
                if (!scrolling && dy > dp(8) && dy > dx) {
                    scrolling = true
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isResizeMode || activeEdge == Edge.NONE) return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - dragStartY
                val newH = when (activeEdge) {
                    Edge.BOTTOM -> dragStartHeight + dy.toInt()
                    Edge.TOP -> dragStartHeight - dy.toInt()
                    else -> dragStartHeight
                }
                val snapped = snap(newH).coerceIn(minHeightPx, maxHeightPx)
                val lp = layoutParams
                lp.height = snapped
                layoutParams = lp
                requestLayout()
                onHeightChanged?.invoke(snapped)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeEdge = Edge.NONE
            }
        }
        return true
    }

    private fun getEdge(y: Float): Edge {
        if (y < handleSize) return Edge.TOP
        if (y > height - handleSize) return Edge.BOTTOM
        return Edge.NONE
    }

    private fun snap(px: Int): Int {
        val step = dp(snapDp)
        return ((px + step / 2) / step) * step
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
