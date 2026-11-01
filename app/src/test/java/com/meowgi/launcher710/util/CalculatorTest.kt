package com.meowgi.launcher710.util

import org.junit.Assert.*
import org.junit.Test

class CalculatorTest {

    @Test
    fun `evaluate basic arithmetic`() {
        assertEquals("4", Calculator.evaluate("2+2"))
        assertEquals("0", Calculator.evaluate("5-5"))
        assertEquals("15", Calculator.evaluate("3*5"))
        assertEquals("4", Calculator.evaluate("12/3"))
        assertEquals("1", Calculator.evaluate("10%3"))
    }

    @Test
    fun `evaluate with operator precedence`() {
        assertEquals("14", Calculator.evaluate("2+3*4"))
        assertEquals("20", Calculator.evaluate("(2+3)*4"))
        assertEquals("7", Calculator.evaluate("2*3+1"))
        assertEquals("8", Calculator.evaluate("2*(3+1)"))
    }

    @Test
    fun `evaluate with decimal numbers`() {
        assertEquals("3.5", Calculator.evaluate("1.5+2"))
        assertEquals("7.5", Calculator.evaluate("2.5*3"))
        assertEquals("0.5", Calculator.evaluate("1/2"))
        assertEquals("2.25", Calculator.evaluate("1.5*1.5"))
    }

    @Test
    fun `evaluate with negative numbers`() {
        assertEquals("-5", Calculator.evaluate("-5"))
        assertEquals("-3", Calculator.evaluate("-5+2"))
        assertEquals("-10", Calculator.evaluate("-5*2"))
        assertEquals("3", Calculator.evaluate("5+-2"))
    }

    @Test
    fun `evaluate with parentheses`() {
        assertEquals("9", Calculator.evaluate("(2+1)*(4-1)"))
        assertEquals("14", Calculator.evaluate("2*(3+4)"))
        assertEquals("1", Calculator.evaluate("((5-2)*(4-2))-5"))
    }

    @Test
    fun `evaluate with whitespace`() {
        assertEquals("4", Calculator.evaluate("2 + 2"))
        assertEquals("15", Calculator.evaluate("3 * 5"))
        assertEquals("20", Calculator.evaluate("( 2 + 3 ) * 4"))
    }

    @Test
    fun `evaluate returns null for plain numbers without operators`() {
        assertNull(Calculator.evaluate("42"))
        assertNull(Calculator.evaluate("123"))
        assertNull(Calculator.evaluate("1.5"))
    }

    @Test
    fun `evaluate returns null for division by zero`() {
        assertNull(Calculator.evaluate("5/0"))
        assertNull(Calculator.evaluate("10%0"))
    }

    @Test
    fun `evaluate returns null for malformed expressions`() {
        assertNull(Calculator.evaluate("2++2"))
        assertNull(Calculator.evaluate("5+"))
        assertNull(Calculator.evaluate("+5"))
        assertNull(Calculator.evaluate("(2+3"))
        assertNull(Calculator.evaluate("2+3)"))
        // "test123" maps to "(24(123" which is malformed
        assertNull(Calculator.evaluate("(24(123"))
    }

    @Test
    fun `evaluate returns null for expressions without digits`() {
        assertNull(Calculator.evaluate("+-*/"))
        assertNull(Calculator.evaluate("()"))
    }

    @Test
    fun `evaluate trims trailing zeros`() {
        assertEquals("2.5", Calculator.evaluate("5/2"))
        assertEquals("1.33333333", Calculator.evaluate("4/3"))
        assertEquals("3", Calculator.evaluate("1.5*2"))
    }

    @Test
    fun `toExpression maps BB keyboard letters to operators`() {
        // o → +, a → *, i → -, g → /, m → ., t → (, y → )
        assertEquals("2+2", Calculator.toExpression("2o2"))
        assertEquals("3*5", Calculator.toExpression("3a5"))
        assertEquals("10-3", Calculator.toExpression("10i3"))
        assertEquals("12/4", Calculator.toExpression("12g4"))
        assertEquals("1.5+2", Calculator.toExpression("1m5o2"))
        assertEquals("(2+3)*4", Calculator.toExpression("t2o3ya4"))
    }

    @Test
    fun `toExpression maps BB keyboard letters to digits`() {
        // w→1, e→2, r→3, s→4, d→5, f→6, z→7, x→8, c→9
        assertEquals("123", Calculator.toExpression("wer"))
        assertEquals("456", Calculator.toExpression("sdf"))
        assertEquals("789", Calculator.toExpression("zxc"))
        assertEquals("2+2", Calculator.toExpression("eoe"))
    }

    @Test
    fun `toExpression accepts raw operator symbols`() {
        assertEquals("2+2", Calculator.toExpression("2+2"))
        assertEquals("3*5", Calculator.toExpression("3*5"))
        assertEquals("10-3", Calculator.toExpression("10-3"))
        assertEquals("12/4", Calculator.toExpression("12/4"))
        assertEquals("10%3", Calculator.toExpression("10%3"))
        assertEquals("(2+3)*4", Calculator.toExpression("(2+3)*4"))
    }

    @Test
    fun `toExpression handles mixed letters and symbols`() {
        assertEquals("2+3*4", Calculator.toExpression("2o3a4"))
        assertEquals("2+3*4", Calculator.toExpression("eo3*4"))
        assertEquals("(5-2)*3", Calculator.toExpression("(5i2)*3"))
    }

    @Test
    fun `toExpression returns null for unmappable characters`() {
        // "hello" has unmappable 'h' and 'l'
        assertNull(Calculator.toExpression("hello"))
        // "giant" has no digits, so returns null even though letters map to operators
        assertNull(Calculator.toExpression("giant"))
        // "test123" maps to "(24(123" - it has digits so mapping succeeds,
        // but evaluate() will reject it as malformed
        assertEquals("(24(123", Calculator.toExpression("test123"))
    }

    @Test
    fun `toExpression ignores whitespace`() {
        assertEquals("2+2", Calculator.toExpression("2 o 2"))
        assertEquals("3*5", Calculator.toExpression("3 a 5"))
    }

    @Test
    fun `toExpression handles comma as thousands separator`() {
        assertEquals("1,000", Calculator.toExpression("1,000"))
        assertEquals("1,000,000", Calculator.toExpression("1,000,000"))
    }

    @Test
    fun `toExpression handles comma as decimal point`() {
        assertEquals("1,5", Calculator.toExpression("1,5"))
        assertEquals("1,5+2", Calculator.toExpression("1,5+2"))
    }

    @Test
    fun `evaluate handles thousands separators`() {
        assertEquals("1001", Calculator.evaluate("1,000+1"))
        assertEquals("2000000", Calculator.evaluate("1,000,000+1,000,000"))
    }

    @Test
    fun `evaluate handles European decimal notation`() {
        assertEquals("3.5", Calculator.evaluate("1,5+2"))
        assertEquals("7.5", Calculator.evaluate("2,5*3"))
    }

    @Test
    fun `operatorChar maps all BB keyboard operators`() {
        assertEquals('+', Calculator.operatorChar('o'))
        assertEquals('*', Calculator.operatorChar('a'))
        assertEquals('-', Calculator.operatorChar('i'))
        assertEquals('/', Calculator.operatorChar('g'))
        assertEquals('.', Calculator.operatorChar('m'))
        assertEquals('(', Calculator.operatorChar('t'))
        assertEquals(')', Calculator.operatorChar('y'))
        assertEquals(',', Calculator.operatorChar('n'))
    }

    @Test
    fun `operatorChar works case-insensitive`() {
        assertEquals('+', Calculator.operatorChar('O'))
        assertEquals('*', Calculator.operatorChar('A'))
        assertEquals('-', Calculator.operatorChar('I'))
        assertEquals('/', Calculator.operatorChar('G'))
    }

    @Test
    fun `operatorChar maps BB keyboard digits`() {
        assertEquals('1', Calculator.operatorChar('w'))
        assertEquals('2', Calculator.operatorChar('e'))
        assertEquals('3', Calculator.operatorChar('r'))
        assertEquals('4', Calculator.operatorChar('s'))
        assertEquals('5', Calculator.operatorChar('d'))
        assertEquals('6', Calculator.operatorChar('f'))
        assertEquals('7', Calculator.operatorChar('z'))
        assertEquals('8', Calculator.operatorChar('x'))
        assertEquals('9', Calculator.operatorChar('c'))
    }

    @Test
    fun `operatorChar returns null for unmappable letters`() {
        assertNull(Calculator.operatorChar('h'))
        assertNull(Calculator.operatorChar('j'))
        assertNull(Calculator.operatorChar('k'))
        assertNull(Calculator.operatorChar('l'))
    }

    @Test
    fun `end to end BB keyboard input evaluation`() {
        // "2+2" as "eoe"
        val expr1 = Calculator.toExpression("eoe")
        assertEquals("2+2", expr1)
        assertEquals("4", Calculator.evaluate(expr1!!))

        // "12*3.5" as "wea3m5"
        val expr2 = Calculator.toExpression("wea3m5")
        assertEquals("12*3.5", expr2)
        assertEquals("42", Calculator.evaluate(expr2!!))

        // "(4-1)/2" as "tsi1yge"
        val expr3 = Calculator.toExpression("tsi1yge")
        assertEquals("(4-1)/2", expr3)
        assertEquals("1.5", Calculator.evaluate(expr3!!))
    }
}
