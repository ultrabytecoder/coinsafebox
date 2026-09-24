#!/usr/bin/env python3
"""E2E test: new-account creation on CoinSafeBox Android.

Drives the real UI (adb + uiautomator, see ui.py) on the emulator:
  1. launches the app
  2. auto-detects the current screen and handles:
       - fresh install  -> full onboarding (security PIN -> create wallet)
       - existing app   -> PIN unlock
  3. for each requested chain, creates a new account from the New Account
     screen, then verifies:
       - the account appears in the list with the expected name
       - the default derivation path is present and its account index
         equals the (0-based) position of this account for that chain
       - the account details screen shows a valid chain-specific address
  4. prints a PASS/FAIL summary and exits non-zero on any failure.

Usage:
  ./test_create_account.py                     # fresh-ish run: create one BTC account
  ./test_create_account.py --types BTC,ETH     # one account per chain
  ./test_create_account.py --types BTC,BTC     # two BTC accounts (index increment)
  ./test_create_account.py --fresh             # uninstall first (full onboarding path)
  ./test_create_account.py --pin 246810        # non-default PIN (must match the
                                               # PIN the app was set up with)

Environment:
  KK_DEVICE  adb serial (default: emulator-5554)

Assumptions / limits:
  - testnet debug build (package com.ultrabytecoder.coinsafebox.testnet).
  - security method is PIN (password-method unlock is not scripted).
  - wallet name uses the default "My wallet" (prefilled by the app).
"""
import argparse
import os
import re
import subprocess
import sys
import time

import ui

PACKAGE = "com.ultrabytecoder.coinsafebox.testnet"
ACTIVITY = "com.ultrabytecoder.coinsafebox.MainActivity"

# key: (label on New Account grid, address regex, derivation path template)
# path template uses {i} = 0-based account index for that chain in this wallet.
# Templates mirror DerivationPathResolver.defaultPath (commonMain/.../providers/).
# BTC coin type 1' = testnet (0' on mainnet — adjust when testing that flavor).
TYPES = {
    "BTC": ("Bitcoin (BTC)",  r"^(tb1|bcrt1|bc1)[a-z0-9]+$",        r"m/84'/1'/{i}'"),
    "ETH": ("Ethereum (ETH)", r"^0x[0-9a-fA-F]{40}$",              r"m/44'/60'/{i}'/0/0"),
    "TRX": ("TRON (TRX)",     r"^T[1-9A-HJ-NP-Za-km-z]{33}$",      r"m/44'/195'/{i}'/0/0"),
    "TON": ("Gram (GRAM)",    r"^(EQ|UQ)[A-Za-z0-9_-]{46}$",       r"m/44'/607'/{i}'"),
}

results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))
    return ok


def ensure_installed():
    """Install the testnet debug APK if the app is not present (e.g. after --fresh)."""
    if ui.adb("shell", "pm", "path", PACKAGE).stdout.strip():
        return
    apk = os.path.normpath(os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..",
        "composeApp", "build", "outputs", "apk", "productionTestnet", "debug",
        "composeApp-productionTestnet-debug.apk"))
    if not os.path.exists(apk):
        print(f"ERROR: app not installed and no APK at {apk}\n       build it first: ./build-install.sh")
        sys.exit(1)
    print("-> installing debug APK")
    # install = stream + dex optimization: well over 60s on slow AVDs
    try:
        r = ui.adb("install", "-r", apk, timeout=600)
    except subprocess.TimeoutExpired:
        print(f"ERROR: install timed out after 600s on this (slow) AVD")
        sys.exit(1)
    if r.returncode != 0 or "Success" not in r.stdout:
        print(f"ERROR: install failed: {r.stdout} {r.stderr}")
        sys.exit(1)


def launch_app():
    ui.adb("shell", "am", "start", "-n", f"{PACKAGE}/{ACTIVITY}")
    # On slow AVDs the first frame can take 10s+; wait until our window is
    # actually focused before reading the screen (a fixed sleep races).
    if not ui.wait_focused(PACKAGE, timeout=90):
        print("ERROR: app window never gained focus after am start")
        ui.shot("no-focus")
        sys.exit(1)


def detect_screen():
    """Map the current screen to a flow stage (order matters: most specific first)."""
    stages = [
        ("New Account", "create_account"),
        ("Choose your security method", "choose_security"),
        ("Choose PIN length", "pin_length"),
        ("Confirm your PIN", "pin_confirm"),
        ("Create a PIN", "pin_create"),
        ("Your recovery phrase", "reveal"),
        ("additional layer of protection", "passphrase"),
        ("Phrase length", "wallet_phrase"),
        ("Set up your wallet", "wallet_setup"),
        ("Enter your PIN", "unlock"),
        ("Add Account", "accounts_list"),
        ("Get Started", "welcome"),
    ]
    for attempt in range(3):   # a single dump can catch a mid-launch frame
        xml = ui.dump()
        for label, stage in stages:
            if any(label.lower() in (n["text"] + " " + n["desc"]).lower() for n in ui.nodes(xml)):
                return stage
        time.sleep(3)
    return "unknown"


def onboard(pin):
    print("-> onboarding: welcome -> PIN -> wallet")
    ui.tap_text("Get Started")
    ui.tap_text("PIN")                      # PIN card (child label of clickable card)
    ui.wait_for("Choose PIN length", timeout=45)
    ui.tap_text("6", exact=True)            # 6-digit option
    ui.wait_for("Create a PIN", timeout=45)
    ui.enter_pin(pin)
    ui.wait_for("Confirm your PIN", timeout=45)
    ui.enter_pin(pin)
    ui.wait_for("Set up your wallet", timeout=60)
    ui.tap_text("Next")                     # step 0 (name prefilled "My wallet")
    ui.wait_for("Phrase length", timeout=45)
    ui.tap_text("Next")                     # default word count, no gesture entropy
    ui.wait_for("additional layer of protection", timeout=45)
    ui.tap_text("No, skip")
    ui.tap_text("Continue")
    ui.wait_for("Your recovery phrase", timeout=60)
    ui.tap_text("Create Wallet")
    ui.wait_for("Add Account", timeout=90)
    print("-> onboarding complete, on accounts list")


def unlock(pin):
    print("-> unlocking existing app")
    ui.wait_for("Enter your PIN", timeout=45)
    ui.enter_pin(pin)
    ui.wait_for("Add Account", timeout=90)
    print("-> unlocked, on accounts list")


def existing_index(type_key):
    """0-based index the next account of this chain will get (max existing + 1)."""
    label = TYPES[type_key][0]
    idxs = [int(m.group(1)) for n in ui.nodes()
            for m in [re.match(rf"^{re.escape(label)} account (\d+)$", n["text"])] if m]
    return max(idxs) if idxs else 0


def create_account(type_key):
    label, addr_re, path_tpl = TYPES[type_key]
    idx = existing_index(type_key)
    print(f"-> creating {label} (will be account {idx + 1})")

    ui.tap_text("Add Account")
    ui.wait_for("New Account", timeout=45)
    ui.tap_text(label)
    time.sleep(1.5)

    path_node = ui.find_by_text(lambda t: t.startswith("m/"))
    path = path_node["text"] if path_node else ""
    print(f"   default derivation path: {path or '<none shown>'}")

    ui.tap_text("Create Account")

    expected_name = f"{label} account {idx + 1}"
    if check(f"{expected_name} appears in list", ui.wait_for(expected_name, timeout=60) is not None):
        if path_tpl:
            want = path_tpl.format(i=idx)
            check(f"derivation path = {want}", path == want, f"got {path!r}")
        else:
            check("derivation path shown", bool(path), f"got {path!r}")
    return label, idx


def verify_address(type_key, label, idx):
    expected_name = f"{label} account {idx + 1}"
    _, addr_re, _ = TYPES[type_key]
    print(f"-> verifying address for {expected_name}")
    ui.tap_text(expected_name)          # open account details (tap inside card)
    if not ui.wait_for("Address", timeout=60):
        check(f"{expected_name}: details show Address", False)
        ui.tap_text("Back")
        return
    node = ui.find_by_text(lambda t: re.match(addr_re, t))
    check(f"{expected_name}: valid address rendered", node is not None,
          node["text"] if node else "no matching address text")
    ui.tap_text("Back")
    ui.wait_for("Add Account", timeout=45)


def main():
    sys.stdout.reconfigure(line_buffering=True)   # progress visible even when piped
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pin", default="123456", help="PIN used for setup/unlock (default 123456)")
    ap.add_argument("--types", default="BTC",
                    help="comma list from: " + ",".join(TYPES) + " (default BTC)")
    ap.add_argument("--fresh", action="store_true",
                    help="uninstall the app first to force the full onboarding path")
    args = ap.parse_args()

    type_keys = [t.strip().upper() for t in args.types.split(",") if t.strip()]
    for t in type_keys:
        if t not in TYPES:
            print(f"ERROR: unknown type {t!r}; valid: {list(TYPES)}")
            sys.exit(2)

    if not ui.device_online():
        print("ERROR: device not online. Start it first: ./emulator.sh up")
        sys.exit(1)

    if args.fresh:
        print("-> uninstalling app for a fresh run")
        ui.adb("uninstall", PACKAGE, timeout=300)
        # On slow AVDs the process kill AND the old window teardown lag behind
        # the uninstall command returning; launching before both are done
        # makes stage detection read the stale window.
        if not ui.wait_pid_gone(PACKAGE, timeout=60):
            print("WARN: app process still alive after uninstall")
        t0 = time.time()
        while ui.focused_window_pkg() == PACKAGE and time.time() - t0 < 60:
            time.sleep(2)

    ensure_installed()
    launch_app()
    stage = detect_screen()
    print(f"-> current stage: {stage}")
    if stage == "welcome":
        onboard(args.pin)
    elif stage == "unlock":
        unlock(args.pin)
    elif stage == "accounts_list":
        pass
    else:
        print(f"ERROR: unexpected stage {stage!r}; dump for manual inspection:")
        ui.show()
        ui.shot("stuck")
        sys.exit(1)

    for t in type_keys:
        label, idx = create_account(t)
        verify_address(t, label, idx)

    print("\n=== summary ===")
    failed = 0
    for name, ok, detail in results:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))
        failed += 0 if ok else 1
    print(f"\n{len(results) - failed}/{len(results)} checks passed")
    ui.shot("final")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
