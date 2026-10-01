#!/usr/bin/env bash
# Manage the CoinSafeBox desktop app for E2E: isolated home, fixed network,
# and (by default) a dedicated virtual X display.
#
#   ./desktop-app.sh up [--fresh]     build (if needed) + launch, wait for window
#   ./desktop-app.sh down              stop the E2E instance (and its Xvfb)
#   ./desktop-app.sh status            is the E2E instance up?
#
# The app is launched with -Duser.home=$KK_E2E_HOME, so ALL app state (DB,
# settings, file key backend) lives under $KK_E2E_HOME/.coinsafebox and never
# touches the real ~/.coinsafebox. Only that isolated instance is ever killed
# (matched by the -Duser.home marker, so a real user-launched app is safe).
#
# Why a virtual display: on a Wayland session the app renders via XWayland,
# and XTEST button events are only delivered while the window holds
# compositor focus (which the suite cannot force from the X11 side). On a
# dedicated Xvfb display XTEST works unconditionally and deterministically,
# and the run never disturbs the user's desktop. Install once:
#   sudo apt install xvfb
#
# Environment:
#   KK_NETWORK    testnet|mainnet (default: testnet)
#   KK_E2E_HOME   isolated home dir (default: /tmp/kk-e2e)
#   KK_USE_XVFB   1 = manage Xvfb ourselves (default), 0 = use KK_DISPLAY as-is
#   KK_DISPLAY    explicit X display (used when KK_USE_XVFB=0)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
NET="${KK_NETWORK:-testnet}"
HOME_DIR="${KK_E2E_HOME:-/tmp/kk-e2e}"
USE_XVFB="${KK_USE_XVFB:-1}"
XVFB_PIDS_FILE="$HOME_DIR/.xvfb.pids"
JAR="$ROOT/dist/CoinSafeBox-$NET.jar"
JAR_ARG="-Duser.home=$HOME_DIR"

case "$NET" in
  testnet|mainnet) ;;
  *) echo "ERROR: KK_NETWORK must be 'testnet' or 'mainnet' (got '$NET')" >&2; exit 2 ;;
esac

python_bin() {
  if [[ -x "$HERE/.venv/bin/python" ]]; then echo "$HERE/.venv/bin/python"; else echo "python3"; fi
}
PYTHON_BIN="$(python_bin)"

# Match only OUR instance (the -Duser.home marker is unique to E2E launches).
# NOTE: the pattern starts with a dash — `--` is required or pgrep/pkill parse
# it as an option.
app_pid() { pgrep -f -- "$JAR_ARG" | head -n1 || true; }

start_xvfb() {
  for n in 99 100 101; do
    if ! ls "/tmp/.X11-unix/X$n" >/dev/null 2>&1; then
      echo "Starting Xvfb :$n ..."
      Xvfb ":$n" -screen 0 1280x1000x24 -nolisten tcp >/dev/null 2>&1 &
      echo $! >> "$XVFB_PIDS_FILE"
      sleep 1
      if ls "/tmp/.X11-unix/X$n" >/dev/null 2>&1; then
        DISPLAY=":$n"
        return 0
      fi
    fi
  done
  return 1
}

stop_xvfb() {
  if [[ -f "$XVFB_PIDS_FILE" ]]; then
    while read -r p; do kill "$p" 2>/dev/null || true; done < "$XVFB_PIDS_FILE"
    rm -f "$XVFB_PIDS_FILE"
  fi
}

case "${1:-}" in
  up)
    FRESH=0
    [[ "${2:-}" == "--fresh" ]] && FRESH=1
    if [[ -n "$(app_pid)" ]]; then
      if [[ "$FRESH" -eq 1 ]]; then
        echo "Killing existing E2E instance..."
        pkill -f -- "$JAR_ARG" || true
        sleep 2
      else
        echo "Already running (pid $(app_pid)). Use --fresh to restart clean."
        exit 0
      fi
    fi
    if [[ ! -f "$JAR" ]]; then
      echo "Jar not found ($JAR) — building it..."
      (cd "$ROOT" && ./build-desktop.sh --network="$NET")
    fi
    if [[ "$FRESH" -eq 1 ]]; then
      echo "Resetting isolated home ($HOME_DIR)..."
      stop_xvfb || true
      rm -rf "$HOME_DIR"
    fi
    mkdir -p "$HOME_DIR"

    if [[ "$USE_XVFB" -eq 1 ]]; then
      if ! command -v Xvfb >/dev/null; then
        echo "ERROR: Xvfb not installed. Run: sudo apt install xvfb" >&2
        exit 1
      fi
      if [[ ! -f "$XVFB_PIDS_FILE" ]]; then
        start_xvfb || { echo "ERROR: could not start Xvfb" >&2; exit 1; }
      fi
    else
      DISPLAY="${KK_DISPLAY:-${DISPLAY:-:0}}"
    fi

    echo "$DISPLAY" > "$HOME_DIR/.display"
    echo "Launching (display=$DISPLAY network=$NET home=$HOME_DIR)..."
    DISPLAY="$DISPLAY" nohup java $JAR_ARG -jar "$JAR" >> "$HOME_DIR/app.log" 2>&1 &
    PID=$!
    echo "$PID" > "$HOME_DIR/app.pid"
    echo "pid $PID — waiting for window..."
    if KK_DISPLAY="$DISPLAY" "$PYTHON_BIN" - <<PY
import sys
sys.path.insert(0, "$HERE")
import ui
sys.exit(0 if ui.wait_window(timeout=120, pid=$PID) else 1)
PY
    then
      echo "UP: window visible (pid $PID, display $DISPLAY)"
    else
      echo "ERROR: window did not appear in 120s; log tail:" >&2
      tail -n 20 "$HOME_DIR/app.log" >&2 || true
      exit 1
    fi
    ;;
  down)
    if [[ -n "$(app_pid)" ]]; then
      pkill -f -- "$JAR_ARG" || true
      echo "stopped"
    else
      echo "not running"
    fi
    if [[ "$USE_XVFB" -eq 1 ]]; then
      stop_xvfb
    fi
    ;;
  status)
    PID="$(app_pid)"
    if [[ -n "$PID" ]]; then
      echo "running (pid $PID, display ${DISPLAY:-?})"
    else
      echo "not running"
      exit 1
    fi
    ;;
  *)
    echo "usage: $0 up [--fresh] | down | status" >&2
    exit 2
    ;;
esac
