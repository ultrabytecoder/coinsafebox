package com.ultrabytecoder.coinsafebox.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EntropyCombinerTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

    @Test
    fun domainSeparatorIsCommitted() {
        assertEquals(30, EntropyCombiner.ENTROPY_COMBINER_DOMAIN.size)
        assertContentEquals(
            "CoinSafeBox/v1/entropy-combiner".encodeToByteArray(),
            EntropyCombiner.ENTROPY_COMBINER_DOMAIN
        )
    }

    @Test
    fun combineMatchesReferenceVectors() {
        // Vectors pre-computed with Python's hmac (SHA-256):
        // key = 0x11 * outLen, data = DOMAIN || (0xAA * 32), truncated to outLen.
        val additional = ByteArray(32) { 0xAA.toByte() }
        assertContentEquals(
            hex("c19d4f84e7672478d7ed11d93e6fec9e"),
            EntropyCombiner.combine(ByteArray(16) { 0x11 }, additional, 16)
        )
        assertContentEquals(
            hex("bab70794009e8bb707b6da4e325c7dc70e8065e3"),
            EntropyCombiner.combine(ByteArray(20) { 0x11 }, additional, 20)
        )
        assertContentEquals(
            hex("806b060fa779c8fde828ebfb47a934a024a819dbd7470f1f"),
            EntropyCombiner.combine(ByteArray(24) { 0x11 }, additional, 24)
        )
        assertContentEquals(
            hex("0ae9174bc0f5e84db503f85fe8d9efc0a6ad19115e19103a60c7ce3f"),
            EntropyCombiner.combine(ByteArray(28) { 0x11 }, additional, 28)
        )
        assertContentEquals(
            hex("dc4fbce62f10986173de6604dde7304004a15181021ae6576313b3414e27d624"),
            EntropyCombiner.combine(ByteArray(32) { 0x11 }, additional, 32)
        )
    }

    @Test
    fun combineRejectsInvalidArguments() {
        val additional = ByteArray(1) { 1 }
        assertFailsWith<IllegalArgumentException> {
            EntropyCombiner.combine(ByteArray(16) { 1 }, additional, 15)
        }
        assertFailsWith<IllegalArgumentException> {
            EntropyCombiner.combine(ByteArray(15) { 1 }, additional, 16)
        }
        assertFailsWith<IllegalArgumentException> {
            EntropyCombiner.combine(ByteArray(16) { 1 }, ByteArray(0), 16)
        }
    }

    @Test
    fun combineDoesNotMutateInputs() {
        val system = ByteArray(16) { 0x11 }
        val additional = ByteArray(4) { 0xAA.toByte() }
        val systemCopy = system.copyOf()
        val additionalCopy = additional.copyOf()
        EntropyCombiner.combine(system, additional, 16)
        assertContentEquals(systemCopy, system)
        assertContentEquals(additionalCopy, additional)
    }

    @Test
    fun generateProducesValidMnemonicsForAllWordCounts() {
        val additional = ByteArray(32) { 0xAA.toByte() }
        for (wordCount in SecureMnemonicCode.SUPPORTED_WORD_COUNTS) {
            val mnemonic = EntropyCombiner.generate(wordCount, additional)
            try {
                SecureMnemonicCode.validate(mnemonic)
                val words = mnemonic.splitOnSpaceForTest()
                assertEquals(wordCount, words.size, "word count for $wordCount")
            } finally {
                mnemonic.wipe()
            }
        }
    }

    @Test
    fun generateWithoutAdditionalEntropyProducesValidMnemonic() {
        val mnemonic = EntropyCombiner.generate(12, null)
        try {
            SecureMnemonicCode.validate(mnemonic)
            assertEquals(12, mnemonic.splitOnSpaceForTest().size)
        } finally {
            mnemonic.wipe()
        }
    }

    @Test
    fun generateRejectsInvalidWordCount() {
        assertFailsWith<IllegalArgumentException> {
            EntropyCombiner.generate(13, ByteArray(1) { 1 })
        }
    }

    @Test
    fun differentAdditionalEntropyChangesMnemonic() {
        val a = EntropyCombiner.generate(12, ByteArray(32) { 1 })
        val b = EntropyCombiner.generate(12, ByteArray(32) { 2 })
        try {
            // With overwhelming probability the phrases differ.
            assertTrue(a.contentToString() != b.contentToString())
        } finally {
            a.wipe()
            b.wipe()
        }
    }
}

private fun CharArray.splitOnSpaceForTest(): List<String> =
    concatToString().split(" ")
