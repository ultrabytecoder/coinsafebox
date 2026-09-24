package com.ultrabytecoder.coinsafebox.domain.model

import com.ultrabytecoder.coinsafebox.data.CustomNodeKeys
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed class AccountType(val type: String) {
    data object Btc : AccountType("BTC")
    data object Eth : AccountType("ETH")
    data object Trx : AccountType("TRX")
    data class Ton(val walletVersion: String = "V3R2") : AccountType("GRAM")
    data class Erc20(val tokenAddress: String) : AccountType("ERC20")
    data class Trc20(val tokenAddress: String) : AccountType("TRC20")
    data class TonToken(val jettonMasterAddress: String) : AccountType("TON_TOKEN")

    val isNative: Boolean get() = this is Btc || this is Eth || this is Trx || this is Ton
    val isToken: Boolean get() = this is Erc20 || this is Trc20 || this is TonToken

    /** Human-readable chain label for tokens, e.g. "ERC20" / "TRC20". */
    val chainLabel: String
        get() = when (this) {
            is Erc20 -> "ERC20"
            is Trc20 -> "TRC20"
            is TonToken -> "GRAM"
            else -> type
        }

    /** Returns the parent native chain for a token type, or null for native types. */
    fun parentChain(): AccountType? = when (this) {
        is Erc20 -> Eth
        is Trc20 -> Trx
        is TonToken -> Ton()
        else -> null
    }

    /** Returns the on-chain contract address for token types (lowercased), or null for native types. */
    val tokenContractAddress: String?
        get() = when (this) {
            is Erc20 -> tokenAddress.lowercase()
            is Trc20 -> tokenAddress.lowercase()
            is TonToken -> jettonMasterAddress.lowercase()
            else -> null
        }

    fun toDbCode(): String = type

    fun toParamsJson(): String? = when (this) {
        is Erc20 -> """{"tokenAddress":"$tokenContractAddress"}"""
        is Trc20 -> """{"tokenAddress":"$tokenContractAddress"}"""
        is TonToken -> """{"jettonMasterAddress":"$tokenContractAddress"}"""
        is Ton -> """{"walletVersion":"$walletVersion"}"""
        else -> null
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromCode(code: String, params: String? = null): AccountType = when (code) {
            "BTC" -> Btc
            "ETH" -> Eth
            "TRX" -> Trx
            "GRAM", "TON" -> {
                val version = parseWalletVersion(params)
                Ton(version)
            }
            "ERC20" -> {
                val tokenAddress = parseTokenAddress(params)
                Erc20(tokenAddress)
            }
            "TRC20" -> {
                val tokenAddress = parseTokenAddress(params)
                Trc20(tokenAddress)
            }
            "TON_TOKEN" -> {
                val jettonMasterAddress = parseJettonMasterAddress(params)
                TonToken(jettonMasterAddress)
            }
            else -> throw IllegalArgumentException("Unsupported account type: $code")
        }

        private fun parseTokenAddress(params: String?): String {
            val parsed: JsonObject? = params?.let { json.parseToJsonElement(it).jsonObject }
            return parsed?.get("tokenAddress")?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing tokenAddress in params")
        }

        private fun parseWalletVersion(params: String?): String {
            val parsed: JsonObject? = params?.let { json.parseToJsonElement(it).jsonObject }
            return parsed?.get("walletVersion")?.jsonPrimitive?.content ?: "V3R2"
        }

        private fun parseJettonMasterAddress(params: String?): String {
            val parsed: JsonObject? = params?.let { json.parseToJsonElement(it).jsonObject }
            return parsed?.get("jettonMasterAddress")?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing jettonMasterAddress in params")
        }
    }
}

/**
 * Ticker of the currency in which the network fee for a transfer from an
 * account of this type is denominated. For token types the fee is paid in the
 * parent chain's native coin, never in the token itself.
 */
val AccountType.feeSymbol: String
    get() = when (this) {
        is AccountType.Btc -> "BTC"
        is AccountType.Eth, is AccountType.Erc20 -> "ETH"
        is AccountType.Trx, is AccountType.Trc20 -> "TRX"
        is AccountType.Ton, is AccountType.TonToken -> "GRAM"
    }

/**
 * Native blockchains for which the user may configure a custom RPC/API node.
 * Token types (ERC20/TRC20) inherit the parent chain's node, so they are not
 * listed separately.
 */
enum class ChainType(
    val displayName: String,
    val storageKey: String,
) {
    BTC("Bitcoin",  CustomNodeKeys.BTC),
    ETH("Ethereum", CustomNodeKeys.ETH),
    TRX("Tron",     CustomNodeKeys.TRX),
    TON("GRAM",     CustomNodeKeys.TON);

    /** Default URL for this chain in the supplied [config]. */
    fun defaultUrl(config: NetworkConfig): String = when (this) {
        BTC -> config.btcMempoolApiBase
        ETH -> config.ethRpcUrl
        TRX -> config.tronApiBase
        TON -> config.tonApiBase
    }
}
