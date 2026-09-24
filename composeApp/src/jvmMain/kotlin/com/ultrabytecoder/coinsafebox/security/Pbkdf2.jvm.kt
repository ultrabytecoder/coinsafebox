package com.ultrabytecoder.coinsafebox.security

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter

/**
 * PBKDF2-HMAC via BouncyCastle's [PKCS5S2ParametersGenerator], which consumes the
 * password as a raw byte array.
 *
 * The previous implementation fed the password bytes through a `CharArray`
 * (`byte.toInt().toChar()`) into the JCA `PBEKeySpec`. For any byte >= 0x80 that
 * conversion sign-extends and truncates into the `\uFF80`–`\uFFFF` range, and the
 * JCA provider then re-encodes those chars to UTF-8 — producing a different byte
 * sequence than the original. That corrupted non-ASCII passwords and made
 * derivations disagree with iOS (which uses the raw bytes via CommonCrypto).
 * BouncyCastle removes the char round-trip entirely, so the password bytes are used
 * verbatim on every platform.
 */
actual object Pbkdf2 {
    actual fun derive(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        derivedKeyLengthBytes: Int,
        algorithm: Pbkdf2Algorithm
    ): ByteArray {
        val digest = when (algorithm) {
            Pbkdf2Algorithm.SHA256 -> SHA256Digest()
            Pbkdf2Algorithm.SHA512 -> SHA512Digest()
        }
        val generator = PKCS5S2ParametersGenerator(digest)
        generator.init(password, salt, iterations)
        val key = (generator.generateDerivedParameters(derivedKeyLengthBytes * 8) as KeyParameter).key
        // The generator copies the password into its own state during init(); the
        // caller still owns [password] and wipes it after this call returns.
        return key
    }
}
