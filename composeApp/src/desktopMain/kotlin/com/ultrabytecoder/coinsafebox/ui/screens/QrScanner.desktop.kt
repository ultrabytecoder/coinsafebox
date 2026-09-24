package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.runtime.Composable

/**
 * Desktop has no camera. Returns a no-op launcher; the address can be entered
 * or pasted manually.
 */
@Composable
actual fun rememberQrScannerLauncher(onResult: (String?) -> Unit): () -> Unit {
    return {}
}