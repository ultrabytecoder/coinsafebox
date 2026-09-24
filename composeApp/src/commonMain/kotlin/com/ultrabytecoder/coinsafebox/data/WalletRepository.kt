package com.ultrabytecoder.coinsafebox.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ultrabytecoder.coinsafebox.domain.model.WalletInfo
import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository as WalletRepositoryInterface
import com.ultrabytecoder.coinsafebox.security.SecretCipher
import com.ultrabytecoder.coinsafebox.security.wipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class WalletRepository(private val databaseProvider: DatabaseProvider) : WalletRepositoryInterface {
    private val queries get() = databaseProvider.database().coinSafeBoxDatabaseQueries

    override fun getWalletsFlow(): Flow<List<WalletInfo>> {
        return queries.selectAllWallets()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list -> list.map { it.toWalletInfo() } }
    }

    override suspend fun getWallet(id: Long): WalletInfo? = withContext(Dispatchers.IO) {
        queries.selectWalletById(id).executeAsOneOrNull()?.toWalletInfo()
    }

    override suspend fun getMasterSeed(id: Long): ByteArray? = withContext(Dispatchers.IO) {
        val stored = queries.selectWalletById(id).executeAsOneOrNull()?.master_seed
            ?: return@withContext null
        try {
            SecretCipher.decrypt(stored).plaintext
        } finally {
            stored.wipe()
        }
    }

    override suspend fun insertWallet(name: String, masterSeed: ByteArray, mnemonic: ByteArray?): Long = withContext(Dispatchers.IO) {
        queries.insertWallet(
            id = null,
            name = name,
            master_seed = SecretCipher.encrypt(masterSeed),
            mnemonic = mnemonic?.let { SecretCipher.encrypt(it) }
        )
        queries.lastInsertRowId().executeAsOne()
    }

    override suspend fun deleteWallet(id: Long) {
        withContext(Dispatchers.IO) {
            queries.deleteWalletById(id)
        }
    }

    override suspend fun getStoredMnemonic(id: Long): ByteArray? = withContext(Dispatchers.IO) {
        val stored = queries.selectWalletById(id).executeAsOneOrNull()?.mnemonic
            ?: return@withContext null
        try {
            SecretCipher.decrypt(stored).plaintext
        } finally {
            stored.wipe()
        }
    }

    override suspend fun renameWallet(id: Long, name: String) {
        withContext(Dispatchers.IO) {
            queries.renameWallet(name, id)
        }
    }

    private fun com.ultrabytecoder.coinsafebox.db.Wallets.toWalletInfo(): WalletInfo = WalletInfo(
        id = id,
        name = name
    )
}