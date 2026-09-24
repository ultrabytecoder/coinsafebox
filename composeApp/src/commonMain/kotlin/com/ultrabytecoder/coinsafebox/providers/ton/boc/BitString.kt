package com.ultrabytecoder.coinsafebox.providers.ton.boc

class BitString(val data: ByteArray, val offset: Int, val length: Int) {

    companion object {
        val EMPTY = BitString(ByteArray(0), 0, 0)
        private const val HEX = "0123456789ABCDEF"
    }

    init {
        require(length >= 0) { "Length $length is out of bounds" }
    }

    fun at(index: Int): Boolean {
        require(index in 0 until length) { "Index $index is out of bounds (length=$length)" }
        val byteIndex = (offset + index) shr 3
        val bitIndex = 7 - ((offset + index) and 7)
        return (data[byteIndex].toInt() and (1 shl bitIndex)) != 0
    }

    fun substring(offset: Int, length: Int): BitString {
        require(offset >= 0) { "Offset $offset < 0" }
        require(offset <= this.length) { "Offset $offset > ${this.length}" }
        if (length == 0) return EMPTY
        require(offset + length <= this.length) { "Offset $offset + Length $length > ${this.length}" }
        return BitString(data, this.offset + offset, length)
    }

    fun subbuffer(offset: Int, length: Int): ByteArray? {
        if (offset < 0 || offset > this.length) return null
        if (offset + length > this.length) return null
        if (length % 8 != 0) return null
        if ((this.offset + offset) % 8 != 0) return null
        val start = (this.offset + offset) shr 3
        val end = start + (length shr 3)
        return data.copyOfRange(start, end)
    }

    override fun toString(): String {
        if (length == 0) return ""
        val sb = StringBuilder()
        val fullNibbles = length / 4
        for (i in 0 until fullNibbles) {
            var nibble = 0
            for (j in 0 until 4) {
                if (at(i * 4 + j)) nibble = nibble or (1 shl (3 - j))
            }
            sb.append(HEX[nibble])
        }
        val remaining = length % 4
        if (remaining > 0) {
            var nibble = 0
            for (j in 0 until remaining) {
                if (at(fullNibbles * 4 + j)) nibble = nibble or (1 shl (3 - j))
            }
            nibble = nibble or (1 shl (3 - remaining))
            sb.append(HEX[nibble])
            sb.append('_')
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BitString) return false
        if (length != other.length) return false
        for (i in 0 until length) {
            if (at(i) != other.at(i)) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var h = length
        for (i in 0 until length) {
            if (at(i)) h = h * 31 + 1
        }
        return h
    }
}
