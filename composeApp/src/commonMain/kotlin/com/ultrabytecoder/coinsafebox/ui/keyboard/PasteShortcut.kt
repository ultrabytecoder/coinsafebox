package com.ultrabytecoder.coinsafebox.ui.keyboard

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Intercepts Ctrl+V (Windows/Linux) / Cmd+V (macOS) as a paste trigger for
 * non-secret secure fields on desktop.
 *
 * Must be composed on a node that can hold focus (the field's container is made
 * focusable and given focus while active). Every non-paste event returns false so
 * normal key handling is left untouched. The field content itself stays
 * focusable(false) / out of the IME chain — only this wrapper node takes focus.
 */
fun Modifier.onPasteShortcut(enabled: Boolean, onPaste: () -> Unit): Modifier =
    if (!enabled) this else this.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown &&
            (event.isCtrlPressed || event.isMetaPressed) &&
            event.key == Key.V
        ) {
            onPaste()
            true
        } else {
            false
        }
    }
