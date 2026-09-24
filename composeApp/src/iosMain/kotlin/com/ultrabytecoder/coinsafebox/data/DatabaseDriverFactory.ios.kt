package com.ultrabytecoder.coinsafebox.data

import app.cash.sqldelight.db.SqlDriver
import co.touchlab.sqliter.DatabaseFileContext
import com.ultrabytecoder.coinsafebox.db.CoinSafeBoxDatabase
import com.yet.sqlcipher.NativeSqlCipherDriverFactory
import com.yet.sqlcipher.SqlCipherConfig
import com.yet.sqlcipher.SqlCipherKey
import com.yet.sqlcipher.SqlCipherRecovery
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

actual class DatabaseDriverFactory actual constructor(context: Any?) {

    private companion object {
        const val DB_NAME = "coinsafebox.db"
    }

    actual suspend fun createDriver(passphrase: ByteArray): SqlDriver {
        val factory = NativeSqlCipherDriverFactory()
        val driver = factory.create(
            schema = CoinSafeBoxDatabase.Schema,
            config = SqlCipherConfig(
                name = DB_NAME,
                key = SqlCipherKey { passphrase.copyOf() },
                recovery = SqlCipherRecovery.Fail
            )
        )
        excludeDatabaseFromBackup()
        return driver
    }

    actual fun deleteDatabase() {
        // SQLiter owns the default database directory resolution, so deletion must
        // go through it too — a hand-built path would silently miss the file.
        DatabaseFileContext.deleteDatabase(DB_NAME, basePath = null)
    }

    /**
     * Marks the SQLite database file (and its WAL/SHM/journal sidecar files,
     * via the containing directory) as excluded from iTunes/Finder and iCloud
     * device backups. The database holds the wallet seed and PIN data.
     */
    private fun excludeDatabaseFromBackup() {
        val databasePath = DatabaseFileContext.databasePath(DB_NAME, null)
        val databaseUrl = NSURL.fileURLWithPath(databasePath)
        databaseUrl.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
        databaseUrl.deletingLastPathComponent?.let { parentUrl ->
            parentUrl.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
        }
    }
}