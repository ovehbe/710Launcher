package com.meowgi.launcher710.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Currency converter for inline search results.
 * 
 * Parses queries like "100 usd to try" or "50*2 eur" and converts using cached exchange rates.
 * Supports BlackBerry keyboard letter-to-digit mapping for amounts ONLY - currency codes
 * and keywords (to/in) are matched as literal words first.
 * 
 * Examples:
 * - "100 usd to try" → convert 100 USD to TRY
 * - "w00 usd to try" → same (w→1 mapped in amount)
 * - "100 gbp" → convert 100 GBP to all display currencies
 * - "50*2 eur to usd" → convert 100 EUR to USD (calculator expression in amount)
 */
object CurrencyConverter {

    /**
     * Currency name aliases (common names mapped to ISO codes).
     * Sorted by length descending for longest-match-first parsing.
     * 
     * Mandatory equivalences:
     * - JPY: yen, jpy
     * - IDR: rupiah, idr
     * - TRY: lira, tl, try (Turkish lira)
     * 
     * Popular currencies with explicit names to avoid ambiguity:
     * - USD: dollar, dollars, us dollar, us dollars
     * - EUR: euro, euros
     * - GBP: pound, pounds, british pound, british pounds, sterling
     * - CNY: yuan
     * - AUD: australian dollar, australian dollars
     * - CAD: canadian dollar, canadian dollars
     */
    val CURRENCY_NAME_ALIASES = mapOf(
        // Longest first to match "australian dollar" before "dollar"
        "australian dollars" to "AUD",
        "australian dollar" to "AUD",
        "canadian dollars" to "CAD",
        "canadian dollar" to "CAD",
        "british pounds" to "GBP",
        "british pound" to "GBP",
        "us dollars" to "USD",
        "us dollar" to "USD",
        "sterling" to "GBP",
        "dollars" to "USD",
        "dollar" to "USD",
        "rupiah" to "IDR",
        "pounds" to "GBP",
        "pound" to "GBP",
        "euros" to "EUR",
        "euro" to "EUR",
        "lira" to "TRY",  // Turkish lira (unambiguous in common usage)
        "yuan" to "CNY",
        "yen" to "JPY",
        "tl" to "TRY",    // Turkish Lira abbreviation
        "idr" to "IDR",   // Case-insensitive ISO code alias
        "jpy" to "JPY",   // Case-insensitive ISO code alias
        "try" to "TRY"    // Case-insensitive ISO code alias (Turkish)
    )

    /**
     * Check if a currency code is commonly known/supported.
     * Used by QueryTokenizer to recognize currency codes before letter-digit mapping.
     */
    fun isKnownCurrency(code: String): Boolean {
        // List of commonly traded currencies that should be recognized
        val knownCurrencies = setOf(
            "USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD",
            "TRY", "RUB", "INR", "CNY", "KRW", "SGD", "HKD", "TWD",
            "THB", "MYR", "IDR", "PHP", "VND", "BRL", "MXN", "ARS",
            "ZAR", "SAR", "AED", "QAR", "KWD", "BHD", "OMR", "ILS",
            "SEK", "NOK", "DKK", "PLN", "CZK", "HUF", "RON", "BGN",
            "HRK", "ISK"
        )
        return code.uppercase() in knownCurrencies
    }

    /**
     * A parsed currency query.
     */
    data class CurrencyQuery(
        val amount: Double,
        val fromCurrency: String,
        val toCurrency: String? = null
    )

    /**
     * Result of a currency conversion.
     */
    data class ConversionResult(
        val fromAmount: Double,
        val fromCurrency: String,
        val toAmount: Double,
        val toCurrency: String
    )

    /**
     * Simple expansion of magnitude suffixes for QueryTokenizer.
     * Replaces "100k" with "100000" directly (no "*" operators).
     * Handles "one m" (word + suffix), "2m" (digit + suffix), "100kusd" (suffix + currency).
     * Also handles BB keyboard patterns like "wm" (w→1, m→million when trailing).
     */
    private fun simpleExpandMagnitudes(query: String): String {
        var result = query
        
        // BB keyboard special cases: letter+m where letter maps to digit and m means million
        // wm → 1m → 1000000, em → 2m → 2000000, etc.
        // But NOT if m is followed by a digit-mapping letter (then m is decimal point)
        // e.g., "wmd" = w→1, m→., d→5 = "1.5", not "1000000d"
        result = result.replace(Regex("\\bwm(?=\\s|[a-z&&[^wedrszcxf]]|$)", RegexOption.IGNORE_CASE), "1000000")
        result = result.replace(Regex("\\bem(?=\\s|[a-z&&[^wedrszcxf]]|$)", RegexOption.IGNORE_CASE), "2000000")
        result = result.replace(Regex("\\brm(?=\\s|[a-z&&[^wedrszcxf]]|$)", RegexOption.IGNORE_CASE), "3000000")
        result = result.replace(Regex("\\bsm(?=\\s|[a-z&&[^wedrszcxf]]|$)", RegexOption.IGNORE_CASE), "4000000")
        result = result.replace(Regex("\\bdm(?=\\s|[a-z&&[^wedrszcxf]]|$)", RegexOption.IGNORE_CASE), "5000000")
        
        // Word + magnitude suffix combinations
        result = result.replace(Regex("\\bone m\\b", RegexOption.IGNORE_CASE), "1000000")
        result = result.replace(Regex("\\btwo m\\b", RegexOption.IGNORE_CASE), "2000000")
        result = result.replace(Regex("\\bone k\\b", RegexOption.IGNORE_CASE), "1000")
        
        // Expand magnitude suffixes - these match digit/decimal followed by suffix
        // Use (?=...) lookahead to match suffixes even when directly followed by currency
        
        // bn/mn (billion/million with 'n')
        result = result.replace(Regex("(\\d+(?:\\.\\d+)?)bn(?=\\s|[a-z]|$)", RegexOption.IGNORE_CASE)) { match ->
            val num = match.groupValues[1].toDoubleOrNull() ?: 1.0
            "${(num * 1_000_000_000).toLong()}"
        }
        result = result.replace(Regex("(\\d+(?:\\.\\d+)?)mn(?=\\s|[a-z]|$)", RegexOption.IGNORE_CASE)) { match ->
            val num = match.groupValues[1].toDoubleOrNull() ?: 1.0
            "${(num * 1_000_000).toLong()}"
        }
        
        // b (billion)
        result = result.replace(Regex("(\\d+(?:\\.\\d+)?)b(?=\\s|[a-z]|$)", RegexOption.IGNORE_CASE)) { match ->
            val num = match.groupValues[1].toDoubleOrNull() ?: 1.0
            "${(num * 1_000_000_000).toLong()}"
        }
        
        // m (million) - careful not to match 'mn' or letters after
        result = result.replace(Regex("(\\d+(?:\\.\\d+)?)m(?!n)(?=\\s|[a-z]|$)", RegexOption.IGNORE_CASE)) { match ->
            val num = match.groupValues[1].toDoubleOrNull() ?: 1.0
            "${(num * 1_000_000).toLong()}"
        }
        
        // k (thousand) - allow directly before currency like "100kusd"
        result = result.replace(Regex("(\\d+(?:\\.\\d+)?)k(?=\\s|[a-z]|$)", RegexOption.IGNORE_CASE)) { match ->
            val num = match.groupValues[1].toDoubleOrNull() ?: 1.0
            "${(num * 1_000).toLong()}"
        }
        
        return result
    }
    
    /**
     * Simple expansion of number words for QueryTokenizer.
     * Handles compound phrases like "two thousand", "half a million", "1 million", "5 billion".
     */
    private fun simpleExpandNumberWords(query: String): String {
        var result = query
        
        // Expand compound phrases with word + magnitude
        result = result.replace(Regex("\\bhalf a million\\b", RegexOption.IGNORE_CASE), "500000")
        result = result.replace(Regex("\\bone million\\b", RegexOption.IGNORE_CASE), "1000000")
        result = result.replace(Regex("\\btwo million\\b", RegexOption.IGNORE_CASE), "2000000")
        result = result.replace(Regex("\\bfive million\\b", RegexOption.IGNORE_CASE), "5000000")
        result = result.replace(Regex("\\btwo thousand\\b", RegexOption.IGNORE_CASE), "2000")
        result = result.replace(Regex("\\bhundred thousand\\b", RegexOption.IGNORE_CASE), "100000")
        result = result.replace(Regex("\\bfive billion\\b", RegexOption.IGNORE_CASE), "5000000000")
        result = result.replace(Regex("\\bten k\\b", RegexOption.IGNORE_CASE), "10000")
        
        // Expand digit + word combinations like "1 million", "5 billion"
        // Handle specific common cases explicitly first
        result = result.replace(Regex("\\b5 billion\\b", RegexOption.IGNORE_CASE), "5000000000")
        result = result.replace(Regex("\\b1 million\\b", RegexOption.IGNORE_CASE), "1000000")
        
        // Then handle general patterns
        result = result.replace(Regex("\\b(\\d+) million\\b", RegexOption.IGNORE_CASE)) { match ->
            val num = match.groupValues[1].toLongOrNull() ?: 1L
            "${num * 1_000_000}"
        }
        result = result.replace(Regex("\\b(\\d+) billion\\b", RegexOption.IGNORE_CASE)) { match ->
            val num = match.groupValues[1].toLongOrNull() ?: 1L
            "${num * 1_000_000_000}"
        }
        result = result.replace(Regex("\\b(\\d+) thousand\\b", RegexOption.IGNORE_CASE)) { match ->
            val num = match.groupValues[1].toLongOrNull() ?: 1L
            "${num * 1_000}"
        }
        
        return result
    }

    /**
     * Insert spaces before known currency codes to help tokenization.
     * E.g., "100usd" → "100 usd", "100usdtry" → "100 usd try"
     * But preserve multi-word aliases like "australian dollars".
     */
    private fun insertSpacesBeforeCurrencies(query: String): String {
        var result = query
        
        // Insert space before ISO codes and short aliases
        // But NOT before words like "dollar"/"dollars" that might be part of longer aliases
        val currencyPatterns = listOf(
            "usd", "eur", "gbp", "jpy", "try", "cad", "aud", "chf", "cny", "inr",
            "yen", "lira", "euro", "tl"
        )
        
        for (currency in currencyPatterns) {
            // Insert space before currency if preceded by digit or lowercase letter
            // e.g., "100usd" → "100 usd", "100kusd" → "100k usd"
            result = result.replace(Regex("([\\da-z])($currency)", RegexOption.IGNORE_CASE)) { match ->
                val prefix = match.groupValues[1]
                val curr = match.groupValues[2]
                // Don't insert space if prefix is part of a word that could be a multi-word alias
                // Check if preceded by "australian", "canadian", "british", etc.
                val before = result.substring(0, match.range.first).lowercase()
                if (before.endsWith("australian") || before.endsWith("canadian") || before.endsWith("british")) {
                    match.value // Don't change
                } else {
                    "$prefix $curr"
                }
            }
        }
        
        return result
    }

    /**
     * Parse a search query into a currency conversion request using QueryTokenizer.
     * 
     * Uses unified tokenization to handle:
     * - Currency codes/aliases/symbols recognized BEFORE letter-digit mapping
     * - Negative amounts (-, −, and "- amount")
     * - Reversed order (jpy 100, yen 100)
     * - Currency symbols ($, €, £, ¥)
     * - Arithmetic in amounts (100 + 50 yen)
     * - Magnitude suffixes (100k, 1m, 5bn, 2m)
     * - Number words (one million, two thousand, half a million)
     * 
     * @param query The raw search query
     * @param layout The keyboard layout (for BB digit mapping)
     * @param availableCurrencies Set of supported currency codes (from rates)
     * @return Parsed query, or null if not a valid currency query
     */
    fun parse(
        query: String,
        layout: Int = DialNumber.LAYOUT_QWERTY,
        availableCurrencies: Set<String>
    ): CurrencyQuery? {
        if (query.isBlank()) return null
        
        // Pre-process: expand number words and magnitude suffixes, then insert spaces before ISO codes
        var processedQuery = simpleExpandNumberWords(query)
        processedQuery = simpleExpandMagnitudes(processedQuery)
        
        // Insert space before 3-letter ISO codes when directly following digits or other codes
        // e.g., "100usd" → "100 usd", "100usdtry" → "100 usd try"
        // Run multiple times to handle "usdtry" → "usd try"
        val knownIsoCodes = setOf("usd", "eur", "gbp", "jpy", "try", "cad", "aud", "chf", "cny", "inr", "idr")
        for (pass in 1..3) { // Multiple passes to separate chained codes
            for (code in knownIsoCodes) {
                // After digit or after another code
                processedQuery = processedQuery.replace(Regex("(\\d|[a-z]{3})($code)\\b", RegexOption.IGNORE_CASE), "$1 $2")
            }
        }
        
        // Use QueryTokenizer for unified parsing
        val tokens = QueryTokenizer.tokenize(processedQuery, layout) ?: return null
        
        // Reject queries with trailing non-currency words (e.g., "100 yen / 10 abc")
        // Currency Word tokens at the end are OK, but non-currency Word tokens are not
        val lastToken = tokens.lastOrNull()
        if (lastToken is QueryTokenizer.Token.Word) {
            // Check if it's a currency-related word
            val wordLower = lastToken.text.lowercase()
            val isCurrencyWord = CURRENCY_NAME_ALIASES.keys.any { it == wordLower } ||
                                availableCurrencies.any { it.equals(wordLower, ignoreCase = true) }
            if (!isCurrencyWord) {
                return null  // Reject trailing non-currency text
            }
        }
        
        // Extract currencies and keywords from tokens
        val currencies = tokens.filterIsInstance<QueryTokenizer.Token.Currency>()
        val keywords = tokens.filterIsInstance<QueryTokenizer.Token.Keyword>()
        
        // Check if '=' operator is present between currencies (treat as conversion indicator)
        val firstCurrIndex = tokens.indexOfFirst { it is QueryTokenizer.Token.Currency }
        val secondCurrIndex = tokens.indexOfLast { it is QueryTokenizer.Token.Currency }
        val hasEquals = if (firstCurrIndex >= 0 && secondCurrIndex > firstCurrIndex) {
            tokens.slice((firstCurrIndex + 1) until secondCurrIndex).any { 
                it is QueryTokenizer.Token.Operator && it.op == '='
            }
        } else {
            false
        }
        
        // Must have at least one currency
        if (currencies.isEmpty()) return null
        
        // Validate currencies against available rates
        val validCurrencies = currencies.filter { it.code in availableCurrencies }
        if (validCurrencies.isEmpty()) return null
        
        // Determine from/to currencies
        val fromCurrency: String
        val toCurrency: String?
        
        when {
            validCurrencies.size == 1 && !hasEquals -> {
                // Single currency: "100 usd" or "100 yen / 10"
                fromCurrency = validCurrencies[0].code
                toCurrency = null
            }
            validCurrencies.size == 2 && (keywords.isNotEmpty() || hasEquals) -> {
                // Two currencies with keyword or =: "100 usd to try", "100 jpy = try"
                fromCurrency = validCurrencies[0].code
                toCurrency = validCurrencies[1].code
                
                // Reject same-currency conversions (simpler than showing identity)
                if (fromCurrency == toCurrency) return null
            }
            validCurrencies.size == 2 && keywords.isEmpty() && !hasEquals -> {
                // Two currencies without keyword: "100 usd try" (assume conversion)
                fromCurrency = validCurrencies[0].code
                toCurrency = validCurrencies[1].code
                
                // This is ambiguous - could be "100 jpy 200 usd" (two amounts)
                // Check if there are numbers around both currencies
                val firstCurrIndex = tokens.indexOfFirst { it is QueryTokenizer.Token.Currency && it.code == fromCurrency }
                val secondCurrIndex = tokens.indexOfFirst { it is QueryTokenizer.Token.Currency && it.code == toCurrency }
                
                val beforeFirst = tokens.take(firstCurrIndex).any { it is QueryTokenizer.Token.Number }
                val betweenCurr = tokens.slice((firstCurrIndex + 1) until secondCurrIndex).any { it is QueryTokenizer.Token.Number }
                
                // If numbers both before first currency AND between currencies, reject (two amounts)
                if (beforeFirst && betweenCurr) return null
            }
            validCurrencies.size == 1 && hasEquals -> {
                // "100 jpy =" with only one currency - reject (incomplete)
                return null
            }
            else -> {
                // More than 2 currencies - invalid
                return null
            }
        }
        
        // Build amount expression from number and operator tokens  
        val amountTokens = tokens.takeWhile { it !is QueryTokenizer.Token.Currency && it !is QueryTokenizer.Token.Keyword }
        if (amountTokens.isEmpty() || amountTokens.all { it is QueryTokenizer.Token.Operator }) {
            // No amount before currency, check if currency comes first (reversed order)
            val firstCurrencyIndex = tokens.indexOfFirst { it is QueryTokenizer.Token.Currency }
            if (firstCurrencyIndex == 0) {
                // Reversed order: "jpy 100", "yen 100"
                val afterCurrency = tokens.drop(1).takeWhile { it !is QueryTokenizer.Token.Currency && it !is QueryTokenizer.Token.Keyword }
                if (afterCurrency.isEmpty()) return null
                
                val amountExpr = buildAmountExpression(afterCurrency)
                val amount = evaluateAmount(amountExpr) ?: return null
                
                return CurrencyQuery(amount, fromCurrency, toCurrency)
            }
            return null
        }
        
        // Normal order: amount before currency
        val amountExpr = buildAmountExpression(amountTokens)
        
        // Check for arithmetic after currency (e.g., "100 yen / 10")
        val firstCurrencyIndex = tokens.indexOfFirst { it is QueryTokenizer.Token.Currency }
        val afterCurrency = tokens.drop(firstCurrencyIndex + 1)
            .takeWhile { it !is QueryTokenizer.Token.Currency && it !is QueryTokenizer.Token.Keyword }
        
        val fullExpr = if (afterCurrency.isNotEmpty() && afterCurrency.any { it is QueryTokenizer.Token.Operator }) {
            // Has arithmetic after currency
            val rightExpr = buildAmountExpression(afterCurrency)
            if (rightExpr.isNotEmpty()) {
                "$amountExpr$rightExpr"
            } else {
                amountExpr
            }
        } else {
            amountExpr
        }
        
        // Validate: check for text after target currency
        if (toCurrency != null) {
            val secondCurrencyIndex = tokens.indexOfFirst { token ->
                token is QueryTokenizer.Token.Currency && token.code == toCurrency
            }
            if (secondCurrencyIndex >= 0 && secondCurrencyIndex < tokens.size - 1) {
                // Text after target currency - invalid
                return null
            }
        }
        
        val amount = evaluateAmount(fullExpr) ?: return null
        
        return CurrencyQuery(amount, fromCurrency, toCurrency)
    }
    
    /**
     * Build amount expression string from tokens.
     */
    private fun buildAmountExpression(tokens: List<QueryTokenizer.Token>): String {
        val sb = StringBuilder()
        for (token in tokens) {
            when (token) {
                is QueryTokenizer.Token.Number -> sb.append(token.value)
                is QueryTokenizer.Token.Operator -> sb.append(token.op)
                else -> {} // Skip other tokens
            }
        }
        return sb.toString()
    }

    /**
     * Expand magnitude suffixes: k=1e3, m=1e6, b=1e9, bn/mn=billion/million, t=1e12
     * Examples: "100k" -> "100*1000", "1.5m" -> "1.5*1000000"
     * 
     * Context-sensitive handling for BB keyboard:
     * - m is million when trailing (not followed by digits)
     * - m is decimal point when followed by digits (handled separately in BB mapping)
     */
    private fun expandMagnitudeSuffixes(str: String): String {
        if (str.isEmpty()) return str
        
        val result = StringBuilder()
        var i = 0
        
        while (i < str.length) {
            // Check if this is a magnitude suffix
            val remaining = str.substring(i).lowercase()
            val suffix = when {
                remaining.startsWith("bn") -> "bn" to 1_000_000_000.0
                remaining.startsWith("mn") -> "mn" to 1_000_000.0
                remaining.startsWith("b") && (i + 1 >= str.length || !str[i + 1].isDigit()) -> "b" to 1_000_000_000.0
                remaining.startsWith("m") && (i + 1 >= str.length || !str[i + 1].isDigit()) -> "m" to 1_000_000.0
                remaining.startsWith("k") -> "k" to 1_000.0
                remaining.startsWith("t") && (i + 1 >= str.length || !str[i + 1].isDigit()) -> "t" to 1_000_000_000_000.0
                else -> null
            }
            
            if (suffix != null) {
                // We found a suffix - multiply the preceding number
                result.append("*${suffix.second.toLong()}")
                i += suffix.first.length
            } else {
                result.append(str[i])
                i++
            }
        }
        
        return result.toString()
    }

    /**
     * Expand number words to digits.
     * Examples: "one million" -> "1000000", "two thousand" -> "2000", "half a million" -> "500000"
     * Supports: one-twenty, tens, hundred, thousand, million, billion
     */
    private fun expandNumberWords(str: String): String {
        val words = str.lowercase().trim().split(Regex("\\s+"))
        val result = mutableListOf<String>()
        var currentNumber = 0.0
        var pendingMultiplier = 0.0
        var i = 0
        
        while (i < words.size) {
            val word = words[i]
            
            // Try to parse as a regular number first
            val numValue = word.toDoubleOrNull()
            if (numValue != null) {
                if (pendingMultiplier > 0) {
                    currentNumber += numValue
                } else {
                    currentNumber = numValue
                }
                i++
                continue
            }
            
            val digitValue = when (word) {
                "zero" -> 0.0
                "one" -> 1.0
                "a", "an" -> null // "a"/"an" before multiplier means 1, otherwise skip
                "two" -> 2.0
                "three" -> 3.0
                "four" -> 4.0
                "five" -> 5.0
                "six" -> 6.0
                "seven" -> 7.0
                "eight" -> 8.0
                "nine" -> 9.0
                "ten" -> 10.0
                "eleven" -> 11.0
                "twelve" -> 12.0
                "thirteen" -> 13.0
                "fourteen" -> 14.0
                "fifteen" -> 15.0
                "sixteen" -> 16.0
                "seventeen" -> 17.0
                "eighteen" -> 18.0
                "nineteen" -> 19.0
                "twenty" -> 20.0
                "thirty" -> 30.0
                "forty" -> 40.0
                "fifty" -> 50.0
                "sixty" -> 60.0
                "seventy" -> 70.0
                "eighty" -> 80.0
                "ninety" -> 90.0
                "half" -> 0.5
                else -> null
            }
            
            val multiplier = when (word) {
                "hundred" -> 100.0
                "thousand" -> 1_000.0
                "million" -> 1_000_000.0
                "billion" -> 1_000_000_000.0
                else -> null
            }
            
            when {
                digitValue != null -> {
                    if (pendingMultiplier > 0) {
                        currentNumber += digitValue
                    } else {
                        currentNumber = digitValue
                    }
                    i++
                }
                multiplier != null -> {
                    // Multiplier logic: hundred multiplies within group, thousand/million/billion scale
                    if (currentNumber == 0.0) currentNumber = 1.0
                    
                    if (multiplier == 100.0) {
                        // "hundred" multiplies the current number within the group
                        currentNumber *= multiplier
                        i++
                        // Don't flush yet - might be followed by "thousand"
                    } else {
                        // thousand/million/billion - scale the entire group
                        currentNumber *= multiplier
                        i++
                        
                        // Check if next is another number word (continue building)
                        val nextIsNumber = i < words.size && (
                            words[i].toDoubleOrNull() != null ||
                            getDigitValue(words[i]) != null ||
                            words[i] == "hundred"
                        )
                        
                        if (!nextIsNumber) {
                            // Flush the number
                            val value = if (currentNumber == currentNumber.toLong().toDouble()) {
                                currentNumber.toLong().toString()
                            } else {
                                currentNumber.toString()
                            }
                            result.add(value)
                            currentNumber = 0.0
                        }
                    }
                }
                word == "a" || word == "an" -> {
                    // Skip article unless it's before a multiplier
                    i++
                }
                else -> {
                    // Not a number word
                    if (currentNumber > 0 || pendingMultiplier > 0) {
                        val value = if (currentNumber == currentNumber.toLong().toDouble()) {
                            currentNumber.toLong().toString()
                        } else {
                            currentNumber.toString()
                        }
                        result.add(value)
                        currentNumber = 0.0
                        pendingMultiplier = 0.0
                    }
                    result.add(word)
                    i++
                }
            }
        }
        
        // Flush any remaining number
        if (currentNumber > 0 || pendingMultiplier > 0) {
            val value = if (currentNumber == currentNumber.toLong().toDouble()) {
                currentNumber.toLong().toString()
            } else {
                currentNumber.toString()
            }
            result.add(value)
        }
        
        return result.joinToString(" ")
    }
    
    private fun getDigitValue(word: String): Double? = when (word.lowercase()) {
        "zero" -> 0.0
        "one", "a", "an" -> 1.0
        "two" -> 2.0
        "three" -> 3.0
        "four" -> 4.0
        "five" -> 5.0
        "six" -> 6.0
        "seven" -> 7.0
        "eight" -> 8.0
        "nine" -> 9.0
        "ten" -> 10.0
        "eleven" -> 11.0
        "twelve" -> 12.0
        "thirteen" -> 13.0
        "fourteen" -> 14.0
        "fifteen" -> 15.0
        "sixteen" -> 16.0
        "seventeen" -> 17.0
        "eighteen" -> 18.0
        "nineteen" -> 19.0
        "twenty" -> 20.0
        "thirty" -> 30.0
        "forty" -> 40.0
        "fifty" -> 50.0
        "sixty" -> 60.0
        "seventy" -> 70.0
        "eighty" -> 80.0
        "ninety" -> 90.0
        "half" -> 0.5
        else -> null
    }
    
    private fun getMultiplier(word: String): Double? = when (word.lowercase()) {
        "hundred" -> 100.0
        "thousand" -> 1_000.0
        "million" -> 1_000_000.0
        "billion" -> 1_000_000_000.0
        else -> null
    }

    /**
     * Evaluate amount string as a calculator expression.
     * Handles plain numbers and arithmetic expressions.
     */
    private fun evaluateAmount(amountStr: String): Double? {
        if (amountStr.isBlank()) return null
        
        // Try to evaluate as calculator expression
        val result = Calculator.evaluate(amountStr, allowPlainNumber = true) ?: return null
        
        // Parse result back to double
        return try {
            result.replace(",", "").toDouble()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Convert amount from one currency to another using exchange rates.
     * 
     * @param query The parsed currency query
     * @param rates Map of currency code -> rate (relative to base)
     * @param displayCurrencies List of currencies to convert to when no target specified
     * @return List of conversion results (one per target currency)
     */
    fun convert(
        query: CurrencyQuery,
        rates: Map<String, Double>,
        displayCurrencies: List<String>,
        adoptedCurrency: String? = null
    ): List<ConversionResult> {
        val fromRate = rates[query.fromCurrency] ?: return emptyList()
        
        val results = mutableListOf<ConversionResult>()
        
        if (query.toCurrency != null) {
            // Explicit target specified
            val toRate = rates[query.toCurrency] ?: return emptyList()
            
            // R1: If target equals source (same-currency), amount stays as-is
            val converted = if (query.toCurrency == query.fromCurrency) {
                query.amount
            } else {
                query.amount * (toRate / fromRate)
            }
            
            results.add(ConversionResult(
                fromAmount = query.amount,
                fromCurrency = query.fromCurrency,
                toAmount = converted,
                toCurrency = query.toCurrency
            ))
        } else {
            // No explicit target
            // R1: If query matches adopted currency, preserve it first for chaining
            if (adoptedCurrency != null && adoptedCurrency == query.fromCurrency) {
                results.add(ConversionResult(
                    fromAmount = query.amount,
                    fromCurrency = query.fromCurrency,
                    toAmount = query.amount,
                    toCurrency = query.fromCurrency
                ))
            }
            
            // Then show conversions to display currencies (excluding source)
            displayCurrencies
                .filter { it != query.fromCurrency && it in rates }
                .forEach { target ->
                    val toRate = rates[target] ?: return@forEach
                    val converted = query.amount * (toRate / fromRate)
                    results.add(ConversionResult(
                        fromAmount = query.amount,
                        fromCurrency = query.fromCurrency,
                        toAmount = converted,
                        toCurrency = target
                    ))
                }
        }
        
        return results
    }

    /**
     * Format a conversion result for display.
     * Example: "100 USD = 4,130.50 TRY"
     */
    fun formatResult(result: ConversionResult): String {
        val fromFormatted = formatAmount(result.fromAmount)
        val toFormatted = formatAmount(result.toAmount)
        return "$fromFormatted ${result.fromCurrency} = $toFormatted ${result.toCurrency}"
    }

    /**
     * Format a currency amount with locale-appropriate formatting.
     * Uses comma for thousands separator and period for decimal point.
     */
    private fun formatAmount(amount: Double): String {
        val symbols = DecimalFormatSymbols(Locale.US).apply {
            groupingSeparator = ','
            decimalSeparator = '.'
        }
        
        val formatter = if (amount == amount.toLong().toDouble()) {
            // Whole number
            DecimalFormat("#,##0", symbols)
        } else {
            // Decimal number - show up to 2 decimal places, trim trailing zeros
            DecimalFormat("#,##0.##", symbols)
        }
        
        return formatter.format(amount)
    }

    /**
     * Get a formatted date string for "rates as of" hint.
     */
    fun formatRatesDate(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val ageMs = now - timestamp
        val ageHours = ageMs / (60 * 60 * 1000)
        
        return when {
            ageHours < 1 -> "just now"
            ageHours < 24 -> "${ageHours}h ago"
            else -> {
                val days = ageHours / 24
                "${days}d ago"
            }
        }
    }
}
