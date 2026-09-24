package com.ultrabytecoder.coinsafebox.data

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Shared app-data directory: `<user.home>/.coinsafebox`.
 * Created with owner-only permissions (0700 on POSIX) because it holds the
 * encrypted database, the device key and the settings file.
 */
fun appDataDir(): File {
    val dir = File(System.getProperty("user.home"), ".coinsafebox")
    dir.mkdirs()
    restrictToOwner(dir)
    return dir
}

fun restrictToOwner(file: File) {
    if (System.getProperty("os.name").lowercase().contains("windows")) return
    try {
        Files.setPosixFilePermissions(
            file.toPath(),
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        )
    } catch (_: UnsupportedOperationException) {
    } catch (_: java.io.IOException) {
    }
}

/** Restricts a file to owner read/write (0600 on POSIX). */
fun restrictFileToOwner(file: File) {
    if (System.getProperty("os.name").lowercase().contains("windows")) return
    try {
        Files.setPosixFilePermissions(
            file.toPath(),
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            )
        )
    } catch (_: UnsupportedOperationException) {
    } catch (_: java.io.IOException) {
    }
}