package com.ultrabytecoder.coinsafebox.ui.util

import androidx.compose.runtime.Composable

/**
 * Wraps [content] in platform-level screenshot protection for the duration of
 * its composition:
 *
 * - Android: `FLAG_SECURE` on the hosting window (screenshots show black).
 * - iOS: black overlay window while the screen is captured (see
 *   [com.ultrabytecoder.coinsafebox.platform.ScreenshotProtector]).
 * - Desktop: no-op — there is no OS-level screenshot API; the window content is
 *   the user's responsibility (their machine, their screen).
 *
 * Use this for screens that display secrets (e.g. the recovery phrase).
 */
@Composable
expect fun SecureScreen(content: @Composable () -> Unit)
