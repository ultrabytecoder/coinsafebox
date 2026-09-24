package com.ultrabytecoder.coinsafebox.data

object MainnetTokens {
    val erc20: Map<String, TokenInfo> = mapOf(
        "USDC" to TokenInfo("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", 6),
        "LINK" to TokenInfo("0x514910771AF9Ca656af840dff83E8264EcF986CA", 18),
        "WETH" to TokenInfo("0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2", 18),
        "DAI" to TokenInfo("0x6B175474E89094C44Da98b954EedeAC495271d0F", 18),
        "USDT" to TokenInfo("0xdAC17F958D2ee523a2206206994597C13D831ec7", 6),
        "WBTC" to TokenInfo("0x2260FAC5E5542a773Aa44fBCfeDf7C193bc2C599", 8),
    )

    val trc20: Map<String, TokenInfo> = mapOf(
        "USDT" to TokenInfo("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", 6),
        "USDC" to TokenInfo("TEkxiTehnzPrSeKvMmRz73qLGCcEdVSMdT", 6),
        "BTT" to TokenInfo("TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf", 18),
        "WETH" to TokenInfo("TXm9mxnEgwaD67jfMe3TTk2Mut3e69E9KN", 18),
    )
}
