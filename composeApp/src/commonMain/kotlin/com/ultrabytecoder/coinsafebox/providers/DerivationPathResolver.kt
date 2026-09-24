package com.ultrabytecoder.coinsafebox.providers

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountType

object DerivationPathResolver {

    fun defaultPath(
        accountType: AccountType,
        accountIndex: Long,
        networkConfig: NetworkConfig
    ): String = when (accountType) {
        is AccountType.Btc -> "m/84'/${networkConfig.btcBip84CoinType}'/$accountIndex'"
        is AccountType.Eth -> "m/44'/60'/$accountIndex'/0/0"
        is AccountType.Trx -> "m/44'/195'/$accountIndex'/0/0"
        is AccountType.Ton -> "m/44'/607'/$accountIndex'"
        is AccountType.Erc20 -> error("ERC20 tokens do not have their own derivation path")
        is AccountType.Trc20 -> error("TRC20 tokens do not have their own derivation path")
        is AccountType.TonToken -> error("Jetton tokens do not have their own derivation path")
    }

    fun expectedDepth(accountType: AccountType): Int = when (accountType) {
        is AccountType.Btc -> 3
        is AccountType.Ton -> 3
        is AccountType.TonToken -> 3
        else -> 5
    }

    fun isValidPath(path: String, accountType: AccountType, networkConfig: NetworkConfig): Boolean {
        val segments = parsePath(path)
        if (segments.isEmpty()) return false
        if (segments.size != expectedDepth(accountType)) return false

        // Validate BIP44 prefix and coin type for native chains to prevent cross-chain fund loss
        if (accountType.isNative) {
            val expectedPrefix = when (accountType) {
                is AccountType.Btc -> "m/84'/${networkConfig.btcBip84CoinType}'"
                is AccountType.Eth -> "m/44'/60'"
                is AccountType.Trx -> "m/44'/195'"
                is AccountType.Ton -> "m/44'/607'"
                else -> return false
            }
            if (!path.startsWith(expectedPrefix)) return false
        }
        return true
    }

    fun parsePath(path: String): List<Pair<Long, Boolean>> {
        if (!path.startsWith("m/")) return emptyList()
        val parts = path.removePrefix("m/").split("/")
        if (parts.isEmpty()) return emptyList()
        return parts.mapNotNull { part ->
            val hardened = part.endsWith("'")
            val numStr = if (hardened) part.removeSuffix("'") else part
            val num = numStr.toLongOrNull() ?: return@mapNotNull null
            if (num < 0) return@mapNotNull null
            num to hardened
        }
    }
}
