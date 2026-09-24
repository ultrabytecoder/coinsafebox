package com.ultrabytecoder.coinsafebox.providers.ton.wallet

import com.ultrabytecoder.coinsafebox.providers.ton.boc.*
import com.ultrabytecoder.coinsafebox.providers.ton.types.MessageRelaxed
import com.ultrabytecoder.coinsafebox.providers.ton.types.storeMessageRelaxed
import io.github.andreypfau.curve25519.ed25519.Ed25519PrivateKey
import io.github.andreypfau.curve25519.ed25519.Ed25519

fun createWalletTransferV3(
    seqno: Int,
    walletId: Int,
    secretKey: ByteArray,
    messages: List<MessageRelaxed>,
    sendMode: Int = 3, // PAY_GAS_SEPARATELY
    timeout: Int? = null
): Cell {
    require(messages.size <= 4) { "Maximum 4 messages per transfer" }

    val signingMessage = beginCell()
        .storeUint(walletId.toLong(), 32)
    if (seqno == 0) {
        repeat(32) { signingMessage.storeBit(true) }
    } else {
        val timeoutSeconds = timeout?.toLong() ?: (kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000 + 60)
        signingMessage.storeUint(timeoutSeconds, 32)
    }
    signingMessage.storeUint(seqno.toLong(), 32)
    for (m in messages) {
        signingMessage.storeUint(sendMode.toLong(), 8)
        signingMessage.storeRef(beginCell().storeWritable(storeMessageRelaxed(m)).endCell())
    }

    val signingCell = signingMessage.endCell()
    val signature = Ed25519.keyFromSeed(secretKey).sign(signingCell.hash())

    return beginCell()
        .storeBuffer(signature)
        .storeSlice(signingCell.beginParse())
        .endCell()
}

fun createWalletTransferV4(
    seqno: Int,
    walletId: Int,
    secretKey: ByteArray,
    messages: List<MessageRelaxed>,
    sendMode: Int = 3,
    timeout: Int? = null
): Cell {
    require(messages.size <= 4) { "Maximum 4 messages per transfer" }

    val signingMessage = beginCell()
        .storeUint(walletId.toLong(), 32)
    if (seqno == 0) {
        repeat(32) { signingMessage.storeBit(true) }
    } else {
        val timeoutSeconds = timeout?.toLong() ?: (kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000 + 60)
        signingMessage.storeUint(timeoutSeconds, 32)
    }
    signingMessage.storeUint(seqno.toLong(), 32)
    signingMessage.storeUint(0, 8) // Simple order
    for (m in messages) {
        signingMessage.storeUint(sendMode.toLong(), 8)
        signingMessage.storeRef(beginCell().storeWritable(storeMessageRelaxed(m)).endCell())
    }

    val signingCell = signingMessage.endCell()
    val signature = Ed25519.keyFromSeed(secretKey).sign(signingCell.hash())

    return beginCell()
        .storeBuffer(signature)
        .storeSlice(signingCell.beginParse())
        .endCell()
}
