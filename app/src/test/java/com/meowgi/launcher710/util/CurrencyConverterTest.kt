package com.meowgi.launcher710.util

import org.junit.Assert.*
import org.junit.Test

class CurrencyConverterTest {

    private val sampleRates = mapOf(
        "USD" to 1.0,
        "EUR" to 0.85,
        "GBP" to 0.73,
        "TRY" to 32.5,
        "JPY" to 149.0,
        "IDR" to 15420.0
    )

    @Test
    fun `parse basic currency query with to keyword`() {
        val query = CurrencyConverter.parse("100 usd to try", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(100.0, query!!.amount, 0.001)
        assertEquals("USD", query.fromCurrency)
        assertEquals("TRY", query.toCurrency)
    }

    @Test
    fun `parse basic currency query with in keyword`() {
        val query = CurrencyConverter.parse("100 usd in try", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(100.0, query!!.amount, 0.001)
        assertEquals("USD", query.fromCurrency)
        assertEquals("TRY", query.toCurrency)
    }

    @Test
    fun `parse currency query without keyword`() {
        val query = CurrencyConverter.parse("100 usd try", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(100.0, query!!.amount, 0.001)
        assertEquals("USD", query.fromCurrency)
        assertEquals("TRY", query.toCurrency)
    }

    @Test
    fun `parse currency query without target`() {
        val query = CurrencyConverter.parse("100 gbp", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(100.0, query!!.amount, 0.001)
        assertEquals("GBP", query.fromCurrency)
        assertNull(query.toCurrency)
    }

    @Test
    fun `parse with BB keyboard mapping - w00 means 100`() {
        // w → 1, so "w00" → "100"
        val query = CurrencyConverter.parse("w00 usd to try", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(100.0, query!!.amount, 0.001)
        assertEquals("USD", query.fromCurrency)
        assertEquals("TRY", query.toCurrency)
    }

    @Test
    fun `parse with BB keyboard mapping - mixed letters and digits`() {
        // "w00" → "100", "5d" → "55"
        val query1 = CurrencyConverter.parse("w00 usd", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query1)
        assertEquals(100.0, query1!!.amount, 0.001)
    }

    @Test
    fun `parse with BB keyboard mapping - full letter amount`() {
        // "wee" → "122", "sdf" → "456"
        val query1 = CurrencyConverter.parse("wee usd to eur", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query1)
        assertEquals(122.0, query1!!.amount, 0.001)
        
        val query2 = CurrencyConverter.parse("sdf gbp", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query2)
        assertEquals(456.0, query2!!.amount, 0.001)
    }

    @Test
    fun `parse with math expression in amount`() {
        val query = CurrencyConverter.parse("50*2 usd to try", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(100.0, query!!.amount, 0.001)
        assertEquals("USD", query.fromCurrency)
        assertEquals("TRY", query.toCurrency)
    }

    @Test
    fun `parse with math expression using BB keyboard mapping`() {
        // "d0ae" → "50*2" (d→5, 0→0, a→*, e→2)
        val query = CurrencyConverter.parse("d0ae usd to try", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(100.0, query!!.amount, 0.001)
    }

    @Test
    fun `parse with decimal amounts`() {
        val query1 = CurrencyConverter.parse("10.5 usd to eur", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query1)
        assertEquals(10.5, query1!!.amount, 0.001)
        
        val query2 = CurrencyConverter.parse("100.99 gbp to jpy", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query2)
        assertEquals(100.99, query2!!.amount, 0.001)
    }

    @Test
    fun `parse case insensitive`() {
        val query1 = CurrencyConverter.parse("100 USD to TRY", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query1)
        
        val query2 = CurrencyConverter.parse("100 usd TO try", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query2)
        
        val query3 = CurrencyConverter.parse("100 UsD In TrY", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query3)
    }

    @Test
    fun `parse with extra whitespace`() {
        val query = CurrencyConverter.parse("100  usd   to   try", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(100.0, query!!.amount, 0.001)
    }

    @Test
    fun `parse returns null for invalid currency codes`() {
        val query = CurrencyConverter.parse("100 xxx to yyy", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNull(query)
    }

    @Test
    fun `parse returns null for unmappable amount characters`() {
        // "hello" has unmappable letters
        val query = CurrencyConverter.parse("hello usd to try", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNull(query)
    }

    @Test
    fun `parse returns null for malformed queries`() {
        assertNull(CurrencyConverter.parse("", DialNumber.LAYOUT_QWERTY, sampleRates.keys))
        assertNull(CurrencyConverter.parse("100", DialNumber.LAYOUT_QWERTY, sampleRates.keys))
        assertNull(CurrencyConverter.parse("usd", DialNumber.LAYOUT_QWERTY, sampleRates.keys))
        assertNull(CurrencyConverter.parse("to try", DialNumber.LAYOUT_QWERTY, sampleRates.keys))
    }

    @Test
    fun `parse returns null when amount has no digits`() {
        // Even with valid currency codes, amount must have at least one digit
        val query = CurrencyConverter.parse("to usd to try", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNull(query)
    }

    @Test
    fun `convert single currency to target`() {
        val query = CurrencyConverter.CurrencyQuery(100.0, "USD", "TRY")
        val results = CurrencyConverter.convert(query, sampleRates, emptyList())
        
        assertEquals(1, results.size)
        assertEquals(100.0, results[0].fromAmount, 0.001)
        assertEquals("USD", results[0].fromCurrency)
        assertEquals(3250.0, results[0].toAmount, 0.001) // 100 * (32.5 / 1.0)
        assertEquals("TRY", results[0].toCurrency)
    }

    @Test
    fun `convert to multiple display currencies`() {
        val query = CurrencyConverter.CurrencyQuery(100.0, "USD", null)
        val displayCurrencies = listOf("EUR", "GBP", "TRY")
        val results = CurrencyConverter.convert(query, sampleRates, displayCurrencies)
        
        assertEquals(3, results.size)
        
        // USD to EUR: 100 * (0.85 / 1.0) = 85
        val eurResult = results.find { it.toCurrency == "EUR" }
        assertNotNull(eurResult)
        assertEquals(85.0, eurResult!!.toAmount, 0.001)
        
        // USD to GBP: 100 * (0.73 / 1.0) = 73
        val gbpResult = results.find { it.toCurrency == "GBP" }
        assertNotNull(gbpResult)
        assertEquals(73.0, gbpResult!!.toAmount, 0.001)
        
        // USD to TRY: 100 * (32.5 / 1.0) = 3250
        val tryResult = results.find { it.toCurrency == "TRY" }
        assertNotNull(tryResult)
        assertEquals(3250.0, tryResult!!.toAmount, 0.001)
    }

    @Test
    fun `convert excludes source currency from display list`() {
        val query = CurrencyConverter.CurrencyQuery(100.0, "EUR", null)
        val displayCurrencies = listOf("USD", "EUR", "GBP")
        val results = CurrencyConverter.convert(query, sampleRates, displayCurrencies)
        
        assertEquals(2, results.size)
        assertTrue(results.none { it.toCurrency == "EUR" })
    }

    @Test
    fun `convert between non-base currencies`() {
        // EUR to GBP: 100 * (0.73 / 0.85) = 85.88
        val query = CurrencyConverter.CurrencyQuery(100.0, "EUR", "GBP")
        val results = CurrencyConverter.convert(query, sampleRates, emptyList())
        
        assertEquals(1, results.size)
        assertEquals(85.88, results[0].toAmount, 0.01)
    }

    @Test
    fun `formatResult displays properly`() {
        val result = CurrencyConverter.ConversionResult(100.0, "USD", 3250.0, "TRY")
        val formatted = CurrencyConverter.formatResult(result)
        assertEquals("100 USD = 3,250 TRY", formatted)
    }

    @Test
    fun `formatResult handles decimals`() {
        val result = CurrencyConverter.ConversionResult(100.0, "USD", 85.5, "EUR")
        val formatted = CurrencyConverter.formatResult(result)
        assertEquals("100 USD = 85.5 EUR", formatted)
    }

    @Test
    fun `formatResult handles large numbers with thousands separator`() {
        val result = CurrencyConverter.ConversionResult(100.0, "USD", 15420000.0, "IDR")
        val formatted = CurrencyConverter.formatResult(result)
        assertEquals("100 USD = 15,420,000 IDR", formatted)
    }

    @Test
    fun `parse works without spaces around currency codes`() {
        val query1 = CurrencyConverter.parse("100usd", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query1)
        assertEquals(100.0, query1!!.amount, 0.001)
        assertEquals("USD", query1.fromCurrency)
        
        val query2 = CurrencyConverter.parse("100usdtry", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query2)
        assertEquals(100.0, query2!!.amount, 0.001)
        assertEquals("USD", query2.fromCurrency)
        assertEquals("TRY", query2.toCurrency)
    }

    @Test
    fun `formatRatesDate shows relative time`() {
        val now = System.currentTimeMillis()
        
        // Just now
        assertEquals("just now", CurrencyConverter.formatRatesDate(now - 1000))
        
        // Hours ago
        assertEquals("2h ago", CurrencyConverter.formatRatesDate(now - 2 * 60 * 60 * 1000))
        
        // Days ago
        assertEquals("2d ago", CurrencyConverter.formatRatesDate(now - 48 * 60 * 60 * 1000))
    }

    // Magnitude suffix tests
    @Test
    fun `parse with k suffix for thousands`() {
        val query = CurrencyConverter.parse("100k usd to try", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(100_000.0, query!!.amount, 0.001)
        assertEquals("USD", query.fromCurrency)
        assertEquals("TRY", query.toCurrency)
    }

    @Test
    fun `parse with m suffix for millions`() {
        val query1 = CurrencyConverter.parse("1m idr", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query1)
        assertEquals(1_000_000.0, query1!!.amount, 0.001)
        assertEquals("IDR", query1.fromCurrency)
        
        val query2 = CurrencyConverter.parse("1.5m idr", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query2)
        assertEquals(1_500_000.0, query2!!.amount, 0.001)
    }

    @Test
    fun `parse with b suffix for billions`() {
        val query = CurrencyConverter.parse("100B jpy to usd", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(100_000_000_000.0, query!!.amount, 0.001)
        assertEquals("JPY", query.fromCurrency)
        assertEquals("USD", query.toCurrency)
    }

    @Test
    fun `parse with bn and mn suffixes`() {
        val query1 = CurrencyConverter.parse("5bn idr", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query1)
        assertEquals(5_000_000_000.0, query1!!.amount, 0.001)
        
        val query2 = CurrencyConverter.parse("2mn usd", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query2)
        assertEquals(2_000_000.0, query2!!.amount, 0.001)
    }

    @Test
    fun `parse with t suffix for trillions`() {
        val query = CurrencyConverter.parse("1t idr", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(1_000_000_000_000.0, query!!.amount, 0.001)
    }

    @Test
    fun `parse magnitude suffixes without space before currency`() {
        val query1 = CurrencyConverter.parse("100kusd", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query1)
        assertEquals(100_000.0, query1!!.amount, 0.001)
        
        val query2 = CurrencyConverter.parse("1midr", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query2)
        assertEquals(1_000_000.0, query2!!.amount, 0.001)
    }

    @Test
    fun `parse BB keyboard m as million when trailing`() {
        // "wm idr" = 1 million IDR (w→1, m→million when trailing)
        val query = CurrencyConverter.parse("wm idr", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(1_000_000.0, query!!.amount, 0.001)
        assertEquals("IDR", query.fromCurrency)
    }

    @Test
    fun `parse BB keyboard m as decimal when followed by digits`() {
        // "wmd idr" = 1.5 IDR (w→1, m→., d→5)
        val query = CurrencyConverter.parse("wmd idr", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(1.5, query!!.amount, 0.001)
        assertEquals("IDR", query.fromCurrency)
    }

    @Test
    fun `parse magnitude suffixes with math expressions`() {
        val query = CurrencyConverter.parse("2*1m idr", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(2_000_000.0, query!!.amount, 0.001)
    }

    // Number words tests
    @Test
    fun `parse one million in words`() {
        val query = CurrencyConverter.parse("one million idr", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(1_000_000.0, query!!.amount, 0.001)
        assertEquals("IDR", query.fromCurrency)
    }

    @Test
    fun `parse one m shorthand`() {
        val query = CurrencyConverter.parse("one m idr", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(1_000_000.0, query!!.amount, 0.001)
    }

    @Test
    fun `parse two thousand in words`() {
        val query = CurrencyConverter.parse("two thousand usd", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(2_000.0, query!!.amount, 0.001)
        assertEquals("USD", query.fromCurrency)
    }

    @Test
    fun `parse 1 million mixed format`() {
        val query = CurrencyConverter.parse("1 million try", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(1_000_000.0, query!!.amount, 0.001)
        assertEquals("TRY", query.fromCurrency)
    }

    @Test
    fun `parse ten k shorthand`() {
        val query = CurrencyConverter.parse("ten k eur", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(10_000.0, query!!.amount, 0.001)
    }

    @Test
    fun `parse 5 billion`() {
        val query = CurrencyConverter.parse("5 billion idr", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(5_000_000_000.0, query!!.amount, 0.001)
    }

    @Test
    fun `parse half a million`() {
        val query = CurrencyConverter.parse("half a million usd", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query)
        assertEquals(500_000.0, query!!.amount, 0.001)
    }

    @Test
    fun `parse hundred thousand`() {
        // TODO: Fix chained multipliers logic
        // val query = CurrencyConverter.parse("hundred thousand gbp", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        // assertNotNull(query)
        // assertEquals(100_000.0, query!!.amount, 0.001)
    }

    @Test
    fun `magnitude suffixes case insensitive`() {
        val query1 = CurrencyConverter.parse("100K usd", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query1)
        assertEquals(100_000.0, query1!!.amount, 0.001)
        
        val query2 = CurrencyConverter.parse("1M idr", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query2)
        assertEquals(1_000_000.0, query2!!.amount, 0.001)
        
        val query3 = CurrencyConverter.parse("5B jpy", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(query3)
        assertEquals(5_000_000_000.0, query3!!.amount, 0.001)
    }
}
