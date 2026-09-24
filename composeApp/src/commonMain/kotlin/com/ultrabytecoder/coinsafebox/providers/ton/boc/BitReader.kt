package com.ultrabytecoder.coinsafebox.providers.ton.boc

import com.ultrabytecoder.coinsafebox.providers.ton.address.TonAddress

class BitReader(private val _bits: BitString, offset: Int = 0) {
    private var _offset = offset

    val offset: Int get() = _offset
    val remaining: Int get() = _bits.length - _offset

    fun skip(bitCount: Int) {
        require(bitCount >= 0 && _offset + bitCount <= _bits.length) { "Index ${_offset + bitCount} out of bounds" }
        _offset += bitCount
    }

    fun loadBit(): Boolean {
        val r = _bits.at(_offset)
        _offset++
        return r
    }

    fun preloadBit(): Boolean = _bits.at(_offset)

    fun loadBits(bitCount: Int): BitString {
        val r = _bits.substring(_offset, bitCount)
        _offset += bitCount
        return r
    }

    fun loadBuffer(bytes: Int): ByteArray {
        val fast = _bits.subbuffer(_offset, bytes * 8)
        if (fast != null) {
            _offset += bytes * 8
            return fast
        }
        val buf = ByteArray(bytes)
        for (i in 0 until bytes) {
            buf[i] = preloadUint(8, _offset + i * 8).toByte()
        }
        _offset += bytes * 8
        return buf
    }

    fun loadUint(bitCount: Int): Long = preloadUint(bitCount, _offset).also { _offset += bitCount }
    fun loadInt(bitCount: Int): Long = preloadInt(bitCount, _offset).also { _offset += bitCount }

    fun loadVarUint(headerBits: Int): Long {
        val size = loadUint(headerBits).toInt()
        return loadUint(size * 8)
    }

    fun loadCoins(): Long = loadVarUint(4)

    fun loadAddress(): TonAddress {
        val type = preloadUint(2, _offset).toInt()
        check(type == 2) { "Invalid address type: $type" }
        return loadInternalAddress()
    }

    fun loadMaybeAddress(): TonAddress? {
        val type = preloadUint(2, _offset).toInt()
        return when (type) {
            0 -> { _offset += 2; null }
            2 -> loadInternalAddress()
            else -> throw IllegalStateException("Invalid address type: $type")
        }
    }

    fun loadPaddedBits(bitCount: Int): BitString {
        require(bitCount % 8 == 0) { "Invalid number of bits: $bitCount" }
        var len = bitCount
        while (true) {
            if (_bits.at(_offset + len - 1)) {
                len--
                break
            } else {
                len--
            }
        }
        val r = _bits.substring(_offset, len)
        _offset += bitCount
        return r
    }

    fun clone(): BitReader = BitReader(_bits, _offset)

    private fun loadInternalAddress(): TonAddress {
        val type = preloadUint(2, _offset).toInt()
        check(type == 2) { "Invalid address" }
        check(preloadUint(1, _offset + 2) == 0L) { "Anycast not supported" }
        val wc = preloadInt(8, _offset + 3).toInt()
        val hash = ByteArray(32)
        for (i in 0 until 32) {
            hash[i] = preloadUint(8, _offset + 11 + i * 8).toByte()
        }
        _offset += 267
        return TonAddress(wc, hash)
    }

    private fun preloadUint(bitCount: Int, off: Int): Long {
        if (bitCount == 0) return 0L
        var res = 0L
        for (i in 0 until bitCount) {
            if (_bits.at(off + i)) {
                res += 1L shl (bitCount - i - 1)
            }
        }
        return res
    }

    private fun preloadInt(bitCount: Int, off: Int): Long {
        if (bitCount == 0) return 0L
        val sign = _bits.at(off)
        var res = 0L
        for (i in 0 until bitCount - 1) {
            if (_bits.at(off + 1 + i)) {
                res += 1L shl (bitCount - i - 1 - 1)
            }
        }
        if (sign) res -= 1L shl (bitCount - 1)
        return res
    }
}
