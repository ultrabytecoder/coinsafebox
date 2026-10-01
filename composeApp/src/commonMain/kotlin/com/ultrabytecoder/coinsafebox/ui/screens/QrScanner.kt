package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.runtime.Composable

@Composable
expect fun rememberQrScannerLauncher(onResult: (String?) -> Unit): (() -> Unit)?
