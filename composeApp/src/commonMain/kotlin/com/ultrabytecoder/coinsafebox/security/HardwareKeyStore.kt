package com.ultrabytecoder.coinsafebox.security

/**
 * Thrown when the device-bound hardware key (Android Keystore / iOS Secure Enclave)
 * is missing, invalidated, or unavailable. The wrapped key material can no longer
 * be decrypted — treat as key-material corruption and route to PIN re-setup.
 */
class HardwareKeyInvalidatedException(message: String) : Exception(message)

/**
 * Thrown when a device key IS present in the OS store but is corrupted (wrong
 * length, non-hex bytes, etc.) — as opposed to [HardwareKeyInvalidatedException],
 * which means the key is absent. Distinguishing the two matters: a corrupted key
 * must NOT be silently replaced by a fresh one (that would orphan any data
 * encrypted under the old key); the caller should route to recovery instead.
 */
class HardwareKeyCorruptedException(message: String) : Exception(message)

/**
 * Always-on, non-interactive hardware-backed encryption (no biometric prompt).
 *
 * - Android: Android Keystore AES-256-GCM key (`setUserAuthenticationRequired(false)`),
 *   backed by TEE/StrongBox where the SoC provides it.
 * - iOS: Secure Enclave ECC P-256 key (`kSecAttrTokenIDSecureEnclave`), ECIES-AES-GCM.
 *
 * Purpose: bind the key hierarchy to the device. The PIN salt is encrypted here, so
 * deriving the KEK requires the hardware key — an attacker who extracts the app data
 * cannot brute-force the PIN offline, and the data is useless on another device.
 *
 * [aad] (additional authenticated data) is authenticated but not encrypted: callers
 * bind a blob to context (e.g. the install ID) so it cannot be swapped in from
 * another install or repurposed.
 */
expect object HardwareKeyStore {

    /** Encrypts [plaintext] with the device key (creating the key on first use). */
    fun encrypt(plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray

    /**
     * Decrypts [encrypted] with the device key.
     *
     * @throws HardwareKeyInvalidatedException when the device key is missing/invalidated.
     * @throws AesGcmAuthenticationException when the ciphertext fails authentication.
     */
    fun decrypt(encrypted: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray

    /**
     * Deletes the device key. Used when wiping all key material (PIN re-setup /
     * recovery). The key is re-created lazily on the next [encrypt] call.
     */
    fun deleteKey()

    /**
     * Wipes any in-process cache of the unwrapped device key (e.g. a Keychain or
     * DPAPI-unwrapped AES key held in the JVM heap). The on-disk / OS-store key is
     * NOT deleted — only the in-memory copy. Call on session lock.
     *
     * On the JVM target the in-memory fallback key IS this cache, so it is wiped
     * here; the next [encrypt]/[decrypt] re-creates a fresh fallback key (old
     * ciphertext then fails GCM authentication). On Android the key never leaves
     * the Keystore, so this is a no-op.
     */
    fun purgeCache()

    /**
     * Binds the device key to the install ID (F-11). On Windows the DPAPI blob is
     * wrapped with entropy derived from [id], so a blob copied to another install
     * (same user) cannot be unwrapped. No-op on platforms without such a binding
     * (Android Keystore is already device-bound; the JVM in-memory fallback has no
     * OS store to bind — install-ID binding there is provided at the AES-GCM layer
     * via the AAD). Must be called before the first [encrypt]/[decrypt].
     */
    fun configureInstallId(id: ByteArray)
}
