package com.ultrabytecoder.coinsafebox.security.hardware.jna

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/** JNA bindings for `kernel32.dll` memory management. */
interface Kernel32 : Library {

    companion object {
        val INSTANCE: Kernel32 = Native.load("kernel32", Kernel32::class.java)
    }

    /** Frees a memory block allocated by DPAPI (e.g. the output `DATA_BLOB.pbData`). */
    fun LocalFree(hMem: Pointer?): Pointer?
}
