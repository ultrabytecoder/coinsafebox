package com.ultrabytecoder.coinsafebox.security.hardware.jna

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.PointerType
import com.sun.jna.WString

/**
 * JNA bindings for `crypt32.dll` DPAPI entry points.
 *
 * `szDataDescr` is an `LPCWSTR` (in) / `LPWSTR*` (out, allocated by DPAPI and
 * freed via [Kernel32.LocalFree]). `pOptionalEntropy` is a caller-owned
 * [DataBlob] whose `pbData` points into a JNA-managed memory region that must
 * outlive the call.
 */
interface Crypt32 : Library {

    companion object {
        val INSTANCE: Crypt32 = Native.load("crypt32", Crypt32::class.java)
    }

    fun CryptProtectData(
        pDataIn: DataBlob,
        szDataDescr: WString?,
        pOptionalEntropy: DataBlob?,
        pvReserved: Pointer?,
        pPromptStruct: Pointer?,
        dwFlags: Int,
        pDataOut: DataBlob
    ): Boolean

    fun CryptUnprotectData(
        pDataIn: DataBlob,
        ppszDataDescr: PointerType?,
        pOptionalEntropy: DataBlob?,
        pvReserved: Pointer?,
        pPromptStruct: Pointer?,
        dwFlags: Int,
        pDataOut: DataBlob
    ): Boolean
}
