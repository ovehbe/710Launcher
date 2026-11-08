package com.meowgi.launcher710.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for R1: Target-preserving currency chain parsing.
 * 
 * FAILING: Demonstrates that "3250 try / 2" doesn't parse with try as target,
 * causing the convert() function to exclude TRY from results.
 */
class CurrencyChainParserTest {
    
    private val sampleRates = mapOf(
        "USD" to 1.0,
        "EUR" to 0.85,
        "GBP" to 0.73,
        "TRY" to 32.5,
        "JPY" to 149.0,
        "IDR" to 15420.0
    )
    
    @Test
    fun `R1 single currency with arithmetic preserves currency in results`() {
        // User types "100 usd to try" → adopts "3250 try"
        // User appends " / 2"
        // Should show "1625 try" as first result
        
        val query = CurrencyConverter.parse("3250 try / 2", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        
        assertNotNull("Should parse", query)
        assertEquals("TRY", query!!.fromCurrency)
        assertEquals(1625.0, query.amount, 0.001)
        
        // R1 solution: toCurrency is null, but convert() shows source currency first
        assertNull("Single currency has no explicit target", query.toCurrency)
    }
    
    @Test
    fun `R1 adopted result conversion preserves currency when adoptedCurrency passed`() {
        // After adopting "3250 try", user appends " / 2"
        val parsed = CurrencyConverter.parse("3250 try / 2", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(parsed)
        
        // Convert with adoptedCurrency=TRY to preserve it
        val displayCurrencies = listOf("USD", "EUR", "GBP")
        val results = CurrencyConverter.convert(parsed!!, sampleRates, displayCurrencies, adoptedCurrency = "TRY")
        
        // Should include TRY first, then conversions
        assertTrue("Results should not be empty", results.isNotEmpty())
        
        val firstResult = results.first()
        assertEquals("First result should stay in TRY", "TRY", firstResult.toCurrency)
        assertEquals(1625.0, firstResult.toAmount, 0.001)
    }
    
    @Test
    fun `original unadopted conversion converts to display targets`() {
        // User types fresh "100 yen / 10" (not adopted result)
        val parsed = CurrencyConverter.parse("100 yen / 10", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(parsed)
        assertEquals("JPY", parsed!!.fromCurrency)
        assertEquals(10.0, parsed.amount, 0.001)
        
        // Without adoptedCurrency, converts to display targets only (no JPY)
        val displayCurrencies = listOf("USD", "EUR", "GBP")
        val results = CurrencyConverter.convert(parsed, sampleRates, displayCurrencies)
        
        assertTrue("Should have results", results.isNotEmpty())
        assertEquals("Should have 3 conversions", 3, results.size)
        
        // All results should be in display currencies, not JPY
        assertTrue("All results should be display currencies",
            results.all { it.toCurrency in displayCurrencies })
        assertFalse("Should not include JPY",
            results.any { it.toCurrency == "JPY" })
    }
}
