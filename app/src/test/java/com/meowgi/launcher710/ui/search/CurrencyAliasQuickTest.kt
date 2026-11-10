package com.meowgi.launcher710.ui.search

import com.meowgi.launcher710.util.Calculator
import com.meowgi.launcher710.util.CurrencyConverter
import org.junit.Assert.*
import org.junit.Test

/**
 * Quick test for specific reported bugs with currency aliases and Q25 letter-digit mapping.
 */
class CurrencyAliasQuickTest {
    
    @Test
    fun `w00 yen should NOT produce calculator row`() {
        // Bug: "w00 yen" was producing garbage calc row "100)2,"
        // Fix: Calculator.toExpression should reject queries with "yen" alias
        val expr = Calculator.toExpression("w00 yen")
        assertNull("Calculator should reject 'w00 yen' (contains currency alias)", expr)
    }
    
    @Test
    fun `w00 tl should NOT produce calculator row`() {
        // Bug: "w00 tl" showed nothing (tl→"(" garbage)
        // Fix: Calculator.toExpression should reject queries with "tl" alias
        val expr = Calculator.toExpression("w00 tl")
        assertNull("Calculator should reject 'w00 tl' (contains currency alias)", expr)
    }
    
    @Test
    fun `100 yen should NOT produce calculator row`() {
        val expr = Calculator.toExpression("100 yen")
        assertNull("Calculator should reject '100 yen' (contains currency alias)", expr)
    }
    
    @Test
    fun `w00 jpy should NOT produce calculator row`() {
        // ISO code should also be rejected
        val expr = Calculator.toExpression("w00 jpy")
        assertNull("Calculator should reject 'w00 jpy' (contains ISO code)", expr)
    }
    
    @Test
    fun `100 yen to try should NOT produce calculator row`() {
        val expr = Calculator.toExpression("100 yen to try")
        assertNull("Calculator should reject currency conversion query", expr)
    }
    
    @Test
    fun `pure calculator w00oe00 should work`() {
        // Pure calculator: w00+e00 = 100+200
        val expr = Calculator.toExpression("w00oe00")
        assertNotNull("Pure calculator should work", expr)
        assertEquals("100+200", expr)
    }
    
    @Test
    fun `eoe should produce 2+2`() {
        val expr = Calculator.toExpression("eoe")
        assertEquals("2+2", expr)
    }
    
    @Test
    fun `negative amount -100 yen should NOT produce calculator row`() {
        val expr = Calculator.toExpression("-100 yen")
        assertNull("Calculator should reject negative currency amount", expr)
    }
}
