# CoinSafeBox Android — Test Automations

UI-level automation for the CoinSafeBox Android app, driven over `adb` +
`uiautomator` against an emulator (or any USB device). Built from a real
end-to-end session of onboarding a fresh install and creating accounts.

## Files

| File | Purpose |
|---|---|
| `ui.py` | Low-level helper: UI dump/parse, wait-for, tap-by-text, PIN entry, screenshots. Also a CLI for manual poking. |
| `emulator.sh` | Start/stop/status the test AVD, wait for full boot. |
| `build-install.sh` | Build the debug APK for a network flavor, install, launch. |
| `test_create_account.py` | E2E test: onboarding (or unlock) → create account(s) → verify list entry, derivation path, address. |
| `test_resume_wallet_flow.py` | E2E test: create-wallet flow interrupted by a session lock (app backgrounded) → re-auth → flow resumes at the right step (REVEAL clamps to PASSPHRASE), wallet name preserved. |
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
- **`adb: more than one device`**: set `KK_DEVICE` to the emulator's serial
  (`emulator-5554`) when a phone is also connected.

## Extending

- New chain: add an entry to `TYPES` in `test_create_account.py`
  (grid label, address regex, path template).
- Token accounts (ERC20/TRC20): they go through the same New Account screen
  but may navigate to a parent-selection step; script that branch separately.
- Add assertions the same way: `check(name, ok, detail)` collects results.
