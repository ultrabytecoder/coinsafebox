package com.ultrabytecoder.coinsafebox.providers.ton.types

import com.ultrabytecoder.coinsafebox.providers.ton.address.TonAddress
import com.ultrabytecoder.coinsafebox.providers.ton.boc.*

data class MessageRelaxed(
    val dest: TonAddress,
    val value: Long,
    val bounce: Boolean = true,
    val body: Cell = Cell.EMPTY,
    val init: StateInit? = null
)

fun storeMessageRelaxed(msg: MessageRelaxed): (CellBuilder) -> Unit = { builder ->
    // int_msg_info$0
    builder.storeBit(false)
    builder.storeBit(true)  // ihr_disabled
    builder.storeBit(msg.bounce)
    builder.storeBit(false) // bounced
    builder.storeAddress(null) // src: addr_none
    builder.storeAddress(msg.dest)
    builder.storeCoins(msg.value)
    builder.storeBit(false) // extra currency empty
    builder.storeCoins(0)   // ihr_fee
    builder.storeCoins(0)   // fwd_fee
    builder.storeUint(0, 64) // created_lt
    builder.storeUint(0, 32) // created_at

    // init
    if (msg.init != null) {
        builder.storeBit(true)
        val initCell = beginCell().storeWritable(storeStateInit(msg.init)).endCell()
        if (builder.availableBits - 2 >= initCell.bits.length) {
            builder.storeBit(false)
            builder.storeSlice(initCell.beginParse())
        } else {
            builder.storeBit(true)
            builder.storeRef(initCell)
        }
    } else {
        builder.storeBit(false)
    }

    // body
    if (builder.availableBits - 1 >= msg.body.bits.length && builder.refsCount + msg.body.refs.size <= 4) {
        builder.storeBit(false)
        builder.storeSlice(msg.body.beginParse())
    } else {
        builder.storeBit(true)
        builder.storeRef(msg.body)
    }
}

fun internalMessage(
    to: TonAddress,
    value: Long,
    bounce: Boolean = true,
    body: Cell = Cell.EMPTY,
    init: StateInit? = null
): MessageRelaxed = MessageRelaxed(dest = to, value = value, bounce = bounce, body = body, init = init)
