package com.ultrabytecoder.coinsafebox.ui.clipboard

import platform.UIKit.UIPasteboard

actual fun readPlatformClipboardText(): String? = UIPasteboard.general.string as? String

actual fun clearPlatformClipboard() {
    UIPasteboard.general.string = ""
}
