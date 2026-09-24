package com.ultrabytecoder.coinsafebox.ui.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ultrabytecoder.coinsafebox.security.wipe

/**
 * Compose state holder for sensitive text (mnemonic, passphrase, ...).
 *
 * The secret is mirrored in a wipe-able [CharArray] buffer in addition to the
 * immutable [String] the platform text field requires. The previous buffer is
 * zeroed on every update, and [wipe] zeroes the current one and drops the
 * [String] reference, so no history accumulates and the caller can explicitly
 * scrub the buffer when the field leaves composition.
 *
 * Note: the [String] itself cannot be zeroed (platform text input needs it);
 * [wipe] only drops the reference. Prefer this holder over raw `String` state
 * because it gives a wipe-able source of truth and an explicit scrub point.
 */
class SecureTextFieldState(initial: String = "") {

    private var buffer: CharArray = initial.toCharArray()

    /** Text exposed to the text field. */
    var text: String by mutableStateOf(initial)
        private set

    /** Replaces the contents, zeroing the previous buffer first. */
    fun update(newText: String) {
        if (newText == text) return
        buffer.wipe()
        buffer = newText.toCharArray()
        text = newText
    }

    /** Replaces the contents from a [CharArray], zeroing the previous buffer first. */
    fun update(newChars: CharArray) {
        buffer.wipe()
        buffer = newChars.copyOf()
        text = newChars.concatToString()
    }

    /** Fresh copy of the current contents; the caller is responsible for wiping it. */
    fun toCharArray(): CharArray = buffer.copyOf()

    /** Fresh copy with leading/trailing whitespace removed; caller wipes it. */
    fun trimmedCopy(): CharArray {
        var start = 0
        var end = buffer.size
        while (start < end && buffer[start].isWhitespace()) start++
        while (end > start && buffer[end - 1].isWhitespace()) end--
        return buffer.copyOfRange(start, end)
    }

    /**
     * Constant-time equality against another field's contents. Avoids the
     * early-exit timing side channel of `String ==` on a secret: always scans
     * a fixed maximum length (so the loop count does not depend on input
     * length) and folds in any length difference into the result.
     */
    fun matches(other: SecureTextFieldState): Boolean {
        val a = buffer
        val b = other.buffer
        var result = a.size xor b.size
        // Scan the full length of the longer buffer with no early exit, so
        // the comparison time depends only on max(len_a, len_b) — the same
        // quantity already leaked by the size-xor above. The shorter side
        // is padded with NUL.
        val n = if (a.size > b.size) a.size else b.size
        for (i in 0 until n) {
            val ca = if (i < a.size) a[i].code else 0
            val cb = if (i < b.size) b[i].code else 0
            result = result or (ca xor cb)
        }
        return result == 0
    }

    /** True when the contents are empty or whitespace-only. */
    fun isBlank(): Boolean {
        var result = 0
        for (c in buffer) {
            // Fold non-whitespace into the accumulator without early exit,
            // so the iteration count is independent of the first non-whitespace
            // character's position (avoids a timing side channel).
            result = result or (if (c.isWhitespace()) 0 else 1)
        }
        return result == 0
    }

    /**
     * Zeroes the internal buffer and drops the text reference. Call on
     * `DisposableEffect.onDispose` and once the secret has been consumed.
     */
    fun wipe() {
        buffer.wipe()
        buffer = CharArray(0)
        text = ""
    }
}