package com.ultrabytecoder.coinsafebox.util

/**
 * A square QR code module matrix. [size] is the dimension in modules (width == height).
 *
 * [get] returns `true` for a dark module at (x = column, y = row) and `false` for a light
 * module or for out-of-bounds coordinates. The matrix contains only the QR modules; it does
 * not include a surrounding quiet zone.
 */
class QrMatrix(val size: Int, private val data: BooleanArray) {
    init {
        require(size > 0) { "size must be positive, was $size" }
        require(data.size == size * size) { "data length ${data.size} != size*size ${size * size}" }
    }

    operator fun get(x: Int, y: Int): Boolean =
        if (x in 0 until size && y in 0 until size) data[y * size + x] else false
}

/**
 * Platform-specific QR code encoder producing a raw module matrix (medium error correction,
 * no quiet zone). JVM targets use ZXing; iOS uses goquati:qr.
 */
expect object QrEncoder {
    fun encode(text: String): QrMatrix
}
