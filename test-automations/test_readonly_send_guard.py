#!/usr/bin/env python3
"""E2E test: read-only send guards on CoinSafeBox Android.

Drives the real UI (adb + uiautomator, see ui.py) on the emulator.
Fund-independent: the wrong-mnemonic guard rejects a bad phrase BEFORE any
on-chain operation (it derives the address from the entered phrase and
compares it with the stored one), so no testnet funding or network is
required. This automates the guard half of the manual script
`improvement-plans/readonly-wallet-e2e-manual.md` (E2E-9 / E2E-12), which
was previously not automatable: the phrase field uses the app's custom
on-screen keyboard, and this test drives it by tapping keyboard keys
(see ui.type_onscreen).

Steps:
  1. fresh install + onboarding + a BTC account (zero balance).
  2. Remove Master Key (confirm) -> the wallet is read-only.
  3. account details -> "Send BTC" -> fill amount (numeric keyboard) and
     recipient (Qwerty keyboard) -> "Send".
  4. because the wallet is read-only, the flow must show the mnemonic card
     "Recovery Phrase Required" instead of signing with a stored seed.
  5. "Sign & Send" with an EMPTY phrase -> blocked with
     "Enter your recovery phrase" (E2E-12).
  6. enter a valid-format but WRONG 12-word phrase -> "Sign & Send" ->
     rejected with "The recovery phrase does not match this wallet"
     (E2E-9); no transaction is broadcast.
  7. "Cancel" -> back to the send form; re-entering the send shows the
     phrase field wiped (E2E-19 behavior: cancel wipes the buffer).

Usage:
  ./test_readonly_send_guard.py
  ./test_readonly_send_guard.py --pin 246810

Environment:
  KK_DEVICE  adb serial (default: emulator-5554)

Assumptions / limits:
  - testnet debug build (package com.ultrabytecoder.coinsafebox.testnet).
  - security method is PIN (password-method unlock is not scripted).
  - always starts from a fresh install; the account is never funded.
  - the wallet has NO passphrase, so the prompt has no passphrase field.
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
AMOUNT = "0.001"
# A valid BIP-39 12-word mnemonic (standard test vector) that is, with
# overwhelming probability, NOT the wallet's phrase -> wrong-mnemonic guard.
WRONG_PHRASE = ("abandon abandon abandon abandon abandon abandon "
                "abandon abandon abandon abandon abandon about")

results = []
STATE = {"addr": None}


def check(name, ok, detail="", soft=False):
    results.append((name, ok, detail, soft))
    tag = "PASS" if ok else ("INFO" if soft else "FAIL")
    print(f"  [{tag}] {name}" + (f" — {detail}" if detail else ""))
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


def create_btc_account():
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
    addr = n["text"]
    ui.tap_text("Back")
    ui.wait_for("Add Account", timeout=45)
    return addr


def open_manage_wallets():
    if ui.wait_for("More", timeout=45) is None:
        raise RuntimeError("accounts-list 'More' overflow not found")
    ui.tap_node(ui.find("More", exact=True))
    ui.wait_for("Manage Wallets", timeout=30)
    ui.tap_text("Manage Wallets")
    if ui.wait_for("More", timeout=60) is None:
        raise RuntimeError("did not reach the Manage Wallets list")


def tap_the_checkbox():
    """Tap the checkable node on screen (the dialog's acknowledgement box)."""
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


def remove_master_key():
    print("-> removing the master key (wallet becomes read-only)")
    open_manage_wallets()
    if ui.wait_for("More", timeout=30) is None:
        raise RuntimeError("wallet 'More' overflow not found")
    ui.tap_node(ui.find("More", exact=True))
    ui.wait_for("Rename", timeout=30)
    ui.tap_text("Remove Master Key")
    if ui.wait_for("I have backed up my recovery phrase", timeout=45) is None:
        raise RuntimeError("remove-master-key dialog did not open")
    if not tap_the_checkbox():
        raise RuntimeError("acknowledgement checkbox not found")
    time.sleep(1.5)
    ui.tap_text("Remove", exact=True)
    if ui.wait_for("Read-only", timeout=90) is None:
        raise RuntimeError("wallet did not become read-only")
    print("   wallet is read-only")
    ui.tap_text("Back")
    ui.wait_for("Add Account", timeout=45)


def fill_send_form(submit=True):
    """On the (full-form) send screen: type amount + recipient via the
    on-screen keyboards. When submit=True also tap Send; when False leave the
    form filled so the caller can assert on it (e.g. the balance warning) before
    submitting."""
    ui.tap_field_below("Amount")
    time.sleep(0.8)
    ui.type_onscreen(AMOUNT)
    time.sleep(1.0)
    if ui.find(AMOUNT, exact=True) is None:
        raise RuntimeError("amount not reflected in the field")
    ui.tap_field_below("Recipient Address")
    time.sleep(0.8)
    ui.type_onscreen(STATE["addr"])
    time.sleep(1.0)
    if ui.find(STATE["addr"], exact=True) is None:
        raise RuntimeError("recipient not reflected in the field")
    if submit:
        ui.tap_text_bottom("Send")


def main():
    sys.stdout.reconfigure(line_buffering=True)
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pin", default="123456")
    args = ap.parse_args()

    if not ui.device_online():
        print("ERROR: device not online. Start it first: ./emulator.sh up")
        sys.exit(1)

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
    STATE["addr"] = create_btc_account()
    print(f"   account address: {STATE['addr']}")
    remove_master_key()

    print("-> opening the send screen on the read-only account")
    ui.tap_text(f"{BTC_LABEL} account 1")
    if ui.wait_for("Address", timeout=60) is None:
        raise RuntimeError("account details did not open")
    ui.tap_text(f"Send BTC")
    if ui.wait_for("Recipient Address", timeout=60) is None:
        raise RuntimeError("did not reach the send screen")

    print("-> filling the send form (not submitting yet)")
    fill_send_form(submit=False)
    # A read-only zero-balance account shows the same insufficient-balance
    # warning, but the Send button STAYS enabled: the recovery-phrase guard must
    # remain reachable (it is verified before any on-chain operation).
    check("read-only send: insufficient-balance warning is shown",
          ui.find("Insufficient BTC balance") is not None)
    check("read-only send: Send button stays enabled (phrase guard reachable)",
          ui.button_enabled("Send") is True,
          f"enabled={ui.button_enabled('Send')!r}",
          soft=True)
    print("-> requesting the send")
    ui.tap_text_bottom("Send")

    if ui.wait_for("Recovery Phrase Required", timeout=90) is None:
        check("read-only send: mnemonic prompt appears", False,
              "the 'Recovery Phrase Required' card did not appear")
        ui.show(); ui.shot("stuck-mnemonic")
        sys.exit(1)
    check("read-only send: mnemonic prompt appears", True)
    check("read-only send: prompt explains the phrase is wiped after use",
          ui.find("wiped immediately after use") is not None)
    check("read-only send: no passphrase field (passphrase-less wallet)",
          ui.find("Passphrase") is None)

    print("-> 'Sign & Send' with an EMPTY phrase (must be blocked)")
    ui.tap_text("Sign & Send")
    if ui.wait_for("Enter your recovery phrase", timeout=60) is None:
        check("empty phrase: blocked with a validation error", False, "no error shown")
        ui.shot("stuck-empty")
        sys.exit(1)
    check("empty phrase: blocked with a validation error", True)
    check("empty phrase: still on the mnemonic prompt",
          ui.find("Recovery Phrase Required") is not None)

    print("-> entering a valid-format but WRONG phrase")
    ui.tap_field_below("Recovery Phrase")
    time.sleep(0.8)
    ui.type_onscreen(WRONG_PHRASE)
    time.sleep(1.5)
    if ui.find_by_text(lambda t: t.strip() == WRONG_PHRASE) is None:
        raise RuntimeError("typed wrong phrase not reflected in the field")
    ui.shot("wrong-phrase-typed")
    ui.tap_text("Sign & Send")
    if ui.wait_for("does not match this wallet", timeout=120) is None:
        check("wrong phrase: rejected by the mnemonic guard", False, "no guard error shown")
        ui.show(); ui.shot("stuck-wrongphrase")
        sys.exit(1)
    check("wrong phrase: rejected by the mnemonic guard", True)
    check("wrong phrase: no transaction was broadcast", ui.find("Transaction Sent") is None)
    ui.shot("wrong-phrase-rejected")

    print("-> 'Cancel' wipes the phrase buffer")
    ui.tap_text("Cancel")
    time.sleep(1.5)
    check("cancel: back to the send form (prompt gone)",
          ui.find("Recovery Phrase Required") is None
          and ui.find("Recipient Address") is not None)
    ui.tap_text_bottom("Send")
    if ui.wait_for("Recovery Phrase Required", timeout=60) is None:
        raise RuntimeError("re-requesting the send did not reopen the mnemonic prompt")
    check("cancel: phrase field is empty on re-entry (buffer wiped)",
          ui.find("your twelve or twenty-four word phrase") is not None)

    print("\n=== summary ===")
    failed = 0
    for name, ok, detail, soft in results:
        tag = "PASS" if ok else ("INFO" if soft else "FAIL")
        print(f"  [{tag}] {name}" + (f" — {detail}" if detail else ""))
        failed += 0 if (ok or soft) else 1
    hard = [r for r in results if not r[3]]
    print(f"\n{sum(1 for r in hard if r[1])}/{len(hard)} hard checks passed"
          + (" (INFO checks are non-fatal)" if any(r[3] for r in results) else ""))
    ui.shot("final")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
