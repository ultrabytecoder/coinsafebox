#!/usr/bin/env python3
"""E2E test: send-screen validation guards on CoinSafeBox Android.

Drives the real UI (adb + uiautomator, see ui.py) on the emulator. Fund-
independent: it exercises the send screen's input + validation state machine
on an UNFUNDED account, using the app's custom on-screen keyboards (the
recipient/amount fields are deliberately out of the IME focus chain, so the
test taps the keyboard keys — see ui.type_onscreen):

  1. fresh install + onboarding + a BTC account (zero balance).
  2. account details -> "Send BTC" -> the send screen shows Available
     Balance / Recipient Address / Amount.
  3. "Send" with both fields empty -> nothing happens (no navigation).
  4. amount "0.001" typed on the numeric keyboard -> the field reflects it;
     fee estimation on the unfunded account should report an error
     ("No UTXOs ..." / "Fee estimation failed ..."). This is an INFO check:
     on this slow AVD the estimation result is racy (live fee-rate re-fetches
     re-run it), so a miss does not fail the run.
  5. a valid recipient address typed on the Qwerty keyboard -> the field
     reflects it.
  6. "Send" with both fields filled on the zero-balance account -> the
     broadcast is guarded: no Transaction Sent screen, no read-only
     mnemonic prompt (this is a full wallet), stays on the send screen.

Usage:
  ./test_send_guards.py
  ./test_send_guards.py --pin 246810

Environment:
  KK_DEVICE  adb serial (default: emulator-5554)

Assumptions / limits:
  - testnet debug build (package com.ultrabytecoder.coinsafebox.testnet).
  - security method is PIN (password-method unlock is not scripted).
  - always starts from a fresh install; the account is never funded, so no
    transaction can be broadcast.
  - fee estimation needs network reachability for the testnet RPC; the
    zero-UTXO error is asserted leniently (either the UTXO error or a
    network-level fee error).
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
    """Create a BTC account, read its address, return to the list."""
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


def open_send_screen():
    ui.tap_text(f"{BTC_LABEL} account 1")
    if ui.wait_for("Address", timeout=60) is None:
        raise RuntimeError("account details did not open")
    ui.tap_text(f"Send BTC")
    if ui.wait_for("Recipient Address", timeout=60) is None:
        raise RuntimeError("did not reach the send screen")


FEE_ERROR_MARKERS = ("No UTXOs", "Fee estimation failed", "Insufficient funds")


def poll_fee_error(deadline):
    """Poll (fresh dumps) until a fee-error string is on screen or the deadline
    passes. Returns the matched text, or None."""
    t0 = time.time()
    while time.time() - t0 < deadline:
        for t in ui.text_all():
            for marker in FEE_ERROR_MARKERS:
                if marker in t:
                    return t
        time.sleep(3)
    return None


def retrigger_estimation():
    """Nudge the fee estimation to re-run: append a '0' to the amount and delete
    it. (The estimation LaunchedEffect keys on the amount value.)"""
    ui.tap_field_below("Amount")
    time.sleep(0.8)
    ui.type_onscreen("0")
    time.sleep(0.4)
    bk = ui.find("⌫", exact=True)
    if bk is not None:
        ui.tap_node(bk)
    time.sleep(1.0)


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

    print("-> opening the send screen")
    open_send_screen()
    check("send screen shows Available Balance", ui.find("Available Balance") is not None)
    check("send screen shows Recipient Address field", ui.find("Recipient Address") is not None)
    check("send screen shows Amount field", ui.find("Amount") is not None)

    print("-> 'Send' with empty fields must not navigate")
    ui.tap_text_bottom("Send")
    time.sleep(2.0)
    check("empty send: still on the send screen (no navigation)",
          ui.find("Recipient Address") is not None
          and ui.find("Transaction Sent") is None)

    print(f"-> typing amount {AMOUNT} on the numeric on-screen keyboard")
    ui.tap_field_below("Amount")
    time.sleep(0.8)
    ui.type_onscreen(AMOUNT)
    time.sleep(1.5)
    check("amount field reflects the typed value", ui.find(AMOUNT, exact=True) is not None)

    # The unfunded account's fee estimation should surface an error (no UTXOs).
    # On this slow AVD the estimation result is RACY: live fee-rate re-fetches
    # re-run the estimation and can clear/re-set the error, so we poll a window
    # and re-trigger the estimation once if the first window misses it. This is
    # an INFO check (soft) — the binding send guards are the zero-balance checks
    # below, which must never broadcast.
    fee_err = poll_fee_error(60)
    if fee_err is None:
        print("   fee error not seen in the first window; re-triggering estimation")
        retrigger_estimation()
        fee_err = poll_fee_error(60)
    check("unfunded account: fee estimation reports an error",
          fee_err is not None, fee_err or "no fee error seen in either window (racy on slow AVD)",
          soft=True)

    print("-> typing the recipient address on the Qwerty on-screen keyboard")
    ui.tap_field_below("Recipient Address")
    time.sleep(0.8)
    ui.type_onscreen(STATE["addr"])
    time.sleep(1.5)
    check("recipient field reflects the typed address",
          ui.find(STATE["addr"], exact=True) is not None)
    ui.shot("send-filled")

    print("-> 'Send' with filled fields on a zero-balance account")
    # Hard-blocking: a full wallet that can't cover amount+fee shows an
    # insufficient-balance warning and a DISABLED Send button; the tap is a no-op.
    check("zero-balance send: insufficient-balance warning is shown",
          ui.find("Insufficient BTC balance") is not None)
    check("zero-balance send: Send button is disabled",
          ui.button_enabled("Send") is False,
          f"enabled={ui.button_enabled('Send')!r}",
          soft=True)
    ui.tap_text_bottom("Send")
    # The broadcast must be guarded: no Transaction Sent screen may appear.
    time.sleep(8)
    leaked = False
    t0 = time.time()
    while time.time() - t0 < 60:
        if ui.find("Transaction Sent") is not None:
            leaked = True
            break
        time.sleep(2)
    check("zero-balance send: no transaction was broadcast", not leaked)
    check("zero-balance send: no read-only mnemonic prompt (full wallet)",
          ui.find("Recovery Phrase Required") is None)
    check("zero-balance send: still on the send screen",
          ui.find("Recipient Address") is not None)
    ui.shot("send-guarded")

    ui.tap_text("Back")
    ui.wait_for("Add Account", timeout=45)

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
