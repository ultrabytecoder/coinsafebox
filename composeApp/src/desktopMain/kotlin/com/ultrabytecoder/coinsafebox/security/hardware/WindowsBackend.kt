package com.ultrabytecoder.coinsafebox.security.hardware

import com.sun.jna.Memory
import com.ultrabytecoder.coinsafebox.data.appDataDir
import com.ultrabytecoder.coinsafebox.data.restrictFileToOwner
import com.ultrabytecoder.coinsafebox.security.AesGcm
import com.ultrabytecoder.coinsafebox.security.AesGcmAuthenticationException
import com.ultrabytecoder.coinsafebox.security.HardwareKeyInvalidatedException
import com.ultrabytecoder.coinsafebox.security.hardware.jna.Crypt32
import com.ultrabytecoder.coinsafebox.security.hardware.jna.DataBlob
import com.ultrabytecoder.coinsafebox.security.hardware.jna.Kernel32
import com.ultrabytecoder.coinsafebox.security.wipe
import com.sun.jna.Native
import com.sun.jna.WString
import java.io.File
import org.kotlincrypto.random.CryptoRand

/**
 * Windows device key backed by DPAPI (`CryptProtectData`/`CryptUnprotectData`).
 *
 * A random 32-byte AES-256 key is generated once, wrapped with the per-user
 * DPAPI master key plus a 32-byte application entropy constant, and stored in
 * `<user.home>/.coinsafebox/.device_key_dpapi` (0600). The unwrapped key is
 * cached in the JVM heap for the process lifetime — the same residency model
 * as [FileBackend] (documented in desktop-security.md; not a regression).
 *
 * The entropy constant is NOT secret (DPAPI threat model): it provides
 * namespace isolation so another DPAPI-aware process running as the same user
 * cannot unwrap our blob without it. `dwFlags = 0` — user master key only
 * (no `CRYPTPROTECT_LOCAL_MACHINE`).
 *
 * SHA-256 of [DPAPI_ENTROPY]: 3aa5050042e072671b6c2385205c4765fd4dddec0c397e57c3035c2cab377ecf
 * (pinned in desktop-security.md — changing it bricks all existing installs).
 */
class WindowsBackend : HardwareKeyBackend {

    override val id: String = "windows_dpapi"

    private companion object {
        const val BLOB_FILE_NAME = ".device_key_dpapi"
        const val KEY_SIZE = 32
        const val DATA_DESCRIPTION = "coinsafebox.device_key"

        // 32-byte application entropy constant (CSPRNG-generated, committed once).
        val DPAPI_ENTROPY: ByteArray = byteArrayOf(
            (-0x54).toByte(), (-0x28).toByte(), 0x49, (-0x30).toByte(), 0x70, (-0x4e).toByte(), 0x60, 0x56,
            0x30, 0x68, 0x1e, (-0x68).toByte(), (-0x55).toByte(), (-0x73).toByte(), 0x4d, 0x1e,
            (-0x67).toByte(), 0x00, (-0x3a).toByte(), (-0x5).toByte(), (-0x48).toByte(), (-0x37).toByte(), 0x07, 0x74,
            0x25, 0x18, 0x41, (-0x14).toByte(), 0x56, 0x49, 0x08, (-0x5d).toByte()
        )
    }

    private val blobFile = File(appDataDir(), BLOB_FILE_NAME)

    @Volatile
    private var cachedKey: ByteArray? = null

    @Volatile
    private var installId: ByteArray? = null

    private val lock = Any()

    /**
     * Binds the DPAPI blob to the install ID (F-11): the entropy becomes
     * SHA-256(DPAPI_ENTROPY || installId). Must be called before the first
     * [encrypt]/[decrypt] (KeyManager.init does this at startup).
     */
    fun setInstallId(id: ByteArray) {
        synchronized(lock) {
            installId = id.copyOf()
        }
    }

    private fun effectiveEntropy(): ByteArray {
        val id = installId ?: return DPAPI_ENTROPY
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(DPAPI_ENTROPY)
        md.update(id)
        return md.digest()
    }

    /** Loads the cached/stored key, creating it on first use (encrypt path). */
    private fun getOrCreateKey(): ByteArray = synchronized(lock) {
        cachedKey?.let { return it }
        val key = if (blobFile.exists()) {
            loadKeyFromBlob()
        } else {
            val fresh = CryptoRand.Default.nextBytes(ByteArray(KEY_SIZE))
            try {
                val wrapped = dpapiProtect(fresh)
                try {
                    blobFile.writeBytes(wrapped)
                } finally {
                    wrapped.wipe()
                }
                restrictFileToOwner(blobFile)
            } finally {
                fresh.wipe()
            }
            // Verify the on-disk blob round-trips before trusting it.
            loadKeyFromBlob()
        }
        cachedKey = key
        key
    }

    /** Loads the cached/stored key; throws when the blob is missing (decrypt path). */
    private fun getKeyOrThrow(): ByteArray = synchronized(lock) {
        cachedKey?.let { return it }
        if (!blobFile.exists()) {
            throw HardwareKeyInvalidatedException("Device hardware key missing")
        }
        val key = loadKeyFromBlob()
        cachedKey = key
        key
    }

    private fun loadKeyFromBlob(): ByteArray {
        val wrapped = blobFile.readBytes()
        return try {
            try {
                dpapiUnprotect(wrapped)
            } catch (e: Exception) {
                throw HardwareKeyInvalidatedException("DPAPI unwrap of device key failed: ${e.message}")
            }
        } finally {
            wrapped.wipe()
        }
    }

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray =
        AesGcm.encrypt(getOrCreateKey(), plaintext, aad)

    override fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray {
        val key = getKeyOrThrow()
        return try {
            AesGcm.decrypt(key, encrypted, aad)
        } catch (e: AesGcmAuthenticationException) {
            throw e
        } catch (e: Exception) {
            throw HardwareKeyInvalidatedException("Device key backend 'windows_dpapi' failed: ${e.message}")
        }
    }

    override fun deleteKey() {
        synchronized(lock) {
            cachedKey?.wipe()
            cachedKey = null
            installId?.wipe()
            installId = null
            blobFile.delete()
        }
    }

    override fun purgeCache() {
        synchronized(lock) {
            cachedKey?.wipe()
            cachedKey = null
        }
    }

    private fun dpapiProtect(plaintext: ByteArray): ByteArray {
        val entropy = effectiveEntropy()
        val inMem = Memory(plaintext.size.toLong()).also { it.write(0, plaintext, 0, plaintext.size) }
        val entropyMem = Memory(entropy.size.toLong()).also { it.write(0, entropy, 0, entropy.size) }
        val inBlob = DataBlob().apply { cbData = plaintext.size; pbData = inMem }
        val entropyBlob = DataBlob().apply { cbData = entropy.size; pbData = entropyMem }
        val outBlob = DataBlob()
        try {
            val ok = Crypt32.INSTANCE.CryptProtectData(
                inBlob, WString(DATA_DESCRIPTION), entropyBlob, null, null, 0, outBlob
            )
            if (!ok) {
                throw IllegalStateException("CryptProtectData failed (Win32 error ${Native.getLastError()})")
            }
            return outBlob.pbData!!.getByteArray(0, outBlob.cbData)
        } finally {
            inMem.clear()
            entropyMem.clear()
            entropy.wipe()
            outBlob.pbData?.let { Kernel32.INSTANCE.LocalFree(it) }
        }
    }

    private fun dpapiUnprotect(wrapped: ByteArray): ByteArray {
        val entropy = effectiveEntropy()
        val inMem = Memory(wrapped.size.toLong()).also { it.write(0, wrapped, 0, wrapped.size) }
        val entropyMem = Memory(entropy.size.toLong()).also { it.write(0, entropy, 0, entropy.size) }
        val inBlob = DataBlob().apply { cbData = wrapped.size; pbData = inMem }
        val entropyBlob = DataBlob().apply { cbData = entropy.size; pbData = entropyMem }
        val outBlob = DataBlob()
        try {
            val ok = Crypt32.INSTANCE.CryptUnprotectData(
                inBlob, null, entropyBlob, null, null, 0, outBlob
            )
            if (!ok) {
                throw HardwareKeyInvalidatedException("CryptUnprotectData failed (wrong user/entropy or master key unavailable)")
            }
            return outBlob.pbData!!.getByteArray(0, outBlob.cbData)
        } finally {
            inMem.clear()
            entropyMem.clear()
            entropy.wipe()
            outBlob.pbData?.let { Kernel32.INSTANCE.LocalFree(it) }
        }
    }
}
