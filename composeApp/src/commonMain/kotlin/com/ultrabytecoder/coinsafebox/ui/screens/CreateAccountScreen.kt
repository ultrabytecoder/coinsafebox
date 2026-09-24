package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Search
import compose.icons.feathericons.X
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import coinsafebox.composeapp.generated.resources.Res
import coinsafebox.composeapp.generated.resources.ic_btc
import coinsafebox.composeapp.generated.resources.ic_eth
import coinsafebox.composeapp.generated.resources.ic_trx
import coinsafebox.composeapp.generated.resources.ic_ton
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.ui.viewmodel.CreateAccountViewModel
import com.ultrabytecoder.coinsafebox.domain.model.AccountType as DomainAccountType
import com.ultrabytecoder.coinsafebox.providers.DerivationPathResolver
import com.ultrabytecoder.coinsafebox.navigation.Screen

private data class AccountTypeUi(
    val displayName: String,
    val tickerSymbol: String,
    val color: Color,
    val icon: DrawableResource
)

sealed interface CreatableItem {
    val displayName: String
    val tickerSymbol: String
    val color: Color
    val icon: DrawableResource

    data class Native(
        override val displayName: String,
        override val tickerSymbol: String,
        override val color: Color,
        override val icon: DrawableResource,
        val type: DomainAccountType
    ) : CreatableItem

    data class Token(
        override val displayName: String,
        override val tickerSymbol: String,
        override val color: Color,
        override val icon: DrawableResource,
        val type: DomainAccountType,
        val chainLabel: String
    ) : CreatableItem
}

private fun buildCreatableItems(networkConfig: NetworkConfig): List<CreatableItem> {
    val natives = listOf(
        CreatableItem.Native("Bitcoin (BTC)", "BTC", Color(0xFFF7931A), Res.drawable.ic_btc, DomainAccountType.Btc),
        CreatableItem.Native("Ethereum (ETH)", "ETH", Color(0xFF627EEA), Res.drawable.ic_eth, DomainAccountType.Eth),
        CreatableItem.Native("TRON (TRX)", "TRX", Color(0xFFFF0013), Res.drawable.ic_trx, DomainAccountType.Trx),
        CreatableItem.Native("Gram (GRAM)", "GRAM", Color(0xFF0098EA), Res.drawable.ic_ton, DomainAccountType.Ton("V3R2"))
    )
    val erc20 = networkConfig.erc20Tokens.map { (symbol, info) ->
        val type = DomainAccountType.Erc20(info.address)
        CreatableItem.Token(
            displayName = "$symbol (ERC20)",
            tickerSymbol = symbol,
            color = tokenColorFor(isErc20 = true),
            icon = tokenIconFor(symbol, isErc20 = true),
            type = type,
            chainLabel = type.chainLabel
        )
    }
    val trc20 = networkConfig.trc20Tokens.map { (symbol, info) ->
        val type = DomainAccountType.Trc20(info.address)
        CreatableItem.Token(
            displayName = "$symbol (TRC20)",
            tickerSymbol = symbol,
            color = tokenColorFor(isErc20 = false),
            icon = tokenIconFor(symbol, isErc20 = false),
            type = type,
            chainLabel = type.chainLabel
        )
    }
    return natives + erc20 + trc20
}

private fun CreatableItem.toAccountTypeUi(): AccountTypeUi = AccountTypeUi(
    displayName = displayName,
    tickerSymbol = tickerSymbol,
    color = color,
    icon = icon
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAccountScreen(
    navController: NavController,
    viewModel: CreateAccountViewModel
) {
    val networkConfig: NetworkConfig = koinInject()
    val allItems = remember { buildCreatableItems(networkConfig) }

    var selectedItem by remember { mutableStateOf<CreatableItem?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var derivationPath by rememberSaveable { mutableStateOf("") }
    var isPathValid by remember { mutableStateOf(true) }
    var selectedTonWalletVersion by rememberSaveable { mutableStateOf("V3R2") }
    var isCreating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    val filteredItems = remember(searchQuery, allItems) {
        if (searchQuery.isBlank()) allItems
        else allItems.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
            it.tickerSymbol.contains(searchQuery, ignoreCase = true)
        }
    }

    // Clear selection if filtered out
    LaunchedEffect(filteredItems, selectedItem) {
        if (selectedItem != null && selectedItem !in filteredItems) {
            selectedItem = null
        }
    }

    // Derivation path defaults for native selection
    LaunchedEffect(selectedItem) {
        val native = selectedItem as? CreatableItem.Native
        if (native != null) {
            derivationPath = viewModel.getDefaultDerivationPath(native.type)
            isPathValid = true
        }
    }

    val isTon = (selectedItem as? CreatableItem.Native)?.type is DomainAccountType.Ton
    val isToken = selectedItem is CreatableItem.Token
    val isNative = selectedItem is CreatableItem.Native
    val buttonEnabled = when {
        isNative -> isPathValid && derivationPath.isNotBlank()
        isToken -> true
        else -> false
    } && !isCreating

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Account") },
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
            Text(
                "Select account type",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Create a new account or add a token",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Search box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text("Search by name or symbol") },
                leadingIcon = {
                    Icon(FeatherIcons.Search, contentDescription = "Search")
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(FeatherIcons.X, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Unified grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filteredItems) { item ->
                    AccountTypeCard(
                        accountType = item.toAccountTypeUi(),
                        isSelected = selectedItem == item,
                        onClick = { selectedItem = item }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Derivation path — only for native types
            val selectedNative = selectedItem as? CreatableItem.Native
            if (selectedNative != null) {
                Text(
                    "Derivation Path",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = derivationPath,
                    onValueChange = { newPath ->
                        derivationPath = newPath
                        isPathValid = DerivationPathResolver.isValidPath(
                            newPath,
                            selectedNative.type,
                            networkConfig
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    isError = !isPathValid,
                    supportingText = {
                        if (!isPathValid) {
                            Text("Invalid derivation path format")
                        }
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Wallet version — only for GRAM
            if (isTon) {
                Text(
                    "Wallet Version",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (version in listOf("V3R2", "V4R2")) {
                        val isSelected = selectedTonWalletVersion == version
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedTonWalletVersion = version },
                            label = { Text(version) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Create / Add button
            Button(
                onClick = {
                    if (isCreating) return@Button
                    isCreating = true
                    scope.launch {
                        try {
                            when (val item = selectedItem) {
                            is CreatableItem.Native -> {
                                val finalType = if (item.type is DomainAccountType.Ton) {
                                    DomainAccountType.Ton(walletVersion = selectedTonWalletVersion)
                                } else {
                                    item.type
                                }
                                val path = derivationPath.ifBlank { null }
                                val params = if (finalType is DomainAccountType.Ton) {
                                    """{"walletVersion":"$selectedTonWalletVersion"}"""
                                } else {
                                    null
                                }
                                viewModel.createAccount(
                                    displayName = item.displayName,
                                    type = finalType,
                                    symbol = item.tickerSymbol,
                                    params = params,
                                    derivationPath = path
                                )
                                navController.popBackStack()
                            }
                            is CreatableItem.Token -> {
                                when (val r = viewModel.createToken(item.type, item.tickerSymbol)) {
                                    is com.ultrabytecoder.coinsafebox.domain.usecase.CreateTokenUseCase.Result.Created -> {
                                        navController.popBackStack()
                                    }
                                    is com.ultrabytecoder.coinsafebox.domain.usecase.CreateTokenUseCase.Result.NeedsParentSelection -> {
                                        navController.navigate(
                                            Screen.AddToken(
                                                walletId = viewModel.walletId,
                                                preselectedTokenAddress = r.tokenAddress,
                                                preselectedTokenType = r.tokenTypeCode,
                                                requireManualSelection = true
                                            )
                                        ) {
                                            popUpTo<Screen.CreateAccount> { inclusive = true }
                                        }
                                    }
                                }
                            }
                            null -> Unit
                        }
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar(e.message ?: "Failed to create account")
                    } finally {
                        isCreating = false
                    }
                }
            },
                enabled = buttonEnabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (isToken) "Add Token" else "Create Account")
            }

                    }
    }
}

@Composable
private fun AccountTypeCard(
    accountType: AccountTypeUi,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) accountType.color else Color.Transparent

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
                    .background(accountType.color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(accountType.icon),
                    contentDescription = accountType.displayName,
                    modifier = Modifier.size(40.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = accountType.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}