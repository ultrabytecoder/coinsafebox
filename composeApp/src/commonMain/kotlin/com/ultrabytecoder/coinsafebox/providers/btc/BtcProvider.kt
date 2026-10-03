package com.ultrabytecoder.coinsafebox.providers

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.delay
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.providers.DerivationPathResolver
import com.ultrabytecoder.coinsafebox.domain.model.UtxoInfo
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.SigVersion
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.toSatoshi
import kotlin.math.ceil
import fr.acinq.bitcoin.utils.Either
import fr.acinq.secp256k1.Hex
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.CustomFeeParams
import com.ultrabytecoder.coinsafebox.domain.model.FeeEstimation
import com.ultrabytecoder.coinsafebox.domain.model.FeePresets
import com.ultrabytecoder.coinsafebox.domain.model.TransactionDirection
import com.ultrabytecoder.coinsafebox.domain.model.TransactionInfo
import com.ultrabytecoder.coinsafebox.domain.model.TransactionStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock

class BtcProvider(
    private val masterKey: DeterministicWallet.ExtendedPrivateKey?,
    private val accountXpub: DeterministicWallet.ExtendedPublicKey?,
    private val utxoRepository: UtxoRepository,
    private val accountRepository: AccountRepository,
    val params: JsonObject,
    private val networkConfig: NetworkConfig,
    private val transactionRepository: TransactionRepository,
    private val createClient: () -> HttpClient = { HttpClient() }
) : Provider {

    companion object {
        private const val FALLBACK_FEE_RATE = 10L  // sat/vByte, used when API is unreachable
        private const val GAP_LIMIT = 20
        private const val RECEIVE_CHAIN = 0
        private const val CHANGE_CHAIN = 1

        // Retry budget for fetchAddressTxCount (BIP44 gap-limit probing).
        private const val FETCH_TXCOUNT_MAX_ATTEMPTS = 3
        private const val FETCH_TXCOUNT_RETRY_DELAY_MS = 250L

        // P2WPKH size constants (bytes)
        private const val P2WPKH_INPUT_BASE_SIZE = 41L  // outpoint(36) + scriptSig varint(1) + sequence(4)
        private const val P2WPKH_OUTPUT_SIZE = 31L      // 8(value) + 1(len) + 22(script)
        private const val P2WPKH_WITNESS_SIZE = 108L    // 1(count) + (1+72 sig) + (1+33 pubkey)

        // Minimum non-dust output for P2WPKH (3 * relay fee rate * output size)
        private const val DUST_THRESHOLD_SAT = 294L

        // Max BTC amount that fits in Long satoshi representation
        private val MAX_BTC_AMOUNT = BigDecimal.fromLong(Long.MAX_VALUE).divide(BigDecimal.fromLong(100_000_000))
    }

    init {
        require((masterKey != null) xor (accountXpub != null)) { "BtcProvider requires exactly one of masterKey or accountXpub" }
    }

    override val isReadOnly: Boolean get() = accountXpub != null

    private fun varIntSize(n: Int): Long = when {
        n < 253 -> 1L
        n <= 65535 -> 3L
        n <= 0xFFFFFFFF -> 5L
        else -> 9L
    }

    private fun chainPrefix(derivationIndex: Long, chain: Int): String =
        "m/84'/${networkConfig.btcBip84CoinType}'/$derivationIndex'/$chain/"

    private suspend fun minDbIndexForChain(accountId: String, chain: Int, derivationIndex: Long): Long =
        utxoRepository.getUtxosByAccount(accountId)
            .filter { it.derivationPath.startsWith(chainPrefix(derivationIndex, chain)) }
            .mapNotNull { it.derivationPath.substringAfterLast('/').toLongOrNull() }
            .minOrNull() ?: Long.MAX_VALUE

    private fun estimateVSize(numInputs: Int, numOutputs: Int): Long {
        var nonWitness = 0L
        nonWitness += 4 // version
        nonWitness += varIntSize(numInputs)
        nonWitness += numInputs * P2WPKH_INPUT_BASE_SIZE
        nonWitness += varIntSize(numOutputs)
        nonWitness += numOutputs * P2WPKH_OUTPUT_SIZE
        nonWitness += 4 // locktime

        var witness = 0L
        if (numInputs > 0) {
            witness += 2 // marker + flag
        }
        witness += numInputs * P2WPKH_WITNESS_SIZE

        val weight = 4 * nonWitness + witness
        return ceil(weight.toDouble() / 4.0).toLong()
    }

    private suspend fun fetchFeeRate(): Long {
        return try {
            val client = createClient()
            try {
                val response: HttpResponse = client.get("${networkConfig.btcMempoolApiBase}/v1/fees/recommended")
                if (response.status != HttpStatusCode.OK) {
                    return FALLBACK_FEE_RATE
                }
                val body = response.body<String>()
                val json = Json.parseToJsonElement(body).jsonObject
                val hourFee = json["hourFee"]?.jsonPrimitive?.longOrNull
                if (hourFee != null && hourFee > 0) hourFee else FALLBACK_FEE_RATE
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            FALLBACK_FEE_RATE
        }
    }

    private fun derivePublicKeyFor(account: AccountInfo, chain: Int, addressIndex: Long): PublicKey {
        val accountXpub = accountXpub
        if (accountXpub != null) {
            return accountXpub.derivePublicKey(listOf(chain.toLong(), addressIndex)).publicKey
        }
        val segments = DerivationPathResolver.parsePath(account.derivationPath).map { (index, hardened) ->
            if (hardened) DeterministicWallet.hardened(index) else index
        }
        return masterKey!!.derivePrivateKey(segments + listOf(chain.toLong(), addressIndex)).publicKey
    }

    private fun deriveChangeKeyFromPath(accountPath: String, addressIndex: Long): DeterministicWallet.ExtendedPrivateKey {
        val segments = DerivationPathResolver.parsePath(accountPath).map { (index, hardened) ->
            if (hardened) DeterministicWallet.hardened(index) else index
        }
        return masterKey!!.derivePrivateKey(segments + listOf(1L, addressIndex))
    }

    override suspend fun getAddress(accountId: String): String {
        val account = accountRepository.getAccount(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")
        val addressIndex = params["current_receive_key_id"]?.jsonPrimitive?.long ?: 0L
        val pub = derivePublicKeyFor(account, RECEIVE_CHAIN, addressIndex)
        return Bitcoin.computeBIP84Address(pub, networkConfig.btcGenesisBlockHash)
    }

    private fun resolveDeriveFunctions(account: AccountInfo): Pair<(Long) -> PublicKey, (Long) -> PublicKey> {
        val receiveDeriver: (Long) -> PublicKey = { addrIdx -> derivePublicKeyFor(account, RECEIVE_CHAIN, addrIdx) }
        val changeDeriver: (Long) -> PublicKey = { addrIdx -> derivePublicKeyFor(account, CHANGE_CHAIN, addrIdx) }
        return receiveDeriver to changeDeriver
    }

    override suspend fun syncBalance(accountId: String, syncMode: SyncMode) {
        println("BtcProvider.syncBalance() started: accountId=$accountId, syncMode=$syncMode")
        val account = accountRepository.getAccount(accountId) ?: return
        val derivationIndex = account.accountIndex!!
        val (receiveDeriver, changeDeriver) = resolveDeriveFunctions(account)

        val receiveStartIndex = scanStartIndex(accountId, RECEIVE_CHAIN, derivationIndex, params["current_receive_key_id"]?.jsonPrimitive?.long, syncMode)
        val changeStartIndex = scanStartIndex(accountId, CHANGE_CHAIN, derivationIndex, params["current_change_key_id"]?.jsonPrimitive?.long, syncMode)

        val client = createClient()
        try {
            val highestReceiveIndex = scanChain(client, accountId, RECEIVE_CHAIN, derivationIndex, receiveStartIndex, receiveDeriver)
            val highestChangeIndex = scanChain(client, accountId, CHANGE_CHAIN, derivationIndex, changeStartIndex, changeDeriver)

            val updates = mutableMapOf<String, JsonPrimitive>()
            if (highestReceiveIndex != null) {
                updates["current_receive_key_id"] = JsonPrimitive(highestReceiveIndex + 1)
            }
            if (highestChangeIndex != null) {
                updates["current_change_key_id"] = JsonPrimitive(highestChangeIndex + 1)
            }
            if (updates.isNotEmpty()) {
                val updatedParams = JsonObject(params.toMutableMap() + updates)
                accountRepository.updateParams(accountId, updatedParams.toString())
            }
        } finally {
            client.close()
        }

        val rawBalance = balance(accountId)
        val normalized = rawBalance.divide(BigDecimal.fromLong(100_000_000)).toPlainString()
        accountRepository.updateAmount(accountId, normalized)
        println("BtcProvider.syncBalance() completed: accountId=$accountId, balance=$normalized BTC")
    }

    override suspend fun syncTransactions(accountId: String, syncMode: SyncMode) {
        println("BtcProvider.syncTransactions() started: accountId=$accountId, syncMode=$syncMode")
        val account = accountRepository.getAccount(accountId) ?: return
        val derivationIndex = account.accountIndex!!
        val (receiveDeriver, changeDeriver) = resolveDeriveFunctions(account)

        val client = createClient()
        try {
            val transactions = fetchTransactionsForAccount(accountId, syncMode, client, receiveDeriver, changeDeriver)
            transactionRepository.upsertAll(transactions)
            println("BtcProvider.syncTransactions() completed: accountId=$accountId, transactions=${transactions.size}")
        } finally {
            client.close()
        }
    }

    private suspend fun scanStartIndex(
        accountId: String,
        chain: Int,
        derivationIndex: Long,
        currentKeyId: Long?,
        syncMode: SyncMode
    ): Long = when (syncMode) {
        SyncMode.FULL -> 0L
        SyncMode.NORMAL -> minOf(currentKeyId ?: 0L, minDbIndexForChain(accountId, chain, derivationIndex))
    }

    private suspend fun fetchTransactionsForAccount(
        accountId: String,
        syncMode: SyncMode,
        client: HttpClient,
        receiveDeriver: (Long) -> PublicKey,
        changeDeriver: (Long) -> PublicKey
    ): List<TransactionInfo> {
        val receiveStartIndex = when (syncMode) {
            SyncMode.FULL -> 0L
            SyncMode.NORMAL -> maxOf(0L, (params["current_receive_key_id"]?.jsonPrimitive?.long ?: 0L) - 1)
        }
        val changeStartIndex = when (syncMode) {
            SyncMode.FULL -> 0L
            SyncMode.NORMAL -> maxOf(0L, (params["current_change_key_id"]?.jsonPrimitive?.long ?: 0L) - 1)
        }

        val txWithContexts = mutableListOf<Pair<JsonObject, String>>()

        // Scan receive chain
        fetchTransactionsForChain(client, RECEIVE_CHAIN, receiveStartIndex, receiveDeriver)
            .forEach { txWithContexts.add(it) }

        // Scan change chain
        fetchTransactionsForChain(client, CHANGE_CHAIN, changeStartIndex, changeDeriver)
            .forEach { txWithContexts.add(it) }

        return aggregateTransactions(txWithContexts, accountId)
    }

    private suspend fun fetchTransactionsForChain(
        client: HttpClient,
        chain: Int,
        startIndex: Long,
        deriveKey: (Long) -> PublicKey
    ): List<Pair<JsonObject, String>> {
        val results = mutableListOf<Pair<JsonObject, String>>()
        var consecutiveEmpty = 0
        var addressIndex = startIndex

        while (consecutiveEmpty < GAP_LIMIT) {
            val pub = deriveKey(addressIndex)
            val address = Bitcoin.computeBIP84Address(pub, networkConfig.btcGenesisBlockHash)

            val txs = fetchAddressTransactions(client, address)
            if (txs.isEmpty()) {
                consecutiveEmpty++
            } else {
                consecutiveEmpty = 0
                for (tx in txs) {
                    results.add(tx to address)
                }
            }
            addressIndex++
            delay(10)
        }
        return results
    }

    private suspend fun fetchAddressTransactions(client: HttpClient, address: String): List<JsonObject> {
        return try {
            val response: HttpResponse = client.get("${networkConfig.btcMempoolApiBase}/address/$address/txs")
            if (response.status != HttpStatusCode.OK) {
                println("fetchAddressTransactions: HTTP ${response.status.value} for $address")
                return emptyList()
            }
            val body = response.body<String>()
            val jsonArray = Json.parseToJsonElement(body).jsonArray
            jsonArray.map { it.jsonObject }
        } catch (e: Exception) {
            println("fetchAddressTransactions: failed for $address — ${e.message}")
            emptyList()
        }
    }

    private fun aggregateTransactions(
        txWithContexts: List<Pair<JsonObject, String>>,
        accountId: String
    ): List<TransactionInfo> {
        if (txWithContexts.isEmpty()) return emptyList()

        // Group by txid to handle multi-address aggregation
        val grouped = txWithContexts.groupBy { (tx, _) ->
            tx["txid"]?.jsonPrimitive?.content ?: return@groupBy ""
        }.filterKeys { it.isNotEmpty() }

        return grouped.mapNotNull { (txid, contexts) ->
            val tx = contexts.first().first
            val ownAddresses = contexts.map { it.second }.toSet().map { it.lowercase() }.toSet()

            // Collect all input and output addresses
            val vins = tx["vin"]?.jsonArray ?: return@mapNotNull null
            val vouts = tx["vout"]?.jsonArray ?: return@mapNotNull null

            // Check if any input spends from our address
            val hasOurInput = vins.any { vin ->
                val prevoutAddr = vin.jsonObject["prevout"]?.jsonObject
                    ?.get("scriptpubkey_address")?.jsonPrimitive?.content
                    ?.lowercase()
                prevoutAddr != null && prevoutAddr in ownAddresses
            }

            val direction = if (hasOurInput) TransactionDirection.OUTGOING else TransactionDirection.INCOMING

            // Amount calculation
            val amount = when (direction) {
                TransactionDirection.OUTGOING -> {
                    // Sum vouts to non-own addresses (excludes change)
                    vouts.sumOf { vout ->
                        val addr = vout.jsonObject["scriptpubkey_address"]?.jsonPrimitive?.content?.lowercase()
                        if (addr != null && addr !in ownAddresses) {
                            vout.jsonObject["value"]?.jsonPrimitive?.long ?: 0L
                        } else 0L
                    }
                }
                TransactionDirection.INCOMING -> {
                    // Sum vouts to own addresses
                    vouts.sumOf { vout ->
                        val addr = vout.jsonObject["scriptpubkey_address"]?.jsonPrimitive?.content?.lowercase()
                        if (addr != null && addr in ownAddresses) {
                            vout.jsonObject["value"]?.jsonPrimitive?.long ?: 0L
                        } else 0L
                    }
                }
                else -> 0L
            }

            // Counterparty address
            val counterparty = when (direction) {
                TransactionDirection.OUTGOING -> {
                    vouts.firstOrNull { vout ->
                        val addr = vout.jsonObject["scriptpubkey_address"]?.jsonPrimitive?.content?.lowercase()
                        addr != null && addr !in ownAddresses
                    }?.jsonObject?.get("scriptpubkey_address")?.jsonPrimitive?.content
                }
                TransactionDirection.INCOMING -> {
                    vins.firstOrNull { vin ->
                        val addr = vin.jsonObject["prevout"]?.jsonObject
                            ?.get("scriptpubkey_address")?.jsonPrimitive?.content?.lowercase()
                        addr != null && addr !in ownAddresses
                    }?.jsonObject?.get("prevout")?.jsonObject
                        ?.get("scriptpubkey_address")?.jsonPrimitive?.content
                }
                else -> null
            }

            // Fee from top-level field (satoshis)
            val fee = tx["fee"]?.jsonPrimitive?.longOrNull?.toString()

            // Status
            val statusObj = tx["status"]?.jsonObject
            val confirmed = statusObj?.get("confirmed")?.jsonPrimitive?.content?.toBoolean() ?: false
            val blockHeight = if (confirmed) statusObj["block_height"]?.jsonPrimitive?.longOrNull else null
            val blockTime = if (confirmed) statusObj["block_time"]?.jsonPrimitive?.longOrNull else null

            val transactionStatus = if (confirmed) TransactionStatus.CONFIRMED else TransactionStatus.PENDING
            val timestamp = if (blockTime != null) blockTime * 1000 else Clock.System.now().toEpochMilliseconds()

            TransactionInfo(
                id = "${accountId}_$txid",
                accountId = accountId,
                txHash = txid,
                direction = direction,
                amount = amount.toString(),
                fee = fee,
                timestamp = timestamp,
                status = transactionStatus,
                counterpartyAddress = counterparty,
                blockHeight = blockHeight
            )
        }
    }

    private suspend fun scanChain(
        client: HttpClient,
        accountId: String,
        chain: Int,
        derivationIndex: Long,
        startIndex: Long,
        deriveKey: (Long) -> PublicKey
    ): Long? {
        val existingUtxos = utxoRepository.getUtxosByAccount(accountId)
        val existingTxids = existingUtxos.map { "${it.txid}:${it.vout}" }.toSet()
        val seenOutpoints = mutableSetOf<String>()

        var consecutiveEmpty = 0
        var addressIndex = startIndex
        var highestUsedIndex: Long? = null  // highestUsedIndex is null means "no UTXOs found on this chain"

        while (consecutiveEmpty < GAP_LIMIT) {
            val pub = deriveKey(addressIndex)
            val address = Bitcoin.computeBIP84Address(pub, networkConfig.btcGenesisBlockHash)
            val derivationPath = "m/84'/${networkConfig.btcBip84CoinType}'/$derivationIndex'/$chain/$addressIndex"

            // An address counts as "used" if it has ANY transaction history (chain
            // or mempool), not merely unspent UTXOs. Using unspent-only here would
            // treat a fully-spent address as empty and stop the scan early, missing
            // later receive addresses (undercounting the balance).
            if (fetchAddressTxCount(client, address) > 0) {
                consecutiveEmpty = 0
                highestUsedIndex = addressIndex
                for (utxo in fetchUtxos(client, address)) {
                    val outpoint = "${utxo.txid}:${utxo.vout}"
                    seenOutpoints.add(outpoint)
                    if (outpoint !in existingTxids) {
                        utxoRepository.insertUtxo(
                            UtxoInfo(
                                accountId = accountId,
                                derivationPath = derivationPath,
                                amount = utxo.value,
                                txid = utxo.txid,
                                vout = utxo.vout.toInt()
                            )
                        )
                    }
                }
            } else {
                consecutiveEmpty++
            }
            addressIndex++
        }
        // Delete DB utxos on this chain that the API no longer reports as unspent (spent externally
        // or by a send whose cleanup was interrupted)
        val stale = existingUtxos.filter {
            it.derivationPath.startsWith(chainPrefix(derivationIndex, chain)) &&
            "${it.txid}:${it.vout}" !in seenOutpoints
        }
        for (utxo in stale) {
            utxoRepository.deleteUtxo(utxo.id)
        }
        return highestUsedIndex
    }

    private suspend fun fetchUtxos(client: HttpClient, address: String): List<Utxo> {
        return try {
            val response: HttpResponse = client.get("${networkConfig.btcMempoolApiBase}/address/$address/utxo")
            if (response.status != HttpStatusCode.OK) {
                println("fetchUtxos: HTTP ${response.status.value} for $address")
                return emptyList()
            }
            val body = response.body<String>()
            val jsonArray = Json.parseToJsonElement(body).jsonArray
            jsonArray.mapNotNull { elem ->
                val obj = elem.jsonObject
                val txid = obj["txid"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val vout = obj["vout"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val value = obj["value"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                Utxo(txid, vout, value)
            }
        } catch (e: Exception) {
            println("fetchUtxos: failed for $address — ${e.message}")
            emptyList()
        }
    }

    /**
     * Total number of transactions (confirmed + mempool) involving [address],
     * from the mempool.space address summary. Returns 0 if the address has no
     * history. Transient HTTP/network failures are retried a few times before
     * giving up and returning 0 — treating a blip as "no history" would let the
     * BIP44 gap-limit stop early and undercount the balance. Used for gap-limit
     * detection: an address is "used" when this is > 0, independent of whether
     * its UTXOs are still unspent.
     */
    private suspend fun fetchAddressTxCount(client: HttpClient, address: String): Long {
        var attempt = 0
        while (true) {
            attempt++
            try {
                val response: HttpResponse = client.get("${networkConfig.btcMempoolApiBase}/address/$address")
                if (response.status == HttpStatusCode.OK) {
                    val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                    val chainCount = obj["chain_stats"]?.jsonObject?.get("tx_count")?.jsonPrimitive?.longOrNull ?: 0L
                    val mempoolCount = obj["mempool_stats"]?.jsonObject?.get("tx_count")?.jsonPrimitive?.longOrNull ?: 0L
                    return chainCount + mempoolCount
                }
                if (attempt < FETCH_TXCOUNT_MAX_ATTEMPTS) {
                    delay(FETCH_TXCOUNT_RETRY_DELAY_MS)
                    continue
                }
                println("fetchAddressTxCount: HTTP ${response.status.value} for $address after $attempt attempt(s)")
                return 0L
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt < FETCH_TXCOUNT_MAX_ATTEMPTS) {
                    delay(FETCH_TXCOUNT_RETRY_DELAY_MS)
                    continue
                }
                println("fetchAddressTxCount: failed for $address after $attempt attempt(s) — ${e.message}")
                return 0L
            }
        }
    }

    private fun selectInputs(requiredAmount: Long, feeRate: Long, utxos: List<UtxoInfo>): List<UtxoInfo>? {
        val sorted = utxos.sortedByDescending { it.amount }
        val selected = mutableListOf<UtxoInfo>()
        var sum = 0L
        for (utxo in sorted) {
            selected.add(utxo)
            sum += utxo.amount

            // Calculate potential change to determine numOutputs
            val estimatedFeeMax = estimateVSize(selected.size, 2) * feeRate
            val tempChange = sum - requiredAmount - estimatedFeeMax

            val numOutputs = if (tempChange >= DUST_THRESHOLD_SAT) 2 else 1
            val estimatedFeeActual = estimateVSize(selected.size, numOutputs) * feeRate

            if (sum >= requiredAmount + estimatedFeeActual) {
                return selected
            }
        }
        return null
    }

    private suspend fun resolveBtcFeeRate(feeParams: CustomFeeParams?): Long {
        return when (feeParams) {
            null -> fetchFeeRate()
            is CustomFeeParams.Btc -> feeParams.feeRateSatVb
            else -> throw IllegalArgumentException("BTC provider received ${feeParams::class.simpleName}")
        }
    }

    override suspend fun balance(accountId: String): BigDecimal {
        val unspent = utxoRepository.getUtxosByAccount(accountId)
        val totalSats = unspent.sumOf { it.amount }
        return BigDecimal.fromLong(totalSats)
    }

    private suspend fun buildSignedTransaction(
        address: String,
        amount: BigDecimal,
        accountId: String,
        feeParams: CustomFeeParams? = null
    ): Triple<String, List<Long>, Boolean> {
        if (isReadOnly) throw ReadOnlyException()
        if (amount > MAX_BTC_AMOUNT) {
            throw IllegalArgumentException("Amount exceeds maximum representable BTC value")
        }
        val destAmountSat = amount.multiply(BigDecimal.fromLong(100_000_000)).longValue(exactRequired = true)

        val allUtxos = utxoRepository.getUtxosByAccount(accountId)
        check(allUtxos.isNotEmpty()) { "No UTXOs found for account $accountId" }

        val feeRate = resolveBtcFeeRate(feeParams)
        val selectedUtxos = selectInputs(destAmountSat, feeRate, allUtxos)
            ?: throw IllegalStateException("Not enough funds - have ${allUtxos.sumOf { it.amount }} sat but need $destAmountSat sat")

        // Calculate numOutputs based on potential change
        val estimatedFeeMax = estimateVSize(selectedUtxos.size, 2) * feeRate
        val tempChange = selectedUtxos.sumOf { it.amount } - destAmountSat - estimatedFeeMax
        val numOutputs = if (tempChange >= DUST_THRESHOLD_SAT) 2 else 1
        val feeSat = estimateVSize(selectedUtxos.size, numOutputs) * feeRate

        val inputsSum = selectedUtxos.sumOf { it.amount }
        val changeAmount = inputsSum - destAmountSat - feeSat

        if (changeAmount < 0) {
            throw IllegalStateException("Not enough funds to cover amount + fee (fee=$feeSat sat)")
        }

        val txIns = selectedUtxos.map { utxo ->
            TxIn(
                outPoint = OutPoint(TxId(utxo.txid), utxo.vout.toLong()),
                sequence = 0xffffffffL
            )
        }

        val scriptResult = Bitcoin.addressToPublicKeyScript(networkConfig.btcGenesisBlockHash, address)
        check(scriptResult is Either.Right) { "Invalid destination address: $address" }
        val destScript = scriptResult.value

        val account = accountRepository.getAccount(accountId)
            ?: throw IllegalStateException("Account not found: $accountId")
        val changeAddressIndex = params["current_change_key_id"]?.jsonPrimitive?.long ?: 0L
        val changeKey = deriveChangeKeyFromPath(account.derivationPath, changeAddressIndex)

        val changeScript = Script.pay2wpkh(changeKey.publicKey)

        val txOuts = mutableListOf(
            TxOut(destAmountSat.toSatoshi(), destScript)
        )
        if (changeAmount >= DUST_THRESHOLD_SAT) {
            txOuts.add(TxOut(changeAmount.toSatoshi(), changeScript))
        }

        var tx = Transaction(
            version = 1,
            txIn = txIns,
            txOut = txOuts,
            lockTime = 0
        )

        for (i in selectedUtxos.indices) {
            val utxo = selectedUtxos[i]
            val signingKey = deriveKeyFromPath(utxo.derivationPath)
            val publicKey = signingKey.publicKey
            val previousOutputScript = Script.pay2pkh(publicKey)

            val sig = tx.signInput(
                i,
                previousOutputScript,
                SigHash.SIGHASH_ALL,
                utxo.amount.toSatoshi(),
                SigVersion.SIGVERSION_WITNESS_V0,
                signingKey.privateKey
            )

            val witness = Script.witnessPay2wpkh(publicKey, ByteVector(sig))
            tx = tx.updateWitness(i, witness)
        }

        val txHex = Hex.encode(Transaction.write(tx))
        return Triple(txHex, selectedUtxos.map { it.id }, changeAmount > 0)
    }

    override suspend fun estimateFee(
        accountId: String,
        amount: BigDecimal,
        recipientAddress: String?,
        feeParams: CustomFeeParams?
    ): FeeEstimation {
        if (amount > MAX_BTC_AMOUNT) {
            throw IllegalArgumentException("Amount exceeds maximum representable BTC value")
        }
        val destAmountSat = amount.multiply(BigDecimal.fromLong(100_000_000)).longValue(exactRequired = true)
        val allUtxos = utxoRepository.getUtxosByAccount(accountId)
        if (allUtxos.isEmpty()) {
            throw IllegalStateException("No UTXOs available for fee estimation")
        }
        val feeRate = resolveBtcFeeRate(feeParams)
        val selected = selectInputs(destAmountSat, feeRate, allUtxos)
        if (selected == null) {
            throw IllegalStateException("Insufficient funds for transaction")
        }
        // Calculate numOutputs based on potential change
        val estimatedFeeMax = estimateVSize(selected.size, 2) * feeRate
        val tempChange = selected.sumOf { it.amount } - destAmountSat - estimatedFeeMax
        val numOutputs = if (tempChange >= DUST_THRESHOLD_SAT) 2 else 1
        val feeSat = estimateVSize(selected.size, numOutputs) * feeRate
        val totalCost = BigDecimal.fromLong(feeSat).divide(BigDecimal.fromLong(100_000_000))

        return FeeEstimation(totalCost, CustomFeeParams.Btc(feeRate))
    }

    override suspend fun createTransaction(
        address: String,
        amount: BigDecimal,
        accountId: String,
        feeParams: CustomFeeParams?
    ): String {
        if (isReadOnly) throw ReadOnlyException()
        return buildSignedTransaction(address, amount, accountId, feeParams).first
    }

    override suspend fun broadcast(rawTransaction: String): String {
        val client = createClient()
        try {
            val broadcastResponse: HttpResponse = client.post("${networkConfig.btcMempoolApiBase}/tx") {
                contentType(ContentType.Text.Plain)
                setBody(rawTransaction)
            }

            if (broadcastResponse.status != HttpStatusCode.OK) {
                val errorBody = broadcastResponse.body<String>()
                throw IllegalStateException("Broadcast failed (${broadcastResponse.status.value}): $errorBody")
            }

            return broadcastResponse.body<String>()
        } finally {
            client.close()
        }
    }

    override suspend fun send(address: String, amount: BigDecimal, accountId: String): String {
        val (txHex, spentUtxoIds, hasChange) = buildSignedTransaction(address, amount, accountId, null)

        val txid = broadcast(txHex)

        for (id in spentUtxoIds) {
            utxoRepository.deleteUtxo(id)
        }

        if (hasChange) {
            val currentIndex = params["current_change_key_id"]?.jsonPrimitive?.long ?: 0L
            val updatedParams = JsonObject(params.toMutableMap() + ("current_change_key_id" to JsonPrimitive(currentIndex + 1)))
            accountRepository.updateParams(accountId, updatedParams.toString())
        }

        return txid
    }

    override suspend fun feePresets(accountId: String): FeePresets {
        val fees = try {
            val client = createClient()
            try {
                val response: HttpResponse = client.get("${networkConfig.btcMempoolApiBase}/v1/fees/recommended")
                if (response.status == HttpStatusCode.OK) {
                    val body = response.body<String>()
                    val json = Json.parseToJsonElement(body).jsonObject
                    Triple(
                        json["hourFee"]?.jsonPrimitive?.longOrNull ?: FALLBACK_FEE_RATE,
                        json["halfHourFee"]?.jsonPrimitive?.longOrNull ?: FALLBACK_FEE_RATE,
                        json["fastestFee"]?.jsonPrimitive?.longOrNull ?: FALLBACK_FEE_RATE
                    )
                } else {
                    null
                }
            } finally {
                client.close()
            }
        } catch (_: Exception) {
            null
        }

        return if (fees != null) {
            FeePresets(
                slow = CustomFeeParams.Btc(fees.first),
                medium = CustomFeeParams.Btc(fees.second),
                fast = CustomFeeParams.Btc(fees.third)
            )
        } else {
            FeePresets(
                slow = CustomFeeParams.Btc(5L),
                medium = CustomFeeParams.Btc(FALLBACK_FEE_RATE),
                fast = CustomFeeParams.Btc(20L)
            )
        }
    }

    private fun deriveKeyFromPath(path: String): DeterministicWallet.ExtendedPrivateKey {
        val parsed = DerivationPathResolver.parsePath(path)
        require(parsed.size == 5 && parsed[0].first == 84L && parsed[0].second) { "Invalid BIP84 path: $path" }
        val segments = parsed.map { (idx, hardened) ->
            if (hardened) DeterministicWallet.hardened(idx) else idx
        }
        return masterKey!!.derivePrivateKey(segments)
    }
}

private data class Utxo(
    val txid: String,
    val vout: Long,
    val value: Long
)
