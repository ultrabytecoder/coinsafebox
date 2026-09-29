package com.ultrabytecoder.coinsafebox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.ultrabytecoder.coinsafebox.navigation.Screen
import com.ultrabytecoder.coinsafebox.ui.screens.AccountDetailsScreen
import com.ultrabytecoder.coinsafebox.ui.screens.AccountsListScreen
import com.ultrabytecoder.coinsafebox.ui.screens.CreateAccountScreen
import com.ultrabytecoder.coinsafebox.ui.screens.AddTokenScreen
import com.ultrabytecoder.coinsafebox.ui.screens.CreateWalletFlow
import com.ultrabytecoder.coinsafebox.ui.screens.ExportMnemonicScreen
import com.ultrabytecoder.coinsafebox.ui.screens.ManageWalletsScreen
import com.ultrabytecoder.coinsafebox.ui.screens.ChooseSecurityMethodScreen
import com.ultrabytecoder.coinsafebox.ui.screens.PinScreenEnter
import com.ultrabytecoder.coinsafebox.ui.screens.PinScreenSetup
import com.ultrabytecoder.coinsafebox.ui.screens.SendScreen
import com.ultrabytecoder.coinsafebox.ui.screens.SettingsScreen
import com.ultrabytecoder.coinsafebox.ui.screens.CustomNodesScreen
import com.ultrabytecoder.coinsafebox.ui.screens.ChangePinScreen
import com.ultrabytecoder.coinsafebox.ui.screens.SetPasswordScreen
import com.ultrabytecoder.coinsafebox.ui.screens.TransactionSentScreen
import com.ultrabytecoder.coinsafebox.ui.screens.TransactionDetailsScreen
import com.ultrabytecoder.coinsafebox.ui.screens.WelcomeScreen
import com.ultrabytecoder.coinsafebox.ui.theme.CoinSafeBoxTheme
import com.ultrabytecoder.coinsafebox.ui.viewmodel.AccountDetailsViewModel
import com.ultrabytecoder.coinsafebox.domain.usecase.SyncManager
import com.ultrabytecoder.coinsafebox.ui.viewmodel.AccountsListViewModel
import com.ultrabytecoder.coinsafebox.ui.viewmodel.CreateAccountViewModel
import com.ultrabytecoder.coinsafebox.ui.viewmodel.AddTokenViewModel
import com.ultrabytecoder.coinsafebox.ui.viewmodel.CreateWalletFlowDraft
import com.ultrabytecoder.coinsafebox.ui.viewmodel.CreateWalletViewModel
import com.ultrabytecoder.coinsafebox.ui.viewmodel.ExportMnemonicViewModel
import com.ultrabytecoder.coinsafebox.ui.viewmodel.ManageWalletsViewModel
import com.ultrabytecoder.coinsafebox.ui.viewmodel.SendViewModel
import com.ultrabytecoder.coinsafebox.ui.viewmodel.SetupPinViewModel
import com.ultrabytecoder.coinsafebox.ui.viewmodel.EnterPinViewModel
import com.ultrabytecoder.coinsafebox.ui.viewmodel.SettingsViewModel
import com.ultrabytecoder.coinsafebox.ui.viewmodel.TransactionDetailsViewModel
import com.ultrabytecoder.coinsafebox.ui.viewmodel.CustomNodesViewModel
import com.ultrabytecoder.coinsafebox.ui.viewmodel.ChangePinViewModel
import com.ultrabytecoder.coinsafebox.ui.viewmodel.SetPasswordViewModel
import com.ultrabytecoder.coinsafebox.ui.viewmodel.StartupViewModel
import com.ultrabytecoder.coinsafebox.ui.viewmodel.StartupState
import com.ultrabytecoder.coinsafebox.domain.repository.PinState
import com.ultrabytecoder.coinsafebox.domain.provider.FiatQuoteProvider
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import com.ultrabytecoder.coinsafebox.domain.usecase.CheckPinStatusUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.EstimateFeeUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.CreateWalletUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountsUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetWalletsUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.SendUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.SyncAccountUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.SyncUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.CreateAccountUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.AddTokenUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.CreateTokenUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountAddressUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetMnemonicUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.DeleteWalletUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.RenameWalletUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.SetupPinUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.VerifyPinUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.ChangePinUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetSecurityMethodUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.SetSecurityMethodUseCase
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.security.SessionLockNotifier

/**
 * Creates a [ViewModel] scoped to the given navigation keys and cancels its
 * [viewModelScope] when the composable disposes (or the keys change).
 *
 * The ViewModels here are built with a bare remember {} and no ViewModelStore,
 * so onCleared() would never run and the scope's coroutines (stateIn
 * collectors, polling loops) would outlive the screen and leak. Cancelling the
 * scope on dispose replicates the ViewModelStore cleanup.
 */
@Composable
private fun <T : ViewModel> rememberDisposableViewModel(vararg keys: Any?, create: () -> T): T {
    val viewModel = remember(*keys) { create() }
    DisposableEffect(*keys) { onDispose { viewModel.viewModelScope.cancel() } }
    return viewModel
}

@Composable
fun App() {
    CoinSafeBoxTheme {
        val navController = rememberNavController()

        // Session locked (app backgrounded, see SessionLockNotifier): return to the
        // startup wizard, which routes to the PIN unlock (or recovery) screen.
        LaunchedEffect(Unit) {
            SessionLockNotifier.locked.collect {
                // Guard against redundant navigations when the lock fires repeatedly
                // (e.g. rapid background/foreground): if we are already on the startup
                // screen, there is nothing to do (NEW-9).
                if (navController.currentDestination?.hasRoute(Screen.Startup::class) != true) {
                    navController.navigate(Screen.Startup) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
        }

        NavHost(
            navController = navController,
            startDestination = Screen.Startup
        ) {
            composable<Screen.Startup> {
                val checkPinStatus: CheckPinStatusUseCase = koinInject()
                val viewModel = rememberDisposableViewModel { StartupViewModel(checkPinStatus) }
                val currentState by viewModel.state.collectAsStateWithLifecycle()

                when (currentState) {
                    is StartupState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    is StartupState.NeedsPinSetup -> {
                        LaunchedEffect(currentState) {
                            navController.navigate(Screen.Welcome) {
                                popUpTo(Screen.Startup) { inclusive = true }
                            }
                        }
                    }
                    is StartupState.NeedsPinUnlock -> {
                        LaunchedEffect(currentState) {
                            navController.navigate(Screen.EnterPin) {
                                popUpTo(Screen.Startup) { inclusive = true }
                            }
                        }
                    }
                }
            }
            composable<Screen.Welcome> {
                WelcomeScreen(navController)
            }
            composable<Screen.ChooseSecurityMethod> {
                val setMethod: SetSecurityMethodUseCase = koinInject()
                ChooseSecurityMethodScreen(navController, setMethod)
            }
            composable<Screen.CreateWallet> {
                val createWallet: CreateWalletUseCase = koinInject()
                val draft: CreateWalletFlowDraft = koinInject()
                val viewModel = rememberDisposableViewModel {
                    CreateWalletViewModel(createWallet, draft)
                }

                // Deterministic wipe at composable dispose: the SESS-3 lock
                // collector (in the VM init) may be cancelled by
                // viewModelScope.cancel() before it fires, so this
                // DisposableEffect is the defense-in-depth backup. Mirrors
                // ExportMnemonicScreen's onDispose -> clearSensitiveData().
                DisposableEffect(viewModel) {
                    onDispose {
                        viewModel.wipeSecrets()
                    }
                }

                CreateWalletFlow(
                    navController = navController,
                    onWalletCreated = { walletId ->
                        // The PIN is always set up BEFORE wallet creation (startup wizard),
                        // so the session is open and we can go straight to the main screen.
                        navController.navigate(Screen.AccountsList(walletId)) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    viewModel = viewModel
                )
            }
            composable<Screen.AccountsList> { backStackEntry ->
                val route = backStackEntry.toRoute<Screen.AccountsList>()
                val getAccounts: GetAccountsUseCase = koinInject()
                val getWallets: GetWalletsUseCase = koinInject()
                val syncUseCase: SyncUseCase = koinInject()
                val syncManager: SyncManager = koinInject()
                val settingsStorage: com.ultrabytecoder.coinsafebox.data.SettingsStorage = koinInject()
                val quoteProvider: FiatQuoteProvider = koinInject()
                val viewModel = rememberDisposableViewModel(route.walletId) {
                    AccountsListViewModel(
                        route.walletId, getAccounts, getWallets, syncUseCase, syncManager,
                        settingsStorage, quoteProvider
                    )
                }
                AccountsListScreen(navController, viewModel)
            }
            composable<Screen.AccountDetails> { backStackEntry ->
                val route = backStackEntry.toRoute<Screen.AccountDetails>()
                val getAccounts: GetAccountsUseCase = koinInject()
                val getAccountAddress: GetAccountAddressUseCase = koinInject()
                val accountRepository: com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository = koinInject()
                val transactionRepository: TransactionRepository = koinInject()
                val settingsStorage: com.ultrabytecoder.coinsafebox.data.SettingsStorage = koinInject()
                val quoteProvider: FiatQuoteProvider = koinInject()
                val viewModel = rememberDisposableViewModel(route.accountId, route.preselectedTokenId) {
                    AccountDetailsViewModel(
                        route.accountId, route.preselectedTokenId, getAccounts, getAccountAddress,
                        accountRepository, transactionRepository, settingsStorage, quoteProvider
                    )
                }
                AccountDetailsScreen(navController, viewModel)
            }
            composable<Screen.Send> { backStackEntry ->
                val route = backStackEntry.toRoute<Screen.Send>()
                val getAccounts: GetAccountsUseCase = koinInject()
                val send: SendUseCase = koinInject()
                val estimateFee: EstimateFeeUseCase = koinInject()
                val syncAccount: SyncAccountUseCase = koinInject()
                val accountRepository: com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository = koinInject()
                val utxoRepository: com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository = koinInject()
                val transactionRepository: com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository = koinInject()
                val keyProvider: com.ultrabytecoder.coinsafebox.domain.service.KeyProvider = koinInject()
                val networkConfig: com.ultrabytecoder.coinsafebox.data.NetworkConfig = koinInject()
                val settingsStorage: com.ultrabytecoder.coinsafebox.data.SettingsStorage = koinInject()
                val quoteProvider: FiatQuoteProvider = koinInject()
                val viewModel = rememberDisposableViewModel(route.accountId) {
                    SendViewModel(route.accountId, getAccounts, send, estimateFee, syncAccount,
                        accountRepository, utxoRepository, transactionRepository, keyProvider, networkConfig, settingsStorage, quoteProvider)
                }
                SendScreen(navController, viewModel)
            }
            composable<Screen.TransactionSent> { backStackEntry ->
                val route = backStackEntry.toRoute<Screen.TransactionSent>()
                TransactionSentScreen(navController, route.txId)
            }
            composable<Screen.TransactionDetails> { backStackEntry ->
                val route = backStackEntry.toRoute<Screen.TransactionDetails>()
                val transactionRepository: TransactionRepository = koinInject()
                val getAccounts: GetAccountsUseCase = koinInject()
                val viewModel = rememberDisposableViewModel(route.txId) {
                    TransactionDetailsViewModel(route.txId, transactionRepository, getAccounts)
                }
                TransactionDetailsScreen(navController, viewModel)
            }
            composable<Screen.CreateAccount> { backStackEntry ->
                val route = backStackEntry.toRoute<Screen.CreateAccount>()
                val createAccount: CreateAccountUseCase = koinInject()
                val createToken: CreateTokenUseCase = koinInject()
                val accountRepository: com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository = koinInject()
                val networkConfig: com.ultrabytecoder.coinsafebox.data.NetworkConfig = koinInject()
                val viewModel = rememberDisposableViewModel(route.walletId) {
                    CreateAccountViewModel(route.walletId, createAccount, createToken, accountRepository, networkConfig)
                }
                CreateAccountScreen(navController, viewModel)
            }
            composable<Screen.AddToken> { backStackEntry ->
                val route = backStackEntry.toRoute<Screen.AddToken>()
                val addToken: AddTokenUseCase = koinInject()
                val accountRepository: com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository = koinInject()
                val networkConfig: com.ultrabytecoder.coinsafebox.data.NetworkConfig = koinInject()
                val viewModel = rememberDisposableViewModel(route.walletId) {
                    AddTokenViewModel(route.walletId, addToken, accountRepository, networkConfig)
                }
                AddTokenScreen(
                    navController = navController,
                    viewModel = viewModel,
                    preselectedTokenAddress = route.preselectedTokenAddress,
                    preselectedTokenType = route.preselectedTokenType,
                    requireManualSelection = route.requireManualSelection
                )
            }
            composable<Screen.ExportMnemonic> { backStackEntry ->
                val route = backStackEntry.toRoute<Screen.ExportMnemonic>()
                val getMnemonic: GetMnemonicUseCase = koinInject()
                val verifyPin: VerifyPinUseCase = koinInject()
                val checkPinStatus: CheckPinStatusUseCase = koinInject()
                val getSecurityMethod: GetSecurityMethodUseCase = koinInject()
                val settingsStorage: com.ultrabytecoder.coinsafebox.data.SettingsStorage = koinInject()
                val viewModel = rememberDisposableViewModel(route.walletId) {
                    ExportMnemonicViewModel(
                        route.walletId, getMnemonic, verifyPin, checkPinStatus, getSecurityMethod, settingsStorage
                    )
                }
                ExportMnemonicScreen(navController, viewModel)
            }
            composable<Screen.ManageWallets> {
                val getWallets: GetWalletsUseCase = koinInject()
                val deleteWallet: DeleteWalletUseCase = koinInject()
                val renameWallet: RenameWalletUseCase = koinInject()
                val viewModel = rememberDisposableViewModel {
                    ManageWalletsViewModel(getWallets, deleteWallet, renameWallet)
                }
                ManageWalletsScreen(navController, viewModel)
            }
            composable<Screen.SetupPin> {
                val setupPin: SetupPinUseCase = koinInject()
                val settingsStorage: com.ultrabytecoder.coinsafebox.data.SettingsStorage = koinInject()
                val viewModel = rememberDisposableViewModel { SetupPinViewModel(setupPin, settingsStorage) }
                PinScreenSetup(navController, viewModel)
            }
            composable<Screen.SetupPassword> {
                val setupPin: SetupPinUseCase = koinInject()
                val viewModel = rememberDisposableViewModel { SetPasswordViewModel(setupPin) }
                SetPasswordScreen(navController, viewModel)
            }
            composable<Screen.EnterPin> {
                val verifyPin: VerifyPinUseCase = koinInject()
                val getWallets: GetWalletsUseCase = koinInject()
                val syncUseCase: SyncUseCase = koinInject()
                val checkPinStatus: CheckPinStatusUseCase = koinInject()
                val getSecurityMethod: GetSecurityMethodUseCase = koinInject()
                val settingsStorage: com.ultrabytecoder.coinsafebox.data.SettingsStorage = koinInject()
                val viewModel = rememberDisposableViewModel { EnterPinViewModel(verifyPin, getWallets, syncUseCase, checkPinStatus, getSecurityMethod, settingsStorage) }
                PinScreenEnter(navController, viewModel)
            }
            composable<Screen.Settings> {
                val settingsStorage: com.ultrabytecoder.coinsafebox.data.SettingsStorage = koinInject()
                val getSecurityMethod: GetSecurityMethodUseCase = koinInject()
                val viewModel = rememberDisposableViewModel { SettingsViewModel(settingsStorage) }
                val securityMethod by getSecurityMethod().collectAsStateWithLifecycle()
                SettingsScreen(navController, viewModel, securityMethod)
            }
            composable<Screen.ChangePin> {
                val changePin: ChangePinUseCase = koinInject()
                val getSecurityMethod: GetSecurityMethodUseCase = koinInject()
                val settingsStorage: com.ultrabytecoder.coinsafebox.data.SettingsStorage = koinInject()
                val viewModel = rememberDisposableViewModel { ChangePinViewModel(changePin, getSecurityMethod, settingsStorage) }
                ChangePinScreen(navController, viewModel)
            }
            composable<Screen.CustomNodes> {
                val settingsStorage: com.ultrabytecoder.coinsafebox.data.SettingsStorage = koinInject()
                val networkConfig: com.ultrabytecoder.coinsafebox.data.NetworkConfig = koinInject(named("raw"))
                val viewModel = rememberDisposableViewModel { CustomNodesViewModel(settingsStorage, networkConfig) }
                CustomNodesScreen(navController, viewModel)
            }
        }
    }
}
