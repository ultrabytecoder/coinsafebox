package com.ultrabytecoder.coinsafebox.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import libsodium.argon2id_hash_raw
import libsodium.sodium_init

/**
 * Argon2id via libsodium on iOS.
 *
 * Uses `argon2id_hash_raw` (the raw Argon2 primitive) rather than the
 * `crypto_pwhash` wrapper, because `crypto_pwhash` hardcodes a 16-byte salt
 * while CoinSafeBox uses 32-byte salts across all platforms (envelope
 * compatibility with Android/Desktop). `argon2id_hash_raw` accepts variable
 * salt lengths and produces byte-identical output to BouncyCastle's
 * `Argon2BytesGenerator` (ARGON2_id, version 1.3) for the same
 * (password, salt, t, m, p) — both implement RFC 9106.
 *
 * Pre-existing iOS envelopes with `kdfAlgorithm = "PBKDF2-SHA256"` are
 * unwrapped via [Pbkdf2.derive] in [KeyManager.deriveKek] — they do NOT pass
 * through here.
 */
@OptIn(ExperimentalForeignApi::class)
actual object Kdf {

    /**
     * Lazy + thread-safe. `sodium_init()` is idempotent and itself
     * thread-safe: returns 0 on first successful init, 1 if already
     * initialized, -1 on unrecoverable failure.
     */
    private val sodiumReady: Boolean by lazy { sodium_init() >= 0 }

    actual val supportsArgon2id: Boolean
        get() = sodiumReady

    actual fun derive(
        password: ByteArray,
        salt: ByteArray,
        timeCost: Int,
        derivedKeyLengthBytes: Int,
        memoryKib: Int,
        parallelism: Int
    ): ByteArray {
        // sodium_init() is idempotent and, once it has failed, cannot be
        // recovered by calling it again — so a single cached check is correct
        // and a "retry" would be misleading.
        if (!sodiumReady) {
            throw KdfException("sodium_init() failed; libsodium unavailable")
        }

        // RFC 9106 bounds parallelism (lanes) to 2^24-1. Use that exact bound:
        // a wider bound would accept values libsodium rejects, and a narrower
        // one (the old 0x7FFF) would refuse envelopes created elsewhere with a
        // higher parallelism, breaking the cross-platform portability invariant.
        // (Note: 0xFFFFFFFF as a Kotlin Int literal is -1, so 1..0xFFFFFFFF is
        // an empty range — the bound must be written as a positive Int.)
        require(parallelism in 1..0xFFFFFF) {
            "Argon2id parallelism out of range: $parallelism"
        }
        require(salt.isNotEmpty()) { "Argon2id salt must be non-empty" }
        require(password.isNotEmpty()) { "Argon2id password must be non-empty" }
        require(derivedKeyLengthBytes > 0) {
            "derivedKeyLengthBytes must be positive: $derivedKeyLengthBytes"
        }
        require(timeCost >= 1) { "timeCost must be >= 1: $timeCost" }
        // Use Long arithmetic: 8 * parallelism overflows Int for
        // parallelism > 2^28, which would make the check pass spuriously.
        require(memoryKib.toLong() >= 8L * parallelism.toLong()) {
            "memoryKib=$memoryKib below Argon2 minimum (8 KiB per lane x $parallelism)"
        }

        val out = ByteArray(derivedKeyLengthBytes)
        val rc: Int = out.usePinned { outPinned ->
            password.usePinned { pwPinned ->
                salt.usePinned { saltPinned ->
                    argon2id_hash_raw(
                        t_cost = timeCost.convert(),
                        m_cost = memoryKib.convert(),
                        parallelism = parallelism.convert(),
                        pwd = pwPinned.addressOf(0),
                        pwdlen = password.size.convert(),
                        salt = saltPinned.addressOf(0),
                        saltlen = salt.size.convert(),
                        hash = outPinned.addressOf(0),
                        hashlen = derivedKeyLengthBytes.convert()
                    )
                }
            }
        }

        if (rc != 0) {
            // libsodium returns -1 on allocation failure (m_cost can't be
            // satisfied) or invalid parameters. Zeroize the output buffer —
            // it may contain partial state.
            out.wipe()
            throw KdfException(
                "argon2id_hash_raw failed (rc=$rc); " +
                    "likely m_cost ($memoryKib KiB) could not be allocated"
            )
        }
        // The caller owns [password] and [salt] and is responsible for wiping
        // them — this function may be called more than once with the same
        // buffers (see KeyManager's Argon2id -> PBKDF2 fallback), so it must
        // not wipe its inputs.
        return out
    }
}
