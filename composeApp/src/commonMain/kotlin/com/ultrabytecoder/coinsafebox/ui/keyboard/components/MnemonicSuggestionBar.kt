package com.ultrabytecoder.coinsafebox.ui.keyboard.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ultrabytecoder.coinsafebox.security.MnemonicSuggestions

@Composable
fun MnemonicSuggestionBar(
    text: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxSuggestions: Int = 8
) {
    val currentWord = remember(text) {
        text.substringAfterLast(' ', missingDelimiterValue = text)
    }
    val suggestions = remember(currentWord, maxSuggestions) {
        MnemonicSuggestions.forPrefix(currentWord, maxSuggestions)
    }

    if (suggestions.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        suggestions.forEach { word ->
            Surface(
                onClick = { if (enabled) onPick(word) },
                enabled = enabled,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Text(
                    text = word,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}
