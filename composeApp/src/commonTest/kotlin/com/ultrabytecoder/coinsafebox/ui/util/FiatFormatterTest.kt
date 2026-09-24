package com.ultrabytecoder.coinsafebox.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals

class FiatFormatterTest {

    @Test
    fun normalAmount_roundsToTwoDecimals() {
        assertEquals("1,234.57 USD", formatFiat(1234.567, "USD"))
        assertEquals("0.10 EUR", formatFiat(0.1, "EUR"))
    }

    @Test
    fun exactZero_showsZero() {
        assertEquals("0.00 USD", formatFiat(0.0, "USD"))
    }

    @Test
    fun subCentAmount_showsLessThanOneCent() {
        assertEquals("< 0.01 USD", formatFiat(0.00031, "USD"))
        assertEquals("< 0.01 EUR", formatFiat(0.0049, "EUR"))
    }

    @Test
    fun boundaryValue_halfCent_roundsUp() {
        assertEquals("0.01 USD", formatFiat(0.005, "USD"))
    }
}
