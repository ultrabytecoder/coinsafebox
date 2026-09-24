package com.ultrabytecoder.coinsafebox.security

import com.ultrabytecoder.coinsafebox.security.hardware.DesktopOs
import com.ultrabytecoder.coinsafebox.security.hardware.FileBackend
import com.ultrabytecoder.coinsafebox.security.hardware.HardwareKeyBackend
import com.ultrabytecoder.coinsafebox.security.hardware.LinuxBackend
import com.ultrabytecoder.coinsafebox.security.hardware.MacOSBackend
import com.ultrabytecoder.coinsafebox.security.hardware.OsDetector
import com.ultrabytecoder.coinsafebox.security.hardware.WindowsBackend

/**
 * Desktop device key: a [KeystoreProvider] that delegates the whole encrypt/
 * decrypt/delete lifecycle to an OS-specific [HardwareKeyBackend], selected by
 * [selectBackend]:
 *
 * - macOS: Secure Enclave P-256 (Keychain fallback) — [MacOSBackend].
 * - Windows: DPAPI-protected key — [WindowsBackend] (install-ID bound, F-11).
 * - Linux: desktop secret service (GNOME Keyring / KWallet) — [LinuxBackend],
 *   with a transparent [FileBackend] fallback when libsecret is unavailable.
 * - Other: [FileBackend].
 *
 * The backends operate on byte blobs (the device key never surfaces as a JVM
 * [javax.crypto.SecretKey] — e.g. the macOS Secure Enclave key is non-exportable),
 * so [handlesBlobOperations] is true and HardwareKeyStore routes every
 * operation through this provider instead of its shared AES-GCM path.
 */
internal class DesktopKeystoreProvider(
    lock: Any,
    private val backend: HardwareKeyBackend
) : KeystoreProvider(lock) {

    override fun handlesBlobOperations(): Boolean = true

    // The device key is owned by the OS store; it never becomes a JVM SecretKey.
    override fun getOrCreateKey(): javax.crypto.SecretKey? = null

    // HardwareKeyStore holds its single [lock] while calling into the provider,
    // and the backends synchronize on their own internal monitors. Lock ordering
    // must be strictly outer -> inner: wrap every backend call in synchronized
    // (outer first) so a thread inside a backend can never be waiting for the
    // outer lock while the outer holder waits to enter it (deadlock).

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray =
        synchronized(lock) { backend.encrypt(plaintext, aad) }

    override fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray = synchronized(lock) {
        try {
            backend.decrypt(encrypted, aad)
        } catch (e: HardwareKeyInvalidatedException) {
            throw e
        } catch (e: HardwareKeyCorruptedException) {
            // Must NOT be collapsed into "invalidated": a corrupted key must
            // not be silently replaced by a fresh one (that would orphan every
            // blob encrypted under the old key) — the caller routes to
            // recovery instead.
            throw e
        } catch (e: AesGcmAuthenticationException) {
            throw e
        } catch (e: Exception) {
            throw HardwareKeyInvalidatedException("Device key backend '${backend.id}' failed: ${e.message}")
        }
    }

    override fun deleteBlobKey() = synchronized(lock) { backend.deleteKey() }

    override fun purgeCache() = synchronized(lock) { backend.purgeCache() }

    override fun configureInstallId(id: ByteArray) {
        (backend as? WindowsBackend)?.setInstallId(id)
    }
}

/**
 * Selects the platform [KeystoreProvider]: the OS-specific desktop backend for
 * the host platform. The base-class in-memory fallback is NOT used on desktop —
 * a key that dies with the process would orphan all stored ciphertext on every
 * restart (permanent lockout).
 *
 * [lock] is the caller's monitor, passed in so the provider synchronizes on the
 * SAME lock as HardwareKeyStore (single-lock design — no nested-lock hazard).
 */
internal actual fun createKeystoreProvider(lock: Any): KeystoreProvider =
    DesktopKeystoreProvider(lock, selectBackend())

private fun selectBackend(): HardwareKeyBackend = when (OsDetector.current) {
    DesktopOs.MAC -> MacOSBackend()
    DesktopOs.WINDOWS -> WindowsBackend()
    DesktopOs.LINUX -> LinuxBackend()
    DesktopOs.OTHER -> FileBackend()
}
