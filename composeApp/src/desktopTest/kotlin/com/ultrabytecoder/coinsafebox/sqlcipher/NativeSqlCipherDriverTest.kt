package com.ultrabytecoder.coinsafebox.sqlcipher

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.ultrabytecoder.coinsafebox.db.CoinSafeBoxDatabase
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeSqlCipherDriverTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File.createTempFile("coinsafebox-test", "").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun insertWallet(driver: SqlDriver, id: Long, name: String, seed: ByteArray, mnemonic: String?): Long =
        driver.execute(
            null,
            "INSERT INTO wallets (id, name, master_seed, mnemonic) VALUES (?, ?, ?, ?)",
            4
        ) {
            bindLong(0, id)
            bindString(1, name)
            bindBytes(2, seed)
            bindString(3, mnemonic)
        }.value

    private fun countWallets(driver: SqlDriver): Long = driver.executeQuery(
        null,
        "SELECT count(*) FROM wallets",
        { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
        0
    ).value

    @Test
    fun schemaCreatedAndQueriesWork() {
        val dbFile = File(tempDir, "test.db")
        val driver = NativeSqlCipherDriver(
            dbPath = dbFile.absolutePath,
            key = "correct horse battery staple".toByteArray(),
            schema = CoinSafeBoxDatabase.Schema,
            migrateEmptySchema = true
        )

        val inserted = insertWallet(driver, 1, "Main wallet", byteArrayOf(1, 2, 3, 4, 5), "test test test")
        assertEquals(1L, inserted)

        val selected = driver.executeQuery<List<String?>>(
            null,
            "SELECT name FROM wallets WHERE id = ?",
            { cursor ->
                var names = mutableListOf<String?>()
                while (cursor.next().value) names.add(cursor.getString(0))
                QueryResult.Value(names)
            },
            1
        ) { bindLong(0, 1) }
        assertEquals(listOf("Main wallet"), selected.value)

        driver.close()

        // Reopen with the same key: data is still there (persisted + decrypted).
        val reopened = NativeSqlCipherDriver(
            dbPath = dbFile.absolutePath,
            key = "correct horse battery staple".toByteArray(),
            schema = CoinSafeBoxDatabase.Schema,
            migrateEmptySchema = false
        )
        assertEquals(1L, countWallets(reopened))
        reopened.close()
    }

    @Test
    fun wrongKeyIsRejected() {
        val dbFile = File(tempDir, "encrypted.db")
        val driver = NativeSqlCipherDriver(
            dbPath = dbFile.absolutePath,
            key = "right-key".toByteArray(),
            schema = CoinSafeBoxDatabase.Schema,
            migrateEmptySchema = true
        )
        insertWallet(driver, 1, "A", byteArrayOf(9), null)
        driver.close()

        assertFailsWith<WrongPassphraseException> {
            NativeSqlCipherDriver(
                dbPath = dbFile.absolutePath,
                key = "wrong-key".toByteArray(),
                schema = CoinSafeBoxDatabase.Schema,
                migrateEmptySchema = false
            )
        }
    }

    @Test
    fun databaseFileIsActuallyEncryptedOnDisk() {
        val dbFile = File(tempDir, "encrypted-on-disk.db")
        val driver = NativeSqlCipherDriver(
            dbPath = dbFile.absolutePath,
            key = "disk-key".toByteArray(),
            schema = CoinSafeBoxDatabase.Schema,
            migrateEmptySchema = true
        )
        insertWallet(driver, 1, "Secret", byteArrayOf(1, 2, 3), "hidden phrase")
        driver.close()

        // The on-disk file must NOT contain the plaintext values.
        val raw = dbFile.readBytes()
        val text = String(raw, Charsets.ISO_8859_1)
        assertTrue(!text.contains("Secret"))
        assertTrue(!text.contains("hidden phrase"))
    }

    @Test
    fun nullBindingsAndBlobsRoundTrip() {
        val dbFile = File(tempDir, "nulls.db")
        val driver = NativeSqlCipherDriver(
            dbPath = dbFile.absolutePath,
            key = "null-key".toByteArray(),
            schema = CoinSafeBoxDatabase.Schema,
            migrateEmptySchema = true
        )

        insertWallet(driver, 2, "nullable", byteArrayOf(7, 8, 9), null)

        val row = driver.executeQuery(
            null,
            "SELECT name, mnemonic FROM wallets WHERE id = ?",
            { cursor ->
                cursor.next()
                QueryResult.Value(Pair(cursor.getString(0), cursor.getString(1)))
            },
            1
        ) { bindLong(0, 2) }.value
        assertEquals("nullable", row.first)
        assertNull(row.second)

        val seed = driver.executeQuery(
            null,
            "SELECT master_seed FROM wallets WHERE id = ?",
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getBytes(0))
            },
            1
        ) { bindLong(0, 2) }.value
        assertContentEquals(byteArrayOf(7, 8, 9), seed)
        driver.close()
    }

    @Test
    fun transactionsCommitThroughTransacter() {
        val dbFile = File(tempDir, "tx.db")
        val driver = NativeSqlCipherDriver(
            dbPath = dbFile.absolutePath,
            key = "tx-key".toByteArray(),
            schema = CoinSafeBoxDatabase.Schema,
            migrateEmptySchema = true
        )
        val database = CoinSafeBoxDatabase(driver)
        database.transaction {
            insertWallet(driver, 1, "Committed", byteArrayOf(1), null)
        }
        assertEquals(1L, countWallets(driver))

        database.transaction {
            insertWallet(driver, 2, "Rolled back", byteArrayOf(2), null)
            rollback()
        }
        assertEquals(1L, countWallets(driver))
        driver.close()
    }

    @Test
    fun listenersNotifiedOnNotify() {
        val dbFile = File(tempDir, "listeners.db")
        val driver = NativeSqlCipherDriver(
            dbPath = dbFile.absolutePath,
            key = "listener-key".toByteArray(),
            schema = CoinSafeBoxDatabase.Schema,
            migrateEmptySchema = true
        )

        var notified = false
        val listener = Query.Listener { notified = true }
        driver.addListener("wallets", listener = listener)
        driver.notifyListeners("wallets")
        assertTrue(notified)
        driver.removeListener("wallets", listener = listener)
        driver.close()
    }

    @Test
    fun driverImplementingSqlDriverCanBeUsedAsDatabase() {
        val dbFile = File(tempDir, "database.db")
        val driver: SqlDriver = NativeSqlCipherDriver(
            dbPath = dbFile.absolutePath,
            key = "db-key".toByteArray(),
            schema = CoinSafeBoxDatabase.Schema,
            migrateEmptySchema = true
        )
        val database = CoinSafeBoxDatabase(driver)
        // The generated transacter runs inside a transaction — exercises the
        // newTransaction/currentTransaction/endTransaction path end to end.
        database.transaction {
            insertWallet(driver, 1, "Tx", byteArrayOf(1), null)
        }
        assertEquals(1L, countWallets(driver))
        driver.close()
    }

    private fun userVersion(driver: SqlDriver): Long = driver.executeQuery(
        null,
        "PRAGMA user_version",
        { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: -1L)
        },
        0
    ).value

    private fun countWcSessions(driver: SqlDriver): Long = driver.executeQuery(
        null,
        "SELECT count(*) FROM wc_sessions",
        { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: -1L)
        },
        0
    ).value

    /**
     * The schema the released app created: the four core tables at version 1 (the
     * SQLDelight default when no migrations are declared). Used to simulate the
     * database file a legacy install leaves behind.
     */
    private object LegacyV1Schema : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long get() = 1

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(null, """
                |CREATE TABLE wallets (
                |    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                |    name TEXT NOT NULL,
                |    master_seed BLOB,
                |    mnemonic BLOB,
                |    has_passphrase INTEGER NOT NULL DEFAULT 0
                |)
            """.trimMargin(), 0)
            driver.execute(null, """
                |CREATE TABLE accounts (
                |    id TEXT PRIMARY KEY NOT NULL,
                |    wallet_id INTEGER NOT NULL,
                |    name TEXT NOT NULL,
                |    amount TEXT NOT NULL,
                |    type TEXT NOT NULL,
                |    address TEXT,
                |    account_index INTEGER,
                |    derivation_path TEXT NOT NULL,
                |    params TEXT,
                |    symbol TEXT NOT NULL,
                |    parent_account_id TEXT,
                |    token_address TEXT,
                |    xpub TEXT,
                |    FOREIGN KEY (wallet_id) REFERENCES wallets(id) ON DELETE CASCADE,
                |    FOREIGN KEY (parent_account_id) REFERENCES accounts(id) ON DELETE CASCADE
                |)
            """.trimMargin(), 0)
            driver.execute(null, "CREATE INDEX idx_accounts_parent ON accounts(parent_account_id)", 0)
            driver.execute(null, """
                |CREATE TABLE utxos (
                |    id              INTEGER PRIMARY KEY,
                |    account_id      TEXT NOT NULL,
                |    derivation_path TEXT NOT NULL,
                |    amount          INTEGER NOT NULL,
                |    txid            TEXT NOT NULL,
                |    vout            INTEGER NOT NULL,
                |    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
                |)
            """.trimMargin(), 0)
            driver.execute(null, """
                |CREATE TABLE transactions (
                |    id TEXT PRIMARY KEY NOT NULL,
                |    account_id TEXT NOT NULL,
                |    tx_hash TEXT NOT NULL,
                |    direction TEXT NOT NULL,
                |    amount TEXT NOT NULL,
                |    fee TEXT,
                |    timestamp INTEGER NOT NULL,
                |    status TEXT NOT NULL DEFAULT 'CONFIRMED',
                |    counterparty_address TEXT,
                |    block_height INTEGER,
                |    chain_data TEXT,
                |    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
                |)
            """.trimMargin(), 0)
            driver.execute(null, "CREATE UNIQUE INDEX idx_transactions_tx_hash_account_id ON transactions(tx_hash, account_id)", 0)
            driver.execute(null, "CREATE INDEX idx_transactions_account_timestamp ON transactions(account_id, timestamp DESC)", 0)
            return QueryResult.Unit
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
    }

    @Test
    fun freshDatabaseReportsCurrentSchemaVersion() {
        val dbFile = File(tempDir, "fresh-version.db")
        val driver = NativeSqlCipherDriver(
            dbPath = dbFile.absolutePath,
            key = "fresh-key".toByteArray(),
            schema = CoinSafeBoxDatabase.Schema,
            migrateEmptySchema = true
        )
        try {
            assertEquals(2L, userVersion(driver))
        } finally {
            driver.close()
        }
    }

    @Test
    fun migratesLegacyV1DatabaseToCurrentSchema() {
        val dbFile = File(tempDir, "migrate.db")
        val key = "migrate-key".toByteArray()

        // Phase 1: leave a database exactly as the released app created it — the
        // four core tables, user_version 1, and one wallet row.
        val legacy = NativeSqlCipherDriver(
            dbPath = dbFile.absolutePath,
            key = key,
            schema = LegacyV1Schema,
            migrateEmptySchema = true
        )
        insertWallet(legacy, 1, "Legacy wallet", byteArrayOf(1, 2, 3), "legacy")
        assertEquals(1L, userVersion(legacy))
        legacy.close()

        // Phase 2: open with the current schema — the driver must migrate 1 -> 2.
        val driver = NativeSqlCipherDriver(
            dbPath = dbFile.absolutePath,
            key = key,
            schema = CoinSafeBoxDatabase.Schema,
            migrateEmptySchema = false
        )
        try {
            assertEquals(2L, userVersion(driver))
            // Legacy data survives the migration.
            assertEquals(1L, countWallets(driver))
            // The new wc_sessions table now exists (empty) — querying it would
            // throw if the migration had not run.
            assertEquals(0L, countWcSessions(driver))
        } finally {
            driver.close()
        }

        // Phase 3: reopen the migrated DB — no re-migration, still consistent.
        val reopened = NativeSqlCipherDriver(
            dbPath = dbFile.absolutePath,
            key = key,
            schema = CoinSafeBoxDatabase.Schema,
            migrateEmptySchema = false
        )
        try {
            assertEquals(2L, userVersion(reopened))
            assertEquals(1L, countWallets(reopened))
        } finally {
            reopened.close()
        }
    }
}