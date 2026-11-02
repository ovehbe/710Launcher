package com.meowgi.launcher710.util

import org.junit.Assert.*
import org.junit.Test

class PlainWordsCommandsTest {

    @Test
    fun timerWithNumericMinutes() {
        val result = PlainWordsCommands.detect("timer 30 min")
        assertNotNull(result)
        assertTrue(result!!.command is PlainWordsCommands.Command.Timer)
        assertEquals(30 * 60, (result.command as PlainWordsCommands.Command.Timer).seconds)
        assertTrue(result.displayText.contains("30m"))
    }

    @Test
    fun timerCompactFormat() {
        val result = PlainWordsCommands.detect("30m timer")
        assertNotNull(result)
        assertTrue(result!!.command is PlainWordsCommands.Command.Timer)
        assertEquals(30 * 60, (result.command as PlainWordsCommands.Command.Timer).seconds)
    }

    @Test
    fun timerWithHours() {
        val result = PlainWordsCommands.detect("timer 2 hours")
        assertNotNull(result)
        assertTrue(result!!.command is PlainWordsCommands.Command.Timer)
        assertEquals(2 * 3600, (result.command as PlainWordsCommands.Command.Timer).seconds)
    }

    @Test
    fun timerMixedUnits() {
        val result = PlainWordsCommands.detect("timer 1h30m")
        assertNotNull(result)
        assertTrue(result!!.command is PlainWordsCommands.Command.Timer)
        assertEquals(90 * 60, (result.command as PlainWordsCommands.Command.Timer).seconds)
    }

    @Test
    fun timerWithNumberWords() {
        val result = PlainWordsCommands.detect("timer one hour thirty minutes")
        assertNotNull(result)
        assertTrue(result!!.command is PlainWordsCommands.Command.Timer)
        assertEquals(90 * 60, (result.command as PlainWordsCommands.Command.Timer).seconds)
    }

    @Test
    fun plainTimerShowsTimersList() {
        val result = PlainWordsCommands.detect("timer")
        assertNotNull(result)
        assertTrue(result!!.command is PlainWordsCommands.Command.ShowTimers)
    }

    @Test
    fun durationParsing() {
        assertEquals(30 * 60, PlainWordsCommands.parseDuration("30 min"))
        assertEquals(90 * 60, PlainWordsCommands.parseDuration("1h30m"))
        assertEquals(2 * 3600, PlainWordsCommands.parseDuration("2 hours"))
        assertEquals(45 * 60, PlainWordsCommands.parseDuration("45 minutes"))
        assertEquals(90 * 60, PlainWordsCommands.parseDuration("one hour thirty minutes"))
    }

    @Test
    fun timeZoneDetection() {
        val result = PlainWordsCommands.detect("time in tokyo")
        assertNotNull(result)
        assertTrue(result!!.command is PlainWordsCommands.Command.TimeZone)
        assertTrue(result.displayText.contains("Tokyo"))
        assertTrue(result.displayText.matches(Regex(".*\\d{2}:\\d{2}.*")))
    }

    @Test
    fun timeZoneVariations() {
        assertNotNull(PlainWordsCommands.detect("tokyo time"))
        assertNotNull(PlainWordsCommands.detect("time in new york"))
        assertNotNull(PlainWordsCommands.detect("london time"))
    }

    @Test
    fun timeZoneAbbreviations() {
        val result = PlainWordsCommands.detect("time in est")
        assertNotNull(result)
        assertTrue(result!!.command is PlainWordsCommands.Command.TimeZone)
    }

    @Test
    fun noteDetection() {
        val result = PlainWordsCommands.detect("note buy milk")
        assertNotNull(result)
        assertTrue(result!!.command is PlainWordsCommands.Command.Note)
        assertEquals("buy milk", (result.command as PlainWordsCommands.Command.Note).text)
    }

    @Test
    fun noteWithBreakMarkers() {
        val result = PlainWordsCommands.detect("note buy milk↵get eggs↵call mom")
        assertNotNull(result)
        val note = result!!.command as PlainWordsCommands.Command.Note
        assertEquals("buy milk↵get eggs↵call mom", note.text)
        assertEquals("buy milk\nget eggs\ncall mom", note.breaksAsNewlines)
    }

    @Test
    fun noteWithSlashBreaks() {
        val result = PlainWordsCommands.detect("note buy milk / get eggs / call mom")
        assertNotNull(result)
        val note = result!!.command as PlainWordsCommands.Command.Note
        assertEquals("buy milk\nget eggs\ncall mom", note.breaksAsNewlines)
    }

    @Test
    fun commandDoesNotHijackNormalSearch() {
        assertNull(PlainWordsCommands.detect("timer app"))
        assertNull(PlainWordsCommands.detect("notepad"))
        assertNull(PlainWordsCommands.detect("notebook"))
    }

    @Test
    fun emptyQueryReturnsNull() {
        assertNull(PlainWordsCommands.detect(""))
        assertNull(PlainWordsCommands.detect("   "))
    }
}
