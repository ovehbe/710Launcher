package com.meowgi.launcher710.ui.header

import org.junit.Assert.assertEquals
import org.junit.Test

class HeaderViewTest {
    @Test
    fun delayLandsOnTheNextMinuteBoundary() {
        assertEquals(60_000L, HeaderView.msToNextMinute(0))
        assertEquals(1_000L, HeaderView.msToNextMinute(59_000))
        assertEquals(59_999L, HeaderView.msToNextMinute(120_001))
    }
}
