package com.ultrabytecoder.coinsafebox.providers.tron

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.CustomFeeParams
import com.ultrabytecoder.coinsafebox.domain.model.FeeEstimation
import com.ultrabytecoder.coinsafebox.domain.model.FeePresets
import com.ultrabytecoder.coinsafebox.domain.model.TransactionDirection
import com.ultrabytecoder.coinsafebox.domain.model.TransactionInfo
import com.ultrabytecoder.coinsafebox.domain.model.TransactionStatus
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.providers.Provider
import com.ultrabytecoder.coinsafebox.providers.ReadOnlyException
import com.ultrabytecoder.coinsafebox.providers.SyncMode
import com.ultrabytecoder.coinsafebox.providers.TrxBase
import com.ultrabytecoder.coinsafebox.providers.sunToTrx
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.secp256k1.Hex
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class Trc20TokenProvider(
    masterKey: DeterministicWallet.ExtendedPrivateKey?,
    private val accountRepository: AccountRepository,
    val params: JsonObject,
    networkConfig: NetworkConfig,
    private val transactionRepository: TransactionRepository,
    private val createClient: () -> HttpClient = { HttpClient() }
) : TrxBase(masterKey, networkConfig), Provider {

    override val isReadOnly: Boolean get() = masterKey == null

    private val contractAddress: String = params["tokenAddress"]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("Missing tokenAddress in params")

    private var energyCacheAccountId: String? = null
    private var energyCacheValue: Long? = null
    private var energyCacheTime: Long = 0L
    private var decimalsCache: Int? = null

    private companion object {
        const val ENERGY_CACHE_TTL_MS = 5 * 60 * 1000L
        const val MIN_FEE_LIMIT_SUN = 1_000_000L
        const val MAX_FEE_LIMIT_SUN = 100_000_000L
        const val DEFAULT_ENERGY_ESTIMATE = 20_000_000L
    }

    override suspend fun getAddress(accountId: String): String {
        val account = accountRepository.getAccount(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")
        if (masterKey == null) {
            return account.address
                ?: throw IllegalStateException("No address persisted for read-only account ${account.id}")
        }
        val key = deriveTrxKeyFromPath(account.derivationPath)
        return trxAddressFromDerivedKey(key)
    }

    override suspend fun syncBalance(accountId: String, syncMode: SyncMode) {
        try {
            val rawBalance = balance(accountId)
            val normalized = rawBalance.toPlainString()
            accountRepository.updateAmount(accountId, normalized)
        } catch (_: Exception) {
            // Balance RPC failed; preserve existing amount
        }
    }

    override suspend fun syncTransactions(accountId: String, syncMode: SyncMode) {
        val address = getAddress(accountId)
        val transactions = fetchTransactions(address, accountId, syncMode)
        transactionRepository.upsertAll(transactions)
    }

    override suspend fun balance(accountId: String): BigDecimal {
        val address = getAddress(accountId)
        val addressHex = encodeTronAddressParameter(address)

        val client = createClient()
        try {
            val resultJson = tronTriggerSmartContract(
                client, contractAddress,
                "balanceOf(address)", addressHex, address
            )
            val rawBalanceHex = resultJson["constant_result"]?.jsonArray?.getOrNull(0)?.jsonPrimitive?.content
                ?.removePrefix("0x")?.removePrefix("0X")?.ifEmpty { "0" }
                ?: throw IllegalStateException("TRON RPC missing constant_result in balanceOf response")
            val decimals = fetchDecimalsWithClient(client, accountId)
            // Parse the hex token units directly into a BigDecimal (arbitrary
            // precision) instead of through Long, which overflows for large
            // 18-decimal token balances. Guard against malformed hex from the
            // RPC: fall back to zero rather than crashing the balance fetch.
            val safeHex = if (rawBalanceHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) rawBalanceHex else "0"
            return BigDecimal.fromBigInteger(BigInteger.parseString(safeHex, 16))
                .divide(BigDecimal.fromLong(10).pow(decimals))
        } finally {
            client.close()
        }
    }

    override suspend fun estimateFee(
        accountId: String,
        amount: BigDecimal,
        recipientAddress: String?,
        feeParams: CustomFeeParams?
    ): FeeEstimation {
        val feeLimit = when (feeParams) {
            is CustomFeeParams.Tron -> feeParams.feeLimitSun
            else -> networkConfig.trc20FeeLimit
        }
        val decimals = fetchDecimals(accountId)
        val rawAmount = amount.multiply(BigDecimal.Companion.fromLong(10).pow(decimals)).longValue()
        // Use a zeroed recipient address for the calldata — energy cost is the same for any recipient
        val zeroAddress = ByteArray(32)
        val amountHex = rawAmount.toString(16).padStart(64, '0')
        val parameter = zeroAddress.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') } + amountHex

        val fromAddress = getAddress(accountId)
        val client = createClient()
        try {
            val triggerJson = tronTriggerSmartContract(
                client, contractAddress,
                "transfer(address,uint256)", parameter, fromAddress,
                feeLimit = feeLimit
            )

            // Extract actual energy used from the simulation
            val energyUsed = triggerJson["energy_used"]?.jsonPrimitive?.longOrNull
            val totalCost: BigDecimal = if (energyUsed != null && energyUsed > 0) {
                // energy_used is in energy units; 1 energy = 1 SUN of TRX fee
                sunToTrx(BigDecimal.fromLong(energyUsed))
            } else {
                // Fallback to a reasonable estimate if simulation fails
                BigDecimal.fromLong(20_000_000).divide(BigDecimal.fromLong(1_000_000))
            }

            // Always report the actual fee limit used — in Auto mode this is the
            // network-config default, so the UI shows the live/effective value
            // instead of a stale custom one.
            val appliedParams = CustomFeeParams.Tron(feeLimit)

            return FeeEstimation(totalCost, appliedParams)
        } finally {
            client.close()
        }
    }

    override suspend fun createTransaction(
        address: String,
        amount: BigDecimal,
        accountId: String,
        feeParams: CustomFeeParams?
    ): String {
        if (masterKey == null) throw ReadOnlyException()
        val feeLimit = when (feeParams) {
            is CustomFeeParams.Tron -> feeParams.feeLimitSun
            else -> networkConfig.trc20FeeLimit
        }
        val decimals = fetchDecimals(accountId)
        val rawAmount = amount.multiply(BigDecimal.Companion.fromLong(10).pow(decimals)).longValue()
        val addressHex = encodeTronAddressParameter(address)
        val amountHex = rawAmount.toString(16).padStart(64, '0')
        val parameter = addressHex + amountHex

        val account = accountRepository.getAccount(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")
        val fromKey = deriveTrxKeyFromPath(account.derivationPath)
        val fromAddress = getAddress(accountId)

        val client = createClient()
        try {
            val triggerJson = tronTriggerSmartContract(
                client, contractAddress,
                "transfer(address,uint256)", parameter, fromAddress,
                feeLimit = feeLimit
            )

            check(!triggerJson.containsKey("Error")) { "Error triggering smart contract: ${triggerJson["Error"]}" }

            val transaction = triggerJson["transaction"]?.jsonObject
                ?: throw IllegalStateException("TRON RPC missing transaction in trigger response")
            val txidHex = transaction["txID"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("TRON RPC missing txID in trigger response")
            val rawDataObj = transaction["raw_data"]?.jsonObject
                ?: throw IllegalStateException("TRON RPC missing raw_data in trigger response")
            val rawDataHex = transaction["raw_data_hex"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("TRON RPC missing raw_data_hex in trigger response")
            val signatureHex = signTronTransaction(Hex.decode(txidHex), fromKey)

            val responseJson = buildJsonObject {
                put("txid", txidHex)
                put("raw_data", rawDataObj as JsonElement)
                put("raw_data_hex", rawDataHex)
                putJsonArray("signature") { add(JsonPrimitive(signatureHex)) }
                put("visible", true)
            }
            return responseJson.toString()
        } finally {
            client.close()
        }
    }

    override suspend fun broadcast(rawTransaction: String): String {
        val client = createClient()
        try {
            return broadcastSignedTransaction(client, rawTransaction)
        } finally {
            client.close()
        }
    }

    override suspend fun send(address: String, amount: BigDecimal, accountId: String): String {
        val broadcastBody = createTransaction(address, amount, accountId, null)
        return broadcast(broadcastBody)
    }

    override suspend fun feePresets(accountId: String): FeePresets {
        val estimatedEnergy = estimateEnergyForTransfer(accountId)
        // fee_limit is a cap on energy burn, not a speed bid — presets are
        // safety margins over the simulated energy_used.
        val slow = (estimatedEnergy * 110 / 100).coerceIn(MIN_FEE_LIMIT_SUN, MAX_FEE_LIMIT_SUN)
        val medium = (estimatedEnergy * 130 / 100).coerceIn(MIN_FEE_LIMIT_SUN, MAX_FEE_LIMIT_SUN)
        val fast = (estimatedEnergy * 150 / 100).coerceIn(MIN_FEE_LIMIT_SUN, MAX_FEE_LIMIT_SUN)
        val auto = networkConfig.trc20FeeLimit // the value estimateFee() uses in Auto mode
        return FeePresets(
            auto = CustomFeeParams.Tron(auto),
            slow = CustomFeeParams.Tron(slow),
            medium = CustomFeeParams.Tron(medium),
            fast = CustomFeeParams.Tron(fast)
        )
    }

    private suspend fun estimateEnergyForTransfer(accountId: String): Long {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        if (energyCacheAccountId == accountId && energyCacheValue != null && now - energyCacheTime < ENERGY_CACHE_TTL_MS) {
            return energyCacheValue!!
        }

        val decimals = fetchDecimals(accountId)
        val zeroAddress = ByteArray(32)
        val amountHex = "0".padStart(64, '0')
        val parameter = zeroAddress.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') } + amountHex
        val fromAddress = getAddress(accountId)
        val client = createClient()
        try {
            val triggerJson = tronTriggerSmartContract(
                client, contractAddress,
                "transfer(address,uint256)", parameter, fromAddress,
                feeLimit = networkConfig.trc20FeeLimit
            )
            val energyUsed = triggerJson["energy_used"]?.jsonPrimitive?.longOrNull
                ?: DEFAULT_ENERGY_ESTIMATE
            energyCacheAccountId = accountId
            energyCacheValue = energyUsed
            energyCacheTime = now
            return energyUsed
        } finally {
            client.close()
        }
    }

    private suspend fun fetchTransactions(
        address: String,
        accountId: String,
        syncMode: SyncMode
    ): List<TransactionInfo> {
        val client = createClient()
        try {
            val limit = if (syncMode == SyncMode.FULL) 200 else 50
            val url = "${networkConfig.tronApiBase}/v1/accounts/$address/transactions/trc20?limit=$limit&order_by=block_timestamp,desc&contract_address=$contractAddress"
            val response: HttpResponse = client.get(url)
            val body = response.body<String>()
            val json = Json.parseToJsonElement(body).jsonObject
            val dataArray = json["data"]?.jsonArray ?: return emptyList()

            return dataArray.mapNotNull { element ->
                parseTrc20Transaction(element.jsonObject, address, accountId)
            }
        } finally {
            client.close()
        }
    }

    private fun parseTrc20Transaction(
        transfer: JsonObject,
        myAddress: String,
        accountId: String
    ): TransactionInfo? {
        // Filter: only include transfers for our token contract
        val tokenInfo = transfer["token_info"]?.jsonObject
        val tokenAddr = tokenInfo?.get("address")?.jsonPrimitive?.content
        if (tokenAddr != null && !tokenAddr.equals(contractAddress, ignoreCase = true)) {
            return null
        }

        val txHash = transfer["transaction_id"]?.jsonPrimitive?.content ?: return null
        val from = transfer["from"]?.jsonPrimitive?.content ?: return null
        val to = transfer["to"]?.jsonPrimitive?.content ?: return null
        val value = transfer["value"]?.jsonPrimitive?.content ?: "0"
        val timestamp = transfer["block_timestamp"]?.jsonPrimitive?.longOrNull ?: return null

        val result = transfer["finalResult"]?.jsonPrimitive?.content
        val status = when (result) {
            "SUCCESS" -> TransactionStatus.CONFIRMED
            "REVERT" -> TransactionStatus.FAILED
            else -> TransactionStatus.PENDING
        }

        val direction = when {
            from.equals(myAddress, ignoreCase = true) && to.equals(myAddress, ignoreCase = true) -> TransactionDirection.SELF
            from.equals(myAddress, ignoreCase = true) -> TransactionDirection.OUTGOING
            to.equals(myAddress, ignoreCase = true) -> TransactionDirection.INCOMING
            else -> TransactionDirection.SELF
        }

        val counterparty = if (direction == TransactionDirection.OUTGOING) to else from

        val chainData = tokenInfo?.let { info ->
            val symbol = info["symbol"]?.jsonPrimitive?.content ?: ""
            val decimals = info["decimals"]?.jsonPrimitive?.intOrNull ?: 0
            val tokenAddress = info["address"]?.jsonPrimitive?.content ?: ""
            """{"symbol":"$symbol","decimals":$decimals,"tokenAddress":"$tokenAddress"}"""
        }

        return TransactionInfo(
            id = "${accountId}_${txHash}",
            accountId = accountId,
            txHash = txHash,
            direction = direction,
            amount = value,
            // TRC20 fee is null: TronGrid /transactions/trc20 endpoint does not return fee data.
            // The TRC20 transfer fee is a TRX network fee visible only on the companion TRX transaction,
            // which would require a separate API lookup by transaction_id to retrieve.
            // This is a known limitation -- fee data for TRC20 transfers requires TRX transaction cross-referencing.
            fee = null,
            timestamp = timestamp,
            status = status,
            counterpartyAddress = counterparty,
            blockHeight = null,
            chainData = chainData
        )
    }

    private suspend fun fetchDecimals(accountId: String): Int {
        val client = createClient()
        try {
            return fetchDecimalsWithClient(client, accountId)
        } finally {
            client.close()
        }
    }

    private suspend fun fetchDecimalsWithClient(client: HttpClient, accountId: String): Int {
        decimalsCache?.let { return it }

        val ownerAddress = getAddress(accountId)
        val resultJson = tronTriggerSmartContract(
            client, contractAddress,
            "decimals()", "", ownerAddress
        )

        val decimals = resultJson["constant_result"]?.jsonArray?.getOrNull(0)?.jsonPrimitive?.content
            ?.let { it.toLongOrNull(16)?.toInt() }
            ?: decodeDecimalsFromErrorMessage(resultJson)
            ?: fetchDecimalsFromTransactionsWithClient(client, ownerAddress)
            ?: 6

        decimalsCache = decimals
        return decimals
    }

    private fun decodeDecimalsFromErrorMessage(resultJson: JsonObject): Int? {
        val message = (resultJson["result"] as? JsonObject)?.get("message")?.jsonPrimitive?.content
            ?: resultJson["message"]?.jsonPrimitive?.content
            ?: return null
        return try {
            Hex.decode(message).decodeToString().trim().toIntOrNull()
        } catch (_: Exception) {
            message.trim().toIntOrNull()
        }
    }

    private suspend fun fetchDecimalsFromTransactionsWithClient(client: HttpClient, address: String): Int? {
        val url = "${networkConfig.tronApiBase}/v1/accounts/$address/transactions/trc20?limit=1&contract_address=$contractAddress"
        return try {
            val response: HttpResponse = client.get(url)
            val body = response.body<String>()
            val json = Json.parseToJsonElement(body).jsonObject
            json["data"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("token_info")?.jsonObject
                ?.get("decimals")?.jsonPrimitive?.intOrNull
        } catch (_: Exception) {
            null
        }
    }
}
