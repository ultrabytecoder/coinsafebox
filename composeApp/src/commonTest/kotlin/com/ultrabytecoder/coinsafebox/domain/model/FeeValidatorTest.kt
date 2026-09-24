package com.ultrabytecoder.coinsafebox.domain.model

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class FeeValidatorTest {

    // --- BTC ---

    @Test
    fun btcFeeRate_validBoundaries() {
        assertTrue(FeeValidator.validateBtcFeeRate(1L), "1 sat/vB should be valid")
        assertTrue(FeeValidator.validateBtcFeeRate(2000L), "2000 sat/vB should be valid")
        assertTrue(FeeValidator.validateBtcFeeRate(500L), "500 sat/vB should be valid")
    }

    @Test
    fun btcFeeRate_belowMinimum() {
        assertFalse(FeeValidator.validateBtcFeeRate(0L), "0 should be invalid")
        assertFalse(FeeValidator.validateBtcFeeRate(-1L), "negative should be invalid")
    }

    @Test
    fun btcFeeRate_aboveMaximum() {
        assertFalse(FeeValidator.validateBtcFeeRate(2001L), "2001 should be invalid")
        assertFalse(FeeValidator.validateBtcFeeRate(10000L), "10000 should be invalid")
    }

    // --- ETH ---

    @Test
    fun ethMaxFee_validBoundaries() {
        assertTrue(FeeValidator.validateEthMaxFee(1L), "1 mGwei (0.001 Gwei) should be valid")
        assertTrue(FeeValidator.validateEthMaxFee(10_000_000L), "10000 Gwei should be valid")
        assertTrue(FeeValidator.validateEthMaxFee(35_000L), "35 Gwei should be valid")
    }

    @Test
    fun ethMaxFee_outOfRange() {
        assertFalse(FeeValidator.validateEthMaxFee(0L), "0 should be invalid")
        assertFalse(FeeValidator.validateEthMaxFee(10_000_001L), "10001 Gwei should be invalid")
        assertFalse(FeeValidator.validateEthMaxFee(-1L), "negative should be invalid")
    }

    @Test
    fun ethPriorityFee_validBoundaries() {
        assertTrue(FeeValidator.validateEthPriorityFee(1L), "1 mGwei (0.001 Gwei) should be valid")
        assertTrue(FeeValidator.validateEthPriorityFee(1_000_000L), "1000 Gwei should be valid")
    }

    @Test
    fun ethPriorityFee_outOfRange() {
        assertFalse(FeeValidator.validateEthPriorityFee(0L))
        assertFalse(FeeValidator.validateEthPriorityFee(1_000_001L))
    }

    // --- ETH combined validation (EIP-1559 constraint) ---

    @Test
    fun ethFeeParams_valid() {
        assertTrue(FeeValidator.validateEthFeeParams(25_000L, 35_000L), "25/35 Gwei should be valid")
        assertTrue(FeeValidator.validateEthFeeParams(1L, 1L), "1/1 mGwei should be valid (equal)")
        assertTrue(FeeValidator.validateEthFeeParams(100L, 10_000_000L), "0.1/10000 Gwei should be valid")
    }

    @Test
    fun ethFeeParams_priorityExceedsMax() {
        assertFalse(FeeValidator.validateEthFeeParams(50_000L, 30_000L), "priority > max should be invalid")
        assertFalse(FeeValidator.validateEthFeeParams(1_000_000L, 1L), "priority >> max should be invalid")
    }

    @Test
    fun ethFeeParams_outOfRange() {
        assertFalse(FeeValidator.validateEthFeeParams(0L, 35_000L), "priority 0 should be invalid")
        assertFalse(FeeValidator.validateEthFeeParams(25_000L, 0L), "max 0 should be invalid")
        assertFalse(FeeValidator.validateEthFeeParams(1_000_001L, 10_000_000L), "priority 1001 Gwei should be invalid")
        assertFalse(FeeValidator.validateEthFeeParams(25_000L, 10_000_001L), "max 10001 Gwei should be invalid")
    }

    // --- TRC-20 ---

    @Test
    fun trc20FeeLimit_validBoundaries() {
        assertTrue(FeeValidator.validateTrc20FeeLimit(1_000_000L), "1 TRX (1M SUN) should be valid")
        assertTrue(FeeValidator.validateTrc20FeeLimit(100_000_000L), "100 TRX should be valid")
        assertTrue(FeeValidator.validateTrc20FeeLimit(35_000_000L), "35 TRX should be valid")
    }

    @Test
    fun trc20FeeLimit_outOfRange() {
        assertFalse(FeeValidator.validateTrc20FeeLimit(999_999L), "below 1 TRX should be invalid")
        assertFalse(FeeValidator.validateTrc20FeeLimit(100_000_001L), "above 100 TRX should be invalid")
        assertFalse(FeeValidator.validateTrc20FeeLimit(0L))
        assertFalse(FeeValidator.validateTrc20FeeLimit(-1L))
    }
}