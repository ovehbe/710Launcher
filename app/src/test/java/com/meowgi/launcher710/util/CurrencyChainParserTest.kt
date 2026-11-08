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
    fun `R1 FAILING - single currency with arithmetic should preserve target currency`() {
        // User types "100 usd to try" → adopts "3250 try"
        // User appends " / 2"
        // Parser should recognize "3250 try / 2" needs to stay in TRY
        
        val query = CurrencyConverter.parse("3250 try / 2", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        
        assertNotNull("Should parse", query)
        assertEquals("TRY", query!!.fromCurrency)
        
        // EXPECTED: toCurrency should be TRY to preserve the unit
        // ACTUAL: toCurrency is null for single-currency queries
        assertEquals("Should preserve TRY as target", "TRY", query.toCurrency)
        
        // Amount should be 3250 / 2 = 1625
        assertEquals(1625.0, query.amount, 0.001)
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
        // Should convert to default targets as before
        val parsed = CurrencyConverter.parse("100 yen / 10", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull(parsed)
        assertEquals("JPY", parsed!!.fromCurrency)
        assertEquals(10.0, parsed.amount, 0.001)
        
        // Should convert to USD/EUR/GBP (not JPY)
        val displayCurrencies = listOf("USD", "EUR", "GBP")
        val results = CurrencyConverter.convert(parsed, sampleRates, displayCurrencies)
        
        assertTrue("Should have results", results.isNotEmpty())
        // First result should be in one of the display currencies, not JPY
        assertTrue("Should convert to display currency",
            results.first().toCurrency in displayCurrencies)
    }
}
