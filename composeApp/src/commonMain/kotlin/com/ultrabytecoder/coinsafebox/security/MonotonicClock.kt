package com.ultrabytecoder.coinsafebox.security

/**
 * Milliseconds since an arbitrary boot-stable epoch that only moves forward
 * (monotonic). Unlike wall-clock time it cannot be changed by the user, so it
 * is safe to use for lockout enforcement against clock tampering.
 *
 * The epoch resets on device reboot, so a persisted monotonic value is only
 * meaningful as a guard within the same boot — PinRepositoryImpl combines it
 * with wall-clock time (which survives reboots) to cover both attacks.
 *
 * - Android: `SystemClock.elapsedRealtime()` (since boot, includes deep sleep).
 * - iOS: `clock_gettime(CLOCK_MONOTONIC)` via `clock_gettime_nsec_np`.
 */
expect fun monotonicNowMillis(): Long
