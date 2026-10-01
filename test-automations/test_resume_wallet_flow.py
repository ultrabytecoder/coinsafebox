#!/usr/bin/env python3
"""E2E test: resume the create-wallet flow after a session lock.

Covers the "Resume create-wallet flow after session lock" feature: an
in-progress create-wallet flow must survive a session lock (triggered by
backgrounding the app), and after re-authentication the flow resumes where
the user left off — with the REVEAL step clamped to PASSPHRASE (the
generated mnemonic is wiped on lock) and the non-secret settings (wallet
name) preserved from the process-scoped draft.

Steps:
  1. fresh install + onboarding (PIN setup), into the create-wallet flow
  2. set a distinctive wallet name, proceed to the REVEAL screen
     ("Your recovery phrase")
  3. background the app (HOME) -> the session must lock
  4. relaunch -> the PIN unlock screen must appear
  5. unlock -> since no wallet exists yet, the app must return to the
     create-wallet flow and resume at PASSPHRASE (the REVEAL clamp),
     not at REVEAL (new mnemonic) or SETUP (flow from scratch)
  6. back to setup -> assert the wallet name was preserved (draft restore)
  7. complete the flow -> the wallet is created and the accounts list
     shows it under the preserved name

Usage:
  ./test_resume_wallet_flow.py               # PIN 123456
  ./test_resume_wallet_flow.py --pin 246810  # non-default PIN

Environment:
  KK_DEVICE  adb serial (default: emulator-5554)

Assumptions / limits:
  - testnet debug build (package com.ultrabytecoder.coinsafebox.testnet).
  - security method is PIN (password-method unlock is not scripted).
  - always starts from a fresh install: the resume routing after unlock
    requires that NO wallet exists yet.
"""
import argparse
import os
import subprocess
import sys
import time

import ui

PACKAGE = "com.ultrabytecoder.coinsafebox.testnet"
ACTIVITY = "com.ultrabytecoder.coinsafebox.MainActivity"

# Distinctive so draft preservation is distinguishable from the default
# "My wallet" prefill. No spaces: `adb shell input text` needs escaping.
WALLET_NAME = "ZZresume-test"

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


def onboard_to_setup(pin):
    """Fresh install through PIN setup, stopping on the wallet setup screen."""
    print("-> onboarding: welcome -> PIN -> wallet setup")
    ui.tap_text("Get Started")
    ui.tap_text("PIN")                      # PIN card (child label of clickable card)
    ui.wait_for("Choose PIN length", timeout=60)
    ui.tap_text("6", exact=True)            # 6-digit option
    ui.wait_for("Create a PIN", timeout=60)
    ui.enter_pin(pin)
    ui.wait_for("Confirm your PIN", timeout=60)
    ui.enter_pin(pin)
    if ui.wait_for("Set up your wallet", timeout=90) is None:
        raise RuntimeError("did not reach the wallet setup screen")
    print("-> on the wallet setup screen")


def set_wallet_name(name):
    """Replace the prefilled name in the system-IME 'Wallet name' field."""
    print(f"-> setting wallet name to {name!r}")
    if ui.wait_for("My wallet", timeout=45) is None:
        raise RuntimeError("default field value 'My wallet' not found on setup screen")
    ui.tap_text("My wallet")                # tap the field value -> focus + IME
    time.sleep(1.5)
    ui.adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(12):                     # clears "My wallet" + margin
        ui.adb("shell", "input", "keyevent", "KEYCODE_DEL")
    ui.adb("shell", "input", "text", name)
    time.sleep(1.5)
    if ui.find(name, exact=True) is None:
        raise RuntimeError(f"wallet name {name!r} not reflected in the field")
    # Close the IME (BACK is consumed by the open keyboard, not navigation),
    # then prove we are still on the setup screen.
    ui.adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1.5)
    if ui.find("Set up your wallet") is None:
        raise RuntimeError("left the setup screen while dismissing the IME")


def proceed_to_reveal():
    """SETUP -> PASSPHRASE -> REVEAL (skip passphrase, no gesture entropy)."""
    ui.tap_text("Next")
    if ui.wait_for("Phrase length", timeout=60) is None:
        raise RuntimeError("did not reach the phrase-length step")
    ui.tap_text("Next")
    if ui.wait_for("additional layer of protection", timeout=60) is None:
        raise RuntimeError("did not reach the passphrase step")
    ui.tap_text("Continue")                 # "No, skip" is the default selection
    if ui.wait_for("Your recovery phrase", timeout=90) is None:
        raise RuntimeError("did not reach the reveal step")


def main():
    sys.stdout.reconfigure(line_buffering=True)   # progress visible even when piped
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pin", default="123456", help="PIN used for setup/unlock (default 123456)")
    args = ap.parse_args()

    if not ui.device_online():
        print("ERROR: device not online. Start it first: ./emulator.sh up")
        sys.exit(1)

    # Always fresh: resume-after-unlock routing requires no existing wallet.
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
        ui.show()
        ui.shot("stuck")
        sys.exit(1)

    onboard_to_setup(args.pin)
    set_wallet_name(WALLET_NAME)
    check("wallet name set on setup screen", ui.find(WALLET_NAME, exact=True) is not None)

    proceed_to_reveal()
    check("pre-lock: reached REVEAL ('Your recovery phrase')",
          ui.find("Your recovery phrase") is not None)
    ui.shot("pre-lock")

    print("-> backgrounding the app (HOME) to trigger the session lock")
    ui.adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(6)   # onActivityStopped -> lock() -> in-app navigation to unlock
    alive = bool(ui.pidof(PACKAGE))
    check("process survived background (draft is in-memory)", alive,
          "" if alive else "process was killed; in-memory draft cannot be tested")

    print("-> relaunching the app")
    launch_app()
    if ui.wait_for("Enter your PIN", timeout=90) is None:
        print("ERROR: did not land on the PIN unlock screen after relaunch:")
        ui.show()
        ui.shot("stuck")
        sys.exit(1)
    check("session locked: PIN screen shown after relaunch", True)

    print("-> unlocking with PIN")
    ui.enter_pin(args.pin)
    if ui.wait_for("additional layer of protection", timeout=90) is None:
        print("ERROR: did not resume at the passphrase step after unlock:")
        ui.show()
        ui.shot("stuck")
        sys.exit(1)
    check("resumed at PASSPHRASE step (no wallet exists yet)", True)

    xml = ui.dump()
    nodes = ui.nodes(xml)
    at_reveal = any("Your recovery phrase" in (n["text"] + n["desc"]) for n in nodes)
    at_setup = any("Set up your wallet" in (n["text"] + n["desc"]) for n in nodes)
    check("not resumed at REVEAL (would show a NEW mnemonic)", not at_reveal)
    check("not resumed at SETUP (flow from scratch)", not at_setup)
    ui.shot("resumed")

    print("-> verifying the wallet name survived (back to setup)")
    ui.tap_text("Back", exact=True)
    if ui.wait_for("Set up your wallet", timeout=60) is None:
        raise RuntimeError("back navigation from passphrase did not reach setup")
    check("wallet name preserved in restored draft",
          ui.find(WALLET_NAME, exact=True) is not None)

    print("-> completing the flow")
    ui.tap_text("Next")
    ui.wait_for("Phrase length", timeout=60)
    ui.tap_text("Next")
    ui.wait_for("additional layer of protection", timeout=60)
    ui.tap_text("Continue")
    ui.wait_for("Your recovery phrase", timeout=90)
    ui.tap_text("Create Wallet")
    check("wallet created: accounts list reached",
          ui.wait_for("Add Account", timeout=90) is not None)
    check("accounts list shows the preserved wallet name",
          ui.find(WALLET_NAME, exact=True) is not None)

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
