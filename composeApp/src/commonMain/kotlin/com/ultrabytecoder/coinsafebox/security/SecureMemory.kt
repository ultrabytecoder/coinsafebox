package com.ultrabytecoder.coinsafebox.security

/**
 * Securely zeroes sensitive data in memory.
 *
 * Plain `fill(0)` is not a zeroization guarantee: the JVM JIT can prove the
 * writes unobservable and remove them (dead-store elimination), and native
 * compilers can do the same for `memset`. Platform actuals use
 * compiler-resistant zeroization:
 * - Android/JVM: a volatile publication barrier that makes the zeroed contents
 *   observable to other threads, so the JIT cannot eliminate the fill.
 * - iOS: `memset_s` (C11), which cannot be elided.
 */
expect fun ByteArray.wipe()

/** See [ByteArray.wipe]. */
expect fun CharArray.wipe()

/**
 * Best-effort garbage collection hint. Used to shorten the lifetime of secret
 * material that cannot be wiped (e.g. the immutable Strings a third-party API
 * forces us to materialize): the copies are already unreachable, this simply
 * asks the collector to reclaim them sooner.
 *
 * - Android/JVM: `System.gc()` (a hint; ART may ignore it).
 * - iOS: `kotlin.native.runtime.GC.collect()` (forces a collection pass).
 *
 * SECURITY NOTE: [gcHint] is NOT a security control. It cannot guarantee
 * that immutable Strings or other unreachable secret material is reclaimed
 * before a memory-dump attacker reads it. The only reliable mitigation is
 * to avoid materializing secrets as immutable types.
 */
expect fun gcHint()
