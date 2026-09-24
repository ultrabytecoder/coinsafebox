package com.ultrabytecoder.coinsafebox.security

import kotlin.concurrent.AtomicInt

/**
 * Kotlin/Native (modern memory model): references are not frozen and plain
 * field writes are visible across threads; AtomicInt provides atomic RMW for
 * the counter itself.
 */
actual class AtomicCounter actual constructor(initial: Int) {
    private val value = AtomicInt(initial)
    actual fun incrementAndGet(): Int = value.addAndGet(1)
    actual fun decrementAndGet(): Int = value.addAndGet(-1)
    actual fun get(): Int = value.get()
}
