package com.ultrabytecoder.coinsafebox.ui.keyboard.layouts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ultrabytecoder.coinsafebox.ui.keyboard.components.KeyboardKey
import com.ultrabytecoder.coinsafebox.ui.keyboard.components.KeyboardRow
import com.ultrabytecoder.coinsafebox.ui.keyboard.model.KeyCode
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.LocalKeyboardController

@Composable
fun QwertyLayout(
    modifier: Modifier = Modifier,
    actionLabel: String = "Done"
) {
    val controller = LocalKeyboardController.current
    val isShifted = controller?.isShifted == true

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        KeyboardRow {
            listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0').forEach { char ->
                KeyboardKey(
                    label = char.toString(),
                    onClick = { controller?.onKey(KeyCode.Digit(char)) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        KeyboardRow {
            listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p').forEach { char ->
                val label = if (isShifted) char.uppercaseChar().toString() else char.toString()
                KeyboardKey(
                    label = label,
                    onClick = { controller?.onKey(KeyCode.Letter(char)) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        KeyboardRow(modifier = Modifier.padding(horizontal = 12.dp)) {
            listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l').forEach { char ->
                val label = if (isShifted) char.uppercaseChar().toString() else char.toString()
                KeyboardKey(
                    label = label,
                    onClick = { controller?.onKey(KeyCode.Letter(char)) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        KeyboardRow {
            KeyboardKey(
                label = "⇧",
                onClick = { controller?.onKey(KeyCode.Shift) },
                isActive = isShifted,
                contentDescription = "Shift",
                modifier = Modifier.weight(1.5f)
            )
            listOf('z', 'x', 'c', 'v', 'b', 'n', 'm').forEach { char ->
                val label = if (isShifted) char.uppercaseChar().toString() else char.toString()
                KeyboardKey(
                    label = label,
                    onClick = { controller?.onKey(KeyCode.Letter(char)) },
                    modifier = Modifier.weight(1f)
                )
            }
            KeyboardKey(
                label = "⌫",
                onClick = { controller?.onKey(KeyCode.Backspace) },
                contentDescription = "Delete",
                modifier = Modifier.weight(1.5f)
            )
        }

        KeyboardRow {
            KeyboardKey(
                label = "?123",
                onClick = { controller?.onKey(KeyCode.SymbolToggle) },
                contentDescription = "Symbols",
                modifier = Modifier.weight(1.5f)
            )
            KeyboardKey(
                label = " ",
                onClick = { controller?.onKey(KeyCode.Space) },
                contentDescription = "Space",
                modifier = Modifier.weight(4f)
            )
            KeyboardKey(
                label = actionLabel,
                onClick = { controller?.onKey(KeyCode.Action) },
                modifier = Modifier.weight(2f)
            )
        }
    }
}
