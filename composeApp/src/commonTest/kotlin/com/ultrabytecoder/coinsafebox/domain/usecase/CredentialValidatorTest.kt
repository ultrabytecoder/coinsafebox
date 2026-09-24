package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.domain.repository.SecurityMethod
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialValidatorTest {

    @Test
    fun pin_sixDigits_valid() {
        assertTrue(CredentialValidator.validate(SecurityMethod.PIN, "123456".toCharArray()).ok)
    }

    @Test
    fun pin_eightDigits_valid() {
        assertTrue(CredentialValidator.validate(SecurityMethod.PIN, "12345678".toCharArray()).ok)
    }

    @Test
    fun pin_fiveDigits_invalid() {
        assertFalse(CredentialValidator.validate(SecurityMethod.PIN, "12345".toCharArray()).ok)
    }

    @Test
    fun pin_sevenDigits_invalid() {
        assertFalse(CredentialValidator.validate(SecurityMethod.PIN, "1234567".toCharArray()).ok)
    }

    @Test
    fun pin_nineDigits_invalid() {
        assertFalse(CredentialValidator.validate(SecurityMethod.PIN, "123456789".toCharArray()).ok)
    }

    @Test
    fun pin_nonDigits_invalid() {
        assertFalse(CredentialValidator.validate(SecurityMethod.PIN, "12ab56".toCharArray()).ok)
    }

    @Test
    fun pin_empty_invalid() {
        assertFalse(CredentialValidator.validate(SecurityMethod.PIN, "".toCharArray()).ok)
    }
}
