package com.ultrabytecoder.coinsafebox.di

import com.ultrabytecoder.coinsafebox.security.DbSessionFactory
import com.ultrabytecoder.coinsafebox.data.DatabaseProvider
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.data.PinRepositoryImpl
import com.ultrabytecoder.coinsafebox.data.RemoteFiatQuoteProvider
import com.ultrabytecoder.coinsafebox.data.SettingsStorage
import com.ultrabytecoder.coinsafebox.data.SessionUnlocker
import com.ultrabytecoder.coinsafebox.data.applyCustomNodes
import com.ultrabytecoder.coinsafebox.domain.provider.FiatQuoteProvider
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.PinRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository
import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
import com.ultrabytecoder.coinsafebox.domain.service.KeyProvider
import com.ultrabytecoder.coinsafebox.domain.usecase.AddTokenUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.CheckPinStatusUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.ChangePinUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.CreateAccountUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.CreateTokenUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.CreateWalletUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.EstimateFeeUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountAddressUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountsUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetWalletsUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetMnemonicUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.DeleteAccountUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.DeleteWalletUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.RenameWalletUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.ReconcileAddressesUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.SendUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.RemoveMasterKeyUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetSecurityMethodUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.SetSecurityMethodUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.SetupPinUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.SyncAccountBalanceUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.SyncBalanceUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.SyncManager
import com.ultrabytecoder.coinsafebox.domain.usecase.SyncTransactionsUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.VerifyPinUseCase
import com.ultrabytecoder.coinsafebox.security.KeyManager
import com.ultrabytecoder.coinsafebox.security.SessionManager
import com.ultrabytecoder.coinsafebox.data.walletconnect.SqlWcSessionRepository
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcController
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcCrypto
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcEthSigner
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcMetadata
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcPendingRequestHolder
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcProposalHolder
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcRelayClient
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcRequestHandler
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcSessionManager
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcSessionRepository
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcSessionRequestHandler
import com.ultrabytecoder.coinsafebox.ui.viewmodel.CreateWalletFlowDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun appModule(networkConfig: NetworkConfig) = module {
    single<NetworkConfig>(named("raw")) { networkConfig }
    single<NetworkConfig> { get<NetworkConfig>(named("raw")).applyCustomNodes(get()) }

    // Security: envelope key management + lazy session (DB opens only after unlock)
    single { KeyManager(get()) }
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { SessionManager(platformDriverFactory(), get()) }
    single<DatabaseProvider> { get<SessionManager>() }
    single<SessionUnlocker> { get<SessionManager>() }

    // App-scoped in-memory draft of non-secret create-wallet flow state,
    // so an interrupted flow (session lock) can resume after re-auth.
    single { CreateWalletFlowDraft() }

    single<AccountRepository> { com.ultrabytecoder.coinsafebox.data.AccountRepository(get()) }
    single<UtxoRepository> { com.ultrabytecoder.coinsafebox.data.UtxoRepository(get()) }
    single<TransactionRepository> { com.ultrabytecoder.coinsafebox.data.TransactionRepository(get()) }
    single<WalletRepository> { com.ultrabytecoder.coinsafebox.data.WalletRepository(get()) }
    single<KeyProvider> { com.ultrabytecoder.coinsafebox.data.KeyProviderImpl(get()) }

    factory { CreateWalletUseCase(get()) }
    factory { GetMnemonicUseCase(get()) }
    factory { GetAccountsUseCase(get()) }
    factory { CreateAccountUseCase(get(), get(), get(), get(), get()) }
    factory { AddTokenUseCase(get()) }
    factory { CreateTokenUseCase(get(), get(), get()) }
    factory { EstimateFeeUseCase(get(), get(), get(), get(), get(), get()) }
    factory { SendUseCase(get(), get(), get(), get(), get(), get()) }
    factory { GetAccountAddressUseCase(get(), get(), get(), get(), get(), get()) }
    factory { GetWalletsUseCase(get()) }
    factory { DeleteAccountUseCase(get(), get(), get()) }
    factory { DeleteWalletUseCase(get(), get(), get(), get()) }
    factory { RenameWalletUseCase(get()) }
    factory { RemoveMasterKeyUseCase(get(), get(), get()) }
    factory { ReconcileAddressesUseCase(get(), get(), get(), get(), get(), get()) }
    single { SyncManager() }
    factory { SyncBalanceUseCase(get(), get(), get(), get(), get(), get(), get()) }
    factory { SyncAccountBalanceUseCase(get(), get(), get(), get(), get(), get(), get()) }
    factory { SyncTransactionsUseCase(get(), get(), get(), get(), get(), get(), get()) }

    single<PinRepository> { PinRepositoryImpl(get(), get(), get(), get()) }
    factory { CheckPinStatusUseCase(get()) }
    factory { GetSecurityMethodUseCase(get()) }
    factory { SetSecurityMethodUseCase(get()) }
    factory { SetupPinUseCase(get()) }
    factory { VerifyPinUseCase(get()) }
    factory { ChangePinUseCase(get()) }

    single<FiatQuoteProvider> { RemoteFiatQuoteProvider(get()) }

    // WalletConnect v2: relay crypto/transport + session manager. The relay is only
    // connected after DB unlock (see WcController.start in the unlock flow) and
    // dropped on session lock (see the lock handler in App.kt).
    single { WcCrypto(get<SettingsStorage>()) }
    single { WcRelayClient(get(), get<NetworkConfig>().wcProjectId) }
    single<WcSessionRepository> { SqlWcSessionRepository(get()) }
    single { WcPendingRequestHolder() }
    single { WcEthSigner(get<NetworkConfig>()) }
    single<WcSessionRequestHandler> { WcRequestHandler(get(), get<NetworkConfig>()) }
    single {
        WcSessionManager(
            crypto = get(),
            relay = get(),
            walletMetadata = WcMetadata(
                name = "CoinSafeBox",
                description = "Self-custody multi-chain wallet",
                url = "https://coinsafebox.duckdns.org"
            ),
            supportedChainIds = listOf(get<NetworkConfig>().ethChainId),
            requestHandler = get(),
            sessionRepository = get()
        )
    }
    single { WcProposalHolder() }
    single { WcController(get(), get(), get(), get(), get()) }
}