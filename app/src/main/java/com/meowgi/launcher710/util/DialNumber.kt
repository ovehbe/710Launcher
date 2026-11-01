package com.meowgi.launcher710.util

/**
 * Decides whether a search query is a phone number the user typed, as opposed to text. "sjsj" is
 * text while "44" and "+90 555 123 45 67" dial.
 *
 * On a BlackBerry-style keyboard ([LAYOUT_QWERTY]) the dial pad is printed on letter keys
 * (W E R / S D F / Z X C = 1–9, A = *, Q = #, O = +) and those keys type the letter unless Alt is
 * applied, so a number typed there arrives as e.g. "wer" or "w23". With that layout such letters
 * count as their keypad digit, but only when every character of the query is dialable.
 */
object DialNumber {

    const val LAYOUT_QWERTY = 0
    const val LAYOUT_T9 = 1
    /** Literal digits only; letters never dial. */
    const val NO_KEYPAD = -1

    /** Formatting people type inside numbers; dropped from the dialled string. */
    private const val SEPARATORS = " \u00A0-().\t"

    /** The dial-pad character printed on [ch]'s key, or null. Only [LAYOUT_QWERTY] prints digits on letters. */
    fun keypadChar(ch: Char, layout: Int): Char? {
        if (layout != LAYOUT_QWERTY) return null
        return when (ch.lowercaseChar()) {
            'w' -> '1'; 'e' -> '2'; 'r' -> '3'
            's' -> '4'; 'd' -> '5'; 'f' -> '6'
            'z' -> '7'; 'x' -> '8'; 'c' -> '9'
            'a' -> '*'; 'q' -> '#'; 'o' -> '+'
            else -> null
        }
    }

    /**
     * The number to dial for [query] — digits, `*`, `#` and one leading `+`, separators removed —
     * or null when the query contains anything else or has no digit at all. With [layout]
     * [LAYOUT_QWERTY], keypad letters count as the character on their key.
     */
    fun dialable(query: String, layout: Int = NO_KEYPAD): String? {
        val sb = StringBuilder(query.length)
        var hasDigit = false
        for (raw in query) {
            val ch = if (raw in '0'..'9' || raw == '*' || raw == '#' || raw == '+' || raw in SEPARATORS) raw
            else keypadChar(raw, layout) ?: return null
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
