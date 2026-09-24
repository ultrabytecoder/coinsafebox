package com.ultrabytecoder.coinsafebox.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class FeeFormatUtilsTest {

    // --- formatFeeChipRate ---

    @Test
    fun formatFeeChipRate_nullReturnsNull() {
        assertNull(formatFeeChipRate(null))
    }

    @Test
    fun formatFeeChipRate_btc() {
        val params = com.ultrabytecoder.coinsafebox.domain.model.CustomFeeParams.Btc(10)
        assertEquals("10 sat/vB", formatFeeChipRate(params))
    }

    @Test
    fun formatFeeChipRate_eth() {
        val params = com.ultrabytecoder.coinsafebox.domain.model.CustomFeeParams.Eth(25_000, 35_000)
        assertEquals("35 Gwei", formatFeeChipRate(params))
    }

    @Test
    fun formatFeeChipRate_trc20() {
        val params = com.ultrabytecoder.coinsafebox.domain.model.CustomFeeParams.Tron(35_000_000)
        assertEquals("35 TRX", formatFeeChipRate(params))
    }

    // --- formatGwei / parseGweiToMilliGwei ---

    @Test
    fun formatGwei_wholeGwei() {
        assertEquals("25", formatGwei(25_000))
        assertEquals("35", formatGwei(35_000))
        assertEquals("1000", formatGwei(1_000_000))
    }

    @Test
    fun formatGwei_fractionalGwei() {
        assertEquals("0.05", formatGwei(50))
        assertEquals("0.001", formatGwei(1))
        assertEquals("1.5", formatGwei(1_500))
        assertEquals("2.005", formatGwei(2_005))
    }

    @Test
    fun formatGwei_zeroAndNegative() {
        assertEquals("0", formatGwei(0))
        assertEquals("-0.5", formatGwei(-500))
        assertEquals("-1.5", formatGwei(-1_500))
        assertEquals("-25", formatGwei(-25_000))
    }

    @Test
    fun formatGwei_largeValue() {
        assertEquals("999999.999", formatGwei(999_999_999))
    }

    @Test
    fun parseGweiToMilliGwei_parses() {
        assertEquals(25_000, parseGweiToMilliGwei("25"))
        assertEquals(50, parseGweiToMilliGwei("0.05"))
        assertEquals(1_500, parseGweiToMilliGwei("1.5"))
        assertNull(parseGweiToMilliGwei("abc"))
        assertNull(parseGweiToMilliGwei(""))
    }

    // --- formatFeeDetail ---

    @Test
    fun formatFeeDetail_nullReturnsNull() {
        assertNull(formatFeeDetail(null))
    }

    @Test
    fun formatFeeDetail_btc() {
        val params = com.ultrabytecoder.coinsafebox.domain.model.CustomFeeParams.Btc(10)
        val detail = formatFeeDetail(params)
        assertNotNull(detail)
        assertEquals(1, detail.size)
        assertEquals("Fee rate: 10 sat/vB", detail[0])
    }

    @Test
    fun formatFeeDetail_eth() {
        val params = com.ultrabytecoder.coinsafebox.domain.model.CustomFeeParams.Eth(25_000, 35_000)
        val detail = formatFeeDetail(params)
        assertNotNull(detail)
        assertEquals(2, detail.size)
        assertEquals("Max fee: 35 Gwei", detail[0])
        assertEquals("Priority tip: 25 Gwei", detail[1])
    }

    @Test
    fun formatFeeDetail_trc20() {
        val params = com.ultrabytecoder.coinsafebox.domain.model.CustomFeeParams.Tron(35_000_000)
        val detail = formatFeeDetail(params)
        assertNotNull(detail)
        assertEquals(1, detail.size)
        assertEquals("Fee limit: 35 TRX (35000000 SUN)", detail[0])
    }
}