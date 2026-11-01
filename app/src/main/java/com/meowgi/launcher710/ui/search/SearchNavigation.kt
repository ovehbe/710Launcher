package com.meowgi.launcher710.ui.search

import android.view.MotionEvent

/** Row/column of the highlighted search target; rows run top to bottom in on-screen order. */
data class NavPos(val row: Int, val col: Int)

/**
 * A navigable row. Rows sharing a [group] are one wrapped grid (the results), so left/right
 * continue onto the neighbouring row; every other row needs a group of its own.
 */
data class NavRow(val size: Int, val group: Int)

object SearchNavigation {

    /**
     * Moves the highlight by [dRow] rows or [dCol] columns. Null means no highlight, i.e. the
     * search field: down from it enters the first row, up from the first row returns to it.
     * Moving past the last row or a row's ends (outside a wrapped grid) stays put.
     */
    fun move(rows: List<NavRow>, from: NavPos?, dRow: Int, dCol: Int): NavPos? {
        if (from == null || from.row !in rows.indices) {
            return if (dRow > 0 && rows.isNotEmpty()) NavPos(0, 0) else null
        }
        if (dRow != 0) {
            val row = from.row + dRow
            if (row < 0) return null
            if (row >= rows.size) return from
            return NavPos(row, from.col.coerceAtMost(rows[row].size - 1))
        }
        if (dCol == 0) return from
        val col = from.col + dCol
        if (col in 0 until rows[from.row].size) return NavPos(from.row, col)
        val next = from.row + dCol.coerceIn(-1, 1)
        if (next !in rows.indices || rows[next].group != rows[from.row].group) return from
        return NavPos(next, if (dCol < 0) rows[next].size - 1 else 0)
    }
}

/**
 * Turns trackball motion into single row/column steps. Trackball coordinates are relative and
 * normalized so that 1.0 is one DPAD press; motion accumulates per axis and resets when the
 * direction or axis changes, so a slight drift doesn't add up into a step.
 */
class TrackballNavigator(
    private val onStep: (dRow: Int, dCol: Int) -> Unit,
    private val onClick: () -> Unit
) {
    private var accX = 0f
    private var accY = 0f
    /** A press was consumed, so its release must be too, even if search closed in between. */
    var pressed = false
        private set

    fun onEvent(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                reset()
                onClick()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressed = false
            MotionEvent.ACTION_MOVE -> {
                var dx = ev.x
                var dy = ev.y
                for (i in 0 until ev.historySize) {
                    dx += ev.getHistoricalX(i)
                    dy += ev.getHistoricalY(i)
                }
                onMotion(dx, dy)
            }
        }
    }

    fun onMotion(dx: Float, dy: Float) {
        if (kotlin.math.abs(dy) >= kotlin.math.abs(dx)) {
            accX = 0f
            if (dy != 0f && kotlin.math.sign(dy) != kotlin.math.sign(accY)) accY = 0f
            accY += dy
            while (kotlin.math.abs(accY) >= 1f) {
                val step = if (accY > 0) 1 else -1
                accY -= step
                onStep(step, 0)
            }
        } else {
            accY = 0f
            if (kotlin.math.sign(dx) != kotlin.math.sign(accX)) accX = 0f
            accX += dx
            while (kotlin.math.abs(accX) >= 1f) {
                val step = if (accX > 0) 1 else -1
                accX -= step
                onStep(0, step)
            }
        }
    }

    fun reset() {
        accX = 0f
        accY = 0f
    }
}
