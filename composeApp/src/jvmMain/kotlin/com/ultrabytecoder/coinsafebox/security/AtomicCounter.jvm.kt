package com.ultrabytecoder.coinsafebox.security

actual class AtomicCounter actual constructor(initial: Int) {
    private val value = java.util.concurrent.atomic.AtomicInteger(initial)
    actual fun incrementAndGet(): Int = value.incrementAndGet()
    actual fun decrementAndGet(): Int = value.decrementAndGet()
    actual fun get(): Int = value.get()
}
