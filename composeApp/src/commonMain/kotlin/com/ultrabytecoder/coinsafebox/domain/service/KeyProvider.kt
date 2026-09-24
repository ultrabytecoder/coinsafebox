package com.ultrabytecoder.coinsafebox.domain.service

interface KeyProvider {
    suspend fun <T> withMasterSeed(walletId: Long, block: suspend (ByteArray) -> T): T
}