package com.ultrabytecoder.coinsafebox.ui.keyboard.state

interface KeyboardTarget {
    val text: String
    /**
     * Character count without materializing the full [text] string.
     * Preferred over `text.length` in mask/display paths where only the
     * count is needed (avoids holding a reference to the secret between
     * recompositions).
     */
    val length: Int get() = text.length
    /** Maximum accepted length; insert() is capped at this (single source of truth). */
    val maxLength: Int
    /** Cursor position (0..text.length); backed by snapshot state so the caret tracks it. */
    var cursorIndex: Int
    fun insert(char: Char)
    /**
     * Bulk-inserts [text] at the cursor, honoring [maxLength] and [isSingleLine].
     * Default implementation inserts one character at a time so any [KeyboardTarget]
     * gets paste support for free; adapters that own a buffer should override this
     * to coalesce into a single state update (avoids per-char churn / re-wipes).
     */
    fun insertText(text: String) {
        for (c in text) {
            if (isFull()) break
            insert(c)
        }
    }
    fun delete()
    fun clear()
    fun isFull(): Boolean = length >= maxLength
    /** Moves the cursor to [index], clamped to 0..text.length. */
    fun setCursor(index: Int)
    /** Moves the cursor by [delta], clamped to 0..text.length. */
    fun moveCursor(delta: Int)
    val maxLines: Int
    val isSingleLine: Boolean
}
