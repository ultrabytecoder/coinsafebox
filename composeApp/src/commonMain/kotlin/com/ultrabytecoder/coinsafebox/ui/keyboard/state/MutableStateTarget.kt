package com.ultrabytecoder.coinsafebox.ui.keyboard.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MutableStateTarget(
    private val getter: () -> String,
    private val setter: (String) -> Unit,
    override val maxLength: Int = Int.MAX_VALUE,
    override val maxLines: Int = 1,
    override val isSingleLine: Boolean = true
) : KeyboardTarget {
    override val text: String get() = getter()
    override var cursorIndex: Int by mutableStateOf(0)

    override fun insert(char: Char) {
        if (isFull()) return
        if (isSingleLine && (char == '\n' || char == '\r')) return
        val current = text
        val i = cursorIndex.coerceIn(0, current.length)
        setter(current.substring(0, i) + char + current.substring(i))
        cursorIndex = i + 1
    }

    override fun insertText(text: String) {
        if (isFull() || text.isEmpty()) return
        val source = if (isSingleLine) text.filter { it != '\n' && it != '\r' } else text
        if (source.isEmpty()) return
        val room = (maxLength - this.text.length).coerceAtLeast(0)
        val toInsert = source.take(room)
        if (toInsert.isEmpty()) return
        val i = cursorIndex.coerceIn(0, this.text.length)
        setter(this.text.substring(0, i) + toInsert + this.text.substring(i))
        cursorIndex = i + toInsert.length
    }

    override fun delete() {
        val current = text
        val i = cursorIndex.coerceIn(0, current.length)
        if (i > 0) {
            setter(current.substring(0, i - 1) + current.substring(i))
            cursorIndex = i - 1
        }
    }

    override fun clear() {
        setter("")
        cursorIndex = 0
    }

    override fun setCursor(index: Int) {
        cursorIndex = index.coerceIn(0, text.length)
    }

    override fun moveCursor(delta: Int) {
        setCursor(cursorIndex + delta)
    }
}
