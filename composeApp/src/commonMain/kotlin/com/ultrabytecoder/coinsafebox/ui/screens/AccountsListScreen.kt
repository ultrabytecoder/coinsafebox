package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import com.ultrabytecoder.coinsafebox.domain.model.AccountGroup
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.ui.components.ReadOnlyBadge
import com.ultrabytecoder.coinsafebox.ui.theme.AuroraPrimary
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import com.ultrabytecoder.coinsafebox.navigation.Screen
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcController
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcProposal
import com.ultrabytecoder.coinsafebox.ui.viewmodel.AccountsListViewModel
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import coinsafebox.composeapp.generated.resources.Res
import coinsafebox.composeapp.generated.resources.ic_btc
import coinsafebox.composeapp.generated.resources.ic_eth
import coinsafebox.composeapp.generated.resources.ic_trx
import coinsafebox.composeapp.generated.resources.ic_ton
import coinsafebox.composeapp.generated.resources.ic_usdc
import coinsafebox.composeapp.generated.resources.ic_link
import coinsafebox.composeapp.generated.resources.ic_weth
import coinsafebox.composeapp.generated.resources.ic_dai
import coinsafebox.composeapp.generated.resources.ic_usdt
import coinsafebox.composeapp.generated.resources.ic_wbtc
import coinsafebox.composeapp.generated.resources.ic_btt
import coinsafebox.composeapp.generated.resources.ic_token_trc20

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsListScreen(
    navController: NavController,
    viewModel: AccountsListViewModel,
    wcController: WcController
) {
    val wallets by viewModel.wallets.collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedWalletId by viewModel.selectedWalletId.collectAsStateWithLifecycle()
    val accountGroups by viewModel.accountGroups.collectAsStateWithLifecycle(initialValue = null)
    val syncingAccounts by viewModel.syncingAccounts.collectAsStateWithLifecycle(initialValue = emptySet())
    val pendingWcProposal by wcController.proposalHolder.pendingProposal.collectAsStateWithLifecycle()
    val pendingWcRequest by wcController.pendingRequestHolder.pendingRequest.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val expandedAccountIds by viewModel.expandedAccountIds.collectAsStateWithLifecycle(initialValue = emptySet())
    val fiatBalances by viewModel.fiatBalances.collectAsStateWithLifecycle(initialValue = emptyMap())
    val selectedWallet = wallets.find { it.id == selectedWalletId }

    var walletSelectorExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = {
                    Box {
                        Row(
                            modifier = Modifier
                                .clickable { walletSelectorExpanded = true }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                selectedWallet?.name ?: "Wallet",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (selectedWallet?.isReadOnly == true) {
                                Spacer(modifier = Modifier.width(8.dp))
                                ReadOnlyBadge()
                            }
                            Icon(
                                FeatherIcons.ChevronDown,
                                contentDescription = "Select wallet",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Accounts",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = walletSelectorExpanded,
                            onDismissRequest = { walletSelectorExpanded = false }
                        ) {
                            wallets.forEach { wallet ->
                                DropdownMenuItem(
                                    text = { Text(wallet.name) },
                                    onClick = {
                                        walletSelectorExpanded = false
                                        viewModel.selectWallet(wallet.id)
                                    },
                                    trailingIcon = {
                                        if (wallet.id == selectedWalletId) {
                                            Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                )
                            }
                        }
                    }
                },
                actions = {
                    var moreMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { moreMenuExpanded = true }) {
                            Icon(FeatherIcons.MoreVertical, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = moreMenuExpanded,
                            onDismissRequest = { moreMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Export Mnemonic") },
                                onClick = {
                                    moreMenuExpanded = false
                                    navController.navigate(Screen.ExportMnemonic(selectedWalletId))
                                },
                                leadingIcon = { Icon(FeatherIcons.Key, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text("Manage Wallets") },
                                onClick = {
                                    moreMenuExpanded = false
                                    navController.navigate(Screen.ManageWallets)
                                },
                                leadingIcon = { Icon(FeatherIcons.Briefcase, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                            if (wcController.isAvailable()) {
                                if (selectedWallet?.isReadOnly != true) {
                                    DropdownMenuItem(
                                        text = { Text("Connect to dApp") },
                                        onClick = {
                                            moreMenuExpanded = false
                                            navController.navigate(Screen.WcPair(selectedWalletId))
                                        },
                                        leadingIcon = { Icon(FeatherIcons.Link, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("WalletConnect Sessions") },
                                    onClick = {
                                        moreMenuExpanded = false
                                        navController.navigate(Screen.WcSessions)
                                    },
                                    leadingIcon = { Icon(FeatherIcons.Globe, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    moreMenuExpanded = false
                                    navController.navigate(Screen.Settings)
                                },
                                leadingIcon = { Icon(FeatherIcons.Settings, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                        }
                    }
                    IconButton(onClick = { navController.navigate(Screen.CreateAccount(selectedWalletId)) }) {
                        Icon(FeatherIcons.Plus, contentDescription = "Add Account")
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
        pendingWcProposal?.let { proposal ->
            WcPendingProposalBanner(
                proposal = proposal,
                onReview = {
                    navController.navigate(
                        Screen.WcSessionProposal(proposal.id, selectedWalletId)
                    )
                },
                onDismiss = {
                    scope.launch {
                        // Tell the dApp the user declined; best-effort — the
                        // proposal may have expired or been consumed already.
                        try { wcController.sessionManager.reject(proposal.id) } catch (_: Exception) {}
                        wcController.proposalHolder.clear()
                    }
                }
            )
        }
        // Auto-navigate to the request confirmation screen when a dApp
        // sends a transaction request.
        LaunchedEffect(pendingWcRequest) {
            pendingWcRequest?.let {
                navController.navigate(Screen.WcRequest(selectedWalletId))
            }
        }
            if (accountGroups == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                val loadedGroups = accountGroups!!
                if (loadedGroups.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                FeatherIcons.Briefcase,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "No accounts yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Add your first account to get started",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = loadedGroups,
                            key = { it.parent.id }
                        ) { group ->
                            AccountGroupItem(
                                group = group,
                                isExpanded = group.parent.id in expandedAccountIds,
                                isSyncing = group.parent.id in syncingAccounts,
                                fiatBalances = fiatBalances,
                                onToggleExpand = { viewModel.toggleExpanded(group.parent.id) },
                                onParentClick = {
                                    navController.navigate(Screen.AccountDetails(group.parent.id))
                                },
                                onTokenClick = { token ->
                                    navController.navigate(Screen.AccountDetails(group.parent.id, token.id))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Banner shown when a dApp's session proposal is waiting for the user's decision. */
@Composable
private fun WcPendingProposalBanner(
    proposal: WcProposal,
    onReview: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                FeatherIcons.Link,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                "Connection request from ${proposal.proposerMetadata.name.ifBlank { "a dApp" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            TextButton(onClick = onReview) {
                Text("Review", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun AccountGroupItem(
    group: AccountGroup,
    isExpanded: Boolean,
    isSyncing: Boolean,
    fiatBalances: Map<String, String>,
    onToggleExpand: () -> Unit,
    onParentClick: () -> Unit,
    onTokenClick: (AccountInfo) -> Unit
) {
    val rotation by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f, label = "chevronRotation")
    
    val infiniteTransition = rememberInfiniteTransition(label = "syncTransition")
    val syncAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "syncAlpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { onParentClick() },
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            // Parent row
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(group.parent.type.nativeIcon()),
                        contentDescription = group.parent.name,
                        modifier = Modifier.size(24.dp),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.parent.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (group.tokens.isNotEmpty()) "${group.tokens.size} assets" else "Main account",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "${group.parent.amount} ${group.parent.symbol}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AuroraPrimary
                    )
                    fiatBalances[group.parent.id]?.let { parentFiatBalance ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = parentFiatBalance,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (group.tokens.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable(onClickLabel = if (isExpanded) "Collapse" else "Expand") {
                                onToggleExpand()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            FeatherIcons.ChevronDown,
                            contentDescription = null,
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer { rotationZ = rotation },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Token rows (expanded)
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    group.tokens.forEach { token ->
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(start = 72.dp, end = 16.dp) // Aligns exactly with the start of token text
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(start = 24.dp, end = 16.dp) // 24 + 36 + 12 = 72dp aligns text perfectly
                                .clickable { onTokenClick(token) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.08f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(token.type.tokenIcon()),
                                    contentDescription = token.symbol,
                                    modifier = Modifier.size(20.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = token.symbol,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Column(
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    text = "${token.amount} ${token.symbol}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                fiatBalances[token.id]?.let { tokenFiatBalance ->
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = tokenFiatBalance,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Syncing indicator
            if (isSyncing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = syncAlpha * 0.5f))
                )
            }
        }
    }
}

private fun AccountType.nativeIcon(): DrawableResource = when (this) {
    is AccountType.Btc -> Res.drawable.ic_btc
    is AccountType.Eth -> Res.drawable.ic_eth
    is AccountType.Trx -> Res.drawable.ic_trx
    is AccountType.Ton -> Res.drawable.ic_ton
    else -> Res.drawable.ic_eth
}

private fun AccountType.tokenIcon(): DrawableResource = when (this) {
    is AccountType.Erc20 -> {
        when (tokenAddress.lowercase()) {
            "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238".lowercase(),
            "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48".lowercase() -> Res.drawable.ic_usdc
            "0x779877a7b0d9e8603169ddbd7836e478b4624789".lowercase(),
            "0x514910771af9ca656af840dff83e8264ecf986ca".lowercase() -> Res.drawable.ic_link
            "0x7b79995e5f793a07bc00c21412e50ecae098e7f9".lowercase(),
            "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2".lowercase() -> Res.drawable.ic_weth
            "0xff34b3d4aee8ddcd6f9afffb6fe49bd371b8a357".lowercase(),
            "0x6b175474e89094c44da98b954eedeac495271d0f".lowercase() -> Res.drawable.ic_dai
            "0x7169d38820dfd117c3fa1f22a697dba58d90ba06".lowercase(),
            "0xdac17f958d2ee523a2206206994597c13d831ec7".lowercase() -> Res.drawable.ic_usdt
            "0x29f2d40b060cca9757c756d8244d569865662692".lowercase(),
            "0x2260fac5e5542a773aa44fbcfedf7c193bc2c599".lowercase() -> Res.drawable.ic_wbtc
            else -> Res.drawable.ic_eth
        }
    }
    is AccountType.Trc20 -> {
        when (tokenAddress.lowercase()) {
            "txyzopyrdj2d9xrtbg411xzz3km5vkaebf".lowercase(),
            "tla0bzlzq5lm4rtf5g6q4c7q6j4jwzqy2r".lowercase() -> Res.drawable.ic_usdt
            "temvynqpntmqkpxp6wxtw2k7e4sm3crmwz".lowercase(),
            "te3l676d7vfa6z5zjz6w8q63zq5q7z5zjz".lowercase() -> Res.drawable.ic_usdc
            "tnuokl1ni8aoshffl1asca1gou9rxwazfn".lowercase() -> Res.drawable.ic_btt
            "txm9mxnegwad67jfme3ttk2mut3e69e9kn".lowercase() -> Res.drawable.ic_weth
            else -> Res.drawable.ic_token_trc20
        }
    }
    is AccountType.TonToken -> Res.drawable.ic_ton
    else -> Res.drawable.ic_eth
}