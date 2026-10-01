#!/usr/bin/env python3
"""E2E test: read-only wallet (Remove Master Key) on CoinSafeBox Android.

Drives the real UI (adb + uiautomator, see ui.py) on the emulator. Fully
automated and non-interactive like the other tests here. It creates the wallet
and the account(s) by itself, removes the master key, and verifies the wallet
becomes read-only WITHOUT losing any viewable data.

Steps:
  1. fresh install + onboarding (PIN); a wallet is created by generating a new
     phrase (a clean single-wallet state keeps the Manage Wallets list
     unambiguous).
  2. create one account per requested chain (BTC by default) and verify a valid
     address is rendered.
  3. Remove Master Key:
       - CANCEL path: the dialog is dismissed and the wallet stays full (no
         "Read-only" badge).
       - CONFIRM path: the key is wiped, a "Read-only" badge appears, and the
         wallet menu item becomes "Read-only (key removed)".
  4. Verify the now read-only account still shows its address, the Address/QR
     section and its balance (read-only means view-only, not blank), and that
     the address is unchanged (data intact).

Usage:
  ./test_readonly_wallet.py               # full read-only E2E
  ./test_readonly_wallet.py --pin 246810  # non-default PIN
  ./test_readonly_wallet.py --types BTC,ETH

Environment:
  KK_DEVICE  adb serial (default: emulator-5554)

Assumptions / limits:
  - testnet debug build (package com.ultrabytecoder.coinsafebox.testnet).
  - security method is PIN (password-method unlock is not scripted).
  - always starts from a fresh install for a clean single-wallet state.
  - the READ-ONLY SEND path (re-entering the recovery phrase to sign/send) is
    NOT covered here: those fields use the app's custom on-screen keyboard and
    are deliberately out of the IME focus chain (see SecureOutlinedTextField),
    so they cannot be driven by `adb input`. It is a manual test — see
    improvement-plans/readonly-wallet-e2e-manual.md (E2E-3).
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

# chain: (label on New Account grid, address regex, account symbol)
TYPES = {
    "BTC": ("Bitcoin (BTC)",  r"^(tb1|bcrt1|bc1)[a-z0-9]+$",  "BTC"),
    "ETH": ("Ethereum (ETH)", r"^0x[0-9a-fA-F]{40}$",        "ETH"),
    "TRX": ("TRON (TRX)",     r"^T[1-9A-HJ-NP-Za-km-z]{33}$", "TRX"),
    "TON": ("Gram (GRAM)",    r"^(EQ|UQ)[A-Za-z0-9_-]{46}$", "GRAM"),
}

results = []
STATE = {"addr": {}}


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
    ui.tap_text("Next")                       # step 0 (name prefilled "My wallet")
    ui.wait_for("Phrase length", timeout=60)
    ui.tap_text("Next")                       # default word count, no gesture entropy
    ui.wait_for("additional layer of protection", timeout=60)
    ui.tap_text("No, skip")
    ui.tap_text("Continue")
    ui.wait_for("Your recovery phrase", timeout=90)
    ui.tap_text("Create Wallet")
    ui.wait_for("Add Account", timeout=120)
    print("-> onboarding complete, on accounts list")


def existing_count(type_key):
    label = TYPES[type_key][0]
    return len([n for n in ui.nodes()
                if re.match(rf"^{re.escape(label)} account \d+$", n["text"])])


def create_account(type_key):
    label, _, _ = TYPES[type_key]
    idx = existing_count(type_key) + 1
    print(f"-> creating {label} (account {idx})")
    ui.tap_text("Add Account")
    ui.wait_for("New Account", timeout=60)
    ui.tap_text(label)
    time.sleep(1.5)
    ui.tap_text("Create Account")
    check(f"{label} account {idx} appears in list",
          ui.wait_for(f"{label} account {idx}", timeout=90) is not None)
    return idx


def open_account(type_key, idx):
    """Open the account's details screen. True if the 'Address' section appeared."""
    label, _, _ = TYPES[type_key]
    ui.tap_text(f"{label} account {idx}")
    return ui.wait_for("Address", timeout=60) is not None


def back_to_list():
    ui.tap_text("Back")
    ui.wait_for("Add Account", timeout=60)


def read_address(type_key, idx):
    """Open the account, read its on-chain address from the UI, then go back."""
    if not open_account(type_key, idx):
        back_to_list()
        return None
    _, addr_re, _ = TYPES[type_key]
    node = ui.find_by_text(lambda t: re.match(addr_re, t))
    addr = node["text"] if node else None
    back_to_list()
    return addr


def balance_shown(type_key):
    """On the (open) account details: is a '<amount> <symbol>' balance line present?"""
    _, _, symbol = TYPES[type_key]
    pat = re.compile(rf"^[0-9]+(?:\.[0-9]+)?\s+{re.escape(symbol)}$")
    return any(pat.match(n["text"].strip()) for n in ui.nodes())


def verify_readonly_details(type_key, idx):
    """Open the now read-only account; assert address, Address/QR section, balance
    and the 'Read-only' badge all render. Returns the address (or None)."""
    label, addr_re, _ = TYPES[type_key]
    if not open_account(type_key, idx):
        check(f"{label}: read-only details open", False, "did not reach account details")
        back_to_list()
        return None
    addr_node = ui.find_by_text(lambda t: re.match(addr_re, t))
    check(f"{label}: read-only details show address",
          addr_node is not None, addr_node["text"] if addr_node else "no address node")
    check(f"{label}: read-only details show Address/QR section", ui.find("Address") is not None)
    check(f"{label}: read-only details show balance", balance_shown(type_key))
    check(f"{label}: read-only details show 'Read-only' badge", ui.find("Read-only") is not None)
    addr = addr_node["text"] if addr_node else None
    back_to_list()
    return addr


def open_manage_wallets():
    """Accounts list overflow -> 'Manage Wallets' -> the wallet list."""
    if ui.wait_for("More", timeout=30) is None:
        raise RuntimeError("accounts-list 'More' overflow not found")
    ui.tap_node(ui.find("More", exact=True))
    ui.wait_for("Manage Wallets", timeout=30)
    ui.tap_text("Manage Wallets")
    if ui.wait_for("More", timeout=45) is None:
        raise RuntimeError("did not reach the Manage Wallets list")


def tap_wallet_menu():
    """On the (single-wallet) Manage Wallets list, open the row's overflow menu.

    Waits for 'Rename' (always present) rather than 'Remove Master Key', because
    once the key is removed that item becomes 'Read-only (key removed)'.
    """
    if ui.wait_for("More", timeout=30) is None:
        raise RuntimeError("wallet 'More' overflow not found")
    ui.tap_node(ui.find("More", exact=True))
    ui.wait_for("Rename", timeout=30)


def tap_the_checkbox():
    """Tap the checkable node on screen (the dialog's acknowledgement box).

    A Compose Checkbox is a separate checkable node from its label text; tapping
    the label does NOT toggle it. There is exactly one checkable node in the
    Remove Master Key dialog.
    """
    for m in re.finditer(r"<node\b[^>]*>", ui.dump()):
        t = m.group(0)
        if 'checkable="true"' not in t:
            continue
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', t)
        if bm:
            x1, y1, x2, y2 = map(int, bm.groups())
            ui.tap((x1 + x2) // 2, (y1 + y2) // 2, settle=0.8)
            return True
    return False


def remove_master_key_cancel():
    print("-> remove master key: CANCEL path (wallet must stay full)")
    open_manage_wallets()
    tap_wallet_menu()
    ui.tap_text("Remove Master Key")
    ui.wait_for("I have backed up my recovery phrase", timeout=30)
    ui.tap_text("Cancel", exact=True)
    check("cancel path: wallet not made read-only (no badge)", ui.find("Read-only") is None)
    ui.tap_text("Back")
    ui.wait_for("Add Account", timeout=45)


def remove_master_key_confirm():
    print("-> remove master key: CONFIRM path (badge must appear)")
    open_manage_wallets()
    tap_wallet_menu()
    ui.tap_text("Remove Master Key")
    check("dialog open", ui.wait_for("I have backed up my recovery phrase", timeout=45) is not None)
    check("dialog shows the destructive 'Remove' action",
          ui.wait_for("Remove", timeout=20, exact=True) is not None)
    if tap_the_checkbox():
        check("acknowledgement checkbox ticked", True)
    else:
        check("acknowledgement checkbox ticked", False, "no checkable node found")
    time.sleep(1.5)                       # let the 'Remove' button become enabled
    ui.tap_text("Remove", exact=True)     # exact: the title is 'Remove Master Key'
    if ui.wait_for("Read-only", timeout=90) is None:
        check("confirm path: 'Read-only' badge appears", False, "badge did not appear")
        ui.shot("stuck-readonly")
        ui.adb("shell", "input", "keyevent", "KEYCODE_BACK")   # dismiss dialog -> wallet list
        time.sleep(1)
        ui.tap_text("Back")
        ui.wait_for("Add Account", timeout=45)
        return
    check("confirm path: 'Read-only' badge appears", True)
    tap_wallet_menu()
    check("menu item now 'Read-only (key removed)'", ui.find("Read-only (key removed)") is not None)
    ui.adb("shell", "input", "keyevent", "KEYCODE_BACK")   # dismiss the menu
    ui.tap_text("Back")
    ui.wait_for("Add Account", timeout=45)


def main():
    sys.stdout.reconfigure(line_buffering=True)
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pin", default="123456")
    ap.add_argument("--types", default="BTC",
                    help="comma list from: " + ",".join(TYPES) + " (default BTC)")
    args = ap.parse_args()

    type_keys = [t.strip().upper() for t in args.types.split(",") if t.strip()]
    for t in type_keys:
        if t not in TYPES:
            print(f"ERROR: unknown type {t!r}; valid: {list(TYPES)}")
            sys.exit(2)

    if not ui.device_online():
        print("ERROR: device not online. Start it first: ./emulator.sh up")
        sys.exit(1)

    # Always fresh: a clean single-wallet state keeps the Manage Wallets list simple.
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

    for t in type_keys:
        idx = create_account(t)
        addr = read_address(t, idx)
        check(f"{t}: valid address rendered", addr is not None, addr or "no address node found")
        STATE["addr"][t] = addr

    remove_master_key_cancel()
    remove_master_key_confirm()

    for t in type_keys:
        addr = verify_readonly_details(t, 1)
        if STATE["addr"].get(t) and addr:
            check(f"{t}: address unchanged after key removal", addr == STATE["addr"][t],
                  f"{STATE['addr'][t]} -> {addr}")

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
