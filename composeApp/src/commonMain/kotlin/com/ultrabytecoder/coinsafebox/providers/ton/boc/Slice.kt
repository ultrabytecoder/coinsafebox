package com.ultrabytecoder.coinsafebox.providers.ton.boc

import com.ultrabytecoder.coinsafebox.providers.ton.address.TonAddress

class Slice(
    private val reader: BitReader,
    private val refs: MutableList<Cell>
) {
    private var refsOffset = 0

    val remainingBits: Int get() = reader.remaining
    val remainingRefs: Int get() = refs.size - refsOffset

    fun loadBit(): Boolean = reader.loadBit()
    fun preloadBit(): Boolean = reader.preloadBit()
    fun loadBits(bitCount: Int): BitString = reader.loadBits(bitCount)
    fun loadUint(bitCount: Int): Long = reader.loadUint(bitCount)
    fun loadInt(bitCount: Int): Long = reader.loadInt(bitCount)
    fun loadBuffer(bytes: Int): ByteArray = reader.loadBuffer(bytes)
    fun loadVarUint(headerBits: Int): Long = reader.loadVarUint(headerBits)
    fun loadCoins(): Long = reader.loadCoins()
    fun loadAddress(): TonAddress = reader.loadAddress()
    fun loadMaybeAddress(): TonAddress? = reader.loadMaybeAddress()

    fun loadRef(): Cell {
        require(refsOffset < refs.size) { "No more references" }
        return refs[refsOffset++]
    }

    fun preloadRef(): Cell {
        require(refsOffset < refs.size) { "No more references" }
        return refs[refsOffset]
    }

    fun loadMaybeRef(): Cell? {
        return if (loadBit()) loadRef() else null
    }

    fun skip(bitCount: Int): Slice { reader.skip(bitCount); return this }

    fun endParse() {
        require(remainingBits == 0 && remainingRefs == 0) { "Slice is not empty" }
    }

    fun asCell(): Cell = beginCell().storeSlice(this).endCell()

    fun clone(): Slice {
        val s = Slice(reader.clone(), refs)
        s.refsOffset = refsOffset
        return s
    }
}
