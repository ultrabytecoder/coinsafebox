package com.ultrabytecoder.coinsafebox.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GestureEntropyAccumulatorTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

    @Test
    fun emptyAccumulatorFinalizesToSha256OfEmpty() {
        val acc = GestureEntropyAccumulator()
        val digest = acc.finalize()
        assertContentEquals(
            hex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            digest
        )
        digest.wipe()
    }

    @Test
    fun singleSampleMatchesReferenceVector() {
        // Reference: SHA256(SHA256("") || LE(1.0f) || LE(2.0f) || LE(0.5f) || LE(12345L))
        val acc = GestureEntropyAccumulator()
        acc.addSample(1.0f, 2.0f, 0.5f, 12345L)
        val digest = acc.finalize()
        assertContentEquals(
            hex("16bde02771ecfbf2f8bb52620fb6e48959ee91a0a6c132382645b4c9e421b534"),
            digest
        )
        digest.wipe()
    }

    @Test
    fun twoSamplesMatchReferenceVector() {
        val acc = GestureEntropyAccumulator()
        acc.addSample(1.0f, 2.0f, 0.5f, 12345L)
        acc.addSample(10.5f, 20.25f, 1.0f, 12400L)
        val digest = acc.finalize()
        assertContentEquals(
            hex("27bdc4a74b1ce5097a9b0b41d89e76fa190174b156f9766f70ac67d7f263a5fd"),
            digest
        )
        digest.wipe()
    }

    @Test
    fun deterministicForSameSampleSequence() {
        fun run(): ByteArray {
            val acc = GestureEntropyAccumulator()
            for (i in 0 until 40) {
                acc.addSample(i * 1.5f, i * 0.7f, 0.5f, 1000L + i * 10L)
            }
            return acc.finalize()
        }
        val a = run()
        val b = run()
        assertContentEquals(a, b)
        a.wipe()
        b.wipe()
    }

    @Test
    fun orderSensitivity() {
        fun run(reversed: Boolean): ByteArray {
            val acc = GestureEntropyAccumulator()
            val samples = (0 until 40).map { it * 1.5f to (1000L + it * 10L) }
            (if (reversed) samples.reversed() else samples).forEach { (x, t) ->
                acc.addSample(x, x * 0.5f, 0.5f, t)
            }
            return acc.finalize()
        }
        val a = run(false)
        val b = run(true)
        assertTrue(!a.contentEquals(b))
        a.wipe()
        b.wipe()
    }

    @Test
    fun minimumRequirementsGating() {
        val acc = GestureEntropyAccumulator()
        assertFalse(acc.meetsMinimumRequirements)

        // A single sample is far from enough.
        acc.addSample(10f, 10f, 1f, 0L)
        assertFalse(acc.meetsMinimumRequirements)

        // A long, varied, slow gesture meets all thresholds.
        val acc2 = GestureEntropyAccumulator()
        for (i in 0 until 60) {
            val x = 10f + (i % 20) * 50f
            val y = 10f + (i / 20) * 200f
            acc2.addSample(x, y, 1f, i * 50L)
        }
        assertTrue(acc2.sampleCountValue >= GestureEntropyAccumulator.MIN_SAMPLES)
        assertTrue(acc2.totalDistanceValue >= GestureEntropyAccumulator.MIN_DISTANCE_PX)
        assertTrue(acc2.durationMillis >= GestureEntropyAccumulator.MIN_DURATION_MS)
        assertTrue(acc2.boundingBoxArea >= GestureEntropyAccumulator.MIN_BOUNDING_BOX_AREA_PX2)
        assertTrue(acc2.meetsMinimumRequirements)
    }

    @Test
    fun resetRestoresInitialState() {
        val acc = GestureEntropyAccumulator()
        acc.addSample(1f, 2f, 1f, 5L)
        acc.addSample(3f, 4f, 1f, 10L)
        acc.reset()
        assertEquals(0, acc.sampleCountValue)
        assertFalse(acc.meetsMinimumRequirements)
        val digest = acc.finalize()
        assertContentEquals(
            hex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            digest
        )
        digest.wipe()
    }

    @Test
    fun wipeClearsCounters() {
        val acc = GestureEntropyAccumulator()
        acc.addSample(1f, 2f, 1f, 5L)
        acc.wipe()
        assertEquals(0, acc.sampleCountValue)
        assertFalse(acc.meetsMinimumRequirements)
    }
}
