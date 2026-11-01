package com.meowgi.launcher710.util

import com.meowgi.launcher710.util.NotificationBadgeCounter.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationBadgeCounterTest {
    private fun e(pkg: String, group: String? = null, summary: Boolean = false, ongoing: Boolean = false, number: Int = 0) =
        Entry(pkg, group, summary, ongoing, number)

    @Test
    fun countsNotificationsPerPackage() {
        val r = NotificationBadgeCounter.count(listOf(e("a"), e("a"), e("b")))
        assertEquals(2, r["a"]); assertEquals(1, r["b"])
    }

    @Test
    fun ongoingNeverBadges() {
        assertNull(NotificationBadgeCounter.count(listOf(e("player", ongoing = true)))["player"])
    }

    @Test
    fun summaryWithChildrenIsNotCountedTwice() {
        val r = NotificationBadgeCounter.count(listOf(e("a", "g", summary = true), e("a", "g"), e("a", "g")))
        assertEquals(2, r["a"])
    }

    @Test
    fun lonelySummaryCounts() {
        assertEquals(1, NotificationBadgeCounter.count(listOf(e("a", "g", summary = true)))["a"])
    }

    @Test
    fun summaryNumberSurvivesWhenChildrenReportNone() {
        val r = NotificationBadgeCounter.count(listOf(e("mail", "g", summary = true, number = 12), e("mail", "g"), e("mail", "g"), e("mail", "g")))
        assertEquals(12, r["mail"])
    }

    @Test
    fun reportedNumberWinsWhenLarger() {
        assertEquals(5, NotificationBadgeCounter.count(listOf(e("mail", number = 5)))["mail"])
    }
}
