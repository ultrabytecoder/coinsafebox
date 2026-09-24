package com.ultrabytecoder.coinsafebox.security

import kotlin.io.encoding.Base64
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F-1 regression: the desktop device key must PERSIST across provider
 * re-creation (app restart). The pre-fix build used a process-lifetime
 * in-memory key, so every restart failed GCM authentication on the stored
 * salt and forced recovery. Uses FileBackend directly — the OS-store backends
 * (DPAPI/Keychain/libsecret) are platform-specific and not exercisable here.
 */
class DesktopKeystoreProviderTest {

    /**
     * Runs [block] with a fresh temporary user.home, restoring the previous
     * property (and deleting the temp dir) afterwards — no global system
     * state leaks across tests.
     */
    private inline fun withTempHome(block: (File) -> Unit) {
        val previous = System.getProperty("user.home")
        val dir = kotlin.io.path.createTempDirectory("kk-keystore-test").toFile()
        System.setProperty("user.home", dir.toString())
        try {
            block(dir)
        } finally {
            if (previous != null) {
                System.setProperty("user.home", previous)
            } else {
                System.clearProperty("user.home")
            }
            dir.deleteRecursively()
        }
    }

    @Test
    fun fileBackendKeyPersistsAcrossProviderRecreation() = withTempHome { _ ->
        val lock = Any()
        val first = DesktopKeystoreProvider(lock, com.ultrabytecoder.coinsafebox.security.hardware.FileBackend())
        val plaintext = byteArrayOf(1, 2, 3, 4, 5)
        val aad = byteArrayOf(9, 9)
        val blob = first.encrypt(plaintext, aad)

        // Fresh provider instance (simulates an app restart): the key file on
        // disk must decrypt what the previous instance encrypted.
        val second = DesktopKeystoreProvider(lock, com.ultrabytecoder.coinsafebox.security.hardware.FileBackend())
        val decrypted = second.decrypt(blob, aad)
        assertTrue(decrypted.contentEquals(plaintext), "persisted key must decrypt prior ciphertext")

        // Wrong AAD must fail authentication.
        assertFailsWith<AesGcmAuthenticationException> {
            second.decrypt(blob, byteArrayOf(0))
        }
    }

    @Test
    fun missingKeyDecryptThrowsInvalidated() = withTempHome { _ ->
        val provider = DesktopKeystoreProvider(Any(), com.ultrabytecoder.coinsafebox.security.hardware.FileBackend())
        // No key file exists yet: decrypt must report the device key as
        // missing, never create one (which would mask corruption).
        assertFailsWith<HardwareKeyInvalidatedException> {
            provider.decrypt(Base64.Default.decode("AAAAAAAAAAAAAAAAAAAAAKSkpKSkpKSkpKSkpKSkpA=="), ByteArray(0))
        }
    }

    @Test
    fun deleteKeyOrphansPriorCiphertext() = withTempHome { _ ->
        val lock = Any()
        val provider = DesktopKeystoreProvider(lock, com.ultrabytecoder.coinsafebox.security.hardware.FileBackend())
        val blob = provider.encrypt(byteArrayOf(7, 7, 7), ByteArray(0))
        provider.deleteBlobKey()
        // After deletion a fresh key is created lazily; old ciphertext must fail.
        assertFailsWith<Exception> {
            provider.decrypt(blob, ByteArray(0))
        }
    }

    @Test
    fun corruptedKeyFileThrowsCorruptedNotInvalidated() = withTempHome { home ->
        // Create a real key, then corrupt the file in place (wrong length).
        val provider = DesktopKeystoreProvider(Any(), com.ultrabytecoder.coinsafebox.security.hardware.FileBackend())
        provider.encrypt(byteArrayOf(1), ByteArray(0))
        val keyFile = File(home, ".coinsafebox/.device_key")
        assertTrue(keyFile.exists(), "device key file must be created")
        keyFile.writeBytes(byteArrayOf(1, 2, 3))

        // A wrong-length key file is corruption, not a missing key: it must
        // surface as HardwareKeyCorruptedException (routes to recovery) and
        // never be silently regenerated.
        val second = DesktopKeystoreProvider(Any(), com.ultrabytecoder.coinsafebox.security.hardware.FileBackend())
        assertFailsWith<HardwareKeyCorruptedException> {
            second.encrypt(byteArrayOf(1), ByteArray(0))
        }
        assertFailsWith<HardwareKeyCorruptedException> {
            second.decrypt(ByteArray(33), ByteArray(0))
        }
    }

    @Test
    fun fileBackendCreatesOwnerOnlyKeyFile() = withTempHome { home ->
        val provider = DesktopKeystoreProvider(Any(), com.ultrabytecoder.coinsafebox.security.hardware.FileBackend())
        provider.encrypt(byteArrayOf(1), ByteArray(0))
        val keyFile = File(home, ".coinsafebox/.device_key")
        assertTrue(keyFile.exists(), "device key file must be created")
        if (!System.getProperty("os.name").lowercase().contains("windows")) {
            val perms = java.nio.file.Files.getPosixFilePermissions(keyFile.toPath())
            assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ), "key file must not be world-readable")
            assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ), "key file must not be group-readable")
        }
    }
}
