package com.ultrabytecoder.coinsafebox.ui.util

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ultrabytecoder.coinsafebox.domain.model.FeeEstimation
import kotlin.test.Test
import kotlin.test.assertEquals

class BalanceSufficiencyTest {

    private fun fee(cost: String) =
        FeeEstimation(totalCost = BigDecimal.parseString(cost), appliedParams = null)

    private fun amt(value: String) = BigDecimal.parseString(value)

    // --- Native accounts: amount + fee vs balance ---

    @Test
    fun native_sufficient_with_fee() {
        assertEquals(
            BalanceSufficiency.Sufficient,
            evaluateBalanceSufficiency("1.0", amt("0.9"), fee("0.01"), isTokenAccount = false)
        )
    }

    @Test
    fun native_insufficient_with_fee() {
        assertEquals(
            BalanceSufficiency.InsufficientNative,
            evaluateBalanceSufficiency("1.0", amt("0.99"), fee("0.02"), isTokenAccount = false)
        )
    }

    @Test
    fun native_exact_balance_is_sufficient() {
        // amount + fee == balance => the user may spend down to zero
        assertEquals(
            BalanceSufficiency.Sufficient,
            evaluateBalanceSufficiency("1.0", amt("0.99"), fee("0.01"), isTokenAccount = false)
        )
    }

    @Test
    fun native_fee_null_warns_when_amount_alone_exceeds_balance() {
        assertEquals(
            BalanceSufficiency.InsufficientNativePendingFee,
            evaluateBalanceSufficiency("0.5", amt("0.6"), null, isTokenAccount = false)
        )
    }

    @Test
    fun native_fee_null_no_warning_when_amount_under_balance() {
        assertEquals(
            BalanceSufficiency.Sufficient,
            evaluateBalanceSufficiency("1.0", amt("0.4"), null, isTokenAccount = false)
        )
    }

    @Test
    fun native_zero_balance_warns() {
        assertEquals(
            BalanceSufficiency.InsufficientNativePendingFee,
            evaluateBalanceSufficiency("0", amt("0.1"), null, isTokenAccount = false)
        )
    }

    // --- Token accounts: token amount vs token balance (fee ignored) ---

    @Test
    fun token_insufficient_ignores_fee() {
        assertEquals(
            BalanceSufficiency.InsufficientTokenBalance,
            evaluateBalanceSufficiency("10", amt("11"), fee("0.001"), isTokenAccount = true)
        )
    }

    @Test
    fun token_sufficient_fee_does_not_matter() {
        assertEquals(
            BalanceSufficiency.Sufficient,
            evaluateBalanceSufficiency("10", amt("9"), fee("999"), isTokenAccount = true)
        )
    }

    @Test
    fun token_exact_balance_is_sufficient() {
        assertEquals(
            BalanceSufficiency.Sufficient,
            evaluateBalanceSufficiency("10", amt("10"), null, isTokenAccount = true)
        )
    }

    // --- Edge cases ---

    @Test
    fun blank_amount_is_sufficient() {
        assertEquals(
            BalanceSufficiency.Sufficient,
            evaluateBalanceSufficiency("0", null, fee("0.01"), isTokenAccount = false)
        )
    }

    @Test
    fun zero_amount_is_sufficient() {
        assertEquals(
            BalanceSufficiency.Sufficient,
            evaluateBalanceSufficiency("0", amt("0"), fee("0.01"), isTokenAccount = false)
        )
    }

    @Test
    fun unparseable_balance_treated_as_zero() {
        assertEquals(
            BalanceSufficiency.InsufficientNative,
            evaluateBalanceSufficiency("", amt("0.1"), fee("0.01"), isTokenAccount = false)
        )
    }
}
