package com.meowgi.launcher710.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchNavigationTest {

    // Dial row, two suggestions, then a 4-column grid of 6 results.
    private val rows = listOf(NavRow(1, 0), NavRow(1, 1), NavRow(1, 2), NavRow(4, -1), NavRow(2, -1))
    private fun move(from: NavPos?, dRow: Int = 0, dCol: Int = 0) = SearchNavigation.move(rows, from, dRow, dCol)

    @Test
    fun downFromTheFieldEntersTheFirstRowAndUpFromItLeaves() {
        assertEquals(NavPos(0, 0), move(null, dRow = 1))
        assertNull(move(NavPos(0, 0), dRow = -1))
        assertNull("up from the field stays in the field", move(null, dRow = -1))
        assertNull("left/right from the field don't enter the rows", move(null, dCol = 1))
        assertNull("nothing to enter", SearchNavigation.move(emptyList(), null, 1, 0))
    }

    @Test
    fun rowsAreWalkedInOrderAndTheLastRowStays() {
        var p: NavPos? = null
        val seen = mutableListOf<NavPos?>()
        repeat(6) { p = move(p, dRow = 1); seen += p }
        assertEquals(listOf(NavPos(0, 0), NavPos(1, 0), NavPos(2, 0), NavPos(3, 0), NavPos(4, 0), NavPos(4, 0)), seen)
    }

    @Test
    fun columnClampsOnShorterRows() {
        assertEquals(NavPos(4, 1), move(NavPos(3, 3), dRow = 1))
        assertEquals(NavPos(2, 0), move(NavPos(3, 3), dRow = -1))
    }

    @Test
    fun leftRightWrapOnlyWithinTheGrid() {
        assertEquals(NavPos(4, 0), move(NavPos(3, 3), dCol = 1))
        assertEquals(NavPos(3, 3), move(NavPos(4, 0), dCol = -1))
        assertEquals("no wrap from the grid into a suggestion", NavPos(3, 0), move(NavPos(3, 0), dCol = -1))
        assertEquals(NavPos(4, 1), move(NavPos(4, 1), dCol = 1))
        assertEquals(NavPos(1, 0), move(NavPos(1, 0), dCol = 1))
    }

    @Test
    fun staleSelectionRestartsFromTheField() {
        assertEquals(NavPos(0, 0), move(NavPos(9, 0), dRow = 1))
        assertNull(move(NavPos(9, 0), dCol = 1))
    }

    @Test
    fun trackballAccumulatesToWholeStepsAndResetsOnReversal() {
        val steps = mutableListOf<Pair<Int, Int>>()
        val nav = TrackballNavigator({ r, c -> steps += r to c }, {})
        nav.onMotion(0f, 0.4f); nav.onMotion(0f, 0.4f)
        assertEquals(emptyList<Pair<Int, Int>>(), steps)
        nav.onMotion(0f, 0.4f)
        assertEquals(listOf(1 to 0), steps)
        nav.onMotion(0f, -0.9f)
        assertEquals("reversal drops the leftover instead of cancelling against it", listOf(1 to 0), steps)
        nav.onMotion(0f, -0.2f)
        assertEquals(listOf(1 to 0, -1 to 0), steps)
        nav.onMotion(2.5f, 0.3f)
        assertEquals(listOf(1 to 0, -1 to 0, 0 to 1, 0 to 1), steps)
        nav.onMotion(0.6f, 0f)
        assertEquals("the x remainder carries in the same direction", 5, steps.size)
    }
}
