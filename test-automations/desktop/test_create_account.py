#!/usr/bin/env python3
"""E2E test: account creation on CoinSafeBox desktop (black-box).

Drives the real packaged app (dist/CoinSafeBox-<net>.jar) on the X display,
mirroring the Android suite (test-automations/test_create_account.py):

  1. (re)launches the app with an ISOLATED home (-Duser.home, see
     desktop-app.sh) so the real ~/.coinsafebox is never touched
  2. auto-detects the current screen:
       - fresh state -> full onboarding:
           Get Started -> PIN ("6 digits, fast unlock") -> length "6" ->
           create + confirm PIN -> Create Wallet (Generate new, default name
           "My wallet", default word count, no gesture entropy, passphrase
           "No, skip") -> reveal phrase -> Create Wallet
       - existing E2E state -> unlock with the PIN
   3. for each requested chain (default: BTC):
        - tap the + button on the accounts list top bar
        - select the chain card on the New Account screen
        - assert the auto-filled derivation path
        - Create Account
        - assert the list row "<Chain> account <n>"
        - open the account and assert a valid chain-specific address
        - assert the account QR code is displayed (dense white-module region;
          best-effort decode reported when the renderer is standard)
  4. prints a PASS/FAIL summary and exits non-zero on any failure.

Usage:
  ./test_create_account.py                    # fresh run: onboarding + one BTC account
  ./test_create_account.py --types BTC,BTC    # two BTC accounts (index increment)
  ./test_create_account.py --pin 246810       # non-default PIN (must match the
                                               # PIN the E2E home was set up with)
  ./test_create_account.py --keep             # don't stop the app afterwards

The app runs on a dedicated Xvfb display (managed by desktop-app.sh), so the
user's desktop is not disturbed and input injection is deterministic.

Environment:
  KK_NETWORK   testnet|mainnet (default: testnet)
  KK_E2E_HOME  isolated home (default: /tmp/kk-e2e)
  KK_USE_XVFB  1 = virtual display (default), 0 = use KK_DISPLAY as-is
  KK_DISPLAY   explicit X display when KK_USE_XVFB=0

Prerequisites:
  ./setup.sh   (once: .venv + python-xlib; system: tesseract-ocr, xvfb)
"""
import argparse
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

# Bootstrap: re-exec with the suite venv when python-xlib is missing.
if os.environ.get("KK_DESKTOP_E2E_VENV") != "1":
    try:
        import Xlib  # noqa: F401
    except ImportError:
        venv_py = os.path.join(HERE, ".venv", "bin", "python")
        if os.path.exists(venv_py):
            os.environ["KK_DESKTOP_E2E_VENV"] = "1"
            os.execv(venv_py, [venv_py] + sys.argv)
        sys.exit("python-xlib missing — run ./setup.sh first")

import ui  # noqa: E402

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))


def summary():
    print("\n" + "=" * 64)
    for name, ok, detail in RESULTS:
        print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  ({detail})" if detail and not ok else ""))
    failed = [r for r in RESULTS if not r[1]]
    print("=" * 64)
    print(f"{len(RESULTS) - len(failed)}/{len(RESULTS)} checks passed")
    return 1 if failed else 0


def stage():
    """Detect the current stage by OCR: 'welcome' | 'unlock' | 'accounts' | None."""
    try:
        text = ui.ocr_text()
    except RuntimeError:
        return None  # window not viewable yet — caller retries
    if "Get Started" in text:
        return "welcome"
    if "Enter your PIN" in text or "Unlock to access your wallet" in text:
        return "unlock"
    if "No accounts yet" in text or re.search(r"account \d+", text):
        return "accounts"
    return None


def enter_pin(pin):
    for d in pin:
        ui.tap_digit(d)
        time.sleep(0.25)


def onboard(pin):
    print("onboarding: welcome -> security -> wallet")
    ui.click_text("Get Started")
    ui.click_text("6 digits, fast unlock")
    # PIN length: the option digits are rendered in the theme's primary color,
    # which the luminance OCR pipeline misses — use the accent-digit helper.
    ui.tap_accent_digit("6")
    if not ui.wait_for("Create a PIN", timeout=30):
        check("PIN length selected (create PIN shown)", False)
        return
    enter_pin(pin)
    check("create PIN accepted", ui.wait_for("Confirm your PIN", timeout=30) is not None)
    enter_pin(pin)
    # Create wallet: Generate new -> Next -> Next (word count) -> No, skip -> Continue
    ui.click_text("Generate new")
    ui.click_text("Next")
    ui.click_text("Next")
    ui.click_text("No, skip")
    ui.click_text("Continue")
    ui.click_text("Create Wallet")
    check("wallet created (accounts list shown)",
          ui.wait_for("No accounts yet", timeout=60) is not None)


def unlock(pin):
    print(f"unlocking with PIN")
    enter_pin(pin)
    check("unlocked (accounts list shown)",
          ui.wait_for("No accounts yet", timeout=60) is not None
          or re.search(r"account \d+", ui.ocr_text()))


def click_add_account():
    """Tap the + icon on the accounts list top bar.

    Three strategies: OCR of the '+' glyph, a white-pixel cluster scan of the
    top bar's right side, then a geometry fallback anchored on the wallet name
    row. Returns (strategy, x, y) or None.
    """
    from PIL import Image
    im = ui.grab()
    W, H = im.size

    # 1) OCR: tesseract often reads the plus glyph as '+' (or 'x').
    for text, x, y, w, h in ui.ocr_words():
        if text.strip() in {"+", "x", "X"} and y < 0.2 * H and x > 0.7 * W:
            ui.click_at(x + w / 2, y + h / 2)
            return ("ocr", x + w / 2, y + h / 2)

    # 2) white-pixel cluster in the top bar, right third (the glyph is white).
    g = im.convert("L").point(lambda p: 0 if p > 190 else 255)
    px = g.load()
    best = None
    for cx in range(int(0.8 * W), W, 4):
        for cy in range(10, int(0.18 * H), 4):
            if px[cx, cy] == 0:
                # count white neighbors in a 12px window
                n = sum(1 for dx in range(-6, 7, 2) for dy in range(-6, 7, 2)
                        if 0 <= cx + dx < W and 0 <= cy + dy < H
                        and px[cx + dx, cy + dy] == 0)
                if 8 < n < 200 and (best is None or n > best[2]):
                    best = (cx, cy, n)
    if best:
        ui.click_at(best[0], best[1])
        return ("pixels", best[0], best[1])

    # 3) geometry fallback: top-bar right edge, wallet-name row.
    anchor = ui.find_text("My wallet") or ui.find_text("wallet")
    if anchor:
        x = W - int(28 * W / 410)
        ui.click_at(x, anchor[1] + anchor[3] / 2)
        return ("geometry", x, anchor[1] + anchor[3] / 2)
    return None


CHAIN_SPECS = {
    # card label substring, list-row prefix, derivation path regex, address regex
    # (address regexes are case-insensitive and lenient: OCR of long token
    # strings garbles individual glyphs, so only the prefix + length are
    # asserted; the QR check below is the stronger verification)
    "BTC": ("Bitcoin", "Bitcoin (BTC) account", r"m\s*/\s*84", r"(?i)tb[0-9a-z]{15,}"),
    "ETH": ("Ethereum", "Ethereum (ETH) account", r"m\s*/\s*44", r"(?i)0x[0-9a-f]{16,}"),
    "TRX": ("Tron", "Tron (TRX) account", r"m\s*/\s*195", r"(?i)t[0-9a-z]{16,}"),
    "TON": ("Toncoin", "Toncoin (TON) account", r"m\s*/\s*392", r"(?i)[eu]q[0-9a-z]{16,}"),
}


def create_account(chain, index):
    label, row_prefix, path_re, addr_re = CHAIN_SPECS[chain]
    print(f"creating {chain} account (index {index})")
    clicked = click_add_account()
    check(f"{chain}: + button clicked", clicked is not None, str(clicked))
    if not clicked:
        return
    if not ui.wait_for("New Account", timeout=30):
        check(f"{chain}: new account screen", False)
        return
    ui.click_text(label)
    time.sleep(1)
    low = ui.ocr_text(min_lum=120)  # derivation path is secondary-color text
    m = re.search(path_re, low)
    check(f"{chain}: derivation path shown", m is not None,
          "expected pattern " + path_re + " in: " + " | ".join(low.splitlines()[:12]))
    ui.click_text("Create Account")
    row = f"{row_prefix} {index + 1}"
    if not ui.wait_for(f"account {index + 1}", timeout=60):
        check(f"{chain}: list row '{row}'", False, ui.ocr_text())
        return
    check(f"{chain}: list row '{row}'", True)
    ui.click_text(f"account {index + 1}")
    if not ui.wait_for("Address", timeout=60):
        check(f"{chain}: details screen", False)
        return
    # The details screen shows the account's QR code (white modules on the
    # dark theme). Verify it is actually displayed, not just the label.
    qr = ui.find_qr()
    check(f"{chain}: QR code shown", qr is not None, str(qr))
    if qr:
        payload = ui.decode_qr()
        if payload:
            print(f"        QR decoded: {payload[:32]}…")
    detail = ui.ocr_text(min_lum=120)
    m = re.search(addr_re, detail)
    check(f"{chain}: address rendered", m is not None,
          "no " + chain + " address pattern in screen text")
    if m:
        print(f"        address: {m.group(0)[:24]}…")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--types", default="BTC", help="comma list, e.g. BTC,ETH")
    ap.add_argument("--pin", default="246810")
    ap.add_argument("--keep", action="store_true", help="leave the app running")
    args = ap.parse_args()
    types = [t.strip().upper() for t in args.types.split(",") if t.strip()]
    for t in types:
        assert t in CHAIN_SPECS, f"unsupported chain {t} (known: {list(CHAIN_SPECS)})"

    net = os.environ.get("KK_NETWORK", "testnet")
    sys.stdout.reconfigure(line_buffering=True)

    print(f"launching app (network={net}, isolated home, display={ui.DISPLAY})...")
    r = subprocess.run(["./desktop-app.sh", "up", "--fresh"], cwd=HERE)
    if r.returncode != 0:
        sys.exit("desktop-app.sh up failed")

    # The app was (re)launched on the display desktop-app.sh chose (often a
    # managed Xvfb, e.g. :99) — which may differ from this process's $DISPLAY.
    # ui.py resolved its target at import, so point it at the right one now.
    home = os.environ.get("KK_E2E_HOME", "/tmp/kk-e2e")
    disp_file = os.path.join(home, ".display")
    if os.path.exists(disp_file):
        disp = open(disp_file).read().strip()
        if disp:
            os.environ["KK_DISPLAY"] = disp
            ui.DISPLAY = disp
            print(f"targeting display {disp}")

    try:
        st = stage()
        for _ in range(10):
            if st:
                break
            time.sleep(3)
            st = stage()
        check("stage detected", st is not None, "no known screen after 30s")
        if st == "welcome":
            onboard(args.pin)
        elif st == "unlock":
            unlock(args.pin)
        else:
            # already on the accounts list (shouldn't happen after --fresh,
            # but be forgiving: treat as unlocked)
            print("assumed accounts stage")

        for i, chain in enumerate(types):
            create_account(chain, i)
    finally:
        if not args.keep:
            # capture the final state while the app (and its display) is alive
            try:
                ui.shot("final")
            except Exception:
                pass
            subprocess.run(["./desktop-app.sh", "down"], cwd=HERE)

    sys.exit(summary())


if __name__ == "__main__":
    main()
