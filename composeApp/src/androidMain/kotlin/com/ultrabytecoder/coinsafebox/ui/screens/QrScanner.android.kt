package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
actual fun rememberQrScannerLauncher(onResult: (String?) -> Unit): (() -> Unit)? {
    val launcher = rememberLauncherForActivityResult(
        contract = ScanContract(),
        onResult = { result -> onResult(result.contents) }
    )
    return {
        launcher.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan a QR Code")
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
        )
    }
}
