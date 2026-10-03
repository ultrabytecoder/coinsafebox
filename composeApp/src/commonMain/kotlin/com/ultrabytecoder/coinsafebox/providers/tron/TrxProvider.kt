package com.ultrabytecoder.coinsafebox.providers.tron

import com.ionspin.kotlin.bignum.decimal.BigDecimal
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
import com.ultrabytecoder.coinsafebox.providers.base58ToHexAddress
import com.ultrabytecoder.coinsafebox.providers.hexToBase58Address
import com.ultrabytecoder.coinsafebox.providers.sunToTrx
import com.ultrabytecoder.coinsafebox.providers.trxToSun
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.secp256k1.Hex
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class TrxProvider(
    masterKey: DeterministicWallet.ExtendedPrivateKey?,
    private val accountRepository: AccountRepository,
    val params: JsonObject,
    networkConfig: NetworkConfig,
    private val transactionRepository: TransactionRepository,
    private val createClient: () -> HttpClient = { HttpClient() }
) : TrxBase(masterKey, networkConfig), Provider {

    override val isReadOnly: Boolean get() = masterKey == null

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
        val rawBalance = balance(accountId)
        val normalized = sunToTrx(rawBalance).toPlainString()
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
            val response: HttpResponse = client.post("${networkConfig.tronApiBase}/wallet/getaccount") {
                contentType(ContentType.Application.Json)
                setBody("""{"address":"$address","visible":true}""")
            }
            val body = response.body<String>()
            val json = Json.parseToJsonElement(body).jsonObject
            val balanceSunStr = json["balance"]?.jsonPrimitive?.content ?: "0"
            // Parse the decimal sun value directly into a BigDecimal instead of
            // through Long, which would overflow above 2^63 sun.
            return try {
                BigDecimal.parseString(balanceSunStr)
            } catch (_: Exception) {
                println("TrxProvider.balance: malformed balance '$balanceSunStr' for $address, defaulting to 0")
                BigDecimal.ZERO
            }
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
        val address = getAddress(accountId)
        val client = createClient()
        try {
            val requestBody = buildJsonObject {
                put("address", address)
                put("visible", true)
            }
            val response: HttpResponse = client.post("${networkConfig.tronApiBase}/wallet/getaccountresource") {
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }
            val body = response.body<String>()
            val json = Json.parseToJsonElement(body).jsonObject

            // Free bandwidth
            val freeNetLimit = json["freeNetLimit"]?.jsonPrimitive?.longOrNull ?: 0L
            val freeNetUsed = json["freeNetUsed"]?.jsonPrimitive?.longOrNull ?: 0L
            // Staked bandwidth
            val netLimit = json["NetLimit"]?.jsonPrimitive?.longOrNull ?: 0L
            val netUsed = json["NetUsed"]?.jsonPrimitive?.longOrNull ?: 0L

            val totalBandwidthRemaining = (freeNetLimit - freeNetUsed) + (netLimit - netUsed)

            // TRX transfer transaction size is ~268 bytes
            val bandwidthNeeded = 268L
            val feeIsZero = totalBandwidthRemaining >= bandwidthNeeded
            val appliedParams: CustomFeeParams? = null

            val totalCost: BigDecimal = if (feeIsZero) {
                BigDecimal.ZERO
            } else {
                val shortfall = bandwidthNeeded - totalBandwidthRemaining
                // 1 bandwidth unit costs 1000 SUN (10^-3 TRX)
                val SUN_PER_BANDWIDTH = 1_000L
                val feeSun = shortfall * SUN_PER_BANDWIDTH
                sunToTrx(BigDecimal.fromLong(feeSun))
            }

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
        val sunAmount = trxToSun(amount)
        val account = accountRepository.getAccount(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")
        val fromKey = deriveTrxKeyFromPath(account.derivationPath)
        val fromAddress = getAddress(accountId)

        // Native TRX transfers have no fee ladder: the fee is fixed (0 TRX with
        // available bandwidth, otherwise a deterministic burn). fee_limit is a
        // smart-contract energy cap that the node silently ignores for plain transfers.
        if (feeParams != null) {
            throw IllegalArgumentException(
                "Native TRX transfers do not support custom fee parameters; " +
                    "fee is determined by bandwidth availability. Received: ${feeParams::class.simpleName}"
            )
        }

        val client = createClient()
        try {
            val requestBody = """{"to_address":"$address","owner_address":"$fromAddress","amount":$sunAmount,"visible":true}"""

            val createResponse: HttpResponse = client.post("${networkConfig.tronApiBase}/wallet/createtransaction") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            val createBody = createResponse.body<String>()
            val createJson = Json.parseToJsonElement(createBody).jsonObject

            check(!createJson.containsKey("Error")) { "Error creating transaction: ${createJson["Error"]}" }

            val txidHex = createJson["txID"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("TRON RPC missing txID in create response")
            val rawDataObj = createJson["raw_data"]?.jsonObject
                ?: throw IllegalStateException("TRON RPC missing raw_data in create response")
            val rawDataHex = createJson["raw_data_hex"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("TRON RPC missing raw_data_hex in create response")
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

    override suspend fun feePresets(accountId: String): FeePresets? = null

    private suspend fun fetchTransactions(
        address: String,
        accountId: String,
        syncMode: SyncMode
    ): List<TransactionInfo> {
        val client = createClient()
        try {
            val limit = if (syncMode == SyncMode.FULL) 200 else 50
            val url = "${networkConfig.tronApiBase}/v1/accounts/$address/transactions?limit=$limit&order_by=block_timestamp,desc"
            val response: HttpResponse = client.get(url)
            val body = response.body<String>()
            val json = Json.parseToJsonElement(body).jsonObject
            val dataArray = json["data"]?.jsonArray ?: return emptyList()

            return dataArray.mapNotNull { element ->
                parseTrxTransaction(element.jsonObject, address, accountId)
            }
        } finally {
            client.close()
        }
    }

    private fun parseTrxTransaction(
        tx: JsonObject,
        myAddress: String,
        accountId: String
    ): TransactionInfo? {
        val txHash = tx["txID"]?.jsonPrimitive?.content ?: return null
        val rawData = tx["raw_data"]?.jsonObject ?: return null
        val contract = rawData["contract"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val value = contract["parameter"]?.jsonObject?.get("value")?.jsonObject ?: return null

        val amount = value["amount"]?.jsonPrimitive?.content ?: "0"
        val ownerHex = value["owner_address"]?.jsonPrimitive?.content ?: return null
        val toHex = value["to_address"]?.jsonPrimitive?.content ?: return null

        val timestamp = rawData["timestamp"]?.jsonPrimitive?.longOrNull
            ?: tx["block_timestamp"]?.jsonPrimitive?.longOrNull
            ?: return null

        val blockHeight = rawData["block_number"]?.jsonPrimitive?.longOrNull
        val retEntry = tx["ret"]?.jsonArray?.firstOrNull()?.jsonObject
        val contractRet = retEntry
            ?.get("contractRet")?.jsonPrimitive?.content

        val fee = retEntry?.get("fee")?.jsonPrimitive?.longOrNull?.toString()

        val status = when (contractRet) {
            "SUCCESS" -> TransactionStatus.CONFIRMED
            "REVERT" -> TransactionStatus.FAILED
            else -> TransactionStatus.PENDING
        }

        val myHex = base58ToHexAddress(myAddress)
        val direction = when {
            ownerHex.equals(myHex, ignoreCase = true) && toHex.equals(myHex, ignoreCase = true) -> TransactionDirection.SELF
            ownerHex.equals(myHex, ignoreCase = true) -> TransactionDirection.OUTGOING
            toHex.equals(myHex, ignoreCase = true) -> TransactionDirection.INCOMING
            else -> TransactionDirection.SELF
        }

        val counterparty = if (direction == TransactionDirection.OUTGOING) {
            hexToBase58Address(toHex)
        } else {
            hexToBase58Address(ownerHex)
        }

        return TransactionInfo(
            id = "${accountId}_${txHash}",
            accountId = accountId,
            txHash = txHash,
            direction = direction,
            amount = amount,
            fee = fee,
            timestamp = timestamp,
            status = status,
            counterpartyAddress = counterparty,
            blockHeight = blockHeight,
            chainData = null
        )
    }
}
