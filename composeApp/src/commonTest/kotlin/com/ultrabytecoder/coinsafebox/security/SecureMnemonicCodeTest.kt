package com.ultrabytecoder.coinsafebox.security

import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SecureMnemonicCodeTest {

    // Standard BIP-39 test vectors (entropy bits: 128/160/192/224/256).
    private data class Vector(val mnemonic: String, val passphrase: String)

    private val vectors = listOf(
        Vector("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", ""),
        Vector("legal winner thank year wave sausage worth useful legal winner thank yellow", ""),
        Vector("letter advice cage absurd amount doctor acoustic avoid letter advice cage above", ""),
        Vector("zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong", ""),
        Vector("panther category order step choose success lose cousin opal warm flood outside", "test")
    )

    @Test
    fun testSeedMatchesReference() {
        for (vector in vectors) {
            val mnemonic = vector.mnemonic
            val passphrase = vector.passphrase
            val mnemonicChars = mnemonic.toCharArray()
            val passphraseChars = passphrase.toCharArray()
            val secureSeed = SecureMnemonicCode.toSeed(mnemonicChars, passphraseChars)
            val referenceSeed = MnemonicCode.toSeed(mnemonic, passphrase)
            assertContentEquals(referenceSeed, secureSeed, "seed mismatch for: $mnemonic")
        }
    }

    @Test
    fun testValidateValidMnemonic() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        SecureMnemonicCode.validate(mnemonic.toCharArray())
    }

    @Test
    fun testValidateInvalidWord() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon aboot"
        assertFailsWith<IllegalArgumentException> {
            SecureMnemonicCode.validate(mnemonic.toCharArray())
        }
    }

    @Test
    fun testValidateInvalidChecksum() {
        // Valid words but broken checksum (last word changed).
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"
        assertFailsWith<IllegalArgumentException> {
            SecureMnemonicCode.validate(mnemonic.toCharArray())
        }
    }

    @Test
    fun testEmptyPassphrase() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val secureSeed = SecureMnemonicCode.toSeed(mnemonic.toCharArray(), CharArray(0))
        val referenceSeed = MnemonicCode.toSeed(mnemonic, "")
        assertContentEquals(referenceSeed, secureSeed)
    }

    @Test
    fun testNonAsciiPassphraseMatchesReference() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val passphrase = "café"
        val secureSeed = SecureMnemonicCode.toSeed(mnemonic.toCharArray(), passphrase.toCharArray())
        val referenceSeed = MnemonicCode.toSeed(mnemonic, passphrase)
        assertContentEquals(referenceSeed, secureSeed)
    }
}
