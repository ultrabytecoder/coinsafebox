#!/usr/bin/env python3
"""CoinSafeBox desktop UI automation helper (X11/XWayland + tesseract + XTEST).

The Compose desktop app renders via Skiko on X11. On a Wayland session it runs
under XWayland, so it still appears as a normal X11 window on $DISPLAY — which
is what we use here. There is no uiautomator-equivalent UI tree on desktop, so
state is read via screenshots + OCR (tesseract) and input is injected with the
XTEST extension.

Environment:
  KK_DISPLAY   X display to use (default: $DISPLAY or :0)
  KK_WINDOW    window WM_NAME to target (default: CoinSafeBox)

CLI usage (manual poking):
  ui.py                     dump all OCR'd words with boxes
  ui.py texts               all visible text (one per line)
  ui.py find "label"        print the first word box containing "label"
  ui.py tap "label"         click the center of the first matching word
  ui.py digit 5             tap PIN digit 5 on the on-screen numpad
  ui.py grid                dump the detected numpad grid (diagnostics)
  ui.py qr                  detect the QR code (box) + best-effort decode
  ui.py shot [name]         screenshots/<name>.png (default: HHMMSS)
  ui.py wait "label"        poll until "label" is visible (exit 0/1)

Design notes:
  - The app is dark-themed with white primary text and colored buttons. Plain
    grayscale OCR garbles text on colored button backgrounds, so the OCR
    pipeline extracts BRIGHT pixels only: grayscale -> 3x LANCZOS ->
    threshold (min_lum, default 190) -> tesseract psm 11 (sparse text).
    Secondary gray text is invisible to the default threshold; pass
    min_lum=120 to a helper to read it.
  - The XTEST click is a synthetic pointer event at root coordinates, so it
    works without window focus, but the window must be VISIBLE (not covered
    by another window). `raise_window()` is called before taps.
  - A MINIMIZED (unmapped) window cannot be captured (GetImage -> BadMatch)
    and cannot receive clicks. `ensure_viewable()` restores it with a
    MapRequest before every grab/click.
  - Re-query window geometry on every action: the user (or a WM) may have
    moved/resized the window.
"""
import os
import re
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
DISPLAY = os.environ.get("KK_DISPLAY") or os.environ.get("DISPLAY") or ":0"
WINDOW_NAME = os.environ.get("KK_WINDOW", "CoinSafeBox")
SHOTS = os.path.join(HERE, "screenshots")
SCALE = 3          # OCR upscale factor
MIN_LUM = 190      # bright (primary) text threshold (0-255)
MIN_LUM_DIM = 120  # dimmer (secondary/card) text threshold
OCR_PSM = "11"     # sparse text + OSD

import Xlib
from Xlib import X, display as xdisplay
from Xlib.ext import xtest
from PIL import Image

_d = None
_d_for = None


def conn():
    global _d, _d_for
    ds = os.environ.get("KK_DISPLAY") or os.environ.get("DISPLAY") or ":0"
    if _d is None or _d_for != ds:
        _d = xdisplay.Display(ds)
        _d_for = ds
    return _d


def find_window(pid=None):
    """Return (window, x, y, w, h) of the app window, or None.

    If pid is given, only windows whose _NET_WM_PID matches are considered
    (disambiguates against a manually launched real instance).
    """
    d = conn()
    root = d.screen().root
    for w in root.query_tree().children:
        try:
            name = w.get_wm_name() or ""
            if name != WINDOW_NAME:
                continue
            if pid is not None:
                atom = d.intern_atom("_NET_WM_PID")
                wpid = w.get_full_property(atom, X.AnyPropertyType)
                if wpid is None or wpid.value[0] != pid:
                    continue
            g = w.get_geometry()
            if g.width < 100 or g.height < 100:
                continue
            return w, g.x, g.y, g.width, g.height
        except Exception:
            continue
    return None


def wait_window(timeout=90, pid=None, interval=2):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            return ensure_viewable(timeout=interval, pid=pid)
        except RuntimeError:
            pass
    return None


def ensure_viewable(timeout=15, pid=None):
    """Find the app window and make sure it is mapped (un-minimize if needed).

    Returns the (window, x, y, w, h) tuple; raises RuntimeError on timeout.
    GetImage fails (BadMatch) on unmapped windows, and clicks on a hidden
    window are lost, so every grab/click goes through here first. A plain
    MapRequest makes the WM restore a minimized window.
    """
    d = conn()
    t0 = time.time()
    while time.time() - t0 < timeout:
        found = find_window(pid=pid)
        if found:
            w = found[0]
            attrs = w.get_attributes()
            if attrs._data.get("map_state") == 2:  # IsViewable
                return found
            w.map()
            d.sync()
        time.sleep(0.5)
    raise RuntimeError(f"window {WINDOW_NAME!r} not viewable on {DISPLAY} "
                       f"within {timeout}s (minimized or not launched)")


def raise_window(w):
    try:
        w.raise_()
        conn().sync()
    except Exception:
        pass


def grab():
    """Screenshot of the app window as a PIL RGB image. Raises if no window.

    Prefers GetImage on the window itself. Falls back to grabbing the
    window's region from the root when the server rejects window-level
    capture. Xvfb returns BadMatch when the region (or a depth-32 window
    on a 24-bit screen) can't be read directly, so the root-region grab
    is the reliable path on the managed virtual display.
    """
    found = ensure_viewable()
    w, x, y, width, height = found
    try:
        img = w.get_image(0, 0, width, height, X.ZPixmap, 0xffffffff)
    except Xlib.error.XError:
        img = conn().screen().root.get_image(
            x, y, width, height, X.ZPixmap, 0xffffffff)
    data = img.data
    bpp = len(data) // (width * height)  # servers may pad 24-bit to 4 bytes/pix
    if bpp == 4:
        return Image.frombytes("RGB", (width, height), data, "raw", "BGRX")
    if bpp == 3:
        return Image.frombytes("RGB", (width, height), data, "raw", "BGR")
    raise RuntimeError(f"unexpected pixmap size {len(data)} for {width}x{height}")


def _ocr_tsv(min_lum, scale):
    """Run tesseract on a window screenshot; return its TSV lines (level-5 words)."""
    im = grab().convert("L")
    big = im.resize((im.width * scale, im.height * scale), Image.LANCZOS)
    big = big.point(lambda p: 0 if p > min_lum else 255)
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
        big.save(f.name)
        path = f.name
    try:
        tsv = subprocess.run(
            ["tesseract", path, "stdout", "--psm", OCR_PSM, "tsv"],
            capture_output=True, text=True, timeout=60).stdout
    finally:
        os.unlink(path)
    words = []
    for line in tsv.splitlines()[1:]:
        f = line.split("\t")
        if len(f) != 12 or f[0] != "5":  # level 5 = word
            continue
        text = f[11].strip()
        if not text:
            continue
        words.append({
            "text": text,
            "line": (int(f[2]), int(f[3]), int(f[4])),
            "x": int(f[6]) // scale, "y": int(f[7]) // scale,
            "w": int(f[8]) // scale, "h": int(f[9]) // scale,
        })
    return words


def ocr_words(min_lum=MIN_LUM, scale=SCALE):
    """OCR the window; return [(text, x, y, w, h)] word boxes (1x coords)."""
    return [(w["text"], w["x"], w["y"], w["w"], w["h"])
            for w in _ocr_tsv(min_lum, scale)]


def ocr_lines(min_lum=MIN_LUM, scale=SCALE):
    """OCR the window as lines: [(text, x, y, w, h)] in reading order.

    The box is the union of the line's word boxes. Multi-word labels are only
    addressable at line granularity (tesseract returns one box per word).
    """
    groups = {}
    for w in _ocr_tsv(min_lum, scale):
        groups.setdefault(w["line"], []).append(w)
    lines = []
    for words in groups.values():
        x0 = min(w["x"] for w in words)
        y0 = min(w["y"] for w in words)
        x1 = max(w["x"] + w["w"] for w in words)
        y1 = max(w["y"] + w["h"] for w in words)
        text = " ".join(w["text"] for w in words)
        lines.append((text, x0, y0, x1 - x0, y1 - y0))
    lines.sort(key=lambda l: (l[2] // 20, l[1]))  # top-to-bottom, then left
    return lines


def _lums(min_lum):
    """Threshold ladder: explicit value, or bright-then-dim auto fallback."""
    if min_lum is not None:
        return (min_lum,)
    return (MIN_LUM, MIN_LUM_DIM)


def ocr_text(min_lum=None):
    # No explicit threshold -> read at the dimmer level (superset of the text).
    return "\n".join(t for t, *_ in ocr_lines(min_lum or MIN_LUM_DIM))


def _match(needle, text, exact, case):
    if case:
        needle, text = needle.lower(), text.lower()
    return (text == needle) if exact else (needle in text)


def find_text(needle, exact=False, min_lum=None, case=True):
    """Box of the first matching label, or None.

    Single-character needles match a whole word exactly (so '6' does not hit
    '6-digit'); longer needles match anywhere inside a line. Reading order.
    min_lum=None: try the bright threshold first, then the dimmer one.
    """
    single = len(needle.strip()) == 1
    for lum in _lums(min_lum):
        if single:
            for text, x, y, w, h in ocr_words(min_lum=lum):
                if _match(needle, text, exact=True, case=case):
                    return (x, y, w, h)
        else:
            for text, x, y, w, h in ocr_lines(min_lum=lum):
                if _match(needle, text, exact=exact, case=case):
                    return (x, y, w, h)
    return None


def find_all(needle, min_lum=None):
    """All matching boxes (single-char: exact words; else: line substrings)."""
    out = []
    single = len(needle.strip()) == 1
    for lum in _lums(min_lum):
        entries = ocr_words(min_lum=lum) if single else ocr_lines(min_lum=lum)
        for text, x, y, w, h in entries:
            if _match(needle, text, exact=single, case=True):
                out.append((x, y, w, h))
        if out:
            break
    return out


def click_at(x, y):
    """Click at window-relative coordinates via warp + XTEST (root-absolute under the hood)."""
    d = conn()
    found = ensure_viewable()
    w, wx, wy, _, _ = found
    raise_window(w)
    try:
        w.warp_pointer(int(x), int(y))
        d.sync()
    except Exception:
        pass
    rx, ry = wx + int(x), wy + int(y)
    xtest.fake_input(d, X.MotionNotify, x=rx, y=ry)
    d.sync()
    time.sleep(0.05)
    xtest.fake_input(d, X.ButtonPress, detail=1)
    d.sync()
    time.sleep(0.05)
    xtest.fake_input(d, X.ButtonRelease, detail=1)
    d.sync()


def click_text(needle, exact=False, min_lum=None, timeout=20, interval=1.5,
               nth=0):
    """Wait until needle is visible, click it. Returns the box clicked."""
    t0 = time.time()
    while True:
        boxes = find_all(needle, min_lum=min_lum) if nth else \
            [find_text(needle, exact=exact, min_lum=min_lum)]
        boxes = [b for b in boxes if b]
        if boxes:
            box = boxes[min(nth, len(boxes) - 1)]
            click_at(box[0] + box[2] / 2, box[1] + box[3] / 2)
            return box
        if time.time() - t0 > timeout:
            raise TimeoutError(f"timeout waiting for text {needle!r}")
        time.sleep(interval)


def wait_for(needle, timeout=60, exact=False, min_lum=None, interval=1.5):
    """Poll until needle is visible. Returns its box or None."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        box = find_text(needle, exact=exact, min_lum=min_lum)
        if box:
            return box
        time.sleep(interval)
    return None


# ------------------------------------------------- accent-colored glyphs ---

def _accent_mask():
    """Binary mask (PIL 'L') of saturated (accent-colored) pixels.

    Accent UI elements (e.g. the PIN-length option digits) are rendered in the
    theme's primary color — colored, not bright — so the luminance-threshold
    OCR pipeline cannot see them. Chroma (max-min of RGB) picks them out.
    """
    from PIL import ImageChops
    r, g, b = grab().split()
    hi = ImageChops.lighter(ImageChops.lighter(r, g), b)
    lo = ImageChops.darker(ImageChops.darker(r, g), b)
    chroma = ImageChops.subtract(hi, lo)
    return chroma.point(lambda c: 255 if c > 60 else 0)


def _count_holes(mask_im):
    """Number of enclosed background regions inside the mask's ink.

    Digit topology among the app's PIN-length options: '6' has one counter,
    '8' has two.
    """
    im = mask_im.convert("L")
    if min(im.size) > 300:
        im = im.resize((im.width // 4, im.height // 4))
    px = im.load()
    W, H = im.size
    seen = bytearray(W * H)

    def flood(x, y):
        stack = [(x, y)]
        seen[y * W + x] = 1
        while stack:
            cx, cy = stack.pop()
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = cx + dx, cy + dy
                if (0 <= nx < W and 0 <= ny < H and not seen[ny * W + nx]
                        and px[nx, ny] > 127):
                    seen[ny * W + nx] = 1
                    stack.append((nx, ny))

    flood(0, 0)
    holes = 0
    for y in range(H):
        for x in range(W):
            i = y * W + x
            if not seen[i] and px[x, y] > 127:
                holes += 1
                flood(x, y)
    return holes


_HOLES_TO_DIGIT = {1: "6", 2: "8"}  # topology fallback for this app's options


def tap_accent_digit(value, y_band=(0.2, 0.55), timeout=20, interval=1.5):
    """Tap a saturated (accent-colored) digit glyph (e.g. a PIN-length option).

    The app renders these digits in the theme's primary color, which the
    luminance OCR pipeline misses. This masks by chroma, clusters the glyphs
    left-to-right, and identifies each with OCR (psm 8) — falling back to
    counter topology when tesseract cannot read the rounded font.
    """
    value = str(value)
    t0 = time.time()
    while True:
        mask = _accent_mask()
        W, H = mask.size
        y0, y1 = int(y_band[0] * H), int(y_band[1] * H)
        px = mask.load()
        colsum = [0] * W
        for x in range(W):
            colsum[x] = sum(1 for y in range(y0, y1, 2) if px[x, y] > 127)
        runs, in_run = [], False
        for x in range(W):
            if colsum[x] >= 4 and not in_run:
                start, in_run = x, True
            elif colsum[x] < 4 and in_run:
                runs.append((start, x - 1))
                in_run = False
        if in_run:
            runs.append((start, W - 1))
        # Merge runs separated by small gaps (strokes inside one glyph); keep
        # separate glyphs distinct.
        merged = []
        for r in runs:
            if merged and r[0] - merged[-1][1] <= 24:
                merged[-1] = (merged[-1][0], r[1])
            else:
                merged.append(list(r))
        for x0, x1 in merged:
            ys = [y for x in range(x0, x1 + 1) for y in range(y0, y1)
                  if px[x, y] > 127]
            if len(ys) < 30:
                continue
            ytop, ybot = min(ys), max(ys)
            crop = mask.crop((x0 - 8, ytop - 8, x1 + 9, ybot + 9))
            with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
                crop.resize((crop.width * 4, crop.height * 4),
                            Image.LANCZOS).save(f.name)
                path = f.name
            try:
                ocr = subprocess.run(
                    ["tesseract", path, "stdout", "--psm", "8",
                     "-c", "tessedit_char_whitelist=0123456789"],
                    capture_output=True, text=True, timeout=30).stdout.strip()
            finally:
                os.unlink(path)
            digit = ocr if ocr in "0123456789" else \
                _HOLES_TO_DIGIT.get(_count_holes(crop))
            if digit == value:
                click_at((x0 + x1) / 2, (ytop + ybot) / 2)
                return (x0, ytop, x1 - x0, ybot - ytop)

        if time.time() - t0 > timeout:
            raise TimeoutError(f"timeout waiting for accent digit {value!r}")
        time.sleep(interval)


# ---------------------------------------------------------------- numpad ---

# OCR aliases for single digits (tesseract confusions on 3x small glyphs).
DIGIT_ALIASES = {
    "0": {"0", "O", "o", "D"},
    "1": {"1", "I", "l", "i", "|"},
    "2": {"2"},
    "3": {"3"},
    "4": {"4", "A"},
    "5": {"5", "S"},
    "6": {"6", "G", "b"},
    "7": {"7", "T", "t"},
    "8": {"8", "B"},
    "9": {"9", "g", "q"},
}


def _cluster(values, gap=30):
    """1-D clustering: sort values, split where consecutive gap > `gap`."""
    values = sorted(values)
    clusters = [[values[0]]]
    for v in values[1:]:
        if v - clusters[-1][-1] > gap:
            clusters.append([v])
        else:
            clusters[-1].append(v)
    return [sum(c) / len(c) for c in clusters]


def _numpad_candidates():
    """Single-digit boxes in the lower region, via a dedicated low-threshold
    OCR pass (numpad glyphs are dimmer gray than primary text, so the bright
    MIN_LUM pipeline misses them). Returns [(cx, cy, digit)] in window coords.
    """
    im = grab().convert("L")
    W, H = im.size
    y0 = int(0.5 * H)
    scale = 4
    crop = im.crop((0, y0, W, H))
    big = crop.resize((W * scale, (H - y0) * scale), Image.LANCZOS)
    big = big.point(lambda p: 0 if p > 100 else 255)
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
        big.save(f.name)
        path = f.name
    try:
        tsv = subprocess.run(
            ["tesseract", path, "stdout", "--psm", "6", "tsv"],
            capture_output=True, text=True, timeout=60).stdout
    finally:
        os.unlink(path)
    out = []
    for line in tsv.splitlines()[1:]:
        f = line.split("\t")
        if len(f) != 12 or f[0] != "5":
            continue
        text = f[11].strip()
        if len(text) != 1 or text not in "0123456789":
            continue
        x = int(f[6]) // scale
        y = int(f[7]) // scale + y0
        w = int(f[8]) // scale
        h = int(f[9]) // scale
        out.append((x + w / 2, y + h / 2, text))
    return out


def numpad_grid():
    """Detect the on-screen numpad as {digit: (cx, cy)} in window coordinates.

    Uses geometry (single-digit boxes in the lower region clustered into a
    3x4 grid) rather than trusting per-glyph OCR for identification.
    """
    cands = _numpad_candidates()
    if len(cands) < 8:
        return None
    cols = _cluster([c[0] for c in cands])
    rows = _cluster([c[1] for c in cands])
    if len(cols) != 3 or len(rows) != 4:
        return None

    def nearest(v, centers):
        return min(range(len(centers)), key=lambda i: abs(centers[i] - v))

    grid = {}
    for cx, cy, text in cands:
        c, r = nearest(cx, cols), nearest(cy, rows)
        if r < 3:  # rows 0..2 hold 1..9
            digit = str(r * 3 + c + 1)
        elif r == 3 and c == 1:  # bottom-middle holds 0
            digit = "0"
        else:  # bottom corners: gap / backspace
            continue
        if digit not in grid:
            grid[digit] = (cx, cy)
    return grid


def tap_digit(digit, timeout=20, interval=1.5):
    """Tap a digit on the on-screen numpad (geometry-based, alias-verified)."""
    digit = str(digit)
    t0 = time.time()
    last = None
    while True:
        grid = numpad_grid()
        if grid and digit in grid:
            cx, cy = grid[digit]
            click_at(cx, cy)
            return (cx, cy)
        if time.time() - t0 > timeout:
            raise TimeoutError(f"timeout waiting for numpad digit {digit!r} "
                               f"(last grid: {last})")
        last = grid
        time.sleep(interval)


def press_digit(digit):
    return tap_digit(digit)


# --------------------------------------------------------------------- QR ---

def find_qr(min_frac=0.22, sizes=(50, 60, 70, 80, 90, 100), step=5, bright=180):
    """Detect the QR code on screen. Returns (frac, x, y, w, h) in window
    coordinates, or None.

    The app's dark theme renders QR modules in white on a near-black card, so
    a QR is the densest square region of bright pixels (text and the numpad
    never reach this density). Measured on a 2x-downsampled frame with a
    prefix-sum; O(sizes * grid) and a few ms.
    """
    g = grab().convert("L")
    scale = 2
    g = g.resize((g.width // scale, g.height // scale))
    W, H = g.size
    px = g.load()
    pref = [[0] * (W + 1) for _ in range(H + 1)]
    for y in range(H):
        row, prev, s = pref[y + 1], pref[y], 0
        for x in range(W):
            s += 1 if px[x, y] > bright else 0
            row[x + 1] = prev[x + 1] + s

    def rs(x0, y0, x1, y1):
        return pref[y1][x1] - pref[y0][x1] - pref[y1][x0] + pref[y0][x0]

    best = None
    for size in sizes:
        if size > W or size > H:
            continue
        for y0 in range(0, H - size + 1, step):
            for x0 in range(0, W - size + 1, step):
                frac = rs(x0, y0, x0 + size, y0 + size) / (size * size)
                if frac >= min_frac and (best is None or frac > best[0]):
                    best = (frac, x0 * scale, y0 * scale,
                            size * scale, size * scale)
    return best


def decode_qr(box=None):
    """Best-effort decode of the on-screen QR via pyzbar. Returns the payload
    string or None.

    NOTE: the app renders a stylized QR (its finder patterns are not the
    standard 7x7 shapes), which standard decoders reject — so this is a bonus
    verification, not a hard requirement. `box` is (x, y, w, h), e.g. the
    return of find_qr().
    """
    try:
        from pyzbar.pyzbar import decode as _decode, ZBarSymbol
    except ImportError:
        return None
    im = grab()
    if box:
        im = im.crop(box[1:])
    g = im.convert("L")
    for v in (g, g.point(lambda p: 255 - p)):
        for scale in (1, 2, 3):
            big = v.resize((v.width * scale, v.height * scale), Image.LANCZOS)
            for r in _decode(big, [ZBarSymbol.QRCODE]):
                return r.data.decode("utf-8", "replace")
    return None


# --------------------------------------------------------------- keyboard ---

def _keycode_for(d, keysym):
    codes = d.keysym_to_keycodes(keysym)
    for code in range(codes[0], codes[1] + 1):
        try:
            if d.keycode_to_keysym(code, 0) == keysym:
                return code
        except Exception:
            continue
    return None


def press_key(name):
    """Press a named key (e.g. 'Return', 'Escape', 'Tab')."""
    import Xlib.XK as XK
    keysym = getattr(XK, "XK_" + name, None)
    if keysym is None:
        raise ValueError(f"unknown key {name!r}")
    d = conn()
    found = ensure_viewable()
    raise_window(found[0])
    code = _keycode_for(d, keysym)
    if code is None:
        raise RuntimeError(f"no keycode for {name}")
    xtest.fake_input(d, X.KeyPress, detail=code)
    d.sync()
    time.sleep(0.03)
    xtest.fake_input(d, X.KeyRelease, detail=code)
    d.sync()


def type_text(s):
    """Type text via XTEST (X11 keymaps only; for simple ASCII)."""
    import Xlib.XK as XK
    d = conn()
    found = ensure_viewable()
    raise_window(found[0])
    for ch in s:
        keysym = XK.string_to_keysym(ch)
        code = _keycode_for(d, keysym)
        if code is None:
            raise RuntimeError(f"no keycode for {ch!r}")
        xtest.fake_input(d, X.KeyPress, detail=code)
        d.sync()
        time.sleep(0.02)
        xtest.fake_input(d, X.KeyRelease, detail=code)
        d.sync()


# ----------------------------------------------------------------- shots ---

def shot(name=None):
    os.makedirs(SHOTS, exist_ok=True)
    if name is None:
        name = time.strftime("%H%M%S")
    path = os.path.join(SHOTS, f"{name}.png")
    grab().save(path)
    print(f"screenshot: {path}")
    return path


# ------------------------------------------------------------------- CLI ---

def main():
    args = sys.argv[1:]
    if not args or args[0] == "words":
        for text, x, y, w, h in ocr_words():
            print(f"{text!r:30} ({x},{y} {w}x{h})")
    elif args[0] == "texts":
        print(ocr_text())
    elif args[0] == "find" and len(args) >= 2:
        print(find_text(args[1]))
    elif args[0] == "tap" and len(args) >= 2:
        print("tapped", click_text(args[1]))
    elif args[0] == "digit" and len(args) >= 2:
        print("tapped digit", tap_digit(args[1]))
    elif args[0] == "accent" and len(args) >= 2:
        print("tapped accent digit", tap_accent_digit(args[1]))
    elif args[0] == "grid":
        print("grid:", numpad_grid())
    elif args[0] == "qr":
        box = find_qr()
        print("qr:", box)
        if box:
            print("decoded:", decode_qr())
    elif args[0] == "shot":
        shot(args[1] if len(args) >= 2 else None)
    elif args[0] == "wait" and len(args) >= 2:
        sys.exit(0 if wait_for(args[1], timeout=90) else 1)
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main()
