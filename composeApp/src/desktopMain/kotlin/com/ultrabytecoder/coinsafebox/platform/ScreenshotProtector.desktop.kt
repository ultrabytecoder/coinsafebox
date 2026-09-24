package com.ultrabytecoder.coinsafebox.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Desktop has no OS-level screenshot API. The window content is the user's
 * responsibility (their machine, their screen). No-op implementation keeps the
 * common contract (idempotent enable/disable).
 */
class DesktopScreenshotProtector : ScreenshotProtector {
    override fun enable() = Unit
    override fun disable() = Unit
}

@Composable
actual fun rememberScreenshotProtector(): ScreenshotProtector {
    return remember { DesktopScreenshotProtector() }
}