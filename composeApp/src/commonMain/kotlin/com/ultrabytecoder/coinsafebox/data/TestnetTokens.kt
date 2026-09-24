package com.ultrabytecoder.coinsafebox.data

data class TokenInfo(
    val address: String,
    val decimals: Int,
)

object TestnetTokens {
    val erc20Sepolia: Map<String, TokenInfo> = mapOf(
        "USDC" to TokenInfo("0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238", 6),
        "LINK" to TokenInfo("0x779877A7B0D9E8603169DdbD7836e478b4624789", 18),
        "WETH" to TokenInfo("0x7b79995e5f793A07Bc00c21412e50Ecae098E7f9", 18),
        "DAI" to TokenInfo("0xff34b3d4aee8ddcd6f9afffb6fe49bd371b8a357", 18),
        "USDT" to TokenInfo("0x7169D38820dfd117C3fa1f22a697dba58d90BA06", 6),
        "WBTC" to TokenInfo("0x29f2D40B060cca9757C756d8244D569865662692", 8),
    )

    val trc20Nile: Map<String, TokenInfo> = mapOf(
        "USDT" to TokenInfo("TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf", 6),
        "USDC" to TokenInfo("TEMVynQpntMqkPxP6wXTW2K7e4sM3cRmWz", 6),
        "BTT" to TokenInfo("TNuoKL1ni8aoshfFL1ASca1Gou9RXwAzfn", 18),
        "WETH" to TokenInfo("TXm9mxnEgwaD67jfMe3TTk2Mut3e69E9KN", 18)
    )
}
