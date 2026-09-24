package com.ultrabytecoder.coinsafebox.security

/**
 * `System.nanoTime()` is monotonic (immune to wall-clock changes) and includes
 * deep sleep on most platforms. Epoch is arbitrary and resets on JVM restart,
 * matching the documented contract of [monotonicNowMillis].
 */
actual fun monotonicNowMillis(): Long = System.nanoTime() / 1_000_000L
