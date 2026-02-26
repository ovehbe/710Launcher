package com.meowgi.launcher710.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.meowgi.launcher710.R
import com.meowgi.launcher710.util.LauncherPrefs

class ResizableWidgetFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val widgetId: Int = 0,
    private var currentWidth: Int = 0,
    private var currentHeight: Int = 0,
    private val snapDp: Int = 8,
    private val prefs: LauncherPrefs? = null,
    private val onSizeChanged: ((Int, Int) -> Unit)? = null,
    private val onResizeEnd: ((Int, Int) -> Unit)? = null
) : FrameLayout(context, attrs) {

    var onRemoveWidget: (() -> Unit)? = null
    /** Called when user drags from center in resize mode to reorder: -1 = move up, 1 = move down */
    var onReorderRequest: ((Int) -> Unit)? = null
    var isResizeMode = false
        private set

    private val minHeightPx = dp(48)
    private val maxHeightPx = dp(600)
    private val minWidthPx = dp(80)
    private val maxWidthPx = context.resources.displayMetrics.widthPixels
    private val handleSize = dp(28)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = prefs?.accentColor ?: context.resources.getColor(R.color.bb_tab_active, null)
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
    }

    private var activeEdge = Edge.NONE
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartWidth = 0
    private var dragStartHeight = 0

    private val removeBar: View

    enum class Edge { NONE, TOP, BOTTOM, LEFT, RIGHT }

    init {
        setWillNotDraw(true)

        removeBar = TextView(context).apply {
            text = "Long-press to remove widget"
            setTextColor(context.resources.getColor(R.color.bb_text_primary, null))
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            gravity = Gravity.CENTER
            setBackgroundColor((prefs?.accentColor ?: context.resources.getColor(R.color.bb_tab_active, null)).let { c ->
                android.graphics.Color.argb(180, android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
            })
            visibility = View.GONE
            setOnLongClickListener { onRemoveWidget?.invoke(); true }
        }
        addView(removeBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply {
            gravity = Gravity.TOP
        })
    }

    fun enterResizeMode() {
        isResizeMode = true
        setWillNotDraw(false)
        removeBar.visibility = View.VISIBLE
        invalidate()
    }

    fun exitResizeMode() {
        isResizeMode = false
        setWillNotDraw(true)
        removeBar.visibility = View.GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isResizeMode) {
            val r = RectF(borderPaint.strokeWidth, borderPaint.strokeWidth,
                width - borderPaint.strokeWidth, height - borderPaint.strokeWidth)
            canvas.drawRect(r, borderPaint)
            val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = prefs?.accentColor ?: context.resources.getColor(R.color.bb_tab_active, null)
                style = Paint.Style.FILL
            }
            val hw = dp(24).toFloat()
            val hh = dp(4).toFloat()
            // Top
            canvas.drawRoundRect(RectF(width / 2f - hw, 0f, width / 2f + hw, hh * 2), hh, hh, handlePaint)
            // Bottom
            canvas.drawRoundRect(RectF(width / 2f - hw, height - hh * 2, width / 2f + hw, height.toFloat()), hh, hh, handlePaint)
            // Left
            canvas.drawRoundRect(RectF(0f, height / 2f - hw, hh * 2, height / 2f + hw), hh, hh, handlePaint)
            // Right
            canvas.drawRoundRect(RectF(width - hh * 2, height / 2f - hw, width.toFloat(), height / 2f + hw), hh, hh, handlePaint)
        }
    }

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var scrolling = false
    private var reorderDragAccum = 0f
    private val reorderThresholdPx = dp(40)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = ev.x
                touchStartY = ev.y
                scrolling = false
                reorderDragAccum = 0f
                if (isResizeMode) {
                    val hitRect = Rect()
                    removeBar.getHitRect(hitRect)
                    if (hitRect.contains(ev.x.toInt(), ev.y.toInt())) {
                        return false
                    }
                    activeEdge = getEdge(ev.x, ev.y)
                    dragStartX = ev.rawX
                    dragStartY = ev.rawY
                    dragStartWidth = width
                    dragStartHeight = height
                    return true
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
        if (!isResizeMode) return super.onTouchEvent(event)
        if (activeEdge != Edge.NONE) {
            when (event.action) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    val dy = event.rawY - dragStartY
                    var newW = dragStartWidth
                    var newH = dragStartHeight
                    when (activeEdge) {
                        Edge.RIGHT -> newW = dragStartWidth + dx.toInt()
                        Edge.LEFT -> newW = dragStartWidth - dx.toInt()
                        Edge.BOTTOM -> newH = dragStartHeight + dy.toInt()
                        Edge.TOP -> newH = dragStartHeight - dy.toInt()
                        else -> {}
                    }
                    newW = snap(newW).coerceIn(minWidthPx, maxWidthPx)
                    newH = snap(newH).coerceIn(minHeightPx, maxHeightPx)
                    val lp = layoutParams
                    lp.width = newW
                    lp.height = newH
                    layoutParams = lp
                    requestLayout()
                    onSizeChanged?.invoke(newW, newH)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val lp = layoutParams
                    if (lp != null) onResizeEnd?.invoke(lp.width, lp.height)
                    activeEdge = Edge.NONE
                }
            }
            return true
        }
        // Center drag: reorder
        when (event.action) {
            MotionEvent.ACTION_DOWN -> touchStartY = event.rawY
            MotionEvent.ACTION_MOVE -> {
                reorderDragAccum += event.rawY - touchStartY
                touchStartY = event.rawY
                if (reorderDragAccum >= reorderThresholdPx) {
                    onReorderRequest?.invoke(1)
                    reorderDragAccum = 0f
                } else if (reorderDragAccum <= -reorderThresholdPx) {
                    onReorderRequest?.invoke(-1)
                    reorderDragAccum = 0f
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reorderDragAccum = 0f
        }
        return true
    }

    private fun getEdge(x: Float, y: Float): Edge {
        if (y < handleSize) return Edge.TOP
        if (y > height - handleSize) return Edge.BOTTOM
        if (x < handleSize) return Edge.LEFT
        if (x > width - handleSize) return Edge.RIGHT
        return Edge.NONE
    }

    private fun snap(px: Int): Int {
        val step = dp(snapDp)
        return ((px + step / 2) / step) * step
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
