#!/usr/bin/env python3
"""E2E test: export mnemonic (re-authentication-gated) on CoinSafeBox Android.

Drives the real UI (adb + uiautomator, see ui.py) on the emulator. Exporting
a wallet's recovery phrase requires re-entering the PIN (a second factor on
top of the unlocked session) and the phrase is masked until explicitly
revealed:

  1. fresh install + onboarding; on the REVEAL screen read the generated
     12-word phrase (tap "Show") and create the wallet.
  2. Manage Wallets -> Export Mnemonic -> the re-authentication screen must
     appear ("Verify your identity to view the recovery phrase").
  3. wrong PIN -> rejected with "Incorrect PIN (4 remaining)".
  4. correct PIN -> the phrase is shown MASKED (bullets only).
  5. tap "Show" -> the revealed phrase matches the one read at creation
     time (the right wallet's phrase, not a placeholder).
  6. the "Copy to clipboard" action is present and enabled once revealed.

Usage:
  ./test_export_mnemonic.py
  ./test_export_mnemonic.py --pin 246810

Environment:
  KK_DEVICE  adb serial (default: emulator-5554)

Assumptions / limits:
  - testnet debug build (package com.ultrabytecoder.coinsafebox.testnet).
  - security method is PIN (password-method re-auth is not scripted).
  - always starts from a fresh install.
  - phrase is 12 words (chosen on the phrase-length step).
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

PHRASE_RE = re.compile(r"^[a-z]+( [a-z]+){11}$")
MASK_RE = re.compile(r"^\u2022{40,}$")

results = []
STATE = {"phrase": None}


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
    ui.tap_text("Next")
    ui.wait_for("Phrase length", timeout=60)
    ui.tap_text("12 words")
    ui.tap_text("Next")
    ui.wait_for("additional layer of protection", timeout=60)
    ui.tap_text("No, skip")
    ui.tap_text("Continue")
    if ui.wait_for("Your recovery phrase", timeout=90) is None:
        raise RuntimeError("did not reach the reveal screen")


def main():
    sys.stdout.reconfigure(line_buffering=True)
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pin", default="123456")
    args = ap.parse_args()
    pin = args.pin
    wrong = "654321" if pin != "654321" else "135790"

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

    onboard_to_reveal(pin)
    ui.tap_text("Show")
    time.sleep(1.0)
    n = ui.find_by_text(lambda t: PHRASE_RE.match(t.strip()))
    if n is None:
        raise RuntimeError("generated mnemonic not readable on the reveal screen")
    STATE["phrase"] = n["text"].strip()
    print("-> mnemonic read from the reveal screen")
    ui.tap_text("Create Wallet")
    if ui.wait_for("Add Account", timeout=120) is None:
        raise RuntimeError("wallet was not created")

    print("-> exporting the mnemonic (re-auth required)")
    if ui.wait_for("More", timeout=45) is None:
        raise RuntimeError("accounts-list 'More' overflow not found")
    ui.tap_node(ui.find("More", exact=True))
    ui.wait_for("Export Mnemonic", timeout=30)
    ui.tap_text("Export Mnemonic")

    # Re-authentication gate
    if ui.wait_for("Verify your identity to view the recovery phrase", timeout=90) is None:
        print("ERROR: re-authentication screen did not appear:")
        ui.show(); ui.shot("stuck"); sys.exit(1)
    check("export requires re-authentication (PIN screen shown)", True)

    print(f"-> wrong PIN: {wrong}")
    ui.enter_pin(wrong)
    if ui.wait_for("Incorrect PIN", timeout=90) is None:
        check("wrong PIN rejected on export re-auth", False, "no 'Incorrect PIN' error")
        ui.shot("stuck-wrongpin")
        sys.exit(1)
    texts = ui.text_all()
    check("wrong PIN rejected with remaining-attempts count",
          any("4 remaining" in t for t in texts), str(texts))
    check("still on the re-auth screen (not revealed)",
          ui.find("Verify your identity") is not None)

    print(f"-> correct PIN: {pin}")
    ui.enter_pin(pin)
    # The loaded state shows the masked phrase; wait for the copy action,
    # which only exists in the revealed/loaded state.
    if ui.wait_for("Copy to clipboard", timeout=120) is None:
        check("correct PIN reveals the export screen", False, "no 'Copy to clipboard' action")
        ui.show(); ui.shot("stuck-export")
        sys.exit(1)
    check("correct PIN reveals the export screen", True)

    texts = ui.text_all()
    masked = [t for t in texts if MASK_RE.match(t.strip())]
    check("phrase masked by default (bullets only)",
          bool(masked) and not any(PHRASE_RE.match(t.strip()) for t in texts),
          masked[0] if masked else "no bullet string found")

    print("-> revealing the phrase")
    ui.tap_text("Show")
    time.sleep(1.0)
    n = ui.find_by_text(lambda t: PHRASE_RE.match(t.strip()))
    revealed = n["text"].strip() if n else None
    check("revealed phrase matches the one read at creation",
          revealed == STATE["phrase"], revealed or "no phrase text found")
    copy = ui.find("Copy to clipboard")
    check("'Copy to clipboard' action present and enabled",
          copy is not None and copy["enabled"], str(copy))
    ui.shot("export-revealed")

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
