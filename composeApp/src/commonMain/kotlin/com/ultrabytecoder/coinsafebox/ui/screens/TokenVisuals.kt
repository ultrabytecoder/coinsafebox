package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.DrawableResource
import coinsafebox.composeapp.generated.resources.Res
import coinsafebox.composeapp.generated.resources.ic_usdc
import coinsafebox.composeapp.generated.resources.ic_link
import coinsafebox.composeapp.generated.resources.ic_weth
import coinsafebox.composeapp.generated.resources.ic_dai
import coinsafebox.composeapp.generated.resources.ic_usdt
import coinsafebox.composeapp.generated.resources.ic_wbtc
import coinsafebox.composeapp.generated.resources.ic_btt
import coinsafebox.composeapp.generated.resources.ic_token_trc20
import coinsafebox.composeapp.generated.resources.ic_eth
import coinsafebox.composeapp.generated.resources.ic_trx

internal fun tokenIconFor(symbol: String, isErc20: Boolean): DrawableResource {
    return if (isErc20) {
        when (symbol) {
            "USDC" -> Res.drawable.ic_usdc
            "LINK" -> Res.drawable.ic_link
            "WETH" -> Res.drawable.ic_weth
            "DAI" -> Res.drawable.ic_dai
            "USDT" -> Res.drawable.ic_usdt
            "WBTC" -> Res.drawable.ic_wbtc
            else -> Res.drawable.ic_eth
        }
    } else {
        when (symbol) {
            "USDT" -> Res.drawable.ic_usdt
            "USDC" -> Res.drawable.ic_usdc
            "BTT" -> Res.drawable.ic_btt
            "WETH" -> Res.drawable.ic_weth
            else -> Res.drawable.ic_token_trc20
        }
    }
}

internal fun tokenColorFor(isErc20: Boolean): Color {
    return if (isErc20) Color(0xFF8B9FE8)
    else Color(0xFFFF4D5A)
}