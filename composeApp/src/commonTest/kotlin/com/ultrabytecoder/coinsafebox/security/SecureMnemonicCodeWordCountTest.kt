package com.ultrabytecoder.coinsafebox.security

import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SecureMnemonicCodeWordCountTest {

    @Test
    fun entropySizeForMapping() {
        assertEquals(16, SecureMnemonicCode.entropySizeFor(12))
        assertEquals(20, SecureMnemonicCode.entropySizeFor(15))
        assertEquals(24, SecureMnemonicCode.entropySizeFor(18))
        assertEquals(28, SecureMnemonicCode.entropySizeFor(21))
        assertEquals(32, SecureMnemonicCode.entropySizeFor(24))
    }

    @Test
    fun entropySizeForRejectsInvalidCounts() {
        assertFailsWith<IllegalArgumentException> { SecureMnemonicCode.entropySizeFor(11) }
        assertFailsWith<IllegalArgumentException> { SecureMnemonicCode.entropySizeFor(13) }
        assertFailsWith<IllegalArgumentException> { SecureMnemonicCode.entropySizeFor(0) }
    }

    @Test
    fun generateAcceptsAllSupportedEntropySizes() {
        val expectedWords = mapOf(16 to 12, 20 to 15, 24 to 18, 28 to 21, 32 to 24)
        for ((size, words) in expectedWords) {
            val entropy = ByteArray(size) { (it + size).toByte() }
            val mnemonic = SecureMnemonicCode.generate(entropy)
            try {
                SecureMnemonicCode.validate(mnemonic)
                assertEquals(words, mnemonic.concatToString().split(" ").size)
                // Interop sanity: the reference implementation accepts it too.
                MnemonicCode.validate(mnemonic.concatToString())
            } finally {
                mnemonic.wipe()
            }
        }
    }

    @Test
    fun generateRejectsUnsupportedEntropySizes() {
        assertFailsWith<IllegalArgumentException> { SecureMnemonicCode.generate(ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { SecureMnemonicCode.generate(ByteArray(17)) }
        assertFailsWith<IllegalArgumentException> { SecureMnemonicCode.generate(ByteArray(0)) }
    }

    @Test
    fun toSeedMatchesReferenceForAllWordCounts() {
        val expectedWords = mapOf(16 to 12, 20 to 15, 24 to 18, 28 to 21, 32 to 24)
        val passphrase = "correct horse battery staple"
        for ((size, _) in expectedWords) {
            val entropy = ByteArray(size) { (it * 7 + size).toByte() }
            val mnemonic = SecureMnemonicCode.generate(entropy)
            try {
                val secureSeed = SecureMnemonicCode.toSeed(mnemonic, passphrase.toCharArray())
                val referenceSeed = MnemonicCode.toSeed(mnemonic.concatToString(), passphrase)
                assertContentEquals(referenceSeed, secureSeed, "seed mismatch for $size-byte entropy")
                secureSeed.wipe()
                referenceSeed.wipe()
            } finally {
                mnemonic.wipe()
            }
        }
    }
}
