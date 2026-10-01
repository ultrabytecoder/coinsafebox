package com.ultrabytecoder.coinsafebox.providers.ton

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
import com.ultrabytecoder.coinsafebox.providers.ton.address.TonAddress
import com.ultrabytecoder.coinsafebox.providers.ton.boc.*
import com.ultrabytecoder.coinsafebox.providers.ton.types.StateInit
import com.ultrabytecoder.coinsafebox.providers.ton.types.internalMessage
import com.ultrabytecoder.coinsafebox.providers.ton.types.storeStateInit
import com.ultrabytecoder.coinsafebox.providers.ton.wallet.WalletContract
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class TonProvider(
    masterSeed: ByteArray?,
    private val accountRepository: AccountRepository,
    val params: JsonObject,
    networkConfig: NetworkConfig,
    private val transactionRepository: TransactionRepository,
    private val createClient: () -> HttpClient = { HttpClient() }
) : TonBase(masterSeed, networkConfig), Provider {

    companion object {
        // Read-only (key-free) fee fallback: a safe OVERESTIMATE. seqno == 0 means the
        // wallet is not yet deployed (carries StateInit); otherwise a deployed wallet.
        private const val RO_FEE_DEPLOY_NANOTONS = 50_000_000L   // 0.05 TON
        private const val RO_FEE_TRANSFER_NANOTONS = 15_000_000L // 0.015 TON
    }

    override val isReadOnly: Boolean get() = masterSeed == null

    override suspend fun getAddress(accountId: String): String {
        val account = accountRepository.getAccount(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")
        if (masterSeed == null) {
            return account.address
                ?: throw IllegalStateException("No address persisted for read-only account ${account.id}")
        }
        val keyPair = deriveTonKeyFromPath(account.derivationPath)
        val version = TonBase.parseWalletVersion(params)
        return tonAddressFromPublicKey(keyPair.publicKey, version)
    }

    override suspend fun sync(accountId: String, syncMode: SyncMode) {
        val rawBalance = balance(accountId)
        accountRepository.updateAmount(accountId, rawBalance.toPlainString())

        val address = getAddress(accountId)
        val transactions = fetchTransactions(address, accountId, syncMode)
        transactionRepository.upsertAll(transactions)
    }

    override suspend fun balance(accountId: String): BigDecimal {
        val account = accountRepository.getAccount(accountId) ?: return BigDecimal.ZERO
        val address = getAddress(accountId)
        val client = createClient()
        try {
            val nanotons = tonGetBalance(client, address)
            return BigDecimal.fromLong(nanotons).divide(BigDecimal.fromLong(networkConfig.tonNanotonsPerTon))
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
        if (masterSeed == null) {
            val roAccount = accountRepository.getAccount(accountId)
                ?: throw IllegalArgumentException("Account not found: $accountId")
            val roAddress = roAccount.address
                ?: throw IllegalStateException("No address persisted for read-only account ${roAccount.id}")
            val roClient = createClient()
            try {
                val roSeqno = tonGetSeqno(roClient, roAddress)
                val roFeeNanotons = if (roSeqno == 0) RO_FEE_DEPLOY_NANOTONS else RO_FEE_TRANSFER_NANOTONS
                val roTotalCost = BigDecimal.fromLong(roFeeNanotons).divide(BigDecimal.fromLong(networkConfig.tonNanotonsPerTon))
                return FeeEstimation(roTotalCost, null)
            } finally {
                roClient.close()
            }
        }
        val nanotons = amount.multiply(BigDecimal.fromLong(networkConfig.tonNanotonsPerTon)).longValue(exactRequired = false)
        val account = accountRepository.getAccount(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")
        val keyPair = deriveTonKeyFromPath(account.derivationPath)
        val version = TonBase.parseWalletVersion(params)
        val wallet: WalletContract = TonBase.walletContractFor(version, 0, keyPair.publicKey)

        val client = createClient()
        try {
            val seqno = tonGetSeqno(client, wallet.address.toString())

            // Use real recipient address if provided, otherwise fall back to self
            val destAddress = recipientAddress?.let { TonAddress.parse(it) }
                ?: TonAddress.parse(getAddress(accountId))
            // Match createTransaction bounce logic for accurate fee estimation
            val bounce = recipientAddress?.let { addr ->
                val destState = tonGetAccountState(client, addr)
                destState == "active" || destState == "frozen"
            } ?: false

            val transferCell = wallet.createTransfer(
                seqno = seqno,
                secretKey = keyPair.privateKeySeed,
                messages = listOf(internalMessage(to = destAddress, value = nanotons, bounce = bounce)),
                sendMode = 1,
                timeout = null
            )

            // Build external message BOC (including StateInit if wallet not yet deployed)
            val bocBase64 = buildExtMessageBoc(wallet, seqno, transferCell)

            // Use TON API /estimateFee to get accurate fee - sum all 4 fee components
            val feeNanotons = try {
                val response: HttpResponse = client.post("${networkConfig.tonApiBase}/estimateFee") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"boc":"$bocBase64","mode":0}""")
                }
                val body = response.body<String>()
                val json = Json.parseToJsonElement(body).jsonObject
                val fees = json["result"]?.jsonObject
                
                // Sum all 4 fee components: in_fwd_fee, storage_fee, gas_fee, fwd_fee
                listOf("in_fwd_fee", "storage_fee", "gas_fee", "fwd_fee")
                    .sumOf { fees?.get(it)?.jsonPrimitive?.longOrNull ?: 0L }
                    .takeIf { it > 0L } ?: 10_000_000L
            } catch (e: Exception) {
                10_000_000L
            }

            val totalCost = BigDecimal.fromLong(feeNanotons).divide(BigDecimal.fromLong(networkConfig.tonNanotonsPerTon))
            return FeeEstimation(totalCost, null)
        } finally {
            client.close()
        }
    }

    /** Extract BOC construction to prevent logic divergence between estimateFee and createTransaction */
    private fun buildExtMessageBoc(
        wallet: WalletContract,
        seqno: Int,
        transferCell: Cell
    ): String {
        val extMsgBuilder = beginCell()
            .storeUint(0b10, 2)
            .storeAddress(null)
            .storeAddress(wallet.address)
            .storeCoins(0)

        if (seqno == 0) {
            val stateInit = StateInit(code = wallet.code, data = wallet.data)
            val stateInitCell = beginCell().storeWritable(storeStateInit(stateInit)).endCell()
            extMsgBuilder.storeBit(true)
            if (extMsgBuilder.availableBits - 1 >= stateInitCell.bits.length && extMsgBuilder.refsCount + stateInitCell.refs.size <= 3) {
                extMsgBuilder.storeBit(false)
                extMsgBuilder.storeSlice(stateInitCell.beginParse())
            } else {
                extMsgBuilder.storeBit(true)
                extMsgBuilder.storeRef(stateInitCell)
            }
        } else {
            extMsgBuilder.storeBit(false)
        }

        extMsgBuilder.storeBit(true).storeRef(transferCell)
        return base64Encode(extMsgBuilder.endCell().toBoc())
    }

    override suspend fun createTransaction(
        address: String,
        amount: BigDecimal,
        accountId: String,
        feeParams: CustomFeeParams?
    ): String {
        if (masterSeed == null) throw ReadOnlyException()
        val nanotons = amount.multiply(BigDecimal.fromLong(networkConfig.tonNanotonsPerTon)).longValue(exactRequired = false)
        val account = accountRepository.getAccount(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")
        val keyPair = deriveTonKeyFromPath(account.derivationPath)
        val version = TonBase.parseWalletVersion(params)
        val wallet: WalletContract = TonBase.walletContractFor(version, 0, keyPair.publicKey)
        val destAddress = TonAddress.parse(address)

        val client = createClient()
        try {
            val seqno = tonGetSeqno(client, wallet.address.toString())
            val destState = tonGetAccountState(client, address)
            val bounce = destState == "active" || destState == "frozen"

            val transferCell = wallet.createTransfer(
                seqno = seqno,
                secretKey = keyPair.privateKeySeed,
                messages = listOf(internalMessage(to = destAddress, value = nanotons, bounce = bounce)),
                sendMode = 1,
                timeout = null
            )

            val bocBase64 = buildExtMessageBoc(wallet, seqno, transferCell)
            return bocBase64
        } finally {
            client.close()
        }
    }

    override suspend fun broadcast(rawTransaction: String): String {
        val client = createClient()
        try {
            return tonSendBoc(client, rawTransaction)
        } finally {
            client.close()
        }
    }

    override suspend fun send(address: String, amount: BigDecimal, accountId: String): String {
        val bocBase64 = createTransaction(address, amount, accountId, null)
        return broadcast(bocBase64)
    }

    override suspend fun feePresets(accountId: String): FeePresets? = null

    private suspend fun fetchTransactions(
        address: String,
        accountId: String,
        syncMode: SyncMode
    ): List<TransactionInfo> {
        val client = createClient()
        try {
            val limit = if (syncMode == SyncMode.FULL) 100 else 20
            val url = "${networkConfig.tonApiBase}/getTransactions?address=$address&limit=$limit"
            val response: HttpResponse = client.get(url)
            val body = response.body<String>()
            val json = Json.parseToJsonElement(body).jsonObject

            if (json["ok"]?.jsonPrimitive?.content != "true") return emptyList()

            val resultArray = json["result"]?.jsonArray ?: return emptyList()

            return resultArray.mapNotNull { element ->
                parseTonTransaction(element.jsonObject, address, accountId)
            }
        } finally {
            client.close()
        }
    }

    private fun parseTonTransaction(
        tx: JsonObject,
        myAddress: String,
        accountId: String
    ): TransactionInfo? {
        val txId = tx["transaction_id"]?.jsonObject ?: return null
        val lt = txId["lt"]?.jsonPrimitive?.content ?: return null
        val hash = txId["hash"]?.jsonPrimitive?.content ?: return null
        val txHash = "$lt:$hash"

        val utime = tx["utime"]?.jsonPrimitive?.longOrNull ?: return null
        val timestamp = utime * 1000

        val inMsg = tx["in_msg"]?.jsonObject
        val inSource = inMsg?.get("source")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        val inDest = inMsg?.get("destination")?.jsonPrimitive?.contentOrNull
        val inValue = inMsg?.get("value")?.jsonPrimitive?.contentOrNull

        val outMsgs = tx["out_msgs"]?.jsonArray ?: emptyList()

        val hasExternalOutMsg = outMsgs.any { msg ->
            val dest = msg.jsonObject?.get("destination")?.jsonPrimitive?.contentOrNull
            dest != null && !dest.equals(myAddress, ignoreCase = true)
        }

        val direction = when {
            inSource != null && !inSource.equals(myAddress, ignoreCase = true) -> TransactionDirection.INCOMING
            hasExternalOutMsg -> TransactionDirection.OUTGOING
            else -> TransactionDirection.SELF
        }
        val amount = when (direction) {
            TransactionDirection.OUTGOING -> {
                outMsgs.firstOrNull()?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull ?: inValue ?: "0"
            }
            TransactionDirection.INCOMING -> inValue ?: "0"
            TransactionDirection.SELF -> inValue ?: "0"
        }

        val counterparty = when (direction) {
            TransactionDirection.OUTGOING -> outMsgs.firstOrNull()?.jsonObject?.get("destination")?.jsonPrimitive?.contentOrNull
            TransactionDirection.INCOMING -> inSource
            TransactionDirection.SELF -> null
        }

        val fee = tx["fee"]?.jsonPrimitive?.longOrNull?.toString()

        return TransactionInfo(
            id = "${accountId}_$lt",
            accountId = accountId,
            txHash = txHash,
            direction = direction,
            amount = amount,
            fee = fee,
            timestamp = timestamp,
            status = TransactionStatus.CONFIRMED,
            counterpartyAddress = counterparty,
            blockHeight = lt.toLongOrNull(),
            chainData = null
        )
    }
}
