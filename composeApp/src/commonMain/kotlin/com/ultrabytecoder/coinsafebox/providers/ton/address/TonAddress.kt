package com.ultrabytecoder.coinsafebox.providers.ton.address

class TonAddress(val workChain: Int, val hash: ByteArray) {

    init {
        require(hash.size == 32) { "Invalid address hash length: ${hash.size}" }
    }

    fun toRawString(): String = "$workChain:${hash.toHexString()}"

    fun toString(bounceable: Boolean = true, testOnly: Boolean = false, urlSafe: Boolean = true): String {
        val tag = if (bounceable) 0x11 else 0x51
        val tagByte = if (testOnly) (tag or 0x80) else tag
        val wcByte = if (workChain == -1) 0xFF else workChain and 0xFF
        val addr = ByteArray(34)
        addr[0] = tagByte.toByte()
        addr[1] = wcByte.toByte()
        hash.copyInto(addr, 2)
        val crc = crc16Xmodem(addr)
        val full = ByteArray(36)
        addr.copyInto(full, 0)
        full[34] = (crc shr 8).toByte()
        full[35] = (crc and 0xFF).toByte()
        return base64Encode(full, urlSafe)
    }

    override fun toString(): String = toString(bounceable = true, testOnly = false, urlSafe = true)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TonAddress) return false
        return workChain == other.workChain && hash.contentEquals(other.hash)
    }

    override fun hashCode(): Int = 31 * workChain + hash.contentHashCode()

    companion object {
        fun parse(source: String): TonAddress {
            return if (isFriendly(source)) parseFriendly(source)
            else if (isRaw(source)) parseRaw(source)
            else throw IllegalArgumentException("Unknown address type: $source")
        }

        fun isFriendly(source: String): Boolean =
            source.length == 48 && source.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "+/=_-" }

        fun isRaw(source: String): Boolean {
            val parts = source.split(":")
            if (parts.size != 2) return false
            val wc = parts[0].toIntOrNull() ?: return false
            val hex = parts[1]
            return hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }

        fun parseRaw(source: String): TonAddress {
            val parts = source.split(":")
            val wc = parts[0].toInt()
            val hash = hexToByteArray(parts[1])
            return TonAddress(wc, hash)
        }

        fun parseFriendly(source: String): TonAddress {
            val normalized = source.replace('-', '+').replace('_', '/')
            val data = base64Decode(normalized)
            require(data.size == 36) { "Address must be 36 bytes, got ${data.size}" }
            val addr = data.copyOfRange(0, 34)
            val crc = data.copyOfRange(34, 36)
            val calcedCrc = crc16Xmodem(addr)
            require(calcedCrc == ((crc[0].toInt() and 0xFF) shl 8 or (crc[1].toInt() and 0xFF))) { "Invalid checksum" }
            var tag = addr[0].toInt() and 0xFF
            if (tag and 0x80 != 0) tag = tag xor 0x80
            require(tag == 0x11 || tag == 0x51) { "Unknown address tag: $tag" }
            val wc = if ((addr[1].toInt() and 0xFF) == 0xFF) -1 else addr[1].toInt() and 0xFF
            val hashPart = addr.copyOfRange(2, 34)
            return TonAddress(wc, hashPart)
        }
    }
}

private fun crc16Xmodem(data: ByteArray): Int {
    var crc = 0
    for (b in data) {
        crc = crc xor ((b.toInt() and 0xFF) shl 8)
        repeat(8) {
            crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
        }
        crc = crc and 0xFFFF
    }
    return crc
}

private fun base64Encode(data: ByteArray, urlSafe: Boolean): String {
    val chars = if (urlSafe) "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    else "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val result = StringBuilder()
    var i = 0
    while (i < data.size) {
        var buf = 0
        var bits = 0
        for (j in 0 until 3) {
            if (i + j < data.size) {
                buf = (buf shl 8) or (data[i + j].toInt() and 0xFF)
                bits += 8
            }
        }
        i += 3
        while (bits > 0) {
            bits -= 6
            result.append(chars[(buf shr bits) and 0x3F])
        }
    }
    return result.toString()
}

private fun base64Decode(str: String): ByteArray {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val lookup = IntArray(128) { -1 }
    for (i in chars.indices) lookup[chars[i].code] = i
    // Also handle url-safe chars
    val altChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    for (i in altChars.indices) {
        val c = altChars[i].code
        if (lookup[c] == -1) lookup[c] = i
    }
    val buf = mutableListOf<Byte>()
    var accum = 0
    var bits = 0
    for (c in str) {
        val idx = if (c.code < 128) lookup[c.code] else -1
        if (idx < 0) continue
        accum = (accum shl 6) or idx
        bits += 6
        if (bits >= 8) {
            bits -= 8
            buf.add(((accum shr bits) and 0xFF).toByte())
        }
    }
    return buf.toByteArray()
}

private fun hexToByteArray(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "Invalid hex string length" }
    return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

//private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
