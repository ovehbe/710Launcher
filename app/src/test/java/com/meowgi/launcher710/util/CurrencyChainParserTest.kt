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
    fun `R1 FAILING - adopted result conversion should include source currency`() {
        // After adopting "3250 try", user appends " / 2"
        val parsed = CurrencyConverter.parse("3250 try / 2", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(parsed)
        
        // Convert with USD as default target
        val displayCurrencies = listOf("USD", "EUR", "GBP")
        val results = CurrencyConverter.convert(parsed!!, sampleRates, displayCurrencies)
        
        // EXPECTED: First result should be 1625 TRY (staying in source currency)
        // ACTUAL: TRY is excluded from displayCurrencies by convert()
        assertTrue("Results should not be empty", results.isNotEmpty())
        
        val firstResult = results.first()
        assertEquals("First result should stay in TRY", "TRY", firstResult.toCurrency)
        assertEquals(1625.0, firstResult.toAmount, 0.001)
    }
    
    @Test
    fun `original unadopted conversion still works`() {
        // User types fresh "100 yen / 10" (not adopted result)
        val parsed = CurrencyConverter.parse("100 yen / 10", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(parsed)
        assertEquals("JPY", parsed!!.fromCurrency)
        assertEquals(10.0, parsed.amount, 0.001)
        
        // R1: Now shows JPY first (for adoption), then converts to USD/EUR/GBP
        val displayCurrencies = listOf("USD", "EUR", "GBP")
        val results = CurrencyConverter.convert(parsed, sampleRates, displayCurrencies)
        
        assertTrue("Should have results", results.isNotEmpty())
        
        // First result is JPY (source, for potential adoption)
        assertEquals("First result should be JPY", "JPY", results.first().toCurrency)
        assertEquals(10.0, results.first().toAmount, 0.001)
        
        // Subsequent results are conversions
        assertTrue("Should have conversions", results.size > 1)
        assertTrue("Second result should be display currency",
            results[1].toCurrency in displayCurrencies)
    }
}
