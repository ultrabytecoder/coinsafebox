package com.ultrabytecoder.coinsafebox.providers.ton.boc

enum class CellType(val value: Int) {
    ORDINARY(-1),
    PRUNED_BRANCH(1),
    LIBRARY(2),
    MERKLE_PROOF(3),
    MERKLE_UPDATE(4)
}
