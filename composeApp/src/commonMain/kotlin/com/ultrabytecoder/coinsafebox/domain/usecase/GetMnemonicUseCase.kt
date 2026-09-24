package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
import com.ultrabytecoder.coinsafebox.security.wipe

class GetMnemonicUseCase(
    private val walletRepository: WalletRepository
) {
    /**
     * Returns the stored mnemonic as a [CharArray]. The stored BLOB is wrapped with
     * the device hardware key (Android Keystore / iOS Secure Enclave, see
     * [com.ultrabytecoder.coinsafebox.security.SecretCipher]) on top of the
     * SQLCipher database. Returns null when the mnemonic was never stored. The
     * caller owns the returned array and must wipe it when done.
     */
    suspend operator fun invoke(walletId: Long): CharArray? {
        val stored = walletRepository.getStoredMnemonic(walletId) ?: return null
        return try {
            decodeUtf8(stored)
        } finally {
            stored.wipe()
        }
    }

    private fun decodeUtf8(bytes: ByteArray): CharArray {
        val chars = CharArray(bytes.size)
        var ci = 0
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b < 0x80 -> {
                    chars[ci++] = b.toChar()
                    i++
                }
                b < 0xE0 -> {
                    if (i + 1 < bytes.size) {
                        val b2 = bytes[i + 1].toInt() and 0xFF
                        chars[ci++] = (((b and 0x1F) shl 6) or (b2 and 0x3F)).toChar()
                        i += 2
                    } else {
                        chars[ci++] = b.toChar()
                        i++
                    }
                }
                b < 0xF0 -> {
                    if (i + 2 < bytes.size) {
                        val b2 = bytes[i + 1].toInt() and 0xFF
                        val b3 = bytes[i + 2].toInt() and 0xFF
                        chars[ci++] = (((b and 0x0F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)).toChar()
                        i += 3
                    } else {
                        chars[ci++] = b.toChar()
                        i++
                    }
                }
                else -> {
                    if (i + 3 < bytes.size) {
                        val b2 = bytes[i + 1].toInt() and 0xFF
                        val b3 = bytes[i + 2].toInt() and 0xFF
                        val b4 = bytes[i + 3].toInt() and 0xFF
                        val cp = ((b and 0x07) shl 18) or ((b2 and 0x3F) shl 12) or ((b3 and 0x3F) shl 6) or (b4 and 0x3F)
                        val off = cp - 0x10000
                        chars[ci++] = ((off ushr 10) or 0xD800).toChar()
                        chars[ci++] = ((off and 0x3FF) or 0xDC00).toChar()
                        i += 4
                    } else {
                        chars[ci++] = b.toChar()
                        i++
                    }
                }
            }
        }
        return if (ci == chars.size) chars else chars.copyOf(ci)
    }
}
