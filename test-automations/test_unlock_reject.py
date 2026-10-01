#!/usr/bin/env python3
"""E2E test: PIN unlock rejection (negative security path) on CoinSafeBox Android.

Drives the real UI (adb + uiautomator, see ui.py) on the emulator. A wrong
PIN must be rejected with a visible error and a remaining-attempts count,
keep the app locked, and decrement the counter on every attempt; the
correct PIN must still unlock afterwards.

Steps:
  1. fresh install + onboarding (PIN setup, wallet created)
  2. background (HOME) -> relaunch -> the PIN unlock screen must appear
  3. wrong PIN #1 -> "Incorrect PIN (4 remaining)" and the app stays locked
  4. wrong PIN #2 -> "Incorrect PIN (3 remaining)" and the app stays locked
  5. correct PIN  -> unlock, accounts list appears

Deliberately stops at 2 wrong attempts: PinConfig.MAX_ATTEMPTS is 5 and a
fifth wrong attempt starts a 1-minute lockout that would pollute later runs.

Usage:
  ./test_unlock_reject.py
  ./test_unlock_reject.py --pin 246810

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

# Wrong PINs: full-length, different from each other and the correct one.
WRONG_PINS = ["654321", "135790"]

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
    ui.tap_text("Next")                       # step 0 (name prefilled "My wallet")
    ui.wait_for("Phrase length", timeout=60)
    ui.tap_text("12 words")                   # shorter phrase: faster downstream steps
    ui.tap_text("Next")
    ui.wait_for("additional layer of protection", timeout=60)
    ui.tap_text("No, skip")
    ui.tap_text("Continue")
    ui.wait_for("Your recovery phrase", timeout=90)
    ui.tap_text("Create Wallet")
    if ui.wait_for("Add Account", timeout=120) is None:
        raise RuntimeError("did not reach the accounts list after onboarding")
    print("-> onboarding complete, on accounts list")


def main():
    sys.stdout.reconfigure(line_buffering=True)
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pin", default="123456")
    args = ap.parse_args()
    pin = args.pin
    wrong = [p if p != pin else str((int(p) + 111111) % 10 ** len(p)) for p in WRONG_PINS]

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

    onboard(pin)

    print("-> backgrounding the app (HOME) to trigger the session lock")
    ui.adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(6)
    launch_app()
    if ui.wait_for("Enter your PIN", timeout=90) is None:
        print("ERROR: did not land on the PIN unlock screen after relaunch:")
        ui.show(); ui.shot("stuck"); sys.exit(1)
    check("session locked: PIN screen shown after relaunch", True)

    for i, wp in enumerate(wrong, 1):
        print(f"-> wrong PIN attempt {i}: {wp}")
        ui.enter_pin(wp)
        if ui.wait_for("Incorrect PIN", timeout=90) is None:
            check(f"wrong PIN {i}: rejection shown", False, "no 'Incorrect PIN' error")
            ui.shot(f"stuck-wrongpin-{i}")
            sys.exit(1)
        texts = ui.text_all()
        expected_remaining = f"{5 - i} remaining"
        check(f"wrong PIN {i}: 'Incorrect PIN' with remaining-attempts count",
              any(expected_remaining in t for t in texts),
              f"expected {expected_remaining!r} in {texts}")
        if ui.find("Add Account") is not None:
            check(f"wrong PIN {i}: app stays locked", False, "accounts list is visible")
        else:
            check(f"wrong PIN {i}: app stays locked", True)
        ui.shot(f"wrongpin-{i}")

    print(f"-> correct PIN: {pin}")
    ui.enter_pin(pin)
    if ui.wait_for("Add Account", timeout=120) is None:
        check("correct PIN unlocks to the accounts list", False, "did not reach the accounts list")
        ui.shot("stuck-unlock")
    else:
        check("correct PIN unlocks to the accounts list", True)

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
