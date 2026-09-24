package com.ultrabytecoder.coinsafebox.security

import fr.acinq.bitcoin.Crypto
import kotlin.math.abs

/**
 * Folds raw pointer samples of a drawn gesture into a running SHA-256 digest.
 *
 * No raw sample is ever stored: each sample is encoded to a fixed 20-byte
 * little-endian block and folded as `digest = SHA-256(digest || sampleBytes)`,
 * replacing (and wiping) the previous digest. The only retained state is the
 * 32-byte running digest plus non-secret scalar counters used to enforce
 * minimum gesture quality.
 *
 * The initial digest is `SHA-256(empty)`. The finalized digest is additional
 * entropy only — it is mixed with fresh CSPRNG system entropy by
 * [EntropyCombiner], which is the dominant entropy source.
 *
 * Not thread-safe: pointer events are dispatched serially on the UI thread.
 */
class GestureEntropyAccumulator {

    private var digest: ByteArray = Crypto.sha256(ByteArray(0))
    private var sampleCount: Int = 0
    private var totalDistance: Float = 0f
    private var firstTimestamp: Long = 0L
    private var lastTimestamp: Long = 0L
    private var minX: Float = Float.MAX_VALUE
    private var maxX: Float = -Float.MAX_VALUE
    private var minY: Float = Float.MAX_VALUE
    private var maxY: Float = -Float.MAX_VALUE
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var hasLast: Boolean = false

    val sampleCountValue: Int get() = sampleCount

    val totalDistanceValue: Float get() = totalDistance

    val durationMillis: Long
        get() = if (sampleCount == 0) 0L else lastTimestamp - firstTimestamp

    val boundingBoxArea: Float
        get() = if (sampleCount == 0) 0f
        else (maxX - minX) * (maxY - minY)

    /** True when the minimum gesture-quality requirements are all met. */
    val meetsMinimumRequirements: Boolean
        get() = sampleCount >= MIN_SAMPLES
            && totalDistance >= MIN_DISTANCE_PX
            && durationMillis >= MIN_DURATION_MS
            && boundingBoxArea >= MIN_BOUNDING_BOX_AREA_PX2

    /**
     * Fraction (0..1) of the minimum gesture-quality requirements satisfied.
     *
     * Defined as the minimum of the four per-requirement fractions, so it only
     * reaches 1.0 once every requirement is met — i.e. it is 1.0 exactly when
     * [meetsMinimumRequirements] is true. Exposed for UI progress display.
     */
    val completionFraction: Float
        get() = if (sampleCount == 0) 0f
        else minOf(
            sampleCount / MIN_SAMPLES.toFloat(),
            totalDistance / MIN_DISTANCE_PX,
            durationMillis / MIN_DURATION_MS.toFloat(),
            boundingBoxArea / MIN_BOUNDING_BOX_AREA_PX2
        ).coerceIn(0f, 1f)

    /**
     * Folds one pointer sample into the running digest.
     *
     * @param x position x (dp)
     * @param y position y (dp)
     * @param pressure normalized pressure; pass 1f on platforms without a
     * pressure sensor (Desktop mouse)
     * @param timestampMillis monotonic clock (e.g. `PointerInputChange.uptimeMillis`)
     */
    fun addSample(x: Float, y: Float, pressure: Float, timestampMillis: Long) {
        if (sampleCount == 0) {
            firstTimestamp = timestampMillis
            minX = x; maxX = x
            minY = y; maxY = y
        } else {
            totalDistance += abs(x - lastX) + abs(y - lastY)
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        lastX = x
        lastY = y
        hasLast = true
        lastTimestamp = timestampMillis
        sampleCount++

        val sampleBytes = encodeSample(x, y, pressure, timestampMillis)
        val input = ByteArray(digest.size + sampleBytes.size)
        try {
            digest.copyInto(input)
            sampleBytes.copyInto(input, digest.size)
            val next = Crypto.sha256(input)
            digest.wipe()
            digest = next
        } finally {
            input.wipe()
            sampleBytes.wipe()
        }
    }

    /**
     * Returns a copy of the 32-byte running digest. The caller owns it and must
     * wipe it.
     */
    fun finalize(): ByteArray = digest.copyOf()

    /** Resets counters and the running digest to the initial state. */
    fun reset() {
        digest.wipe()
        digest = Crypto.sha256(ByteArray(0))
        sampleCount = 0
        totalDistance = 0f
        firstTimestamp = 0L
        lastTimestamp = 0L
        minX = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE
        minY = Float.MAX_VALUE
        maxY = -Float.MAX_VALUE
        lastX = 0f
        lastY = 0f
        hasLast = false
    }

    /** Zeroes the running digest and all counters. */
    fun wipe() {
        digest.wipe()
        digest = ByteArray(0)
        sampleCount = 0
        totalDistance = 0f
        firstTimestamp = 0L
        lastTimestamp = 0L
        minX = 0f
        maxX = 0f
        minY = 0f
        maxY = 0f
        lastX = 0f
        lastY = 0f
        hasLast = false
    }

    private fun encodeSample(
        x: Float,
        y: Float,
        pressure: Float,
        timestampMillis: Long
    ): ByteArray {
        val out = ByteArray(SAMPLE_BYTES)
        writeFloatLE(out, 0, x)
        writeFloatLE(out, 4, y)
        writeFloatLE(out, 8, pressure)
        writeLongLE(out, 12, timestampMillis)
        return out
    }

    private fun writeFloatLE(out: ByteArray, offset: Int, value: Float) {
        val bits = value.toRawBits()
        out[offset] = (bits and 0xFF).toByte()
        out[offset + 1] = ((bits shr 8) and 0xFF).toByte()
        out[offset + 2] = ((bits shr 16) and 0xFF).toByte()
        out[offset + 3] = ((bits shr 24) and 0xFF).toByte()
    }

    private fun writeLongLE(out: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            out[offset + i] = ((value shr (i * 8)) and 0xFF).toByte()
        }
    }

    companion object {
        const val MIN_SAMPLES = 32
        const val MIN_DISTANCE_PX = 4000f
        const val MIN_DURATION_MS = 500L
        const val MIN_BOUNDING_BOX_AREA_PX2 = 10_000f

        private const val SAMPLE_BYTES = 20
    }
}
