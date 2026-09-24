# CoinSafeBox — Code Structure & Main Flows

A quick-reference document for understanding the codebase.

---

## 1. Project Overview

**CoinSafeBox** is a multiplatform cryptocurrency wallet built with **Kotlin Multiplatform (KMP)** + **Compose Multiplatform**.

| Target | Notes |
|--------|-------|
| Android | minSdk 24, target 36, JVM 17 |
| iOS | iOS 18.2, static framework `ComposeApp`, cinterop for `CommonCrypto` + `libsodium` |
| Desktop (JVM) | Linux/macOS/Windows, JVM 17, packaged as `.deb`/`.rpm`/`.msi`/`.dmg` |

**Single Gradle module:** `:composeApp`. Android product flavors: `productionTestnet` / `productionMainnet`.

**Key libraries:**
- Compose Multiplatform (Material3, dark theme by default)
- Koin (DI)
- SQLDelight + SQLCipher (encrypted database)
- Ktor (HTTP client)
- `fr.acinq.secp256k1` + `fr.acinq.bitcoin` (BIP-39, BIP-32, SHA-256)
- `dev.whyoleg.cryptography` + `org.kotlincrypto.random` (CSPRNG)
- BouncyCastle (Argon2id on JVM), libsodium (Argon2id on iOS)
- JNA (desktop native bindings: SQLCipher, DPAPI, libsecret, Security.framework)
- Feather icons, QR generation

---

## 2. Source Set Layout

```
composeApp/src/
├── commonMain/          # ALL shared code (domain, data, security, providers, UI, navigation, DI)
├── commonTest/          # Shared tests
├── androidMain/         # Android actuals + MainActivity, WalletApplication, QrScannerActivity
├── iosMain/             # iOS actuals + MainViewController
├── jvmMain/             # Shared JVM actuals (crypto primitives, HardwareKeyStore)
├── desktopMain/         # Desktop actuals (SQLCipher driver, hardware key backends, JNA)
└── desktopTest/         # Desktop tests
```

A custom `jvmMain` source set is shared between the desktop (jvm) and Android targets.

---

## 3. Package Structure

Base package: `com.ultrabytecoder.coinsafebox`

| Package | Contents |
|---------|----------|
| *(root)* | `App.kt` (root composable + NavHost), `Platform.kt` |
| `navigation/` | `Screen.kt` — all routes (sealed interface) |
| `di/` | `AppModule.kt` (shared Koin module), per-platform `PlatformModule.kt` |
| `domain/model/` | `WalletInfo`, `AccountInfo`, `AccountType`, `TransactionInfo`, `FeeEstimation`, etc. |
| `domain/repository/` | Interfaces: `WalletRepository`, `AccountRepository`, `TransactionRepository`, `UtxoRepository`, `PinRepository` |
| `domain/service/` | `KeyProvider` (master seed loan pattern) |
| `domain/usecase/` | 21 use cases (see §7) |
| `domain/provider/` | `FiatQuoteProvider` interface + `MockFiatQuoteProvider` |
| `data/RemoteFiatQuoteProvider.kt` | Real fiat quote provider (Ktor), bound in DI |
| `data/` | Repository impls, `DatabaseProvider`, `DatabaseDriverFactory`, `SettingsStorage`, `NetworkConfig`, `KeyProviderImpl`, `PinRepositoryImpl` |
| `security/` | All crypto: `KeyManager`, `SessionManager`, `HardwareKeyStore`, `AesGcm`, `Kdf`, `Pbkdf2`, `SecureMemory`, `SecureMnemonicCode`, `EntropyCombiner`, `GestureEntropyAccumulator`, `SecretCipher`, `PinBytes`, `MonotonicClock`, `IdleHook`, `SessionLockNotifier` |
| `providers/` | `Provider` interface, `ProviderFactory`, `DerivationPathResolver`; sub-packages `btc/`, `eth/`, `tron/`, `ton/` |
| `platform/` | `ScreenshotProtector` (expect) |
| `ui/theme/` | `Theme.kt`, `Color.kt`, `Typography.kt` |
| `ui/screens/` | 23 screen composables |
| `ui/viewmodel/` | 16 ViewModels |
| `ui/components/` | `Numpad.kt`, `TransactionItem.kt` |
| `ui/util/` | `SecureScreen`, `SecureTextFieldState`, formatters |

**Desktop-only packages** (`desktopMain`): `sqlcipher/`, `security/hardware/` (OS backends), `data/AppDataDir.kt`.

---

## 4. Navigation

**File:** `navigation/Screen.kt` — a `sealed interface Screen` with `@Serializable` routes.

| Route | Params |
|-------|--------|
| `Startup` | — (start destination) |
| `Welcome` | — |
| `CreateWallet` | — |
| `AccountsList` | `walletId: Long` |
| `AccountDetails` | `accountId: String`, `preselectedTokenId: String?` |
| `Send` | `accountId: String` |
| `TransactionSent` | `txId: String` |
| `TransactionDetails` | `txId: String` |
| `CreateAccount` | `walletId: Long` |
| `AddToken` | `walletId`, `preselectedTokenAddress?`, `preselectedTokenType?`, `requireManualSelection` |
| `ExportMnemonic` | `walletId: Long` |
| `ManageWallets` | — |
| `ChooseSecurityMethod` | — |
| `SetupPin` | — |
| `SetupPassword` | — |
| `EnterPin` | — |
| `Settings` | — |
| `ChangePin` | — |
| `CustomNodes` | — |

**How it works** (`App.kt`):
- Single `NavHost` with `startDestination = Screen.Startup`.
- `Startup` is a **state-driven router**: observes `StartupViewModel.state`, navigates to `Welcome` (needs PIN setup) or `EnterPin` (needs unlock).
- `LaunchedEffect` collects `SessionLockNotifier.locked` — when the session locks (app backgrounded), resets the back stack to `Startup` (`popUpTo(0)`).
- Each `composable<Screen.X>` block uses `koinInject()` to pull use cases and `remember` to build the ViewModel.

---

## 5. Main Flows

### 5.1 App Startup

```
Entry point (MainActivity / MainViewController / main())
  → startKoin { appModule(networkConfig) + platformModule }
  → App() → NavHost → Screen.Startup
      → StartupViewModel maps CheckPinStatusUseCase() → PinState:
          NotSetup  → navigate to Welcome
          Setup     → navigate to EnterPin
```

- Android `WalletApplication` registers `ActivityLifecycleCallbacks`: on resume → `sessionManager.registerActivity()`; on stop (backgrounded) → `sessionManager.lock()` + `SessionLockNotifier.notifyLocked()`.
- Desktop `main()` picks mainnet/testnet via `-Dcoinsafebox.network`, installs idle hook.
- iOS observes `UIApplicationWillResignActiveNotification` to lock.

### 5.2 PIN / Password Setup (First Run)

```
Welcome → ChooseSecurityMethod (PIN or Password)
  → SetupPin (choose length 6/8, enter + confirm)
     OR SetupPassword (enter + confirm)
  → PinRepositoryImpl.setupPin():
      keyManager.deleteAll()
      keyManager.generateAndWrapDek(pin, method)
      sessionManager.unlockRecreating(dek)   // opens/creates SQLCipher DB
      store security method + PIN length
  → Navigate to CreateWallet
```

**Key point:** PIN is set up FIRST, before any wallet exists. After setup the session is open.

### 5.3 Wallet Creation

```
Screen.CreateWallet → CreateWalletFlow (state machine in CreateWalletViewModel)
  Steps: SETUP → PASSPHRASE? → GESTURE? → REVEAL

Way A (Generate new):
  CreateWalletSetupScreen (name, word count 12/15/18/21/24, optional gesture)
    → [PassphraseScreen] (optional BIP-39 passphrase entry + confirm)
    → [GestureEntropyScreen] (draw gesture → digest)
    → RevealMnemonicScreen (generates mnemonic ONCE via EntropyCombiner, shows it)
    → "Create Wallet" → createWalletFromGenerated()

Way B (Restore existing):
  CreateWalletSetupScreen (paste mnemonic)
    → [PassphraseScreen] (optional passphrase)
    → "Create Wallet" → createWalletFromMnemonic()

Both call CreateWalletUseCase(name, mnemonic, passphrase)
  → SecureMnemonicCode.validate + toSeed
  → WalletRepository.insertWallet (seed + mnemonic wrapped by SecretCipher)
   → walletCreated SharedFlow emits → navigate to AccountsList(walletId)
```

**Current UI layout of `CreateWalletSetupScreen`:**
- Top: "Set up your wallet" title + subtitle
- **Mode selector (SegmentedSingleChoice) at the TOP**: "Generate new" | "Restore existing"
- Wallet name field
- Mode-specific fields (word count + gesture checkbox for Generate; mnemonic field for Restore)
- **Action button at the BOTTOM**: "Next" (Generate) or "Create Wallet" (Restore); passphrase is entered on a separate `PassphraseScreen` step

### 5.4 Main App (After Wallet Exists)

```
AccountsList (main screen)
  ├── Wallet selector dropdown (switch wallet → FULL sync)
  ├── List of AccountGroup cards (native parent + expandable token children)
  │     each showing balance + fiat value
  ├── "Add Account" → CreateAccount
  ├── Overflow menu → ExportMnemonic, ManageWallets, Settings
  └── Tap account → AccountDetails
        ├── Address + QR
        ├── Transaction history (paginated)
        └── "Send" → SendScreen
              ├── Fee selection (Auto/Conservative/Balanced/Generous/Custom)
              └── "Send" → TransactionSent → TransactionDetails

CreateAccount → creates native account or token (may route to AddToken)
Settings → fiat currency, ChangePin, CustomNodes
ManageWallets → rename/delete wallets
```

### 5.5 PIN / Unlock

```
EnterPin (PinScreenEnter): PIN numpad or password field
  → EnterPinViewModel accumulates in wipe-able buffer
  → VerifyPinUseCase → PinRepositoryImpl.verifyPin():
      Success  → navigateAfterUnlock():
                   wallet exists → FULL sync + AccountsList
                   no wallet     → CreateWallet
      WrongPin → shake + remaining attempts
      Locked   → countdown
      Corrupted→ NavigateToRecovery (re-setup PIN)
      SessionLocked → "try again"
```

### 5.6 Security Flows

- **Export Mnemonic:** requires re-authentication (PIN/password) before revealing stored mnemonic; wipes on dispose and on session lock; schedules 30s clipboard clear.
- **Change PIN/Password:** 3 stages (old → new → confirm) → `ChangePinUseCase` → `PinRepositoryImpl.changePin()` (verifies old, re-wraps DEK envelope with new credential).
- **Recovery:** triggered when key material is corrupted / hardware key invalidated → routes to `SetupPin` (fresh DEK, DB recreated).

---

## 6. Security Architecture

### 6.1 Key Hierarchy (Envelope Encryption)

```
User credential (PIN/password)
        │  KDF (Argon2id, PBKDF2 fallback)
        ▼
   KEK (Key Encryption Key)
        │  AES-256-GCM (AAD = installId + wrapVersion)
        ▼
   DEK (32-byte Data Encryption Key)  ──►  encrypts the SQLCipher database
        │
   KDF salt ──► wrapped by HardwareKeyStore (device key)
```

### 6.2 Key Files

| File | Role |
|------|------|
| `security/KeyManager.kt` | Manages the DEK envelope. `generateAndWrapDek`, `unwrapDekWithPin` (GCM unwrap IS the PIN verification), `rewrapDekWithDek`, `deleteAll`. Envelope is one atomic JSON blob. |
| `security/SessionManager.kt` | Holds raw DEK in memory + opened SQLCipher DB. `unlock`/`unlockRecreating`/`lock`. 5-min idle timeout. Mutex-based concurrency. Wipes DEK on lock. |
| `security/HardwareKeyStore.kt` (expect) | Always-on device key: Android Keystore AES-GCM, iOS Secure Enclave ECC P-256, Desktop OS backends (MacOS Keychain, Windows DPAPI, Linux libsecret, File fallback). |
| `security/SecretCipher.kt` | Defense-in-depth: master seed + mnemonic BLOBs additionally wrapped with device hardware key (`"KRYPT" \|\| 0x01 \|\| hwEncrypt(plaintext)`) before DB write. |
| `security/Kdf.kt` (expect) | Argon2id (BouncyCastle on JVM, libsodium on iOS) |
| `security/Pbkdf2.kt` (expect) | PBKDF2-HMAC-SHA256/512 (BIP-39 seed + KDF fallback) |
| `security/AesGcm.kt` (expect) | AES-256-GCM (IV‖ct‖tag layout) |
| `security/SecureMemory.kt` (expect) | `wipe()` (compiler-resistant zeroization), `gcHint()` |
| `security/SecureMnemonicCode.kt` | BIP-39 validate/generate/toSeed on `CharArray` (never materializes phrase as immutable String) |
| `security/EntropyCombiner.kt` | Mixes CSPRNG entropy with gesture digest via HMAC-SHA-256 |
| `security/GestureEntropyAccumulator.kt` | Folds pointer samples into running SHA-256 digest |
| `security/PinBytes.kt` | `CharArray`→UTF-8 bytes without String allocation |
| `security/SessionLockNotifier.kt` | Cross-platform "session locked" signal |
| `security/MonotonicClock.kt` (expect) | Tamper-resistant clock for lockout |

### 6.3 What Protects What

| Layer | Protection |
|-------|-----------|
| DB at rest | SQLCipher (DEK). Desktop: custom `NativeSqlCipherDriver` (JNA over SQLCipher 4.17, PBKDF2-HMAC-SHA512, 256000 iter, WAL) |
| Secrets at rest | `SecretCipher` hardware-key wrap on top of SQLCipher |
| DEK at rest | AES-GCM wrapped by KEK; KEK salt wrapped by device hardware key |
| In memory | DEK + master seed + mnemonic wiped after use (`KeyProviderImpl` loan pattern, `wipe()` everywhere) |
| Offline attack | Hardware-wrapped salt → offline PIN brute-force impossible; data device-bound |
| Session | 5-min idle lock, background lock, screenshot protection (`FLAG_SECURE` / black overlay / `SecureScreen`) |
| PIN lockout | Escalating (5 attempts → 1 min, doubling to 60 min max), stored hardware-encrypted (AES-GCM, AAD-bound to install ID) |

### 6.4 PIN Verification

The PIN is **never stored as a hash**. Verification works by:
1. Deriving KEK = KDF(PIN, salt, params)
2. Attempting AES-GCM unwrap of the DEK
3. GCM authentication succeeds → correct PIN; fails → wrong PIN

---

## 7. Key Classes

### 7.1 ViewModels (`ui/viewmodel/`)

| ViewModel | Responsibility |
|-----------|----------------|
| `StartupViewModel` | Maps `PinState` → wizard step (Loading / NeedsPinSetup / NeedsPinUnlock) |
| `SetupPinViewModel` | PIN setup (length choice, enter/confirm) |
| `SetPasswordViewModel` | Password setup (enter/confirm, validation) |
| `EnterPinViewModel` | Unlock (PIN/password, lockout countdown, shake) |
| `CreateWalletViewModel` | Wallet create state machine (SETUP/PASSPHRASE/GESTURE/REVEAL, generate/restore) |
| `AccountsListViewModel` | Wallet list, grouped accounts, fiat balances, sync triggers |
| `AccountDetailsViewModel` | Account detail, address, paginated transactions |
| `SendViewModel` | Send + fee selection/presets/custom fees |
| `TransactionDetailsViewModel` | Single transaction lookup |
| `CreateAccountViewModel` | Create native account / token |
| `AddTokenViewModel` | Add token to a parent account |
| `ExportMnemonicViewModel` | Re-auth + reveal stored mnemonic |
| `ManageWalletsViewModel` | Rename/delete wallets |
| `SettingsViewModel` | Fiat currency |
| `ChangePinViewModel` | Change PIN/password (3-stage) |
| `CustomNodesViewModel` | Per-chain custom RPC node URLs |

### 7.2 UseCases (`domain/usecase/`)

| Category | Use Cases |
|----------|-----------|
| Wallet | `CreateWalletUseCase`, `GetWalletsUseCase`, `DeleteWalletUseCase`, `RenameWalletUseCase`, `GetMnemonicUseCase` |
| Account/Token | `GetAccountsUseCase`, `CreateAccountUseCase`, `AddTokenUseCase`, `CreateTokenUseCase`, `GetAccountAddressUseCase` |
| Send/Fee | `SendUseCase`, `EstimateFeeUseCase` |
| Sync | `SyncUseCase`, `SyncAccountUseCase`, `SyncManager` (per-account mutex + syncing set) |
| PIN/Security | `CheckPinStatusUseCase`, `SetupPinUseCase`, `VerifyPinUseCase`, `ChangePinUseCase`, `GetSecurityMethodUseCase`, `SetSecurityMethodUseCase`, `CredentialValidator` |

### 7.3 Repositories

| Interface (`domain/repository/`) | Impl (`data/`) |
|----------------------------------|----------------|
| `WalletRepository` | `WalletRepository` (SQLDelight) |
| `AccountRepository` | `AccountRepository` (SQLDelight) |
| `TransactionRepository` | `TransactionRepository` (SQLDelight) |
| `UtxoRepository` | `UtxoRepository` (SQLDelight) |
| `PinRepository` | `PinRepositoryImpl` (settings + KeyManager + SessionManager) |
| `KeyProvider` | `KeyProviderImpl` (master seed loan) |

### 7.4 Domain Models

- `AccountType` — sealed class: `Btc`, `Eth`, `Trx`, `Ton` (native; symbol `GRAM`); `Erc20`, `Trc20`, `TonToken` (tokens). Has `parentChain()`, `tokenContractAddress`, DB-code/params-JSON mapping.
- `ChainType` — enum for custom-node chains.
- `WalletInfo`, `AccountInfo`, `TransactionInfo`, `UtxoInfo`, `FeeEstimation`, `FeeValidator`, `CustomFeeParams`, `FiatCurrency`.

### 7.5 Providers (`providers/`)

| Provider | Chain | Notes |
|----------|-------|-------|
| `BtcProvider` | Bitcoin | UTXO, P2WPKH, mempool.space API |
| `EthProvider` / `Erc20TokenProvider` | Ethereum | EIP-1559, `EthBase` fee presets |
| `TrxProvider` / `Trc20TokenProvider` | Tron | — |
| `TonProvider` | GRAM (TON) | Full BOC/Cell serialization under `ton/boc`, `ton/types`, `ton/wallet` |

- `ProviderFactory.create(type, masterSeed, ...)` → builds `DeterministicWallet` from seed.
- `DerivationPathResolver`: BIP-44/84 paths per chain + validation.

---

## 8. Data Layer

### 8.1 Database Schema (SQLDelight)

**File:** `composeApp/src/commonMain/sqldelight/com/ultrabytecoder/coinsafebox/db/CoinSafeBoxDatabase.sq`

| Table | Columns |
|-------|---------|
| `wallets` | `id` (PK), `name`, `master_seed` (BLOB), `mnemonic` (BLOB) |
| `accounts` | `id` (TEXT PK), `wallet_id` (FK→wallets), `name`, `amount`, `type`, `address`, `account_index`, `derivation_path`, `params`, `symbol`, `parent_account_id` (FK→accounts), `token_address` |
| `utxos` | `id` (PK), `account_id` (FK→accounts), `derivation_path`, `amount`, `txid`, `vout` |
| `transactions` | `id` (TEXT PK), `account_id` (FK→accounts), `tx_hash`, `direction`, `amount`, `fee`, `timestamp`, `status`, `counterparty_address`, `block_height`, `chain_data` |

Indexes: `idx_accounts_parent`, `idx_transactions_tx_hash_account_id` (unique), `idx_transactions_account_timestamp`.

### 8.2 Access Pattern

```
DatabaseProvider (lazy accessor — throws if session locked)
  ← SessionManager implements it
  ← Repositories get queries via databaseProvider.database().coinSafeBoxDatabaseQueries
```

### 8.3 Settings Storage

`SettingsStorage` (expect) → key/value. Keys:
- `fiat_currency`, `security_method`, `pin_length`
- `pin_data` (hardware-encrypted lockout state)
- `dek_envelope`, `dek_install_id`
- `custom_node_{btc,eth,trx,ton}`
- Per-account fee prefs

### 8.4 Network Config

`NetworkConfig.testnet()` / `mainnet()` presets:
- ETH: Sepolia / mainnet
- Tron: Nile / mainnet
- TON: testnet / mainnet
- BTC: signet / mainnet (via mempool.space)

`applyCustomNodes()` overlays user node URLs.

---

## 9. UI Layer

### 9.1 Theme

- `CoinSafeBoxTheme` (Material3, dark by default)
- Custom `Color.kt` (incl. `AuroraPrimary`), `Typography.kt`

### 9.2 Screens (`ui/screens/`, 23 files)

| Screen | File |
|--------|------|
| Welcome | `WelcomeScreen.kt` |
| Choose Security Method | `ChooseSecurityMethodScreen.kt` |
| PIN Setup / Enter | `PinScreen.kt` (`PinScreenSetup` + `PinScreenEnter`) |
| Set Password | `SetPasswordScreen.kt` |
| Create Wallet Flow | `CreateWalletFlow.kt` |
| Create Wallet Setup | `CreateWalletSetupScreen.kt` |
| Passphrase | `PassphraseScreen.kt` |
| Gesture Entropy | `GestureEntropyScreen.kt` |
| Reveal Mnemonic | `RevealMnemonicScreen.kt` |
| Accounts List | `AccountsListScreen.kt` |
| Account Details | `AccountDetailsScreen.kt` |
| Send | `SendScreen.kt` |
| Transaction Sent | `TransactionSentScreen.kt` |
| Transaction Details | `TransactionDetailsScreen.kt` |
| Create Account | `CreateAccountScreen.kt` |
| Add Token | `AddTokenScreen.kt` |
| Export Mnemonic | `ExportMnemonicScreen.kt` |
| Manage Wallets | `ManageWalletsScreen.kt` |
| Settings | `SettingsScreen.kt` |
| Change PIN | `ChangePinScreen.kt` |
| Custom Nodes | `CustomNodesScreen.kt` |
| QR Scanner | `QrScanner.kt` (expect + platform actuals) |
| Token Visuals | `TokenVisuals.kt` |

### 9.3 Components & Utils

- `Numpad` (PIN pad), `TransactionItem`
- `SecureTextFieldState` (wipe-able text field backing)
- `SecureScreen` (expect — screenshot protection)
- `AmountFormatter`, `FiatFormatter`, `TimeFormatter`, `FeeFormatUtils`

---

## 10. DI (Koin)

**File:** `di/AppModule.kt`

```kotlin
fun appModule(networkConfig: NetworkConfig) = module {
    // NetworkConfig (raw + with custom nodes)
    // Security: KeyManager, CoroutineScope, SessionManager, DatabaseProvider
    // Repositories: Account, Utxo, Transaction, Wallet, KeyProvider
    // UseCases: CreateWallet, GetMnemonic, GetAccounts, CreateAccount, AddToken,
    //           CreateToken, EstimateFee, Send, GetAccountAddress, GetWallets,
    //           DeleteWallet, RenameWallet, Sync, SyncAccount
    // PIN: PinRepository, CheckPinStatus, GetSecurityMethod, SetSecurityMethod,
    //      SetupPin, VerifyPin, ChangePin
    // Fiat: RemoteFiatQuoteProvider
}
```

Per-platform `PlatformModule.kt` provides `DatabaseDriverFactory` + `SettingsStorage`.

---

## 11. Architectural Observations

- **Clean architecture:** `ui` (Compose + ViewModels) → `domain` (use cases, models, repository interfaces) → `data` (SQLDelight repos, settings) + `providers` (chain logic) + `security`.
- **Koin DI:** `appModule` (shared) + per-platform `platformModule`.
- **expect/actual** heavily used for platform crypto, storage, DB driver, screenshot protection, idle hooks.
- **Security-first design:** secrets never materialize as immutable Strings (CharArray + wipe everywhere), envelope encryption, hardware key binding, memory zeroization, tamper-resistant lockout, screenshot protection, session locking on background/idle.
- **PIN is set up BEFORE wallet creation** — the startup wizard ensures the DEK exists and the DB is open before any wallet data is written.
