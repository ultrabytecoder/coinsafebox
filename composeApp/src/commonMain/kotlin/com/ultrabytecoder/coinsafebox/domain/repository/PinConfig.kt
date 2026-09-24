package com.ultrabytecoder.coinsafebox.domain.repository

import kotlin.time.Duration.Companion.minutes

expect object PinConfig {
    val PIN_LENGTH: Int
    val PIN_LENGTH_OPTIONS: List<Int>
    val PASSWORD_MIN_LENGTH: Int
    val PASSWORD_MIN_CLASSES: Int
    val MAX_ATTEMPTS: Int
    val INITIAL_LOCKOUT: kotlin.time.Duration
    val MAX_LOCKOUT: kotlin.time.Duration
    /** Argon2id time cost (passes) / PBKDF2 iterations for short PINs. */
    val KDF_TIME_COST_PIN: Int
    /** Argon2id time cost (passes) / PBKDF2 iterations for long passwords. */
    val KDF_TIME_COST_PASSWORD: Int
    /** Argon2id memory cost in KiB. */
    val KDF_MEMORY_KIB: Int
    /** Argon2id parallelism / lanes. */
    val KDF_PARALLELISM: Int
    val SALT_SIZE: Int
    /**
     * PBKDF2-HMAC-SHA256 iteration count used when the platform's Argon2id
     * backend is unavailable (iOS fallback / runtime degradation). 600,000
     * per OWASP 2023 password-storage guidance.
     */
    val PBKDF2_FALLBACK_ITERATIONS: Int
}

/**
 * Result of a PIN verification attempt.
 */
sealed class VerifyResult {
    data object Success : VerifyResult()
    data class WrongPin(val remainingAttempts: Int) : VerifyResult()
    data class Locked(val lockedUntil: Long) : VerifyResult()
    data object Corrupted : VerifyResult()
    /**
     * The PIN was correct (the DEK unwrapped successfully) but the session was
     * locked while the driver was opening (e.g. an idle timeout fired at the same
     * moment). The user should simply try again — this is NOT a wrong PIN and NOT
     * a corruption.
     */
    data object SessionLocked : VerifyResult()
}
