package com.ultrabytecoder.coinsafebox.data

import com.ultrabytecoder.coinsafebox.domain.repository.ChangePinResult
import com.ultrabytecoder.coinsafebox.domain.repository.PinConfig
import com.ultrabytecoder.coinsafebox.domain.repository.PinRepository
import com.ultrabytecoder.coinsafebox.domain.repository.PinState
import com.ultrabytecoder.coinsafebox.domain.repository.SecurityMethod
import com.ultrabytecoder.coinsafebox.domain.repository.VerifyResult
import com.ultrabytecoder.coinsafebox.domain.usecase.CredentialValidator
import com.ultrabytecoder.coinsafebox.security.AesGcmAuthenticationException
import com.ultrabytecoder.coinsafebox.security.gcHint
import com.ultrabytecoder.coinsafebox.security.HardwareKeyCorruptedException
import com.ultrabytecoder.coinsafebox.security.HardwareKeyInvalidatedException
import com.ultrabytecoder.coinsafebox.security.HardwareKeyStore
import com.ultrabytecoder.coinsafebox.security.KeyManager
import com.ultrabytecoder.coinsafebox.security.SessionManager
import com.ultrabytecoder.coinsafebox.security.UnlockResult
import com.ultrabytecoder.coinsafebox.security.monotonicNowMillis
import com.ultrabytecoder.coinsafebox.security.wipe
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PinSecureData(
    val failedAttempts: Int = 0,
    val lockedUntil: Long = 0L,
    val lockoutCount: Int = 0,
    // Monotonic lockout window (ms since boot, see monotonicNowMillis). Guards the
    // lockout against wall-clock tampering; only authoritative within the same boot
    // (monotonicLockedAt acts as the same-boot marker).
    val monotonicLockedAt: Long = 0L,
    val monotonicLockedUntil: Long = 0L
)

private const val PIN_DATA_KEY = "pin_data"

/**
 * Thrown by [PinRepositoryImpl.setupPin] when existing DEK key material would be
 * destroyed without an explicit recovery acknowledgement. The UI must confirm
 * recovery with the user and retry with `recoveryAcknowledged = true`.
 */
class ExistingKeyMaterialException(message: String) : Exception(message)

/**
 * PIN lifecycle: setup, verification (via envelope unwrap of the DEK — the correct PIN
 * is proven by a successful GCM authentication), lockout bookkeeping.
 *
 * The DEK key material lives in [KeyManager]; this repository only tracks attempt
 * counters and lockout state. PIN verification hands the unwrapped DEK to
 * [SessionManager], which opens the SQLCipher database for the session.
 *
 * The lockout state ([PinSecureData]) is stored hardware-encrypted (AES-GCM via the
 * device key): an attacker with file-system access cannot reset the attempt counters
 * or lockout timestamps — any tamper fails authentication and is treated as
 * corruption (max attempts, force recovery).
 */
class PinRepositoryImpl(
    private val settingsStorage: SettingsStore,
    private val keyManager: KeyManager,
    private val sessionManager: SessionUnlocker,
    private val walletRepository: com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
) : PinRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val initScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _pinStateFlow = MutableStateFlow<PinState>(PinState.Loading)
    private val _securityMethodFlow = MutableStateFlow<SecurityMethod?>(null)

    override val pinStateFlow: StateFlow<PinState> = _pinStateFlow.asStateFlow()
    override val securityMethodFlow: StateFlow<SecurityMethod?> = _securityMethodFlow.asStateFlow()

    init {
        initScope.launch {
            loadState()
        }
    }

    private suspend fun loadState() {
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val storedMethod = settingsStorage.getString(SettingsKeys.SECURITY_METHOD)
                if (storedMethod != null) {
                    _securityMethodFlow.value = try {
                        SecurityMethod.valueOf(storedMethod)
                    } catch (_: Exception) {
                        null
                    }
                }

                val stored = settingsStorage.getString(PIN_DATA_KEY)
                if (stored == null) {
                    // No lockout metadata. If key material exists the state is
                    // inconsistent (metadata lost while a wallet may exist) —
                    // route to recovery instead of offering a fresh setup that
                    // would deleteAll() the existing envelope. hasRawEnvelope
                    // (not hasPinKeyMaterial) so a CORRUPTED-but-present
                    // envelope also counts as existing material.
                    _pinStateFlow.value = if (keyManager.hasRawEnvelope()) {
                        PinState.Setup(
                            failedAttempts = PinConfig.MAX_ATTEMPTS,
                            lockedUntil = 0,
                            isCorrupted = true
                        )
                    } else {
                        PinState.NotSetup
                    }
                    return@withContext
                }

                try {
                    val data = loadData(stored)
                    val keyMaterialOk = keyManager.hasPinKeyMaterial()
                    _pinStateFlow.value = PinState.Setup(
                        failedAttempts = data.failedAttempts,
                        lockedUntil = data.lockedUntil,
                        isCorrupted = !keyMaterialOk
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _pinStateFlow.value = PinState.Setup(
                        failedAttempts = PinConfig.MAX_ATTEMPTS,
                        lockedUntil = 0,
                        isCorrupted = true
                    )
                }
            }
        }
    }

    override suspend fun setupPin(pin: CharArray, method: SecurityMethod, recoveryAcknowledged: Boolean) =
        withContext(Dispatchers.Default) {
        require(CredentialValidator.validate(method, pin).ok) {
            "Invalid credential"
        }

        mutex.withLock {
            // Guard against silent wallet destruction: if a DEK envelope (raw,
            // parsed or corrupted) exists, fresh setup would deleteAll() it and
            // orphan the SQLCipher database. Only the acknowledged recovery flow
            // may proceed; a missing lockout state with key material present is
            // surfaced as corrupted by loadState()/loadLockoutGate().
            if (keyManager.hasRawEnvelope() && !recoveryAcknowledged) {
                _pinStateFlow.value = PinState.Setup(
                    failedAttempts = PinConfig.MAX_ATTEMPTS,
                    lockedUntil = 0,
                    isCorrupted = true
                )
                throw ExistingKeyMaterialException(
                    "Existing wallet key material found. Recovery required — setting up a new PIN will erase it."
                )
            }
            // The credential is set up first (startup wizard), so no raw DEK exists yet —
            // always generate a fresh one and open the (empty) database with it.
            // Recreating the DB is safe here: there is no wallet data at stake
            // (this is the fresh-setup / recovery path, see SessionManager.unlockRecreating).
            keyManager.deleteAll()
            val dek = try {
                keyManager.generateAndWrapDek(pin, method)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Hardware key / crypto failure — surface a user-friendly message
                // on the PIN screen and leave no partial key material behind.
                keyManager.deleteAll()
                throw IllegalStateException("Failed to generate key", e)
            }

            val opened = try {
                sessionManager.unlockRecreating(dek)
            } catch (e: CancellationException) {
                dek.wipe()
                keyManager.deleteAll()
                throw e
            } catch (e: Exception) {
                // The database could not be opened even after recreation —
                // surface the real cause (e.g. a missing/mislinked native
                // SQLCipher library) instead of a generic message.
                dek.wipe()
                keyManager.deleteAll()
                throw IllegalStateException("Failed to initialize secure storage: ${e.message}", e)
            }
            if (!opened) {
                dek.wipe()
                keyManager.deleteAll()
                throw IllegalStateException("Failed to initialize secure storage")
            }
            // Success: sessionManager owns `dek`.

            settingsStorage.putString(SettingsKeys.SECURITY_METHOD, method.name)
            if (method == SecurityMethod.PIN) {
                settingsStorage.putString(SettingsKeys.PIN_LENGTH, pin.size.toString())
            }
            _securityMethodFlow.value = method
            saveData(PinSecureData())
            _pinStateFlow.value = PinState.Setup(
                failedAttempts = 0,
                lockedUntil = 0,
                isCorrupted = false
            )
        }
    }

    override suspend fun verifyPin(pin: CharArray): VerifyResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            var dek: ByteArray? = null
            // True once SessionManager has taken ownership of the unwrapped DEK
            // (only on UnlockResult.Success). On every other exit path the DEK must
            // be wiped — including on CancellationException and unexpected Errors —
            // so it is never left in a local for the GC to collect.
            var sessionOwnsDek = false

            try {
            val result: VerifyResult = when (val gate = loadLockoutGate()) {
                // Corrupted data (tampered lockout state) — force recovery.
                is LockoutGate.Corrupted -> VerifyResult.Corrupted

                is LockoutGate.Locked -> VerifyResult.Locked(gate.lockedUntil)

                is LockoutGate.Open -> {
                    // Envelope unwrap inside the lock — avoids stale data races.
                    // A correct PIN is proven by the GCM authentication, no stored hash needed.
                    // Hardware-key invalidation is distinct from a wrong PIN → Corrupted.
                    var hwInvalidated = false
                    val method = _securityMethodFlow.value ?: SecurityMethod.PIN
                    dek = try {
                        keyManager.unwrapDekWithPin(pin, method)
                    } catch (e: HardwareKeyInvalidatedException) {
                        hwInvalidated = true
                        null
                    } catch (e: HardwareKeyCorruptedException) {
                        // A corrupted (not missing) device key routes to
                        // recovery the same way; it must never be silently
                        // replaced.
                        hwInvalidated = true
                        null
                    }

                    if (hwInvalidated) {
                        _pinStateFlow.value = PinState.Setup(
                            failedAttempts = PinConfig.MAX_ATTEMPTS,
                            lockedUntil = 0,
                            isCorrupted = true
                        )
                        VerifyResult.Corrupted
                    } else if (dek != null) {
                        // Reset the lockout counter BEFORE opening the session:
                        // the GCM auth above already proved the PIN is correct,
                        // so the counter reset is unconditional. If saveData
                        // throws, the session is not yet unlocked and the DEK
                        // is wiped in finally — safe retry. If unlock() later
                        // fails (corrupt DB), the counter was reset
                        // unnecessarily, but the user is routed to recovery
                        // anyway where the counter is irrelevant.
                        val resetData = gate.data.copy(
                            failedAttempts = 0,
                            lockedUntil = 0L,
                            lockoutCount = 0,
                            monotonicLockedAt = 0L,
                            monotonicLockedUntil = 0L
                        )
                        saveData(resetData)
                        when (val result = sessionManager.unlock(dek)) {
                            is UnlockResult.Success -> {
                                sessionOwnsDek = true
                                _pinStateFlow.value = PinState.Setup(
                                    failedAttempts = 0,
                                    lockedUntil = 0,
                                    isCorrupted = false
                                )
                                VerifyResult.Success
                            }
                            is UnlockResult.Failed -> {
                                // The database could not be opened (wrong key, corrupt
                                // file) — route to recovery.
                                dek.wipe()
                                dek = null
                                _pinStateFlow.value = PinState.Setup(
                                    failedAttempts = PinConfig.MAX_ATTEMPTS,
                                    lockedUntil = 0,
                                    isCorrupted = true
                                )
                                VerifyResult.Corrupted
                            }
                            is UnlockResult.Locked -> {
                                // A transient lock request (e.g. idle timeout) fired
                                // while the driver was opening. The PIN was correct
                                // (the DEK unwrapped successfully) but the session is
                                // now locked. Do NOT route to recovery — prompt the
                                // user to try again.
                                dek.wipe()
                                dek = null
                                VerifyResult.SessionLocked
                            }
                        }
                    } else {
                        recordWrongPin(gate.data, gate.now, gate.nowMono)
                    }
                }
            }

            result
            } finally {
                // Wipe the unwrapped DEK on every exit path (normal return,
                // CancellationException, or an unexpected Error) unless the session
                // took ownership of it on Success — it is then wiped by lock().
                if (!sessionOwnsDek) {
                    dek?.wipe()
                    dek = null
                }
            }
        }
    }

    override suspend fun changePin(oldPin: CharArray, newPin: CharArray, newMethod: SecurityMethod): ChangePinResult =
        withContext(Dispatchers.Default) {
            require(CredentialValidator.validate(newMethod, newPin).ok) {
                "Invalid new credential"
            }
            mutex.withLock {
                // 1. Lockout gate shared with verifyPin (NEW-2): a locked-out user
                //    cannot brute-force the old credential through the change screen.
                val result: ChangePinResult = when (val gate = loadLockoutGate()) {
                    is LockoutGate.Corrupted ->
                        ChangePinResult.Failed("PIN data is corrupted. Recovery required.")
                    is LockoutGate.Locked ->
                        ChangePinResult.Locked(gate.lockedUntil)
                    is LockoutGate.Open -> {
                        // 2. Verify the old credential (GCM-authenticated DEK unwrap, no stored
                        //    verifier). The unwrapped DEK is kept for step 3 — a single
                        //    PBKDF2 derivation for the whole change (NEW-12).
                        val oldMethod = _securityMethodFlow.value ?: SecurityMethod.PIN
                        var oldDek: ByteArray? = null
                        try {
                            oldDek = keyManager.unwrapDekWithPin(oldPin, oldMethod)
                        } catch (e: HardwareKeyInvalidatedException) {
                            return@withLock ChangePinResult.Failed("PIN data is corrupted. Recovery required.")
                        } catch (e: HardwareKeyCorruptedException) {
                            // Corrupted device key (present but malformed) —
                            // route to recovery, never silently replace.
                            return@withLock ChangePinResult.Failed("PIN data is corrupted. Recovery required.")
                        }

                        if (oldDek == null) {
                            // Wrong old credential — count the attempt through the shared
                            // lockout logic (escalating lockout, NEW-2).
                            return@withLock when (val r = recordWrongPin(gate.data, gate.now, gate.nowMono)) {
                                is VerifyResult.Locked -> ChangePinResult.Locked(r.lockedUntil)
                                else -> ChangePinResult.WrongOldPin
                            }
                        }

                        // 3. Switch the DEK envelope to the new credential — the mnemonic is stored
                        //    plaintext in the SQLCipher database and needs no re-keying
                        //    (the new DEK encrypts it as soon as the DB is rewritten).
                        //    Any exception (hardware key failure) is treated as a wrap
                        //    failure so the change is aborted (NEW-5).
                        val rewrapped = try {
                            keyManager.rewrapDekWithDek(oldDek, newPin, newMethod)
                        } catch (e: CancellationException) {
                            oldDek.wipe()
                            throw e
                        } catch (e: Exception) {
                            false
                        } finally {
                            oldDek.wipe()
                        }
                        if (!rewrapped) {
                            ChangePinResult.Failed("Failed to update credential key material")
                        } else {
                            // 4. Success — the old credential verified, so clear the attempt counters.
                            val resetData = gate.data.copy(
                                failedAttempts = 0,
                                lockedUntil = 0L,
                                lockoutCount = 0,
                                monotonicLockedAt = 0L,
                                monotonicLockedUntil = 0L
                            )
                            settingsStorage.putString(SettingsKeys.SECURITY_METHOD, newMethod.name)
                            _securityMethodFlow.value = newMethod
                            saveData(resetData)
                            _pinStateFlow.value = PinState.Setup(
                                failedAttempts = 0,
                                lockedUntil = 0,
                                isCorrupted = false
                            )
                            ChangePinResult.Success
                        }
                    }
                }
                result
            }
        }

    internal suspend fun resetLockState() = withContext(Dispatchers.Default) {
        mutex.withLock {
            val stored = settingsStorage.getString(PIN_DATA_KEY) ?: return@withContext
            val data = try {
                loadData(stored)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Corrupt/tampered lockout state — treat as corrupted, force recovery.
                _pinStateFlow.value = PinState.Setup(
                    failedAttempts = PinConfig.MAX_ATTEMPTS,
                    lockedUntil = 0,
                    isCorrupted = true
                )
                return@withLock
            }
            val resetData = data.copy(
                failedAttempts = 0,
                lockedUntil = 0L,
                lockoutCount = 0,
                monotonicLockedAt = 0L,
                monotonicLockedUntil = 0L
            )
            saveData(resetData)
            _pinStateFlow.value = PinState.Setup(
                failedAttempts = 0,
                lockedUntil = 0,
                isCorrupted = false
            )
        }
    }

    /**
     * Shared lockout gate for every PIN-verified operation ([verifyPin] and
     * [changePin]): loads the stored lockout state, handles corruption, checks the
     * hybrid wall/monotonic lockout clock and resets expired attempt counters.
     */
    private sealed interface LockoutGate {
        /** Lockout state missing/tampered — the caller must force recovery. */
        data object Corrupted : LockoutGate

        /** The PIN is currently locked out; [lockedUntil] is the wall-clock expiry. */
        data class Locked(val lockedUntil: Long) : LockoutGate

        /** PIN verification may proceed; carries the current counters and the
         * wall/monotonic timestamps captured at gate evaluation time. */
        data class Open(
            val data: PinSecureData,
            val now: Long,
            val nowMono: Long
        ) : LockoutGate
    }

    private fun loadLockoutGate(): LockoutGate {
        // Handle corrupted data
        val loaded = try {
            val stored = settingsStorage.getString(PIN_DATA_KEY)
            if (stored == null) null else loadData(stored)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _pinStateFlow.value = PinState.Setup(
                failedAttempts = PinConfig.MAX_ATTEMPTS,
                lockedUntil = 0,
                isCorrupted = true
            )
            null
        }
        if (loaded == null) {
            // Missing or unreadable lockout metadata: if raw key material
            // exists, the state is inconsistent (metadata lost or tampered
            // while a wallet may exist) — surface as corrupted so the user
            // is routed to recovery, never to a silent fresh setup that
            // would destroy the wallet.
            if (keyManager.hasRawEnvelope()) {
                _pinStateFlow.value = PinState.Setup(
                    failedAttempts = PinConfig.MAX_ATTEMPTS,
                    lockedUntil = 0,
                    isCorrupted = true
                )
            }
            return LockoutGate.Corrupted
        }

        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val nowMono = monotonicNowMillis()

        // Hybrid lockout clock: wall time (survives reboots) OR monotonic
        // time (immune to user clock changes, authoritative within the same
        // boot — monotonicLockedAt is the same-boot marker; after a reboot
        // monotonic restarts below it and the wall clock takes over).
        val wallLocked = now < loaded.lockedUntil
        val monoLocked = nowMono >= loaded.monotonicLockedAt &&
            nowMono < loaded.monotonicLockedUntil

        if (wallLocked || monoLocked) {
            val remainingMs = maxOf(
                loaded.lockedUntil - now,
                loaded.monotonicLockedUntil - nowMono
            ).coerceAtLeast(0L)
            return LockoutGate.Locked(now + remainingMs)
        }

        // Reset failed attempts if the lockout has expired (lockoutCount persists for escalation)
        val currentData = if (loaded.lockedUntil > 0L || loaded.monotonicLockedUntil > 0L) {
            val resetData = loaded.copy(
                failedAttempts = 0,
                lockedUntil = 0L,
                monotonicLockedAt = 0L,
                monotonicLockedUntil = 0L
            )
            saveData(resetData)
            resetData
        } else {
            loaded
        }
        return LockoutGate.Open(currentData, now, nowMono)
    }

    /**
     * Records a wrong-PIN attempt with escalating lockout (shared by [verifyPin]
     * and [changePin], so no PIN-verified entry point bypasses the rate limit).
     */
    private fun recordWrongPin(data: PinSecureData, now: Long, nowMono: Long): VerifyResult {
        val newFailed = data.failedAttempts + 1
        val lockoutSeconds = if (newFailed >= PinConfig.MAX_ATTEMPTS) {
            val newLockoutCount = data.lockoutCount + 1
            val escalation = newLockoutCount - 1
            val shift = escalation.coerceAtMost(62)
            val maxMultiplier = PinConfig.MAX_LOCKOUT.inWholeSeconds / PinConfig.INITIAL_LOCKOUT.inWholeSeconds
            val multiplier = (1L shl shift).coerceAtMost(maxMultiplier)
            PinConfig.INITIAL_LOCKOUT.inWholeSeconds * multiplier
        } else {
            0L
        }

        val lockedUntil = if (lockoutSeconds > 0L) now + lockoutSeconds * 1000 else 0L
        val monoLockedAt = if (lockoutSeconds > 0L) nowMono else 0L
        val monoLockedUntil = if (lockoutSeconds > 0L) nowMono + lockoutSeconds * 1000 else 0L
        val remaining = PinConfig.MAX_ATTEMPTS - newFailed

        val updatedData = data.copy(
            failedAttempts = newFailed,
            lockedUntil = lockedUntil,
            monotonicLockedAt = monoLockedAt,
            monotonicLockedUntil = monoLockedUntil,
            lockoutCount = if (lockoutSeconds > 0L) data.lockoutCount + 1 else data.lockoutCount
        )
        saveData(updatedData)

        _pinStateFlow.value = PinState.Setup(
            failedAttempts = newFailed,
            lockedUntil = lockedUntil,
            isCorrupted = false
        )

        return if (lockedUntil > now) {
            VerifyResult.Locked(lockedUntil)
        } else {
            VerifyResult.WrongPin(remaining.coerceAtLeast(1))
        }
    }

    /**
     * AAD binding the lockout state to this install: a lockout blob copied from
     * another install (or repurposed from another key) fails authentication.
     */
    private fun lockoutAad(): ByteArray {
        val id = keyManager.installIdBytes()
        return try {
            id + byteArrayOf(0x70, 0x69, 0x6E, 0x6C, 0x6F, 0x63, 0x6B) // "pinlock"
        } finally {
            id.wipe()
        }
    }

    /**
     * Decodes [stored] lockout data: hardware-encrypted JSON, AAD-bound to the
     * install ID (tampering or cross-install copying fails authentication here).
     *
     * @throws Exception on corruption/tamper — callers treat it as corrupted state.
     */
    private fun loadData(stored: String): PinSecureData {
        val blob = Base64.Default.decode(stored)
        val aad = lockoutAad()
        val plain = try {
            HardwareKeyStore.decrypt(blob, aad = aad)
        } finally {
            aad.wipe()
        }
        return try {
            json.decodeFromString<PinSecureData>(plain.decodeToString())
        } finally {
            plain.wipe()
        }
    }

    private fun saveData(data: PinSecureData) {
        val plain = json.encodeToString(PinSecureData.serializer(), data).encodeToByteArray()
        val aad = lockoutAad()
        try {
            val blob = HardwareKeyStore.encrypt(plain, aad = aad)
            settingsStorage.putString(PIN_DATA_KEY, Base64.Default.encode(blob))
        } finally {
            aad.wipe()
            plain.wipe()
        }
        gcHint()
    }
}
/**
 * The subset of [SessionManager] that [PinRepositoryImpl] needs to open the
 * database for a fresh-setup/recovery session. Extracted so the repository can
 * be unit-tested without SQLCipher or a real driver.
 */
interface SessionUnlocker {
    suspend fun unlock(dek: ByteArray): UnlockResult
    suspend fun unlockRecreating(dek: ByteArray): Boolean
}
