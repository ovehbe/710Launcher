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
        
        // First, try to find currency codes and keywords in the string
        // Currency codes are 3 consecutive letters that match available currencies
        val currencyMatches = mutableListOf<Pair<Int, String>>()
        var i = 0
        while (i <= normalized.length - 3) {
            val threeLetters = normalized.substring(i, i + 3)
            if (threeLetters.all { it.isLetter() }) {
                val code = threeLetters.uppercase()
                if (code in availableCurrencies) {
                    currencyMatches.add(i to code)
                    i += 3 // Skip past this match
                    continue
                }
            }
            i++
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
        val amountEnd: Int
        
        when {
            currencyMatches.size == 1 -> {
                // Single currency: "100 usd" or "100usd"
                fromCurrency = currencyMatches[0].second
                toCurrency = null
                amountEnd = currencyMatches[0].first
            }
            currencyMatches.size == 2 -> {
                // Two currencies
                val first = currencyMatches[0]
                val second = currencyMatches[1]
                
                // If there's a keyword between them, use it as delimiter
                if (keywordPos != null && keywordPos!! > first.first && keywordPos!! < second.first) {
                    fromCurrency = first.second
                    toCurrency = second.second
                    amountEnd = first.first
                } else {
                    // No keyword or keyword not between them - assume "100 usd try"
                    fromCurrency = first.second
                    toCurrency = second.second
                    amountEnd = first.first
                }
            }
            else -> {
                // More than 2 currencies - invalid
                return null
            }
        }
        
        if (amountEnd == 0) return null
        
        // Split into tokens for old logic compatibility
        val tokens = normalized.split(Regex("\\s+"))
        if (tokens.isEmpty()) return null
        
        // Extract amount string (everything before first currency)
        val amountStr = normalized.substring(0, amountEnd)
        
        // Apply BB keyboard mapping to amount string
        val mappedAmount = mapAmountString(amountStr, layout) ?: return null
        
        // Evaluate amount as calculator expression
        val amount = evaluateAmount(mappedAmount) ?: return null
        
        return CurrencyQuery(amount, fromCurrency, toCurrency)
    }

    /**
     * Map BB keyboard letters to digits/operators in amount string only.
     * Similar to Calculator.toExpression but returns the mapped string.
     */
    private fun mapAmountString(str: String, layout: Int): String? {
        if (str.isEmpty()) return null
        val sb = StringBuilder(str.length)
        var hasDigit = false
        
        for (ch in str) {
            when {
                ch in '0'..'9' -> { sb.append(ch); hasDigit = true }
                ch == '+' || ch == '-' || ch == '*' || ch == '/' || ch == '%' -> sb.append(ch)
                ch == '.' || ch == ',' -> sb.append(ch)
                ch == '(' || ch == ')' -> sb.append(ch)
                ch == ' ' || ch == '\t' -> {} // ignore whitespace
                else -> {
                    // Try to map letter to operator/digit
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
        
        return if (hasDigit) sb.toString() else null
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
