package com.ultrabytecoder.coinsafebox.domain.model

import com.ionspin.kotlin.bignum.decimal.BigDecimal

/**
 * Wrapper class returned by Provider.estimateFee() that carries both the
 * final fee cost and the exact parameters that were used to compute it.
 *
 * This enables the UI to display, e.g., "Auto mode used 5 Gwei priority fee
 * and 35 Gwei max fee" rather than just showing a bare amount.
 */
data class FeeEstimation(
    val totalCost: BigDecimal,
    val appliedParams: CustomFeeParams?,
    /** True when live EIP-1559 fee fetching failed and legacy gas price was used. */
    val usedFallbackFees: Boolean = false
)