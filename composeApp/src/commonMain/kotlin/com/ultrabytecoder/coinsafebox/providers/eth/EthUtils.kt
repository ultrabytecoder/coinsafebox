package com.ultrabytecoder.coinsafebox.providers

internal fun keccak256(data: ByteArray): ByteArray {
    val state = LongArray(25)
    val rate = 136

    val paddedLen = ((data.size + 1 + rate - 1) / rate) * rate
    val padded = ByteArray(paddedLen)
    data.copyInto(padded)
    padded[data.size] = 0x01.toByte()
    padded[padded.size - 1] = (padded[padded.size - 1].toInt() xor 0x80).toByte()

    for (offset in padded.indices step rate) {
        for (i in 0 until rate / 8) {
            state[i] = state[i] xor readLong(padded, offset + i * 8)
        }
        keccakF1600(state)
    }

    val output = ByteArray(32)
    for (i in 0 until 4) {
        writeLong(state[i], output, i * 8)
    }
    return output
}

private fun keccakF1600(state: LongArray) {
    val RC = longArrayOf(
        h("0000000000000001"), h("0000000000008082"), h("800000000000808A"),
        h("8000000080008000"), h("000000000000808B"), h("0000000080000001"),
        h("8000000080008081"), h("8000000000008009"), h("000000000000008A"),
        h("0000000000000088"), h("0000000080008009"), h("000000008000000A"),
        h("000000008000808B"), h("800000000000008B"), h("8000000000008089"),
        h("8000000000008003"), h("8000000000008002"), h("8000000000000080"),
        h("000000000000800A"), h("800000008000000A"), h("8000000080008081"),
        h("8000000000008080"), h("0000000080000001"), h("8000000080008008")
    )

    val ROT = arrayOf(
        intArrayOf(0, 36, 3, 41, 18),
        intArrayOf(1, 44, 10, 45, 2),
        intArrayOf(62, 6, 43, 15, 61),
        intArrayOf(28, 55, 25, 21, 56),
        intArrayOf(27, 20, 39, 8, 14)
    )

    val bc = LongArray(5)

    for (round in 0 until 24) {
        for (x in 0 until 5) {
            bc[x] = state[x] xor state[x + 5] xor state[x + 10] xor state[x + 15] xor state[x + 20]
        }
        for (x in 0 until 5) {
            val t = bc[(x + 4) % 5] xor ((bc[(x + 1) % 5] shl 1) or (bc[(x + 1) % 5] ushr 63))
            for (y in 0 until 5) {
                state[x + 5 * y] = state[x + 5 * y] xor t
            }
        }

        val tmp = LongArray(25)
        for (x in 0 until 5) {
            for (y in 0 until 5) {
                val r = ROT[x][y]
                val s = state[x + 5 * y]
                tmp[y + 5 * ((2 * x + 3 * y) % 5)] = if (r == 0) s else (s shl r) or (s ushr (64 - r))
            }
        }

        for (x in 0 until 5) {
            for (y in 0 until 5) {
                state[x + 5 * y] = tmp[x + 5 * y] xor (tmp[(x + 1) % 5 + 5 * y].inv() and tmp[(x + 2) % 5 + 5 * y])
            }
        }

        state[0] = state[0] xor RC[round]
    }
}

private fun readLong(data: ByteArray, offset: Int): Long {
    var result = 0L
    for (i in 0 until 8) {
        result = result or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
    }
    return result
}

private fun writeLong(value: Long, output: ByteArray, offset: Int) {
    for (i in 0 until 8) {
        output[offset + i] = (value shr (8 * i)).toByte()
    }
}

internal fun rlpEncode(data: ByteArray): ByteArray {
    return when {
        data.size == 1 && data[0] >= 0 && data[0] <= 0x7f -> data
        data.size <= 55 -> byteArrayOf((0x80 + data.size).toByte()) + data
        else -> {
            val lenBytes = encodeBeLength(data.size)
            byteArrayOf((0xb7 + lenBytes.size).toByte()) + lenBytes + data
        }
    }
}

internal fun rlpEncodeList(items: List<ByteArray>): ByteArray {
    val concatenated = items.fold(byteArrayOf()) { acc, item -> acc + item }
    return when {
        concatenated.size <= 55 -> byteArrayOf((0xc0 + concatenated.size).toByte()) + concatenated
        else -> {
            val lenBytes = encodeBeLength(concatenated.size)
            byteArrayOf((0xf7 + lenBytes.size).toByte()) + lenBytes + concatenated
        }
    }
}

internal fun rlpEncodeLong(value: Long): ByteArray {
    if (value == 0L) return rlpEncode(byteArrayOf())
    val be = ByteArray(8) {
        (value shr (56 - 8 * it)).toByte()
    }
    val stripped = be.dropWhile { it == 0.toByte() }.toByteArray()
    return rlpEncode(stripped)
}

internal fun rlpEncodeAddress(hexAddress: String): ByteArray {
    val bytes = hexAddress.removePrefix("0x")
    return rlpEncode(ByteArray(20) { i -> ((bytes[i * 2].digitToInt(16) shl 4) or bytes[i * 2 + 1].digitToInt(16)).toByte() })
}

private fun encodeBeLength(length: Int): ByteArray {
    return when {
        length <= 0xFF -> byteArrayOf(length.toByte())
        length <= 0xFFFF -> byteArrayOf((length shr 8).toByte(), length.toByte())
        length <= 0xFFFFFF -> byteArrayOf((length shr 16).toByte(), (length shr 8).toByte(), length.toByte())
        else -> byteArrayOf((length shr 24).toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte())
    }
}

private fun h(hex: String): Long = hex.toULong(16).toLong()
