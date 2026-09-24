package com.ultrabytecoder.coinsafebox.ui.keyboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ultrabytecoder.coinsafebox.ui.keyboard.layouts.NumericNumpadLayout
import com.ultrabytecoder.coinsafebox.ui.keyboard.layouts.QwertyLayout
import com.ultrabytecoder.coinsafebox.ui.keyboard.layouts.SymbolLayout
import com.ultrabytecoder.coinsafebox.ui.keyboard.model.KeyboardLayoutType
import com.ultrabytecoder.coinsafebox.ui.keyboard.platform.blockSystemKeyboard
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.LocalKeyboardController

@Composable
fun AppKeyboard(
    modifier: Modifier = Modifier,
    actionLabel: String = "Done"
) {
    val controller = LocalKeyboardController.current ?: return

    // Scoped system-IME suppression: dismiss any showing system keyboard when the
    // on-screen keyboard appears. This only composes where the secure keyboard is
    // present, so non-secure fields elsewhere keep normal system-IME behavior. The
    // secure fields are focusable(false) and the keyboard is touch-driven, so the
    // system IME cannot observe secure input regardless — this is defense-in-depth.
    DisposableEffect(controller.isVisible) {
        if (controller.isVisible) blockSystemKeyboard()
        onDispose { }
    }

    AnimatedVisibility(
        visible = controller.isVisible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp)
        ) {
            when (controller.layoutType) {
                KeyboardLayoutType.Numeric -> {
                    NumericNumpadLayout(
                        modifier = Modifier.fillMaxWidth(),
                        showDecimal = controller.supportsDecimal
                    )
                }
                KeyboardLayoutType.Qwerty -> {
                    QwertyLayout(
                        actionLabel = actionLabel,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                KeyboardLayoutType.Symbols -> {
                    SymbolLayout(
                        actionLabel = actionLabel,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
