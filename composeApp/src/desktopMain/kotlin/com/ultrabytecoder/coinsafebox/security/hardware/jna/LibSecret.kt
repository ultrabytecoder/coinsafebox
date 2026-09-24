package com.ultrabytecoder.coinsafebox.security.hardware.jna

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * JNA bindings for the `libsecret-1.so` password-store entry points.
 *
 * All functions take a `const SecretSchema *` (see [SecretSchema]) and a
 * `GHashTable *` of attribute values (see [GLib]). The synchronous `v`-suffix
 * variants block until the keyring daemon responds; `lookupv_sync` returns a
 * `char *` allocated by GLib (free with `g_free`) or NULL when the entry is
 * absent.
 */
interface LibSecret : Library {

    companion object {
        val INSTANCE: LibSecret = Native.load("secret-1", LibSecret::class.java)
    }

    /**
     * Stores a password. [password] is a native NUL-terminated `char *`
     * (allocated with [GLib.g_malloc0], freed by the caller with [GLib.g_free]).
     *
     * It is deliberately a [Pointer] rather than a [String] so that secret
     * material (the device key hex) never materializes as an immutable JVM
     * [String] that the GC cannot wipe — see [LinuxBackend.storeKey].
     */
    fun secret_password_storev_sync(
        schema: Pointer?,
        attributes: Pointer?,
        collection: String?,
        label: String?,
        password: Pointer?,
        cancellable: Pointer?,
        error: PointerByReference?
    ): Boolean

    fun secret_password_lookupv_sync(
        schema: Pointer?,
        attributes: Pointer?,
        cancellable: Pointer?,
        error: PointerByReference?
    ): Pointer?

    fun secret_password_clearv_sync(
        schema: Pointer?,
        attributes: Pointer?,
        cancellable: Pointer?,
        error: PointerByReference?
    ): Boolean
}
