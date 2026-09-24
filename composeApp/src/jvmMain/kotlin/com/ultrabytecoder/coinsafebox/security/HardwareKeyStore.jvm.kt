package com.ultrabytecoder.coinsafebox.security

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM device key.
 *
 * The key comes from [KeystoreProvider]: on Android that is the Android
 * Keystore (hardware-backed, the key never leaves the Keystore); on the
 * desktop target the provider delegates the whole encrypt/decrypt/delete
 * lifecycle to an OS key store (DPAPI / Secure Enclave / libsecret / file), so
 * the device key persists across restarts. The [fallbackKey] in-memory key is
 * only used on a platform whose provider reports "no Keystore" and does not
 * handle blob operations.
 *
 * Concurrency: [keyLock] is the SINGLE lock for the whole object — it guards
 * the provider resolution, the fallback key, AND is passed into the provider so
 * the provider's internal operations use the same monitor. This eliminates any
 * nested-lock (lock-ordering) hazard between [HardwareKeyStore] and
 * [KeystoreProvider]. [provider] is a retryable holder (NOT `by lazy`) so a
 * transient provider-construction failure is not cached permanently.
 */
actual object HardwareKeyStore {

    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128

    private val keyLock = Any()

    @Volatile
    private var providerHolder: KeystoreProvider? = null

    @Volatile
    private var fallbackKey: ByteArray? = null

    // Install ID captured by configureInstallId() so a provider constructed later
    // (retry path) still receives the binding before its first use.
    @Volatile
    private var installBoundId: ByteArray? = null

    private val random = SecureRandom()

    /**
     * Resolves the platform [KeystoreProvider], constructing it on first use.
     * The holder is retried on each miss (unlike `by lazy`, which would cache a
     * failed construction forever), so a transient failure does not permanently
     * disable the hardware key for the process.
     *
     * Synchronized on [keyLock] so concurrent first-use cannot construct two
     * providers (one would be orphaned). [keyLock] is reentrant, so this is safe
     * to call from paths that already hold [keyLock] (e.g. [encrypt],
     * [resolveKeyForDecrypt]). The provider is constructed with [keyLock] so its
     * internal operations synchronize on the same monitor (no nested locks).
     */
    private fun provider(): KeystoreProvider = synchronized(keyLock) {
        providerHolder ?: createKeystoreProvider(keyLock).also {
            installBoundId?.let { id -> it.configureInstallId(id) }
            providerHolder = it
        }
    }

    /**
     * Returns the live fallback key, creating it on first use. Caller must hold
     * [keyLock]. Returns the live array reference; [SecretKeySpec] copies the
     * bytes, so wiping [fallbackKey] later does not affect already-created specs.
     */
    private fun getOrCreateFallbackKey(): ByteArray {
        return fallbackKey ?: ByteArray(32).also {
            random.nextBytes(it)
            fallbackKey = it
        }
    }

    /**
     * Resolves the active key for decryption WITHOUT creating a fallback key.
     * Returns the hardware key if present, the existing fallback key if one is
     * live, or null when no key exists (the caller then reports "missing").
     * The whole resolve is under [keyLock] so the "is there a key?" decision and
     * the key it returns are consistent (no TOCTOU).
     */
    private fun resolveKeyForDecrypt(): SecretKey? = synchronized(keyLock) {
        provider().getOrCreateKey()
            ?: fallbackKey?.let { SecretKeySpec(it, "AES") }
    }

    actual fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        // Desktop providers that operate on byte blobs (OS key stores whose keys
        // cannot be exported as a JVM SecretKey — e.g. macOS Secure Enclave ECIES)
        // handle the whole operation themselves.
        val opProvider = provider()
        if (opProvider.handlesBlobOperations()) {
            return synchronized(keyLock) { opProvider.encrypt(plaintext, aad) }
        }
        val key = synchronized(keyLock) {
            opProvider.getOrCreateKey() ?: SecretKeySpec(getOrCreateFallbackKey(), "AES")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return cipher.iv + ciphertext
    }

    actual fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray {
        val opProvider = provider()
        if (opProvider.handlesBlobOperations()) {
            return synchronized(keyLock) { opProvider.decrypt(encrypted, aad) }
        }
        val key = resolveKeyForDecrypt()
            ?: throw HardwareKeyInvalidatedException("Device hardware key missing")
        return decryptWithKey(key, encrypted, aad)
    }

    private fun decryptWithKey(key: SecretKey, encrypted: ByteArray, aad: ByteArray): ByteArray {
        require(encrypted.size > GCM_IV_LENGTH) { "Encrypted data too short" }
        val iv = encrypted.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = encrypted.copyOfRange(GCM_IV_LENGTH, encrypted.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return try {
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw AesGcmAuthenticationException("Hardware GCM authentication failed")
        }
    }

    actual fun deleteKey() {
        // Atomic: wipe the fallback key AND delete the hardware key under the
        // same [keyLock] so a concurrent encrypt() cannot generate a fresh
        // fallback key in the gap (which provider().deleteKey() would then
        // orphan). Reentrant — provider()/provider().deleteKey() re-acquire the
        // same [keyLock] without deadlocking.
        synchronized(keyLock) {
            fallbackKey?.wipe()
            fallbackKey = null
            val p = provider()
            if (p.handlesBlobOperations()) p.deleteBlobKey() else p.deleteKey()
        }
    }

    actual fun purgeCache() {
        // Wipes the in-process copy of the device key. On Android the key never
        // leaves the Keystore (nothing to purge); on the JVM target the
        // in-memory fallback key IS the cache, so it is wiped here. The next
        // encrypt/decrypt re-creates a fresh fallback key, which is the intended
        // "session is over" behavior (old ciphertext then fails GCM auth).
        // Blob-handling providers wipe their own cached OS key.
        synchronized(keyLock) {
            fallbackKey?.wipe()
            fallbackKey = null
            provider().purgeCache()
        }
    }

    actual fun configureInstallId(id: ByteArray) {
        // The Android Keystore key is already device-bound, and the in-memory
        // fallback has no OS store to bind (install-ID binding there is provided
        // at the AES-GCM layer by KeyManager.wrapAad()). Desktop providers
        // override configureInstallId to bind their OS-store blob (F-11, Windows
        // DPAPI entropy). The id is retained so a provider constructed later
        // (retry path) receives it too.
        synchronized(keyLock) {
            installBoundId?.wipe()
            installBoundId = id.copyOf()
            provider().configureInstallId(id)
        }
    }
}

/**
 * Selects the platform [KeystoreProvider]. Android returns the Android Keystore
 * implementation; the desktop target returns a [DesktopKeystoreProvider] that
 * delegates blob operations to an OS backend (DPAPI / Secure Enclave /
 * libsecret / file — see KeystoreProvider.desktop.kt), so the device key
 * persists across restarts instead of dying with the process.
 *
 * [lock] is the caller's monitor, passed in so the provider synchronizes on the
 * SAME lock as [HardwareKeyStore] (single-lock design — no nested-lock hazard).
 */
internal expect fun createKeystoreProvider(lock: Any): KeystoreProvider

/**
 * Platform hook for the hardware-backed key. Android subclasses this with the
 * Android Keystore; the desktop target subclasses it with a blob-handling
 * provider (handlesBlobOperations() = true) that delegates to an OS key store.
 *
 * All methods synchronize on [lock] (the monitor supplied by
 * [HardwareKeyStore]), so the provider and its caller share one lock.
 */
internal open class KeystoreProvider(protected val lock: Any) {

    /** Returns the hardware-backed key, creating it on first use; null when no Keystore exists. */
    open fun getOrCreateKey(): SecretKey? = null

    /** True when a hardware-backed key exists, false when it does not, null when there is no Keystore. */
    open fun hasKey(): Boolean? = null

    /** Deletes the hardware-backed key; no-op when no Keystore exists. */
    open fun deleteKey() {}

    /**
     * True when this provider performs the encrypt/decrypt operations itself on
     * byte blobs (OS key stores whose device key cannot be exported as a JVM
     * [SecretKey] — e.g. macOS Secure Enclave ECIES). The default false keeps
     * the shared AES-GCM path in HardwareKeyStore for Android and the in-memory
     * fallback. Blob-handling providers implement [encrypt]/[decrypt] and
     * [deleteBlobKey]; [getOrCreateKey] returns null for them.
     */
    open fun handlesBlobOperations(): Boolean = false

    /** Blob-path encrypt; only called when [handlesBlobOperations] is true. */
    open fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray =
        throw UnsupportedOperationException("Provider does not handle blob operations")

    /** Blob-path decrypt; only called when [handlesBlobOperations] is true. */
    open fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray =
        throw UnsupportedOperationException("Provider does not handle blob operations")

    /** Deletes the OS-store device key on the blob path (see [encrypt]/[decrypt]). */
    open fun deleteBlobKey() {}

    /**
     * Wipes any in-process cache of the unwrapped device key (the on-disk /
     * OS-store key is NOT deleted). No-op by default; blob-handling providers
     * override this to wipe their cached copy. Called by HardwareKeyStore.purgeCache().
     */
    open fun purgeCache() {}

    /**
     * Binds the device key to the install ID (F-11). The default is a no-op: the
     * Android Keystore key is already device-bound and the in-memory fallback has
     * no OS store to bind. Desktop providers override this to bind their OS-store
     * blob (e.g. Windows DPAPI entropy). Called by [HardwareKeyStore] before the
     * provider's first use, and again whenever a new provider is constructed.
     */
    open fun configureInstallId(id: ByteArray) {}
}
