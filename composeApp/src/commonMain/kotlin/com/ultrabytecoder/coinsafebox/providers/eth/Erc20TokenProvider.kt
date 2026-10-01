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
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class Erc20TokenProvider(
    masterKey: DeterministicWallet.ExtendedPrivateKey?,
    private val accountRepository: AccountRepository,
    val params: JsonObject,
    networkConfig: NetworkConfig,
    private val createClient: () -> HttpClient = { HttpClient() },
    private val transactionRepository: TransactionRepository
) : EthBase(masterKey, networkConfig), Provider {

    override val isReadOnly: Boolean get() = masterKey == null

    private val contractAddress: String = params["tokenAddress"]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("Missing tokenAddress in params")

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

    override suspend fun sync(accountId: String, syncMode: SyncMode) {
        val rawBalance = balance(accountId)
        val normalized = rawBalance.toPlainString()
        accountRepository.updateAmount(accountId, normalized)

        val address = getAddress(accountId)
        val transactions = fetchTransactions(address, accountId, syncMode)
        transactionRepository.upsertAll(transactions)
    }

    override suspend fun balance(accountId: String): BigDecimal {
        val address = getAddress(accountId)
        val paddedAddress = address.removePrefix("0x").lowercase().padStart(64, '0')
        val data = "0x70a08231$paddedAddress"

        val client = createClient()
        try {
            val rawBalanceHex = ethCall(client, contractAddress, data)
                .removePrefix("0x").removePrefix("0X").ifEmpty { "0" }
            val decimals = fetchDecimalsWithClient(client)
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
                "&module=account&action=tokentx" +
                "&address=$address" +
                "&contractaddress=$contractAddress" +
                "&startblock=$startBlock" +
                "&sort=desc" +
                "&apikey=${networkConfig.ethEtherscanApiKey}"

            val response: HttpResponse = client.get(url)
            val body = response.body<String>()
            val json = Json.parseToJsonElement(body).jsonObject

            val status = json["status"]?.jsonPrimitive?.content
            if (status != "1") return emptyList()

            val resultArray = json["result"]?.jsonArray ?: return emptyList()
            val transactions = resultArray.mapNotNull { parseErc20Transaction(it.jsonObject, address, accountId) }

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

    private fun parseErc20Transaction(
        tx: JsonObject,
        myAddress: String,
        accountId: String
    ): TransactionInfo? {
        val hash = tx["hash"]?.jsonPrimitive?.content ?: return null
        val from = tx["from"]?.jsonPrimitive?.content ?: return null
        val to = tx["to"]?.jsonPrimitive?.content ?: return null
        val value = tx["value"]?.jsonPrimitive?.content ?: "0"  // raw token smallest unit, DO NOT normalize
        val tokenDecimal = tx["tokenDecimal"]?.jsonPrimitive?.content ?: "18"
        val tokenSymbol = tx["tokenSymbol"]?.jsonPrimitive?.content ?: "UNKNOWN"
        val tokenName = tx["tokenName"]?.jsonPrimitive?.content ?: "Unknown"
        val txContractAddress = tx["contractAddress"]?.jsonPrimitive?.content ?: return null

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
            amount = value,  // raw value in token smallest unit, DO NOT normalize
            fee = fee,
            timestamp = timeStamp * 1000,  // Etherscan returns seconds, store as ms
            status = txStatus,
            counterpartyAddress = counterparty,
            blockHeight = blockNumber,
            chainData = """{"tokenSymbol":"$tokenSymbol","tokenName":"$tokenName","tokenDecimal":$tokenDecimal,"contractAddress":"$txContractAddress"}"""
        )
    }

    override suspend fun createTransaction(
        address: String,
        amount: BigDecimal,
        accountId: String,
        feeParams: CustomFeeParams?
    ): String {
        if (masterKey == null) throw ReadOnlyException()
        val decimals = fetchDecimals()
        val rawAmount = amount.multiply(BigDecimal.fromLong(10).pow(decimals)).toBigInteger()
        val addressBytes = ByteArray(32).also {
            val addr = address.removePrefix("0x").chunked(2).map { h -> h.toInt(16).toByte() }.toByteArray()
            addr.copyInto(it, 12)
        }
        val amountBytes = ByteArray(32).also {
            val raw = rawAmount.toByteArray()
            raw.copyInto(it, 32 - raw.size)
        }
        val data = byteArrayOf(0xa9.toByte(), 0x05.toByte(), 0x9c.toByte(), 0xbb.toByte()) + addressBytes + amountBytes
        val dataHex = "0x" + data.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        val account = accountRepository.getAccount(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")
        val fromKey = deriveEthKeyFromPath(account.derivationPath)
        val fromAddress = ethAddressFromPublicKey(fromKey)

        val client = createClient()
        try {
            val nonce = ethGetTransactionCount(client, fromAddress)

            val (gasTipCap, gasFeeCap) = when (feeParams) {
                is CustomFeeParams.Eth -> {
                    feeParams.maxPriorityFeePerGasMilliGwei * 1_000_000L to feeParams.maxFeePerGasMilliGwei * 1_000_000L
                }
                else -> {
                    val computed = computeFeeParams(client)
                    computed.tipCap to computed.feeCap
                }
            }

            val gasLimit = when (feeParams) {
                is CustomFeeParams.Eth -> feeParams.gasLimit ?: run {
                    val estimatedGas = ethEstimateGas(client, fromAddress, contractAddress, dataHex, gasFeeCap, gasTipCap)
                    if (estimatedGas > 0) estimatedGas * NetworkConfig.ETH_GAS_BUFFER_NUMERATOR / NetworkConfig.ETH_GAS_BUFFER_DENOMINATOR else networkConfig.ethErc20GasLimit
                }
                else -> {
                    val estimatedGas = ethEstimateGas(client, fromAddress, contractAddress, dataHex, gasFeeCap, gasTipCap)
                    if (estimatedGas > 0) estimatedGas * NetworkConfig.ETH_GAS_BUFFER_NUMERATOR / NetworkConfig.ETH_GAS_BUFFER_DENOMINATOR else networkConfig.ethErc20GasLimit
                }
            }

            return signEip1559Transaction(
                nonce, gasTipCap, gasFeeCap, gasLimit, contractAddress, 0, data, fromKey
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
        val toAddress = recipientAddress ?: fromAddress

        // Build ERC-20 transfer calldata for accurate gas estimation
        val decimals = fetchDecimals()
        val rawAmount = amount.multiply(BigDecimal.fromLong(10).pow(decimals)).toBigInteger()
        val addressBytes = ByteArray(32).also {
            val addr = toAddress.removePrefix("0x").chunked(2).map { h -> h.toInt(16).toByte() }.toByteArray()
            addr.copyInto(it, 12)
        }
        val amountBytes = ByteArray(32).also {
            val raw = rawAmount.toByteArray()
            raw.copyInto(it, 32 - raw.size)
        }
        val data = byteArrayOf(0xa9.toByte(), 0x05.toByte(), 0x9c.toByte(), 0xbb.toByte()) + addressBytes + amountBytes
        val dataHex = "0x" + data.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        val client = createClient()
        try {
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

            val estimatedGas = ethEstimateGas(client, fromAddress, contractAddress, dataHex, feeCap, tipCap)
            val gasLimit = when (feeParams) {
                is CustomFeeParams.Eth -> feeParams.gasLimit ?: run {
                    if (estimatedGas > 0) estimatedGas * NetworkConfig.ETH_GAS_BUFFER_NUMERATOR / NetworkConfig.ETH_GAS_BUFFER_DENOMINATOR else networkConfig.ethErc20GasLimit
                }
                else -> if (estimatedGas > 0) estimatedGas * NetworkConfig.ETH_GAS_BUFFER_NUMERATOR / NetworkConfig.ETH_GAS_BUFFER_DENOMINATOR else networkConfig.ethErc20GasLimit
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

    private suspend fun fetchDecimals(): Int {
        val client = createClient()
        try {
            return fetchDecimalsWithClient(client)
        } finally {
            client.close()
        }
    }

    private suspend fun fetchDecimalsWithClient(client: HttpClient): Int {
        val result = ethCall(client, contractAddress, "0x313ce567")
        return result.removePrefix("0x").toLong(16).toInt()
    }
}
