package com.ultrabytecoder.coinsafebox.security.hardware

/** Desktop host OS, parsed from `os.name` once at class load. */
enum class DesktopOs { MAC, WINDOWS, LINUX, OTHER }

object OsDetector {

    /** Pure mapping from an `os.name` value to a [DesktopOs] (null-safe). */
    fun parse(osName: String?): DesktopOs = when {
        (osName ?: "").startsWith("mac", ignoreCase = true) -> DesktopOs.MAC
        (osName ?: "").startsWith("win", ignoreCase = true) -> DesktopOs.WINDOWS
        (osName ?: "").startsWith("linux", ignoreCase = true) -> DesktopOs.LINUX
        else -> DesktopOs.OTHER
    }

    val current: DesktopOs = parse(System.getProperty("os.name"))

    val isMac: Boolean get() = current == DesktopOs.MAC
    val isWindows: Boolean get() = current == DesktopOs.WINDOWS
    val isLinux: Boolean get() = current == DesktopOs.LINUX
}
