package com.ultrabytecoder.coinsafebox.security.hardware

import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.ultrabytecoder.coinsafebox.security.AesGcm
import com.ultrabytecoder.coinsafebox.security.AesGcmAuthenticationException
import com.ultrabytecoder.coinsafebox.security.HardwareKeyCorruptedException
import com.ultrabytecoder.coinsafebox.security.HardwareKeyInvalidatedException
import com.ultrabytecoder.coinsafebox.security.hardware.jna.GLib
import com.ultrabytecoder.coinsafebox.security.hardware.jna.LibSecret
import com.ultrabytecoder.coinsafebox.security.hardware.jna.SecretSchemaFactory
import com.ultrabytecoder.coinsafebox.security.wipe
import org.kotlincrypto.random.CryptoRand

/**
 * Linux device key backed by the desktop secret service (GNOME Keyring /
 * KWallet) through `libsecret-1.so`.
 *
 * A random 32-byte AES-256 key is stored once in the default collection under
 * the fixed attribute `service = com.ultrabytecoder.coinsafebox` and wrapped
 * with AES-GCM ([AesGcm]) for every payload. The unwrapped key is cached in
 * the JVM heap for the process lifetime (same residency model as
 * [FileBackend]) and wiped by [purgeCache] on session lock.
 *
 * When libsecret (or the keyring daemon) is unavailable — e.g. a headless
 * server without a session bus — the backend transparently falls back to
 * [FileBackend] so the app remains usable.
 */
class LinuxBackend : HardwareKeyBackend {

    override val id: String = "linux_libsecret"

    private companion object {
        const val KEY_SIZE = 32
        const val SCHEMA_NAME = "com.ultrabytecoder.coinsafebox"
        const val ATTR_SERVICE = "service"
        const val SERVICE = "com.ultrabytecoder.coinsafebox"
        const val LABEL = "CoinSafeBox device key"
        val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }

    private val libsecret: LibSecret? = loadLibsecret()
    private val glib: GLib? = loadGLib()
    private val fileFallback = FileBackend()

    private val lock = Any()

    @Volatile
    private var cachedKey: ByteArray? = null

    private fun available(): Boolean = libsecret != null && glib != null

    private fun loadLibsecret(): LibSecret? = try {
        LibSecret.INSTANCE
    } catch (_: Exception) {
        null
    }

    private fun loadGLib(): GLib? = try {
        GLib.INSTANCE
    } catch (_: Exception) {
        null
    }

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        if (!available()) return fileFallback.encrypt(plaintext, aad)
        val key = synchronized(lock) {
            cachedKey ?: (lookupKey() ?: storeKey()).also { cachedKey = it }
        }
        return AesGcm.encrypt(key, plaintext, aad)
    }

    override fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray {
        if (!available()) return fileFallback.decrypt(encrypted, aad)
        val key = synchronized(lock) {
            cachedKey ?: lookupKey()
                ?: throw HardwareKeyInvalidatedException("libsecret key missing")
        }
        return try {
            AesGcm.decrypt(key, encrypted, aad)
        } catch (e: AesGcmAuthenticationException) {
            throw e
        } catch (e: Exception) {
            // Do not surface e.message: it can carry native libsecret/GLib
            // strings (paths, daemon versions, DBus addresses) that aid an
            // attacker in fingerprinting the environment. Log the detail for
            // debugging and surface a generic message to the UI.
            System.err.println("linux_libsecret decrypt failed: ${e.message}")
            throw HardwareKeyInvalidatedException("Device key backend unavailable. Re-init required.")
        }
    }

    override fun deleteKey() {
        synchronized(lock) {
            cachedKey?.wipe()
            cachedKey = null
        }
        if (available()) {
            try {
                clearKey()
            } catch (_: Exception) {
                // Keyring unavailable — nothing to clear.
            }
        }
    }

    override fun purgeCache() {
        synchronized(lock) {
            cachedKey?.wipe()
            cachedKey = null
        }
    }

    /**
     * Creates the native `SecretSchema` and a `GHashTable` with the `service`
     * attribute.
     *
     * All native allocations use [GLib.g_malloc0] which returns plain
     * [Pointer] objects — **not** `com.sun.jna.Memory` subclasses.  This
     * means JNA registers **no** `Cleaner` for these buffers, so `g_free` in
     * the caller's `finally` block is the sole deallocation path.  This
     * eliminates the double-free that occurred when both `g_free` and JNA's
     * GC-triggered `MemoryDisposer` freed the same pointer.
     *
     * The hash table key and value are stable native pointers (not JNA-
     * converted Kotlin Strings, which would be freed immediately after the
     * insert call).  The hash table is created with `NULL` key/value destroy
     * functions, so `g_hash_table_unref` will not attempt to free the
     * key/value pointers — the caller does that explicitly.
     */
    private fun createSchemaAndTable(): Triple<Pointer, Pointer, List<Pointer>> {
        val glib = glib!!
        val (schema, schemaPtrs) = SecretSchemaFactory.create(glib, SCHEMA_NAME, ATTR_SERVICE)

        val glibLib = NativeLibrary.getInstance("glib-2.0")
        val gStrHash = glibLib.getFunction("g_str_hash")
        val gStrEqual = glibLib.getFunction("g_str_equal")
        val table = glib.g_hash_table_new_full(gStrHash, gStrEqual, null, null)

        val keyBytes = ATTR_SERVICE.toByteArray(Charsets.UTF_8)
        val keyMem = glib.g_malloc0((keyBytes.size + 1).toLong())!!
        keyMem.write(0, keyBytes, 0, keyBytes.size)

        val serviceBytes = SERVICE.toByteArray(Charsets.UTF_8)
        val serviceMem = glib.g_malloc0((serviceBytes.size + 1).toLong())!!
        serviceMem.write(0, serviceBytes, 0, serviceBytes.size)

        val insertFn = glibLib.getFunction("g_hash_table_insert")
        insertFn.invoke(arrayOf<Any?>(table, keyMem, serviceMem))

        return Triple(schema, table, schemaPtrs + keyMem + serviceMem)
    }

    private fun freeError(glib: GLib, error: PointerByReference) {
        val errPtr = error.value
        if (errPtr != null) {
            glib.g_error_free(errPtr)
        }
    }

    private fun lookupKey(): ByteArray? {
        val libsecret = libsecret!!
        val glib = glib!!
        val (schema, table, ptrs) = createSchemaAndTable()
        val error = PointerByReference()
        try {
            val result = libsecret.secret_password_lookupv_sync(schema, table, null, error)
            if (error.value != null) {
                freeError(glib, error)
                return null
            }
            if (result == null) return null
            // Read the stored value byte-by-byte from the native buffer so the
            // key hex never materializes as a JVM String (see storeKey).
            val hexChars = ByteArray(KEY_SIZE * 2)
            try {
                var len = 0
                for (i in 0 until KEY_SIZE * 2) {
                    val c = result.getByte(i.toLong()).toInt() and 0xFF
                    if (c == 0) break
                    hexChars[len++] = c.toByte()
                }
                // A value that is present but not exactly KEY_SIZE*2 valid hex
                // chars is CORRUPT, not missing. Throw (rather than return null)
                // so the caller does not silently replace it with a fresh key —
                // that would orphan any data encrypted under the old key.
                if (len != KEY_SIZE * 2) {
                    throw HardwareKeyCorruptedException("Stored device key has invalid length $len")
                }
                for (i in 0 until len) {
                    if (!isHexDigit(hexChars[i])) {
                        throw HardwareKeyCorruptedException("Stored device key is not valid hex")
                    }
                }
                val out = ByteArray(KEY_SIZE)
                for (i in 0 until KEY_SIZE) {
                    out[i] = ((hexNibble(hexChars[i * 2]) shl 4) or hexNibble(hexChars[i * 2 + 1])).toByte()
                }
                return out
            } finally {
                // Zero the JVM-side hex copy and the native buffer before
                // freeing, so the key hex does not linger in freed native heap
                // or on the JVM heap until GC (mirrors storeKey's zero-before-free).
                hexChars.fill(0)
                for (i in 0 until KEY_SIZE * 2) result.setByte(i.toLong(), 0.toByte())
                glib.g_free(result)
            }
        } finally {
            ptrs.forEach { glib.g_free(it) }
            glib.g_hash_table_unref(table)
        }
    }

    private fun storeKey(): ByteArray {
        val libsecret = libsecret!!
        val glib = glib!!
        val key = CryptoRand.Default.nextBytes(ByteArray(KEY_SIZE))
        try {
            // Build the hex representation in a native buffer (NUL-terminated)
            // so the 32-byte device key never touches an immutable JVM String.
            val hexMem = glib.g_malloc0((KEY_SIZE * 2 + 1).toLong())!!
            try {
                for (i in 0 until KEY_SIZE) {
                    val v = key[i].toInt() and 0xFF
                    hexMem.setByte((i * 2).toLong(), HEX_DIGITS[v ushr 4].code.toByte())
                    hexMem.setByte((i * 2 + 1).toLong(), HEX_DIGITS[v and 0x0F].code.toByte())
                }
                hexMem.setByte((KEY_SIZE * 2).toLong(), 0.toByte())

                val (schema, table, ptrs) = createSchemaAndTable()
                val error = PointerByReference()
                try {
                    val ok = libsecret.secret_password_storev_sync(
                        schema, table, null, LABEL, hexMem, null, error
                    )
                    if (!ok) {
                        freeError(glib, error)
                        throw HardwareKeyInvalidatedException("libsecret store failed")
                    }
                    return key
                } finally {
                    ptrs.forEach { glib.g_free(it) }
                    glib.g_hash_table_unref(table)
                }
            } finally {
                // Zero then free the native hex buffer.
                for (i in 0 until KEY_SIZE * 2) hexMem.setByte(i.toLong(), 0.toByte())
                glib.g_free(hexMem)
            }
        } catch (e: Exception) {
            key.wipe()
            throw e
        }
    }

    private fun isHexDigit(b: Byte): Boolean {
        val c = b.toInt() and 0xFF
        return (c in '0'.code..'9'.code) || (c in 'a'.code..'f'.code) || (c in 'A'.code..'F'.code)
    }

    private fun hexNibble(b: Byte): Int {
        val c = b.toInt() and 0xFF
        return when {
            c in '0'.code..'9'.code -> c - '0'.code
            c in 'a'.code..'f'.code -> c - 'a'.code + 10
            c in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> 0
        }
    }

    private fun clearKey() {
        val libsecret = libsecret!!
        val glib = glib!!
        val (schema, table, ptrs) = createSchemaAndTable()
        val error = PointerByReference()
        try {
            libsecret.secret_password_clearv_sync(schema, table, null, error)
        } finally {
            freeError(glib, error)
            ptrs.forEach { glib.g_free(it) }
            glib.g_hash_table_unref(table)
        }
    }
}
