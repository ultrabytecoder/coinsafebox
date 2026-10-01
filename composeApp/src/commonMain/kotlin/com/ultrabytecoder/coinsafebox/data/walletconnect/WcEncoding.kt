package com.ultrabytecoder.coinsafebox.data.walletconnect

object WcEncoding {

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    fun hexEncode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }

    fun hexDecode(str: String): ByteArray {
        val clean = if (str.startsWith("0x") || str.startsWith("0X")) str.substring(2) else str
        require(clean.length % 2 == 0) { "Invalid hex string length: ${clean.length}" }
        return ByteArray(clean.length / 2) { i ->
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex character at index ${i * 2}" }
            ((hi shl 4) or lo).toByte()
        }
    }

    private val B64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64Encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            sb.append(B64_CHARS[b0 ushr 2])
            sb.append(B64_CHARS[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (i + 1 < bytes.size) {
                sb.append(B64_CHARS[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            } else {
                sb.append('=')
            }
            if (i + 2 < bytes.size) {
                sb.append(B64_CHARS[b2 and 0x3F])
            } else {
                sb.append('=')
            }
            i += 3
        }
        return sb.toString()
    }

    fun base64Decode(str: String): ByteArray {
        val lookup = IntArray(128) { -1 }
        for (i in B64_CHARS.indices) lookup[B64_CHARS[i].code] = i
        lookup['-'.code] = 62
        lookup['_'.code] = 63

        val output = mutableListOf<Byte>()
        var buf = 0
        var bits = 0
        for (c in str) {
            if (c == '=') break
            val idx = if (c.code < 128) lookup[c.code] else -1
            if (idx < 0) continue
            buf = (buf shl 6) or idx
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.add(((buf shr bits) and 0xFF).toByte())
            }
        }
        return output.toByteArray()
    }

    private val B64URL_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun base64UrlEncode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            sb.append(B64URL_CHARS[b0 ushr 2])
            sb.append(B64URL_CHARS[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (i + 1 < bytes.size) {
                sb.append(B64URL_CHARS[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            }
            if (i + 2 < bytes.size) {
                sb.append(B64URL_CHARS[b2 and 0x3F])
            }
            i += 3
        }
        return sb.toString()
    }

    fun base64UrlDecode(str: String): ByteArray = base64Decode(str)

    private val URL_UNRESERVED: Set<Char> = buildSet {
        addAll('A'..'Z'); addAll('a'..'z'); addAll('0'..'9')
        add('-'); add('.'); add('_'); add('~')
    }

    fun urlEncode(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c in URL_UNRESERVED) {
                sb.append(c)
            } else {
                for (b in c.toString().encodeToByteArray()) {
                    val v = b.toInt() and 0xFF
                    sb.append('%')
                    sb.append(HEX_CHARS[v ushr 4].uppercase())
                    sb.append(HEX_CHARS[v and 0x0F].uppercase())
                }
            }
        }
        return sb.toString()
    }

    fun urlDecode(s: String): String {
        if (!s.contains('%') && !s.contains('+')) return s
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' -> {
                    val hi = Character.digit(s[i + 1], 16)
                    val lo = Character.digit(s[i + 2], 16)
                    if (hi >= 0 && lo >= 0) {
                        bytes.add(((hi shl 4) or lo).toByte())
                        i += 3
                        continue
                    }
                    bytes.add(c.code.toByte())
                }
                c == '+' -> {
                    bytes.add(' '.code.toByte())
                }
                else -> bytes.add(c.code.toByte())
            }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    private val B58_CHARS = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun base58btcEncode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++
        val bytes = input.copyOf()
        val encoded = CharArray(bytes.size * 2)
        var outputStart = encoded.size
        var inputStart = zeros
        while (inputStart < bytes.size) {
            encoded[--outputStart] = B58_CHARS[divmod(bytes, inputStart, 256, 58)]
            if (bytes[inputStart].toInt() == 0) inputStart++
        }
        while (outputStart < encoded.size && encoded[outputStart] == B58_CHARS[0]) outputStart++
        for (i in 0 until zeros) encoded[--outputStart] = B58_CHARS[0]
        return String(encoded, outputStart, encoded.size - outputStart)
    }

    private fun divmod(number: ByteArray, firstDigit: Int, base: Int, divisor: Int): Int {
        var remainder = 0
        for (i in firstDigit until number.size) {
            val digit = number[i].toInt() and 0xFF
            val temp = remainder * base + digit
            number[i] = (temp / divisor).toByte()
            remainder = temp % divisor
        }
        return remainder
    }

    fun base58btcDecode(str: String): ByteArray {
        if (str.isEmpty()) return ByteArray(0)
        val lookup = IntArray(128) { -1 }
        for (i in B58_CHARS.indices) lookup[B58_CHARS[i].code] = i
        var zeros = 0
        while (zeros < str.length && str[zeros] == '1') zeros++
        val output = ByteArray(str.length)
        var outputLen = 0
        for (i in 0 until str.length) {
            val c = str[i]
            val digit = if (c.code < 128) lookup[c.code] else -1
            require(digit >= 0) { "Invalid base58 character: $c" }
            var carry = digit
            var j = 0
            while (j < outputLen) {
                val temp = (output[j].toInt() and 0xFF) * 58 + carry
                output[j] = (temp and 0xFF).toByte()
                carry = temp ushr 8
                j++
            }
            while (carry > 0) {
                output[outputLen++] = (carry and 0xFF).toByte()
                carry = carry ushr 8
            }
        }
        val result = ByteArray(zeros + outputLen)
        for (i in 0 until outputLen) {
            result[zeros + i] = output[outputLen - 1 - i]
        }
        return result
    }
}
