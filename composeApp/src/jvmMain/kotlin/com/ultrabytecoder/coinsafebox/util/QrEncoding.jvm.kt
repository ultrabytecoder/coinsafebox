package com.ultrabytecoder.coinsafebox.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

actual object QrEncoder {
    actual fun encode(text: String): QrMatrix {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            // 0 => no quiet zone, matching the raw-module matrix expected by the renderer.
            EncodeHintType.MARGIN to 0,
        )
        val matrix: BitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val n = matrix.width
        val data = BooleanArray(n * n)
        for (y in 0 until matrix.height) {
            for (x in 0 until n) {
                data[y * n + x] = matrix.get(x, y)
            }
        }
        return QrMatrix(n, data)
    }
}
