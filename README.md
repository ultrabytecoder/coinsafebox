# CoinSafeBox

Multiplatform cryptocurrency wallet for Android and iOS, built with Kotlin Multiplatform and Compose Multiplatform.

## Features

- **Multi-wallet support** — create, import, and manage multiple wallets
- **Multi-chain** — Bitcoin, Ethereum, Tron, and TON with native and token support
- **Send & receive** — QR code scanning, fee estimation, and transaction history
- **Secure storage** — encrypted mnemonic phrases and keys
- **Shared UI** — single Compose codebase for Android and iOS

## Supported Networks

| Network | Native | Tokens |
|---------|--------|--------|
| Bitcoin (BTC) | BTC | — |
| Ethereum (ETH) | ETH | USDC, LINK, WETH, DAI, USDT, WBTC |
| Tron (TRX) | TRX | USDT, USDC, BTT, WETH |
| TON | TON | — |

Both mainnet and testnet are supported.

## Tech Stack

- **Kotlin Multiplatform** — shared business logic
- **Compose Multiplatform** — shared UI
- **SQLDelight** — local database
- **Koin** — dependency injection
- **Ktor** — networking
- **Kotlinx Serialization** — JSON handling

## Project Structure

```
composeApp/src/
├── commonMain/     # Shared code (domain, data, UI)
├── androidMain/    # Android-specific implementations
└── iosMain/        # iOS-specific implementations
iosApp/             # iOS app entry point (SwiftUI)
```

## Build

### Android

```shell
./gradlew :composeApp:assembleDebug
```

### iOS

Open `iosApp/` in Xcode and run from there.

### Build Variants

- `productionTestnet` — connects to testnet networks
- `productionMainnet` — connects to mainnet networks

## Supported Platforms

| Platform | Minimum Version | Notes |
|----------|-----------------|-------|
| Android  | 7.0 (API 24)    | Targets API 36 |
| iOS      | 18.2            | `arm64` (device) and `simulatorArm64` (Apple Silicon) |
| Linux    | —               | Desktop, packaged as `.deb` and `.rpm` |
| macOS    | —               | Desktop, packaged as `.dmg` |
| Windows  | —               | Desktop, packaged as `.msi` |

Desktop is built with Compose Desktop (JVM 17) and runs on all three OSes.

## Requirements

- Android SDK 24+ (targets up to 36)
- Android Studio with KMP plugin
- Xcode (for iOS builds)
- Kotlin 2.3.0

## License

[MIT](LICENSE)
