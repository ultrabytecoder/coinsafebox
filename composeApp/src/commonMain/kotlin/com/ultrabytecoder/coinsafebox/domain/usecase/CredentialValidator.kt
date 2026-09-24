package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.domain.repository.PinConfig
import com.ultrabytecoder.coinsafebox.domain.repository.SecurityMethod

object CredentialValidator {
    data class Result(val ok: Boolean, val error: String?)

    fun validate(method: SecurityMethod, credential: CharArray): Result = when (method) {
        SecurityMethod.PIN -> {
            if (credential.size !in PinConfig.PIN_LENGTH_OPTIONS)
                Result(false, "PIN must be ${PinConfig.PIN_LENGTH_OPTIONS.joinToString(" or ")} digits")
            else if (!credential.all { it.isDigit() }) Result(false, "PIN must be digits only")
            else Result(true, null)
        }
        SecurityMethod.PASSWORD -> {
            if (credential.size < PinConfig.PASSWORD_MIN_LENGTH)
                Result(false, "Password must be at least ${PinConfig.PASSWORD_MIN_LENGTH} characters")
            else {
                val classes = listOf(
                    credential.any { it.isLowerCase() },
                    credential.any { it.isUpperCase() },
                    credential.any { it.isDigit() },
                    credential.any { !it.isLetterOrDigit() }
                ).count { it }
                if (classes < PinConfig.PASSWORD_MIN_CLASSES)
                    Result(false, "Use at least ${PinConfig.PASSWORD_MIN_CLASSES} character classes (upper, lower, digit, symbol)")
                else Result(true, null)
            }
        }
    }
}
