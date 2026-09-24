package com.ultrabytecoder.coinsafebox.security.hardware.jna

import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * Windows `DATA_BLOB` (`{ DWORD cbData; BYTE *pbData; }`).
 *
 * JNA's default GNU alignment matches the MSVC layout for this structure
 * (4-byte int followed by an 8-byte pointer, 8-byte aligned on x64).
 */
class DataBlob : Structure() {

    @JvmField
    var cbData: Int = 0

    @JvmField
    var pbData: Pointer? = null

    override fun getFieldOrder(): List<String> = listOf("cbData", "pbData")
}
