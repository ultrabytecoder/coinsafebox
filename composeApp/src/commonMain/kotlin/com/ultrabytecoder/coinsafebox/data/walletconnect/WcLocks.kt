package com.ultrabytecoder.coinsafebox.data.walletconnect

/**
 * A platform-backed mutual-exclusion lock for guarding the WC in-memory state
 * (pairings, proposals, sessions, subscriptions, the pending-request map).
 *
 * This is the cross-platform replacement for the JVM-only `kotlin.synchronized`,
 * which is not available in `commonMain`. The platform primitive is obtained via
 * [newWcLockHandle]: JVM actuals back it with a `ReentrantLock` and native
 * actuals with the platform's lock. The handle is stored per [WcLock] instance,
 * so each lock site (state lock, subscriptions lock, dedupe lock) is independent.
 */
class WcLock {
    private val handle: Any = newWcLockHandle()

    /** Acquires the lock. Callers must pair this with a subsequent [unlock]. */
    fun lock() = wcLockAcquire(handle)

    /** Releases the lock. Must be called exactly once per [lock]. */
    fun unlock() = wcLockRelease(handle)
}

/** Opaque platform lock primitive (a `ReentrantLock` on JVM, `NSLock` on native). */
internal expect fun newWcLockHandle(): Any
internal expect fun wcLockAcquire(handle: Any)
internal expect fun wcLockRelease(handle: Any)

/**
 * Cross-platform replacement for the JVM-only `kotlin.synchronized`. Runs [block]
 * while holding [lock]. Inlined so a non-local `return` from inside [block]
 * behaves exactly like it does with `synchronized`.
 */
internal inline fun <T> synchronized(lock: WcLock, block: () -> T): T {
    lock.lock()
    try {
        return block()
    } finally {
        lock.unlock()
    }
}
