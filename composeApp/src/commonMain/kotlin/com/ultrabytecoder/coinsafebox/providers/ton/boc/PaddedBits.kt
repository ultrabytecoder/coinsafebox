package com.ultrabytecoder.coinsafebox.providers.ton.boc

fun bitsToPaddedBuffer(bits: BitString): ByteArray {
    if (bits.length == 0) return ByteArray(0)
    val targetLen = (bits.length + 7) / 8
    val buffer = ByteArray(targetLen)
    for (i in 0 until bits.length) {
        if (bits.at(i)) {
            val byteIdx = i / 8
            val bitIdx = 7 - (i % 8)
            buffer[byteIdx] = (buffer[byteIdx].toInt() or (1 shl bitIdx)).toByte()
        }
    }
    val padding = targetLen * 8 - bits.length
    if (padding > 0) {
        val byteIdx = bits.length / 8
        val bitIdx = 7 - (bits.length % 8)
        buffer[byteIdx] = (buffer[byteIdx].toInt() or (1 shl bitIdx)).toByte()
    }
    return buffer
}

fun paddedBufferToBits(buff: ByteArray): BitString {
    var bitLen = 0
    for (i in buff.lastIndex downTo 0) {
        if (buff[i].toInt() != 0) {
            val testByte = buff[i].toInt() and 0xFF
            var bitPos = testByte and (-testByte)
            if (bitPos == 0) continue
            if (bitPos and (bitPos - 1) == 0) {
                bitPos = bitPos.countLeadingZeroBits() + 1
            }
            bitLen = if (i > 0) i * 8 else 0
            bitLen += 8 - bitPos
            break
        }
    }
    return BitString(buff, 0, bitLen)
}
