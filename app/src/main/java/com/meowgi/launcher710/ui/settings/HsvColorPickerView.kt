package com.meowgi.launcher710.ui.settings

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A simple HSV color picker: horizontal hue bar + saturation/value square.
 * Touch the hue bar to set hue; touch the square to set saturation and value.
 * Call [setColor] to update from external RGB (e.g. from sliders or hex).
 */
class HsvColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onColorChanged: ((Int) -> Unit)? = null

    private val hsv = floatArrayOf(0f, 0f, 1f) // H 0-360, S 0-1, V 0-1
    private var hueBarHeightPx = 0
    private var hueBarTop = 0
    private var svLeft = 0
    private var svTop = 0
    private var svWidth = 0
    private var svHeight = 0

    private val hueBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val svPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }
    private val thumbFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    private var svBitmap: Bitmap? = null
    private var cachedHueForBitmap = -1f
    private var cachedSvW = 0
    private var cachedSvH = 0

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, thumbPaint)
    }

    /** Set current color from RGB (e.g. 0xFFRRGGBB). Updates hue/sat/value and redraws. */
    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        invalidateSvBitmap()
        invalidate()
    }

    /** Get current color as 0xFFRRGGBB. */
    fun getColor(): Int = Color.HSVToColor(hsv)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        hueBarHeightPx = (24 * resources.displayMetrics.density).toInt().coerceAtLeast(20)
        hueBarTop = 0
        svLeft = paddingLeft
        svTop = paddingTop + hueBarHeightPx + (8 * resources.displayMetrics.density).toInt()
        val maxSide = minOf(w - paddingLeft - paddingRight, h - (svTop - paddingTop) - paddingBottom)
        svWidth = maxSide.coerceAtLeast(1)
        svHeight = maxSide.coerceAtLeast(1)
        invalidateSvBitmap()
    }

    private fun invalidateSvBitmap() {
        svBitmap?.recycle()
        svBitmap = null
        cachedHueForBitmap = -1f
        cachedSvW = 0
        cachedSvH = 0
    }

    private fun ensureSvBitmap(): Bitmap? {
        if (svWidth <= 0 || svHeight <= 0) return null
        if (svBitmap != null && cachedHueForBitmap == hsv[0] && cachedSvW == svWidth && cachedSvH == svHeight) {
            return svBitmap
        }
        svBitmap?.recycle()
        val bmp = Bitmap.createBitmap(svWidth, svHeight, Bitmap.Config.ARGB_8888)
        val h = hsv[0]
        val arr = FloatArray(3)
        for (py in 0 until svHeight) {
            val v = 1f - py.toFloat() / svHeight
            for (px in 0 until svWidth) {
                val s = px.toFloat() / svWidth
                arr[0] = h
                arr[1] = s
                arr[2] = v
                bmp.setPixel(px, py, Color.HSVToColor(arr))
            }
        }
        svBitmap = bmp
        cachedHueForBitmap = h
        cachedSvW = svWidth
        cachedSvH = svHeight
        return bmp
    }

    override fun onDraw(canvas: Canvas) {
        // Hue bar gradient: R -> Y -> G -> C -> B -> M -> R
        if (hueBarHeightPx > 0 && width > 0) {
            val left = paddingLeft.toFloat()
            val right = (width - paddingRight).toFloat()
            val top = hueBarTop.toFloat()
            val bottom = (hueBarTop + hueBarHeightPx).toFloat()
            val hueGradient = LinearGradient(
                left, 0f, right, 0f,
                intArrayOf(
                    Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
                ),
                floatArrayOf(0f, 1f / 6, 2f / 6, 3f / 6, 4f / 6, 5f / 6, 1f),
                Shader.TileMode.CLAMP
            )
            hueBarPaint.shader = hueGradient
            canvas.drawRect(left, top, right, bottom, hueBarPaint)
            hueBarPaint.shader = null
            // Hue thumb
            val hueX = left + (hsv[0] / 360f) * (right - left)
            canvas.drawCircle(hueX, (top + bottom) / 2f, 12f, thumbFillPaint)
            canvas.drawCircle(hueX, (top + bottom) / 2f, 12f, thumbPaint)
        }

        // Saturation / Value square
        val bmp = ensureSvBitmap()
        if (bmp != null && svWidth > 0 && svHeight > 0) {
            canvas.drawBitmap(bmp, svLeft.toFloat(), svTop.toFloat(), svPaint)
            val sx = svLeft + hsv[1] * svWidth
            val sy = svTop + (1f - hsv[2]) * svHeight
            canvas.drawCircle(sx, sy, 14f, thumbFillPaint)
            canvas.drawCircle(sx, sy, 14f, thumbPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN && event.action != MotionEvent.ACTION_MOVE) {
            return super.onTouchEvent(event)
        }
        val x = event.x
        val y = event.y
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val hueBarBottom = (hueBarTop + hueBarHeightPx).toFloat()

        if (y >= hueBarTop && y <= hueBarBottom) {
            val hue = ((x - left) / (right - left)).coerceIn(0f, 1f) * 360f
            hsv[0] = hue
            invalidateSvBitmap()
            invalidate()
            onColorChanged?.invoke(Color.HSVToColor(hsv))
            return true
        }
        if (y >= svTop && y < svTop + svHeight && x >= svLeft && x < svLeft + svWidth) {
            val s = ((x - svLeft) / svWidth).coerceIn(0f, 1f)
            val v = 1f - ((y - svTop) / svHeight).coerceIn(0f, 1f)
            hsv[1] = s
            hsv[2] = v
            invalidate()
            onColorChanged?.invoke(Color.HSVToColor(hsv))
            return true
        }
        return super.onTouchEvent(event)
    }
}
