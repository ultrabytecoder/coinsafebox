package com.ultrabytecoder.coinsafebox.util

import io.github.goquati.qr.QrCode

actual object QrEncoder {
    actual fun encode(text: String): QrMatrix {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val n = qr.size
        val data = BooleanArray(n * n)
        for (y in 0 until n) {
            for (x in 0 until n) {
                data[y * n + x] = qr[x, y]
            }
        }
        return QrMatrix(n, data)
    }
}
