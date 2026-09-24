package com.ultrabytecoder.coinsafebox.security

/**
 * Cross-platform atomic integer for the session's in-flight query counter.
 * JVM actuals back this with `java.util.concurrent.atomic.AtomicInteger`;
 * native actuals use the platform's atomic intrinsics (Kotlin/Native references
 * are freeze-safe, so plain volatile-equivalent access under [SessionManager]'s
 * synchronization is sufficient there).
 */
expect class AtomicCounter(initial: Int = 0) {
    fun incrementAndGet(): Int
    fun decrementAndGet(): Int
    fun get(): Int
}
