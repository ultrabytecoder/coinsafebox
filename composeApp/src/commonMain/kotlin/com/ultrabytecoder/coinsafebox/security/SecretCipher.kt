package com.ultrabytecoder.coinsafebox.security

/**
 * Hardware-key wrapping for stored secrets (recovery phrase and master seed) —
 * defense in depth on top of the SQLCipher database: the secret BLOB is encrypted
 * with the always-on device key ([HardwareKeyStore], Android Keystore / iOS Secure
 * Enclave) before it is written, so even a decrypted database (e.g. DEK
 * exfiltration) does not reveal the secret, and the ciphertext is useless on
 * another device.
 *
 * Format: `"KRYPT" || 0x01 || HardwareKeyStore.encrypt(plaintext)`. Every stored
 * secret BLOB is hardware-encrypted from creation — the magic prefix is a
 * structural invariant, not a format discriminator.
 */
object SecretCipher {

    private val MAGIC: ByteArray = byteArrayOf(0x4B, 0x52, 0x59, 0x50, 0x54, 0x01) // "KRYPT" v1

    /**
     * Wraps [plaintext] with the device hardware key. The caller owns both the
     * input (wiped by the caller) and the returned ciphertext.
     *
     * @throws Exception when the hardware key cannot be created/used.
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val blob = HardwareKeyStore.encrypt(plaintext)
        return try {
            MAGIC + blob
        } finally {
            blob.wipe()
        }
    }

    /**
     * Decrypts a stored secret BLOB with the device hardware key.
     *
     * @throws HardwareKeyInvalidatedException when the device key is missing/invalidated
     * and the stored blob is hardware-encrypted — the secret is unrecoverable.
     * @throws AesGcmAuthenticationException when the stored blob fails authentication
     * (tampered/corrupt) — never fall back to treating it as plaintext.
     */
    fun decrypt(stored: ByteArray): Result {
        if (!stored.startsWith(MAGIC)) {
            throw AesGcmAuthenticationException("Stored secret blob is not hardware-encrypted")
        }
        val blob = stored.copyOfRange(MAGIC.size, stored.size)
        return try {
            Result(HardwareKeyStore.decrypt(blob))
        } finally {
            blob.wipe()
        }
    }

    /** Decrypted with the device hardware key; [plaintext] is a fresh array. */
    class Result(val plaintext: ByteArray)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }
}
