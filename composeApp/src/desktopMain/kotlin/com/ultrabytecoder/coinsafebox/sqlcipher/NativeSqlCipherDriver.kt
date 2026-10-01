package com.ultrabytecoder.coinsafebox.sqlcipher

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.ultrabytecoder.coinsafebox.security.wipe
import java.nio.charset.StandardCharsets

/**
 * Thrown when the SQLCipher database cannot be opened with the given key
 * ("file is not a database" on an existing encrypted file).
 */
class WrongPassphraseException(message: String) : Exception(message)

/**
 * Thrown when the database file exists but is not a valid SQLCipher database
 * (corruption, not a wrong key).
 */
class DatabaseCorruptException(message: String) : Exception(message)

/**
 * Synchronous SQLDelight [SqlDriver] over the bundled SQLCipher C library.
 *
 * Mirrors the semantics of `app.cash.sqldelight.driver.jdbc.JdbcDriver`:
 *  - one connection for the driver lifetime;
 *  - transactions are tracked per-thread (the transacter runs transaction bodies
 *    synchronously, so a body never migrates threads mid-transaction);
 *  - all calls are serialized on the driver (SQLCipher is built with
 *    SQLITE_THREADSAFE=1 and a single connection is not safe to share).
 *
 * Key handling: [key] is copied and the copy is zeroed immediately after
 * `sqlite3_key_v2` (SQLCipher derives its KEK during that call and does not
 * retain the input buffer). The key is verified with `SELECT count(*)
 * FROM sqlite_master` so a wrong passphrase fails fast with
 * [WrongPassphraseException] — an unencrypted fallback is never possible.
 */
class NativeSqlCipherDriver(
    private val dbPath: String,
    key: ByteArray,
    private val schema: SqlSchema<QueryResult.Value<Unit>>,
    migrateEmptySchema: Boolean = true,
) : SqlDriver {

    private val sqlite = SqlCipherNative.lib

    private val listeners = linkedMapOf<String, MutableSet<Query.Listener>>()
    private val transactions = ThreadLocal<SqlCipherTransaction>()

    private var db: Pointer
    private var closed = false

    init {
        val databaseFileExists = java.io.File(dbPath).exists()
        val keyCopy = key.copyOf()
        try {
            db = openAndKey(dbPath, keyCopy)
        } finally {
            keyCopy.wipe()
        }

        exec("PRAGMA journal_mode = WAL")
        exec("PRAGMA foreign_keys = ON")

        val oldVersion = queryUserVersion()
        val createdOrMigrated = when {
            migrateEmptySchema && !databaseFileExists -> {
                schema.create(this)
                true
            }
            oldVersion < schema.version -> {
                schema.migrate(this, oldVersion, schema.version)
                true
            }
            else -> false
        }
        // The generated create()/migrate() leave PRAGMA user_version untouched, so
        // record the schema version here. Without this, a freshly-created DB would
        // still report version 0 on the next launch and the driver would re-run the
        // migration, crashing on "table already exists". (The Android/iOS drivers
        // get this from their OpenHelper equivalent.)
        if (createdOrMigrated) {
            setUserVersion(schema.version)
        }
    }

    private fun openAndKey(path: String, key: ByteArray): Pointer {
        val dbRef = PointerByReference()
        val openRc = sqlite.sqlite3_open_v2(
            path,
            dbRef,
            SqlCipherNative.SQLITE_OPEN_READWRITE or SqlCipherNative.SQLITE_OPEN_CREATE or SqlCipherNative.SQLITE_OPEN_NOMUTEX,
            null
        )
        if (openRc != SqlCipherNative.SQLITE_OK) {
            val message = dbRef.value?.let { sqlite.sqlite3_errmsg(it) } ?: "open failed"
            if (dbRef.value != null) sqlite.sqlite3_close_v2(dbRef.value)
            throw IllegalStateException("SQLCipher open failed: $message")
        }
        val dbHandle = dbRef.value

        // Pin the cipher parameters BEFORE sqlite3_key_v2: the KDF iteration count,
        // KDF algorithm, HMAC algorithm, and HMAC flag all affect key derivation,
        // which happens inside sqlite3_key_v2, so they must be set first to take
        // effect. Use the current-database PRAGMA names (cipher_use_hmac /
        // cipher_kdf_iter / cipher_kdf_algorithm / cipher_hmac_algorithm) — the
        // *_default_* variants only set process-wide defaults for FUTURE opens and
        // have no effect on this already-open handle. (SQLCipher 4 defaults match
        // these values; setting them explicitly is audit-friendly and future-proof
        // against SQLCipher 5 default changes.)
        try {
            execOnHandle(dbHandle, "PRAGMA cipher_use_hmac = 1;")
            execOnHandle(dbHandle, "PRAGMA cipher_page_size = 4096;")
            execOnHandle(dbHandle, "PRAGMA cipher_kdf_iter = 256000;")
            execOnHandle(dbHandle, "PRAGMA cipher_kdf_algorithm = 2;")   // PBKDF2_HMAC_SHA512
            execOnHandle(dbHandle, "PRAGMA cipher_hmac_algorithm = 2;")  // HMAC_SHA512
        } catch (e: Exception) {
            // A PRAGMA failure must not leak the database handle.
            sqlite.sqlite3_close_v2(dbHandle)
            throw e
        }

        val keyMem = Memory(key.size.toLong()).apply { write(0, key, 0, key.size) }
        try {
            val keyRc = sqlite.sqlite3_key_v2(dbHandle, null, keyMem, key.size)
            if (keyRc != SqlCipherNative.SQLITE_OK) {
                val message = sqlite.sqlite3_errmsg(dbHandle)
                sqlite.sqlite3_close_v2(dbHandle)
                throw IllegalStateException("SQLCipher key rejected: $message")
            }
        } finally {
            keyMem.clear()
        }

        // Verify the key now: on an existing file a wrong key makes every read
        // fail with "file is not a database"; surface it as a typed exception.
        val errRef = PointerByReference()
        val verifyRc = sqlite.sqlite3_exec(
            dbHandle,
            "SELECT count(*) FROM sqlite_master",
            null,
            null,
            errRef
        )
        if (verifyRc != SqlCipherNative.SQLITE_OK) {
            val errCode = sqlite.sqlite3_errcode(dbHandle)
            val message = errRef.value?.getString(0, StandardCharsets.UTF_8.name())
                ?: sqlite.sqlite3_errmsg(dbHandle)
            errRef.value?.let { sqlite.sqlite3_free(it) }
            sqlite.sqlite3_close_v2(dbHandle)
            if (errCode == SqlCipherNative.SQLITE_NOTADB) {
                throw WrongPassphraseException(message)
            }
            throw DatabaseCorruptException("Database corruption (code $errCode): $message")
        }
        return dbHandle
    }

    private fun execOnHandle(handle: Pointer, sql: String) {
        val errRef = PointerByReference()
        val rc = sqlite.sqlite3_exec(handle, sql, null, null, errRef)
        if (rc != SqlCipherNative.SQLITE_OK) {
            val message = errRef.value?.getString(0, StandardCharsets.UTF_8.name())
                ?: sqlite.sqlite3_errmsg(handle)
            errRef.value?.let { sqlite.sqlite3_free(it) }
            // A failed PRAGMA (e.g. typo, unsupported parameter) must not be
            // silently ignored — the database would proceed with default cipher
            // parameters, potentially corrupting it or failing to open it.
            throw IllegalStateException("SQLCipher PRAGMA failed: $message ($sql)")
        }
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = synchronized(this) {
        ensureOpen()
        val stmtRef = prepare(sql)
        val stmt = stmtRef.value
        var ps: NativePreparedStatement? = null
        try {
            if (binders != null) {
                ps = NativePreparedStatement(stmt, sqlite)
                ps.binders()
            }
            mapper(NativeSqliteCursor(stmt, sqlite, db))
        } finally {
            ps?.clearRetained()
            sqlite.sqlite3_finalize(stmt)
        }
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> = synchronized(this) {
        ensureOpen()
        val stmtRef = prepare(sql)
        val stmt = stmtRef.value
        var ps: NativePreparedStatement? = null
        try {
            if (binders != null) {
                ps = NativePreparedStatement(stmt, sqlite)
                ps.binders()
            }
            stepToDone(stmt)
            QueryResult.Value(sqlite.sqlite3_changes64(db))
        } finally {
            ps?.clearRetained()
            sqlite.sqlite3_finalize(stmt)
        }
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> = synchronized(this) {
        ensureOpen()
        val enclosing = transactions.get()
        val transaction = SqlCipherTransaction(this, enclosing)
        transactions.set(transaction)
        if (enclosing == null) {
            exec("BEGIN IMMEDIATE")
        }
        QueryResult.Value(transaction)
    }

    override fun currentTransaction(): Transacter.Transaction? = transactions.get()

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners.getOrPut(it) { linkedSetOf() }.add(listener) }
        }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners[it]?.remove(listener) }
        }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val listenersToNotify = linkedSetOf<Query.Listener>()
        synchronized(listeners) {
            queryKeys.forEach { listeners[it]?.let(listenersToNotify::addAll) }
        }
        listenersToNotify.forEach(Query.Listener::queryResultsChanged)
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            sqlite.sqlite3_close_v2(db)
        }
    }

    private fun endTransaction(transaction: SqlCipherTransaction, successful: Boolean) {
        synchronized(this) {
            ensureOpen()
            if (transaction.enclosing == null) {
                if (successful) exec("COMMIT") else exec("ROLLBACK")
            }
            transactions.set(transaction.enclosing)
        }
    }

    private fun prepare(sql: String): PointerByReference {
        val stmtRef = PointerByReference()
        val rc = sqlite.sqlite3_prepare_v2(db, sql, -1, stmtRef, null)
        if (rc != SqlCipherNative.SQLITE_OK) {
            throw IllegalStateException("SQL prepare failed (${sqlite.sqlite3_errmsg(db)}): $sql")
        }
        return stmtRef
    }

    private fun stepToDone(stmt: Pointer) {
        val rc = sqlite.sqlite3_step(stmt)
        when (rc) {
            SqlCipherNative.SQLITE_DONE -> Unit
            SqlCipherNative.SQLITE_ROW -> Unit // INSERT OR IGNORE etc. may return rows; JDBC treats as 0 changes
            else -> throw IllegalStateException("SQL execution failed: ${sqlite.sqlite3_errmsg(db)}")
        }
    }

    private fun exec(sql: String) {
        val errRef = PointerByReference()
        val rc = sqlite.sqlite3_exec(db, sql, null, null, errRef)
        if (rc != SqlCipherNative.SQLITE_OK) {
            val message = errRef.value?.getString(0, StandardCharsets.UTF_8.name()) ?: sqlite.sqlite3_errmsg(db)
            errRef.value?.let { sqlite.sqlite3_free(it) }
            throw IllegalStateException("SQLCipher exec failed: $message ($sql)")
        }
    }

    private fun queryUserVersion(): Long {
        val stmtRef = prepare("PRAGMA user_version")
        val stmt = stmtRef.value
        try {
            return if (sqlite.sqlite3_step(stmt) == SqlCipherNative.SQLITE_ROW) {
                sqlite.sqlite3_column_int64(stmt, 0)
            } else {
                0L
            }
        } finally {
            sqlite.sqlite3_finalize(stmt)
        }
    }

    private fun setUserVersion(version: Long) {
        exec("PRAGMA user_version = $version")
    }

    private fun ensureOpen() {
        check(!closed) { "SQLCipher driver is closed" }
    }

    private class SqlCipherTransaction(
        private val driver: NativeSqlCipherDriver,
        val enclosing: SqlCipherTransaction?,
    ) : Transacter.Transaction() {
        override val enclosingTransaction: Transacter.Transaction? get() = enclosing

        override fun endTransaction(successful: Boolean): QueryResult<Unit> {
            driver.endTransaction(this, successful)
            return QueryResult.Unit
        }
    }
}

private class NativePreparedStatement(
    private val stmt: Pointer,
    private val sqlite: Sqlite3,
) : SqlPreparedStatement {

    private val retained = mutableListOf<Memory>()

    /** Zeroes and releases every JNA [Memory] backing a bound BLOB/TEXT parameter. */
    fun clearRetained() {
        retained.forEach { it.clear() }
        retained.clear()
    }

    override fun bindBytes(index: Int, bytes: ByteArray?) {
        if (bytes == null) {
            bindNull(index)
            return
        }
        val mem = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
        retained += mem
        val rc = sqlite.sqlite3_bind_blob(stmt, index + 1, mem, bytes.size, SqlCipherNative.SQLITE_TRANSIENT)
        checkBind(rc)
    }

    override fun bindLong(index: Int, long: Long?) {
        if (long == null) {
            bindNull(index)
            return
        }
        checkBind(sqlite.sqlite3_bind_int64(stmt, index + 1, long))
    }

    override fun bindDouble(index: Int, double: Double?) {
        if (double == null) {
            bindNull(index)
            return
        }
        checkBind(sqlite.sqlite3_bind_double(stmt, index + 1, double))
    }

    override fun bindString(index: Int, string: String?) {
        if (string == null) {
            bindNull(index)
            return
        }
        val utf8 = string.toByteArray(StandardCharsets.UTF_8)
        val mem = Memory((utf8.size + 1).toLong()).apply { write(0, utf8, 0, utf8.size) }
        retained += mem
        val rc = sqlite.sqlite3_bind_text(stmt, index + 1, mem, utf8.size, SqlCipherNative.SQLITE_TRANSIENT)
        checkBind(rc)
    }

    override fun bindBoolean(index: Int, boolean: Boolean?) {
        if (boolean == null) {
            bindNull(index)
            return
        }
        checkBind(sqlite.sqlite3_bind_int(stmt, index + 1, if (boolean) 1 else 0))
    }

    private fun bindNull(index: Int) {
        checkBind(sqlite.sqlite3_bind_null(stmt, index + 1))
    }

    private fun checkBind(rc: Int) {
        check(rc == SqlCipherNative.SQLITE_OK) {
            "SQL bind failed (rc=$rc, err=${sqlite.sqlite3_errmsg(sqlite.sqlite3_db_handle(stmt))})"
        }
    }
}

private class NativeSqliteCursor(
    private val stmt: Pointer,
    private val sqlite: Sqlite3,
    private val db: Pointer,
) : SqlCursor {

    private var onRow = false

    override fun next(): QueryResult<Boolean> {
        val rc = sqlite.sqlite3_step(stmt)
        onRow = when (rc) {
            SqlCipherNative.SQLITE_ROW -> true
            SqlCipherNative.SQLITE_DONE -> false
            else -> throw IllegalStateException("SQL query failed: ${sqlite.sqlite3_errmsg(db)}")
        }
        return QueryResult.Value(onRow)
    }

    override fun getString(index: Int): String? {
        if (isNull(index)) return null
        return sqlite.sqlite3_column_text(stmt, index).getString(0, StandardCharsets.UTF_8.name())
    }

    override fun getLong(index: Int): Long? {
        if (isNull(index)) return null
        return sqlite.sqlite3_column_int64(stmt, index)
    }

    override fun getBytes(index: Int): ByteArray? {
        if (isNull(index)) return null
        val size = sqlite.sqlite3_column_bytes(stmt, index)
        if (size <= 0) return ByteArray(0)
        return sqlite.sqlite3_column_blob(stmt, index).getByteArray(0, size)
    }

    override fun getDouble(index: Int): Double? {
        if (isNull(index)) return null
        return sqlite.sqlite3_column_double(stmt, index)
    }

    override fun getBoolean(index: Int): Boolean? {
        if (isNull(index)) return null
        return sqlite.sqlite3_column_int(stmt, index) != 0
    }

    private fun isNull(index: Int): Boolean {
        if (!onRow) return true
        return sqlite.sqlite3_column_type(stmt, index) == SqlCipherNative.SQLITE_NULL
    }
}