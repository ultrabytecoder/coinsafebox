package com.ultrabytecoder.coinsafebox.data

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Properties
import kotlin.concurrent.withLock
import java.util.concurrent.locks.ReentrantLock

/**
 * Key/value storage in `<user.home>/.coinsafebox/settings.properties`.
 * Every mutation is flushed synchronously so a crash cannot lose a write that
 * was acknowledged to the caller (same commit()-like contract as the Android
 * SharedPreferences implementation).
 */
actual class SettingsStorage actual constructor(context: Any?) : SettingsStore {
    private val baseDir: File = (context as? File) ?: appDataDir()
    private val file = File(baseDir, "settings.properties")

    private val lock = ReentrantLock()
    private val properties = Properties()

    init {
        baseDir.mkdirs()
        restrictToOwner(baseDir)
        if (file.exists()) {
            try {
                FileInputStream(file).use { input ->
                    properties.load(InputStreamReader(input, StandardCharsets.UTF_8))
                }
            } catch (_: Exception) {
                // Corrupt settings file: start fresh (a corrupt settings.properties
                // must not prevent the app from starting; PIN key material is
                // written atomically via a temp-file rename below).
                properties.clear()
            }
        }
    }

    actual override fun putString(key: String, value: String) {
        lock.withLock {
            properties.setProperty(key, value)
            save()
        }
    }

    actual override fun getString(key: String): String? = lock.withLock { properties.getProperty(key) }

    actual override fun remove(key: String) {
        lock.withLock {
            properties.remove(key)
            save()
        }
    }

    /**
     * Atomic write: write to a temp file in the same directory, then rename over
     * the target. A crash mid-write leaves either the old file or the new one,
     * never a truncated mix.
     */
    private fun save() {
        val temp = File(baseDir, "settings.properties.tmp")
        OutputStreamWriter(FileOutputStream(temp), StandardCharsets.UTF_8).use { writer ->
            properties.store(writer, null)
        }
        if (!temp.renameTo(file)) {
            // Fallback for exotic filesystems without atomic rename semantics.
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}