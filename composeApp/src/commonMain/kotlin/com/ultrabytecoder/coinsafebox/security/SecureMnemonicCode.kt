package com.ultrabytecoder.coinsafebox.security

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.MnemonicCode

/**
 * BIP-39 mnemonic validation and seed derivation WITHOUT materializing the
 * recovery phrase (or passphrase) as an immutable String.
 *
 * The wordlist and SHA-256 checksum come from `libs.bitcoin.kmp`; the PBKDF2
 * step uses [Pbkdf2] with HMAC-SHA512 (BIP-39 requirement). For the same input
 * [toSeed] produces byte-identical output to `MnemonicCode.toSeed(str, str)`.
 */
object SecureMnemonicCode {

    private const val BIP39_ITERATIONS = 2048
    private const val BIP39_SEED_LENGTH = 64

    /** Word counts accepted by this implementation (BIP-39). */
    val SUPPORTED_WORD_COUNTS: List<Int> = listOf(12, 15, 18, 21, 24)

    /** BIP-39 word count -> root entropy size in bytes. */
    fun entropySizeFor(wordCount: Int): Int = when (wordCount) {
        12 -> 16
        15 -> 20
        18 -> 24
        21 -> 28
        24 -> 32
        else -> throw IllegalArgumentException("invalid word count $wordCount")
    }

    private val WORDLIST: List<String> = MnemonicCode.englishWordlist
    private val WORDLIST_CHARS: Array<CharArray> = WORDLIST.map { it.toCharArray() }.toTypedArray()

    /**
     * Validates a BIP-39 mnemonic given as a space-separated [CharArray].
     *
     * @throws IllegalArgumentException when a word is not in the wordlist, the
     * word count is not a multiple of 3, or the checksum is invalid.
     */
    fun validate(mnemonic: CharArray) {
        val words = splitOnSpace(mnemonic)
        // Use primitive arrays (not boxed List<Int>/List<Boolean>) so every
        // intermediate can be wiped in a finally block. A boxed List holds
        // immutable Integer/Boolean objects that persist on the heap until
        // GC and cannot be zeroed — and `indexes` alone reconstructs the
        // full mnemonic via wordlist lookup. Declared before the try so the
        // finally block can wipe them even if an early require() throws.
        val indexes = IntArray(words.size)
        val bits = BooleanArray(words.size * 11)
        try {
            require(words.isNotEmpty()) { "mnemonic code cannot be empty" }
            require(words.size % 3 == 0) { "invalid mnemonic word count ${words.size}, it must be a multiple of 3" }

            for (w in words.indices) {
                // Accepted risk: linear wordlist lookup is not constant-time (word
                // position leaks into comparison count). Not exploitable here — the
                // mnemonic is typed locally through the UI with no remote timing
                // oracle, and a HashMap lookup would materialize wordlist entries
                // as immutable Strings, violating the zero-String secret policy.
                val idx = WORDLIST_CHARS.indexOfFirst { it.contentEquals(words[w]) }
                if (idx < 0) throw IllegalArgumentException("invalid mnemonic word")
                indexes[w] = idx
            }

            var bitPos = 0
            for (index in indexes) {
                for (bit in 10 downTo 0) {
                    bits[bitPos++] = (index shr bit) and 1 == 1
                }
            }
            val bitLength = (bits.size * 32) / 33
            val checksumBitCount = bitLength / 32

            val data = ByteArray(bitLength / 8)
            try {
                for (i in data.indices) {
                    var byte = 0
                    for (j in 0 until 8) {
                        byte = (byte shl 1) or (if (bits[i * 8 + j]) 1 else 0)
                    }
                    data[i] = byte.toByte()
                }
                val hash = Crypto.sha256(data)
                try {
                    // Compare the stored checksum bits (bits[bitLength..])
                    // directly against the recomputed SHA-256 checksum bits.
                    for (i in 0 until checksumBitCount) {
                        val expected = (hash[i / 8].toInt() shr (7 - (i % 8))) and 1 == 1
                        if (bits[bitLength + i] != expected) {
                            throw IllegalArgumentException("invalid checksum")
                        }
                    }
                } finally {
                    hash.wipe()
                }
            } finally {
                data.wipe()
            }
        } finally {
            // Wipe every mnemonic-derived intermediate so the entropy does not
            // linger in the heap, plus each word copy the caller cannot reach.
            indexes.fill(0)
            bits.fill(false)
            words.forEach { it.wipe() }
        }
    }

    /**
     * Derives the 64-byte BIP-39 seed:
     * `PBKDF2(mnemonic_utf8, "mnemonic" + passphrase_utf8, 2048, 64, HMAC-SHA512)`.
     *
     * Byte-identical to `MnemonicCode.toSeed(mnemonicStr, passphraseStr)`.
     * The caller owns the returned array and must wipe it when done.
     */
    fun toSeed(mnemonic: CharArray, passphrase: CharArray): ByteArray {
        val mnemonicBytes = mnemonic.toPinBytes()
        val salt = buildSalt(passphrase)
        return try {
            Pbkdf2.derive(
                password = mnemonicBytes,
                salt = salt,
                iterations = BIP39_ITERATIONS,
                derivedKeyLengthBytes = BIP39_SEED_LENGTH,
                algorithm = Pbkdf2Algorithm.SHA512
            )
        } finally {
            mnemonicBytes.wipe()
            salt.wipe()
        }
    }

    /**
     * Generates a BIP-39 mnemonic from [entropy] without materializing the
     * phrase as an immutable String. Returns a space-separated [CharArray].
     *
     * The caller owns [entropy] (CSPRNG output) and must wipe it after this
     * call returns — it is the root secret and this function does not wipe it.
     *
     * @param entropy 16, 20, 24, 28, or 32 bytes of CSPRNG output.
     */
    fun generate(entropy: ByteArray): CharArray {
        require(entropy.size in listOf(16, 20, 24, 28, 32)) {
            "entropy must be 16, 20, 24, 28, or 32 bytes (was ${entropy.size})"
        }
        val entropyBits = BooleanArray(entropy.size * 8)
        try {
            var bitPos = 0
            for (b in entropy) {
                val v = b.toInt()
                for (shift in 7 downTo 0) {
                    entropyBits[bitPos++] = (v shr shift) and 1 == 1
                }
            }
            val checksumBitCount = entropy.size * 8 / 32
            val hash = Crypto.sha256(entropy)
            try {
                val checksumBits = BooleanArray(checksumBitCount)
                try {
                    for (i in 0 until checksumBitCount) {
                        checksumBits[i] = (hash[i / 8].toInt() shr (7 - (i % 8))) and 1 == 1
                    }
                    val allBits = BooleanArray(entropyBits.size + checksumBits.size)
                    try {
                        entropyBits.copyInto(allBits)
                        checksumBits.copyInto(allBits, destinationOffset = entropyBits.size)
                        val wordCount = allBits.size / 11
                        val words = (0 until wordCount).map { w ->
                            var index = 0
                            for (b in 0 until 11) {
                                index = (index shl 1) or (if (allBits[w * 11 + b]) 1 else 0)
                            }
                            WORDLIST_CHARS[index]
                        }
                        val totalLen = words.sumOf { it.size + 1 } - 1
                        val result = CharArray(totalLen)
                        var pos = 0
                        for (w in words.indices) {
                            val word = words[w]
                            word.copyInto(result, destinationOffset = pos)
                            pos += word.size
                            if (w < wordCount - 1) result[pos++] = ' '
                        }
                        return result
                    } finally {
                        allBits.fill(false)
                    }
                } finally {
                    checksumBits.fill(false)
                }
            } finally {
                hash.wipe()
            }
        } finally {
            // entropyBits holds the full root entropy in bit form — wipe it.
            entropyBits.fill(false)
        }
    }

    /** Splits a [CharArray] on single spaces into word slices (no String allocation). */
    private fun splitOnSpace(mnemonic: CharArray): List<CharArray> {
        val words = mutableListOf<CharArray>()
        var start = 0
        var i = 0
        while (i <= mnemonic.size) {
            if (i == mnemonic.size || mnemonic[i] == ' ') {
                if (i > start) words.add(mnemonic.copyOfRange(start, i))
                start = i + 1
            }
            i++
        }
        return words
    }

    private fun buildSalt(passphrase: CharArray): ByteArray {
        val prefix = "mnemonic".encodeToByteArray()
        val passBytes = passphrase.toPinBytes()
        return try {
            prefix + passBytes
        } finally {
            passBytes.wipe()
        }
    }
}
