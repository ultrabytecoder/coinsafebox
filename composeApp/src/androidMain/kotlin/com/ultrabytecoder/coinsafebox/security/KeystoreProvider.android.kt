package com.ultrabytecoder.coinsafebox.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Android Keystore AES-256-GCM key with `setUserAuthenticationRequired(false)`
 * — no biometric prompt; the key is usable whenever the device is unlocked.
 *
 * StrongBox is requested where available (API 28+), with a transparent fallback
 * to the TEE: any failure to create a StrongBox-backed key (unsupported device,
 * OEM quirks) retries with a plain spec. The key never leaves the Keystore.
 *
 * Concurrency: all methods synchronize on [lock], the monitor supplied by
 * [HardwareKeyStore] (single-lock design — no nested-lock hazard).
 */
internal class AndroidKeystoreProvider(lock: Any) : KeystoreProvider(lock) {

    private companion object {
        const val KEY_ALIAS = "coinsafebox_device_key"
    }

    // The `AndroidKeyStore` provider does not exist on a plain JVM (local unit
    // tests). A wedged Keystore daemon / keymaster HAL can also make
    // getInstance()/load() throw (KeyStoreException, IOException,
    // CertificateException, NoSuchAlgorithmException). Fail open to the
    // in-memory fallback in all of those cases rather than crashing the caller.
    private val keyStore: KeyStore? = try {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    } catch (_: Exception) {
        null
    }

    override fun getOrCreateKey(): SecretKey? = synchronized(lock) {
        val keyStore = keyStore ?: return null
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
            )
            generateWithFallback(keyGenerator, tryStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        }
        // The alias holds a key we generated (always a SecretKeyEntry). If a
        // corrupted/OEM state put a different entry type under the alias, report
        // it as corrupt (routed to recovery) rather than a raw ClassCastException.
        val entry = try {
            keyStore.getEntry(KEY_ALIAS, null)
        } catch (e: Exception) {
            throw HardwareKeyCorruptedException("Device key entry unreadable: ${e.message}")
        }
        return when (entry) {
            is KeyStore.SecretKeyEntry -> entry.secretKey
            else -> throw HardwareKeyCorruptedException("Device key alias holds an unexpected entry type")
        }
    }

    override fun hasKey(): Boolean? = synchronized(lock) {
        val keyStore = keyStore ?: return null
        try {
            keyStore.containsAlias(KEY_ALIAS)
        } catch (_: KeyStoreException) {
            // Keystore present but not usable (e.g. load() partially failed) —
            // report "no Keystore" so the caller falls back instead of crashing.
            null
        }
    }

    override fun deleteKey() {
        synchronized(lock) {
            try {
                keyStore?.deleteEntry(KEY_ALIAS)
            } catch (_: Exception) {
                // Key already gone or Keystore unavailable — nothing to clean up.
            }
        }
    }

    private fun generateWithFallback(keyGenerator: KeyGenerator, tryStrongBox: Boolean) {
        try {
            keyGenerator.init(buildSpec(strongBox = tryStrongBox))
            keyGenerator.generateKey()
        } catch (e: Exception) {
            if (!tryStrongBox) throw e
            // StrongBox is the only feature we degrade. StrongBoxUnavailableException
            // is the precise signal; the broad ProviderException check would also
            // swallow unrelated provider failures (keymaster OOM, attestation) and
            // mask them as a StrongBox fallback.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && e !is StrongBoxUnavailableException) throw e
            try {
                keyStore?.deleteEntry(KEY_ALIAS)
            } catch (_: Exception) {
            }
            keyGenerator.init(buildSpec(strongBox = false))
            keyGenerator.generateKey()
        }
    }

    private fun buildSpec(strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .setInvalidatedByBiometricEnrollment(false)
            .setRandomizedEncryptionRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && strongBox) {
            builder.setIsStrongBoxBacked(true)
        }
        return builder.build()
    }
}

internal actual fun createKeystoreProvider(lock: Any): KeystoreProvider = AndroidKeystoreProvider(lock)
