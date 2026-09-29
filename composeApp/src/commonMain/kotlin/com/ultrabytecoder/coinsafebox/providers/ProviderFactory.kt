package com.ultrabytecoder.coinsafebox.providers

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository
import com.ultrabytecoder.coinsafebox.providers.ton.TonProvider
import com.ultrabytecoder.coinsafebox.providers.tron.Trc20TokenProvider
import com.ultrabytecoder.coinsafebox.providers.tron.TrxProvider
import fr.acinq.bitcoin.DeterministicWallet
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

object ProviderFactory {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun create(
        type: AccountType,
        masterSeed: ByteArray,
        utxoRepository: UtxoRepository,
        accountRepository: AccountRepository,
        transactionRepository: TransactionRepository,
        networkConfig: NetworkConfig,
        params: String? = null
    ): Provider {
        val parsedParams = params?.let { json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        val masterKey = DeterministicWallet.generate(masterSeed)
        return when (type) {
            is AccountType.Btc -> BtcProvider(
                masterKey, null, utxoRepository, accountRepository, parsedParams, networkConfig,
                transactionRepository = transactionRepository,
                createClient = { HttpClient() }
            )
            is AccountType.Eth -> EthProvider(
                masterKey, accountRepository, parsedParams, networkConfig,
                transactionRepository = transactionRepository
            )
            is AccountType.Trx -> TrxProvider(masterKey, accountRepository, parsedParams, networkConfig, transactionRepository = transactionRepository)
            is AccountType.Ton -> TonProvider(masterSeed, accountRepository, parsedParams, networkConfig, transactionRepository = transactionRepository)
            is AccountType.Erc20 -> Erc20TokenProvider(
                masterKey, accountRepository, parsedParams, networkConfig,
                transactionRepository = transactionRepository
            )
            is AccountType.Trc20 -> Trc20TokenProvider(
                masterKey,
                accountRepository,
                parsedParams,
                networkConfig,
                transactionRepository = transactionRepository
            )
            // TODO: implement TonTokenProvider when Jetton support is added
            is AccountType.TonToken -> TonProvider(masterSeed, accountRepository, parsedParams, networkConfig, transactionRepository = transactionRepository)
        }
    }

    suspend fun createReadOnly(
        type: AccountType,
        xpub: String?,
        utxoRepository: UtxoRepository,
        accountRepository: AccountRepository,
        transactionRepository: TransactionRepository,
        networkConfig: NetworkConfig,
        params: String? = null
    ): Provider {
        val parsedParams = params?.let { json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        return when (type) {
            is AccountType.Btc -> {
                val accountXpub = xpub?.let { BtcXpub.decode(it) }
                    ?: throw IllegalArgumentException("BTC read-only provider requires an xpub")
                BtcProvider(
                    null, accountXpub, utxoRepository, accountRepository, parsedParams, networkConfig,
                    transactionRepository = transactionRepository,
                    createClient = { HttpClient() }
                )
            }
            is AccountType.Eth -> EthProvider(
                null, accountRepository, parsedParams, networkConfig,
                transactionRepository = transactionRepository
            )
            is AccountType.Trx -> TrxProvider(null, accountRepository, parsedParams, networkConfig, transactionRepository = transactionRepository)
            is AccountType.Ton -> TonProvider(null, accountRepository, parsedParams, networkConfig, transactionRepository = transactionRepository)
            is AccountType.Erc20 -> Erc20TokenProvider(
                null, accountRepository, parsedParams, networkConfig,
                transactionRepository = transactionRepository
            )
            is AccountType.Trc20 -> Trc20TokenProvider(
                null,
                accountRepository,
                parsedParams,
                networkConfig,
                transactionRepository = transactionRepository
            )
            // TODO: implement TonTokenProvider when Jetton support is added
            is AccountType.TonToken -> TonProvider(null, accountRepository, parsedParams, networkConfig, transactionRepository = transactionRepository)
        }
    }
}
