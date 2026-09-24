package com.ultrabytecoder.coinsafebox.data

import app.cash.sqldelight.db.SqlDriver

expect class DatabaseDriverFactory(context: Any? = null) {
    /**
     * Opens (creating the schema on first run) the SQLCipher-encrypted database.
     * [passphrase] is the raw DEK; implementations must copy it before handing it
     * to the platform driver, since SQLCipher wrappers may zero the input array.
     * Throws when the file exists but cannot be opened with this key.
     */
    suspend fun createDriver(passphrase: ByteArray): SqlDriver

    /** Deletes the on-disk database file (recovery / reset). No-op if missing. */
    fun deleteDatabase()
}