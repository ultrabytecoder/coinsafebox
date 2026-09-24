package com.ultrabytecoder.coinsafebox.data

import app.cash.sqldelight.db.SqlDriver
import com.ultrabytecoder.coinsafebox.db.CoinSafeBoxDatabase
import com.ultrabytecoder.coinsafebox.sqlcipher.NativeSqlCipherDriver
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop (JVM) database driver: SQLCipher 4.17.0 bundled as a native library
 * (see the `resources/native` directory), exposed through JNA. Byte-compatible
 * with the Android `net.zetetic:sqlcipher-android:4.17.0` database format.
 *
 * The database lives in `<user.home>/.coinsafebox/coinsafebox.db`; the directory
 * is created with owner-only permissions (0700 on POSIX).
 */
actual class DatabaseDriverFactory actual constructor(context: Any?) {
    private val baseDir: File = (context as? File) ?: appDataDir()
    private val dbFile = File(baseDir, "coinsafebox.db")

    actual suspend fun createDriver(passphrase: ByteArray): SqlDriver = withContext(Dispatchers.IO) {
        if (!baseDir.exists()) {
            try {
                Files.createDirectories(
                    baseDir.toPath(),
                    PosixFilePermissions.asFileAttribute(
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    )
                )
            } catch (_: UnsupportedOperationException) {
                baseDir.mkdirs()
                restrictToOwner(baseDir)
            }
        }

        // The driver copies and zeroes the key during construction (sqlite3_key_v2
        // derives its KEK immediately); the caller keeps ownership of [passphrase].
NativeSqlCipherDriver(
            dbPath = dbFile.absolutePath,
            key = passphrase,
            schema = CoinSafeBoxDatabase.Schema,
            migrateEmptySchema = true
        )
    }

    actual fun deleteDatabase() {
        dbFile.delete()
        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()
        File(dbFile.absolutePath + "-journal").delete()
    }
}