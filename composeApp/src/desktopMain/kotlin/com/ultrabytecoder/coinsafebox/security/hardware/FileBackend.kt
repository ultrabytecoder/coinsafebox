package com.ultrabytecoder.coinsafebox.security.hardware

import com.ultrabytecoder.coinsafebox.data.appDataDir
import com.ultrabytecoder.coinsafebox.data.restrictFileToOwner
import com.ultrabytecoder.coinsafebox.security.AesGcmAuthenticationException
import com.ultrabytecoder.coinsafebox.security.HardwareKeyCorruptedException
import com.ultrabytecoder.coinsafebox.security.HardwareKeyInvalidatedException
import com.ultrabytecoder.coinsafebox.security.wipe
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * File-backed device key: a random AES-256 key stored in
 * `<user.home>/.coinsafebox/.device_key` with owner-only permissions
 * (0600 on POSIX).
 *
 * This is the baseline backend (and the fallback for OSes without a
 * dedicated backend). It binds the key hierarchy to the app data directory —
 * the same threat-model position as Android Keystore, minus the hardware
 * backing. The key file is created lazily on first [encrypt].
 */
class FileBackend : HardwareKeyBackend {

    override val id: String = "file"

    private companion object {
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
        const val KEY_SIZE_BYTES = 32
    }

    private val baseDir = appDataDir()
    private val keyFile = File(baseDir, ".device_key")

    private val keyLock = Any()

    private fun getOrCreateSecretKey(): SecretKey = synchronized(keyLock) {
        if (!keyFile.exists()) {
            baseDir.mkdirs()
            val keyGenerator = KeyGenerator.getInstance("AES")
            keyGenerator.init(KEY_SIZE_BYTES * 8)
            val key = keyGenerator.generateKey()
            val encoded = key.encoded
            try {
                // Create the file with owner-only permissions BEFORE writing the
                // key bytes, so there is no window where the key is readable
                // with umask-default permissions.
                createOwnerOnlyFile(keyFile)
                keyFile.writeBytes(encoded)
            } finally {
                encoded.wipe()
            }
        }
        val encoded = keyFile.readBytes()
        try {
            // A key file of the wrong length is corruption, NOT a missing key:
            // silently regenerating a fresh key would orphan every blob
            // encrypted under the old one (permanent unreadable data). Surface
            // it as corrupted so the caller routes to recovery instead.
            if (encoded.size != KEY_SIZE_BYTES) {
                throw HardwareKeyCorruptedException(
                    "Device key file has invalid length ${encoded.size} (expected $KEY_SIZE_BYTES)"
                )
            }
            // Pass a defensive copy to SecretKeySpec so wiping `encoded` in
            // finally cannot zero the live key if the JDK implementation
            // retains a reference to the original array.
            return@synchronized javax.crypto.spec.SecretKeySpec(encoded.copyOf(), "AES")
        } finally {
            encoded.wipe()
        }
    }

    private fun createOwnerOnlyFile(file: File) {
        if (OsDetector.isWindows) {
            file.createNewFile()
            restrictWindowsFileToOwner(file)
            return
        }
        try {
            Files.createFile(
                file.toPath(),
                PosixFilePermissions.asFileAttribute(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                )
            )
        } catch (_: UnsupportedOperationException) {
            file.createNewFile()
            restrictFileToOwner(file)
        } catch (_: FileAlreadyExistsException) {
            restrictFileToOwner(file)
        }
    }

    /**
     * Restricts a Windows file to the current user (owner read/write only) via the
     * NTFS ACL. Uses reflection because the `java.nio.file.attribute` ACL classes
     * are only present on the Windows JDK and would fail to compile on Linux/macOS.
     * Best-effort: if the filesystem does not support ACLs (e.g. a non-NTFS volume)
     * or the classes are unavailable, the restriction is skipped rather than
     * failing key setup.
     */
    @Suppress("UNCHECKED_CAST")
    private fun restrictWindowsFileToOwner(file: File) {
        try {
            val path = file.toPath()
            val aclViewClass = Class.forName("java.nio.file.attribute.AclFileAttributeView")
            val aclView = Files.getFileAttributeView<java.nio.file.attribute.FileAttributeView>(
                path,
                aclViewClass as Class<java.nio.file.attribute.FileAttributeView>
            ) ?: return
            val owner = aclViewClass.getMethod("getOwner").invoke(aclView)
            val ownerName = (owner as java.nio.file.attribute.UserPrincipal).name

            // UserPrincipalLookupService is obtained from the FileSystem, not via
            // a static factory method.
            val fileSystem = path.fileSystem
            val lookupService = fileSystem.userPrincipalLookupService
            val identity = lookupService.lookupPrincipalByName(ownerName)

            val entryClass = Class.forName("java.nio.file.attribute.FileAclEntry")
            val builder = entryClass.getMethod("newBuilder").invoke(null)
            val permClass = Class.forName("java.nio.file.attribute.FileAclEntryPermission")
            val readData = permClass.getField("READ_DATA").get(null)
            val writeData = permClass.getField("WRITE_DATA").get(null)
            val typeClass = Class.forName("java.nio.file.attribute.FileAclEntryType")
            val allow = typeClass.getField("ALLOW").get(null)

            builder.javaClass.getMethod("setPrincipal", java.nio.file.attribute.UserPrincipal::class.java)
                .invoke(builder, identity)
            builder.javaClass.getMethod("setPermissions", java.util.Set::class.java)
                .invoke(builder, setOf(readData, writeData))
            builder.javaClass.getMethod("setType", typeClass).invoke(builder, allow)
            val entry = entryClass.getMethod("build").invoke(builder)

            aclViewClass.getMethod("setAcl", java.util.List::class.java)
                .invoke(aclView, listOf(entry))
        } catch (_: Exception) {
            // ACL not supported, classes unavailable, or permission denied —
            // leave the file as-is.
        }
    }

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return cipher.iv + ciphertext
    }

    override fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray {
        if (!keyFile.exists()) {
            throw HardwareKeyInvalidatedException("Device hardware key missing")
        }
        require(encrypted.size > GCM_IV_LENGTH) { "Encrypted data too short" }
        val iv = encrypted.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = encrypted.copyOfRange(GCM_IV_LENGTH, encrypted.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return try {
            cipher.doFinal(ciphertext)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw AesGcmAuthenticationException("Hardware GCM authentication failed")
        }
    }

    override fun deleteKey() {
        keyFile.delete()
    }
}
