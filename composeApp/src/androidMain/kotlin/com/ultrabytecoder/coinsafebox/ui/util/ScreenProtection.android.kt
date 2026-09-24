package com.ultrabytecoder.coinsafebox.ui.util

import android.view.Window
import android.view.WindowManager
import com.ultrabytecoder.coinsafebox.BuildConfig

/**
 * Whether OS-level screen protection (`FLAG_SECURE`) is applied to windows.
 *
 * Disabled in debug builds so that scrcpy / screen recording works during
 * development and QA. Release builds keep full protection.
 */
internal val screenProtectionEnabled: Boolean = !BuildConfig.DEBUG

internal fun Window.applySecureFlag() {
    if (screenProtectionEnabled) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

internal fun Window.clearSecureFlag() {
    if (screenProtectionEnabled) clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
}
