package com.ultrabytecoder.coinsafebox.security

import fr.acinq.bitcoin.Crypto
import org.kotlincrypto.random.CryptoRand

/**
 * Combines fresh system (CSPRNG) entropy with user-provided additional entropy
 * (the gesture digest) into BIP-39 root entropy.
 *
 * Mixing is `HMAC-SHA-256(key = systemEntropy, data = DOMAIN || additionalEntropy)`
 * truncated to the required entropy length, so the CSPRNG output acts as the key
 * and the (lower-entropy) gesture input can never dominate or bias the result.
 *
 * The HMAC construction (RFC 2104) is built directly on `Crypto.sha256` from
 * `bitcoin-kmp` — the same native-backed digest the rest of the app already
 * uses (JVM `MessageDigest` on Android/Desktop, CommonCrypto on iOS). The
 * library's own `Digest.hmac` extension is not usable: it is missing from the
 * published common metadata of bitcoin-kmp 0.30.0 (present in the JVM bytecode
 * but absent from the native/common klibs), so it does not resolve from
 * commonMain.
 *
 * [ENTROPY_COMBINER_DOMAIN] is a committed, non-secret domain separator. A future
 * algorithm revision must change it (e.g. `/v2`); old wallets stay recoverable
 * because the BIP-39 mnemonic — not the combined entropy — is the recovery
 * artifact.
 */
object EntropyCombiner {

    /** ASCII bytes of "CoinSafeBox/v1/entropy-combiner" (30 bytes). */
    val ENTROPY_COMBINER_DOMAIN: ByteArray = byteArrayOf(
        75, 114, 121, 112, 116, 97, 75, 101, 101, 112, // "CoinSafeBox"
        47, 118, 49, // "/v1"
        47, 101, 110, 116, 114, 111, 112, 121, 45, 99, // "/entropy-c"
        111, 109, 98, 105, 110, 101, 114 // "ombiner"
    )

    /**
     * `HMAC-SHA-256(key = systemEntropy, data = DOMAIN || additionalEntropy)`,
     * truncated to [outLen] bytes. Wipes all intermediates; the caller owns the
     * result and must wipe it.
     */
    fun combine(
        systemEntropy: ByteArray,
        additionalEntropy: ByteArray,
        outLen: Int
    ): ByteArray {
        require(outLen in listOf(16, 20, 24, 28, 32)) {
            "outLen must be 16, 20, 24, 28, or 32 bytes"
        }
        require(systemEntropy.size == outLen) {
            "systemEntropy size must match outLen"
        }
        require(additionalEntropy.isNotEmpty()) {
            "additionalEntropy must not be empty"
        }

        val data = ByteArray(ENTROPY_COMBINER_DOMAIN.size + additionalEntropy.size)
        ENTROPY_COMBINER_DOMAIN.copyInto(data)
        additionalEntropy.copyInto(data, ENTROPY_COMBINER_DOMAIN.size)

        val hmac = try {
            hmacSha256(key = systemEntropy, data = data)
        } finally {
            data.wipe()
        }
        try {
            return hmac.copyOfRange(0, outLen)
        } finally {
            hmac.wipe()
        }
    }

    /**
     * RFC 2104 HMAC-SHA-256: `H((K xor opad) || H((K xor ipad) || data))`,
     * block size 64. The key is zero-padded to 64 bytes, or hashed first when
     * longer. All intermediates are wiped; the caller owns the result.
     */
    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val blockSize = 64
        val key0 = ByteArray(blockSize)
        if (key.size > blockSize) {
            val hashedKey = Crypto.sha256(key)
            try {
                hashedKey.copyInto(key0)
            } finally {
                hashedKey.wipe()
            }
        } else {
            key.copyInto(key0)
        }
        val ipad = ByteArray(blockSize)
        val opad = ByteArray(blockSize)
        try {
            for (i in 0 until blockSize) {
                ipad[i] = (key0[i].toInt() xor 0x36).toByte()
                opad[i] = (key0[i].toInt() xor 0x5C).toByte()
            }
            val innerInput = ByteArray(blockSize + data.size)
            val outerInput = ByteArray(blockSize + 32)
            try {
                ipad.copyInto(innerInput)
                data.copyInto(innerInput, blockSize)
                val inner = Crypto.sha256(innerInput)
                try {
                    opad.copyInto(outerInput)
                    inner.copyInto(outerInput, blockSize)
                    return Crypto.sha256(outerInput)
                } finally {
                    inner.wipe()
                }
            } finally {
                innerInput.wipe()
                outerInput.wipe()
            }
        } finally {
            key0.wipe()
            ipad.wipe()
            opad.wipe()
        }
    }

    /**
     * Draws fresh CSPRNG system entropy, optionally mixes it with
     * [additionalEntropy] via [combine], and returns the BIP-39 mnemonic as a
     * wipe-able [CharArray].
     *
     * Wipes the system entropy and the combined entropy in a finally block.
     * Does NOT wipe [additionalEntropy] — the caller owns it and must wipe it
     * after this call returns.
     */
    fun generate(wordCount: Int, additionalEntropy: ByteArray?): CharArray {
        require(wordCount in SecureMnemonicCode.SUPPORTED_WORD_COUNTS) {
            "wordCount must be one of ${SecureMnemonicCode.SUPPORTED_WORD_COUNTS}"
        }
        val entropySize = SecureMnemonicCode.entropySizeFor(wordCount)
        val systemEntropy = ByteArray(entropySize)
        var combinedEntropy: ByteArray? = null
        try {
            CryptoRand.Default.nextBytes(systemEntropy)
            combinedEntropy = if (additionalEntropy == null) {
                systemEntropy.copyOf()
            } else {
                combine(systemEntropy, additionalEntropy, entropySize)
            }
            return SecureMnemonicCode.generate(combinedEntropy)
        } finally {
            systemEntropy.wipe()
            combinedEntropy?.wipe()
        }
    }
}
