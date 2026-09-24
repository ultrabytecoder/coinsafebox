package com.ultrabytecoder.coinsafebox.domain.model

enum class TransactionStatus(val code: String) {
    PENDING("PENDING"),
    CONFIRMED("CONFIRMED"),
    FAILED("FAILED");

    companion object {
        fun fromCode(code: String): TransactionStatus =
            entries.find { it.code == code }
                ?: throw IllegalArgumentException("Unknown transaction status: $code")
    }
}
