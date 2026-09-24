package com.ultrabytecoder.coinsafebox.security.hardware.jna

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * JNA bindings for the `g_hash_table` and error-handling subset of
 * `libglib-2.0.so` needed by the libsecret backend.
 *
 * `g_str_hash` and `g_str_equal` are fetched as raw function pointers via
 * [com.sun.jna.Function.getFunction] and passed to [g_hash_table_new_full]
 * (they are not called through this interface).
 *
 * [g_malloc0] is used instead of [com.sun.jna.Memory] for all native
 * allocations that are later freed with [g_free].  A [com.sun.jna.Memory]
 * object registers a JNA `Cleaner` that calls `free(peer)` on GC; calling
 * `g_free` on the same pointer produces a double-free that glibc detects at
 * process exit (tcache abort, exit code 134).  Pointers returned by JNA for
 * `void *` return types are plain [Pointer] instances with **no** cleaner,
 * so [g_free] is the sole deallocator.
 */
interface GLib : Library {

    companion object {
        val INSTANCE: GLib = Native.load("glib-2.0", GLib::class.java)
    }

    /**
     * Allocates `n_bytes` of zero-initialised memory via GLib's allocator.
     *
     * The returned [Pointer] is a plain wrapper around the native address —
     * it is **not** a [com.sun.jna.Memory] subclass and therefore has **no**
     * registered JNA `Cleaner`.  The caller must free it with [g_free]; no
     * JNA finaliser will ever touch it.
     */
    fun g_malloc0(n_bytes: Long): Pointer?

    fun g_hash_table_new_full(
        hashFunc: Pointer?,
        equalFunc: Pointer?,
        keyDestroy: Pointer?,
        valueDestroy: Pointer?
    ): Pointer

    fun g_hash_table_unref(table: Pointer?)

    fun g_free(memory: Pointer?)

    fun g_error_free(error: Pointer?)
}
