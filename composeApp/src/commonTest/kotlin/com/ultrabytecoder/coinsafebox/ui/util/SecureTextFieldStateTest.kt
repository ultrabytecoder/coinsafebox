package com.ultrabytecoder.coinsafebox.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecureTextFieldStateTest {

    @Test
    fun updateReplacesContents() {
        val state = SecureTextFieldState("first")
        state.update("second")
        assertEquals("second", state.text)
        assertTrue(state.toCharArray().contentEquals("second".toCharArray()))
    }

    @Test
    fun wipeDropsTextAndBuffer() {
        val state = SecureTextFieldState("sensitive")
        state.wipe()
        assertEquals("", state.text)
        assertTrue(state.toCharArray().isEmpty())
    }

    @Test
    fun trimmedCopyStripsLeadingAndTrailingWhitespace() {
        val state = SecureTextFieldState("  word1 word2  ")
        assertTrue(state.trimmedCopy().contentEquals("word1 word2".toCharArray()))
    }

    @Test
    fun stateIsReusableAfterWipe() {
        val state = SecureTextFieldState("abc")
        state.wipe()
        state.update("new")
        assertEquals("new", state.text)
        assertTrue(state.toCharArray().contentEquals("new".toCharArray()))
    }

    @Test
    fun noOpUpdateKeepsText() {
        val state = SecureTextFieldState("abc")
        state.update("abc")
        assertEquals("abc", state.text)
    }
}