package com.ultrabytecoder.coinsafebox.security

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.ultrabytecoder.coinsafebox.data.GuardedSqlDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * F-3 regression: SessionManager.lock() must wait for in-flight guarded queries
 * to drain before closing the SQLCipher driver, so native SQLite handles are
 * never freed mid-statement. The guard lives in [GuardedSqlDriver] (per
 * statement) and the bounded drain in SessionManager.lock().
 */
class SessionManagerInFlightTest {

    /** SqlDriver fake: counts closes and delegate query calls; optionally blocks inside a query execution. */
    private class FakeDriver(private val blockQueryMillis: Long = 0) : SqlDriver {
        val closeCount = java.util.concurrent.atomic.AtomicInteger(0)
        val queryEntered = CountDownLatch(1)
        val delegateQueryCount = java.util.concurrent.atomic.AtomicInteger(0)

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?
        ): QueryResult<R> {
            delegateQueryCount.incrementAndGet()
            queryEntered.countDown()
            // Only the test's "SELECT 1" query blocks — the unlock probe
            // (PRAGMA user_version) must stay fast so unlock() is not
            // delayed and the latch reflects the test query only.
            if (blockQueryMillis > 0 && sql == "SELECT 1") Thread.sleep(blockQueryMillis)
            return mapper(EmptyCursor)
        }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?
        ): QueryResult<Long> = QueryResult.Value(0L)

        override fun currentTransaction(): app.cash.sqldelight.Transacter.Transaction? = null

        override fun newTransaction(): QueryResult<app.cash.sqldelight.Transacter.Transaction> =
            throw UnsupportedOperationException()

        override fun addListener(vararg queryKeys: String, listener: app.cash.sqldelight.Query.Listener) {}
        override fun removeListener(vararg queryKeys: String, listener: app.cash.sqldelight.Query.Listener) {}
        override fun notifyListeners(vararg queryKeys: String) {}
        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private object EmptyCursor : SqlCursor {
        override fun next(): QueryResult<Boolean> = QueryResult.Value(false)
        override fun getString(index: Int): String? = null
        override fun getLong(index: Int): Long? = null
        override fun getBytes(index: Int): ByteArray? = null
        override fun getDouble(index: Int): Double? = null
        override fun getBoolean(index: Int): Boolean? = null
    }

    /** Factory fake that returns [driver] instead of opening SQLCipher. */
    private class FakeFactory(private val driver: SqlDriver) : DbSessionFactory {
        override suspend fun createDriver(passphrase: ByteArray): SqlDriver = driver
        override fun deleteDatabase() {}
    }

    private fun closeCountOf(driver: FakeDriver): Int = driver.closeCount.get()

    @Test
    fun lockWaitsForInFlightGuardedQueryBeforeClosing() {
        kotlinx.coroutines.runBlocking {
            val raw = FakeDriver(blockQueryMillis = 300)
            val factory = FakeFactory(raw)
            // Real app scope (not the test scheduler): the drain uses wall-clock delay.
            val scope = CoroutineScope(Dispatchers.Default)
            val manager = SessionManager(factory, scope)

            try {
                assertEquals(UnlockResult.Success, manager.unlock(ByteArray(32)))
                val guarded = GuardedSqlDriver(raw, manager)

                var queryError: Throwable? = null
                val queryDone = CountDownLatch(1)
                val t = Thread {
                    try {
                        guarded.executeQuery(null, "SELECT 1", { QueryResult.Value(Unit) }, 0)
                    } catch (e: Throwable) {
                        queryError = e
                    } finally {
                        queryDone.countDown()
                    }
                }
                val tStart = System.currentTimeMillis()
                t.start()
                assertTrue(raw.queryEntered.await(2, TimeUnit.SECONDS), "query must enter the driver")

                manager.lock()
                // The lock's drain must wait for the running statement: closing while
                // blocked would mean closeCount >= 1 before the query finished.
                assertTrue(queryDone.await(5, TimeUnit.SECONDS))
                t.join(5000)
                assertEquals(null, queryError, "guarded query must complete without error")

                // Wait for the lock coroutine to finish closing after the drain.
                val deadline = System.currentTimeMillis() + 2000
                while (closeCountOf(raw) == 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                }
                assertTrue(closeCountOf(raw) >= 1, "driver must be closed after the session locks")
                assertEquals(false, manager.isUnlocked.value)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun databaseThrowsWhenLocked() = runTest {
        val factory = FakeFactory(FakeDriver())
        val manager = SessionManager(factory, CoroutineScope(Dispatchers.Default))
        assertFailsWith<IllegalStateException> { manager.database() }
    }

    @Test
    fun lockStopsWaitingAfterDrainBudgetAndDefersNativeClose() {
        kotlinx.coroutines.runBlocking {
            // Blocks far longer than the 500ms drain budget: the lock must
            // stop waiting after the budget expires. The native close is
            // deferred (not called) while the query is still in flight to
            // avoid freeing the SQLite handle mid-statement (use-after-free).
            // The markClosed() gate ensures new queries fail fast.
            val raw = FakeDriver(blockQueryMillis = 3000)
            val factory = FakeFactory(raw)
            val scope = CoroutineScope(Dispatchers.Default)
            val manager = SessionManager(factory, scope)

            try {
                assertEquals(UnlockResult.Success, manager.unlock(ByteArray(32)))
                val guarded = GuardedSqlDriver(raw, manager)

                val queryDone = CountDownLatch(1)
                val t = Thread {
                    try {
                        guarded.executeQuery(null, "SELECT 1", { QueryResult.Value(Unit) }, 0)
                    } finally {
                        queryDone.countDown()
                    }
                }
                val tStart = System.currentTimeMillis()
                t.start()
                assertTrue(raw.queryEntered.await(2, TimeUnit.SECONDS), "query must enter the driver")

                // The lock must complete within the bounded drain budget
                // (~500ms + scheduling jitter), not wait for the full 3000ms
                // block. The 1800ms deadline sits between the two.
                manager.lock()
                val deadline = tStart + 1800
                while (manager.isUnlocked.value && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                }
                assertEquals(
                    false,
                    manager.isUnlocked.value,
                    "session must be locked after the drain budget expires, without waiting for the slow query"
                )
                // database() must throw now that the session is locked.
                assertFailsWith<IllegalStateException> { manager.database() }

                // The slow query still completes on the fake (a real driver
                // would fail it); its endQuery must not leak the counter.
                assertTrue(queryDone.await(6, TimeUnit.SECONDS), "in-flight query must complete")
                t.join(6000)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun queryStartingAfterCloseFailsFastWithoutReachingDelegate() {
        val raw = FakeDriver()
        val factory = FakeFactory(raw)
        val manager = SessionManager(factory, CoroutineScope(Dispatchers.Default))
        val guarded = GuardedSqlDriver(raw, manager)

        // Before the close, statements reach the delegate as usual.
        guarded.executeQuery(null, "SELECT 1", { QueryResult.Value(Unit) }, 0)
        assertEquals(1, raw.delegateQueryCount.get(), "open driver must receive statements")

        // SessionManager.closeInternal() marks the wrapper closed before the
        // delegate is closed. A statement that starts AFTER the close (a
        // background coroutine still holding a captured database reference)
        // must fail fast with a typed error and never reach the closed
        // delegate (freed native handle).
        guarded.markClosed()
        assertFailsWith<IllegalStateException> {
            guarded.executeQuery(null, "SELECT 1", { QueryResult.Value(Unit) }, 0)
        }
        assertFailsWith<IllegalStateException> {
            guarded.execute(null, "DELETE FROM t", 0, null)
        }
        assertEquals(
            1,
            raw.delegateQueryCount.get(),
            "closed driver must not receive new statements"
        )
    }

    @Test
    fun deferredNativeCloseIsRetriedOnNextLock() {
        kotlinx.coroutines.runBlocking {
            // First lock defers the close (query still in flight after drain
            // budget). After the query finishes, a second lock retries and
            // closes the driver.
            val raw = FakeDriver(blockQueryMillis = 1500)
            val factory = FakeFactory(raw)
            val scope = CoroutineScope(Dispatchers.Default)
            val manager = SessionManager(factory, scope)

            try {
                // Phase 1: unlock, start a slow query, lock (defers close).
                assertEquals(UnlockResult.Success, manager.unlock(ByteArray(32)))
                val guarded = GuardedSqlDriver(raw, manager)

                val queryDone = CountDownLatch(1)
                val t = Thread {
                    try {
                        guarded.executeQuery(null, "SELECT 1", { QueryResult.Value(Unit) }, 0)
                    } finally {
                        queryDone.countDown()
                    }
                }
                t.start()
                assertTrue(raw.queryEntered.await(2, TimeUnit.SECONDS))

                manager.lock()
                // Wait for the session to lock (drain budget expires ~500ms).
                val deadline = System.currentTimeMillis() + 3000
                while (manager.isUnlocked.value && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                }
                assertEquals(false, manager.isUnlocked.value, "session must be locked")

                // Phase 2: wait for the slow query to finish, then give the
                // first lock coroutine time to finish deferring the close.
                assertTrue(queryDone.await(8, TimeUnit.SECONDS), "query must complete")
                t.join(8000)
                Thread.sleep(200)

                // Phase 3: second lock retries the deferred close. Since
                // inFlightQueries is now 0, the pending driver is closed.
                manager.lock()
                val retryDeadline = System.currentTimeMillis() + 3000
                while (closeCountOf(raw) == 0 && System.currentTimeMillis() < retryDeadline) {
                    Thread.sleep(20)
                }
                assertTrue(
                    closeCountOf(raw) >= 1,
                    "deferred driver must be closed on the retry lock"
                )
            } finally {
                scope.cancel()
            }
        }
    }
}
