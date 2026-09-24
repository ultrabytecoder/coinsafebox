package com.ultrabytecoder.coinsafebox.security

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.ultrabytecoder.coinsafebox.data.DatabaseDriverFactory
import com.ultrabytecoder.coinsafebox.data.DatabaseProvider
import com.ultrabytecoder.coinsafebox.data.GuardedSqlDriver
import com.ultrabytecoder.coinsafebox.db.CoinSafeBoxDatabase
import com.ultrabytecoder.coinsafebox.security.monotonicNowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.Volatile


/**
 * Result of [SessionManager.unlock]. Distinguishes a successful unlock from a
 * failed unlock (wrong key / corrupt DB) from a transient lock request (the
 * session was locked while the driver was opening). The caller must handle
 * [Locked] differently from [Failed]: a transient lock is not a corruption and
 * should not route the user to recovery.
 */
sealed interface UnlockResult {
    /** The database was opened successfully; the session is now unlocked. */
    data object Success : UnlockResult

    /** The database could not be opened (wrong key, corrupt file, etc.). */
    data object Failed : UnlockResult

    /** A lock was requested while the driver was opening; the session remains
     * locked. The caller should prompt the user to try again, NOT route to
     * recovery. */
    data object Locked : UnlockResult
}

/**
 * Holds the session state: the raw DEK in memory and the opened SQLCipher database.
 *
 * Ownership of the DEK array:
 *  - On success, [unlock] takes ownership — the caller must NOT wipe it afterwards;
 *    it is wiped by [lock] (H-3: Memory Wiping).
 *  - On failure, [unlock] leaves the array untouched — the caller owns it and must wipe it.
 *
 * Idle timeout (F-5): after [IDLE_TIMEOUT_MS] without [registerActivity] the session
 * locks itself. [appScope] must outlive the session (application scope).
 *
 * Concurrency: [stateLock] (a coroutine [Mutex]) serializes ALL state mutations —
 * both [unlock]/[unlockRecreating] and [lock]. A single lock is used so that
 * [lock] cannot interleave with [unlock]: with two independent locks, a [lock]
 * running while [unlock] was inside the driver open could either leave the
 * session unlocked after a lock was requested, or close the freshly-opened driver
 * and wipe the DEK that [unlock] had just handed to the caller.
 *
 * The lock is held only for the short state-check and state-commit phases. The
 * suspending [databaseDriverFactory.createDriver] call runs OUTSIDE the lock.
 * While the lock is released during the driver open, [lock] cannot run (it
 * suspends on the same mutex), so the race is still prevented: the commit phase
 * re-acquires the lock and re-checks [lockRequested] before publishing state.
 */
class SessionManager(
    private val databaseDriverFactory: DbSessionFactory,
    private val appScope: CoroutineScope
) : DatabaseProvider, com.ultrabytecoder.coinsafebox.data.SessionUnlocker {

    private companion object {
        const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L
        const val IDLE_CHECK_INTERVAL_MS = 1000L

        // Bounded drain budget for closing the driver while queries are in flight.
        const val CLOSE_DRAIN_POLL_MS = 5L
        const val CLOSE_DRAIN_TIMEOUT_MS = 500L
    }

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val stateLock = Mutex()

    // The published references are mutated under [stateLock] but read LOCKLESS
    // from arbitrary worker threads via database() (which also reads the
    // _isUnlocked StateFlow). @Volatile gives a safe publication on both JVM
    // and Kotlin/Native: a worker that observes _isUnlocked == true is
    // guaranteed to see the fully-published driver/database references, never
    // a stale null (which would spuriously report "Database is locked") or a
    // partially-visible object.
    @Volatile
    private var dek: ByteArray? = null

    @Volatile
    private var driver: GuardedSqlDriver? = null

    /**
     * Drivers whose native close was deferred because queries were still in
     * flight when the drain budget expired. Retried on the next [lock] or
     * [unlock] Phase 1. Holding these references (and the DEK) until the
     * native close actually runs prevents the key from lingering in an
     * un-closed SQLCipher connection after a "successful" lock.
     *
     * A list (not a single slot) so two rapid locks with overlapping in-flight
     * queries don't lose the first deferred driver.
     */
    @Volatile
    private var pendingCloses: List<GuardedSqlDriver> = emptyList()

    @Volatile
    private var database: CoinSafeBoxDatabase? = null

    /**
     * Number of query executions currently in flight on the published driver.
     * [database] increments it for the duration of a guarded query; [lock]'s
     * close path waits (without holding [stateLock]) for this to reach zero
     * before closing the SQLCipher driver, so native SQLite handles are never
     * freed while a statement is mid-execution (SIGSEGV / use-after-free).
     */
    private val inFlightQueries = AtomicCounter(0)


    private var lastActivity: Long = 0L

    private var idleJob: Job? = null

    /**
     * Set by [lock] (and NOT reset until the next [unlock] Phase 1). When [unlock]
     * reaches Phase 3 (after the suspending driver-open in Phase 2), it re-checks
     * this flag: if a [lock] ran while the lock was released during Phase 2, the
     * flag is true and the commit aborts, honouring the lock request.
     *
     * The flag is cleared in Phase 1 of [unlock]/[unlockRecreating] (when the
     * session is confirmed locked), so a stale flag from a previous lock() cannot
     * cause a permanent lockout.
     *
     * @Volatile: [lock] writes this BEFORE acquiring [stateLock] so an
     * [unlock] Phase 3 that is currently holding [stateLock] sees the write
     * without needing the mutex. The in-lock read in [unlock] Phase 3 still
     * works because @Volatile provides acquire semantics on the read.
     */
    @Volatile
    private var lockRequested = false

    /**
     * Guards [unlockRecreating] against concurrent calls. Set under [stateLock]
     * in Phase 1, cleared in Phase 3. A second call that finds this flag set
     * returns false immediately instead of creating a driver that the first
     * call's Phase 3 would close via closeInternal().
     *
     * In practice the caller (PinRepositoryImpl.setupPin) serializes calls via
     * its mutex, so this is a defensive invariant, not a hot path.
     */
    private var recreateInFlight = false

    /**
     * Records user activity, resetting the idle timeout. Uses the monotonic clock
     * (not wall-clock [System.currentTimeMillis]) so the timeout cannot be defeated
     * by moving the system clock backward — the same clock the lockout logic uses.
     */
    fun registerActivity() {
        lastActivity = monotonicNowMillis()
    }

    private fun startIdleWatcher() {
        idleJob?.cancel()
        idleJob = appScope.launch {
            while (true) {
                delay(IDLE_CHECK_INTERVAL_MS)
                if (_isUnlocked.value &&
                    monotonicNowMillis() - lastActivity >= IDLE_TIMEOUT_MS
                ) {
                    lock()
                    SessionLockNotifier.notifyLocked()
                }
            }
        }
    }

    /**
     * Opens the database with [dek] and keeps the key in memory for the session.
     *
     * Never deletes the database file: when the file cannot be opened with this key
     * (stale/corrupt database), unlock fails and the caller must route to the recovery
     * flow (PIN re-setup through the setup wizard), never to file deletion.
     */
    override suspend fun unlock(dek: ByteArray): UnlockResult {
        // Phase 1: check state under the lock. Clear the lockRequested flag (a
        // stale flag from a previous lock() must not abort this unlock). If the
        // session is locked, also clear stale driver/database/dek from a previous
        // crashed unlock so Phase 3's closeInternal() doesn't double-close.
        stateLock.lock()
        try {
            if (_isUnlocked.value) {
                // Session already open (e.g. PIN re-verified from Settings): keep the
                // driver untouched so active flows survive, only refresh the key.
                this.dek?.wipe()
                this.dek = dek
                registerActivity()
                return UnlockResult.Success
            }
            // Session is locked — clear the flag and any stale state.
            lockRequested = false
            closeInternal()
            registerActivity()
        } finally {
            stateLock.unlock()
        }

        // Phase 2: open the driver OUTSIDE the lock (createDriver is suspending).
        // lock() cannot run here because it suspends on stateLock.
        val rawDriver = try {
            databaseDriverFactory.createDriver(dek)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return UnlockResult.Failed
        }

        // Phase 3: commit under the lock. Re-check lockRequested: if lock() ran
        // while the lock was released during Phase 2, the flag is true and we
        // abort, honouring the lock request.
        stateLock.lock()
        try {
            if (lockRequested) {
                // A lock was requested while we were opening the driver — honour it.
                try { rawDriver.close() } catch (_: Exception) {}
                return UnlockResult.Locked
            }
            val newDriver = GuardedSqlDriver(rawDriver, this)
            val newDatabase = CoinSafeBoxDatabase(newDriver)
            probe(newDriver)
            closeInternal()
            driver = newDriver
            database = newDatabase
            this.dek = dek
            _isUnlocked.value = true
            startIdleWatcher()
            return UnlockResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try { rawDriver.close() } catch (_: Exception) {}
            return UnlockResult.Failed
        } finally {
            stateLock.unlock()
        }
    }

    /**
     * Opens the database, deleting the file and recreating it when it cannot be opened
     * with [dek]. Must only be used from the PIN setup wizard (fresh setup / recovery),
     * where no wallet data is at stake — the flag is not exposed through the public
     * [unlock] API on purpose.
     */
    override suspend fun unlockRecreating(dek: ByteArray): Boolean {
        // Phase 1: clear state under the lock. Do NOT return early if the session
        // is unlocked: setupPin generates a brand-new DEK, so the old database must
        // be closed and recreated. closeInternal() does not set _isUnlocked = false
        // (that is done explicitly in lock()), so set it here to keep the state
        // consistent while the driver is being recreated.
        //
        // The recreateInFlight flag prevents a second concurrent call from
        // creating a driver that this call's Phase 3 would close via
        // closeInternal(), orphaning the first caller's database reference.
        stateLock.lock()
        try {
            if (recreateInFlight) return false
            recreateInFlight = true
            lockRequested = false
            closeInternal()
            _isUnlocked.value = false
        } finally {
            stateLock.unlock()
        }

        // Phase 2 + 3: wrapped in a single try/finally so recreateInFlight is
        // cleared exactly once on every exit path — no per-catch-site bookkeeping.
        try {
            // Phase 2: open the driver OUTSIDE the lock (createDriver is
            // suspending). If the first attempt fails, delete the database
            // and retry once.
            var newDriver: SqlDriver? = null
            try {
                newDriver = databaseDriverFactory.createDriver(dek)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                try {
                    databaseDriverFactory.deleteDatabase()
                } catch (_: Exception) {
                    // deleteDatabase failed (e.g. file locked) — proceed to
                    // retry; createDriver will fail if the file still can't
                    // be opened.
                }
                try {
                    newDriver = databaseDriverFactory.createDriver(dek)
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    return false
                }
            }

            // Phase 3: commit under the lock. Re-check lockRequested.
            stateLock.lock()
            try {
                val raw = newDriver ?: return false
                if (lockRequested) {
                    try { raw.close() } catch (_: Exception) {}
                    return false
                }
                val d = GuardedSqlDriver(raw, this)
                val newDatabase = CoinSafeBoxDatabase(d)
                probe(d)
                closeInternal()
                driver = d
                database = newDatabase
                this.dek = dek
                _isUnlocked.value = true
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                try { newDriver?.close() } catch (_: Exception) {}
                return false
            } finally {
                stateLock.unlock()
            }
        } finally {
            recreateInFlight = false
        }
    }

    /**
     * Forces the SQLCipher key check against the on-disk file. On Android the driver
     * opens lazily; a wrong key surfaces here ("file is not a database") instead of
     * on the first query. On iOS the driver factory already probes eagerly.
     */
    private fun probe(driver: SqlDriver) {
        driver.executeQuery(null, "PRAGMA user_version;", { QueryResult.Value(Unit) }, 0)
    }

    /**
     * Locks the session: closes the DB connection, zeroes the DEK in memory (H-3)
     * and purges any in-process cache of the device hardware key (F-13).
     *
     * This is a non-suspending function called from the idle watcher coroutine and
     * from the main thread. It acquires the same [stateLock] as the suspending
     * [unlock]/[unlockRecreating], so a lock can never interleave with an in-flight
     * unlock (see the class-level concurrency note). The mutex is a coroutine
     * primitive, so the critical section runs in a child coroutine of [appScope]
     * (the caller is not blocked).
     */
    fun lock() {
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Fail-fast for new database() callers AND for any unlock() Phase 3
            // currently holding stateLock: both flags are written before we
            // acquire the mutex, so an in-flight unlock() sees them via
            // @Volatile / StateFlow acquire semantics and aborts its commit.
            // Re-asserted under the lock below.
            _isUnlocked.value = false
            lockRequested = true
            var locked = false
            try {
                stateLock.lock()
                locked = true
                // Re-assert under the lock: if unlock() Phase 3 published
                // _isUnlocked=true after our write above but before we acquired
                // the lock, it would otherwise survive closeInternal() below.
                _isUnlocked.value = false
                // lockRequested was set before the lock; re-assert here so the
                // value is definitely visible to any unlock() that acquires
                // stateLock after us (belt-and-suspenders with @Volatile).
                lockRequested = true
                // Bounded drain: wait for in-flight guarded queries to finish so
                // the driver can be closed without yanking native SQLite handles
                // out from under a running statement. If they do not drain within
                // the budget, closing proceeds — hung statements are already
                // broken by SQLCipher's own timeout handling, and refusing to
                // lock would keep the DEK and database open indefinitely.
                //
                // Holding stateLock across the drain+close also preserves the
                // invariant that close never interleaves with an unlock: a
                // successful acquire proves no unlock()/unlockRecreating() phase
                // is in flight. Deadlock-free by design: guarded query workers
                // never acquire stateLock, so draining can always complete.
                var waitedMillis = 0L
                while (inFlightQueries.get() > 0 && waitedMillis < CLOSE_DRAIN_TIMEOUT_MS) {
                    delay(CLOSE_DRAIN_POLL_MS)
                    waitedMillis += CLOSE_DRAIN_POLL_MS
                }
                // Do NOT reset lockRequested here: the next unlock() Phase 1
                // will clear it. If we reset it, an unlock() in Phase 3
                // (after the lock was released during Phase 2) would see
                // lockRequested == false and publish the unlocked state.
                closeInternal()
                HardwareKeyStore.purgeCache()
                idleJob?.cancel()
                idleJob = null
            } finally {
                if (locked) stateLock.unlock()
            }
        }
    }

    private fun closeInternal() {
        // Retry any previously deferred native closes: only safe when ALL
        // in-flight queries (shared counter across current + pending drivers)
        // have drained. If close throws, keep the reference for the next retry.
        if (pendingCloses.isNotEmpty() && inFlightQueries.get() == 0) {
            val stillPending = mutableListOf<GuardedSqlDriver>()
            for (pending in pendingCloses) {
                try {
                    pending.close()
                } catch (_: Exception) {
                    stillPending.add(pending)
                }
            }
            pendingCloses = stillPending
        }
        try {
            // Mark the GUARDED wrapper closed FIRST, then close it. After this
            // line any statement starting on this wrapper (a background
            // coroutine still holding a captured database reference) fails
            // fast in tryBeginQuery instead of reaching the native handle.
            driver?.markClosed()
            if (inFlightQueries.get() == 0) {
                driver?.close()
            } else {
                // Queries still in flight after the drain budget: defer the
                // native close to avoid freeing the SQLite handle
                // mid-statement (use-after-free / SIGSEGV). The driver is
                // kept in pendingCloses and retried on the next lock/unlock.
                // The DEK is NOT wiped until the native close actually runs,
                // so the key doesn't linger in an un-closed connection.
                if (driver != null) {
                    pendingCloses = pendingCloses + driver!!
                }
            }
        } catch (_: Exception) {
        }
        driver = null
        database = null
        // Only wipe the DEK when no unclosed native driver holds it.
        if (pendingCloses.isEmpty()) {
            dek?.wipe()
            dek = null
        }
    }

    override fun database(): CoinSafeBoxDatabase {
        // Read _isUnlocked FIRST: it is written last in unlock() Phase 3, so a
        // reader that sees true is guaranteed (via the StateFlow's AtomicReference
        // release/acquire) to also see the fully-published driver/database refs.
        // Reading database first would allow a worker to observe the new reference
        // before the unlock flag is published, or to observe a stale reference after
        // lock() has cleared it.
        if (!_isUnlocked.value) throw IllegalStateException("Database is locked")
        return database ?: throw IllegalStateException("Database is locked")
    }

    /**
     * Increments the in-flight query counter. Called by [GuardedSqlDriver] around
     * every statement so [lock]'s drain knows when it is safe to close the
     * driver. Pairs with the wrapper's closed-flag gate: an increment that the
     * wrapper rolls back (statement refused after close) is matched by an
     * [endQuery], so the counter always returns to its pre-statement value.
     */
    fun beginQuery() {
        inFlightQueries.incrementAndGet()
    }

    /** Releases a guard acquired by [beginQuery]. Must be called exactly once per acquire. */
    fun endQuery() {
        inFlightQueries.decrementAndGet()
    }
}


/**
 * Session-scoped factory for the encrypted database driver. The production
 * binding (see `expect platformDriverFactory()` in di) delegates to the platform
 * [com.ultrabytecoder.coinsafebox.data.DatabaseDriverFactory]; tests substitute a
 * fake that returns an in-memory driver without SQLCipher.
 */
interface DbSessionFactory {
    suspend fun createDriver(passphrase: ByteArray): SqlDriver
    fun deleteDatabase()
}
