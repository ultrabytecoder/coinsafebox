package com.ultrabytecoder.coinsafebox.providers.ton.boc

import com.ultrabytecoder.coinsafebox.providers.ton.address.TonAddress

fun beginCell(): CellBuilder = CellBuilder()

class CellBuilder {
    private val bits = BitBuilder()
    private val refs = mutableListOf<Cell>()

    val bitsCount: Int get() = bits.length
    val refsCount: Int get() = refs.size
    val availableBits: Int get() = 1023 - bitsCount
    val availableRefs: Int get() = 4 - refsCount

    fun storeBit(value: Boolean): CellBuilder { bits.writeBit(value); return this }
    fun storeBits(src: BitString): CellBuilder { bits.writeBits(src); return this }
    fun storeBuffer(src: ByteArray): CellBuilder { bits.writeBuffer(src); return this }
    fun storeUint(value: Long, bitCount: Int): CellBuilder { bits.writeUint(value, bitCount); return this }
    fun storeInt(value: Long, bitCount: Int): CellBuilder { bits.writeInt(value, bitCount); return this }
    fun storeVarUint(value: Long, headerBits: Int): CellBuilder { bits.writeVarUint(value, headerBits); return this }
    fun storeCoins(amount: Long): CellBuilder { bits.writeCoins(amount); return this }
    fun storeAddress(address: TonAddress?): CellBuilder { bits.writeAddress(address); return this }

    fun storeRef(cell: Cell): CellBuilder {
        require(refs.size < 4) { "Too many references" }
        refs.add(cell)
        return this
    }

    fun storeMaybeRef(cell: Cell?): CellBuilder {
        if (cell != null) {
            storeBit(true)
            storeRef(cell)
        } else {
            storeBit(false)
        }
        return this
    }

    fun storeWritable(writer: (CellBuilder) -> Unit): CellBuilder {
        writer(this)
        return this
    }

    fun storeSlice(src: Slice): CellBuilder {
        // Store bits directly
        val bits = src.remainingBits
        if (bits > 0) {
            for (i in 0 until bits) {
                storeBit(src.loadBit())
            }
        }
        // Store refs
        while (src.remainingRefs > 0) {
            storeRef(src.loadRef())
        }
        return this
    }

    fun endCell(): Cell = Cell.create(bits.build(), refs.toList())

    fun asCell(): Cell = endCell()
}
