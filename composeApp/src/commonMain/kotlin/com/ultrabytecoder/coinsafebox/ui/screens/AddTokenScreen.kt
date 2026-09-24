package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import coinsafebox.composeapp.generated.resources.Res
import coinsafebox.composeapp.generated.resources.ic_eth
import coinsafebox.composeapp.generated.resources.ic_ton
import coinsafebox.composeapp.generated.resources.ic_trx
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.data.TokenInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.ui.viewmodel.AddTokenViewModel

private data class TokenUi(
    val symbol: String,
    val color: Color,
    val icon: DrawableResource,
    val type: AccountType
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTokenScreen(
    navController: NavController,
    viewModel: AddTokenViewModel,
    preselectedTokenAddress: String? = null,
    preselectedTokenType: String? = null,
    requireManualSelection: Boolean = false
) {
    val networkConfig: NetworkConfig = koinInject()
    val parentAccounts by viewModel.parentAccounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val allAccounts by viewModel.allAccounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedParentId by viewModel.selectedParentId.collectAsStateWithLifecycle()
    val alreadyAddedTokens by viewModel.alreadyAddedTokens.collectAsStateWithLifecycle(emptySet())
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    // Collect errors from the ViewModel and show as snackbars
    LaunchedEffect(Unit) {
        viewModel.error.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    // Auto-select first eligible parent matching the preselected token type
    // Skip parents that already have the preselected token so the user can pick one that doesn't
    // Skip entirely when requireManualSelection is true (NeedsParentSelection flow).
    LaunchedEffect(parentAccounts, allAccounts, preselectedTokenAddress, preselectedTokenType, requireManualSelection) {
        if (requireManualSelection) return@LaunchedEffect
        if (selectedParentId == null && preselectedTokenAddress != null) {
            val parentTypeCode = preselectedTokenType ?: return@LaunchedEffect
            val matchingParents = parentAccounts.filter {
                if (parentTypeCode == "ERC20") it.type is AccountType.Eth
                else if (parentTypeCode == "TRC20") it.type is AccountType.Trx
                else if (parentTypeCode == "TON_TOKEN") it.type is AccountType.Ton
                else false
            }
            // Prefer a parent that does NOT already have the preselected token
            val tokenAddressLower = preselectedTokenAddress.lowercase()
            val bestParent = matchingParents.firstOrNull { parent ->
                val hasToken = allAccounts.any {
                    it.parentAccountId == parent.id &&
                    it.type.tokenContractAddress == tokenAddressLower
                }
                !hasToken
            }
            bestParent?.let {
                viewModel.selectParent(it.id)
            }
        }
    }

    val selectedParent = parentAccounts.find { it.id == selectedParentId }

    // Determine which token map to use based on selected parent's chain
    val availableTokens: Map<String, TokenInfo> = remember(networkConfig, selectedParent) {
        if (selectedParent == null) emptyMap()
        else when (selectedParent.type) {
            is AccountType.Eth -> networkConfig.erc20Tokens
            is AccountType.Trx -> networkConfig.trc20Tokens
            // TODO: add tonTokens to NetworkConfig when Jetton support is implemented
            is AccountType.Ton -> emptyMap()
            else -> emptyMap()
        }
    }

    // Build token UI list
    val tokenUiList: List<TokenUi> = remember(availableTokens, selectedParent, alreadyAddedTokens) {
        val isErc20 = selectedParent?.type is AccountType.Eth
        availableTokens.mapNotNull { (symbol, info) ->
            if (info.address.lowercase() in alreadyAddedTokens) return@mapNotNull null
            val accountType = when (selectedParent?.type) {
                is AccountType.Eth -> AccountType.Erc20(info.address)
                is AccountType.Trx -> AccountType.Trc20(info.address)
                else -> return@mapNotNull null
            }
            val (icon, color) = iconAndColor(symbol, isErc20)
            TokenUi(symbol, color, icon, accountType)
        }
    }

    var selectedToken by remember { mutableStateOf<TokenUi?>(null) }
    var userSelectedToken by remember { mutableStateOf(false) }

    // Clear selection if it gets filtered out (e.g. switching to a parent that already has it)
    LaunchedEffect(tokenUiList) {
        if (selectedToken != null && selectedToken !in tokenUiList) {
            selectedToken = null
            userSelectedToken = false
        }
    }

    // Auto-select preselected token from the token list
    LaunchedEffect(tokenUiList, preselectedTokenAddress) {
        if (preselectedTokenAddress != null && selectedToken == null) {
            val match = tokenUiList.find {
                it.type.tokenContractAddress == preselectedTokenAddress.lowercase()
            }
            if (match != null) selectedToken = match
        }
    }

    // Auto-add when both parent and token are preselected and ready
    // Skip when requireManualSelection is true — the user confirms manually.
    var added by remember { mutableStateOf(false) }
    var addFailed by remember { mutableStateOf(false) }
    var isAdding by remember { mutableStateOf(false) }
    LaunchedEffect(selectedParentId, selectedToken, preselectedTokenAddress, added, alreadyAddedTokens, requireManualSelection) {
        if (requireManualSelection) return@LaunchedEffect
        if (added) return@LaunchedEffect
        val parentId = selectedParentId ?: return@LaunchedEffect
        val token = selectedToken ?: return@LaunchedEffect
        if (preselectedTokenAddress == null) return@LaunchedEffect
        val addr = token.type.tokenContractAddress ?: return@LaunchedEffect
        if (addr in alreadyAddedTokens) return@LaunchedEffect

        added = true
        addFailed = false
        val success = viewModel.addToken(parentId, token.type, token.symbol)
        if (success) {
            navController.popBackStack()
        } else {
            added = false
            addFailed = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Token") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Parent account picker
            Text(
                "Parent account",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Select the account to link this token to",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Only show ETH and TRX accounts as eligible parents, further filtered by preselected token type.
            // Exclude parents that already have the preselected token.
            val eligibleParents = parentAccounts.filter { parent ->
                val isNativeParent = parent.type is AccountType.Eth || parent.type is AccountType.Trx || parent.type is AccountType.Ton
                if (!isNativeParent) return@filter false

                val matchesChain = when (preselectedTokenType) {
                    "ERC20" -> parent.type is AccountType.Eth
                    "TRC20" -> parent.type is AccountType.Trx
                    "TON_TOKEN" -> parent.type is AccountType.Ton
                    null -> true
                    else -> false
                }
                if (!matchesChain) return@filter false

                // Exclude parents that already have this specific token
                if (preselectedTokenAddress != null) {
                    val addr = preselectedTokenAddress.lowercase()
                    val alreadyHas = allAccounts.any { acc ->
                        acc.parentAccountId == parent.id &&
                        acc.type.tokenContractAddress == addr
                    }
                    !alreadyHas
                } else {
                    true
                }
            }

            if (eligibleParents.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No ETH, TRX, or GRAM accounts found. Create one first.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(eligibleParents) { account ->
                        ParentAccountCard(
                            account = account,
                            isSelected = account.id == selectedParentId,
                            onClick = {
                                viewModel.selectParent(account.id)
                                added = false
                                addFailed = false
                                if (preselectedTokenAddress == null) selectedToken = null
                            }
                        )
                    }
                }
            }

            // Retry button shown when auto-add fails in preselected flow
            if (preselectedTokenAddress != null && !requireManualSelection && addFailed) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        added = false
                        addFailed = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Retry")
                }
            }

            // Manual "Add Token" button for requireManualSelection flow (token is preselected, user picks parent).
            if (preselectedTokenAddress != null && requireManualSelection) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (isAdding) return@Button
                        val parentId = selectedParentId ?: return@Button
                        val token = selectedToken ?: return@Button
                        isAdding = true
                        scope.launch {
                            val success = viewModel.addToken(parentId, token.type, token.symbol)
                            if (success) {
                                navController.popBackStack()
                            } else {
                                isAdding = false
                            }
                        }
                    },
                    enabled = selectedParentId != null && selectedToken != null && !isAdding,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Add Token")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Step 2 — Token grid (only shown when no token is preselected)
            if (preselectedTokenAddress == null) {
                AnimatedVisibility(
                    visible = selectedParentId != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        Text(
                            "Token",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Select a token to add",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        selectedParent?.address?.let { addr ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                tonalElevation = 2.dp,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "Reuses parent's address: ${addr.take(10)}...${addr.takeLast(8)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(12.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            gridItems(tokenUiList) { token ->
                                TokenCard(
                                    token = token,
                                    isSelected = selectedToken == token,
                                    onClick = {
                                        selectedToken = token
                                        userSelectedToken = true
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (isAdding) return@Button
                        val token = selectedToken ?: return@Button
                        val parentId = selectedParentId ?: return@Button
                        isAdding = true
                        scope.launch {
                            val success = viewModel.addToken(parentId, token.type, token.symbol)
                            if (success) {
                                navController.popBackStack()
                            } else {
                                isAdding = false
                            }
                        }
                    },
                    enabled = selectedToken != null && selectedParentId != null && !isAdding,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Add Token")
                }
            }
        }
    }
}

private fun iconAndColor(symbol: String, isErc20: Boolean): Pair<DrawableResource, Color> {
    return tokenIconFor(symbol, isErc20) to tokenColorFor(isErc20)
}

@Composable
private fun ParentAccountCard(
    account: AccountInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val chainIcon = when (account.type) {
        is AccountType.Eth -> Res.drawable.ic_eth
        is AccountType.Trx -> Res.drawable.ic_trx
        is AccountType.Ton -> Res.drawable.ic_ton
        else -> Res.drawable.ic_eth
    }
    val chainColor = when (account.type) {
        is AccountType.Eth -> Color(0xFF627EEA)
        is AccountType.Trx -> Color(0xFFFF0013)
        is AccountType.Ton -> Color(0xFF0098EA)
        else -> Color.Gray
    }

    Card(
        modifier = Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(chainColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(chainIcon),
                    contentDescription = account.name,
                    modifier = Modifier.size(24.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = account.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )
            if (account.address != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${account.address.take(6)}...${account.address.takeLast(4)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TokenCard(
    token: TokenUi,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) token.color else Color.Transparent

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(token.color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(token.icon),
                    contentDescription = token.symbol,
                    modifier = Modifier.size(40.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = token.symbol,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}