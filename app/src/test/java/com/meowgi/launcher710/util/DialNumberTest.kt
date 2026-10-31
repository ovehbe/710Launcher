package com.meowgi.launcher710.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DialNumberTest {

    @Test
    fun plainDigitsDial() {
        assertEquals("44", DialNumber.dialable("44"))
        assertEquals("5551234", DialNumber.dialable("5551234"))
        assertEquals("0", DialNumber.dialable("0"))
    }

    @Test
    fun internationalNumberWithSeparatorsDials() {
        assertEquals("+905551234567", DialNumber.dialable("+90 555 123 45 67"))
        assertEquals("+15551234567", DialNumber.dialable("+1 (555) 123-4567"))
        assertEquals("5551234", DialNumber.dialable("555.1234"))
        assertEquals("5551234", DialNumber.dialable(" 555 1234 "))
        assertEquals("5551234", DialNumber.dialable("555\u00A01234"))
    }

    @Test
    fun serviceCodesKeepStarAndHash() {
        assertEquals("*#06#", DialNumber.dialable("*#06#"))
        assertEquals("*100#", DialNumber.dialable("*100#"))
    }

    @Test
    fun lettersAreNeverConvertedToKeypadDigits() {
        assertNull(DialNumber.dialable("sjsj"))
        assertNull(DialNumber.dialable("zw0"))
        assertNull(DialNumber.dialable("a"))
        assertNull(DialNumber.dialable("1-800-FLOWERS"))
        assertNull(DialNumber.dialable("555 1234x"))
        assertNull(DialNumber.dialable("çağ 1"))
    }

    @Test
    fun otherSymbolsAreNotDialable() {
        assertNull(DialNumber.dialable("12,34"))
        assertNull(DialNumber.dialable("12/34"))
        assertNull(DialNumber.dialable("@123"))
        assertNull(DialNumber.dialable("555;1"))
    }

    @Test
    fun plusOnlyLeading() {
        assertEquals("+44", DialNumber.dialable(" +44"))
        assertEquals("+44", DialNumber.dialable("(+44)"))
        assertNull(DialNumber.dialable("44+1"))
        assertNull(DialNumber.dialable("++44"))
    }

    @Test
    fun needsAtLeastOneDigit() {
        assertNull(DialNumber.dialable(""))
        assertNull(DialNumber.dialable("   "))
        assertNull(DialNumber.dialable("+"))
        assertNull(DialNumber.dialable("*#"))
        assertNull(DialNumber.dialable("- ( )"))
    }
}
