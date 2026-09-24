package com.ultrabytecoder.coinsafebox.ui.util

import androidx.compose.runtime.Composable

/**
 * No-op on desktop: there is no OS-level screenshot API. The window content is
 * the user's responsibility (their machine, their screen) — a documented
 * limitation, not a security control.
 */
@Composable
actual fun SecureScreen(content: @Composable () -> Unit) {
    content()
}
