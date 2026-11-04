package com.meowgi.launcher710.util

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Detects and handles plain words commands in search: timer, time zones, quick notes.
 * No prefix needed - commands are suggested when the query reads like one.
 */
object PlainWordsCommands {

    sealed class Command {
        data class Timer(val seconds: Int) : Command()
        object ShowTimers : Command()
        data class TimeZone(val zoneName: String, val zoneId: ZoneId) : Command()
        data class Note(val text: String, val breaksAsNewlines: String) : Command()
    }

    data class CommandResult(
        val command: Command,
        val displayText: String,
        val secondaryText: String? = null
    )

    fun detect(query: String): CommandResult? {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return null
        
        detectTimer(q, query)?.let { return it }
        detectTimeZone(q)?.let { return it }
        detectNote(query)?.let { return it }
        
        return null
    }

    private fun detectTimer(q: String, originalQuery: String): CommandResult? {
        if (!q.contains("timer")) return null
        
        if (q.matches(Regex("^timer\\s*app.*")) || q.matches(Regex(".*\\btimer\\s+app\\b.*"))) {
            return null
        }
        
        val withoutTimer = q.replace("timer", "").trim()
        
        if (withoutTimer.isEmpty() || withoutTimer == "s") {
            return CommandResult(
                Command.ShowTimers,
                "Open timers",
                "Tap to view timers in Clock app"
            )
        }
        
        val seconds = parseDuration(withoutTimer)
        
        if (seconds <= 0) {
            return CommandResult(
                Command.ShowTimers,
                "Open timers",
                "Tap to view timers in Clock app"
            )
        }
        
        val formatted = formatDuration(seconds)
        return CommandResult(
            Command.Timer(seconds),
            "Set timer: $formatted",
            "Tap or press Enter to start"
        )
    }

    fun parseDuration(text: String): Int {
        var totalSeconds = 0
        val cleaned = text.lowercase(Locale.ROOT).trim()
        
        val hourPattern = Regex("(\\d+)\\s*h(?:ours?|rs?)?(?:\\b|(?=[^a-z]))")
        val minutePattern = Regex("(\\d+)\\s*m(?:inutes?|ins?)?(?:\\b|(?=[^a-z]))")
        val secondPattern = Regex("(\\d+)\\s*s(?:econds?|ecs?)?(?:\\b|(?=[^a-z]))")
        
        hourPattern.findAll(cleaned).forEach { match ->
            match.groupValues.getOrNull(1)?.toIntOrNull()?.let {
                totalSeconds += it * 3600
            }
        }
        
        minutePattern.findAll(cleaned).forEach { match ->
            match.groupValues.getOrNull(1)?.toIntOrNull()?.let {
                totalSeconds += it * 60
            }
        }
        
        secondPattern.findAll(cleaned).forEach { match ->
            match.groupValues.getOrNull(1)?.toIntOrNull()?.let {
                totalSeconds += it
            }
        }
        
        if (totalSeconds == 0) {
            val words = cleaned.split(Regex("\\s+"))
            var i = 0
            while (i < words.size) {
                val word = words[i]
                val num = parseNumberWord(word)
                if (num > 0 && i + 1 < words.size) {
                    val unit = words[i + 1]
                    when {
                        unit.startsWith("hour") || unit == "hr" -> {
                            totalSeconds += num * 3600; i += 2
                        }
                        unit.startsWith("minute") || unit == "min" -> {
                            totalSeconds += num * 60; i += 2
                        }
                        unit.startsWith("second") || unit == "sec" -> {
                            totalSeconds += num; i += 2
                        }
                        else -> i++
                    }
                } else {
                    i++
                }
            }
        }
        
        return totalSeconds
    }

    private fun parseNumberWord(word: String): Int = when (word) {
        "one" -> 1; "two" -> 2; "three" -> 3; "four" -> 4; "five" -> 5
        "six" -> 6; "seven" -> 7; "eight" -> 8; "nine" -> 9; "ten" -> 10
        "eleven" -> 11; "twelve" -> 12; "thirteen" -> 13; "fourteen" -> 14; "fifteen" -> 15
        "sixteen" -> 16; "seventeen" -> 17; "eighteen" -> 18; "nineteen" -> 19
        "twenty" -> 20; "thirty" -> 30; "forty" -> 40; "fifty" -> 50; "sixty" -> 60
        else -> word.toIntOrNull() ?: 0
    }

    private fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        
        return buildList {
            if (h > 0) add("${h}h")
            if (m > 0) add("${m}m")
            if (s > 0) add("${s}s")
        }.joinToString(" ")
    }

    private fun detectTimeZone(q: String): CommandResult? {
        val timeInPattern = Regex("(?:time\\s+in\\s+|(?:what\\s+time\\s+is\\s+it\\s+in\\s+))?([a-z\\s]+?)(?:\\s+time)?")
        val match = timeInPattern.matchEntire(q) ?: return null
        
        val location = match.groupValues.getOrNull(1)?.trim() ?: return null
        if (location.length < 2) return null
        
        val zoneId = findZoneId(location) ?: return null
        val now = ZonedDateTime.now(zoneId)
        val deviceZone = ZoneId.systemDefault()
        val deviceNow = ZonedDateTime.now(deviceZone)
        
        val offsetHours = (now.offset.totalSeconds - deviceNow.offset.totalSeconds) / 3600.0
        val offsetSign = if (offsetHours >= 0) "+" else ""
        val offsetStr = when {
            offsetHours == 0.0 -> ""
            offsetHours % 1.0 == 0.0 -> " (${offsetSign}${offsetHours.toInt()}h)"
            else -> " (${offsetSign}${String.format(Locale.ROOT, "%.1f", offsetHours)}h)"
        }
        
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        val dayFormatter = DateTimeFormatter.ofPattern("EEE", Locale.ROOT)
        
        val zoneName = location.split(Regex("\\s+")).joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
        val timeStr = now.format(timeFormatter)
        val dayStr = now.format(dayFormatter)
        
        val displayText = "$zoneName $timeStr · $dayStr$offsetStr"
        
        return CommandResult(
            Command.TimeZone(zoneName, zoneId),
            displayText,
            "Tap to copy time"
        )
    }

    private fun findZoneId(location: String): ZoneId? {
        val normalized = location.lowercase(Locale.ROOT).trim()
        
        CITY_ZONES[normalized]?.let { return ZoneId.of(it) }
        
        val abbrevZone = ZONE_ABBREVIATIONS[normalized.uppercase(Locale.ROOT)]
        if (abbrevZone != null) return ZoneId.of(abbrevZone)
        
        try {
            return ZoneId.of(normalized.replace(" ", "_").replaceFirstChar { it.uppercaseChar() })
        } catch (e: Exception) {
        }
        
        return null
    }

    private fun detectNote(query: String): CommandResult? {
        val notePrefix = Regex("^note\\s+", RegexOption.IGNORE_CASE)
        if (!notePrefix.containsMatchIn(query)) return null
        
        val content = query.replaceFirst(notePrefix, "").trim()
        if (content.isEmpty()) return null
        
        val withNewlines = content.replace("↵", "\n").replace(" / ", "\n")
        
        val preview = if (content.length > 100) {
            content.take(97) + "..."
        } else {
            content
        }
        
        val previewMultiline = preview.replace("↵", "\n").replace(" / ", "\n")
        
        return CommandResult(
            Command.Note(content, withNewlines),
            previewMultiline,
            "📝 Note → Keep"
        )
    }

    private val CITY_ZONES = mapOf(
        "tokyo" to "Asia/Tokyo",
        "jakarta" to "Asia/Jakarta",
        "new york" to "America/New_York",
        "los angeles" to "America/Los_Angeles",
        "chicago" to "America/Chicago",
        "london" to "Europe/London",
        "paris" to "Europe/Paris",
        "berlin" to "Europe/Berlin",
        "madrid" to "Europe/Madrid",
        "rome" to "Europe/Rome",
        "moscow" to "Europe/Moscow",
        "dubai" to "Asia/Dubai",
        "mumbai" to "Asia/Kolkata",
        "delhi" to "Asia/Kolkata",
        "beijing" to "Asia/Shanghai",
        "shanghai" to "Asia/Shanghai",
        "hong kong" to "Asia/Hong_Kong",
        "singapore" to "Asia/Singapore",
        "sydney" to "Australia/Sydney",
        "melbourne" to "Australia/Melbourne",
        "auckland" to "Pacific/Auckland",
        "toronto" to "America/Toronto",
        "vancouver" to "America/Vancouver",
        "mexico city" to "America/Mexico_City",
        "sao paulo" to "America/Sao_Paulo",
        "buenos aires" to "America/Argentina/Buenos_Aires",
        "cairo" to "Africa/Cairo",
        "johannesburg" to "Africa/Johannesburg",
        "istanbul" to "Europe/Istanbul",
        "athens" to "Europe/Athens",
        "stockholm" to "Europe/Stockholm",
        "copenhagen" to "Europe/Copenhagen",
        "amsterdam" to "Europe/Amsterdam",
        "brussels" to "Europe/Brussels",
        "zurich" to "Europe/Zurich",
        "vienna" to "Europe/Vienna",
        "prague" to "Europe/Prague",
        "bangkok" to "Asia/Bangkok",
        "manila" to "Asia/Manila",
        "seoul" to "Asia/Seoul",
        "taipei" to "Asia/Taipei",
        "kuala lumpur" to "Asia/Kuala_Lumpur",
        "riyadh" to "Asia/Riyadh",
        "tel aviv" to "Asia/Tel_Aviv",
        "jerusalem" to "Asia/Jerusalem"
    )

    private val ZONE_ABBREVIATIONS = mapOf(
        "EST" to "America/New_York",
        "PST" to "America/Los_Angeles",
        "MST" to "America/Denver",
        "CST" to "America/Chicago",
        "GMT" to "GMT",
        "UTC" to "UTC",
        "CET" to "Europe/Paris",
        "JST" to "Asia/Tokyo",
        "IST" to "Asia/Kolkata",
        "AEST" to "Australia/Sydney",
        "NZST" to "Pacific/Auckland"
    )
}
