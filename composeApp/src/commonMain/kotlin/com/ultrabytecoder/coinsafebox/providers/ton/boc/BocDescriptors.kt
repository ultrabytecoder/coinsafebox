package com.ultrabytecoder.coinsafebox.providers.ton.boc

fun getRefsDescriptor(refs: List<Cell>, level: Int, type: CellType): Int {
    return refs.size + (if (type != CellType.ORDINARY) 8 else 0) + level * 32
}

fun getBitsDescriptor(bits: BitString): Int {
    val len = bits.length
    return (len + 7) / 8 + len / 8
}

fun getRepr(originalBits: BitString, bits: BitString, refs: List<Cell>, level: Int, type: CellType): ByteArray {
    val bitsLen = (bits.length + 7) / 8
    val repr = ByteArray(2 + bitsLen + (2 + 32) * refs.size)

    var cursor = 0
    repr[cursor++] = getRefsDescriptor(refs, level, type).toByte()
    repr[cursor++] = getBitsDescriptor(originalBits).toByte()

    bitsToPaddedBuffer(bits).copyInto(repr, cursor)
    cursor += bitsLen

    for (c in refs) {
        val childDepth = if (type == CellType.MERKLE_PROOF || type == CellType.MERKLE_UPDATE) {
            c.depth(level + 1)
        } else {
            c.depth(level)
        }
        repr[cursor++] = (childDepth / 256).toByte()
        repr[cursor++] = (childDepth % 256).toByte()
    }
    for (c in refs) {
        val childHash = if (type == CellType.MERKLE_PROOF || type == CellType.MERKLE_UPDATE) {
            c.hash(level + 1)
        } else {
            c.hash(level)
        }
        childHash.copyInto(repr, cursor)
        cursor += 32
    }

    return repr
}
