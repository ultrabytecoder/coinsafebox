package com.ultrabytecoder.coinsafebox.providers.ton.boc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BitStringTest {

    @Test
    fun shouldReadBits() {
        val bs = BitString(byteArrayOf(0b10101010.toByte()), 0, 8)
        assertTrue(bs.at(0))
        assertTrue(!bs.at(1))
        assertTrue(bs.at(2))
        assertTrue(!bs.at(3))
        assertTrue(bs.at(4))
        assertTrue(!bs.at(5))
        assertTrue(bs.at(6))
        assertTrue(!bs.at(7))
        assertEquals("AA", bs.toString())
    }

    @Test
    fun shouldEquals() {
        val a = BitString(byteArrayOf(0b10101010.toByte()), 0, 8)
        val b = BitString(byteArrayOf(0b10101010.toByte()), 0, 8)
        val c = BitString(byteArrayOf(0, 0b10101010.toByte()), 8, 8)
        assertEquals(a, b)
        assertEquals(b, a)
        assertEquals(a, c)
        assertEquals(c, a)
        assertEquals("AA", a.toString())
        assertEquals("AA", b.toString())
        assertEquals("AA", c.toString())
    }

    @Test
    fun shouldFormatStrings() {
        assertEquals("4_", BitString(byteArrayOf(0b00000000.toByte()), 0, 1).toString())
        assertEquals("C_", BitString(byteArrayOf(0b10000000.toByte()), 0, 1).toString())
        assertEquals("E_", BitString(byteArrayOf(0b11000000.toByte()), 0, 2).toString())
        assertEquals("F_", BitString(byteArrayOf(0b11100000.toByte()), 0, 3).toString())
        assertEquals("E", BitString(byteArrayOf(0b11100000.toByte()), 0, 4).toString())
        assertEquals("EC_", BitString(byteArrayOf(0b11101000.toByte()), 0, 5).toString())
    }

    @Test
    fun shouldDoSubbuffers() {
        val bs = BitString(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 0, 64)
        val bs2 = bs.subbuffer(0, 16)
        assertEquals(2, bs2!!.size)
    }

    @Test
    fun shouldDoSubstrings() {
        val bs = BitString(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 0, 64)
        val bs2 = bs.substring(0, 16)
        assertEquals(16, bs2.length)
    }

    @Test
    fun shouldDoEmptySubstringsWithZeroLength() {
        val bs = BitString(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 0, 64)
        val bs2 = bs.substring(bs.length, 0)
        assertEquals(0, bs2.length)
    }

    @Test
    fun shouldOOBWhenSubstringOffsetOutOfBounds() {
        val bs = BitString(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 0, 64)
        assertOOB { bs.substring(bs.length + 1, 0) }
        assertOOB { bs.substring(-1, 0) }
    }

    @Test
    fun shouldOOBWhenSubbufferOffsetOutOfBounds() {
        val bs = BitString(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 0, 64)
        assertEquals(null, bs.subbuffer(bs.length + 1, 0))
        assertEquals(null, bs.subbuffer(-1, 0))
    }

    @Test
    fun shouldOOBWhenOffsetAtEndAndLengthPositive() {
        val bs = BitString(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 0, 64)
        assertOOB { bs.substring(bs.length, 1) }
    }

    @Test
    fun shouldDoEmptySubbufferWithZeroLength() {
        val bs = BitString(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 0, 64)
        val bs2 = bs.subbuffer(bs.length, 0)
        assertEquals(0, bs2!!.size)
    }

    @Test
    fun shouldOOBWhenSubbufferOffsetAtEndAndLengthPositive() {
        val bs = BitString(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 0, 64)
        assertEquals(null, bs.subbuffer(bs.length, 8))
    }

    @Test
    fun shouldProcessMonkeyStrings() {
        val cases = listOf(
            "001110101100111010" to "3ACEA_",
            "01001" to "4C_",
            "000000110101101010" to "035AA_",
            "1000011111100010111110111" to "87E2FBC_",
            "0111010001110010110" to "7472D_",
            "" to "",
            "0101" to "5",
            "010110111010100011110101011110" to "5BA8F57A_",
            "00110110001101" to "3636_",
            "1110100" to "E9_",
            "010111000110110" to "5C6D_",
            "01" to "6_",
            "1000010010100" to "84A4_",
            "010000010" to "414_",
            "110011111" to "CFC_",
            "11000101001101101" to "C536C_",
            "011100111" to "73C_",
            "11110011" to "F3",
            "011001111011111000" to "67BE2_",
            "10101100000111011111" to "AC1DF",
            "0100001000101110" to "422E",
            "000110010011011101" to "19376_",
            "10111001" to "B9",
            "011011000101000001001001110000" to "6C5049C2_",
            "0100011101" to "476_",
            "01001101000001" to "4D06_",
            "00010110101" to "16B_",
            "01011011110" to "5BD_",
            "1010101010111001011101" to "AAB976_",
            "00011" to "1C_",
            "11011111111001111100" to "DFE7C",
            "1110100100110111001101011111000" to "E93735F1_",
            "10011110010111100110100000" to "9E5E682_",
            "00100111110001100111001110" to "27C673A_",
            "01010111011100000000001110000" to "57700384_",
            "010000001011111111111000" to "40BFF8",
            "0011110001111000110101100001" to "3C78D61",
            "101001011011000010" to "A5B0A_",
            "1111" to "F",
            "10101110" to "AE",
            "1001" to "9",
            "001010010" to "294_",
            "110011" to "CE_",
            "10000000010110" to "805A_",
            "11000001101000100" to "C1A24_",
            "1" to "C_",
            "0100101010000010011101111" to "4A8277C_",
            "10" to "A_",
            "1010110110110110110100110010110" to "ADB6D32D_",
            "010100000000001000111101011001" to "50023D66_"
        )
        for ((binary, expected) in cases) {
            val builder = BitBuilder()
            for (ch in binary) {
                builder.writeBit(ch == '1')
            }
            val r = builder.build()
            for (i in binary.indices) {
                assertEquals(binary[i] == '1', r.at(i), "Bit mismatch at index $i for '$binary'")
            }
            assertEquals(expected, r.toString(), "toString mismatch for '$binary'")
        }
    }

    private fun assertOOB(block: () -> Unit) {
        var threw = false
        try {
            block()
        } catch (e: Exception) {
            threw = true
            assertTrue(e.message?.contains("out of bounds", ignoreCase = true) == true ||
                e.message?.contains("Offset", ignoreCase = true) == true,
                "Expected OOB error but got: ${e.message}")
        }
        assertTrue(threw, "Expected an exception to be thrown")
    }
}
