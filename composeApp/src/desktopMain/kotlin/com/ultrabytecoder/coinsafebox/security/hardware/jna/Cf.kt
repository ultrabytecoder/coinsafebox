package com.ultrabytecoder.coinsafebox.security.hardware.jna

import com.sun.jna.Memory
import com.sun.jna.Pointer

/**
 * Small helpers building CoreFoundation values from Kotlin, mirroring the
 * reference-counting discipline of the iOS implementation (every +1 reference
 * is released exactly once by the caller).
 */
internal object Cf {

    private val cf = CoreFoundation.INSTANCE

    fun release(ref: Pointer?) {
        if (ref != null) cf.CFRelease(ref)
    }

    /** UTF-8 [CFStringRef] (+1, caller releases). */
    fun cfString(value: String): CFStringRef {
        val bytes = value.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        return cf.CFStringCreateWithCString(CoreFoundation.kCFAllocatorDefault, bytes, CoreFoundation.K_CF_STRING_ENCODING_UTF8)
            ?: throw IllegalStateException("CFStringCreateWithCString failed")
    }

    /** Boolean [CFTypeRef] (+1, caller releases). */
    fun cfBoolean(value: Boolean): Pointer {
        val name = if (value) "kCFBooleanTrue" else "kCFBooleanFalse"
        val ref = CoreFoundation.globalVar(name).getPointer(0)
        return cf.CFRetain(ref)
    }

    /** 32-bit [CFNumberRef] (+1, caller releases). */
    fun cfNumberSInt32(value: Int): CFNumberRef {
        val mem = Memory(4)
        mem.setInt(0, value)
        return cf.CFNumberCreate(CoreFoundation.kCFAllocatorDefault, CoreFoundation.K_CF_NUMBER_S_INT32_TYPE, mem)
            ?: throw IllegalStateException("CFNumberCreate failed")
    }

    /** [CFDataRef] over [bytes] (+1, caller releases). The bytes are copied. */
    fun cfData(bytes: ByteArray): CFDataRef {
        val mem = Memory(bytes.size.toLong())
        return try {
            mem.write(0, bytes, 0, bytes.size)
            cf.CFDataCreate(CoreFoundation.kCFAllocatorDefault, mem, bytes.size.toLong())
                ?: throw IllegalStateException("CFDataCreate failed")
        } finally {
            // CFDataCreate copied the bytes; zero the staging buffer.
            mem.clear()
        }
    }

    /** Mutable dictionary (+1, caller releases). */
    fun dictMutable(): CFDictionaryRef =
        cf.CFDictionaryCreateMutable(
            CoreFoundation.kCFAllocatorDefault,
            0,
            CoreFoundation.kCFTypeDictionaryKeyCallBacks,
            CoreFoundation.kCFTypeDictionaryValueCallBacks
        ) ?: throw IllegalStateException("CFDictionaryCreateMutable failed")

    fun dictAdd(dict: CFDictionaryRef, key: CFTypeRef, value: CFTypeRef) {
        cf.CFDictionaryAddValue(dict, key, value)
    }

    /** Copies the bytes of a [CFDataRef] into a fresh [ByteArray]. */
    fun dataToBytes(data: CFDataRef): ByteArray {
        val length = cf.CFDataGetLength(data).toInt()
        val ptr = cf.CFDataGetBytePtr(data) ?: throw IllegalStateException("CFDataGetBytePtr returned NULL")
        return ptr.getByteArray(0, length)
    }

    /**
     * Reads a `CFTypeRef` written by an out-parameter (`CFTypeRef *result` /
     * `CFErrorRef *error`) into a 16-byte scratch area.
     */
    fun readOutRef(out: Pointer): Pointer? = out.getPointer(0)

    /** 16-byte scratch for a single out-pointer. */
    fun outPtr(): Pointer = Memory(16)

    /**
     * Reads the OSStatus/CFError code from a `CFErrorRef *` out-parameter.
     * Returns null when no error was produced. The CFErrorRef is NOT released
     * here — the caller owns it (pass to [release] when done).
     */
    fun cfErrorCode(errorOut: Pointer): Int? {
        val errorRef = readOutRef(errorOut) ?: return null
        return try {
            cf.CFErrorGetCode(errorRef)
        } finally {
            release(errorRef)
        }
    }
}
