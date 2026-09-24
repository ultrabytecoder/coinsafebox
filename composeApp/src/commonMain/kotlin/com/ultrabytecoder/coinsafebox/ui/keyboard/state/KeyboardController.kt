package com.ultrabytecoder.coinsafebox.ui.keyboard.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.ultrabytecoder.coinsafebox.ui.keyboard.model.KeyCode
import com.ultrabytecoder.coinsafebox.ui.keyboard.model.KeyboardLayoutType

/**
 * Central state + event dispatcher for the in-app keyboard.
 *
 * Each UI-visible field is a separate [mutableStateOf] so that Compose
 * snapshot invalidation is scoped: toggling Shift only re-composes the
 * letter-key labels, not the layout switch or visibility — avoiding a full
 * 30-key keyboard re-composition on every keystroke.
 *
 * Thread confinement: every mutator (`show`, `hide`, `onKey`, `insertChar`,
 * `deleteChar`) writes Compose snapshot state and MUST run on the Compose/UI
 * (main) thread. All current call sites are Compose compositions or event
 * handlers (key clicks, tap-to-position), which already run on the UI thread —
 * keep new call sites there as well.
 */
class KeyboardController(
    var onAction: (() -> Unit)? = null
) {
    private var _isVisible: Boolean by mutableStateOf(false)
    private var _layoutType: KeyboardLayoutType by mutableStateOf(KeyboardLayoutType.Qwerty)
    private var _isShifted: Boolean by mutableStateOf(false)
    private var _supportsDecimal: Boolean by mutableStateOf(false)
    var activeTarget: KeyboardTarget? by mutableStateOf(null)
    // When false (e.g. a locked or in-flight screen) the keyboard is inert: no
    // characters are inserted/deleted. Set per-screen via setInputEnabled.
    private var inputEnabled = true

    val isVisible: Boolean get() = _isVisible
    val target: KeyboardTarget? get() = activeTarget
    val layoutType: KeyboardLayoutType get() = _layoutType
    val isShifted: Boolean get() = _isShifted
    // Derived from layoutType (single source of truth) so it can't drift (M1/M22).
    val isSymbolsActive: Boolean get() = _layoutType == KeyboardLayoutType.Symbols
    val supportsDecimal: Boolean get() = _supportsDecimal

    fun show(target: KeyboardTarget, layout: KeyboardLayoutType = KeyboardLayoutType.Qwerty, supportsDecimal: Boolean = false) {
        activeTarget = target
        _isVisible = true
        _layoutType = layout
        _isShifted = false
        _supportsDecimal = supportsDecimal
    }

    fun showNumpad() {
        _isVisible = true
        _layoutType = KeyboardLayoutType.Numeric
        _isShifted = false
    }

    fun hide() {
        _isVisible = false
        activeTarget = null
    }

    fun onKey(key: KeyCode) {
        // Ignore key events outside an active input session — prevents shift/symbol
        // state from drifting when no target is showing. Also gate on inputEnabled
        // so a locked screen cannot toggle layout or shift state.
        if (!isVisible || activeTarget == null) return
        if (!inputEnabled && key !is KeyCode.Action) return
        when (key) {
            is KeyCode.Letter -> {
                val char = if (_isShifted) key.char.uppercaseChar() else key.char.lowercaseChar()
                insertChar(char)
                if (_isShifted) _isShifted = false
            }
            is KeyCode.Digit -> insertChar(key.digit)
            is KeyCode.Symbol -> insertChar(key.char)
            is KeyCode.Space -> insertChar(' ')
            is KeyCode.Backspace -> deleteChar()
            is KeyCode.Shift -> _isShifted = !_isShifted
            is KeyCode.SymbolToggle -> {
                _layoutType = if (_layoutType == KeyboardLayoutType.Symbols) {
                    KeyboardLayoutType.Qwerty
                } else {
                    KeyboardLayoutType.Symbols
                }
                _isShifted = false
            }
            is KeyCode.Action -> {
                try {
                    onAction?.invoke()
                } finally {
                    hide()
                }
            }
        }
    }

    fun setInputEnabled(enabled: Boolean) {
        inputEnabled = enabled
    }

    fun insertChar(c: Char) {
        if (!inputEnabled) return
        activeTarget?.insert(c)
    }

    fun deleteChar() {
        if (!inputEnabled) return
        activeTarget?.delete()
    }
}

val LocalKeyboardController = compositionLocalOf<KeyboardController?> { null }

@Composable
fun rememberKeyboardController(onAction: (() -> Unit)? = null): KeyboardController {
    val controller = remember { KeyboardController() }
    // Keep onAction in sync across recomposition (e.g. a state-dependent submit
    // action whose captured state changes over time).
    val onActionRef = rememberUpdatedState(onAction)
    LaunchedEffect(controller) {
        controller.onAction = { onActionRef.value?.invoke() }
    }
    // Hide the keyboard when this controller's screen leaves composition so the
    // active target (and any secure field it drives) cannot outlive the screen.
    DisposableEffect(controller) {
        onDispose { controller.hide() }
    }
    return controller
}
