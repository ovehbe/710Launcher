package com.meowgi.launcher710.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for R3: Rejecting ambiguous multi-currency arithmetic.
 * 
 * FAILING: Demonstrates that "100 usd + 50 eur" incorrectly parses as "150 usd to eur"
 */
class CurrencyMultiUnitArithmeticTest {
    
    private val sampleRates = mapOf(
        "USD" to 1.0,
        "EUR" to 0.85,
        "GBP" to 0.73,
        "TRY" to 32.5,
        "JPY" to 149.0,
        "IDR" to 15420.0
    )
    
    @Test
    fun `R3 FAILING - multi-currency arithmetic should be rejected`() {
        // "100 usd + 50 eur" is ambiguous - cannot add different units
        val query = CurrencyConverter.parse("100 usd + 50 eur", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        
        // EXPECTED: null (rejected as ambiguous)
        // ACTUAL: parses as from=USD, to=EUR, amount=150 (wrong!)
        assertNull("Should reject multi-currency arithmetic", query)
    }
    
    @Test
    fun `R3 FAILING - multi-currency in parentheses should be rejected`() {
        val query = CurrencyConverter.parse("(100 usd + 50 eur) * 2", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNull("Should reject", query)
    }
    
    @Test
    fun `R3 text after target currency should be rejected`() {
        // "100 usd to eur abc" has trailing "abc"
        val query1 = CurrencyConverter.parse("100 usd to eur abc", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNull("Should reject trailing text after target", query1)
        
        // "100 usd to eur * 2" has arithmetic after target
        val query2 = CurrencyConverter.parse("100 usd to eur * 2", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNull("Should reject arithmetic after target", query2)
    }
    
    @Test
    fun `R3 valid shorthand still works`() {
        // "100 tl usd" (no arithmetic) is valid shorthand
        val query = CurrencyConverter.parse("100 tl usd", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull("Shorthand should work", query)
        assertEquals("TRY", query!!.fromCurrency)
        assertEquals("USD", query.toCurrency)
        assertEquals(100.0, query.amount, 0.001)
    }
    
    @Test
    fun `R3 arithmetic in source with explicit target works`() {
        // "100 yen / 10" - arithmetic before currency, explicit target
        val query = CurrencyConverter.parse("100 yen / 10", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull("Should parse", query)
        
        // "(50 + 50) lira * 2" - arithmetic around currency
        val query2 = CurrencyConverter.parse("(50+50) lira *2", DialNumber.LAYOUT_QWERTY, sampleRates.keys)
        assertNotNull("Should parse", query2)
    }
}
