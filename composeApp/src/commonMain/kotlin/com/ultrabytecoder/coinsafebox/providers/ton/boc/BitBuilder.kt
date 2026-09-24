package com.ultrabytecoder.coinsafebox.providers.ton.boc

import com.ultrabytecoder.coinsafebox.providers.ton.address.TonAddress

class BitBuilder(size: Int = 1023) {
    private val buffer = ByteArray((size + 7) / 8)
    private var _length = 0

    val length: Int get() = _length

    fun writeBit(value: Boolean) {
        check(_length < buffer.size * 8) { "BitBuilder overflow" }
        if (value) {
            buffer[_length / 8] = (buffer[_length / 8].toInt() or (1 shl (7 - (_length % 8)))).toByte()
        }
        _length++
    }

    fun writeBits(src: BitString) {
        for (i in 0 until src.length) {
            writeBit(src.at(i))
        }
    }

    fun writeBuffer(src: ByteArray) {
        if (_length % 8 == 0) {
            check(_length + src.size * 8 <= buffer.size * 8) { "BitBuilder overflow" }
            src.copyInto(buffer, _length / 8)
            _length += src.size * 8
        } else {
            for (b in src) {
                writeUint(b.toLong() and 0xFF, 8)
            }
        }
    }

    fun writeUint(value: Long, bits: Int) {
        if (bits == 8 && _length % 8 == 0) {
            check(value in 0..255) { "Value $value out of range for $bits bits" }
            buffer[_length / 8] = value.toByte()
            _length += 8
            return
        }
        if (bits == 16 && _length % 8 == 0) {
            check(value in 0..65535) { "Value $value out of range for $bits bits" }
            buffer[_length / 8] = (value shr 8).toByte()
            buffer[_length / 8 + 1] = (value and 0xFF).toByte()
            _length += 16
            return
        }
        require(bits >= 0) { "Invalid bit length: $bits" }
        if (bits == 0) {
            check(value == 0L) { "Value $value is not zero for $bits bits" }
            return
        }
        check(value >= 0 && value < (1L shl bits)) { "Value $value out of range for $bits bits" }
        for (i in bits - 1 downTo 0) {
            writeBit((value shr i) and 1L != 0L)
        }
    }

    fun writeInt(value: Long, bits: Int) {
        require(bits >= 0) { "Invalid bit length: $bits" }
        if (bits == 0) {
            check(value == 0L) { "Value $value is not zero for $bits bits" }
            return
        }
        if (bits == 1) {
            check(value == 0L || value == -1L) { "Value $value is not 0 or -1 for 1 bit" }
            writeBit(value == -1L)
            return
        }
        val vBits = 1L shl (bits - 1)
        check(value >= -vBits && value < vBits) { "Value $value out of range for $bits bits" }
        if (value < 0) {
            writeBit(true)
            writeUint(vBits + value, bits - 1)
        } else {
            writeBit(false)
            writeUint(value, bits - 1)
        }
    }

    fun writeVarUint(value: Long, headerBits: Int) {
        require(headerBits >= 0) { "Invalid header bits: $headerBits" }
        require(value >= 0) { "Value is negative: $value" }
        if (value == 0L) {
            writeUint(0, headerBits)
            return
        }
        val sizeBytes = (64 - value.countLeadingZeroBits() + 7) / 8
        writeUint(sizeBytes.toLong(), headerBits)
        writeUint(value, sizeBytes * 8)
    }

    fun writeCoins(amount: Long) {
        writeVarUint(amount, 4)
    }

    fun writeAddress(address: TonAddress?) {
        if (address == null) {
            writeUint(0, 2)
            return
        }
        writeUint(2, 2)
        writeUint(0, 1)
        writeInt(address.workChain.toLong(), 8)
        writeBuffer(address.hash)
    }

    fun build(): BitString = BitString(buffer, 0, _length)

    fun buffer(): ByteArray {
        check(_length % 8 == 0) { "BitBuilder buffer is not byte aligned" }
        return buffer.copyOfRange(0, _length / 8)
    }
}
