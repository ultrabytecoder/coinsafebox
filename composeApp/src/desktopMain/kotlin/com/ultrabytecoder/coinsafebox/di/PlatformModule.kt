package com.ultrabytecoder.coinsafebox.di

import com.ultrabytecoder.coinsafebox.data.DatabaseDriverFactory
import com.ultrabytecoder.coinsafebox.data.SettingsStorage
import com.ultrabytecoder.coinsafebox.data.SettingsStore
import com.ultrabytecoder.coinsafebox.security.DbSessionFactory
import org.koin.dsl.bind
import org.koin.dsl.module

val platformModule = module {
    single { SettingsStorage() } bind SettingsStore::class
}

/** Platform driver factory wired into SessionManager (constructed directly —
 * it is stateless apart from the DB path, so no Koin indirection is needed). */
internal actual fun platformDriverFactory(): DbSessionFactory = object : DbSessionFactory {
    private val factory = DatabaseDriverFactory()
    override suspend fun createDriver(passphrase: ByteArray) = factory.createDriver(passphrase)
    override fun deleteDatabase() = factory.deleteDatabase()
}
