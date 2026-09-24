package com.ultrabytecoder.coinsafebox.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AccountTypeFeeSymbolTest {

    @Test
    fun nativeTypes_mapToOwnTicker() {
        assertEquals("BTC", AccountType.Btc.feeSymbol)
        assertEquals("ETH", AccountType.Eth.feeSymbol)
        assertEquals("TRX", AccountType.Trx.feeSymbol)
        assertEquals("GRAM", AccountType.Ton().feeSymbol)
    }

    @Test
    fun tokenTypes_mapToParentChainNativeTicker() {
        assertEquals("ETH", AccountType.Erc20("0xabc").feeSymbol)
        assertEquals("TRX", AccountType.Trc20("Tabc").feeSymbol)
        assertEquals("GRAM", AccountType.TonToken("EQabc").feeSymbol)
    }
}
