package com.ultrabytecoder.coinsafebox.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ultrabytecoder.coinsafebox.domain.model.UtxoInfo
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository as UtxoRepositoryInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class UtxoRepository(private val databaseProvider: DatabaseProvider) : UtxoRepositoryInterface {
    private val queries get() = databaseProvider.database().coinSafeBoxDatabaseQueries

    override fun getUnspentByAccountFlow(accountId: String): Flow<List<UtxoInfo>> {
        return queries.selectUtxosByAccountId(accountId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list -> list.map { it.toUtxoInfo() } }
    }

    override suspend fun getUtxosByAccount(accountId: String): List<UtxoInfo> = withContext(Dispatchers.IO) {
        queries.selectUtxosByAccountId(accountId).executeAsList().map { it.toUtxoInfo() }
    }

    override suspend fun insertUtxo(utxo: UtxoInfo) {
        withContext(Dispatchers.IO) {
            queries.insertUtxo(
                account_id = utxo.accountId,
                derivation_path = utxo.derivationPath,
                amount = utxo.amount,
                txid = utxo.txid,
                vout = utxo.vout.toLong()
            )
        }
    }

    override suspend fun deleteUtxo(id: Long) {
        withContext(Dispatchers.IO) {
            queries.deleteUtxoById(id)
        }
    }

    override suspend fun deleteUtxosByAccount(accountId: String) {
        withContext(Dispatchers.IO) {
            queries.deleteUtxosByAccountId(accountId)
        }
    }

    private fun com.ultrabytecoder.coinsafebox.db.Utxos.toUtxoInfo(): UtxoInfo = UtxoInfo(
        id = id,
        accountId = account_id,
        derivationPath = derivation_path,
        amount = amount,
        txid = txid,
        vout = vout.toInt()
    )
}
