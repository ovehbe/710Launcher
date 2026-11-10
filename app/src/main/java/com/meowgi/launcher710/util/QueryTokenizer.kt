package com.meowgi.launcher710.util

/**
 * Tokenizer for Q25/BlackBerry keyboard queries that need letter-to-digit/operator mapping.
 * 
 * Key insight: Currency aliases (yen, tl, try, rupiah, dollar, euro, pound, lira) and ISO codes
 * (JPY, TRY, USD, etc.) must be recognized as TOKENS before letter-digit mapping is applied.
 * Otherwise "yen" becomes "y2n" (y→skip, e→2, n→skip) and "tl" becomes "(" (t→().
 * 
 * Strategy:
 * 1. Tokenize the query into segments: numbers, operators, currency codes, words
 * 2. Apply letter-digit mapping ONLY to numeric and operator segments
 * 3. Leave currency codes and known aliases unmapped
 * 4. Reject queries with unmappable words (unless they're currency-related)
 */
object QueryTokenizer {
    
    sealed class Token {
        data class Number(val value: String) : Token()
        data class Operator(val op: Char) : Token()
        data class Currency(val code: String, val originalText: String) : Token()
        data class Word(val text: String) : Token()
        data class Whitespace(val text: String) : Token()
    }
    
    /**
     * Tokenize a query, recognizing currency codes/aliases before applying letter-digit mapping.
     * Returns null if the query contains unmappable characters or words that aren't currency-related.
     */
    fun tokenize(query: String, layout: Int = DialNumber.LAYOUT_QWERTY): List<Token>? {
        if (query.isEmpty()) return null
        
        val tokens = mutableListOf<Token>()
        var i = 0
        
        while (i < query.length) {
            val ch = query[i]
            
            when {
                // Whitespace
                ch.isWhitespace() -> {
                    val start = i
                    while (i < query.length && query[i].isWhitespace()) i++
                    tokens.add(Token.Whitespace(query.substring(start, i)))
                }
                
                // Negative number: minus sign followed by digit or letter-digit
                (ch == '-' || ch == '−') && i + 1 < query.length && 
                    (query[i + 1].isDigit() || Calculator.operatorChar(query[i + 1], layout)?.isDigit() == true) -> {
                    // This is a negative number, not a subtraction operator
                    tokens.add(Token.Operator('-'))
                    i++
                }
                
                // Direct operators
                ch in "+-*/%()." || ch == ',' || ch == '−' -> {
                    // Convert Unicode minus to ASCII minus
                    val op = if (ch == '−') '-' else ch
                    tokens.add(Token.Operator(op))
                    i++
                }
                
                // Direct digits
                ch in '0'..'9' -> {
                    val start = i
                    while (i < query.length && query[i] in '0'..'9') i++
                    tokens.add(Token.Number(query.substring(start, i)))
                }
                
                // Letters: could be currency code/alias, letter-digit, letter-operator, or word
                ch.isLetter() -> {
                    // Try to match currency code or alias
                    val currencyMatch = matchCurrency(query, i)
                    if (currencyMatch != null) {
                        tokens.add(Token.Currency(currencyMatch.first, currencyMatch.second))
                        i += currencyMatch.second.length
                    } else {
                        // Try to map as digit/operator
                        val mapped = Calculator.operatorChar(ch, layout)
                        if (mapped != null) {
                            if (mapped in '0'..'9') {
                                tokens.add(Token.Number(mapped.toString()))
                            } else {
                                tokens.add(Token.Operator(mapped))
                            }
                            i++
                        } else {
                            // Unmappable letter - might be part of a word
                            // Collect the whole word
                            val start = i
                            while (i < query.length && query[i].isLetter()) i++
                            val word = query.substring(start, i)
                            tokens.add(Token.Word(word))
                        }
                    }
                }
                
                // Currency symbols
                ch == '$' || ch == '€' || ch == '£' || ch == '¥' -> {
                    val code = when (ch) {
                        '$' -> "USD"
                        '€' -> "EUR"
                        '£' -> "GBP"
                        '¥' -> "JPY"
                        else -> null
                    }
                    if (code != null) {
                        tokens.add(Token.Currency(code, ch.toString()))
                    }
                    i++
                }
                
                else -> {
                    // Unknown character - reject
                    return null
                }
            }
        }
        
        return tokens
    }
    
    /**
     * Try to match a currency code (ISO 3-letter) or alias at position i.
     * Returns (code, matchedText) or null.
     */
    private fun matchCurrency(query: String, i: Int): Pair<String, String>? {
        val remaining = query.substring(i).lowercase()
        
        // Try aliases first (longer matches first to avoid "try" matching when "trys" is typed)
        val aliases = CurrencyConverter.CURRENCY_NAME_ALIASES.entries.sortedByDescending { it.key.length }
        for ((alias, code) in aliases) {
            if (remaining.startsWith(alias)) {
                // Check word boundary after
                val end = i + alias.length
                if (end >= query.length || !query[end].isLetter()) {
                    return Pair(code, query.substring(i, end))
                }
            }
        }
        
        // Try ISO 3-letter code
        if (remaining.length >= 3) {
            val potential = remaining.substring(0, 3).uppercase()
            if (potential.all { it.isLetter() } && CurrencyConverter.isKnownCurrency(potential)) {
                // Check word boundary
                val end = i + 3
                if (end >= query.length || !query[end].isLetter()) {
                    return Pair(potential, query.substring(i, end))
                }
            }
        }
        
        return null
    }
    
    /**
     * Check if tokens represent a valid calculator expression (no currency or word tokens).
     */
    fun isCalculatorExpression(tokens: List<Token>): Boolean {
        return tokens.none { it is Token.Currency || it is Token.Word }
    }
    
    /**
     * Check if tokens represent a currency query (has at least one currency token).
     */
    fun isCurrencyQuery(tokens: List<Token>): Boolean {
        return tokens.any { it is Token.Currency }
    }
    
    /**
     * Build a calculator expression string from tokens (only numbers and operators).
     * Returns null if tokens contain currency or words.
     */
    fun toCalculatorExpression(tokens: List<Token>): String? {
        if (!isCalculatorExpression(tokens)) return null
        
        val sb = StringBuilder()
        for (token in tokens) {
            when (token) {
                is Token.Number -> sb.append(token.value)
                is Token.Operator -> sb.append(token.op)
                is Token.Whitespace -> {} // Skip whitespace
                else -> return null
            }
        }
        
        // Require at least one digit
        return if (sb.any { it.isDigit() }) sb.toString() else null
    }
    
    /**
     * Reconstruct the original-ish query from tokens (useful for currency queries).
     */
    fun toOriginalForm(tokens: List<Token>): String {
        val sb = StringBuilder()
        for (token in tokens) {
            when (token) {
                is Token.Number -> sb.append(token.value)
                is Token.Operator -> sb.append(token.op)
                is Token.Currency -> sb.append(token.originalText)
                is Token.Word -> sb.append(token.text)
                is Token.Whitespace -> sb.append(token.text)
            }
        }
        return sb.toString()
    }
}
