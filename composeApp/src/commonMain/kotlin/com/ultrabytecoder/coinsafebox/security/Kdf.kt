package com.ultrabytecoder.coinsafebox.security

/**
 * Key-derivation function for the PIN/password -> KEK step (Argon2id).
 *
 * - Android/Desktop (JVM): Argon2id via BouncyCastle.
 * - iOS: Argon2id via libsodium (`argon2id_hash_raw`).
 *
 * All platforms produce byte-identical output for the same
 * (password, salt, timeCost, memoryKib, parallelism) — both backends implement
 * RFC 9106 Argon2id v1.3.
 *
 * The algorithm actually used for a given envelope is recorded in the envelope
 * itself ([com.ultrabytecoder.coinsafebox.security.KeyManager] dispatches on it),
 * so the platform capability and the stored parameters never have to match
 * across a re-wrap.
 */
expect object Kdf {

    /**
     * True when this platform can derive with Argon2id. Checked at runtime:
     * on iOS this reflects whether libsodium initialized successfully.
     */
    val supportsArgon2id: Boolean

    /**
     * Derives [derivedKeyLengthBytes] from [password]/[salt] using Argon2id.
     *
     * **Ownership contract:** the caller owns [password], [salt] and the
     * returned array, and must wipe all three when done. This function does
     * NOT wipe its inputs — it may legitimately be called more than once with
     * the same buffers (see [KeyManager]'s Argon2id -> PBKDF2 fallback, which
     * reuses the same `pinBytes`/`salt`), so wiping inputs here would corrupt
     * the second call.
     *
     * Note: the backends copy [password] into internal working memory that
     * cannot be zeroed from Kotlin (BouncyCastle's Argon2 working set,
     * libsodium's argon2 buffer). Wiping the caller's buffers is the only
     * zeroization available to us.
     *
     * @param timeCost Argon2 passes (t_cost).
     * @param memoryKib Argon2 memory cost in KiB (m_cost).
     * @param parallelism Argon2 lanes.
     * @throws KdfException when the backend cannot satisfy the parameters
     * (e.g. memory allocation failure).
     */
    fun derive(
        password: ByteArray,
        salt: ByteArray,
        timeCost: Int,
        derivedKeyLengthBytes: Int,
        memoryKib: Int = 32768,
        parallelism: Int = 1
    ): ByteArray
}
