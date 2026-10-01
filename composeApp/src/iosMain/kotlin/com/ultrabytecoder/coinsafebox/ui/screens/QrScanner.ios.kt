package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.runtime.Composable

@Composable
actual fun rememberQrScannerLauncher(onResult: (String?) -> Unit): (() -> Unit)? {
    return { onResult(null) }
}
