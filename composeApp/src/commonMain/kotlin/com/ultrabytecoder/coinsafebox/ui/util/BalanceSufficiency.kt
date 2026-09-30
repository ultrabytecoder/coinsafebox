package com.ultrabytecoder.coinsafebox.ui.util

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ultrabytecoder.coinsafebox.domain.model.FeeEstimation

/**
 * Outcome of checking whether an account balance can cover a planned send.
 *
 * The variants distinguish *why* it is insufficient so the UI can phrase the
 * warning accurately:
 *  - [Sufficient]                     — the balance covers the send.
 *  - [InsufficientNative]             — native asset: amount + fee exceeds balance.
 *  - [InsufficientNativePendingFee]   — native asset: the amount alone exceeds the
 *                                       balance and the fee has not been estimated yet.
 *  - [InsufficientTokenBalance]       — token asset: the token amount exceeds the
 *                                       token balance. The network fee is paid in the
 *                                       parent native coin and is NOT part of this
 *                                       check, so the warning must not claim that gas
 *                                       was verified.
 */
sealed interface BalanceSufficiency {
    data object Sufficient : BalanceSufficiency
    data object InsufficientNative : BalanceSufficiency
    data object InsufficientNativePendingFee : BalanceSufficiency
    data object InsufficientTokenBalance : BalanceSufficiency
}

/**
 * Pure, side-effect-free predicate deciding whether [balanceRaw] (the account
 * balance, a plain decimal string in human coin/token units) can cover
 * [parsedAmount].
 *
 *  - Native accounts compare `amount + fee` to the balance. When the fee has not
 *    been estimated yet ([feeEstimation] is null) it falls back to `amount` alone
 *    so an obviously-too-large amount still surfaces an early (pending) warning.
 *  - Token accounts compare the token amount to the token balance only; the fee is
 *    denominated in the parent native coin and cannot be validated here.
 *
 * A blank or unparseable balance is treated as zero, so the result is never
 * over-permissive. A null or non-positive [parsedAmount] means there is nothing to
 * check and yields [BalanceSufficiency.Sufficient] (the blank/negative-input path is
 * handled elsewhere).
 */
fun evaluateBalanceSufficiency(
    balanceRaw: String,
    parsedAmount: BigDecimal?,
    feeEstimation: FeeEstimation?,
    isTokenAccount: Boolean
): BalanceSufficiency {
    if (parsedAmount == null || parsedAmount <= BigDecimal.ZERO) {
        return BalanceSufficiency.Sufficient
    }
    val balance = try {
        BigDecimal.parseString(balanceRaw)
    } catch (_: Exception) {
        BigDecimal.ZERO
    }
    return if (isTokenAccount) {
        if (parsedAmount > balance) BalanceSufficiency.InsufficientTokenBalance
        else BalanceSufficiency.Sufficient
    } else {
        val fee = feeEstimation?.totalCost
        if (fee == null) {
            if (parsedAmount > balance) BalanceSufficiency.InsufficientNativePendingFee
            else BalanceSufficiency.Sufficient
        } else {
            if (parsedAmount.add(fee) > balance) BalanceSufficiency.InsufficientNative
            else BalanceSufficiency.Sufficient
        }
    }
}
