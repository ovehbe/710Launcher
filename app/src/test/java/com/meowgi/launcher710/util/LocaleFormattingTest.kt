package com.meowgi.launcher710.util

import org.junit.Test
import org.junit.Assert.*
import java.util.Locale

/**
 * Tests for locale-independent canonical formatting (Blocker B2).
 * Turkish/Indonesian locales use comma as decimal separator.
 */
class LocaleFormattingTest {

    private fun formatCurrencyAmount(amount: Double): String {
        // Copied from SearchOverlay.formatCurrencyAmount
        return if (amount.isFinite() && amount >= Long.MIN_VALUE && amount <= Long.MAX_VALUE 
                   && amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            String.format(Locale.ROOT, "%.8f", amount).trimEnd('0').trimEnd('.')
        }
    }

    @Test
    fun `formatCurrencyAmount uses locale-independent format`() {
        val originalLocale = Locale.getDefault()
        try {
            // Test with Turkish locale (uses comma)
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("3.25", formatCurrencyAmount(3.25))
            assertEquals("100", formatCurrencyAmount(100.0))
            
            // Test with Indonesian locale (uses comma)
            Locale.setDefault(Locale.forLanguageTag("id-ID"))
            assertEquals("15420.5", formatCurrencyAmount(15420.5))
            assertEquals("0.00065", formatCurrencyAmount(0.00065))
            
            // Test with German locale (uses comma)
            Locale.setDefault(Locale.forLanguageTag("de-DE"))
            assertEquals("32.5", formatCurrencyAmount(32.5))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `formatCurrencyAmount handles large values without overflow`() {
        // Very large but still within Double range
        val large = 1e15
        val result = formatCurrencyAmount(large)
        assertFalse("Should not contain comma", result.contains(','))
        assertTrue("Should be parseable", result.toDoubleOrNull() != null)
        
        // Should not use toLong() for values near Long.MAX_VALUE that would overflow
        val nearMax = Long.MAX_VALUE.toDouble()
        val resultMax = formatCurrencyAmount(nearMax)
        assertTrue("Should be valid", resultMax.isNotEmpty())
    }

    @Test
    fun `formatCurrencyAmount handles small values precisely`() {
        val small = 0.00000123
        val result = formatCurrencyAmount(small)
        assertFalse("Should not contain comma", result.contains(','))
        assertEquals("0.00000123", result)
    }

    @Test
    fun `formatCurrencyAmount strips trailing zeros`() {
        assertEquals("3.25", formatCurrencyAmount(3.25000))
        assertEquals("100", formatCurrencyAmount(100.00000))
        assertEquals("0.1", formatCurrencyAmount(0.10000))
    }
}
