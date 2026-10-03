package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import compose.icons.feathericons.MoreVertical
import compose.icons.feathericons.Trash2
import com.ultrabytecoder.coinsafebox.util.QrEncoder
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.model.TransactionInfo
import com.ultrabytecoder.coinsafebox.navigation.Screen
import com.ultrabytecoder.coinsafebox.ui.components.ReadOnlyBadge
import com.ultrabytecoder.coinsafebox.ui.components.TransactionItem
import com.ultrabytecoder.coinsafebox.ui.theme.AuroraPrimary
import com.ultrabytecoder.coinsafebox.ui.viewmodel.AccountDetailsEvent
import com.ultrabytecoder.coinsafebox.ui.viewmodel.AccountDetailsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailsScreen(
    navController: NavController,
    viewModel: AccountDetailsViewModel
) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val address by viewModel.address.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedFiatBalance by viewModel.selectedFiatBalance.collectAsStateWithLifecycle()
    val isReadOnly by viewModel.isReadOnly.collectAsStateWithLifecycle()

    var showRemoveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AccountDetailsEvent.NavigateToAccountsList -> {
                    navController.navigate(Screen.AccountsList(event.walletId)) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    if (showRemoveDialog) {
        account?.let { acc ->
            RemoveAccountConfirmationDialog(
                accountName = acc.name,
                onConfirm = {
                    showRemoveDialog = false
                    viewModel.removeAccount()
                },
                onDismiss = { showRemoveDialog = false }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = account?.name ?: "Account Details",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isReadOnly) ReadOnlyBadge()
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = "Back")
                    }
                },
                actions = {
                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(FeatherIcons.MoreVertical, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Remove Account") },
                                onClick = {
                                    menuExpanded = false
                                    showRemoveDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        FeatherIcons.Trash2,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Balance section with chips
            account?.let { acc ->
                val allAssets = listOf(acc) + uiState.tokens
                val selected = uiState.selectedAccount ?: acc
                BalanceSection(
                    assets = allAssets,
                    selectedAccount = selected,
                    fiatBalance = selectedFiatBalance,
                    onSelect = { viewModel.selectAccount(it) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // QR Code / Address section
            address?.let { addr ->
                AddressSection(addr)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Error banner
            error?.let { errorMsg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = errorMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Dismiss")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Send button
            val selected = uiState.selectedAccount ?: account
            selected?.let { acc ->
                Button(
                    onClick = {
                        navController.navigate(Screen.Send(acc.id))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Send ${acc.symbol}")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Transaction list section
            Text(
                text = "Transactions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            selected?.let { acc ->
                TransactionListSection(
                    transactions = transactions,
                    accountType = acc.type,
                    isSyncing = uiState.isSyncingTransactions,
                    isLoadingMore = isLoadingMore,
                    hasMore = hasMore,
                    onLoadMore = { viewModel.loadNextPage() },
                    onTransactionClick = { tx ->
                        navController.navigate(Screen.TransactionDetails(tx.id))
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RemoveAccountConfirmationDialog(
    accountName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove Account") },
        text = {
            Text(
                "Are you sure you want to remove \"$accountName\"? " +
                    "This account, all of its tokens, and their transaction history " +
                    "will be permanently removed."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
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

@Composable
private fun BalanceSection(
    assets: List<AccountInfo>,
    selectedAccount: AccountInfo,
    fiatBalance: String?,
    onSelect: (AccountInfo) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Asset chips
            if (assets.size > 1) {
                Text(
                    text = "Select Asset",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(assets, key = { it.id }) { asset ->
                        val isSelected = asset.id == selectedAccount.id
                        AssetChip(
                            account = asset,
                            isSelected = isSelected,
                            onClick = { onSelect(asset) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // Selected balance
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${selectedAccount.amount} ${selectedAccount.symbol}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = AuroraPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (fiatBalance != null) {
                    Text(
                        text = fiatBalance,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = selectedAccount.derivationPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AssetChip(
    account: AccountInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val chipColor = account.type.chipColor()

    Card(
        modifier = Modifier
            .height(40.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) chipColor else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .wrapContentWidth()
                .padding(horizontal = 16.dp)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = account.symbol,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun AccountType.chipColor(): Color = when (this) {
    is AccountType.Btc -> Color(0xFFF7931A)
    is AccountType.Eth -> Color(0xFF627EEA)
    is AccountType.Trx -> Color(0xFFFF0013)
    is AccountType.Ton -> Color(0xFF0098EA)
    is AccountType.Erc20 -> Color(0xFF8B9FE8)
    is AccountType.Trc20 -> Color(0xFFFF4D5A)
    is AccountType.TonToken -> Color(0xFF0098EA)
}

@Composable
private fun AddressSection(address: String) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Address",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            val qrCode = remember(address) {
                QrEncoder.encode(address)
            }
            Canvas(modifier = Modifier.size(140.dp)) {
                drawRect(color = Color.White)
                val moduleSize = size.width / qrCode.size
                for (y in 0 until qrCode.size) {
                    for (x in 0 until qrCode.size) {
                        if (qrCode[x, y]) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(x * moduleSize, y * moduleSize),
                                size = Size(moduleSize, moduleSize)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(address))
                        copied = true
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                        contentDescription = "Copy address",
                        modifier = Modifier.size(16.dp),
                        tint = if (copied) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TransactionListSection(
    transactions: List<TransactionInfo>,
    accountType: AccountType,
    isSyncing: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onTransactionClick: (TransactionInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    val shouldLoadMore by remember {
        androidx.compose.runtime.derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = layoutInfo.totalItemsCount
            lastVisibleItem >= totalItems - 10
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && hasMore && !isLoadingMore) {
            onLoadMore()
        }
    }

    if (isSyncing && transactions.isEmpty()) {
        // First open with no cached txs: full-area spinner until the sync delivers the
        // first page (or confirms the account has no activity).
        Box(
            modifier = Modifier.then(modifier).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }
    } else if (transactions.isEmpty()) {
        Box(
            modifier = Modifier.then(modifier).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No transactions yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Inline "refreshing" indicator: the cached list stays visible while a
            // background sync is in flight.
            if (isSyncing) {
                item(key = "syncing-indicator") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Refreshing…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(
                items = transactions,
                key = { it.id }
            ) { tx ->
                TransactionItem(
                    tx = tx,
                    accountType = accountType,
                    onClick = { onTransactionClick(tx) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (isLoadingMore) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}