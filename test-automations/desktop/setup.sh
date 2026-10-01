#!/usr/bin/env bash
# One-time setup for the desktop E2E suite:
#   - creates ./.venv with python-xlib + pillow (+ pyzbar for QR decode)
#   - verifies tesseract, Xvfb and an X display are available
#
# Requires (system packages, install with your package manager):
#   tesseract-ocr          (OCR)
#   xvfb                   (virtual X display; see below)
#   python3-venv           (virtualenv support)
#   libzbar0               (optional — best-effort QR decoding)
#
# The suite runs the app on a dedicated Xvfb display by default (KK_USE_XVFB=1).
# A real X display (KK_USE_XVFB=0, KK_DISPLAY=:0) also works, but on Wayland
# sessions the app renders via XWayland and XTEST input is only delivered while
# the window holds compositor focus — not reliable for unattended runs.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

if ! command -v tesseract >/dev/null; then
  echo "ERROR: tesseract not found. Install it (e.g. 'sudo apt install tesseract-ocr')." >&2
  exit 1
fi
if ! python3 -m venv --help >/dev/null 2>&1; then
  echo "ERROR: python3-venv unavailable. Install it (e.g. 'sudo apt install python3-venv')." >&2
  exit 1
fi
if ! command -v Xvfb >/dev/null; then
  echo "WARNING: Xvfb not found — install it for default (virtual display) runs:" >&2
  echo "         sudo apt install xvfb" >&2
fi
# Optional: QR decoding of the details-screen code (libzbar). The QR presence
# check (ui.find_qr) does NOT need it — only the best-effort decode does.
if ! ldconfig -p 2>/dev/null | grep -q 'libzbar\.so'; then
  echo "WARNING: libzbar not found — install it for best-effort QR decoding:" >&2
  echo "         sudo apt install libzbar0" >&2
fi

if [[ ! -x "$HERE/.venv/bin/python" ]]; then
  echo "Creating $HERE/.venv ..."
  python3 -m venv "$HERE/.venv"
fi
"$HERE/.venv/bin/pip" install -q --upgrade pip
"$HERE/.venv/bin/pip" install -q python-xlib pillow
"$HERE/.venv/bin/pip" install -q pyzbar || echo "WARNING: pyzbar install failed (QR decode disabled)" >&2
"$HERE/.venv/bin/python" -c "import Xlib, PIL; print('venv ready: Xlib', Xlib.__version__, '/ Pillow', PIL.__version__)"

if command -v Xvfb >/dev/null; then
  Xvfb :99 -screen 0 1280x1000x24 -nolisten tcp &
  XPID=$!
  sleep 1
  DISPLAY=:99 "$HERE/.venv/bin/python" - <<'PY'
from Xlib import display
d = display.Display(":99")
assert d.query_extension("XTEST") is not None, "XTEST extension missing on Xvfb"
print("Xvfb :99: XTEST available")
PY
  kill "$XPID" 2>/dev/null || true
else
  echo "(skipping Xvfb check)"
fi
