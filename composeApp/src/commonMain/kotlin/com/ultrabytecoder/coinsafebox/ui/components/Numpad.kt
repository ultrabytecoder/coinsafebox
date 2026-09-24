package com.ultrabytecoder.coinsafebox.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun Numpad(
    onDigitClick: (Int) -> Unit,
    onDeleteClick: () -> Unit,
    isLocked: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        NumpadRow(digits = listOf(1, 2, 3), onDigit = onDigitClick, enabled = !isLocked)
        NumpadRow(digits = listOf(4, 5, 6), onDigit = onDigitClick, enabled = !isLocked)
        NumpadRow(digits = listOf(7, 8, 9), onDigit = onDigitClick, enabled = !isLocked)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.weight(1f))
            NumpadButton(text = "0", onClick = { onDigitClick(0) }, enabled = !isLocked, modifier = Modifier.weight(1f))
            NumpadButton(text = "⌫", onClick = { onDeleteClick() }, enabled = !isLocked, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun NumpadRow(
    digits: List<Int>,
    onDigit: (Int) -> Unit,
    enabled: Boolean
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        digits.forEach { digit ->
            NumpadButton(
                text = digit.toString(),
                onClick = { onDigit(digit) },
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun NumpadButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
        }
    }
}