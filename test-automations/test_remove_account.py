#!/usr/bin/env python3
"""E2E test: remove an account on CoinSafeBox Android.

Drives the real UI (adb + uiautomator, see ui.py) on the emulator. Covers the
account-removal flow on the Account Details screen:

  1. fresh install + onboarding -> wallet "My wallet".
  2. create a BTC account (auto-named "Bitcoin (BTC) account 1").
  3. open the account's Details screen (tap the account row).
  4. top-bar overflow (content-desc "More") -> "Remove Account" -> confirmation
     dialog. CANCEL first: the dialog dismisses and we stay on the Details
     screen (nothing is deleted).
  5. re-open the menu, "Remove Account" -> confirm "Remove": the app redirects
     to the Accounts List, now showing "No accounts yet"; the account is gone.

Usage:
  ./test_remove_account.py
  ./test_remove_account.py --pin 246810

Environment:
  KK_DEVICE  adb serial (default: emulator-5554)

Assumptions / limits:
  - testnet debug build (package com.ultrabytecoder.coinsafebox.testnet).
  - security method is PIN (password-method unlock is not scripted).
  - always starts from a fresh install.
"""
import argparse
import os
import subprocess
import sys
import time

import ui

PACKAGE = "com.ultrabytecoder.coinsafebox.testnet"
ACTIVITY = "com.ultrabytecoder.coinsafebox.MainActivity"

BTC_LABEL = "Bitcoin (BTC)"
ACCOUNT_ROW = f"{BTC_LABEL} account 1"    # "Bitcoin (BTC) account 1"

results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))
    return ok


def ensure_installed():
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
    try:
        r = ui.adb("install", "-r", apk, timeout=600)
    except subprocess.TimeoutExpired:
        print("ERROR: install timed out after 600s on this (slow) AVD")
        sys.exit(1)
    if r.returncode != 0 or "Success" not in r.stdout:
        print(f"ERROR: install failed: {r.stdout} {r.stderr}")
        sys.exit(1)


def launch_app():
    ui.adb("shell", "am", "start", "-n", f"{PACKAGE}/{ACTIVITY}")
    if not ui.wait_focused(PACKAGE, timeout=90):
        print("ERROR: app window never gained focus after am start")
        ui.shot("no-focus")
        sys.exit(1)


def onboard(pin):
    print("-> onboarding: welcome -> PIN -> wallet")
    ui.tap_text("Get Started")
    ui.tap_text("PIN")
    ui.wait_for("Choose PIN length", timeout=60)
    ui.tap_text("6", exact=True)
    ui.wait_for("Create a PIN", timeout=60)
    ui.enter_pin(pin)
    ui.wait_for("Confirm your PIN", timeout=60)
    ui.enter_pin(pin)
    ui.wait_for("Set up your wallet", timeout=90)
    ui.tap_text("Next")
    ui.wait_for("Phrase length", timeout=60)
    ui.tap_text("12 words")
    ui.tap_text("Next")
    ui.wait_for("additional layer of protection", timeout=60)
    ui.tap_text("No, skip")
    ui.tap_text("Continue")
    ui.wait_for("Your recovery phrase", timeout=90)
    ui.tap_text("Create Wallet")
    if ui.wait_for("Add Account", timeout=120) is None:
        raise RuntimeError("did not reach the accounts list after onboarding")
    print("-> onboarding complete, on accounts list")


def create_btc_account():
    ui.tap_text("Add Account")
    ui.wait_for("New Account", timeout=60)
    ui.tap_text(BTC_LABEL)
    time.sleep(1.5)
    ui.tap_text("Create Account")
    if ui.wait_for(ACCOUNT_ROW, timeout=90) is None:
        raise RuntimeError("BTC account did not appear in the list")
    # On success the app auto-pops back to the accounts list.
    ui.wait_for("Add Account", timeout=45)


def open_details():
    """Tap the BTC account row -> Account Details screen (has an 'Address'
    section)."""
    ui.tap_text(ACCOUNT_ROW)
    if ui.wait_for("Address", timeout=60) is None:
        raise RuntimeError("did not reach the account Details screen")


def tap_details_overflow():
    """Tap the Details top-bar overflow menu (content-desc 'More')."""
    n = ui.find("More", exact=True)
    if n is None:
        raise RuntimeError("Details 'More' overflow not found")
    ui.tap_node(n)


def main():
    sys.stdout.reconfigure(line_buffering=True)
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pin", default="123456")
    args = ap.parse_args()

    if not ui.device_online():
        print("ERROR: device not online. Start it first: ./emulator.sh up")
        sys.exit(1)

    # Gboard must be off for reliable `input text` (see ui.ime_disable).
    ui.ime_disable()
    try:
        _main_body(args)
    finally:
        ui.ime_enable()


def _main_body(args):
    print("-> uninstalling app for a fresh run")
    ui.adb("uninstall", PACKAGE, timeout=300)
    if not ui.wait_pid_gone(PACKAGE, timeout=60):
        print("WARN: app process still alive after uninstall")
    t0 = time.time()
    while ui.focused_window_pkg() == PACKAGE and time.time() - t0 < 60:
        time.sleep(2)

    ensure_installed()
    launch_app()
    time.sleep(3)
    if ui.find("Get Started") is None:
        print("ERROR: expected the welcome screen on a fresh install:")
        ui.show(); ui.shot("stuck"); sys.exit(1)

    onboard(args.pin)
    create_btc_account()
    check("BTC account created and listed", ui.find(ACCOUNT_ROW) is not None)

    print("-> open account Details")
    open_details()

    print("-> overflow -> Remove Account -> CANCEL (stays on Details)")
    tap_details_overflow()
    ui.tap_text("Remove Account", exact=True)      # menu item -> opens the dialog
    if ui.wait_for("Cancel", timeout=30) is None:
        raise RuntimeError("remove-account dialog did not open")
    ui.tap_text("Cancel", exact=True)
    time.sleep(1.5)
    check("cancel keeps us on the Details screen", ui.find("Address") is not None)

    print("-> overflow -> Remove Account -> confirm 'Remove'")
    tap_details_overflow()
    ui.tap_text("Remove Account", exact=True)
    if ui.wait_for("Cancel", timeout=30) is None:
        raise RuntimeError("remove-account dialog did not open (2nd time)")
    ui.tap_text("Remove", exact=True)              # dialog confirm (destructive)
    # app should redirect to the (now empty) Accounts List
    if ui.wait_for("No accounts yet", timeout=60) is None:
        raise RuntimeError("did not return to an empty Accounts List after removal")
    check("redirected to the Accounts List after removal", True)
    check("removed account is gone from the list", ui.find(ACCOUNT_ROW) is None)
    ui.shot("removed-empty-list")

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
