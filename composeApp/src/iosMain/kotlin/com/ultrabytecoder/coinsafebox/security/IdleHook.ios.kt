package com.ultrabytecoder.coinsafebox.security

/**
 * No-op on iOS: the app lifecycle (backgrounding) already locks the session,
 * and there is no lightweight OS-level activity hook to install here.
 */
actual fun installIdleHook(callback: () -> Unit) {
}
