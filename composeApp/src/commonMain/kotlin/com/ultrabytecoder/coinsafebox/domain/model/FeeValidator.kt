package com.ultrabytecoder.coinsafebox.domain.model

object FeeValidator {
    // BTC: 1..2000 sat/vB
    fun validateBtcFeeRate(feeRate: Long): Boolean = feeRate in 1L..2000L

    // ETH (milli-Gwei): 1..10_000_000 mGwei for max fee (0.001..10000 Gwei),
    // 1..1_000_000 mGwei for priority fee (0.001..1000 Gwei)
    fun validateEthMaxFee(maxFeeMilliGwei: Long): Boolean = maxFeeMilliGwei in 1L..10_000_000L
    fun validateEthPriorityFee(priorityFeeMilliGwei: Long): Boolean = priorityFeeMilliGwei in 1L..1_000_000L

    /**
     * Validate ETH fee params together — EIP-1559 requires maxFee >= priorityFee.
     */
    fun validateEthFeeParams(priorityFeeMilliGwei: Long, maxFeeMilliGwei: Long): Boolean {
        return validateEthPriorityFee(priorityFeeMilliGwei) &&
            validateEthMaxFee(maxFeeMilliGwei) &&
            maxFeeMilliGwei >= priorityFeeMilliGwei
    }

    // TRC-20: 1..100 TRX (1_000_000..100_000_000 SUN)
    fun validateTrc20FeeLimit(feeLimitSun: Long): Boolean = feeLimitSun in 1_000_000L..100_000_000L
}