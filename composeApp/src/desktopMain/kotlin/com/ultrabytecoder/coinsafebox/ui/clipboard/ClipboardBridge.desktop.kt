package com.ultrabytecoder.coinsafebox.ui.clipboard

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

actual fun readPlatformClipboardText(): String? = runCatching {
    val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return null
    if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
        contents.getTransferData(DataFlavor.stringFlavor) as? String
    } else {
        null
    }
}.getOrNull()

actual fun clearPlatformClipboard() {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(""), null)
    }
}
