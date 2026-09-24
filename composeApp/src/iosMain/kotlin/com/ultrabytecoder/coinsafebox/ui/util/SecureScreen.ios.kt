package com.ultrabytecoder.coinsafebox.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.ultrabytecoder.coinsafebox.platform.IosScreenshotProtector

/**
 * iOS: black overlay window while the screen is captured (UIScreen.isCaptured)
 * or the app resigns active — see [IosScreenshotProtector].
 */
@Composable
actual fun SecureScreen(content: @Composable () -> Unit) {
    val protector = remember { IosScreenshotProtector() }
    DisposableEffect(protector) {
        protector.enable()
        onDispose { protector.disable() }
    }
    content()
}
