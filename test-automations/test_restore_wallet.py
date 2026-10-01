#!/usr/bin/env python3
"""E2E test: restore a wallet from an existing recovery phrase on CoinSafeBox.

Drives the real UI (adb + uiautomator, see ui.py) on the emulator. Covers the
"Restore existing" (Way B) branch of the create-wallet flow, which the
generate-new tests never touch:

  1. fresh install + onboarding; on the REVEAL screen read the generated
     12-word phrase (tap "Show") and create wallet #1.
  2. create a BTC account in wallet #1 and record its address.
  3. Manage Wallets -> Create Wallet -> "Restore existing" -> type the
     recorded phrase into the secure Mnemonic field via the on-screen
     keyboard -> Create Wallet. The restored wallet is created.
  4. create a BTC account in the RESTORED wallet and assert its address
     equals wallet #1's — same phrase must derive the same keys (proves the
     restore actually imported the same seed, not just any wallet).
  5. NEGATIVE: start a third restore with a corrupted phrase (two adjacent
     words swapped -> bad BIP-39 checksum) and assert:
        - "Create Wallet" is rejected with the invalid-phrase error
        - no wallet is created (the Manage Wallets list is unchanged)
  6. assert the Manage Wallets list contains exactly the two wallets.

Usage:
  ./test_restore_wallet.py
  ./test_restore_wallet.py --pin 246810

Environment:
  KK_DEVICE  adb serial (default: emulator-5554)

Assumptions / limits:
  - testnet debug build (package com.ultrabytecoder.coinsafebox.testnet).
  - security method is PIN (password-method unlock is not scripted).
  - always starts from a fresh install.
  - phrase is always 12 words (chosen on the phrase-length step) so the
    on-screen-keyboard typing stays short.
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

BTC_LABEL = "Bitcoin (BTC)"
BTC_ADDR_RE = r"^(tb1|bcrt1|bc1)[a-z0-9]+$"
PHRASE_RE = re.compile(r"^[a-z]+( [a-z]+){11}$")

results = []
STATE = {"phrase": None, "addr_wallet1": None}


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


def onboard_to_reveal(pin):
    print("-> onboarding: welcome -> PIN -> wallet reveal")
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
    ui.tap_text("12 words")
    ui.tap_text("Next")
    ui.wait_for("additional layer of protection", timeout=60)
    ui.tap_text("No, skip")
    ui.tap_text("Continue")
    if ui.wait_for("Your recovery phrase", timeout=90) is None:
        raise RuntimeError("did not reach the reveal screen")
    print("-> on the reveal screen")


def read_phrase():
    """Reveal the generated mnemonic on the REVEAL screen and read it."""
    ui.tap_text("Show")
    time.sleep(1.0)
    n = ui.find_by_text(lambda t: PHRASE_RE.match(t.strip()))
    if n is None:
        raise RuntimeError("generated mnemonic not readable on the reveal screen")
    phrase = n["text"].strip()
    print(f"   mnemonic ({len(phrase.split())} words) read from reveal screen")
    return phrase


def create_btc_account():
    """Create a BTC account in the wallet currently shown; return its address."""
    ui.tap_text("Add Account")
    ui.wait_for("New Account", timeout=60)
    ui.tap_text(BTC_LABEL)
    time.sleep(1.5)
    ui.tap_text("Create Account")
    if ui.wait_for(f"{BTC_LABEL} account 1", timeout=90) is None:
        raise RuntimeError("BTC account did not appear in the list")
    ui.tap_text(f"{BTC_LABEL} account 1")
    if ui.wait_for("Address", timeout=60) is None:
        raise RuntimeError("account details did not show the Address section")
    n = ui.find_by_text(lambda t: re.match(BTC_ADDR_RE, t))
    if n is None:
        raise RuntimeError("no BTC address rendered on the account details")
    ui.tap_text("Back")
    ui.wait_for("Add Account", timeout=45)
    return n["text"]


def open_manage_wallets():
    if ui.wait_for("More", timeout=45) is None:
        raise RuntimeError("accounts-list 'More' overflow not found")
    ui.tap_node(ui.find("More", exact=True))
    ui.wait_for("Manage Wallets", timeout=30)
    ui.tap_text("Manage Wallets")
    if ui.wait_for("More", timeout=60) is None:
        raise RuntimeError("did not reach the Manage Wallets list")


def back_to_manage_wallets():
    """From the create-wallet setup screen: step1 -> step0 -> Manage Wallets."""
    ui.tap_text("Back")
    time.sleep(1.0)
    ui.tap_text("Back")
    ui.wait_for("Manage Wallets", timeout=60)


def set_setup_name(name):
    """Step 0 of create-wallet: set the wallet name field (system IME).

    Needs ui.ime_disable() done at test start (see ui.ime_disable docstring
    for why plain `input text` is unreliable while Gboard is active).
    """
    ui.ime_set_text(name)
    if ui.find(name, exact=True) is None:
        raise RuntimeError(f"wallet name {name!r} not reflected in the field")
    ui.ensure_no_ime_window()          # the IME window must not eat the next taps


def start_restore(name, phrase):
    """Manage Wallets -> Create Wallet -> restore mode -> type the phrase."""
    open_manage_wallets()
    ui.tap_text_bottom("Create Wallet")
    if ui.wait_for("Set up your wallet", timeout=60) is None:
        raise RuntimeError("did not reach the wallet setup screen")
    set_setup_name(name)
    ui.tap_text("Restore existing")
    time.sleep(1.0)
    ui.tap_text("Next")
    if ui.wait_for("Mnemonic", timeout=60) is None:
        raise RuntimeError("did not reach the restore (mnemonic) step")
    ui.tap_field_below("Mnemonic")
    time.sleep(0.8)                          # let the keyboard settle
    ui.type_onscreen(phrase)
    time.sleep(1.0)
    if ui.find_by_text(lambda t: t.strip() == phrase) is None:
        raise RuntimeError("typed mnemonic not reflected in the field")


def corrupt(words):
    """Swap the first pair of adjacent DIFFERENT words -> valid words, bad checksum."""
    w = list(words)
    for i in range(len(w) - 1):
        if w[i] != w[i + 1]:
            w[i], w[i + 1] = w[i + 1], w[i]
            return " ".join(w)
    # pathological (all words identical): substitute a different real BIP-39 word
    w[-1] = "abandon" if w[-1] != "abandon" else "zoo"
    return " ".join(w)


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

    onboard_to_reveal(args.pin)
    STATE["phrase"] = read_phrase()
    check("reveal screen shows the generated 12-word phrase", True)
    ui.tap_text("Create Wallet")
    if ui.wait_for("Add Account", timeout=120) is None:
        raise RuntimeError("wallet #1 was not created")
    print("-> wallet #1 created")

    print("-> creating a BTC account in wallet #1")
    STATE["addr_wallet1"] = create_btc_account()
    check("wallet #1: BTC address rendered", True, STATE["addr_wallet1"])

    print("-> restoring the same phrase as wallet #2")
    start_restore("ZZrestored", STATE["phrase"])
    ui.tap_text_bottom("Create Wallet")
    if ui.wait_for("Add Account", timeout=120) is None:
        check("restore: wallet #2 created", False, "did not reach the accounts list")
        ui.shot("stuck-restore")
        sys.exit(1)
    check("restore: wallet #2 created", True)
    check("restore: accounts list shows the new wallet name",
          ui.find("ZZrestored", exact=True) is not None)

    print("-> creating a BTC account in the restored wallet")
    try:
        addr2 = create_btc_account()
    except RuntimeError as e:
        addr2 = None
        print(f"   WARNING: {e}")
    check("restored wallet: BTC address rendered", addr2 is not None, addr2 or "")
    if STATE["addr_wallet1"] and addr2:
        check("same phrase -> same derived address (seed actually restored)",
              addr2 == STATE["addr_wallet1"], f"{STATE['addr_wallet1']} vs {addr2}")

    print("-> NEGATIVE: restoring a corrupted phrase must be rejected")
    bad = corrupt(STATE["phrase"].split())
    start_restore("ZZbad", bad)
    ui.tap_text_bottom("Create Wallet")
    if ui.wait_for("recovery phrase is invalid", timeout=90) is None:
        check("corrupted phrase: invalid-phrase error shown", False, "no error visible")
        ui.show(); ui.shot("stuck-badrestore")
        sys.exit(1)
    check("corrupted phrase: invalid-phrase error shown", True)
    check("corrupted phrase: still on the setup screen (no wallet created)",
          ui.find("Mnemonic") is not None)
    ui.shot("badrestore-rejected")
    back_to_manage_wallets()

    names = ui.text_all()
    check("manage wallets: both good wallets listed",
          any("My wallet" == t for t in names) and any(t == "ZZrestored" for t in names),
          str(names))
    check("manage wallets: failed restore did not create a wallet",
          all(t != "ZZbad" for t in names))

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
