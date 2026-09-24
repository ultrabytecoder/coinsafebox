package com.ultrabytecoder.coinsafebox.ui.keyboard.platform

/**
 * Desktop (Swing) has no software IME layer to route keystrokes through, so there is
 * nothing to suppress — the in-app keyboard is not driven by a system input method here.
 * No-op by design (M15).
 */
actual fun blockSystemKeyboard() {
}
