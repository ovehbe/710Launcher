package com.meowgi.launcher710.ai

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * The subset of Markdown chat models use: paragraphs, headings, bold/italic, inline and fenced
 * code, bullet and numbered lists, quotes, links (shown as their text). Unclosed markers stay
 * literal, so a half-streamed answer renders sensibly and settles when the closing marker arrives.
 */
object AiMarkdown {

    enum class Kind { BOLD, ITALIC, CODE, CODE_BLOCK, HEADING, LIST_ITEM, QUOTE }

    /** [level] is the heading level or the list nesting depth. */
    data class Span(val start: Int, val end: Int, val kind: Kind, val level: Int = 0)

    data class TableCell(val text: String, val spans: List<Span>)
    data class Table(val rows: List<List<TableCell>>, val hasHeader: Boolean, val textOffset: Int)

    data class Rendered(val text: String, val spans: List<Span>, val tables: List<Table> = emptyList())

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val TABLE_ROW = Regex("^\\s*\\|(.+)\\|\\s*$")
    private val TABLE_SEP = Regex("^\\s*\\|\\s*([-:]+\\s*\\|\\s*)+$")

    private fun parseTableRow(row: String): List<String> {
        val cells = row.trim().removePrefix("|").removeSuffix("|").split('|')
        return cells.map { it.trim() }
    }

    private fun isTableSeparator(line: String): Boolean = TABLE_SEP.matches(line)

    private fun isTableRow(line: String): Boolean = TABLE_ROW.matches(line)

    fun parse(markdown: String): Rendered {
        val out = StringBuilder()
        val spans = ArrayList<Span>()
        val tables = ArrayList<Table>()
        val lines = markdown.replace("\r\n", "\n").split('\n')
        var i = 0
        var pendingBreak = false

        fun startBlock() {
            if (out.isNotEmpty()) out.append(if (pendingBreak) "\n\n" else "\n")
            pendingBreak = false
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                isTableRow(line) && i + 1 < lines.size && isTableSeparator(lines[i + 1]) -> {
                    startBlock()
                    val textOffset = out.length
                    val tableRows = ArrayList<List<TableCell>>()
                    val headerCells = parseTableRow(line).map { cellText ->
                        val cellOut = StringBuilder()
                        val cellSpans = ArrayList<Span>()
                        inline(cellText, cellOut, cellSpans)
                        TableCell(cellOut.toString(), cellSpans)
                    }
                    tableRows += headerCells
                    i += 2
                    while (i < lines.size && isTableRow(lines[i])) {
                        val rowCells = parseTableRow(lines[i]).map { cellText ->
                            val cellOut = StringBuilder()
                            val cellSpans = ArrayList<Span>()
                            inline(cellText, cellOut, cellSpans)
                            TableCell(cellOut.toString(), cellSpans)
                        }
                        tableRows += rowCells
                        i++
                    }
                    tables += Table(tableRows, hasHeader = true, textOffset = textOffset)
                    out.append("[Table: ${tableRows.size} rows × ${headerCells.size} columns]")
                    i--
                    pendingBreak = true
                }
                trimmed.startsWith("```") -> {
                    startBlock()
                    val start = out.length
                    i++
                    val code = ArrayList<String>()
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) code += lines[i++]
                    out.append(code.joinToString("\n"))
                    if (out.length > start) spans += Span(start, out.length, Kind.CODE_BLOCK)
                    pendingBreak = true
                }
                line.isBlank() -> if (out.isNotEmpty()) pendingBreak = true
                RULE.matches(line) -> pendingBreak = true
                HEADING.matches(line) -> {
                    val m = HEADING.find(line)!!
                    if (out.isNotEmpty()) pendingBreak = true
                    startBlock()
                    val start = out.length
                    inline(m.groupValues[2], out, spans)
                    spans += Span(start, out.length, Kind.HEADING, m.groupValues[1].length)
                    pendingBreak = true
                }
                BULLET.matches(line) -> {
                    val m = BULLET.find(line)!!
                    startBlock()
                    listItem("•  ", m.groupValues[2], depth(m.groupValues[1]), out, spans)
                }
                ORDERED.matches(line) -> {
                    val m = ORDERED.find(line)!!
                    startBlock()
                    listItem("${m.groupValues[2]}.  ", m.groupValues[3], depth(m.groupValues[1]), out, spans)
                }
                trimmed.startsWith(">") -> {
                    startBlock()
                    val start = out.length
                    inline(trimmed.removePrefix(">").trimStart(), out, spans)
                    spans += Span(start, out.length, Kind.QUOTE)
                }
                else -> {
                    startBlock()
                    inline(line.trim(), out, spans)
                }
            }
            i++
        }
        return Rendered(out.toString(), spans, tables)
    }

    private fun depth(indent: String) = (indent.replace("\t", "    ").length / 2).coerceAtMost(4)

    private fun listItem(marker: String, body: String, depth: Int, out: StringBuilder, spans: MutableList<Span>) {
        val start = out.length
        out.append(marker)
        inline(body, out, spans)
        spans += Span(start, out.length, Kind.LIST_ITEM, depth)
    }

    /** Appends [s] with inline markup turned into spans. */
    private fun inline(s: String, out: StringBuilder, spans: MutableList<Span>) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_[]#>") {
                out.append(s[i + 1]); i += 2; continue
            }
            if (c == '`') {
                val close = s.indexOf('`', i + 1)
                if (close > i + 1) {
                    val start = out.length
                    out.append(s, i + 1, close)
                    spans += Span(start, out.length, Kind.CODE)
                    i = close + 1
                    continue
                }
            }
            if ((c == '*' || c == '_') && s.startsWith("$c$c", i)) {
                val close = s.indexOf("$c$c", i + 2)
                if (close > i + 2) {
                    val start = out.length
                    inline(s.substring(i + 2, close), out, spans)
                    spans += Span(start, out.length, Kind.BOLD)
                    i = close + 2
                    continue
                }
            }
            if ((c == '*' || c == '_') && opensEmphasis(s, i)) {
                val close = findEmphasisClose(s, i + 1, c)
                if (close > i + 1) {
                    val start = out.length
                    inline(s.substring(i + 1, close), out, spans)
                    spans += Span(start, out.length, Kind.ITALIC)
                    i = close + 1
                    continue
                }
            }
            if (c == '[') {
                val mid = s.indexOf("](", i + 1)
                val end = if (mid > i) s.indexOf(')', mid + 2) else -1
                if (mid > i + 1 && end > mid) {
                    inline(s.substring(i + 1, mid), out, spans)
                    i = end + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
    }

    /** `*x*` / `_x_` open only before a non-space; `_` also needs a non-word char before it (snake_case stays). */
    private fun opensEmphasis(s: String, i: Int): Boolean {
        val next = s.getOrNull(i + 1) ?: return false
        if (next.isWhitespace() || next == s[i]) return false
        return s[i] == '*' || s.getOrNull(i - 1)?.isLetterOrDigit() != true
    }

    private fun findEmphasisClose(s: String, from: Int, c: Char): Int {
        var j = s.indexOf(c, from)
        while (j > 0) {
            val prevOk = !s[j - 1].isWhitespace()
            val nextOk = c == '*' || s.getOrNull(j + 1)?.isLetterOrDigit() != true
            if (prevOk && nextOk && s.getOrNull(j + 1) != c) return j
            j = s.indexOf(c, j + 1)
        }
        return -1
    }

    data class Style(val codeBackground: Int, val quoteColor: Int, val indentPx: Int)

    fun toPlainText(r: Rendered): String {
        val out = StringBuilder(r.text)
        for (table in r.tables.sortedByDescending { it.textOffset }) {
            val placeholder = "[Table: ${table.rows.size} rows × ${table.rows.firstOrNull()?.size ?: 0} columns]"
            val start = r.text.indexOf(placeholder, table.textOffset)
            if (start >= 0) {
                val tableText = formatTableAsPlainText(table)
                out.replace(start, start + placeholder.length, tableText)
            }
        }
        return out.toString()
    }

    private fun formatTableAsPlainText(table: Table): String {
        if (table.rows.isEmpty()) return ""
        val numCols = table.rows.firstOrNull()?.size ?: 0
        val colWidths = IntArray(numCols)
        for (row in table.rows) {
            for ((colIdx, cell) in row.withIndex()) {
                if (colIdx < numCols) {
                    colWidths[colIdx] = maxOf(colWidths[colIdx], cell.text.length)
                }
            }
        }
        val sb = StringBuilder()
        for ((rowIdx, row) in table.rows.withIndex()) {
            for ((colIdx, cell) in row.withIndex()) {
                if (colIdx > 0) sb.append(" | ")
                sb.append(cell.text.padEnd(colWidths[colIdx]))
            }
            sb.append("\n")
            if (rowIdx == 0 && table.hasHeader) {
                for (colIdx in 0 until numCols) {
                    if (colIdx > 0) sb.append("-+-")
                    sb.append("-".repeat(colWidths[colIdx]))
                }
                sb.append("\n")
            }
        }
        return sb.toString().trimEnd()
    }

    fun toSpanned(r: Rendered, style: Style): CharSequence {
        val sb = SpannableStringBuilder(r.text)
        for (s in r.spans) {
            if (s.start >= s.end || s.end > sb.length) continue
            val what: List<Any> = when (s.kind) {
                Kind.BOLD -> listOf(StyleSpan(Typeface.BOLD))
                Kind.ITALIC -> listOf(StyleSpan(Typeface.ITALIC))
                Kind.CODE -> listOf(TypefaceSpan("monospace"), BackgroundColorSpan(style.codeBackground))
                Kind.CODE_BLOCK -> listOf(
                    TypefaceSpan("monospace"), BackgroundColorSpan(style.codeBackground),
                    RelativeSizeSpan(0.9f), LeadingMarginSpan.Standard(style.indentPx / 2)
                )
                Kind.HEADING -> listOf(StyleSpan(Typeface.BOLD), RelativeSizeSpan(if (s.level <= 2) 1.2f else 1.08f))
                Kind.LIST_ITEM -> listOf(LeadingMarginSpan.Standard(style.indentPx * s.level, style.indentPx * (s.level + 1)))
                Kind.QUOTE -> listOf(StyleSpan(Typeface.ITALIC), ForegroundColorSpan(style.quoteColor), LeadingMarginSpan.Standard(style.indentPx))
            }
            for (w in what) sb.setSpan(w, s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }
}
