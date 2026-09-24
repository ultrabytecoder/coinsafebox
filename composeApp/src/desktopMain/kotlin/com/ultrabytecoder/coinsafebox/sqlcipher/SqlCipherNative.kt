package com.ultrabytecoder.coinsafebox.sqlcipher

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale

/**
 * JNA bindings for the SQLCipher C API (the subset used by [NativeSqlCipherDriver]).
 *
 * The bundled native library is a SQLCipher 4.17.0 build (same version as the
 * Android `net.zetetic:sqlcipher-android:4.17.0` dependency) so database files
 * are byte-compatible across platforms. Built with:
 *   -DSQLITE_HAS_CODEC -DSQLITE_TEMP_STORE=2 -DSQLITE_THREADSAFE=1
 *   -DSQLITE_OMIT_LOAD_EXTENSION -DSQLITE_DEFAULT_FOREIGN_KEYS=1
 *   -DSQLITE_EXTRA_INIT=sqlcipher_extra_init -DSQLITE_EXTRA_SHUTDOWN=sqlcipher_extra_shutdown
 */
interface Sqlite3 : Library {

    fun sqlite3_open_v2(filename: String?, ppDb: PointerByReference, flags: Int, zVfs: String?): Int
    fun sqlite3_key_v2(db: Pointer, zDbName: String?, pKey: Pointer, nKey: Int): Int
    fun sqlite3_close_v2(db: Pointer): Int
    fun sqlite3_exec(db: Pointer, sql: String?, callback: Pointer?, arg: Pointer?, errmsg: PointerByReference?): Int
    fun sqlite3_errmsg(db: Pointer): String
    fun sqlite3_errcode(db: Pointer): Int

    fun sqlite3_prepare_v2(db: Pointer, zSql: String?, nByte: Int, ppStmt: PointerByReference, pzTail: Pointer?): Int
    fun sqlite3_db_handle(stmt: Pointer): Pointer
    fun sqlite3_step(stmt: Pointer): Int
    fun sqlite3_finalize(stmt: Pointer): Int
    fun sqlite3_changes64(db: Pointer): Long
    fun sqlite3_last_insert_rowid(db: Pointer): Long

    fun sqlite3_bind_blob(stmt: Pointer, index: Int, value: Pointer, n: Int, destructor: Pointer): Int
    fun sqlite3_bind_text(stmt: Pointer, index: Int, value: Pointer, n: Int, destructor: Pointer): Int
    fun sqlite3_bind_int64(stmt: Pointer, index: Int, value: Long): Int
    fun sqlite3_bind_double(stmt: Pointer, index: Int, value: Double): Int
    fun sqlite3_bind_int(stmt: Pointer, index: Int, value: Int): Int
    fun sqlite3_bind_null(stmt: Pointer, index: Int): Int

    fun sqlite3_column_type(stmt: Pointer, index: Int): Int
    fun sqlite3_column_text(stmt: Pointer, index: Int): Pointer
    fun sqlite3_column_blob(stmt: Pointer, index: Int): Pointer
    fun sqlite3_column_bytes(stmt: Pointer, index: Int): Int
    fun sqlite3_column_int64(stmt: Pointer, index: Int): Long
    fun sqlite3_column_double(stmt: Pointer, index: Int): Double
    fun sqlite3_column_int(stmt: Pointer, index: Int): Int
    fun sqlite3_free(p: Pointer)
}

object SqlCipherNative {

    const val SQLITE_OK = 0
    const val SQLITE_ROW = 100
    const val SQLITE_DONE = 101
    const val SQLITE_NULL = 5
    const val SQLITE_NOTADB = 26
    const val SQLITE_OPEN_READWRITE = 0x00000002
    const val SQLITE_OPEN_CREATE = 0x00000004
    const val SQLITE_OPEN_NOMUTEX = 0x00008000

    /** SQLite copies bound values during the bind call; this destructor value requests that. */
    val SQLITE_TRANSIENT: Pointer = Pointer.createConstant(-1L)

    val lib: Sqlite3 by lazy {
        val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)

        val resourcePath = when {
            osName.contains("linux") && arch == "amd64" -> "/native/linux-x86_64/libsqlcipher.so"
            osName.contains("linux") && arch == "aarch64" -> "/native/linux-aarch64/libsqlcipher.so"
            osName.contains("mac") && arch == "aarch64" -> "/native/macos-aarch64/libsqlcipher.dylib"
            osName.contains("mac") -> "/native/macos-x86_64/libsqlcipher.dylib"
            osName.contains("windows") && arch == "amd64" -> "/native/windows-x86_64/libsqlcipher.dll"
            osName.contains("windows") -> "/native/windows-aarch64/libsqlcipher.dll"
            else -> null
        } ?: throw IllegalStateException(
            "No bundled SQLCipher native library for $osName/$arch — refusing to open an unencrypted database"
        )

        val extracted = extractToTemp(resourcePath)
        Native.load(extracted.absolutePath, Sqlite3::class.java)
    }

    private fun extractToTemp(resourcePath: String): File {
        val stream = SqlCipherNative::class.java.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Missing bundled SQLCipher native library: $resourcePath")
        val bytes = stream.use { it.readBytes() }
        val expectedHash = HASHES[resourcePath]
            ?: throw IllegalStateException("No pinned hash for $resourcePath")
        val actualHash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        if (!actualHash.contentEquals(expectedHash)) {
            throw IllegalStateException("SQLCipher native library hash mismatch for $resourcePath")
        }
        val fileName = resourcePath.substringAfterLast('/')
        // Create the temp file with owner-only permissions BEFORE writing the
        // library bytes, so there is no window where the file is world-readable
        // (or replaceable by another local user) between creation and Native.load.
        val temp = try {
            Files.createTempFile(
                "libsqlcipher",
                fileName.substringAfter("libsqlcipher"),
                PosixFilePermissions.asFileAttribute(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                )
            ).toFile()
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (Windows): fall back to the default temp file.
            File.createTempFile("libsqlcipher", fileName.substringAfter("libsqlcipher"))
        }
        temp.deleteOnExit()
        FileOutputStream(temp).use { it.write(bytes) }
        return temp
    }

    // Pinned SHA-256 of each bundled native library (compute: shasum -a 256 <file>).
    private val HASHES: Map<String, ByteArray> = mapOf(
        "/native/linux-x86_64/libsqlcipher.so" to hexToBytes("c472aa49ca70181cb7d53876b0953fa7a610c8fa3f6676693f7c3501ecd273fd"),
        "/native/windows-x86_64/libsqlcipher.dll" to hexToBytes("3ec27ce31ac779f943c3b377aef88459feafdcf216eec57042cfe9cf75d4be16")
    )

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}