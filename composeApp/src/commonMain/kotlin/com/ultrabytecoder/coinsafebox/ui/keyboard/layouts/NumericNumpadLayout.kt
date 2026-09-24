package com.ultrabytecoder.coinsafebox.ui.keyboard.layouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ultrabytecoder.coinsafebox.ui.keyboard.components.KeyboardKey
import com.ultrabytecoder.coinsafebox.ui.keyboard.components.KeyboardRow
import com.ultrabytecoder.coinsafebox.ui.keyboard.model.KeyCode
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.LocalKeyboardController

/**
 * Numeric numpad layout for PIN entry and amount input.
 *
 * Dual input path: when [onDigitClick] is provided (as in PIN screens),
 * digits are routed directly to the ViewModel, bypassing the
 * [KeyboardController]. The controller's `setInputEnabled(false)` has no
 * effect on this path — the ViewModel must enforce its own input lock.
 * When [onDigitClick] is null, digits flow through the controller's
 * `onKey` dispatch (the standard path for Qwerty/Symbol layouts).
 */
@Composable
fun NumericNumpadLayout(
    modifier: Modifier = Modifier,
    isLocked: Boolean = false,
    showDecimal: Boolean = false,
    onDigitClick: ((Int) -> Unit)? = null,
    onDecimalClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null
) {
    val controller = LocalKeyboardController.current

    val handleDigit: (Int) -> Unit = onDigitClick ?: { digit ->
        controller?.onKey(KeyCode.Digit(digit.digitToChar()))
    }
    val handleDecimal: () -> Unit = onDecimalClick ?: {
        controller?.insertChar('.')
    }
    val handleDelete: () -> Unit = onDeleteClick ?: {
        controller?.onKey(KeyCode.Backspace)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        KeyboardRow {
            listOf(1, 2, 3).forEach { digit ->
                KeyboardKey(
                    label = digit.toString(),
                    onClick = { handleDigit(digit) },
                    enabled = !isLocked,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        KeyboardRow {
            listOf(4, 5, 6).forEach { digit ->
                KeyboardKey(
                    label = digit.toString(),
                    onClick = { handleDigit(digit) },
                    enabled = !isLocked,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        KeyboardRow {
            listOf(7, 8, 9).forEach { digit ->
                KeyboardKey(
                    label = digit.toString(),
                    onClick = { handleDigit(digit) },
                    enabled = !isLocked,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        KeyboardRow {
            if (showDecimal) {
                KeyboardKey(
                    label = ".",
                    onClick = { handleDecimal() },
                    enabled = !isLocked,
                    contentDescription = "Decimal point",
                    modifier = Modifier.weight(1f)
                )
            } else {
                Box(modifier = Modifier.weight(1f))
            }
            KeyboardKey(
                label = "0",
                onClick = { handleDigit(0) },
                enabled = !isLocked,
                modifier = Modifier.weight(1f)
            )
            KeyboardKey(
                label = "⌫",
                onClick = { handleDelete() },
                enabled = !isLocked,
                contentDescription = "Delete",
                modifier = Modifier.weight(1f)
            )
        }
    }
}
