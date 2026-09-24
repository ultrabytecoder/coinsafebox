#!/usr/bin/env python3
"""CoinSafeBox Android UI automation helper (adb + uiautomator).

Environment:
  KK_DEVICE  adb serial of the target device (default: emulator-5554)

CLI usage:
  ui.py                     dump UI, list labeled/clickable nodes with tap coords
  ui.py tap X Y             tap at absolute coordinates
  ui.py find "label"        print the first node whose text/desc contains "label"
  ui.py shot [name]         screenshot -> screenshots/<name>.png (default: HHMMSS)

Design notes (learned the hard way):
  - Compose renders every element as a generic android.view.View; the
    clickable node is usually the PARENT wrapper with empty text, and the
    visible label sits in a child TextView. So: match by label, tap the
    label's center (the tap lands inside the clickable parent and fires it).
  - `uiautomator dump` right after a navigation can return the PREVIOUS
    screen (or an empty file) because the UI is not idle yet. Every reader
    here re-dumps on demand; wait_for() polls.
  - Coordinates are resolution-dependent. Always prefer wait_for/tap_text
    over hard-coded X,Y. The 1080x2400 coords seen in logs are only for
    manual debugging.
"""
import os
import re
import subprocess
import sys
import time

DEVICE = os.environ.get("KK_DEVICE", "emulator-5554")
HERE = os.path.dirname(os.path.abspath(__file__))


def adb(*args, timeout=60):
    return subprocess.run(["adb", "-s", DEVICE, *args],
                          capture_output=True, text=True, timeout=timeout)


def device_online(retries=30):
    for _ in range(retries):
        out = adb("get-state")
        if out.returncode == 0 and out.stdout.strip() == "device":
            return True
        time.sleep(2)
    return False


def pidof(pkg):
    """Pid of the package's main process, or '' if not running."""
    return adb("shell", "pidof", pkg).stdout.strip().split()


def focused_window_pkg():
    """Package of the currently focused window (from dumpsys window), or ''.

    Line looks like: mCurrentFocus=Window{3f u0 <pkg>/<Activity>}, so scan the
    tokens for the one containing '/'.
    """
    out = adb("shell", "dumpsys", "window").stdout
    for line in out.splitlines():
        if "mCurrentFocus=" in line:
            w = line.split("mCurrentFocus=", 1)[1].strip()
            for token in w.split():
                if "/" in token:
                    return token.split("/", 1)[0]
            return ""
    return ""


def wait_pid_gone(pkg, timeout=60):
    """Wait until the package has no running process (post-uninstall teardown)."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        if not pidof(pkg):
            return True
        time.sleep(2)
    return False


def wait_focused(pkg, timeout=90):
    """Wait until the package owns the focused window (new instance is on top)."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        if focused_window_pkg() == pkg:
            return True
        time.sleep(2)
    return False


def dump(retries=3):
    """Return the current UI hierarchy XML, retrying until non-empty."""
    for _ in range(retries):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        xml = adb("shell", "cat", "/sdcard/ui.xml").stdout
        if xml and "<node" in xml:
            return xml
        time.sleep(1)
    raise RuntimeError(f"uiautomator dump failed on {DEVICE}")


def nodes(xml=None):
    """Parse dump XML into a list of node dicts (document order)."""
    if xml is None:
        xml = dump()
    out = []
    for m in re.finditer(r"<node\b[^>]*>", xml):
        t = m.group(0)

        def g(attr):
            mm = re.search(attr + r'="([^"]*)"', t)
            return mm.group(1) if mm else ""

        bnd = g("bounds")
        cx = cy = None
        if bnd:
            rb = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bnd)
            if rb:
                x1, y1, x2, y2 = map(int, rb.groups())
                cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        out.append({
            "text": g("text"),
            "desc": g("content-desc"),
            "cls": g("class").split(".")[-1],
            "bounds": bnd,
            "cx": cx,
            "cy": cy,
            "clickable": g("clickable") == "true",
            "enabled": g("enabled") == "true",
        })
    return out


def _match(n, label, exact=False):
    for field in ("text", "desc"):
        v = n[field]
        if not v:
            continue
        if (v == label) if exact else (label.lower() in v.lower()):
            return True
    return False


def find(label, exact=False, xml=None):
    """First node whose text or content-desc matches (default: substring, case-insensitive)."""
    for n in nodes(xml):
        if _match(n, label, exact):
            return n
    return None


def find_by_text(pred, xml=None):
    """First node whose text satisfies pred(str). E.g. lambda t: t.startswith('m/')."""
    for n in nodes(xml):
        if n["text"] and pred(n["text"]):
            return n
    return None


def wait_for(label, timeout=60, exact=False, interval=2.0):
    """Poll until a node matching `label` appears (fresh dump per poll).

    NOTE: each poll costs one full `uiautomator dump`, which blocks until the
    UI is idle — that can be 10-30s on slow AVDs. So `timeout` is a total
    deadline, not a poll interval; keep it generous (>= 3x one dump).
    """
    t0 = time.time()
    while True:
        n = find(label, exact=exact)
        if n:
            return n
        if time.time() - t0 > timeout:
            return None
        time.sleep(interval)


def tap(x, y, settle=0.4):
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(settle)


def tap_node(n, settle=0.4):
    if n["cx"] is None:
        raise RuntimeError(f"node has no bounds: {n}")
    tap(n["cx"], n["cy"], settle)
    return n


def tap_text(label, timeout=20, exact=False):
    """Wait for a node matching `label` and tap it. Raises if not found in time."""
    n = wait_for(label, timeout=timeout, exact=exact)
    if n is None:
        raise RuntimeError(f"node not found within {timeout}s: '{label}'")
    return tap_node(n)


def tap_digit(d):
    """Tap an on-screen numpad digit (exact text/desc match)."""
    n = find(str(d), exact=True)
    if n is None:
        raise RuntimeError(f"numpad digit {d} not found")
    tap_node(n)
    time.sleep(0.25)


def enter_pin(pin):
    """Tap a PIN on the numpad. ONE dump: locate all digits, then tap in
    sequence (a per-digit dump would cost 10-30s each on slow AVDs)."""
    xml = dump()
    for ch in str(pin):
        n = find(ch, exact=True, xml=xml)
        if n is None:
            raise RuntimeError(f"numpad digit {ch} not found")
        tap_node(n, settle=0.3)


def text_all():
    """All non-empty texts currently on screen (for debugging)."""
    return [n["text"] for n in nodes() if n["text"]]


def shot(name=None):
    d = os.path.join(HERE, "screenshots")
    os.makedirs(d, exist_ok=True)
    name = name or time.strftime("%Y%m%d-%H%M%S")
    path = os.path.join(d, f"{name}.png")
    adb("shell", "screencap", "/sdcard/kk_shot.png")
    subprocess.run(["adb", "-s", DEVICE, "pull", "/sdcard/kk_shot.png", path],
                   capture_output=True)
    print(f"screenshot: {path}")
    return path


def show():
    for n in nodes():
        label = n["text"] or n["desc"]
        if not label and not n["clickable"]:
            continue
        mark = "TAP " if n["clickable"] else "    "
        en = "" if n["enabled"] else " [DISABLED]"
        print(f"{mark} ({n['cx']},{n['cy']}) [{n['cls']}]{en} '{label}'")


if __name__ == "__main__":
    if len(sys.argv) > 1:
        cmd = sys.argv[1]
        if cmd == "tap":
            tap(int(sys.argv[2]), int(sys.argv[3]), settle=0)
            print("tapped", sys.argv[2], sys.argv[3])
        elif cmd == "find":
            n = find(sys.argv[2])
            print(n if n else "NOT FOUND")
        elif cmd == "shot":
            shot(sys.argv[3] if len(sys.argv) > 3 else None)
        elif cmd == "texts":
            for t in text_all():
                print(t)
        else:
            print(__doc__)
            sys.exit(2)
    else:
        show()
