package com.ultrabytecoder.coinsafebox.providers.ton.types

import com.ultrabytecoder.coinsafebox.providers.ton.boc.*

data class StateInit(val code: Cell? = null, val data: Cell? = null)

fun storeStateInit(src: StateInit): (CellBuilder) -> Unit = { builder ->
    builder.storeBit(false) // no split_depth
    builder.storeBit(false) // no special
    builder.storeMaybeRef(src.code)
    builder.storeMaybeRef(src.data)
    builder.storeBit(false) // empty library
}
