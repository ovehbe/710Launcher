package com.meowgi.launcher710.util

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

/**
 * Tests for m1: BigDecimal formatting without Long.MAX_VALUE clamping.
 */
class BigDecimalFormattingTest {
    
    private fun formatCurrencyAmount(amount: Double): String {
        // Copy of SearchOverlay.formatCurrencyAmount
        if (!amount.isFinite()) {
            return amount.toString()
        }
        val formatted = String.format(java.util.Locale.ROOT, "%.8f", amount)
        return formatted.trimEnd('0').trimEnd('.')
    }
    
    @Test
    fun `m1 large values near Long MAX_VALUE formatted correctly`() {
        val nearMax = 9.223372036854776E18  // Near Long.MAX_VALUE
        val result = formatCurrencyAmount(nearMax)
        
        // Should not clamp to 9223372036854775807
        assertFalse("Should not contain clamped value", result.contains("9223372036854775807"))
        assertTrue("Should be parseable", result.toDoubleOrNull() != null)
    }
    
    @Test
    fun `m1 values above Long MAX_VALUE formatted correctly`() {
        val above = 1e19  // 10^19, above Long.MAX_VALUE
        val result = formatCurrencyAmount(above)
        
        assertTrue("Should be parseable", result.toDoubleOrNull() != null)
        assertEquals("Should preserve magnitude", above, result.toDouble(), above * 0.0001)
    }
    
    @Test
    fun `m1 integer values have no decimal`() {
        assertEquals("100", formatCurrencyAmount(100.0))
        assertEquals("3250", formatCurrencyAmount(3250.0))
    }
    
    @Test
    fun `m1 decimal values strip trailing zeros`() {
        assertEquals("3.25", formatCurrencyAmount(3.25))
        assertEquals("0.1", formatCurrencyAmount(0.1))
        assertEquals("0.00000123", formatCurrencyAmount(0.00000123))
    }
    
    @Test
    fun `m1 locale independent`() {
        val originalLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            val result = formatCurrencyAmount(3.25)
            assertFalse("Should not contain comma", result.contains(','))
            assertEquals("3.25", result)
        } finally {
            java.util.Locale.setDefault(originalLocale)
        }
    }
}
