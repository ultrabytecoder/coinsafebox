package com.ultrabytecoder.coinsafebox.security

/**
 * Installs a platform hook that invokes [callback] on user activity (mouse/keyboard),
 * used to reset the session idle timeout (F-5). No-op on platforms where the app
 * lifecycle already reports activity (Android) or where no OS-level hook exists (iOS).
 */
expect fun installIdleHook(callback: () -> Unit)
