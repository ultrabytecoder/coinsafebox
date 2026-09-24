package com.ultrabytecoder.coinsafebox.providers

import com.ultrabytecoder.coinsafebox.domain.model.CustomFeeParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EthFeePresetsTest {

    // --- weiToMilliGwei ---

    @Test
    fun weiToMilliGwei_zero() {
        assertEquals(0L, 0L.weiToMilliGwei())
    }

    @Test
    fun weiToMilliGwei_roundsDown() {
        assertEquals(0L, 499_999L.weiToMilliGwei())
    }

    @Test
    fun weiToMilliGwei_roundsUpAtHalf() {
        assertEquals(1L, 500_000L.weiToMilliGwei())
    }

    @Test
    fun weiToMilliGwei_roundsUpJustAboveHalf() {
        assertEquals(1L, 500_001L.weiToMilliGwei())
    }

    @Test
    fun weiToMilliGwei_exact() {
        assertEquals(1L, 1_000_000L.weiToMilliGwei())
    }

    @Test
    fun weiToMilliGwei_largeValue() {
        assertEquals(25_000L, 25_000_000_000L.weiToMilliGwei())
    }

    @Test
    fun weiToMilliGwei_noOverflowNearLongMax() {
        val result = Long.MAX_VALUE.weiToMilliGwei()
        // Long.MAX_VALUE % 1_000_000 = 775807 >= 500000, so it rounds up
        assertTrue(result > 0)
        assertEquals(Long.MAX_VALUE / 1_000_000L + 1L, result)
    }

    // --- computeFeePresets: L2 sub-Gwei scenario (original 1/2 Gwei bug) ---

    @Test
    fun computeFeePresets_l2SubGweiValues() {
        // tip = 0.05 Gwei (50M wei), cap = 0.1 Gwei (100M wei) — Base-like
        val presets = computeFeePresets(tipCap = 50_000_000L, feeCap = 100_000_000L)
        val auto = presets.auto as CustomFeeParams.Eth
        val slow = presets.slow as CustomFeeParams.Eth
        val medium = presets.medium as CustomFeeParams.Eth
        val fast = presets.fast as CustomFeeParams.Eth

        assertEquals(50L, auto.maxPriorityFeePerGasMilliGwei)
        assertEquals(100L, auto.maxFeePerGasMilliGwei)
        assertEquals(35L, slow.maxPriorityFeePerGasMilliGwei)
        assertEquals(85L, slow.maxFeePerGasMilliGwei)
        assertEquals(75L, fast.maxPriorityFeePerGasMilliGwei)
        assertEquals(150L, fast.maxFeePerGasMilliGwei)
        assertEquals(auto, medium)
    }

    @Test
    fun computeFeePresets_mainnetValues() {
        // tip = 2 Gwei, cap = 20 Gwei
        val presets = computeFeePresets(tipCap = 2_000_000_000L, feeCap = 20_000_000_000L)
        val auto = presets.auto as CustomFeeParams.Eth
        val slow = presets.slow as CustomFeeParams.Eth
        val fast = presets.fast as CustomFeeParams.Eth

        assertEquals(2_000L, auto.maxPriorityFeePerGasMilliGwei)
        assertEquals(20_000L, auto.maxFeePerGasMilliGwei)
        assertEquals(1_400L, slow.maxPriorityFeePerGasMilliGwei)
        assertEquals(17_000L, slow.maxFeePerGasMilliGwei)
        assertEquals(3_000L, fast.maxPriorityFeePerGasMilliGwei)
        assertEquals(30_000L, fast.maxFeePerGasMilliGwei)
    }

    @Test
    fun computeFeePresets_invariantsHold() {
        // tip < cap within every preset, slow <= auto <= fast for tip and cap
        val presets = computeFeePresets(tipCap = 50_000_000L, feeCap = 100_000_000L)
        val auto = presets.auto as CustomFeeParams.Eth
        val slow = presets.slow as CustomFeeParams.Eth
        val medium = presets.medium as CustomFeeParams.Eth
        val fast = presets.fast as CustomFeeParams.Eth

        assertTrue(slow.maxPriorityFeePerGasMilliGwei < slow.maxFeePerGasMilliGwei)
        assertTrue(medium.maxPriorityFeePerGasMilliGwei < medium.maxFeePerGasMilliGwei)
        assertTrue(fast.maxPriorityFeePerGasMilliGwei < fast.maxFeePerGasMilliGwei)

        assertTrue(slow.maxPriorityFeePerGasMilliGwei <= auto.maxPriorityFeePerGasMilliGwei)
        assertTrue(auto.maxPriorityFeePerGasMilliGwei <= fast.maxPriorityFeePerGasMilliGwei)
        assertTrue(slow.maxFeePerGasMilliGwei <= auto.maxFeePerGasMilliGwei)
        assertTrue(auto.maxFeePerGasMilliGwei <= fast.maxFeePerGasMilliGwei)
    }

    @Test
    fun computeFeePresets_tinyFeesStillHaveValidStructure() {
        // Sub-mGwei input: everything must be coerced up but keep tip < cap
        val presets = computeFeePresets(tipCap = 1L, feeCap = 1L)
        val auto = presets.auto as CustomFeeParams.Eth
        val slow = presets.slow as CustomFeeParams.Eth
        val fast = presets.fast as CustomFeeParams.Eth

        assertTrue(auto.maxPriorityFeePerGasMilliGwei >= 1L)
        assertTrue(auto.maxPriorityFeePerGasMilliGwei < auto.maxFeePerGasMilliGwei)
        assertTrue(slow.maxPriorityFeePerGasMilliGwei < slow.maxFeePerGasMilliGwei)
        assertTrue(fast.maxPriorityFeePerGasMilliGwei < fast.maxFeePerGasMilliGwei)
        assertTrue(slow.maxPriorityFeePerGasMilliGwei <= fast.maxPriorityFeePerGasMilliGwei)
        assertTrue(slow.maxFeePerGasMilliGwei <= fast.maxFeePerGasMilliGwei)
    }

    @Test
    fun computeFeePresets_noOverflowWithExtremeValues() {
        // Must not overflow or produce negative values even at Long.MAX wei
        val presets = computeFeePresets(tipCap = Long.MAX_VALUE, feeCap = Long.MAX_VALUE)
        val auto = presets.auto as CustomFeeParams.Eth
        val slow = presets.slow as CustomFeeParams.Eth
        val medium = presets.medium as CustomFeeParams.Eth
        val fast = presets.fast as CustomFeeParams.Eth

        assertTrue(auto.maxPriorityFeePerGasMilliGwei > 0)
        assertTrue(auto.maxFeePerGasMilliGwei > 0)
        assertTrue(slow.maxFeePerGasMilliGwei > 0)
        assertTrue(fast.maxFeePerGasMilliGwei > 0)
        assertTrue(fast.maxPriorityFeePerGasMilliGwei >= medium.maxPriorityFeePerGasMilliGwei)
        assertTrue(fast.maxFeePerGasMilliGwei >= medium.maxFeePerGasMilliGwei)
        assertTrue(auto.maxPriorityFeePerGasMilliGwei < auto.maxFeePerGasMilliGwei)
    }
}
