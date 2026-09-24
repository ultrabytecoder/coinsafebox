package com.ultrabytecoder.coinsafebox.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlin.native.runtime.GC
import platform.posix.memset_s

/**
 * iOS zeroization via `memset_s` (C11, available since iOS 11): the libc
 * implementation cannot be elided by the compiler, unlike `memset`.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun ByteArray.wipe() {
    if (isEmpty()) return
    usePinned { pinned ->
        memset_s(pinned.addressOf(0), size.convert(), 0, size.convert())
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun CharArray.wipe() {
    if (isEmpty()) return
    usePinned { pinned ->
        // Char is 2 bytes (UTF-16).
        memset_s(pinned.addressOf(0), (size * 2).convert(), 0, (size * 2).convert())
    }
}

actual fun gcHint() {
    // Kotlin/Native: forces a collection pass.
    GC.collect()
}
