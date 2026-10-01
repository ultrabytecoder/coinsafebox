# CoinSafeBox Desktop — Test Automations

Black-box UI automation for the CoinSafeBox **desktop** app (Compose for
Desktop / Skiko on X11). Mirrors the Android suite: it drives the real
packaged app (`dist/CoinSafeBox-<net>.jar`), reads state from screenshots +
OCR, and injects input with the X11 **XTEST** extension.

There is no uiautomator-equivalent UI tree on desktop (Skia renders to a
bitmap), so the state model is: **window capture → OCR → tap by text/geometry**.

## Why a virtual display

On a Wayland session the app renders via XWayland, and XTEST button events are
only delivered while the window holds *compositor* focus — which cannot be
forced from the X11 side (EWMH `_NET_ACTIVE_WINDOW` from X11 is ignored,
XTEST motion is dropped). So by default the suite starts its own **Xvfb**
display, where XTEST works unconditionally and deterministically, and the
user's desktop is never disturbed.

Set `KK_USE_XVFB=0 KK_DISPLAY=:0` to target a real X display instead
(best-effort on Wayland hosts; fine on a pure-X11 session).

## Files

| File | Purpose |
|---|---|
| `ui.py` | Low-level helper: window find/raise, capture, OCR (bright+dim+accent pipelines), tap-by-text, numpad geometry taps, accent-digit taps, QR detection/decode, screenshots. Also a CLI for manual poking. |
| `desktop-app.sh` | Start/stop/status the app with an **isolated home** (`-Duser.home`) and a managed Xvfb display. Never touches `~/.coinsafebox`. |
| `setup.sh` | One-time: `.venv` with `python-xlib`+`pillow`(+`pyzbar`), checks tesseract/Xvfb/XTEST. |
| `test_create_account.py` | E2E test: onboarding (or unlock) → create account(s) → verify list row, derivation path, chain-specific address, and that the account QR code is displayed. |
| `screenshots/` | Screenshots written by the helpers (gitignored). |

## Prerequisites

- `tesseract-ocr` and `xvfb` — `sudo apt install tesseract-ocr xvfb`
- `python3-venv`
- `libzbar0` (optional) — enables the best-effort QR **decode** on the
  details screen. The QR **presence** check does not need it.
- Project dependencies available to Gradle (same as a normal build); the
  suite builds the jar itself if `dist/CoinSafeBox-<net>.jar` is missing.

## Quick start

```bash
cd coinsafebox/test-automations/desktop

./setup.sh                        # once: venv + dependency checks
./test_create_account.py          # fresh run: full onboarding + one BTC account
```

Useful variants:

```bash
./test_create_account.py --types BTC,BTC     # second account -> index increment
./test_create_account.py --types BTC,ETH     # ETH: 0x address check
./test_create_account.py --pin 246810        # if the E2E home was set up with another PIN
./test_create_account.py --keep              # leave app + Xvfb running for manual poking
KK_NETWORK=mainnet ./test_create_account.py  # mainnet jar
```

Manual poking (app must be up, e.g. via `./test_create_account.py --keep`
or `./desktop-app.sh up --fresh`):

```bash
./.venv/bin/python ui.py           # all OCR'd words with boxes
./.venv/bin/python ui.py texts     # visible text, line per line
./.venv/bin/python ui.py find "Create Wallet"
./.venv/bin/python ui.py tap "Create Wallet"
./.venv/bin/python ui.py digit 5   # on-screen numpad (geometry-based)
./.venv/bin/python ui.py accent 6  # accent-colored digit (PIN length options)
./.venv/bin/python ui.py shot name # screenshots/name.png
./.venv/bin/python ui.py grid      # numpad grid detection (diagnostics)
./.venv/bin/python ui.py qr        # detect the QR (box) + best-effort decode
```

`test_create_account.py` exits 0 only if every check passes; it prints a
summary and saves `screenshots/final.png`.

## How it works (the non-obvious parts)

- **Isolation.** The app is launched with `-Duser.home=$KK_E2E_HOME`, so the
  app data dir (`<home>/.coinsafebox`) — DB, settings, file key backend — is
  fully isolated per test environment. `up --fresh` wipes it. Kill is matched
  by the `-Duser.home` marker, so a user-launched real instance is never
  touched. The keyring device key (libsecret) is global but only wraps fresh
  data in a fresh DB, so it cannot leak state between runs.
- **Window targeting.** The window is found by `WM_NAME = CoinSafeBox`
  (optionally `_NET_WM_PID`-matched). Geometry is re-queried on every action
  (moves/resizes are fine). A minimized (unmapped) window cannot be captured
  (`GetImage` → BadMatch) — `ensure_viewable()` restores it with a MapRequest
  before every grab/click.
- **Capture.** Per-window `GetImage` (BGRX), with a fallback to grabbing the
  window's region from the **root** when the server rejects window-level
  capture. Xvfb returns `BadMatch` for a window that extends past the screen
  edge (the app's 780px-tall window vs. a short virtual screen) or for a
  depth-32 window on a 24-bit screen, so the Xvfb screen is sized
  1280×1000 and the root-region path is the reliable one there.
- **OCR pipeline.** The app is dark-themed: primary text is white, secondary
  text gray, and accent elements (e.g. the PIN-length option digits) are the
  theme's *primary color* (purple) — none of which survive plain grayscale.
  The pipeline extracts **bright ink** (`lum > 190`), falls back to a dimmer
  threshold (`> 120`) for secondary text, and up-samples 3x before tesseract
  (psm 11, TSV word boxes grouped into lines). Multi-word labels match at
  line granularity; single-character needles match a whole word exactly
  (so `6` does not hit `6-digit`).
- **Accent digits.** The PIN-length options render their digits in the
  primary color, invisible to the luminance pipeline. `tap_accent_digit`
  masks by chroma (max−min of RGB > 60), clusters the glyphs left-to-right,
  and identifies each with tesseract psm 8 — falling back to counter
  topology (one hole = `6`, two = `8`) when the rounded font defeats OCR.
- **Input.** `warp_pointer` + XTEST ButtonPress/Release (and KeyPress/Release
  for `type_text`). On Xvfb this is 100% deterministic; see "Why a virtual
  display" for the XWayland caveat.
- **Numpad.** The PIN numpad is a 3×4 grid of text keys. `tap_digit` detects
  single-character word boxes in the lower half, clusters them into columns
  and rows, and taps the grid cell for the requested digit — so per-glyph OCR
  confusions (`1`→`I`, `7`→`T`, …) never matter.
- **The `+` button** (add account) has no text (icon-only). Strategy order:
  OCR of the `+` glyph in the top bar, then a white-pixel cluster scan of the
  top bar's right side, then a geometry fallback anchored on the wallet-name
  row.
- **QR code (details screen).** The account QR renders as white modules on a
  near-black card. `find_qr()` finds the **densest bright square** (2x
  downsample + prefix sum) — text/numpad never reach that density — and
  returns its box, which is what the test asserts. `decode_qr()` is a
  best-effort pyzbar decode; the app draws **non-standard finder patterns**
  (not the usual 7×7), which standard decoders reject, so a decode is
  reported but not required.

## Known gotchas

- **Wayland + real display**: unreliable input (see "Why a virtual display").
  The suite defaults to Xvfb for a reason.
- **First run builds the jar** if missing (Gradle, a few minutes).
- The app talks to real testnet/mainnet RPC; tests are network-dependent
  (sync indicators may appear while accounts sync).
- Address OCR on the details screen is noisy (long token strings are
  OCR-hostile), so the address assertion only checks a chain-specific prefix
  + length (case-insensitive), not the exact value. The QR **presence** check
  is the stronger signal that the details screen rendered the account.
- The app's QR uses non-standard finder patterns, so `decode_qr()` usually
  returns `None`. If you want the decoded payload verified against the
  address, the app's QR renderer would need to emit standard finders.
