package com.ultrabytecoder.coinsafebox.security

import com.ultrabytecoder.coinsafebox.data.SettingsStore
import com.ultrabytecoder.coinsafebox.domain.repository.PinConfig
import com.ultrabytecoder.coinsafebox.domain.repository.SecurityMethod
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kotlincrypto.random.CryptoRand

/**
 * Single atomic key-material envelope: wrapped DEK + hardware-wrapped salt +
 * KDF parameters, serialized to one JSON blob and written with a single
 * [SettingsStorage.putString] call (commit()-backed), so a crash between writes
 * can never leave partial key material.
 *
 * Version 2 records the KDF algorithm and its parameters explicitly:
 *  - All platforms: Argon2id (`kdfAlgorithm = "ARGON2ID"`), `iterations` is the
 *    Argon2 time cost (passes), plus `memoryKib`/`parallelism`.
 *  - PBKDF2-HMAC-SHA256 (`kdfAlgorithm = "PBKDF2-SHA256"`) is used only as a
 *    runtime fallback when Argon2id is unavailable (legacy iOS envelopes, or a
 *    transient libsodium failure); `iterations` is the PBKDF2 iteration count.
 */
@Serializable
private data class WrappedDekEnvelope(
    val version: Int,
    val salt: String,
    val wrapped: String,
    val iterations: Int,
    val kdfAlgorithm: String,
    val memoryKib: Int = 32768,
    val parallelism: Int = 1
)

/**
 * Thrown when a stored DEK envelope exists but cannot be parsed (truncated JSON,
 * unknown version, malformed fields). Distinct from "no envelope" so the caller can
 * route to the recovery flow instead of the fresh-setup flow (which would otherwise
 * let the user create a new wallet and lose access to the old one).
 */
class EnvelopeCorruptionException(message: String) : Exception(message)

/** Parsed DEK envelope. */
private data class EnvelopeData(
    val salt: String,
    val wrapped: String,
    val iterations: Int,
    val kdfAlgorithm: String,
    val memoryKib: Int,
    val parallelism: Int
)

/**
 * Manages the database encryption key hierarchy (Envelope Encryption):
 *
 *  - [DEK] (Data Encryption Key, 32 random bytes) encrypts the SQLCipher database.
 *  - [KEK] (Key Encryption Key) = KDF(credential, salt, params) wraps the DEK at rest:
 *    Argon2id on all platforms (BouncyCastle on JVM, libsodium on iOS) with a
 *    PBKDF2-HMAC-SHA256 runtime fallback ([Kdf], [PinConfig]).
 *  - The KDF **salt** is wrapped by the always-on device hardware key
 *    ([HardwareKeyStore], Android Keystore / iOS Secure Enclave, no biometric prompt),
 *    so deriving the KEK requires the device: offline PIN brute-force is impossible
 *    and app data is unusable on another device.
 *  - The DEK wrap is authenticated with AES-GCM and bound (AAD) to the install ID
 *    and the wrap version, so a wrapped DEK from another install/version cannot
 *    be swapped in.
 *
 * The PIN is set up first (startup wizard), so the DEK is wrapped by the PIN from the
 * very first moment — a raw DEK never exists outside [SessionManager]'s memory.
 *
 * The wrapped DEK is authenticated (AES-GCM), so a wrong PIN fails the unwrap with an
 * authentication error — the unwrap itself is the PIN verification.
 */
class KeyManager(
    private val settingsStorage: SettingsStore
) {

    private companion object {
        const val KEY_WRAPPED_DEK = "dek_envelope"
        const val KEY_INSTALL_ID = "dek_install_id"
        const val WRAP_VERSION = 2
        const val DEK_SIZE = 32
        const val INSTALL_ID_SIZE = 16
        const val KDF_ARGON2ID = "ARGON2ID"
        const val KDF_PBKDF2_SHA256 = "PBKDF2-SHA256"
    }

    private val json = Json { ignoreUnknownKeys = true }

    init {
        // Bind the device key to this install (F-11, Windows DPAPI entropy).
        // Must run before the first HardwareKeyStore encrypt/decrypt.
        val id = installIdBytes()
        try {
            HardwareKeyStore.configureInstallId(id)
        } finally {
            id.wipe()
        }
    }

    /**
     * True when a parseable PIN key-material envelope exists. A corrupted envelope
     * is treated as "no material" here (the startup flow then shows the setup
     * screen, where the user is routed to recovery); the corruption itself is
     * surfaced with a distinct error when the user actually tries to verify a PIN
     * (see [unwrapDekWithPin]).
     */
    fun hasPinKeyMaterial(): Boolean = try {
        loadEnvelope() != null
    } catch (e: EnvelopeCorruptionException) {
        false
    }

    /**
     * True when a DEK envelope value is stored at all — WITHOUT parsing it. A
     * corrupted envelope still counts here, unlike [hasPinKeyMaterial] (which
     * reports corruption as "no material"). Use this to decide whether fresh key
     * setup would DESTROY existing key material: a corrupted-but-present
     * envelope must route to recovery, never to a silent fresh setup.
     */
    fun hasRawEnvelope(): Boolean = settingsStorage.getString(KEY_WRAPPED_DEK) != null

    /**
     * Generates a brand-new DEK and wraps it with [pin] (fresh setup / recovery).
     * Caller owns the returned array.
     */
    fun generateAndWrapDek(pin: CharArray, method: SecurityMethod): ByteArray {
        val dek = CryptoRand.Default.nextBytes(ByteArray(DEK_SIZE))
        try {
            wrapDekWithPin(pin, dek, method)
        } catch (e: Exception) {
            dek.wipe()
            throw e
        }
        return dek
    }

    /**
     * Derives the KEK from [pin] and unwraps the DEK.
     * Returns null when the PIN is wrong or the key material is missing/corrupt —
     * never throws for an authentication failure.
     *
     * @throws HardwareKeyInvalidatedException when the device hardware key is
     * missing/invalidated — the caller must route to recovery (PIN re-setup).
     * @throws HardwareKeyCorruptedException when the device hardware key is
     * present but malformed (e.g. a truncated key file) — the caller must
     * route to recovery as well; a corrupted key must never be silently
     * replaced, or data encrypted under the old key would be orphaned.
     */
    fun unwrapDekWithPin(pin: CharArray, method: SecurityMethod): ByteArray? {
        val envelope = try {
            loadEnvelope()
        } catch (e: EnvelopeCorruptionException) {
            // A stored envelope exists but is unparseable — the DEK is unrecoverable
            // without a fresh setup. Surface as hardware-invalidated so the UI routes
            // to recovery rather than misreporting "wrong PIN" or silently re-wrapping
            // over the (now unreadable) old key material.
            throw HardwareKeyInvalidatedException("DEK envelope is corrupted: ${e.message}")
        } ?: return null

        val wrapped = try {
            Base64.Default.decode(envelope.wrapped)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val saltBlob = try {
            Base64.Default.decode(envelope.salt)
        } catch (e: IllegalArgumentException) {
            wrapped.wipe()
            return null
        }

        var pinBytes: ByteArray? = null
        var kek: ByteArray? = null
        var salt: ByteArray? = null
        var dek: ByteArray? = null
        return try {
            salt = try {
                HardwareKeyStore.decrypt(saltBlob)
            } catch (e: HardwareKeyInvalidatedException) {
                throw e
            } catch (e: HardwareKeyCorruptedException) {
                // Propagate distinctly: a corrupted device key must route to
                // recovery and must NOT be silently replaced with a fresh one.
                throw e
            } catch (e: AesGcmAuthenticationException) {
                throw HardwareKeyInvalidatedException("Hardware key mismatch with stored salt")
            } catch (e: Exception) {
                throw HardwareKeyInvalidatedException("Hardware key unavailable: ${e.message}")
            }
            pinBytes = pin.toPinBytes()
            // The stored KDF parameters win: they are the values the DEK was wrapped
            // with, so re-deriving with different parameters would always fail GCM.
            kek = deriveKek(pinBytes, salt, envelope)
            val aad = wrapAad()
            dek = try {
                AesGcm.decrypt(kek, wrapped, aad)
            } finally {
                aad.wipe()
            }
            dek
        } catch (e: AesGcmAuthenticationException) {
            null
        } catch (e: KdfException) {
            // Argon2id backend failed at runtime (e.g. memory allocation failure
            // under pressure). The DEK is unrecoverable on this device right now —
            // surface as hardware-invalidated so the UI routes to recovery rather
            // than crashing or misreporting "wrong PIN".
            throw HardwareKeyInvalidatedException("Key derivation failed: ${e.message}")
        } finally {
            wrapped.wipe()
            saltBlob.wipe()
            pinBytes?.wipe()
            kek?.wipe()
            salt?.wipe()
        }
    }

    /**
     * Re-wraps an already-unwrapped [dek] with [newPin] (PIN change). The caller
     * has already verified the old PIN by unwrapping [dek] itself, so this skips
     * the second KDF derivation (NEW-12). Returns false when the wrap fails.
     * The caller keeps ownership of [dek] and must wipe it when done.
     */
    fun rewrapDekWithDek(dek: ByteArray, newPin: CharArray, newMethod: SecurityMethod): Boolean {
        return try {
            wrapDekWithPin(newPin, dek, newMethod)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Wipes all key material (recovery / reset). */
    fun deleteAll() {
        settingsStorage.remove(KEY_WRAPPED_DEK)
        // Remove the device hardware key too: the salt/DEK envelopes it protected
        // are gone, and a fresh key is created lazily on the next setup (iOS
        // Keychain items otherwise persist across app reinstalls).
        try {
            HardwareKeyStore.deleteKey()
        } catch (_: Exception) {
        }
    }

    private fun timeCostFor(method: SecurityMethod): Int = when (method) {
        SecurityMethod.PIN -> PinConfig.KDF_TIME_COST_PIN
        SecurityMethod.PASSWORD -> PinConfig.KDF_TIME_COST_PASSWORD
    }

    private fun wrapDekWithPin(pin: CharArray, dek: ByteArray, method: SecurityMethod) {
        var pinBytes: ByteArray? = null
        var kek: ByteArray? = null
        var wrapped: ByteArray? = null
        var salt: ByteArray? = null
        var hwSaltBlob: ByteArray? = null
        try {
            salt = CryptoRand.Default.nextBytes(ByteArray(PinConfig.SALT_SIZE))
            // Wrap the salt with the device hardware key BEFORE storing anything:
            // a failure here aborts setup without leaving partial key material.
            hwSaltBlob = HardwareKeyStore.encrypt(salt)
            val timeCost = timeCostFor(method)
            pinBytes = pin.toPinBytes()
            // Prefer Argon2id; if the platform's Argon2id backend fails at
            // runtime (e.g. libsodium memory allocation failure under pressure),
            // degrade to PBKDF2 for this envelope. The algorithm is recorded
            // honestly in the envelope so unwrap uses the matching path.
            val (algorithm, iterations) = if (Kdf.supportsArgon2id) {
                try {
                    kek = Kdf.derive(
                        password = pinBytes,
                        salt = salt,
                        timeCost = timeCost,
                        derivedKeyLengthBytes = DEK_SIZE,
                        memoryKib = PinConfig.KDF_MEMORY_KIB,
                        parallelism = PinConfig.KDF_PARALLELISM
                    )
                    KDF_ARGON2ID to timeCost
                } catch (e: KdfException) {
                    // Argon2id failed at runtime — degrade to PBKDF2 for this
                    // envelope. The algorithm is recorded honestly in the envelope so
                    // unwrap uses the matching path.
                    kek = Pbkdf2.derive(
                        password = pinBytes,
                        salt = salt,
                        iterations = PinConfig.PBKDF2_FALLBACK_ITERATIONS,
                        derivedKeyLengthBytes = DEK_SIZE,
                        algorithm = Pbkdf2Algorithm.SHA256
                    )
                    KDF_PBKDF2_SHA256 to PinConfig.PBKDF2_FALLBACK_ITERATIONS
                }
            } else {
                kek = Pbkdf2.derive(
                    password = pinBytes,
                    salt = salt,
                    iterations = PinConfig.PBKDF2_FALLBACK_ITERATIONS,
                    derivedKeyLengthBytes = DEK_SIZE,
                    algorithm = Pbkdf2Algorithm.SHA256
                )
                KDF_PBKDF2_SHA256 to PinConfig.PBKDF2_FALLBACK_ITERATIONS
            }
            val aad = wrapAad()
            wrapped = try {
                AesGcm.encrypt(kek, dek, aad)
            } finally {
                aad.wipe()
            }
            // Record Argon2 memory/parallelism only for Argon2id envelopes; a
            // PBKDF2 envelope has no such parameters (deriveKek ignores them,
            // but storing 0 keeps the envelope self-describing).
            val (memKib, par) = if (algorithm == KDF_ARGON2ID) {
                PinConfig.KDF_MEMORY_KIB to PinConfig.KDF_PARALLELISM
            } else {
                0 to 0
            }
            val envelope = WrappedDekEnvelope(
                version = WRAP_VERSION,
                salt = Base64.Default.encode(hwSaltBlob),
                wrapped = Base64.Default.encode(wrapped),
                iterations = iterations,
                kdfAlgorithm = algorithm,
                memoryKib = memKib,
                parallelism = par
            )
            // Single atomic write (commit()-backed) — a crash here leaves either
            // the old envelope or the new one, never a partial mix.
            settingsStorage.putString(KEY_WRAPPED_DEK, json.encodeToString(envelope))
        } finally {
            pinBytes?.wipe()
            kek?.wipe()
            wrapped?.wipe()
            salt?.wipe()
            hwSaltBlob?.wipe()
        }
    }

    /**
     * Derives the KEK with the KDF recorded in [envelope]. The dispatch is on the
     * envelope's recorded algorithm, not the platform capability, so an envelope
     * created on any platform unwraps on any platform that can run that KDF.
     *
     * @throws HardwareKeyInvalidatedException when the envelope's KDF cannot run
     * on this platform (e.g. an Argon2id envelope on a device where libsodium
     * failed to initialize) — the DEK is unrecoverable here and the caller must
     * route to recovery.
     */
    private fun deriveKek(pinBytes: ByteArray, salt: ByteArray, envelope: EnvelopeData): ByteArray =
        when (envelope.kdfAlgorithm) {
            KDF_ARGON2ID -> {
                if (!Kdf.supportsArgon2id) {
                    throw HardwareKeyInvalidatedException(
                        "Argon2id envelope but Argon2id unavailable on this platform"
                    )
                }
                Kdf.derive(
                    password = pinBytes,
                    salt = salt,
                    timeCost = envelope.iterations,
                    derivedKeyLengthBytes = DEK_SIZE,
                    memoryKib = envelope.memoryKib,
                    parallelism = envelope.parallelism
                )
            }

            KDF_PBKDF2_SHA256 -> Pbkdf2.derive(
                password = pinBytes,
                salt = salt,
                iterations = envelope.iterations,
                derivedKeyLengthBytes = DEK_SIZE,
                algorithm = Pbkdf2Algorithm.SHA256
            )

            else -> throw IllegalStateException("Unknown KDF algorithm: ${envelope.kdfAlgorithm}")
        }

    /**
     * Reads the AAD-bound DEK envelope. Returns null when no envelope is stored.
     *
     * @throws EnvelopeCorruptionException when an envelope IS stored but cannot be
     * parsed (truncated JSON, unknown version, malformed fields) — the caller must
     * distinguish this from "missing" so it can route to recovery.
     */
    private fun loadEnvelope(): EnvelopeData? {
        val stored = settingsStorage.getString(KEY_WRAPPED_DEK) ?: return null
        val envelope = try {
            json.decodeFromString<WrappedDekEnvelope>(stored)
        } catch (e: Exception) {
            throw EnvelopeCorruptionException("unparseable envelope: ${e.message}")
        }
        if (envelope.version != WRAP_VERSION) {
            throw EnvelopeCorruptionException("unsupported envelope version ${envelope.version}")
        }
        // Validate the Base64 fields: malformed Base64 is corruption, not a wrong
        // PIN. If we let Base64.decode throw IllegalArgumentException in
        // unwrapDekWithPin, it would be caught and return null (wrong PIN),
        // burning a PIN attempt instead of routing to recovery.
        try {
            Base64.Default.decode(envelope.wrapped)
        } catch (e: IllegalArgumentException) {
            throw EnvelopeCorruptionException("malformed Base64 in wrapped DEK")
        }
        try {
            Base64.Default.decode(envelope.salt)
        } catch (e: IllegalArgumentException) {
            throw EnvelopeCorruptionException("malformed Base64 in salt")
        }
        return EnvelopeData(
            salt = envelope.salt,
            wrapped = envelope.wrapped,
            iterations = envelope.iterations,
            kdfAlgorithm = envelope.kdfAlgorithm,
            memoryKib = envelope.memoryKib,
            parallelism = envelope.parallelism
        )
    }

    /** Stable per-install identifier generated on first use (not secret). */
    internal fun installIdBytes(): ByteArray {
        val stored = settingsStorage.getString(KEY_INSTALL_ID)
        if (stored != null) {
            try {
                return Base64.Default.decode(stored)
            } catch (_: IllegalArgumentException) {
                // corrupt value — regenerate below
            }
        }
        val id = CryptoRand.Default.nextBytes(ByteArray(INSTALL_ID_SIZE))
        settingsStorage.putString(KEY_INSTALL_ID, Base64.Default.encode(id))
        return id
    }

    /** AAD binding the DEK wrap to this install and the wrap algorithm version. */
    private fun wrapAad(): ByteArray {
        val id = installIdBytes()
        return try {
            id + byteArrayOf(WRAP_VERSION.toByte())
        } finally {
            id.wipe()
        }
    }
}
