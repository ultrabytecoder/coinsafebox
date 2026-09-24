package com.ultrabytecoder.coinsafebox.providers.ton.boc

class LevelMask(mask: Int = 0) {
    val value: Int = mask
    val level: Int = if (mask == 0) 0 else 32 - mask.countLeadingZeroBits()
    val hashIndex: Int = mask.countOneBits()
    val hashCount: Int = hashIndex + 1

    fun apply(level: Int): LevelMask = LevelMask(value and ((1 shl level) - 1))

    fun isSignificant(level: Int): Boolean =
        level == 0 || (value shr (level - 1)) % 2 != 0
}
