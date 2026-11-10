package com.meowgi.launcher710.util

/**
 * Unified tokenizer for calculator and currency queries.
 * Recognizes currency codes/aliases BEFORE applying Q25 letter-digit mapping.
 */
object QueryTokenizer {
    sealed class Token {
        data class Number(val value: String) : Token()
        data class Operator(val op: Char) : Token()
        data class Currency(val code: String, val originalText: String) : Token()
        data class Keyword(val word: String) : Token()
        data class Word(val text: String) : Token()
    }

    private val KEYWORDS = setOf("to", "in")

    fun tokenize(query: String, layout: Int = DialNumber.LAYOUT_QWERTY): List<Token>? {
        if (query.isEmpty()) return null
        val normalized = query.trim()
        val tokens = mutableListOf<Token>()
        var i = 0
        
        while (i < normalized.length) {
            val ch = normalized[i]
            
            when {
                ch.isWhitespace() -> { i++; continue }
                
                // Currency symbols
                ch in "$€£¥" -> {
                    tokens.add(Token.Currency(
                        code = when (ch) { '$' -> "USD"; '€' -> "EUR"; '£' -> "GBP"; '¥' -> "JPY"; else -> "USD" },
                        originalText = ch.toString()
                    ))
                    i++
                }
                
                // Operators
                ch in "+-*/%()=" || ch == '−' -> {
                    tokens.add(Token.Operator(if (ch == '−') '-' else ch))
                    i++
                }
                
                // Decimal/thousands separators
                ch in ".," -> {
                    tokens.add(Token.Operator(ch))
                    i++
                }
                
                // Direct digits
                ch in '0'..'9' -> {
                    val start = i
                    while (i < normalized.length && normalized[i] in '0'..'9') i++
                    tokens.add(Token.Number(normalized.substring(start, i)))
                }
                
                // Letters: check currency/keyword FIRST, then map
                ch.isLetter() -> {
                    // Look ahead for multi-letter currency/keyword
                    val wordMatch = matchWord(normalized, i)
                    if (wordMatch != null) {
                        val (word, len) = wordMatch
                        val lower = word.lowercase()
                        
                        // Check if it's a currency ISO code (3 letters)
                        if (word.length == 3 && CurrencyConverter.isKnownCurrency(word.uppercase())) {
                            tokens.add(Token.Currency(word.uppercase(), word))
                            i += len
                        }
                        // Check if it's a currency alias
                        else if (CurrencyConverter.CURRENCY_NAME_ALIASES.containsKey(lower)) {
                            val code = CurrencyConverter.CURRENCY_NAME_ALIASES[lower]!!
                            tokens.add(Token.Currency(code, word))
                            i += len
                        }
                        // Check if it's a keyword
                        else if (KEYWORDS.contains(lower)) {
                            tokens.add(Token.Keyword(lower))
                            i += len
                        }
                        // Check if it's a known unmappable word (contains unmappable letters)
                        else if (word.any { !canMapLetter(it, layout) }) {
                            tokens.add(Token.Word(word))
                            i += len
                        }
                        // Otherwise, apply letter-digit mapping per character
                        else {
                            // Map each letter individually
                            for (c in word) {
                                val mapped = Calculator.operatorChar(c, layout)
                                if (mapped != null) {
                                    if (mapped in '0'..'9') {
                                        tokens.add(Token.Number(mapped.toString()))
                                    } else {
                                        tokens.add(Token.Operator(mapped))
                                    }
                                } else {
                                    // Should not happen if canMapLetter check above is correct
                                    tokens.add(Token.Word(word))
                                    break
                                }
                            }
                            i += len
                        }
                    } else {
                        // Single unmappable letter
                        return null
                    }
                }
                
                else -> return null
            }
        }
        
        return tokens
    }

    private fun matchWord(str: String, start: Int): Pair<String, Int>? {
        var end = start
        while (end < str.length && str[end].isLetter()) end++
        if (end == start) return null
        return Pair(str.substring(start, end), end - start)
    }

    private fun canMapLetter(ch: Char, layout: Int): Boolean {
        return Calculator.operatorChar(ch.lowercaseChar(), layout) != null
    }

    fun isCurrencyQuery(tokens: List<Token>): Boolean {
        return tokens.any { it is Token.Currency || it is Token.Keyword }
    }

    fun hasOperator(tokens: List<Token>): Boolean {
        return tokens.any { it is Token.Operator && it.op in "+-*/%()=" }
    }

    fun toCalculatorExpression(tokens: List<Token>, includeCurrency: Boolean = false): String? {
        val sb = StringBuilder()
        var hasDigit = false
        var hasOperator = false
        
        for (token in tokens) {
            when (token) {
                is Token.Number -> { sb.append(token.value); hasDigit = true }
                is Token.Operator -> { 
                    sb.append(token.op)
                    if (token.op in "+-*/%") hasOperator = true
                }
                is Token.Currency -> {
                    // When includeCurrency=true, skip currency tokens (just use the numbers)
                    // e.g., "100 yen * 10" → "100*10"
                    if (!includeCurrency) return null
                }
                is Token.Keyword, is Token.Word -> return null
            }
        }
        
        // Require at least one digit AND at least one operator to be a calculator expression
        // Single numbers without operators are NOT calculator expressions (they're just amounts)
        return if (hasDigit && hasOperator) sb.toString() else null
    }
}
