#!/usr/bin/env bash
# Manage the CoinSafeBox test emulator (AVD, boot wait, kill).
#
# Usage:
#   ./emulator.sh up [--headless]     start the AVD (windowed by default) and wait for full boot
#   ./emulator.sh up-nowait [--headless]  start without waiting for boot
#   ./emulator.sh kill                stop the emulator
#   ./emulator.sh status              show adb devices + boot state
#   ./emulator.sh list-avds           list available AVDs
#
# Environment:
#   KK_AVD     AVD name            (default: Medium_Phone)
#   KK_DEVICE  adb serial to use   (default: emulator-5554)
#   ANDROID_HOME                  (default: $HOME/Android/Sdk)
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
AVD="${KK_AVD:-Medium_Phone}"
DEV="${KK_DEVICE:-emulator-5554}"

wait_boot() {
  adb wait-for-device
  local bc i
  for i in $(seq 1 60); do
    bc=$(adb -s "$DEV" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') || true
    [ "$bc" = "1" ] && { echo "booted after ~$((i*5))s"; return 0; }
    sleep 5
  done
  echo "ERROR: emulator did not report sys.boot_completed=1 in 300s" >&2
  return 1
}

case "${1:-}" in
  up|up-nowait)
    HEADLESS=""
    for a in "${@:2}"; do [ "$a" = "--headless" ] && HEADLESS="-no-window -no-boot-anim"; done
    # Never leave a previous instance half-dead: kill it first if present.
    adb -s "$DEV" emu kill >/dev/null 2>&1 || true
    sleep 2
    echo "starting AVD '$AVD' (${HEADLESS:+headless }windowed)"
    nohup "$SDK/emulator/emulator" -avd "$AVD" -no-audio $HEADLESS \
      > /tmp/coinsafebox-emulator.log 2>&1 &
    echo "emulator pid $! (log: /tmp/coinsafebox-emulator.log)"
    [ "${1}" = "up" ] && wait_boot
    ;;
  kill)
    adb -s "$DEV" emu kill
    ;;
  status)
    adb devices
    echo "boot_completed=$(adb -s "$DEV" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    ;;
  list-avds)
    "$SDK/cmdline-tools/latest/bin/avdmanager" list avd
    ;;
  *)
    grep '^# ' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
