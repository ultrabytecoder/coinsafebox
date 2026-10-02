package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.runtime.Composable

/**
 * iOS has no scanner wired up yet. Returns null so the UI hides the scan
 * button; the value can be entered or pasted manually.
 */
@Composable
actual fun rememberQrScannerLauncher(onResult: (String?) -> Unit): (() -> Unit)? {
    return null
}
