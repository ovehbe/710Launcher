package com.meowgi.launcher710.util

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Decides whether one touch stream is a deliberate downward swipe. Framework-free so it can be
 * unit tested; the caller feeds raw coordinates and decides where a swipe may start at all.
 *
 * The direction is locked once the finger leaves the touch slop: anything mostly horizontal
 * (page swipes, bottom-bar swipes) or upward (dock swipe-up actions) is rejected for the rest of
 * the stream. A finger that stays inside the slop past the long-press timeout is a long press —
 * the start of an icon or widget drag — and is rejected too, so drags never open the shade.
 */
class SwipeDownGesture(
    private val touchSlop: Float,
    private val triggerDistance: Float,
    private val flingDistance: Float,
    private val minFlingVelocity: Float,
    private val longPressTimeoutMs: Long
) {
    enum class Result { UNDECIDED, REJECTED, TRIGGERED }

    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L
    private var pastSlop = false
    private var rejected = true

    fun down(x: Float, y: Float, timeMs: Long) {
        startX = x
        startY = y
        startTime = timeMs
        pastSlop = false
        rejected = false
    }

    fun move(x: Float, y: Float, timeMs: Long): Result {
        if (rejected) return Result.REJECTED
        val dx = x - startX
        val dy = y - startY
        if (!pastSlop) {
            if (hypot(dx, dy) <= touchSlop) {
                return if (timeMs - startTime > longPressTimeoutMs) reject() else Result.UNDECIDED
            }
            pastSlop = true
            if (dy <= 0f || abs(dx) >= dy) return reject()
        }
        if (abs(dx) > dy) return reject()
        if (dy >= triggerDistance && dy >= 2f * abs(dx)) return Result.TRIGGERED
        return Result.UNDECIDED
    }

    /** [velocityY] in px/s, positive downwards. A short, fast flick also counts. */
    fun up(x: Float, y: Float, timeMs: Long, velocityY: Float): Result {
        val r = move(x, y, timeMs)
        if (r != Result.UNDECIDED) return r
        val dx = x - startX
        val dy = y - startY
        val fling = pastSlop && dy >= flingDistance && velocityY >= minFlingVelocity && dy >= 2f * abs(dx)
        rejected = true
        return if (fling) Result.TRIGGERED else Result.REJECTED
    }

    private fun reject(): Result {
        rejected = true
        return Result.REJECTED
    }
}
