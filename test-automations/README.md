 # CoinSafeBox Android — Test Automations

UI-level automation for the CoinSafeBox Android app, driven over `adb` +
`uiautomator` against an emulator (or any USB device). Built from a real
end-to-end session of onboarding a fresh install and creating accounts.

> **Desktop app:** see [`desktop/`](desktop/README.md) — black-box E2E for the
> Compose Desktop build (screenshot + OCR + XTEST on a managed Xvfb display),
> mirroring the flows below.

## Files

| File | Purpose |
|---|---|
| `ui.py` | Low-level helper: UI dump/parse, wait-for, tap-by-text, PIN entry, screenshots. Also a CLI for manual poking. |
| `emulator.sh` | Start/stop/status the test AVD, wait for full boot. |
| `build-install.sh` | Build the debug APK for a network flavor, install, launch. |
| `test_create_account.py` | E2E test: onboarding (or unlock) → create account(s) → verify list entry, derivation path, address. |
| `test_resume_wallet_flow.py` | E2E test: create-wallet flow interrupted by a session lock (app backgrounded) → re-auth → flow resumes at the right step (REVEAL clamps to PASSPHRASE), wallet name preserved. |
| `test_readonly_wallet.py` | E2E test: create wallet + account(s) (auto) → Remove Master Key (cancel + confirm, "Read-only" badge) → verify the read-only account still shows address/QR/balance (data intact). Fund-independent. |
| `test_unlock_reject.py` | E2E test: wrong PIN on the unlock screen is rejected with a remaining-attempts count, the app stays locked, and the correct PIN still unlocks. Negative security path. |
| `test_export_mnemonic.py` | E2E test: Manage Wallets → Export Mnemonic requires PIN re-auth; wrong PIN rejected, correct PIN shows the phrase MASKED, "Show" reveals the phrase read at creation, copy-to-clipboard enabled. |
| `test_send_guards.py` | E2E test: send-screen validation guards on an unfunded account (empty send no-op, amount/recipient typed on the app's on-screen keyboards, fee-estimation error, zero-balance broadcast guarded). Fund-independent. |
| `test_readonly_send_guard.py` | E2E test: read-only wallet send requires the recovery phrase; a wrong phrase is rejected by the local address-match guard before any on-chain op. Fund-independent. |
| `test_restore_wallet.py` | E2E test: restore a wallet from an existing 12-word phrase (Way B) — restored wallet derives the SAME BTC address as the original (seed actually imported); a corrupted phrase (bad BIP-39 checksum) is rejected and creates no wallet. |
| `test_manage_wallets.py` | E2E test: Manage Wallets surface — create a second wallet (custom name via system IME), rename it (row menu + dialog), wallet switcher independence, delete + confirm. |
| `screenshots/` | Screenshots written by the helpers (`shot`, `final`, `stuck`). |

## Prerequisites

- Android SDK with `adb`, `emulator`, `avdmanager` (this host: `~/Android/Sdk`).
- An AVD with `x86_64` system image. Default here: `Medium_Phone`
  (Android 16 / API 36, `google_apis_playstore`, 1080x2400).
- KVM (`/dev/kvm`) for usable emulator performance.
- Project dependencies available to Gradle (same as a normal build).

Everything talks to one device, selected by `KK_DEVICE` (default
`emulator-5554`). The AVD name is `KK_AVD` (default `Medium_Phone`).

## Quick start

```bash
cd coinsafebox/test-automations

./emulator.sh up                 # start Medium_Phone windowed, wait for boot
./build-install.sh               # build testnet debug APK, install, launch
./test_create_account.py --fresh # full E2E: onboarding + create BTC account
```

Useful variants:

```bash
./emulator.sh up --headless      # CI / no display (see "Viewing the emulator")
./emulator.sh list-avds
./build-install.sh mainnet       # mainnet flavor (pkg ...coinsafebox.mainnet)
./test_create_account.py --types BTC,BTC   # second account -> index increment
./test_create_account.py --types BTC,ETH,TRX
./test_create_account.py --pin 246810      # if the app was set up with another PIN
```

`test_create_account.py` exits 0 only if every check passes; it prints a
summary table and saves a `screenshots/final.png`.

## What the E2E test covers

1. **Launch** the app (`am start -n <pkg>/com.ultrabytecoder.coinsafebox.MainActivity`).
2. **Stage detection** — reads the UI and branches:
   - `welcome` (fresh install) → full onboarding:
     Get Started → choose **PIN** → length **6** → enter + confirm PIN →
     Create wallet (**Generate new**, default name, default word count, no
     gesture entropy, passphrase **No, skip**) → reveal phrase → Create Wallet.
   - `unlock` (existing app) → enter the PIN.
   - `accounts_list` → skip straight to account creation.
3. **Per requested chain** (BTC/ETH/TRX/TON):
   - tap **Add Account** → New Account screen;
   - select the chain card;
   - read the auto-filled **derivation path** and assert it equals the
     expected template with the next 0-based index
     (BTC: `m/84'/1'/{i}'`, ETH: `m/44'/60'/0'/0/{i}`);
   - tap **Create Account**;
   - assert the list shows `<Chain> account <n>`;
   - open the account and assert a **valid address** is rendered
      (BTC: `tb1…` bech32, ETH: `0x`+40 hex, TRX: `T…`, TON: `EQ/UQ…`).

## What the read-only wallet E2E covers

`test_readonly_wallet.py` — always a fresh install (a clean single-wallet state
keeps the Manage Wallets list unambiguous). Fund-independent: it verifies the
read-only feature at the view layer, so no testnet funding is needed. The script
creates the wallet and the account(s) by itself.

1. Onboard (PIN) → create the wallet by generating a new phrase.
2. Create one account per chain; verify a valid address is rendered.
3. Remove Master Key:
   - **CANCEL**: dialog dismissed, wallet stays full (no "Read-only" badge).
   - **CONFIRM**: key wiped, a "Read-only" badge appears, and the wallet menu
     item becomes "Read-only (key removed)".
4. Verify the now read-only account still shows its address, the Address/QR
   section and its balance (read-only means view-only, not blank), and that the
   address is unchanged (data intact).

Note: the read-only SEND path's phrase field uses the app's custom on-screen
keyboard (deliberately out of the IME focus chain). `test_readonly_send_guard.py`
now automates the guard half of that flow (wrong phrase rejected by the local
address-match check) by tapping the keyboard keys — see `ui.type_onscreen`.
The full sign-and-broadcast half still needs testnet funding and remains
manual (see `improvement-plans/readonly-wallet-e2e-manual.md`, E2E-3/E2E-9).

## Manual test procedure (what the script automates)

For reference, or when debugging by hand — on a 1080x2400 screen:

1. Fresh install → **Welcome**: tap "Get Started".
2. **Security**: tap the PIN card ("6 digits, fast unlock").
3. **Choose PIN length**: tap "6".
4. **Create a PIN / Confirm your PIN**: tap the numpad digits twice.
5. **Create Wallet**: "Generate new" (default) → "Next" → "Next" (phrase
   length) → passphrase "No, skip" → "Continue" → "Create Wallet".
6. **Accounts** (empty): tap the **+** ("Add Account") in the top bar.
7. **New Account**: tap a chain card (e.g. "Bitcoin (BTC)") → derivation
   path auto-fills → tap "Create Account".
8. Verify the list row `Bitcoin (BTC) account 1`, open it, verify the
   `tb1…` address. Repeat for a second account → `account 2`, path index 1.

## `ui.py` as a manual debugging tool

```bash
python3 ui.py                 # list all labeled/clickable nodes with tap coords
python3 ui.py texts           # all visible text
python3 ui.py find "Derivation"   # locate a node
python3 ui.py tap 540 2233    # raw coordinate tap
python3 ui.py shot mystep     # screenshots/mystep.png
```

## Troubleshooting (gotchas from real runs)

- **Emulator not visible on screen**: it was started with `-no-window`.
  `./emulator.sh kill && ./emulator.sh up` restarts it windowed. Or attach the
  already-running instance in Android Studio (Device Manager) without killing.
- **First `uiautomator dump` after a navigation is stale/empty**: the UI must
  be idle; right after a screen change the dump can return the previous
  screen or 0 bytes. `ui.py` re-dumps on every read and `wait_for()` polls —
  always wait for the *expected* label of the next screen before acting,
  never use fixed sleeps alone.
- **`uiautomator dump` blocks until the UI is fully idle** — on slow AVDs
  (few vCPUs, cold disk, busy host) that is 10–30s *per dump*, every time.
  Consequences the scripts already handle: `wait_for()` treats `timeout` as a
  total deadline (≥ 3× one dump), `enter_pin()` does ONE dump to locate all
  numpad digits then taps in sequence, and the test reconfigures stdout to
  line-buffering so a slow run is visible instead of looking hung. If a run
  still seems stuck, time a single dump:
  `time adb shell uiautomator dump /sdcard/ui.xml`.
- **Tapping a card**: Compose exposes clickable elements as parent
  `android.view.View` nodes with empty text; the visible label is a child
  `TextView`. Match the label and tap its center — the event lands inside
  the clickable parent. Don't try to tap the empty parent by guessing.
- **Coordinates change with resolution**: the 1080x2400 numbers above are
  only for manual debugging. Scripts resolve nodes by text each run.
- **Digit "6" ambiguity**: the PIN length screen has a "6" option and other
  screens contain "6" inside longer strings — that's why digit/option taps
  use *exact* matching (`find(..., exact=True)`).
- **Release APKs need signing**: automation uses the **debug** variant
  (`assembleProductionTestnetDebug`) which installs without a keystore. The
  release pipeline (`build-and-deploy.sh` + `signing/sign.sh`) is for
  distribution, not for this testing.
- **App stuck in an unexpected stage**: the test dumps the UI, saves
  `screenshots/stuck.png` and exits 1 — inspect both.
- **Password security method**: only the PIN method is scripted. If the app
  was set up with a password, use `--fresh` to reset, or extend `unlock()`.
- **`adb input text` is unreliable while Gboard's IME window is on screen**
  (this Android 16 / API 36 AVD): while the IME window is up — even a STALE
  one from a disabled Gboard, which lingers with a full-screen touchable
  region — the app ignores injected character input (the dialog's input
  channel acks the events, `responsive=true`, but Compose drops them) and
  taps inside its region are eaten. `ime hide` does NOT clear the stale
  window; `am force-stop com.google.android.inputmethod.latin` does. The
  tests therefore call `ui.ime_disable()` at start (disable + force-stop)
  and `ui.ime_enable()` at the end; `ui.ime_set_text()` re-kills any IME
  window (`ensure_no_ime_window`) before every typing round, and the tests
  call it again before tapping buttons that sit under the old window.
- **App locks its session when backgrounded**: any test that presses HOME
  (or otherwise backgrounds the app) must re-enter the PIN afterwards
  (`ui.enter_pin`). `test_unlock_reject.py` relies on exactly this.
- **`uiautomator` XML escapes attribute values**: labels containing `&`
  (e.g. the send screen's "Sign & Send" button) come back as `&amp;`.
  `ui.py` unescapes in `nodes()`, so `find("Sign & Send")` works — don't
  bypass `nodes()` with raw regex text matching.
- **Fee estimation on an unfunded account is racy on this AVD**: live
  fee-rate re-fetches re-run the estimation and can clear/re-set the
  "No UTXOs available for fee estimation" error, so `test_send_guards.py`
  treats that sub-check as INFO (non-fatal) and re-triggers the
  estimation once before giving up. The binding send guards (no broadcast
  on zero balance) remain hard checks.
- **App bug found & fixed by these tests**: the read-only send flow was
  unreachable — `SendViewModel.isReadOnly` was a
  `stateIn(viewModelScope, SharingStarted.Lazily, false)` flow that the
  send screen never collected, so `requestSend` always read `false`,
  skipped the mnemonic-prompt branch, and the resulting error was invisible
  (`sendError` only renders in the MNEMONIC_PROMPT phase). Fixed by making
  `isReadOnly` a plain `MutableStateFlow` kept in sync with the wallet
  (see `SendViewModel.kt`); `test_readonly_send_guard.py` covers the flow.
- **`adb: more than one device`**: set `KK_DEVICE` to the emulator's serial
  (`emulator-5554`) when a phone is also connected.

## Extending

- New chain: add an entry to `TYPES` in `test_create_account.py`
  (grid label, address regex, path template).
- Token accounts (ERC20/TRC20): they go through the same New Account screen
  but may navigate to a parent-selection step; script that branch separately.
- Add assertions the same way: `check(name, ok, detail)` collects results.
