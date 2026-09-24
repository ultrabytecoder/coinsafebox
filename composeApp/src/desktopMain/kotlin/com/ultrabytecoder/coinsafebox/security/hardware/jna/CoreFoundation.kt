package com.ultrabytecoder.coinsafebox.security.hardware.jna

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Opaque CoreFoundation reference types. All are passed as plain [Pointer]s;
 * the helpers in [Cf] manage their lifetimes (CF_RETAIN / CF_RELEASE).
 */
typealias CFTypeRef = Pointer
typealias CFDataRef = Pointer
typealias CFDictionaryRef = Pointer
typealias CFStringRef = Pointer
typealias CFNumberRef = Pointer
typealias CFAllocatorRef = Pointer
typealias CFErrorRef = Pointer

/**
 * JNA bindings for the CoreFoundation subset used by the macOS device-key
 * backend (CFData / CFDictionary / CFNumber / CFString / CFRelease).
 */
interface CoreFoundation : Library {

    companion object {
        val INSTANCE: CoreFoundation = Native.load("CoreFoundation", CoreFoundation::class.java)

        private val lib: NativeLibrary = NativeLibrary.getInstance("CoreFoundation")

        /** Address of a global variable exported by CoreFoundation. */
        fun globalVar(name: String): Pointer =
            lib.getGlobalVariableAddress(name)
                ?: throw IllegalStateException("CoreFoundation global '$name' not found")

        /** `kCFAllocatorDefault` — the process-wide default allocator. */
        val kCFAllocatorDefault: Pointer = globalVar("kCFAllocatorDefault").getPointer(0)

        /** The `CFDictionaryKeyCallBacks` struct pointers (read through the globals). */
        val kCFTypeDictionaryKeyCallBacks: Pointer = globalVar("kCFTypeDictionaryKeyCallBacks").getPointer(0)
        val kCFTypeDictionaryValueCallBacks: Pointer = globalVar("kCFTypeDictionaryValueCallBacks").getPointer(0)

        /** `kCFStringEncodingUTF8` = 0x08000100. */
        const val K_CF_STRING_ENCODING_UTF8: Long = 0x08000100L

        /** `kCFNumberSInt32Type` = 3. */
        const val K_CF_NUMBER_S_INT32_TYPE: Int = 3
    }

    fun CFRelease(cf: Pointer?)

    fun CFRetain(cf: Pointer?): Pointer

    fun CFDataCreate(allocator: CFAllocatorRef?, bytes: Pointer?, length: Long): CFDataRef

    fun CFDataGetLength(data: CFDataRef): Long

    fun CFDataGetBytePtr(data: CFDataRef): Pointer

    fun CFDictionaryCreateMutable(
        allocator: CFAllocatorRef?,
        capacity: Int,
        keyCallBacks: Pointer?,
        valueCallBacks: Pointer?
    ): CFDictionaryRef

    fun CFDictionaryAddValue(dict: CFDictionaryRef?, key: CFTypeRef?, value: CFTypeRef?)

    fun CFNumberCreate(allocator: CFAllocatorRef?, theType: Int, valuePtr: Pointer?): CFNumberRef

    fun CFStringCreateWithCString(allocator: CFAllocatorRef?, cStr: ByteArray, encoding: Long): CFStringRef

    fun CFErrorGetCode(error: CFErrorRef?): Int
}
