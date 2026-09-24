package com.ultrabytecoder.coinsafebox.data

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.ultrabytecoder.coinsafebox.security.AtomicCounter
import com.ultrabytecoder.coinsafebox.security.SessionManager

/**
 * Wraps the session's [SqlDriver] so every statement execution is bracketed by
 * SessionManager's in-flight query guard. The session-lock drain waits for the
 * guard to reach zero before closing the SQLCipher driver, which makes it safe
 * for background coroutines (sync workers, reactive flow re-executions) to keep
 * using a captured database reference across a lock request: their statements
 * either finish before the close, or fail fast after it — never mid-flight on a
 * freed native handle.
 *
 * Fail-fast gate: [markClosed] is invoked by SessionManager on this exact
 * wrapper before the delegate is closed. A statement that starts AFTER the
 * close (a background coroutine still holding a captured database reference)
 * sees [closed] and throws [IllegalStateException] instead of executing on a
 * freed native handle. The check is taken around the in-flight increment:
 * an increment that lands after the close is immediately rolled back, so the
 * drain can still observe zero.
 *
 * Statement-level guarding is the contract: a lock that lands BETWEEN
 * statements of an open transaction closes the driver mid-transaction, which
 * is safe — SQLite rolls back the open transaction on close, and the
 * transaction's remaining statements then fail with the closed-driver error.
 */
class GuardedSqlDriver(
    private val delegate: SqlDriver,
    private val sessionManager: SessionManager
) : SqlDriver {

    private val closed = AtomicCounter(0)

    /** Called by SessionManager on this wrapper before the delegate is closed. */
    internal fun markClosed() {
        closed.incrementAndGet()
    }

    /**
     * Increments the in-flight guard unless this wrapper is already closed.
     * Returns false (and leaves the counter untouched) when closed, so the
     * caller must fail fast instead of executing on a freed native handle.
     */
    private fun tryBeginQuery(): Boolean {
        if (closed.get() != 0) return false
        sessionManager.beginQuery()
        if (closed.get() != 0) {
            // The close landed between the two reads: roll back the increment
            // (no statement will run, so the drain can still reach zero) and
            // fail fast.
            sessionManager.endQuery()
            return false
        }
        return true
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<R> {
        if (!tryBeginQuery()) throw IllegalStateException("Database is locked")
        try {
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        } finally {
            sessionManager.endQuery()
        }
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<Long> {
        if (!tryBeginQuery()) throw IllegalStateException("Database is locked")
        try {
            return delegate.execute(identifier, sql, parameters, binders)
        } finally {
            sessionManager.endQuery()
        }
    }

    override fun currentTransaction() = delegate.currentTransaction()

    /**
     * Fail-fast gate: refuse to start a new transaction after the driver is
     * marked closed. Statements executed through the returned [Transacter.Transaction]
     * bypass the per-statement guard (they do not go through [executeQuery]/[execute]),
     * so the only protection for transaction-scoped statements is this entry-point
     * check. A transaction started before [markClosed] may still run its remaining
     * statements after close; SQLite rolls back the open transaction on close, and
     * any subsequent statement fails with the closed-driver error.
     */
    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        if (closed.get() != 0) throw IllegalStateException("Database is locked")
        return delegate.newTransaction()
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) =
        delegate.addListener(*queryKeys, listener = listener)

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) =
        delegate.removeListener(*queryKeys, listener = listener)

    override fun notifyListeners(vararg queryKeys: String) =
        delegate.notifyListeners(*queryKeys)

    override fun close() {
        // closeInternal() calls markClosed() before close(), so the gate is
        // already armed. Do NOT call markClosed() here again — it would
        // double-increment the counter. If close() is reached from a path
        // other than closeInternal (e.g. a future refactor), the gate is
        // still armed because closeInternal() runs first in every known path.
        delegate.close()
    }
}
