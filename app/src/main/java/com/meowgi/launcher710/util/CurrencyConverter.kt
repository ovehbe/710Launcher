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
     */
    private val CURRENCY_NAME_ALIASES = mapOf(
        "dollars" to "USD",
        "dollar" to "USD",
        "euros" to "EUR",
        "euro" to "EUR",
        "pounds" to "GBP",
        "pound" to "GBP",
        "yuan" to "CNY",
        "yen" to "JPY"
    )

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
     * Parse a search query into a currency conversion request.
     * 
     * Strategy:
     * 1. Match keywords (to/in) and 3-letter currency codes as LITERAL words first
     * 2. Extract the amount token
     * 3. Apply BB keyboard mapping to the amount part only
     * 4. Evaluate amount as calculator expression if needed
     * 5. Validate currency codes against available rates
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
        
        val normalized = query.trim().lowercase()
        
        // Pattern: [amount] [from_currency] [optional: to/in] [optional: to_currency]
        // Currency codes are 3-letter words, keywords are "to" or "in"
        
        // First, search for currency names (longer matches first to avoid partial matches)
        val nameMatches = mutableListOf<Pair<Int, String>>()
        for ((name, code) in CURRENCY_NAME_ALIASES.entries.sortedByDescending { it.key.length }) {
            var pos = 0
            while (pos <= normalized.length - name.length) {
                val found = normalized.indexOf(name, pos)
                if (found < 0) break
                // Check word boundaries
                val beforeOk = found == 0 || !normalized[found - 1].isLetter()
                val afterOk = found + name.length >= normalized.length || !normalized[found + name.length].isLetter()
                if (beforeOk && afterOk && code in availableCurrencies) {
                    nameMatches.add(found to code)
                    pos = found + name.length
                } else {
                    pos = found + 1
                }
            }
        }
        
        // Then, find 3-letter ISO currency codes
        val codeMatches = mutableListOf<Pair<Int, String>>()
        var i = 0
        while (i <= normalized.length - 3) {
            val threeLetters = normalized.substring(i, i + 3)
            if (threeLetters.all { it.isLetter() }) {
                val code = threeLetters.uppercase()
                if (code in availableCurrencies) {
                    codeMatches.add(i to code)
                    i += 3 // Skip past this match
                    continue
                }
            }
            i++
        }
        
        // Merge name and code matches, removing overlaps (prefer longer name matches)
        val currencyMatches = mutableListOf<Pair<Int, String>>()
        val allMatches = (nameMatches + codeMatches).sortedBy { it.first }
        var lastEnd = -1
        for (match in allMatches) {
            if (match.first >= lastEnd) {
                currencyMatches.add(match)
                // Calculate match end based on original name/code length in normalized string
                val matchedText = when {
                    nameMatches.contains(match) -> {
                        // Find the name that produced this code
                        CURRENCY_NAME_ALIASES.entries.find { it.value == match.second }?.key?.length ?: 3
                    }
                    else -> 3 // ISO code
                }
                lastEnd = match.first + matchedText
            }
        }
        
        if (currencyMatches.isEmpty()) return null
        
        // Find "to" or "in" keywords
        var keywordPos: Int? = null
        var keywordLength = 0
        listOf("to", "in").forEach { keyword ->
            val pos = normalized.indexOf(keyword)
            if (pos >= 0) {
                // Check if it's a word boundary (not part of another word)
                val beforeOk = pos == 0 || !normalized[pos - 1].isLetter()
                val afterOk = pos + keyword.length >= normalized.length || !normalized[pos + keyword.length].isLetter()
                if (beforeOk && afterOk) {
                    keywordPos = pos
                    keywordLength = keyword.length
                }
            }
        }
        
        // Determine from/to currencies based on positions
        val fromCurrency: String
        val toCurrency: String?
        val firstCurrencyStart: Int
        val firstCurrencyEnd: Int
        
        when {
            currencyMatches.size == 1 -> {
                // Single currency: "100 usd" or "100 yen / 10"
                fromCurrency = currencyMatches[0].second
                toCurrency = null
                firstCurrencyStart = currencyMatches[0].first
                // Find the actual end position of the currency text in normalized string
                firstCurrencyEnd = findCurrencyEndPosition(normalized, currencyMatches[0])
            }
            currencyMatches.size == 2 -> {
                // Two currencies
                val first = currencyMatches[0]
                val second = currencyMatches[1]
                
                // If there's a keyword between them, use it as delimiter
                if (keywordPos != null && keywordPos!! > first.first && keywordPos!! < second.first) {
                    fromCurrency = first.second
                    toCurrency = second.second
                    firstCurrencyStart = first.first
                    firstCurrencyEnd = findCurrencyEndPosition(normalized, first)
                } else {
                    // No keyword or keyword not between them - assume "100 usd try"
                    fromCurrency = first.second
                    toCurrency = second.second
                    firstCurrencyStart = first.first
                    firstCurrencyEnd = findCurrencyEndPosition(normalized, first)
                }
            }
            else -> {
                // More than 2 currencies - invalid
                return null
            }
        }
        
        if (firstCurrencyStart == 0) return null
        
        // Extract amount: left part (before currency) + right part (arithmetic after currency)
        val leftPart = normalized.substring(0, firstCurrencyStart).trim()
        
        // Extract right part: arithmetic after currency (before keyword or second currency)
        var rightEnd = normalized.length
        if (toCurrency != null) {
            // Find position of "to"/"in" keyword or second currency, whichever comes first
            if (keywordPos != null && keywordPos!! > firstCurrencyEnd) {
                rightEnd = keywordPos!!
            } else {
                // Find second currency position
                if (currencyMatches.size >= 2) {
                    rightEnd = currencyMatches[1].first
                }
            }
        }
        
        val rightPart = normalized.substring(firstCurrencyEnd, rightEnd).trim()
        
        // Filter rightPart to only include arithmetic operators, numbers, whitespace, parens
        // Stop at first keyword or letter that isn't an operator
        val arithmeticChars = setOf('+', '-', '*', '/', '%', '(', ')', '.', ',', ' ', '\t')
        val rightArithmetic = rightPart.takeWhile { ch ->
            ch.isDigit() || ch in arithmeticChars
        }.trim()
        
        // Combine left + right arithmetic
        val amountStr = if (rightArithmetic.isNotEmpty()) {
            "$leftPart $rightArithmetic".trim()
        } else {
            leftPart
        }
        
        // Apply BB keyboard mapping to amount string
        val mappedAmount = mapAmountString(amountStr, layout) ?: return null
        
        // Evaluate amount as calculator expression
        val amount = evaluateAmount(mappedAmount) ?: return null
        
        return CurrencyQuery(amount, fromCurrency, toCurrency)
    }

    /**
     * Find the end position of a currency match in the normalized string.
     * For ISO codes, it's start + 3.
     * For name aliases, we need to find which name was matched.
     */
    private fun findCurrencyEndPosition(normalized: String, match: Pair<Int, String>): Int {
        val start = match.first
        val code = match.second
        
        // Check if this was matched by a currency name
        for ((name, aliasCode) in CURRENCY_NAME_ALIASES) {
            if (aliasCode == code && start + name.length <= normalized.length) {
                val substring = normalized.substring(start, start + name.length)
                if (substring == name) {
                    return start + name.length
                }
            }
        }
        
        // Otherwise, it's a 3-letter ISO code
        return start + 3
    }

    /**
     * Map BB keyboard letters to digits/operators in amount string only.
     * Handles magnitude suffixes (k, m, b) and number words.
     * 
     * Strategy:
     * - Map each character: digits stay, BB letters map to their values
     * - ONLY trailing k/m/b/bn/mn (at end of amount) are magnitude suffixes
     * - All other letters map through BB keyboard
     */
    private fun mapAmountString(str: String, layout: Int): String? {
        if (str.isEmpty()) return null
        
        // First, expand number words (e.g., "one million" -> "1000000")
        val withWords = expandNumberWords(str)
        
        // Check for trailing magnitude suffix (k, m, b, bn, mn) at the END
        val trimmed = withWords.trim()
        val hasBn = trimmed.endsWith("bn", ignoreCase = true)
        val hasMn = trimmed.endsWith("mn", ignoreCase = true)
        val hasK = !hasBn && !hasMn && trimmed.endsWith("k", ignoreCase = true)
        val hasM = !hasBn && !hasMn && trimmed.endsWith("m", ignoreCase = true)
        val hasB = !hasBn && !hasMn && trimmed.endsWith("b", ignoreCase = true)
        
        val (baseAmount, multiplier) = when {
            hasBn -> trimmed.dropLast(2) to "*1000000000"
            hasMn -> trimmed.dropLast(2) to "*1000000"
            hasK -> trimmed.dropLast(1) to "*1000"
            hasM -> trimmed.dropLast(1) to "*1000000"
            hasB -> trimmed.dropLast(1) to "*1000000000"
            else -> trimmed to ""
        }
        
        // Now map BB keyboard letters in baseAmount
        val sb = StringBuilder(baseAmount.length * 2)
        var hasDigit = false
        
        for (ch in baseAmount) {
            when {
                ch in '0'..'9' -> { sb.append(ch); hasDigit = true }
                ch == '+' || ch == '-' || ch == '*' || ch == '/' || ch == '%' -> sb.append(ch)
                ch == '.' || ch == ',' -> sb.append(ch)
                ch == '(' || ch == ')' -> sb.append(ch)
                ch == ' ' || ch == '\t' -> {} // ignore whitespace
                else -> {
                    // Map letter to operator/digit via BB keyboard
                    val mapped = Calculator.operatorChar(ch, layout)
                    if (mapped != null) {
                        sb.append(mapped)
                        if (mapped in '0'..'9') hasDigit = true
                    } else {
                        // Character can't be mapped
                        return null
                    }
                }
            }
        }
        
        // Append multiplier if any
        sb.append(multiplier)
        
        return if (hasDigit) sb.toString() else null
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
        displayCurrencies: List<String>
    ): List<ConversionResult> {
        val fromRate = rates[query.fromCurrency] ?: return emptyList()
        
        val targetCurrencies = if (query.toCurrency != null) {
            // Single target specified
            listOf(query.toCurrency)
        } else {
            // Convert to all display currencies except source
            displayCurrencies.filter { it != query.fromCurrency }
        }
        
        val results = mutableListOf<ConversionResult>()
        for (target in targetCurrencies) {
            val toRate = rates[target] ?: continue
            
            // Convert: amount * (toRate / fromRate)
            val converted = query.amount * (toRate / fromRate)
            
            results.add(ConversionResult(
                fromAmount = query.amount,
                fromCurrency = query.fromCurrency,
                toAmount = converted,
                toCurrency = target
            ))
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
