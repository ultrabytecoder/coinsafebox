package com.ultrabytecoder.coinsafebox.security

import com.ultrabytecoder.coinsafebox.security.hardware.DesktopOs
import com.ultrabytecoder.coinsafebox.security.hardware.OsDetector
import org.junit.Test
import kotlin.test.assertEquals

class OsDetectorTest {

    @Test
    fun parseMacVariants() {
        assertEquals(DesktopOs.MAC, OsDetector.parse("Mac OS X"))
        assertEquals(DesktopOs.MAC, OsDetector.parse("macOS"))
        assertEquals(DesktopOs.MAC, OsDetector.parse("MAC OS X"))
    }

    @Test
    fun parseWindowsVariants() {
        assertEquals(DesktopOs.WINDOWS, OsDetector.parse("Windows 11"))
        assertEquals(DesktopOs.WINDOWS, OsDetector.parse("Windows 10"))
        assertEquals(DesktopOs.WINDOWS, OsDetector.parse("Windows Server 2022"))
    }

    @Test
    fun parseLinuxVariants() {
        assertEquals(DesktopOs.LINUX, OsDetector.parse("Linux"))
        assertEquals(DesktopOs.LINUX, OsDetector.parse("LINUX"))
    }

    @Test
    fun parseOtherAndNull() {
        assertEquals(DesktopOs.OTHER, OsDetector.parse("FreeBSD"))
        assertEquals(DesktopOs.OTHER, OsDetector.parse(""))
        assertEquals(DesktopOs.OTHER, OsDetector.parse(null))
    }

    @Test
    fun currentMatchesOsName() {
        assertEquals(OsDetector.parse(System.getProperty("os.name")), OsDetector.current)
    }

    @Test
    fun flagsAreConsistent() {
        assertEquals(OsDetector.current == DesktopOs.MAC, OsDetector.isMac)
        assertEquals(OsDetector.current == DesktopOs.WINDOWS, OsDetector.isWindows)
        assertEquals(OsDetector.current == DesktopOs.LINUX, OsDetector.isLinux)
    }
}
