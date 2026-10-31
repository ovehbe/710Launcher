package com.meowgi.launcher710.util

import com.meowgi.launcher710.util.SwipeDownGesture.Result.REJECTED
import com.meowgi.launcher710.util.SwipeDownGesture.Result.TRIGGERED
import com.meowgi.launcher710.util.SwipeDownGesture.Result.UNDECIDED
import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeDownGestureTest {

    private val slop = 16f
    private val trigger = 128f
    private val longPress = 400L

    private fun gesture() = SwipeDownGesture(
        touchSlop = slop,
        triggerDistance = trigger,
        flingDistance = 48f,
        minFlingVelocity = 1200f,
        longPressTimeoutMs = longPress
    ).apply { down(100f, 300f, 0L) }

    @Test
    fun steadyDownwardDragTriggersPastTheThreshold() {
        val g = gesture()
        assertEquals(UNDECIDED, g.move(100f, 310f, 16))
        assertEquals(UNDECIDED, g.move(102f, 360f, 32))
        assertEquals(UNDECIDED, g.move(104f, 420f, 48))
        assertEquals(TRIGGERED, g.move(106f, 300f + trigger, 64))
    }

    @Test
    fun slowDragStillTriggersOnceItLeftTheSlopEarly() {
        val g = gesture()
        assertEquals(UNDECIDED, g.move(100f, 330f, 100))
        assertEquals(TRIGGERED, g.move(100f, 450f, 1500))
    }

    @Test
    fun horizontalSwipeIsRejected() {
        val g = gesture()
        assertEquals(REJECTED, g.move(140f, 310f, 16))
        assertEquals("stays rejected", REJECTED, g.move(140f, 600f, 32))
    }

    @Test
    fun diagonalGestureIsRejected() {
        val g = gesture()
        assertEquals(REJECTED, g.move(130f, 325f, 16))
    }

    @Test
    fun gestureThatTurnsHorizontalIsRejected() {
        val g = gesture()
        assertEquals(UNDECIDED, g.move(100f, 340f, 16))
        assertEquals(REJECTED, g.move(200f, 380f, 32))
    }

    @Test
    fun steepButNotVerticalEnoughDoesNotTrigger() {
        val g = gesture()
        assertEquals(UNDECIDED, g.move(110f, 340f, 16))
        // dy = 140 past the threshold, but |dx| = 80 is more than half of it.
        assertEquals(UNDECIDED, g.move(180f, 440f, 48))
        assertEquals(REJECTED, g.up(180f, 440f, 64, velocityY = 5000f))
    }

    @Test
    fun upwardSwipeIsRejected() {
        val g = gesture()
        assertEquals(REJECTED, g.move(100f, 270f, 16))
        assertEquals(REJECTED, g.move(100f, 600f, 32))
    }

    @Test
    fun longPressThenDragIsRejected() {
        val g = gesture()
        assertEquals(UNDECIDED, g.move(101f, 302f, 200))
        assertEquals("finger held inside the slop past the long-press timeout", REJECTED, g.move(101f, 303f, longPress + 1))
        assertEquals(REJECTED, g.move(100f, 600f, longPress + 100))
    }

    @Test
    fun tapIsNotASwipe() {
        val g = gesture()
        assertEquals(REJECTED, g.up(101f, 302f, 80, velocityY = 0f))
    }

    @Test
    fun shortFastFlingTriggersOnUp() {
        val g = gesture()
        assertEquals(UNDECIDED, g.move(100f, 330f, 16))
        assertEquals(TRIGGERED, g.up(101f, 360f, 40, velocityY = 3000f))
    }

    @Test
    fun shortSlowDragDoesNotTriggerOnUp() {
        val g = gesture()
        assertEquals(UNDECIDED, g.move(100f, 330f, 16))
        assertEquals(REJECTED, g.up(100f, 360f, 300, velocityY = 200f))
    }

    @Test
    fun flingTooShortDoesNotTrigger() {
        val g = gesture()
        assertEquals(UNDECIDED, g.move(100f, 320f, 16))
        assertEquals(REJECTED, g.up(100f, 330f, 24, velocityY = 5000f))
    }

    @Test
    fun downResetsState() {
        val g = gesture()
        assertEquals(REJECTED, g.move(200f, 300f, 16))
        g.down(100f, 300f, 1000L)
        assertEquals(TRIGGERED, g.move(100f, 300f + trigger, 1064L))
    }
}
