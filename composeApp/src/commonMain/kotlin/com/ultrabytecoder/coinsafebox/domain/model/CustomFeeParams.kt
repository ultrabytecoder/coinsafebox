package com.ultrabytecoder.coinsafebox.domain.model

/**
 * User-selected fee parameters per chain.
 * [Default] means the provider picks fees automatically.
 */
sealed class CustomFeeParams {
    abstract val chain: AccountType // Add this to enforce validation

    data class Btc(val feeRateSatVb: Long) : CustomFeeParams() {
        override val chain = AccountType.Btc
    }
    data class Eth(
        val maxPriorityFeePerGasMilliGwei: Long,
        val maxFeePerGasMilliGwei: Long,
        val gasLimit: Long? = null
    ) : CustomFeeParams() {
        override val chain = AccountType.Eth
    }
    /**
     * TRON smart-contract call fee cap (SUN). Applies only to energy-consuming
     * contract calls such as TRC20 transfers; native TRX transfers have no fee
     * parameter. A higher cap is a safety margin, not a speed bid.
     */
    data class Tron(val feeLimitSun: Long) : CustomFeeParams() {
        override val chain = AccountType.Trx
    }
}

/**
 * Fee presets returned by providers for auto/slow/medium/fast.
 * Values are in milli-Gwei (1 mGwei = 1e6 wei = 0.001 Gwei) so sub-Gwei
 * priority fees on L2s are representable. [auto] carries the live network
 * values fetched by the provider, used by the Auto fee chip.
 */
data class FeePresets(
    val auto: CustomFeeParams? = null,
    val slow: CustomFeeParams,
    val medium: CustomFeeParams,
    val fast: CustomFeeParams
)