package com.meowgi.launcher710.util

/**
 * Decides whether a search query is a phone number the user typed, as opposed to text. Letters are
 * never mapped to keypad digits here, so "sjsj" is text while "44" and "+90 555 123 45 67" dial.
 */
object DialNumber {

    /** Formatting people type inside numbers; dropped from the dialled string. */
    private const val SEPARATORS = " \u00A0-().\t"

    /**
     * The number to dial for [query] — digits, `*`, `#` and one leading `+`, separators removed —
     * or null when the query contains anything else or has no digit at all.
     */
    fun dialable(query: String): String? {
        val sb = StringBuilder(query.length)
        var hasDigit = false
        for (ch in query) {
            when {
                ch in '0'..'9' -> { sb.append(ch); hasDigit = true }
                ch == '*' || ch == '#' -> sb.append(ch)
                ch == '+' -> if (sb.isEmpty()) sb.append(ch) else return null
                ch in SEPARATORS -> {}
                else -> return null
            }
        }
        return if (hasDigit) sb.toString() else null
    }
}
