package com.ultrabytecoder.coinsafebox.data

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.BlockHash

/**
 * Returns a copy of this [NetworkConfig] with any per-chain custom node URLs
 * stored in [storage] applied. Chains without a saved custom value keep their
 * default URL.
 */
fun NetworkConfig.applyCustomNodes(storage: SettingsStorage): NetworkConfig {
    val btc = storage.getString(CustomNodeKeys.BTC)
    val eth = storage.getString(CustomNodeKeys.ETH)
    val trx = storage.getString(CustomNodeKeys.TRX)
    val ton = storage.getString(CustomNodeKeys.TON)
    return copy(
        btcMempoolApiBase = btc ?: btcMempoolApiBase,
        ethRpcUrl = eth ?: ethRpcUrl,
        tronApiBase = trx ?: tronApiBase,
        tonApiBase = ton ?: tonApiBase
    )
}

data class NetworkConfig(
    val ethRpcUrl: String,
    val ethChainId: Long,
    val ethGasLimit: Long,
    val ethErc20GasLimit: Long,
    val ethEtherscanApiBase: String,
    val ethEtherscanApiKey: String,
    val tronApiBase: String,
    val trc20FeeLimit: Long,
    val tonApiBase: String,
    val tonNanotonsPerTon: Long,
    val btcMempoolApiBase: String,
    val btcBip84CoinType: Long,
    val btcGenesisBlockHash: BlockHash,
    val erc20Tokens: Map<String, TokenInfo>,
    val trc20Tokens: Map<String, TokenInfo>,
    val exchangeRateApiBase: String,
) {
    companion object {
        // EIP-1559: 25% safety margin on baseFee to account for next-block fluctuations
        const val ETH_BASE_FEE_MARGIN_NUMERATOR = 125
        const val ETH_BASE_FEE_MARGIN_DENOMINATOR = 100
        // Gas buffer multiplier for eth_estimateGas results (20%)
        const val ETH_GAS_BUFFER_NUMERATOR = 120
        const val ETH_GAS_BUFFER_DENOMINATOR = 100

        fun testnet(etherscanApiKey: String): NetworkConfig = NetworkConfig(
            ethRpcUrl = "https://ethereum-sepolia-rpc.publicnode.com",
            ethChainId = 11155111L,
            ethGasLimit = 21000L,
            ethErc20GasLimit = 60000L,
            ethEtherscanApiBase = "https://api.etherscan.io/v2/api",
            ethEtherscanApiKey = etherscanApiKey,
            tronApiBase = "https://nile.trongrid.io",
            trc20FeeLimit = 30_000_000L,
            tonApiBase = "http://10.0.2.2:8081",
            tonNanotonsPerTon = 1_000_000_000L,
            btcMempoolApiBase = "https://mempool.space/signet/api",
            btcBip84CoinType = 1L,
            btcGenesisBlockHash = Block.SignetGenesisBlock.hash,
            erc20Tokens = TestnetTokens.erc20Sepolia,
            trc20Tokens = TestnetTokens.trc20Nile,
            exchangeRateApiBase = "https://backend.coinsafebox.duckdns.org",
        )

        fun mainnet(etherscanApiKey: String): NetworkConfig = NetworkConfig(
            ethRpcUrl = "https://ethereum-rpc.publicnode.com",
            ethChainId = 1L,
            ethGasLimit = 21000L,
            ethErc20GasLimit = 60000L,
            ethEtherscanApiBase = "https://api.etherscan.io/v2/api",
            ethEtherscanApiKey = etherscanApiKey,
            tronApiBase = "https://api.trongrid.io",
            trc20FeeLimit = 30_000_000L,
            tonApiBase = "https://toncenter.com/api/v2",
            tonNanotonsPerTon = 1_000_000_000L,
            btcMempoolApiBase = "https://mempool.space/api",
            btcBip84CoinType = 0L,
            btcGenesisBlockHash = Block.LivenetGenesisBlock.hash,
            erc20Tokens = MainnetTokens.erc20,
            trc20Tokens = MainnetTokens.trc20,
            exchangeRateApiBase = "https://backend.coinsafebox.duckdns.org",
        )
    }
}
