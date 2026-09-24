package com.ultrabytecoder.coinsafebox.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Abstraction over platform-specific screenshot / screen-recording protection.
 *
 * Contract:
 *  - [enable] MUST be idempotent (safe to call multiple times).
 *  - [disable] MUST release any OS-level resources (observers, overlay windows, flags).
 *  - Implementations MUST survive backgrounding / foregrounding without leaking state.
 */
interface ScreenshotProtector {
    fun enable()
    fun disable()
}

@Composable
expect fun rememberScreenshotProtector(): ScreenshotProtector

/**
 * Apply this Modifier to any subtree whose contents should be hidden from
 * screenshots / screen recordings while it is in composition.
 */
@Composable
fun Modifier.preventScreenshots(): Modifier {
    val protector = rememberScreenshotProtector()
    DisposableEffect(protector) {
        protector.enable()
        onDispose { protector.disable() }
    }
    return this
}