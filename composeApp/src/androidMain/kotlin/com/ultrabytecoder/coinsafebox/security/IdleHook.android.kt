package com.ultrabytecoder.coinsafebox.security

/**
 * No-op on Android: activity resume events already reset the idle timeout
 * (see WalletApplication.onActivityResumed).
 *
 * This actual must stay in androidMain (not the shared jvmMain) because the
 * desktop implementation uses `java.awt.*`, which does not exist on the Android
 * runtime.
 */
actual fun installIdleHook(callback: () -> Unit) {
}
