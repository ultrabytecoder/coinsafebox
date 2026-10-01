package com.ultrabytecoder.coinsafebox.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Lock

/**
 * Small "Read-only" affordance shown on wallet/account surfaces when the wallet has
 * no master key at rest (balances + history remain visible; sending requires the
 * recovery phrase). Driven by [com.ultrabytecoder.coinsafebox.domain.model.WalletInfo.isReadOnly].
 */
@Composable
fun ReadOnlyBadge(modifier: Modifier = Modifier) {
    AssistChip(
        onClick = {},
        label = { Text("Read-only", style = MaterialTheme.typography.labelSmall) },
        leadingIcon = {
            Icon(
                FeatherIcons.Lock,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        elevation = AssistChipDefaults.assistChipElevation(),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    )
}
