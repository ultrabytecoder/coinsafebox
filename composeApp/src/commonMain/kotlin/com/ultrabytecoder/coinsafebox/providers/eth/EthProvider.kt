package com.ultrabytecoder.coinsafebox.providers

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
import fr.acinq.bitcoin.DeterministicWallet
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class EthProvider(
    masterKey: DeterministicWallet.ExtendedPrivateKey?,
    private val accountRepository: AccountRepository,
    val params: JsonObject,
    networkConfig: NetworkConfig,
    private val createClient: () -> HttpClient = { HttpClient() },
    private val transactionRepository: TransactionRepository
) : EthBase(masterKey, networkConfig), Provider {

    override val isReadOnly: Boolean get() = masterKey == null

    override suspend fun getAddress(accountId: String): String {
        val account = accountRepository.getAccount(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")
        if (masterKey == null) {
            return account.address
                ?: throw IllegalStateException("No address persisted for read-only account ${account.id}")
        }
        val key = deriveEthKeyFromPath(account.derivationPath)
        return ethAddressFromPublicKey(key)
    }

    override suspend fun syncBalance(accountId: String, syncMode: SyncMode) {
        val rawBalance = balance(accountId)
        val normalized = rawBalance.divide(BigDecimal.fromLong(1_000_000_000_000_000_000)).toPlainString()
        accountRepository.updateAmount(accountId, normalized)
    }

    override suspend fun syncTransactions(accountId: String, syncMode: SyncMode) {
        val address = getAddress(accountId)
        val transactions = fetchTransactions(address, accountId, syncMode)
        transactionRepository.upsertAll(transactions)
    }

    override suspend fun balance(accountId: String): BigDecimal {
        val address = getAddress(accountId)
        val client = createClient()
        try {
            val response: HttpResponse = client.post(networkConfig.ethRpcUrl) {
                contentType(ContentType.Application.Json)
                setBody("""{"jsonrpc":"2.0","method":"eth_getBalance","params":["$address","latest"],"id":1}""")
            }
            val body = response.body<String>()
            val json = Json.parseToJsonElement(body).jsonObject
            val result = json["result"]
                ?: json["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    ?.let { throw IllegalStateException("ETH RPC error: $it") }
                ?: throw IllegalStateException("ETH RPC returned no result and no error: $body")
            val hexBalance = result.jsonPrimitive.content
                .removePrefix("0x").removePrefix("0X").ifEmpty { "0" }
            // Parse the hex wei value directly into a BigDecimal (arbitrary
            // precision) instead of through Long, which overflows above 2^63 wei.
            // Guard against malformed hex from the RPC: fall back to zero rather
            // than crashing the balance fetch.
            val safeHex = if (hexBalance.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) hexBalance else "0"
            return BigDecimal.fromBigInteger(BigInteger.parseString(safeHex, 16))
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
            val startBlock = when (syncMode) {
                SyncMode.FULL -> 0L
                SyncMode.NORMAL -> params["lastSyncBlock"]?.jsonPrimitive?.longOrNull ?: 0L
            }

            val url = "${networkConfig.ethEtherscanApiBase}" +
                "?chainid=${networkConfig.ethChainId}" +
                "&module=account&action=txlist" +
                "&address=$address" +
                "&startblock=$startBlock" +
                "&sort=desc" +
                "&apikey=${networkConfig.ethEtherscanApiKey}"

            val response: HttpResponse = client.get(url)
            val body = response.body<String>()
            val json = Json.parseToJsonElement(body).jsonObject

            val status = json["status"]?.jsonPrimitive?.content
            if (status != "1") return emptyList()

            val resultArray = json["result"]?.jsonArray ?: return emptyList()
            val transactions = resultArray.mapNotNull { parseEthTransaction(it.jsonObject, address, accountId) }

            // Update lastSyncBlock for incremental sync
            val maxBlock = resultArray.mapNotNull {
                it.jsonObject["blockNumber"]?.jsonPrimitive?.content?.toLongOrNull()
            }.maxOrNull()
            if (maxBlock != null && maxBlock > startBlock) {
                val updatedParams = JsonObject(params.toMutableMap() + ("lastSyncBlock" to JsonPrimitive(maxBlock)))
                accountRepository.updateParams(accountId, updatedParams.toString())
            }

            return transactions
        } finally {
            client.close()
        }
    }

    private fun parseEthTransaction(
        tx: JsonObject,
        myAddress: String,
        accountId: String
    ): TransactionInfo? {
        val hash = tx["hash"]?.jsonPrimitive?.content ?: return null
        val from = tx["from"]?.jsonPrimitive?.content ?: return null
        val to = tx["to"]?.jsonPrimitive?.content ?: return null
        val value = tx["value"]?.jsonPrimitive?.content ?: "0"
        val timeStamp = tx["timeStamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
        val blockNumber = tx["blockNumber"]?.jsonPrimitive?.content?.toLongOrNull()

        val gasUsed = tx["gasUsed"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val gasPrice = tx["gasPrice"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val fee = (gasUsed * gasPrice).toString()

        val direction = when {
            from.equals(myAddress, ignoreCase = true) && to.equals(myAddress, ignoreCase = true) -> TransactionDirection.SELF
            from.equals(myAddress, ignoreCase = true) -> TransactionDirection.OUTGOING
            to.equals(myAddress, ignoreCase = true) -> TransactionDirection.INCOMING
            else -> TransactionDirection.SELF
        }

        val counterparty = if (direction == TransactionDirection.OUTGOING) to else from

        val isError = tx["isError"]?.jsonPrimitive?.content == "1"
        val txStatus = when {
            isError -> TransactionStatus.FAILED
            tx["txreceipt_status"]?.jsonPrimitive?.content == "0" -> TransactionStatus.FAILED
            else -> TransactionStatus.CONFIRMED
        }

        return TransactionInfo(
            id = "${accountId}_$hash",
            accountId = accountId,
            txHash = hash,
            direction = direction,
            amount = value,
            fee = fee,
            timestamp = timeStamp * 1000,  // Etherscan returns seconds, store as ms
            status = txStatus,
            counterpartyAddress = counterparty,
            blockHeight = blockNumber,
            chainData = null
        )
    }

    override suspend fun createTransaction(
        address: String,
        amount: BigDecimal,
        accountId: String,
        feeParams: CustomFeeParams?
    ): String {
        if (masterKey == null) throw ReadOnlyException()
        val weiAmountBD = amount.multiply(BigDecimal.fromLong(1_000_000_000_000_000_000))
        if (weiAmountBD > BigDecimal.fromLong(Long.MAX_VALUE)) {
            throw IllegalArgumentException("Amount exceeds maximum representable wei value")
        }
        val weiAmount = weiAmountBD.longValue(exactRequired = true)
        val account = accountRepository.getAccount(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")
        val fromKey = deriveEthKeyFromPath(account.derivationPath)
        val fromAddress = ethAddressFromPublicKey(fromKey)

            val client = createClient()
        try {
            val nonce = ethGetTransactionCount(client, fromAddress)

            // Validate feeParams type
            if (feeParams != null && feeParams !is CustomFeeParams.Eth) {
                throw IllegalArgumentException("ETH provider received ${feeParams::class.simpleName}")
            }

            val (gasTipCap, gasFeeCap) = when (feeParams) {
                is CustomFeeParams.Eth -> {
                    feeParams.maxPriorityFeePerGasMilliGwei * 1_000_000L to feeParams.maxFeePerGasMilliGwei * 1_000_000L
                }
                else -> {
                    val computed = computeFeeParams(client)
                    computed.tipCap to computed.feeCap
                }
            }

            val gasLimit = (feeParams as? CustomFeeParams.Eth)?.gasLimit ?: run {
                val estimatedGas = ethEstimateGas(client, fromAddress, address, "0x", gasFeeCap, gasTipCap)
                if (estimatedGas > 0) estimatedGas * NetworkConfig.ETH_GAS_BUFFER_NUMERATOR / NetworkConfig.ETH_GAS_BUFFER_DENOMINATOR else networkConfig.ethGasLimit
            }

            return signEip1559Transaction(
                nonce, gasTipCap, gasFeeCap, gasLimit, address, weiAmount, byteArrayOf(), fromKey
            )
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
        val fromAddress = getAddress(accountId)

        // Require real recipient for accurate contract gas estimation (not self-transfer)
        val toAddress = recipientAddress ?: throw IllegalArgumentException("Recipient address is required for accurate ETH fee estimation")

        val client = createClient()
        try {
            // Compute fee parameters if not provided (Auto mode)
            var usedFallbackFees = false
            val (tipCap, feeCap) = when (feeParams) {
                is CustomFeeParams.Eth -> {
                    val priorityFeeWei = feeParams.maxPriorityFeePerGasMilliGwei * 1_000_000L
                    val maxFeeWei = feeParams.maxFeePerGasMilliGwei * 1_000_000L
                    priorityFeeWei to maxFeeWei
                }
                else -> {
                    val computed = computeFeeParams(client)
                    usedFallbackFees = computed.usedFallback
                    computed.tipCap to computed.feeCap
                }
            }

            // Skip ethEstimateGas if user provided gasLimit
            val gasLimit = (feeParams as? CustomFeeParams.Eth)?.gasLimit ?: run {
                val estimatedGas = ethEstimateGas(client, fromAddress, toAddress, "0x", feeCap, tipCap)
                if (estimatedGas > 0) estimatedGas * NetworkConfig.ETH_GAS_BUFFER_NUMERATOR / NetworkConfig.ETH_GAS_BUFFER_DENOMINATOR else networkConfig.ethGasLimit
            }

            val feeWei = BigDecimal.fromLong(feeCap).multiply(BigDecimal.fromLong(gasLimit))
            val totalCost = feeWei.divide(BigDecimal.fromLong(1_000_000_000_000_000_000))

            val appliedParams = when (feeParams) {
                is CustomFeeParams.Eth -> CustomFeeParams.Eth(
                    feeParams.maxPriorityFeePerGasMilliGwei,
                    feeParams.maxFeePerGasMilliGwei,
                    feeParams.gasLimit
                )
                else -> {
                    // Auto mode: reconstruct params from the values fetched via computeFeeParams
                    val tipMGwei = tipCap.weiToMilliGwei().coerceAtLeast(1L)
                    val capMGwei = feeCap.weiToMilliGwei().coerceAtLeast(tipMGwei + 1L)
                    CustomFeeParams.Eth(tipMGwei, capMGwei)
                }
            }

            return FeeEstimation(totalCost, appliedParams, usedFallbackFees)
        } finally {
            client.close()
        }
    }

    override suspend fun broadcast(rawTransaction: String): String {
        val client = createClient()
        try {
            return ethSendRawTransaction(client, rawTransaction)
        } finally {
            client.close()
        }
    }

    override suspend fun send(address: String, amount: BigDecimal, accountId: String): String {
        val rawTxHex = createTransaction(address, amount, accountId, null)
        return broadcast(rawTxHex)
    }

    override suspend fun feePresets(accountId: String): FeePresets {
        val client = createClient()
        try {
            val computed = computeFeeParams(client)
            return computeFeePresets(computed.tipCap, computed.feeCap)
        } catch (_: Exception) {
            return FeePresets(
                auto = CustomFeeParams.Eth(25_000L, 35_000L),
                slow = CustomFeeParams.Eth(15_000L, 20_000L),
                medium = CustomFeeParams.Eth(25_000L, 35_000L),
                fast = CustomFeeParams.Eth(40_000L, 60_000L)
            )
        } finally {
            client.close()
        }
    }
}
