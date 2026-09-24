package com.ultrabytecoder.coinsafebox.security

import kotlin.test.Test
import kotlin.test.assertTrue

class SecureMemoryTest {

    @Test
    fun byteArrayWipeZeroesAllElements() {
        val data = byteArrayOf(0x01, 0x42, 0x7F.toByte(), 0x00, 0xFF.toByte())
        data.wipe()
        assertTrue(data.all { it == 0.toByte() }, "ByteArray must be all zeros after wipe()")
    }

    @Test
    fun charArrayWipeZeroesAllElements() {
        val data = charArrayOf('a', 'Z', '1', '!', '\u0000')
        data.wipe()
        assertTrue(data.all { it == '\u0000' }, "CharArray must be all null chars after wipe()")
    }

    @Test
    fun wipeKeepsArraySize() {
        val bytes = ByteArray(32) { it.toByte() }
        bytes.wipe()
        assertTrue(bytes.size == 32)

        val chars = CharArray(24) { 'x' }
        chars.wipe()
        assertTrue(chars.size == 24)
    }

    @Test
    fun wipeOnEmptyArrayDoesNotThrow() {
        ByteArray(0).wipe()
        CharArray(0).wipe()
    }
}