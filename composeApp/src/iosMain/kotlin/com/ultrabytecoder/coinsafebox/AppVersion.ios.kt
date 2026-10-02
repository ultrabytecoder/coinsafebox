package com.ultrabytecoder.coinsafebox

import platform.Foundation.Bundle

actual fun getAppVersion(): String =
    Bundle.main.infoDictionary?.get("CFBundleShortVersionString") as? String ?: "unknown"
