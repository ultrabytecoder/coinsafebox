package com.ultrabytecoder.coinsafebox.ui.clipboard

/**
 * Reads the current system clipboard as plain text, or null if empty / unreadable.
 *
 * Platform note: on desktop (JVM) the Compose `LocalClipboardManager` delegates to
 * Skiko's clipboard reader, which is unreliable at reading text placed by external
 * apps (browsers, wallets, password managers). This bridge reads the native
 * clipboard directly on each platform for a trustworthy result, so the secure
 * field's paste path does not depend on the Skiko interop layer.
 */
expect fun readPlatformClipboardText(): String?

/** Replaces the system clipboard with an empty string (best-effort, never throws). */
expect fun clearPlatformClipboard()
