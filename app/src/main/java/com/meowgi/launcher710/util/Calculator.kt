package com.meowgi.launcher710.util

/**
 * Safe arithmetic expression evaluator for the inline calculator in search.
 *
 * Supports: +, -, *, /, %, (, ), decimal numbers (with . or ,), and operator precedence.
 * Handles BlackBerry Classic keyboard letter-to-operator mapping so expressions work
 * without holding Alt (e.g. "eoe" → "2+2").
 *
 * Keyboard mappings (BlackBerry Q10/Classic Alt layer, from BBQ10KBD documentation):
 * - o → + (addition)
 * - a → * (multiplication)
 * - i → - (subtraction)
 * - g → / (division)
 * - m → . (decimal point)
 * - t → ( (left paren)
 * - y → ) (right paren)
 * - n → , (comma - context-sensitive: thousands separator or decimal)
 *
 * Digit mappings reuse existing DialNumber.keypadChar() for w/e/r/s/d/f/z/x/c → 1-9.
 *
 * Comma handling rule:
 * - "1,000" → thousands separator → 1000
 * - "1,5" → decimal point (European notation) → 1.5
 * - If comma separates groups of exactly 3 digits consistently, it's thousands separator.
 * - Otherwise, if single comma with <3 digits after (and no other decimal), it's decimal.
 */
object Calculator {

    /**
     * Maps a BlackBerry keyboard letter to its calculator operator/symbol, or null.
     * Works for both lowercase and uppercase. Reuses DialNumber for digit mappings.
     */
    fun operatorChar(ch: Char, layout: Int = DialNumber.LAYOUT_QWERTY): Char? {
        // First check if it's a digit mapping (w/e/r/s/d/f/z/x/c → 1-9, etc.)
        DialNumber.keypadChar(ch, layout)?.let { return it }
        // Then check operator mappings
        return when (ch.lowercaseChar()) {
            'o' -> '+'
            'a' -> '*'
            'i' -> '-'
            'g' -> '/'
            'm' -> '.'
            't' -> '('
            'y' -> ')'
            'n' -> ','
            else -> null
        }
    }

    /**
     * Converts a query string to a calculator expression by mapping BlackBerry keyboard
     * letters to operators and digits. Returns null if the string contains characters
     * that aren't mappable (meaning it's a normal text query, not a calculator expression).
     *
     * Also accepts raw symbols (+, -, *, /, ., (, ), %, digits) directly.
     * 
     * Requires at least one digit (raw or mapped) to prevent plain words from being treated as math.
     */
    fun toExpression(query: String, layout: Int = DialNumber.LAYOUT_QWERTY): String? {
        if (query.isEmpty()) return null
        val sb = StringBuilder(query.length)
        var hasDigit = false
        for (ch in query) {
            when {
                ch in '0'..'9' -> { sb.append(ch); hasDigit = true }
                ch == '+' || ch == '-' || ch == '*' || ch == '/' || ch == '%' -> sb.append(ch)
                ch == '.' || ch == ',' -> sb.append(ch)
                ch == '(' || ch == ')' -> sb.append(ch)
                ch == ' ' || ch == '\t' -> {} // ignore whitespace
                else -> {
                    // Try to map letter to operator/digit
                    val mapped = operatorChar(ch, layout)
                    if (mapped != null) {
                        sb.append(mapped)
                        // Check if this mapped to a digit
                        if (mapped in '0'..'9') hasDigit = true
                    } else {
                        // Character can't be mapped → not a calculator expression
                        return null
                    }
                }
            }
        }
        // Require at least one digit to prevent words like "giant" from being treated as expressions
        return if (hasDigit) sb.toString() else null
    }

    /**
     * Evaluates an arithmetic expression and returns the formatted result, or null on error.
     * The expression should already be mapped (use toExpression first).
     *
     * Set allowPlainNumber to true to return the number itself when there are no operators.
     * 
     * Returns null for:
     * - Parse errors (malformed expression)
     * - Division by zero
     * - No operators present (just a plain number) - unless allowPlainNumber is true
     * - Expression doesn't contain at least one digit
     */
    fun evaluate(expression: String, allowPlainNumber: Boolean = false): String? {
        if (expression.isBlank()) return null
        try {
            // Normalize commas: apply context-sensitive comma rule
            val normalized = normalizeCommas(expression)
            // Check if expression contains at least one digit
            if (!normalized.any { it.isDigit() }) return null
            
            val hasOperator = normalized.any { it in "+-*/%"}
            
            // If no operator and allowPlainNumber is false, return null
            if (!hasOperator && !allowPlainNumber) return null
            
            // If no operator but allowPlainNumber is true, just return the normalized number
            if (!hasOperator && allowPlainNumber) {
                // Parse as a plain number and format it
                return try {
                    val number = normalized.toDouble()
                    formatResult(number)
                } catch (e: Exception) {
                    null
                }
            }
            
            val result = evaluateExpression(normalized)
            return formatResult(result)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Context-sensitive comma normalization:
     * - "1,000" or "1,000,000" → remove commas (thousands separators)
     * - "1,5" → replace comma with period (European decimal notation)
     */
    private fun normalizeCommas(expr: String): String {
        if (',' !in expr) return expr
        
        // Split by operators and parens to isolate number tokens
        val tokens = expr.split(Regex("[+\\-*/%()]")).filter { it.isNotEmpty() }
        var result = expr
        
        for (token in tokens) {
            if (',' !in token) continue
            val parts = token.split(',')
            // Check if all parts after first have exactly 3 digits (thousands separator pattern)
            val isThousandsSeparator = parts.size > 1 && 
                parts.drop(1).all { it.length == 3 && it.all { c -> c.isDigit() } }
            
            if (isThousandsSeparator) {
                // Remove commas (thousands separators)
                result = result.replace(token, token.replace(",", ""))
            } else if (parts.size == 2 && '.' !in token) {
                // Single comma, no period → treat as decimal point
                result = result.replace(token, token.replace(",", "."))
            } else {
                // Malformed or mixed → remove commas
                result = result.replace(token, token.replace(",", ""))
            }
        }
        
        return result
    }

    /**
     * Formats the result number: trim trailing zeros after decimal, limit precision.
     */
    private fun formatResult(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "Error"
        
        // If it's a whole number, return as integer
        if (value == value.toLong().toDouble()) {
            return value.toLong().toString()
        }
        
        // Format with up to 8 decimal places, then trim trailing zeros
        var formatted = "%.8f".format(value)
        formatted = formatted.trimEnd('0').trimEnd('.')
        return formatted
    }

    /**
     * Recursive descent parser with operator precedence.
     * Grammar:
     *   expr    := term (('+' | '-') term)*
     *   term    := factor (('*' | '/' | '%') factor)*
     *   factor  := '-' factor | '(' expr ')' | number
     */
    private fun evaluateExpression(expr: String): Double {
        val parser = Parser(expr)
        val result = parser.parseExpression()
        if (parser.pos < parser.expr.length) {
            throw IllegalArgumentException("Unexpected character at position ${parser.pos}")
        }
        return result
    }

    private class Parser(val expr: String) {
        var pos = 0

        fun parseExpression(): Double {
            var result = parseTerm()
            while (pos < expr.length) {
                skipWhitespace()
                if (pos >= expr.length) break
                when (expr[pos]) {
                    '+' -> { pos++; result += parseTerm() }
                    '-' -> { pos++; result -= parseTerm() }
                    else -> break
                }
            }
            return result
        }

        fun parseTerm(): Double {
            var result = parseFactor()
            while (pos < expr.length) {
                skipWhitespace()
                if (pos >= expr.length) break
                when (expr[pos]) {
                    '*' -> { pos++; result *= parseFactor() }
                    '/' -> {
                        pos++
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw ArithmeticException("Division by zero")
                        result /= divisor
                    }
                    '%' -> {
                        pos++
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw ArithmeticException("Division by zero")
                        result %= divisor
                    }
                    else -> break
                }
            }
            return result
        }

        fun parseFactor(): Double {
            skipWhitespace()
            if (pos >= expr.length) throw IllegalArgumentException("Unexpected end of expression")
            
            // Handle unary minus
            if (expr[pos] == '-') {
                pos++
                return -parseFactor()
            }
            
            // Handle parentheses
            if (expr[pos] == '(') {
                pos++
                val result = parseExpression()
                skipWhitespace()
                if (pos >= expr.length || expr[pos] != ')') {
                    throw IllegalArgumentException("Missing closing parenthesis")
                }
                pos++
                return result
            }
            
            // Parse number
            return parseNumber()
        }

        fun parseNumber(): Double {
            skipWhitespace()
            val start = pos
            if (pos >= expr.length) throw IllegalArgumentException("Expected number")
            
            // Read digits before decimal point
            while (pos < expr.length && expr[pos].isDigit()) {
                pos++
            }
            
            // Read decimal point and digits after
            if (pos < expr.length && expr[pos] == '.') {
                pos++
                while (pos < expr.length && expr[pos].isDigit()) {
                    pos++
                }
            }
            
            if (start == pos) throw IllegalArgumentException("Expected number at position $pos")
            
            val numberStr = expr.substring(start, pos)
            return numberStr.toDouble()
        }

        fun skipWhitespace() {
            while (pos < expr.length && expr[pos].isWhitespace()) {
                pos++
            }
        }
    }
}
