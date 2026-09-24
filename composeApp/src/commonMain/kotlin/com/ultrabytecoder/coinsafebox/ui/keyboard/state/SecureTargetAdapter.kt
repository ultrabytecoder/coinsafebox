package com.ultrabytecoder.coinsafebox.ui.keyboard.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ultrabytecoder.coinsafebox.security.wipe
import com.ultrabytecoder.coinsafebox.ui.util.SecureTextFieldState

class SecureTargetAdapter(
    private val state: SecureTextFieldState,
    override val maxLength: Int = Int.MAX_VALUE,
    override val maxLines: Int = 1,
    override val isSingleLine: Boolean = true,
    private val onValueChanged: (String) -> Unit
) : KeyboardTarget {
    override val text: String get() = state.text
    override var cursorIndex: Int by mutableStateOf(0)

    override fun insert(char: Char) {
        if (isFull()) return
        if (isSingleLine && (char == '\n' || char == '\r')) return
        val current = state.toCharArray()
        val i = cursorIndex.coerceIn(0, current.size)
        val newChars = current.copyOfRange(0, i) + char + current.copyOfRange(i, current.size)
        current.wipe()
        state.update(newChars)
        newChars.wipe()
        cursorIndex = i + 1
        onValueChanged(state.text)
    }

    override fun insertText(text: String) {
        if (isFull() || text.isEmpty()) return
        val source = if (isSingleLine) text.filter { it != '\n' && it != '\r' } else text
        if (source.isEmpty()) return
        val room = (maxLength - length).coerceAtLeast(0)
        val toInsert = source.take(room)
        if (toInsert.isEmpty()) return
        val current = state.toCharArray()
        val i = cursorIndex.coerceIn(0, current.size)
        val newChars = current.copyOfRange(0, i) + toInsert.toCharArray() + current.copyOfRange(i, current.size)
        current.wipe()
        state.update(newChars)
        newChars.wipe()
        cursorIndex = i + toInsert.length
        onValueChanged(state.text)
    }

    override fun delete() {
        val current = state.toCharArray()
        val i = cursorIndex.coerceIn(0, current.size)
        if (i > 0) {
            val newChars = current.copyOfRange(0, i - 1) + current.copyOfRange(i, current.size)
            current.wipe()
            state.update(newChars)
            newChars.wipe()
            cursorIndex = i - 1
            onValueChanged(state.text)
        }
    }

    override fun clear() {
        state.wipe()
        cursorIndex = 0
        onValueChanged("")
    }

    override fun setCursor(index: Int) {
        cursorIndex = index.coerceIn(0, state.text.length)
    }

    override fun moveCursor(delta: Int) {
        setCursor(cursorIndex + delta)
    }

    /**
     * Replaces the partial word currently being typed (text after the last space)
     * with [word] followed by a space, then parks the cursor at the end.
     */
    fun replaceTrailingWord(word: String) {
        val current = state.toCharArray()
        val lastSpace = current.indexOfLast { it == ' ' }
        val start = if (lastSpace < 0) 0 else lastSpace + 1
        val newChars = current.copyOfRange(0, start) + word.toCharArray() + ' '
        current.wipe()
        state.update(newChars)
        newChars.wipe()
        cursorIndex = state.text.length
        onValueChanged(state.text)
    }
}
