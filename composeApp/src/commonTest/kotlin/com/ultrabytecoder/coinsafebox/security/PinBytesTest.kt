package com.ultrabytecoder.coinsafebox.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PinBytesTest {

    @Test
    fun testAsciiDigits() {
        val chars = charArrayOf('1', '2', '3', '4')
        assertContentEquals(byteArrayOf(49, 50, 51, 52), chars.toPinBytes())
    }

    @Test
    fun testNonAsciiChar() {
        val chars = charArrayOf('é')
        assertContentEquals(byteArrayOf(0xC3.toByte(), 0xA9.toByte()), chars.toPinBytes())
    }

    @Test
    fun testSurrogatePair() {
        val chars = charArrayOf(0xD83D.toChar(), 0xDE00.toChar())
        assertContentEquals(
            byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte()),
            chars.toPinBytes()
        )
    }

    @Test
    fun testEmptyArray() {
        assertContentEquals(ByteArray(0), CharArray(0).toPinBytes())
    }

    @Test
    fun testMatchesUtf8Encoding() {
        val chars = "café 123".toCharArray()
        assertContentEquals("café 123".encodeToByteArray(), chars.toPinBytes())
    }
}
