package com.ultrabytecoder.coinsafebox.ui.keyboard.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ultrabytecoder.coinsafebox.ui.clipboard.clearPlatformClipboard
import com.ultrabytecoder.coinsafebox.ui.clipboard.readPlatformClipboardText
import com.ultrabytecoder.coinsafebox.ui.keyboard.model.KeyboardLayoutType
import com.ultrabytecoder.coinsafebox.ui.keyboard.onPasteShortcut
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.KeyboardTarget
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.LocalKeyboardController
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import kotlinx.coroutines.delay

@Composable
fun SecureOutlinedTextField(
    target: KeyboardTarget,
    label: @Composable (() -> Unit)?,
    modifier: Modifier = Modifier,
    layoutType: KeyboardLayoutType = KeyboardLayoutType.Qwerty,
    supportsDecimal: Boolean = false,
    masked: Boolean = true,
    revealable: Boolean = false,
    pasteEnabled: Boolean = false,
    pasteValidator: ((String) -> Boolean)? = null,
    clearClipboardAfterPaste: Boolean = false,
    isError: Boolean = false,
    supportingText: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    minLines: Int = 1,
    maxLines: Int = 1,
    singleLine: Boolean = false,
    shape: RoundedCornerShape = RoundedCornerShape(4.dp),
    enabled: Boolean = true
) {
    val controller = LocalKeyboardController.current
    val focusManager = LocalFocusManager.current
    val isActive = controller?.isVisible == true && controller.activeTarget == target

    // Optional validated clipboard paste (for non-secret fields such as recipient
    // addresses). The field content stays focusable(false) / IME-blocked; paste is an
    // explicit action (button tap, or Ctrl/Cmd+V on desktop) that reads the NATIVE
    // clipboard once via the platform bridge, validates it, and inserts via the
    // target. Secret fields never set pasteEnabled.
    var pasteError by remember { mutableStateOf(false) }
    LaunchedEffect(pasteError) {
        if (pasteError) {
            delay(3_000)
            pasteError = false
        }
    }
    val onPasteClick: () -> Unit = {
        val cleaned = readPlatformClipboardText().orEmpty().trim()
        when {
            cleaned.isEmpty() -> Unit
            pasteValidator?.invoke(cleaned) == false -> {
                pasteError = true
            }
            else -> {
                target.insertText(cleaned)
                if (clearClipboardAfterPaste) {
                    clearPlatformClipboard()
                }
            }
        }
    }

    // Desktop Ctrl/Cmd+V shortcut: give the wrapper a (non-editable) focus while the
    // field is active so it can receive the key event. The wrapper is not a text
    // field, so no system IME InputConnection is created — the security model holds.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isActive, pasteEnabled) {
        if (isActive && pasteEnabled) focusRequester.requestFocus()
    }
    val pasteShortcutModifier: Modifier = if (pasteEnabled) {
        modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPasteShortcut(enabled = isActive, onPaste = onPasteClick)
    } else {
        modifier
    }

    // H9: wipe this field's buffer when the app is backgrounded (ON_STOP). Bound to
    // the FIELD (not the keyboard), so it fires even when the on-screen keyboard has
    // been dismissed and the controller no longer holds an active target. Per-screen
    // dispose wipes cover navigation; this covers app-level backgrounding. Lifecycle
    // callbacks fire on the main thread, matching the target's thread confinement.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, target) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) target.clear()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val borderColor = when {
        isError || (pasteEnabled && pasteError) -> MaterialTheme.colorScheme.error
        isActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    val borderWidth = if (isActive) 2.dp else 1.dp

    // Sensitive fields are masked by default. The mask is derived from the
    // character count only — every character (including whitespace) is rendered
    // as a bullet, so no secret character is ever read or rendered. This also
    // prevents word-boundary / word-length leakage for multi-line mnemonics,
    // where preserving spaces would reveal the BIP-39 word structure.
    // H13: a sensitive field can optionally reveal its content briefly (to let
    // the user verify an unrecoverable secret); auto-hides after 5s.
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(revealed) {
        if (revealed) {
            delay(5_000)
            revealed = false
        }
    }
    val showMask = masked && !revealed
    val displayText = if (showMask) {
        // Mask path: use length only — no reference to the secret string is
        // held between recompositions. The mask is always dots, so only the
        // count matters (not the content).
        remember(target.length, showMask) { "•".repeat(target.length) }
    } else {
        // Unmasked path: content matters, so key on the full text.
        remember(target.text, showMask) { target.text }
    }

    // Per-Text layouts, used to map a tap on the (split) content to a character
    // index for tap-to-position cursor placement (single-line fields only).
    var beforeLayout: TextLayoutResult? by remember { mutableStateOf<TextLayoutResult?>(null) }
    var afterLayout: TextLayoutResult? by remember { mutableStateOf<TextLayoutResult?>(null) }
    val enabledState = rememberUpdatedState(enabled)
    // singleLine forces a single line (caps maxLines); minLines grows the min
    // height so multi-line fields (mnemonic) reserve room before scrolling.
    val effectiveMaxLines = if (singleLine) 1 else maxLines
    // Cap the box at its max-line height so multi-line fields scroll instead of
    // growing without bound in an unconstrained column.
    val boxMaxHeight = 56.dp + 28.dp * (effectiveMaxLines - 1)

    Column(modifier = pasteShortcutModifier) {
        label?.let {
            Box(modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)) { it() }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp + 28.dp * (minLines - 1), max = boxMaxHeight)
                // Deliberately out of the IME focus chain: input only flows through the
                // on-screen keyboard, so no system input method can observe the secret.
                .focusable(false)
                .semantics { password() }
                .clip(shape)
                .border(BorderStroke(borderWidth, borderColor), shape)
                .background(MaterialTheme.colorScheme.surface, shape)
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    // Clear any IME focus held by a non-secure field so the
                    // system keyboard cannot capture input while the secure
                    // field is active.
                    focusManager.clearFocus()
                    controller?.let { c ->
                        // Tap toggles: bring up this field's keyboard, or dismiss if it's up.
                        if (c.target == target && c.isVisible) c.hide() else c.show(target, layoutType, supportsDecimal)
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = if (effectiveMaxLines > 1) Alignment.TopStart else Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (leadingIcon != null) {
                    leadingIcon()
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(if (effectiveMaxLines > 1) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (target.text.isEmpty() && placeholder != null) {
                        Box(modifier = Modifier.alpha(0.5f)) {
                            placeholder()
                        }
                    } else if (effectiveMaxLines == 1) {
                        val text = displayText
                        val cursor = target.cursorIndex.coerceIn(0, text.length)
                        val before = text.substring(0, cursor)
                        val after = text.substring(cursor)
                        val contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = before,
                                style = MaterialTheme.typography.bodyLarge,
                                color = contentColor,
                                maxLines = 1,
                                onTextLayout = { beforeLayout = it },
                                modifier = if (before.isNotEmpty()) Modifier.pointerInput(Unit) {
                                    detectTapGestures { offset ->
                                        if (!enabledState.value) return@detectTapGestures
                                        controller?.show(target, layoutType, supportsDecimal)
                                        val r = beforeLayout
                                        target.setCursor(if (r != null) r.getOffsetForPosition(offset) else before.length)
                                    }
                                } else Modifier
                            )
                            if (isActive) {
                                KeyboardCursor()
                            }
                            Text(
                                text = after,
                                style = MaterialTheme.typography.bodyLarge,
                                color = contentColor,
                                maxLines = 1,
                                onTextLayout = { afterLayout = it },
                                modifier = if (after.isNotEmpty()) Modifier.pointerInput(Unit) {
                                    detectTapGestures { offset ->
                                        if (!enabledState.value) return@detectTapGestures
                                        controller?.show(target, layoutType, supportsDecimal)
                                        val r = afterLayout
                                        target.setCursor(target.cursorIndex + (if (r != null) r.getOffsetForPosition(offset) else 0))
                                    }
                                } else Modifier
                            )
                        }
                    } else {
                        var multiLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                        val density = LocalDensity.current
                        val contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = displayText,
                                style = MaterialTheme.typography.bodyLarge,
                                color = contentColor,
                                maxLines = effectiveMaxLines,
                                onTextLayout = { multiLayout = it },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (isActive) {
                                val r = multiLayout
                                val idx = target.cursorIndex.coerceIn(0, displayText.length)
                                if (r != null) {
                                    val rect = runCatching { r.getCursorRect(idx) }.getOrNull()
                                    if (rect != null) {
                                        val cursorAlpha by rememberInfiniteTransition().animateFloat(
                                            initialValue = 1f,
                                            targetValue = 0f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(500),
                                                repeatMode = RepeatMode.Reverse
                                            )
                                        )
                                        val cursorX = with(density) { rect.left.toDp() }
                                        val cursorY = with(density) { rect.top.toDp() }
                                        val cursorHeight = with(density) { rect.height.toDp() }
                                        Box(
                                            modifier = Modifier
                                                .offset(x = cursorX, y = cursorY)
                                                .width(2.dp)
                                                .height(cursorHeight)
                                                .alpha(cursorAlpha)
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (trailingIcon != null) {
                    trailingIcon()
                }
                if (revealable) {
                    IconButton(
                        onClick = { revealed = !revealed },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (revealed) FeatherIcons.EyeOff else FeatherIcons.Eye,
                            contentDescription = if (revealed) "Hide" else "Show",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (pasteEnabled) {
                    IconButton(
                        onClick = onPasteClick,
                        enabled = enabled,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Clipboard,
                            contentDescription = "Paste",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        val showPasteHint = pasteEnabled && pasteError
        if (supportingText != null || isError || showPasteHint) {
            Spacer(modifier = Modifier.height(4.dp))
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                supportingText?.invoke()
                if (showPasteHint) {
                    Text(
                        text = "Invalid input - paste rejected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
