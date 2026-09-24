package com.ultrabytecoder.coinsafebox.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Known-answer tests for Argon2id cross-platform parity.
 *
 * The reference vectors were generated with the official Argon2 reference
 * implementation (via argon2-cffi) and verified byte-identical against
 * BouncyCastle's `Argon2BytesGenerator`. Running this test on every platform
 * (JVM via BouncyCastle, iOS via libsodium `argon2id_hash_raw`) proves all
 * backends produce the same KEK for the same (password, salt, t, m, p) — the
 * invariant that makes envelopes portable across devices.
 *
 * The vectors are inlined (not loaded from a resource file) because
 * `Class.getResource` is unreliable on Kotlin/Native test binaries; the
 * canonical copy lives in `commonTest/resources/argon2id_kat.json`.
 *
 * NOTE: these are *test reference vectors* (synthetic passwords/salts such as
 * "allzero-salt" and "allff-salt") used only to verify cross-platform KDF
 * parity. They are NOT production envelope parameters and must never be
 * copied into app code, fixtures, or committed as real key material.
 */
class Argon2idKatTest {

    private data class KatVector(
        val id: String,
        val passwordHex: String,
        val saltHex: String,
        val timeCost: Int,
        val memoryKib: Int,
        val parallelism: Int,
        val outLen: Int,
        val expectedHex: String
    )

    // Keep in sync with commonTest/resources/argon2id_kat.json.
    private val vectors = listOf(
        KatVector("pin-4digit", "31323334",
            "000102030405060708090a0b0c0d0e0f", 3, 65536, 1, 32,
            "25aff3856b38697ed256e1face06782e6b80c89602655565b6171a1f8122ba56"),
        KatVector("pin-6digit", "313233343536",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", 3, 65536, 1, 32,
            "481bda6e40e04cd11e4383557a6f442a2cf0e6984000dde853b5f94f1a4b7ad0"),
        KatVector("password-unicode", "61c3b1c3ab7362c3b6c3a772c3af66",
            "deadbeefdeadbeefdeadbeefdeadbeef", 3, 65536, 1, 32,
            "b8a73a93129abd96560a6f2050baafd2f6b7bf5071565581b1aa6754181ccf48"),
        KatVector("allzero-salt", "70617373776f7264",
            "0000000000000000000000000000000000000000000000000000000000000000", 3, 65536, 1, 32,
            "3b22574f683cc2f2fc3ef59c6c86342ad590e22d2f02f3f94d1bfe9a32e2aa04"),
        KatVector("allff-salt", "70617373776f7264",
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 3, 65536, 1, 32,
            "a8fb37744b290df7d1968788f2798111215177c161c404d62f532c3ac209537a"),
        KatVector("16byte-salt", "313233343536",
            "000102030405060708090a0b0c0d0e0f", 3, 65536, 1, 32,
            "9b0a9f24be07d83998f657d86a216621de37af21b9d3133fab03035e94fd58ec")
    )

    @Test
    fun parityWithReferenceVectors() {
        assertTrue(Kdf.supportsArgon2id, "platform must support Argon2id for this test")
        for (v in vectors) {
            val out = Kdf.derive(
                password = v.passwordHex.hexToBytes(),
                salt = v.saltHex.hexToBytes(),
                timeCost = v.timeCost,
                derivedKeyLengthBytes = v.outLen,
                memoryKib = v.memoryKib,
                parallelism = v.parallelism
            )
            try {
                assertEquals(v.expectedHex, out.toHexString(), "KAT ${v.id} failed")
            } finally {
                out.wipe()
            }
        }
    }
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { i ->
        ((Character.digit(get(i * 2), 16) shl 4) + Character.digit(get(i * 2 + 1), 16)).toByte()
    }
}

private fun ByteArray.toHexString(): String =
    joinToString("") { b -> "%02x".format(b) }
