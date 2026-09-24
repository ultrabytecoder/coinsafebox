package com.ultrabytecoder.coinsafebox.security

/**
 * Converts a [CharArray] to UTF-8 bytes WITHOUT creating an immutable String
 * or boxing individual bytes. The caller owns the returned array and must wipe it.
 */
fun CharArray.toPinBytes(): ByteArray {
    // Pass 1: compute output size
    var outputSize = 0
    var i = 0
    while (i < size) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < size && this[i + 1].isLowSurrogate()) {
            outputSize += 4
            i += 2
        } else {
            outputSize += when (c.code) {
                in 0..0x7F -> 1
                in 0x80..0x7FF -> 2
                else -> 3
            }
            i++
        }
    }
    // Pass 2: fill
    val output = ByteArray(outputSize)
    var pos = 0
    i = 0
    while (i < size) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < size && this[i + 1].isLowSurrogate()) {
            val cp = (c.code - 0xD800) * 0x400 + (this[i + 1].code - 0xDC00) + 0x10000
            output[pos++] = (0xF0 or (cp shr 18)).toByte()
            output[pos++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
            output[pos++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
            output[pos++] = (0x80 or (cp and 0x3F)).toByte()
            i += 2
        } else {
            val cp = c.code
            when {
                cp <= 0x7F -> output[pos++] = cp.toByte()
                cp <= 0x7FF -> {
                    output[pos++] = (0xC0 or (cp shr 6)).toByte()
                    output[pos++] = (0x80 or (cp and 0x3F)).toByte()
                }
                else -> {
                    output[pos++] = (0xE0 or (cp shr 12)).toByte()
                    output[pos++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                    output[pos++] = (0x80 or (cp and 0x3F)).toByte()
                }
            }
            i++
        }
    }
    return output
}
