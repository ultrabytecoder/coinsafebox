package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Briefcase
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.Key
import compose.icons.feathericons.Lock
import compose.icons.feathericons.MoreVertical
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2
import com.ultrabytecoder.coinsafebox.domain.model.WalletInfo
import com.ultrabytecoder.coinsafebox.navigation.Screen
import com.ultrabytecoder.coinsafebox.ui.components.ReadOnlyBadge
import com.ultrabytecoder.coinsafebox.ui.viewmodel.ManageWalletsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageWalletsScreen(
    navController: NavController,
    viewModel: ManageWalletsViewModel
) {
    val wallets by viewModel.wallets.collectAsStateWithLifecycle(initialValue = emptyList())

    var walletToDelete by remember { mutableStateOf<WalletInfo?>(null) }
    var walletToRename by remember { mutableStateOf<WalletInfo?>(null) }
    var walletToRemoveKey by remember { mutableStateOf<WalletInfo?>(null) }
    val removeMasterKeyError by viewModel.removeMasterKeyError.collectAsStateWithLifecycle()

    // Auto-close the remove-master-key dialog once the wallet has actually flipped
    // to read-only (success). On failure the error stays visible in the dialog.
    LaunchedEffect(wallets, walletToRemoveKey) {
        val target = walletToRemoveKey ?: return@LaunchedEffect
        val current = wallets.find { it.id == target.id }
        if (current != null && current.isReadOnly) {
            walletToRemoveKey = null
        }
    }

    walletToDelete?.let { wallet ->
        DeleteConfirmationDialog(
            walletName = wallet.name,
            onConfirm = {
                viewModel.deleteWallet(wallet.id)
                walletToDelete = null
            },
            onDismiss = { walletToDelete = null }
        )
    }

    walletToRename?.let { wallet ->
        RenameWalletDialog(
            currentName = wallet.name,
            onConfirm = { newName ->
                viewModel.renameWallet(wallet.id, newName)
                walletToRename = null
            },
            onDismiss = { walletToRename = null }
        )
    }

    walletToRemoveKey?.let { wallet ->
        RemoveMasterKeyDialog(
            walletName = wallet.name,
            error = removeMasterKeyError,
            onConfirm = { viewModel.removeMasterKey(wallet.id) },
            onDismiss = {
                viewModel.clearRemoveMasterKeyError()
                walletToRemoveKey = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Wallets") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (wallets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            FeatherIcons.Briefcase,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No wallets yet",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Create your first wallet to get started",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = wallets,
                        key = { it.id }
                    ) { wallet ->
                        WalletItem(
                            wallet = wallet,
                            onRename = { walletToRename = wallet },
                            onDelete = { walletToDelete = wallet },
                            onExportMnemonic = {
                                navController.navigate(Screen.ExportMnemonic(wallet.id))
                            },
                            onRemoveMasterKey = { walletToRemoveKey = wallet }
                        )
                    }
                }
            }

            Button(
                onClick = { navController.navigate(Screen.CreateWallet) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    FeatherIcons.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Create Wallet")
            }
        }
    }
}

@Composable
private fun WalletItem(
    wallet: WalletInfo,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExportMnemonic: () -> Unit,
    onRemoveMasterKey: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = wallet.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                if (wallet.isReadOnly) {
                    Spacer(modifier = Modifier.height(4.dp))
                    ReadOnlyBadge()
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(FeatherIcons.MoreVertical, contentDescription = "More")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                        leadingIcon = {
                            Icon(FeatherIcons.Edit2, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Export Mnemonic") },
                        onClick = {
                            menuExpanded = false
                            onExportMnemonic()
                        },
                        leadingIcon = {
                            Icon(FeatherIcons.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (wallet.isReadOnly) "Read-only (key removed)" else "Remove Master Key") },
                        enabled = !wallet.isReadOnly,
                        onClick = {
                            menuExpanded = false
                            onRemoveMasterKey()
                        },
                        leadingIcon = {
                            Icon(FeatherIcons.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(FeatherIcons.Trash2, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirmationDialog(
    walletName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Wallet") },
        text = {
            Text("Are you sure you want to delete \"$walletName\"? All accounts and transaction history will be permanently removed.")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun RenameWalletDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Wallet") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Wallet name") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (newName.isNotBlank()) onConfirm(newName.trim()) },
                enabled = newName.isNotBlank() && newName.trim() != currentName
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun RemoveMasterKeyDialog(
    walletName: String,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var acknowledged by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove Master Key") },
        text = {
            Column {
                Text(
                    "This is IRREVERSIBLE. \"$walletName\" will become read-only: you can still view balances and transaction history, but sending requires your recovery phrase."
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it }
                    )
                    Text("I have backed up my recovery phrase")
                }
                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = acknowledged
            ) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
