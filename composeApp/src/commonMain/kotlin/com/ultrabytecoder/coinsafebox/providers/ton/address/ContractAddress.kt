package com.ultrabytecoder.coinsafebox.providers.ton.address

import com.ultrabytecoder.coinsafebox.providers.ton.boc.*

fun contractAddress(workchain: Int, code: Cell, data: Cell): TonAddress {
    val stateInitCell = beginCell()
        .storeBit(false) // no split_depth
        .storeBit(false) // no special
        .storeBit(true)  // code present
        .storeBit(true)  // data present
        .storeBit(false) // empty library
        .storeRef(code)
        .storeRef(data)
        .endCell()
    return TonAddress(workchain, stateInitCell.hash())
}
