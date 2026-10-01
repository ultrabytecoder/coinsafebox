#!/usr/bin/env python3
"""E2E test: wallet management on CoinSafeBox Android.

Drives the real UI (adb + uiautomator, see ui.py) on the emulator. Covers the
Manage Wallets surface and the account-list wallet switcher, which the other
tests (single-wallet state) never exercise:

  1. fresh install + onboarding -> wallet "My wallet" (with a BTC account).
  2. create a SECOND wallet "ZZsecond" (generate-new flow re-entered from
     Manage Wallets, custom name via the system IME).
  3. rename "ZZsecond" -> "ZZrenamed" via the row menu + dialog.
  4. account list: the wallet switcher lists both wallets; switching to
     "My wallet" shows its BTC account, switching to "ZZrenamed" shows the
     empty state (wallets are independent).
  5. delete "ZZrenamed" via the row menu + confirm dialog; the wallet is
     gone from the Manage Wallets list and the switcher.

Usage:
  ./test_manage_wallets.py
  ./test_manage_wallets.py --pin 246810

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
W2, W2_NEW = "ZZsecond", "ZZrenamed"

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
    if ui.wait_for(f"{BTC_LABEL} account 1", timeout=90) is None:
        raise RuntimeError("BTC account did not appear in the list")
    # On success the app auto-pops back to the accounts list (no Back button
    # there) — just confirm we're on the list.
    ui.wait_for("Add Account", timeout=45)


def open_manage_wallets():
    if ui.wait_for("More", timeout=45) is None:
        raise RuntimeError("accounts-list 'More' overflow not found")
    ui.tap_node(ui.find("More", exact=True))
    ui.wait_for("Manage Wallets", timeout=30)
    ui.tap_text("Manage Wallets")
    if ui.wait_for("More", timeout=60) is None:
        raise RuntimeError("did not reach the Manage Wallets list")


def back_to_list():
    ui.tap_text("Back")
    ui.wait_for("Add Account", timeout=60)


def tap_row_menu(wallet_name):
    """Tap the 'More' overflow on the wallet row that shows `wallet_name`
    (matched by vertical band, so it works with several rows on screen)."""
    ns = ui.nodes()
    row = next((n for n in ns if n["text"] == wallet_name and n["cy"] is not None), None)
    if row is None:
        raise RuntimeError(f"wallet row {wallet_name!r} not found")
    cands = [n for n in ns if n["desc"] == "More" and n["cy"] is not None
             and abs(n["cy"] - row["cy"]) < 120]
    if not cands:
        raise RuntimeError(f"no row menu near {wallet_name!r}")
    ui.tap_node(cands[0])


def create_second_wallet(name):
    print(f"-> creating second wallet {name!r}")
    open_manage_wallets()
    ui.tap_text_bottom("Create Wallet")
    if ui.wait_for("Set up your wallet", timeout=60) is None:
        raise RuntimeError("did not reach the wallet setup screen")
    ui.ime_set_text(name)
    if ui.find(name, exact=True) is None:
        raise RuntimeError(f"wallet name {name!r} not reflected in the field")
    ui.ensure_no_ime_window()          # the IME window must not eat the Next tap
    ui.tap_text("Next")                       # generate-new is the default mode
    ui.wait_for("Phrase length", timeout=60)
    ui.tap_text("12 words")
    ui.tap_text("Next")
    ui.wait_for("additional layer of protection", timeout=60)
    ui.tap_text("No, skip")
    ui.tap_text("Continue")
    ui.wait_for("Your recovery phrase", timeout=90)
    ui.tap_text_bottom("Create Wallet")
    if ui.wait_for("Add Account", timeout=120) is None:
        raise RuntimeError("second wallet was not created")
    if ui.find(name, exact=True) is None:
        raise RuntimeError(f"accounts list does not show the new wallet {name!r}")


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

    create_second_wallet(W2)
    check("second wallet created and selected in the account list",
          ui.find(W2, exact=True) is not None)

    print("-> rename %r -> %r" % (W2, W2_NEW))
    open_manage_wallets()
    texts = ui.text_all()
    check("manage wallets lists both wallets",
          any(t == "My wallet" for t in texts) and any(t == W2 for t in texts), str(texts))
    tap_row_menu(W2)
    ui.tap_text("Rename", exact=True)        # menu item -> opens the dialog
    if ui.wait_for("Rename Wallet", timeout=30) is None:
        raise RuntimeError("rename dialog did not open")
    ui.ime_set_text(W2_NEW)
    if ui.find(W2_NEW, exact=True) is None:
        raise RuntimeError(f"renamed value {W2_NEW!r} not reflected in the dialog field")
    ui.ensure_no_ime_window()          # the IME window must not eat the Rename tap
    ui.tap_text("Rename", exact=True)
    time.sleep(1.5)
    texts = ui.text_all()
    check("rename applied in the wallet list",
          any(t == W2_NEW for t in texts) and all(t != W2 for t in texts), str(texts))
    back_to_list()

    print("-> wallet switcher on the accounts list")
    ui.tap_node(ui.find("Select wallet"))
    if ui.wait_for(W2_NEW, timeout=30) is None:
        check("switcher lists the renamed wallet", False)
    else:
        check("switcher lists the renamed wallet", True)
    check("switcher lists both wallets", ui.find("My wallet") is not None)
    ui.tap_text("My wallet")
    if ui.wait_for(f"{BTC_LABEL} account 1", timeout=60) is None:
        check("switch back: wallet 1 shows its BTC account", False)
    else:
        check("switch back: wallet 1 shows its BTC account", True)
    ui.tap_node(ui.find("Select wallet"))
    ui.tap_text(W2_NEW)
    if ui.wait_for("No accounts yet", timeout=60) is None:
        check("switch to wallet 2: independent empty account list", False)
    else:
        check("switch to wallet 2: independent empty account list", True)
    ui.shot("switched-empty-wallet")

    print(f"-> deleting {W2_NEW!r}")
    open_manage_wallets()
    tap_row_menu(W2_NEW)
    ui.tap_text("Delete", exact=True)        # menu item -> opens the dialog
    if ui.wait_for("Delete Wallet", timeout=30) is None:
        raise RuntimeError("delete dialog did not open")
    ui.tap_text("Delete", exact=True)        # dialog confirm button
    time.sleep(2.0)
    texts = ui.text_all()
    check("deleted wallet is gone from the list",
          all(t != W2_NEW for t in texts) and any(t == "My wallet" for t in texts), str(texts))
    back_to_list()
    ui.tap_node(ui.find("Select wallet"))
    check("switcher no longer lists the deleted wallet",
          ui.wait_for("My wallet", timeout=30) is not None
          and ui.find(W2_NEW) is None)

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
