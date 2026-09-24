package com.ultrabytecoder.coinsafebox

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform