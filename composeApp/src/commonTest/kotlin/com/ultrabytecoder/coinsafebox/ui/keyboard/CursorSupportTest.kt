package com.ultrabytecoder.coinsafebox.ui.keyboard.state

import com.ultrabytecoder.coinsafebox.ui.util.SecureTextFieldState
import kotlin.test.Test
import kotlin.test.assertEquals

class CursorSupportTest {

    private fun target(start: String = ""): MutableStateTarget {
        var backing = start
        return MutableStateTarget(
            getter = { backing },
            setter = { backing = it }
        )
    }

    @Test
    fun insertAppendsAndCursorFollowsEnd() {
        val t = target()
        t.insert('a'); t.insert('b'); t.insert('c')
        assertEquals("abc", t.text)
        assertEquals(3, t.cursorIndex)
    }

    @Test
    fun insertAtCursor() {
        val t = target(start = "ac")
        t.setCursor(1)
        t.insert('b')
        assertEquals("abc", t.text)
        assertEquals(2, t.cursorIndex)
    }

    @Test
    fun deleteRemovesCharBeforeCursor() {
        val t = target(start = "abc")
        t.setCursor(2)
        t.delete()
        assertEquals("ac", t.text)
        assertEquals(1, t.cursorIndex)
    }

    @Test
    fun deleteAtStartIsNoOp() {
        val t = target(start = "abc")
        t.setCursor(0)
        t.delete()
        assertEquals("abc", t.text)
        assertEquals(0, t.cursorIndex)
    }

    @Test
    fun setCursorClampsToRange() {
        val t = target(start = "abc")
        t.setCursor(99)
        assertEquals(3, t.cursorIndex)
        t.setCursor(-5)
        assertEquals(0, t.cursorIndex)
    }

    @Test
    fun moveCursorClampsToRange() {
        val t = target(start = "abc")
        t.setCursor(3)
        t.moveCursor(5)
        assertEquals(3, t.cursorIndex)
        t.moveCursor(-10)
        assertEquals(0, t.cursorIndex)
    }

    @Test
    fun secureAdapterInsertDeleteAtCursor() {
        val state = SecureTextFieldState()
        val t = SecureTargetAdapter(state = state, onValueChanged = {})
        t.insert('a'); t.insert('b'); t.insert('c')
        assertEquals("abc", t.text)
        t.setCursor(1)
        t.insert('x')
        assertEquals("axbc", t.text)
        assertEquals(2, t.cursorIndex)
        t.delete()
        assertEquals("abc", t.text)
        assertEquals(1, t.cursorIndex)
    }

    @Test
    fun secureAdapterRespectsMaxLength() {
        val state = SecureTextFieldState()
        val t = SecureTargetAdapter(state = state, maxLength = 2, onValueChanged = {})
        t.insert('a'); t.insert('b'); t.insert('c')
        assertEquals("ab", t.text)
    }

    @Test
    fun secureAdapterClearResetsCursor() {
        val state = SecureTextFieldState()
        val t = SecureTargetAdapter(state = state, onValueChanged = {})
        t.insert('a'); t.insert('b')
        t.setCursor(1)
        t.clear()
        assertEquals("", t.text)
        assertEquals(0, t.cursorIndex)
    }
}
