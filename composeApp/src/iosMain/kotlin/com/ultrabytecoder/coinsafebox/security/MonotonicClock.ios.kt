package com.ultrabytecoder.coinsafebox.security

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime_nsec_np

/**
 * Monotonic nanoseconds since boot via `clock_gettime_nsec_np(CLOCK_MONOTONIC)`
 * (iOS 10+). Not affected by user clock changes; resets only on reboot.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun monotonicNowMillis(): Long =
    (clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000_000uL).toLong()
