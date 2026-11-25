#!/usr/bin/env python3
"""
Bromide - in-the-field film emulation for a Sony camera + Android phone.

The camera FTPs each photo (JPEG and/or RAW) to your phone over its Wi-Fi
hotspot. Bromide "develops" it as a real film stock would render it and saves
the result to your gallery.

The color and tone come from spektrafilm (github.com/andreavolpato/spektrafilm),
a spectral simulation of film built from Kodak and Fujifilm datasheets: the
negative's spectral sensitivities, density curves, coupler effects, and the
paper it's printed on. The full simulation is far too slow for a phone, so each
stock's color response was baked into a 65x65x65 LUT (bromide_luts.npz). The
spatial effects a LUT can't hold - halation and grain - are done here, with
halation following spektrafilm's physical model.

  python bromide.py serve                      start FTP server + auto-develop
  python bromide.py develop IMG.JPG -s gold200 develop a file or a folder
  python bromide.py sheet IMG.JPG              contact sheet of several stocks
  python bromide.py stocks                     list film stocks

Pick the film from the camera: each stock is an FTP username, so set up one
camera FTP server profile per film you like (user "portra400", "gold200", ...).

Phone setup (Termux, from F-Droid):
  pkg update && pkg install python python-numpy python-pillow
  pip install pyftpdlib
  termux-setup-storage
  mkdir -p ~/bromide && cp /sdcard/Download/bromide.py /sdcard/Download/bromide_luts.npz ~/bromide/
  cd ~/bromide && python bromide.py serve

Phone storage: serve saves 2048px film versions (about 1 MB each; --max-size 0
for full size) and deletes the camera's files from the phone. Your originals
stay on the camera card. Each phone photo carries its recipe (stock, print
exposure, grain seed), so at home you can rebuild full-resolution versions:
  python bromide.py redo PHONE_FOLDER CARD_FOLDER -o full_res/

RAW files: decoded with rawpy if installed, otherwise LibRaw's dcraw_emu
(Termux: pkg install libraw). If neither works, Bromide keeps using the JPEG.
With RAW+JPEG, the JPEG is developed first and replaced by the RAW version.

License: GPLv3 (it implements spektrafilm's methods). The baked LUTs are
CC BY-SA 4.0, (c) Andrea Volpato / spektrafilm - see LUT_LICENSE.txt.
Film modeling powered by spektrafilm.
"""
import argparse
import json
import os
import queue
import socket
import struct
import subprocess
import sys
import threading
import time
import zlib

import numpy as np
from PIL import Image, ImageDraw, ImageOps

HERE = os.path.dirname(os.path.abspath(__file__))

# --------------------------------------------------------------------------
# Film stocks. "lut" names a baked spektrafilm LUT; "halation" is the
# (film base, anti-halation layer) pair from the stock's datasheet profile;
# "iso" drives grain. All LUTs are printed on the paper the profile targets.
# --------------------------------------------------------------------------
STOCKS = {
    "portra160":     dict(name="Kodak Portra 160", lut="portra160", iso=160, halation=("still", "strong")),
    "portra400":     dict(name="Kodak Portra 400", lut="portra400", iso=400, halation=("still", "strong")),
    "portra800":     dict(name="Kodak Portra 800", lut="portra800", iso=800, halation=("still", "strong")),
    "gold200":       dict(name="Kodak Gold 200", lut="gold200", iso=200, halation=("still", "weak")),
    "ultramax400":   dict(name="Kodak Ultramax 400", lut="ultramax400", iso=400, halation=("still", "weak")),
    "ektar100":      dict(name="Kodak Ektar 100", lut="ektar100", iso=100, halation=("still", "strong")),
    "c200":          dict(name="Fujifilm C200", lut="c200", iso=200, halation=("still", "weak")),
    "superia400":    dict(name="Fujifilm Superia X-Tra 400", lut="superia400", iso=400, halation=("still", "weak")),
    "pro400h":       dict(name="Fujifilm Pro 400H", lut="pro400h", iso=400, halation=("still", "strong")),
    "vision250d":    dict(name="Kodak Vision3 250D (cinema, 2383 print)", lut="vision250d", iso=250, halation=("cine", "strong")),
    "vision500t":    dict(name="Kodak Vision3 500T (cinema, 2383 print)", lut="vision500t", iso=500, halation=("cine", "strong")),
    "cinestill800t": dict(name="CineStill 800T = Vision3 500T without rem-jet", lut="vision500t", iso=800, halation=("cine", "no")),
    "provia100f":    dict(name="Fujifilm Provia 100F (slide)", lut="provia100f", iso=100, halation=("still", "strong"), slide=True),
    "velvia100":     dict(name="Fujifilm Velvia 100 (slide)", lut="velvia100", iso=100, halation=("still", "strong"), slide=True),
    "ektachrome100": dict(name="Kodak Ektachrome 100 (slide)", lut="ektachrome100", iso=100, halation=("still", "strong"), slide=True),
    "kodachrome64":  dict(name="Kodak Kodachrome 64 (slide)", lut="kodachrome64", iso=64, halation=("still", "weak"), slide=True),
}
DEFAULT_STOCK = "portra400"

# spektrafilm halation presets: first-bounce sigma (um on the film) and total
# strength per channel (R, G, B), set by film base and anti-halation layer.
HALATION = {
    ("still", "strong"): (65.0, (0.015, 0.005, 0.0)),
    ("still", "weak"):   (65.0, (0.08, 0.02, 0.0)),
    ("still", "no"):     (65.0, (0.30, 0.10, 0.015)),
    ("cine", "strong"):  (50.0, (0.015, 0.005, 0.0)),
    ("cine", "weak"):    (50.0, (0.08, 0.02, 0.0)),
    ("cine", "no"):      (50.0, (0.30, 0.10, 0.015)),
}
HALATION_BOUNCES, HALATION_DECAY = 3, 0.5
FILM_WIDTH_UM = 36000.0  # 35 mm frame: sets how big halation and grain are

# Linear sRGB / Rec.2020 -> linear S-Gamut3.Cine (the LUTs' input gamut)
M_SRGB_TO_SG3C = np.array([[0.6457234746, 0.2591258951, 0.0952441824],
                           [0.0874721750, 0.7597328340, 0.1527667879],
                           [0.0369304287, 0.1292865667, 0.8337346656]])
M_SRGB_TO_SG3C = (M_SRGB_TO_SG3C / M_SRGB_TO_SG3C.sum(1, keepdims=True)).astype(np.float32)
M_2020_TO_SG3C = np.array([[1.0381440619, -0.0954526920, 0.0573086300],
                           [0.0479489221, 0.7938618493, 0.1581892286],
                           [0.0301320543, 0.0408857929, 0.9289821528]], np.float32)
LUMA_SG3C = (np.array([0.2126, 0.7152, 0.0722]) @ np.linalg.inv(M_SRGB_TO_SG3C)).astype(np.float32)
LUMA = np.array([0.2126, 0.7152, 0.0722], np.float32)
RAW_EXTS = (".arw", ".srf", ".sr2", ".dng", ".nef", ".cr2", ".cr3", ".raf", ".orf", ".rw2")

# --------------------------------------------------------------------------
# LUTs
# --------------------------------------------------------------------------
_LUTS = {}


def lut_path():
    for p in (os.path.join(HERE, "bromide_luts.npz"), "bromide_luts.npz"):
        if os.path.exists(p):
            return p
    sys.exit("bromide_luts.npz not found - put it next to bromide.py")


def get_lut(name):
    """Flattened (N^3, 3) float32 table, indexed [b][g][r] like a .cube file."""
    if name not in _LUTS:
        with np.load(lut_path()) as z:
            a = z[name]
        n = a.shape[0]
        _LUTS[name] = (a.reshape(-1, 3).astype(np.float32) * np.float32(1 / 65535), n)
    return _LUTS[name]


def apply_lut(flat, n, x):
    """Tetrahedral 3D LUT lookup (4 table reads per pixel); x is (..., 3) in [0, 1]."""
    p = np.clip(x, 0, 1) * np.float32(n - 1)
    i0 = np.minimum(p.astype(np.int32), n - 2)
    f = p - i0
    fr, fg, fb = f[..., 0], f[..., 1], f[..., 2]
    nn = n * n
    base = (i0[..., 2] * nn + i0[..., 1] * n + i0[..., 0])
    fmax = np.maximum(np.maximum(fr, fg), fb)
    fmin = np.minimum(np.minimum(fr, fg), fb)
    fmid = fr + fg + fb - fmax - fmin
    # table offsets of the largest / smallest fraction's axis (ties broken so they differ)
    off_max = np.where((fr >= fg) & (fr >= fb), 1, np.where(fg >= fb, n, nn))
    off_min = np.where((fb <= fg) & (fb <= fr), nn, np.where(fg <= fr, n, 1))
    off_mid = (1 + n + nn) - off_max - off_min
    c0 = np.take(flat, base, axis=0)
    ca = np.take(flat, base + off_max, axis=0)
    cab = np.take(flat, base + off_max + off_mid, axis=0)
    c1 = np.take(flat, base + (1 + n + nn), axis=0)
    return (c0 * (1 - fmax)[..., None] + ca * (fmax - fmid)[..., None]
            + cab * (fmid - fmin)[..., None] + c1 * fmin[..., None])


def slog3_encode_exact(x):
    x = np.maximum(x, 0.0)
    hi = (420.0 + np.log10((x + 0.01) / 0.19) * 261.5) / 1023.0
    lo = (x * ((171.2102946929 - 95.0) / 0.01125) + 95.0) / 1023.0
    return np.where(x >= 0.01125, hi, lo).astype(np.float32)


_SLOG_MAX, _SLOG_N = 40.0, 32768  # S-Log3 reaches code 1.0 at ~38x linear
_SLOG_TABLE = slog3_encode_exact((np.linspace(0, 1, _SLOG_N) ** 2) * _SLOG_MAX)


def slog3_encode(x):
    """S-Log3 via a sqrt-spaced table (fast, error < 1e-5)."""
    idx = np.sqrt(np.clip(x, 0, _SLOG_MAX) * np.float32(1 / _SLOG_MAX)) * np.float32(_SLOG_N - 1)
    return np.take(_SLOG_TABLE, (idx + np.float32(0.5)).astype(np.int32))


# --------------------------------------------------------------------------
# Image helpers
# --------------------------------------------------------------------------
_SRGB_DECODE = None


def srgb_decode_u8(u8):
    global _SRGB_DECODE
    if _SRGB_DECODE is None:
        x = np.arange(256, dtype=np.float64) / 255
        _SRGB_DECODE = np.where(x <= 0.04045, x / 12.92,
                                ((x + 0.055) / 1.055) ** 2.4).astype(np.float32)
    return _SRGB_DECODE[u8]


def _box1d(a, r, axis):
    if r < 1:
        return a
    pad = [(0, 0)] * a.ndim
    pad[axis] = (r + 1, r)
    c = np.cumsum(np.pad(a, pad, mode="edge"), axis=axis, dtype=np.float32)
    n = a.shape[axis]
    hi = np.take(c, np.arange(2 * r + 1, 2 * r + 1 + n), axis=axis)
    lo = np.take(c, np.arange(0, n), axis=axis)
    return (hi - lo) / (2 * r + 1)


def blur(a, sigma):
    """Approximate gaussian blur: three box-blur passes per axis."""
    r = int(round((np.sqrt(4 * sigma * sigma + 1) - 1) / 2))
    for _ in range(3):
        a = _box1d(_box1d(a, r, 0), r, 1)
    return a


def _resize(a, size, resample):
    if a.ndim == 2:
        return np.asarray(Image.fromarray(np.ascontiguousarray(a, np.float32)).resize(size, resample),
                          dtype=np.float32)
    return np.stack([_resize(a[..., c], size, resample) for c in range(a.shape[2])], -1)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


# Grain: cached seamless noise tiles, sampled at random offsets per photo
_GRAIN_CACHE = {}
_TILE = 1536


def _grain_tile(size, octave):
    key = (round(size, 2), octave)
    if key not in _GRAIN_CACHE:
        rng = np.random.default_rng(1000 + octave)
        t = max(8, round(_TILE / size))
        pad = 4
        n = np.pad(rng.standard_normal((t, t), dtype=np.float32), pad, mode="wrap")
        big = _resize(n, (round((t + 2 * pad) * _TILE / t),) * 2, Image.BICUBIC)
        o = round(pad * _TILE / t)
        big = big[o:o + _TILE, o:o + _TILE]
        _GRAIN_CACHE[key] = (big - big.mean()) / (big.std() + 1e-6)
    return _GRAIN_CACHE[key]


def _grain_field(rows, w, size, octave, offsets):
    tile = _grain_tile(size, octave)
    oy, ox = offsets[octave]
    return tile[((rows + oy) % _TILE)[:, None], ((np.arange(w) + ox) % _TILE)[None, :]]


# --------------------------------------------------------------------------
# Loading: everything becomes scene-linear S-Gamut3.Cine float32
# --------------------------------------------------------------------------
def _light_source(lin, knee=0.9, clip=1.9, boost=8.0):
    """Lamps and glints are many stops brighter than white, but camera files clip
    them near 1-2. Halation is what shows that extra light on film, so clipped
    pixels count up to `boost` times brighter for it (not for the image itself)."""
    m = lin.max(axis=-1, keepdims=True)
    return np.float32(1.0) + np.float32(boost - 1.0) * smoothstep(knee, clip, m)


def _jpeg_highlight_boost(lin, ev=1.0):
    """Camera JPEGs squash highlights into 1.0. Stretch the last few % back
    out (up to +ev stops) so halation and the film shoulder see brighter light."""
    m = lin.max(axis=-1, keepdims=True)
    return lin * np.exp2(np.float32(ev) * smoothstep(0.85, 1.0, m))


def load_image(path, raw_gain=None):
    """Returns (linear S-Gamut3.Cine float32 HxWx3, exif bytes or None, source kind)."""
    if path.lower().endswith(RAW_EXTS):
        lin, exif = load_raw(path, raw_gain)
        return lin, exif, "raw"
    im = Image.open(path)
    exif = im.info.get("exif")
    im = ImageOps.exif_transpose(im).convert("RGB")
    u8 = np.asarray(im)
    lin = np.empty(u8.shape, np.float32)
    for y0 in range(0, u8.shape[0], 256):  # in strips to keep memory low
        s = srgb_decode_u8(u8[y0:y0 + 256])
        lin[y0:y0 + 256] = _jpeg_highlight_boost(s) @ M_SRGB_TO_SG3C.T
    return lin, exif, "jpeg"


def _decode_raw_linear_2020(path):
    """Camera RAW -> linear Rec.2020 float32 (white balance as shot)."""
    try:
        import rawpy
        with rawpy.imread(path) as raw:
            rgb = raw.postprocess(gamma=(1, 1), no_auto_bright=True, output_bps=16,
                                  use_camera_wb=True, output_color=rawpy.ColorSpace.Rec2020,
                                  user_flip=None)
            try:
                th = raw.extract_thumb()
                thumb = th.data if th.format == rawpy.ThumbFormat.JPEG else None
            except Exception:
                thumb = None
        return rgb.astype(np.float32) * np.float32(1 / 65535), thumb
    except ImportError:
        pass
    # Fallback for phones: LibRaw's dcraw_emu (Termux: pkg install libraw)
    out = path + ".ppm"
    try:
        subprocess.run(["dcraw_emu", "-4", "-w", "-o", "8", "-Z", out, path],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    except (FileNotFoundError, subprocess.CalledProcessError) as e:
        raise RuntimeError("can't decode RAW: install rawpy (pip) or LibRaw's dcraw_emu") from e
    with open(out, "rb") as f:
        data = f.read()
    os.remove(out)
    parts = data.split(maxsplit=4)
    w, h = int(parts[1]), int(parts[2])
    rgb = np.frombuffer(parts[4][: w * h * 6], ">u2").reshape(h, w, 3)
    return rgb.astype(np.float32) * np.float32(1 / 65535), None


def load_raw(path, raw_gain=None):
    rgb, thumb = _decode_raw_linear_2020(path)
    lin = rgb @ M_2020_TO_SG3C.T
    exif = None
    gain = raw_gain
    if thumb is not None:
        try:
            import io
            t = Image.open(io.BytesIO(thumb))
            exif = t.info.get("exif")
            t = ImageOps.exif_transpose(t)
            if gain is None:
                gain = _match_camera_exposure(lin, np.asarray(t.convert("RGB")))
        except Exception:
            pass
    if gain is None:
        gain = 2.0  # typical: camera meters mid-grey ~1 stop under 0.18 in RAW
    return lin * np.float32(gain), exif


def _match_camera_exposure(lin, thumb_u8):
    """Find the gain that puts the RAW's mid-tones where the camera's own JPEG
    put them, so Bromide respects your metering and exposure compensation."""
    h, w = thumb_u8.shape[:2]
    small = _resize(lin, (w, h), Image.BOX) if lin.shape[:2] != (h, w) else lin
    if small.shape[:2] != (h, w):
        return None
    y_raw = small @ LUMA_SG3C
    y_jpg = srgb_decode_u8(thumb_u8) @ LUMA
    sel = (y_jpg > 0.03) & (y_jpg < 0.5) & (y_raw > 1e-4)
    if sel.sum() < 500:
        return None
    return float(np.clip(np.median(y_jpg[sel]) / np.median(y_raw[sel]), 0.25, 16))


# --------------------------------------------------------------------------
# The film pipeline
# --------------------------------------------------------------------------
def lab_print_ev(lin, strength=0.5, limit=1.5):
    """Like a photo lab's auto printer: nudge the print exposure part of the way
    toward an average (center-weighted) mid-grey, within limits, so dark scenes
    print a little lighter and bright ones a little darker - without erasing
    your exposure choice."""
    if strength <= 0:
        return 0.0
    h, w = lin.shape[:2]
    s = min(1.0, 256 / max(h, w))
    small = _resize(lin, (max(1, round(w * s)), max(1, round(h * s))), Image.BOX)
    y = np.maximum(small @ LUMA_SG3C, 1e-4)
    sh, sw = y.shape
    yy, xx = np.mgrid[0:sh, 0:sw]
    r2 = ((yy - sh / 2) ** 2 + (xx - sw / 2) ** 2) / (max(sh, sw) / 2) ** 2
    wgt = np.exp(-r2 / (2 * 0.35 ** 2))
    geo = np.exp((np.log(y) * wgt).sum() / wgt.sum())
    return float(np.clip(strength * np.log2(0.18 / geo), -limit, limit))


def develop_linear(lin, stock, ev=0.0, grain=1.0, halation=1.0, lab=0.5, seed=None, strip=64,
                   info=None, grain_size=1.0, color=1.0, warmth=0.0, tint=0.0):
    """lin: HxWx3 scene-linear S-Gamut3.Cine. Returns HxWx3 uint8 sRGB."""
    p = STOCKS[stock]
    h, w = lin.shape[:2]
    long_edge = max(h, w)
    px_um = FILM_WIDTH_UM / long_edge  # size of one pixel on the virtual film
    rng = np.random.default_rng(seed)
    ev = ev + lab_print_ev(lin, lab)
    if info is not None:
        info["ev"] = ev
    gain = np.float32(2.0 ** ev)
    flat, n = get_lut(p["lut"])

    # Halation (spektrafilm model): light passing through the film reflects
    # off the base and re-exposes it, mostly in the red layer. Sum of N
    # gaussian bounces with sqrt(k)-growing widths, energy renormalized.
    # Wide blurs are computed on a ~900px copy and scaled up per strip.
    sigma_um, strength = HALATION[p["halation"]]
    a_tot = np.array(strength, np.float32) * np.float32(halation)
    glow = None
    if a_tot.max() > 0:
        s = min(1.0, 900 / long_edge)
        sw, sh = max(1, round(w * s)), max(1, round(h * s))
        small = _resize(lin * _light_source(lin), (sw, sh), Image.BOX) * gain
        weights = np.array([HALATION_DECAY ** k for k in range(HALATION_BOUNCES)])
        weights /= weights.sum()
        acc = np.zeros_like(small)
        for k, wk in enumerate(weights, start=1):
            acc += np.float32(wk) * blur(small, sigma_um * np.sqrt(k) / px_um * s)
        acc *= a_tot
        glow = [Image.fromarray(np.ascontiguousarray(acc[..., c])) for c in range(3)]
    norm = (1.0 / (1.0 + a_tot)).astype(np.float32)

    # Grain: size and strength from film speed, scaled to the image size
    iso = p["iso"]
    amt = grain * 0.020 * (iso / 400) ** 0.5 * (0.75 if p.get("slide") else 1.0)
    gsize = (6.0 + 6.0 * (iso / 400) ** 0.5) / px_um * max(0.25, grain_size)  # grain clump size in px
    if gsize < 1.0:  # small image = downscaled film: grain averages out
        amt *= gsize
        gsize = 1.0
    offsets = {o: rng.integers(0, _TILE, 2) for o in (1, 2, 3, 4)}

    # White balance shift before the film: up to about a stop between red and blue
    wb = gain * np.exp2(np.array([0.5 * warmth + 0.15 * tint, -0.3 * tint, -0.5 * warmth + 0.15 * tint],
                                 np.float32))

    out = np.empty((h, w, 3), np.uint8)
    for y0 in range(0, h, strip):
        y1 = min(h, y0 + strip)
        x = lin[y0:y1] * wb
        if glow is not None:
            sy = glow[0].height / h
            box = (0, y0 * sy, glow[0].width, y1 * sy)
            for c in range(3):
                x[..., c] += np.asarray(glow[c].resize((w, y1 - y0), Image.BILINEAR, box=box),
                                        np.float32)
            x *= norm

        # Negative + print (or slide) color and tone: the baked spektrafilm LUT
        d = apply_lut(flat, n, slog3_encode(x))
        if color != 1.0:  # print saturation
            yl = (d @ LUMA)[..., None]
            d = yl + (d - yl) * np.float32(max(0.0, color))

        # Grain: two octaves of luminance grain plus dye-cloud color speckle,
        # strongest in the mid-tones
        if amt > 0:
            rows = np.arange(y0, y1)
            yc = np.clip(d @ LUMA, 0, 1)
            wgt = np.float32(amt) * (0.25 + 2.7 * yc * (1 - yc)) * (0.35 + 0.65 * smoothstep(0.0, 0.2, yc))
            g = wgt * (0.8 * _grain_field(rows, w, gsize, 1, offsets)
                       + 0.4 * _grain_field(rows, w, gsize * 2.3, 2, offsets))
            c1 = wgt * 0.3 * _grain_field(rows, w, gsize * 1.6, 3, offsets)
            c2 = wgt * 0.3 * _grain_field(rows, w, gsize * 1.6, 4, offsets)
            d[..., 0] += g + c1
            d[..., 1] += g - 0.3 * (c1 + c2)
            d[..., 2] += g + c2

        out[y0:y1] = np.clip(d * np.float32(255) + np.float32(0.5), 0, 255).astype(np.uint8)
    return out


RECIPE_TAG = "Bromide recipe "


def develop_file(src, dst, stock, ev=0.0, grain=1.0, halation=1.0, lab=0.5, max_long=None,
                 raw_gain=None, quality=93, info=None, name=None, grain_size=1.0, color=1.0,
                 warmth=0.0, tint=0.0):
    lin, exif, kind = load_image(src, raw_gain)
    if max_long and max(lin.shape[:2]) > max_long:
        s = max_long / max(lin.shape[:2])
        lin = _resize(lin, (round(lin.shape[1] * s), round(lin.shape[0] * s)), Image.BOX)
    base = (name or os.path.splitext(os.path.basename(src))[0]).upper()
    info = {} if info is None else info
    # grain pattern seeded from the file name, so a redo looks the same
    out = Image.fromarray(develop_linear(lin, stock, ev, grain, halation, lab,
                                         seed=zlib.crc32(base.encode()), info=info,
                                         grain_size=grain_size, color=color, warmth=warmth, tint=tint))
    kw = dict(quality=quality, subsampling=0)
    ex = Image.Exif()
    if exif:
        try:
            ex.load(exif)
        except Exception:
            ex = Image.Exif()
    ex[0x0112] = 1  # orientation is already applied to the pixels
    # The recipe travels inside the photo, so "redo" can rebuild it at full
    # resolution from the original on the camera card later.
    ex[0x010E] = RECIPE_TAG + json.dumps(dict(
        source=base, stock=stock, print_ev=round(info["ev"], 3), grain=grain,
        halation=halation, grain_size=grain_size, color=color, warmth=warmth, tint=tint,
        raw=(kind == "raw")))
    kw["exif"] = ex.tobytes()
    os.makedirs(os.path.dirname(os.path.abspath(dst)), exist_ok=True)
    out.save(dst, "JPEG", **kw)
    return dst, kind


# --------------------------------------------------------------------------
# Android / Termux niceties (all optional, fail silently)
# --------------------------------------------------------------------------
def _try(cmd):
    try:
        subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
    except Exception:
        pass


def local_ips():
    ips = set()
    try:
        import fcntl
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        for _, name in socket.if_nameindex():
            try:
                raw = fcntl.ioctl(s.fileno(), 0x8915, struct.pack("256s", name[:15].encode()))
                ips.add((name, socket.inet_ntoa(raw[20:24])))
            except OSError:
                pass
    except Exception:
        pass
    if not ips:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("10.255.255.255", 1))
            ips.add(("default", s.getsockname()[0]))
        except Exception:
            pass
    return sorted(i for i in ips if not i[1].startswith("127."))


def default_dirs():
    sd = "/sdcard" if os.path.isdir("/sdcard/DCIM") else os.path.expanduser("~/bromide")
    return os.path.join(sd, "DCIM", "Bromide-in"), os.path.join(sd, "DCIM", "Bromide")


# --------------------------------------------------------------------------
# FTP server that develops everything it receives
# --------------------------------------------------------------------------
def serve(args):
    try:
        from pyftpdlib.authorizers import DummyAuthorizer
        from pyftpdlib.handlers import FTPHandler
        from pyftpdlib.servers import FTPServer
        from pyftpdlib.log import config_logging
    except ImportError:
        sys.exit("pyftpdlib is missing. Run:  pip install pyftpdlib")
    import logging
    config_logging(level=logging.WARNING)

    inbox, outdir = args.inbox, args.out
    os.makedirs(inbox, exist_ok=True)
    os.makedirs(outdir, exist_ok=True)
    lut_path()  # fail early if the LUTs are missing

    auth = DummyAuthorizer()
    for name in list(STOCKS) + ["film"]:
        auth.add_user(name, args.password, inbox, perm="elradfmwMT")

    jobs = queue.Queue()
    written = {}  # (base, stock) -> output path, for this session
    from_raw = set()

    def worker():
        while True:
            path, stock, base, is_raw = jobs.get()
            t0 = time.time()
            key = (base, stock)
            try:
                if not is_raw and key in from_raw:
                    print(f"  {base}: RAW already developed, skipping JPEG", flush=True)
                else:
                    dst = written.get(key) or os.path.join(outdir, f"{base}_{stock}.jpg")
                    k = 2
                    while key not in written and os.path.exists(dst):  # file number reused
                        dst = os.path.join(outdir, f"{base}_{stock}_{k}.jpg")
                        k += 1
                    info = {}
                    develop_file(path, dst, stock, ev=args.ev, grain=args.grain,
                                 halation=args.halation, lab=args.lab, max_long=args.max_size,
                                 info=info, name=base, **_look_kw(args))
                    written[key] = dst
                    if is_raw:
                        from_raw.add(key)
                    print(f"  developed {base} ({'RAW' if is_raw else 'JPEG'}) as {stock}, "
                          f"print {info['ev']:+.1f} EV, {time.time() - t0:.1f}s", flush=True)
                    _try(["termux-media-scan", dst])
                    if args.notify:
                        _try(["termux-notification", "--id", "bromide", "--title", "Bromide",
                              "--content", f"{base} - {STOCKS[stock]['name']}"])
            except Exception as e:
                print(f"  failed on {base}: {e}", flush=True)
            finally:
                if not args.keep_originals and os.path.exists(path):
                    os.remove(path)
            jobs.task_done()

    threading.Thread(target=worker, daemon=True).start()

    class Handler(FTPHandler):
        def on_file_received(self, file):
            stock = self.username if self.username in STOCKS else args.stock
            low = file.lower()
            is_raw = low.endswith(RAW_EXTS)
            if not (is_raw or low.endswith((".jpg", ".jpeg"))):
                print(f"received {os.path.basename(file)} (not a photo, kept as is)", flush=True)
                return
            if is_raw and args.no_raw:
                print(f"received {os.path.basename(file)} (RAW processing off)", flush=True)
                return
            print(f"received {os.path.basename(file)} -> {stock}", flush=True)
            # move aside at once so a re-sent file with the same name can't clash
            held = f"{file}.{time.time_ns()}{os.path.splitext(file)[1]}"
            os.replace(file, held)
            jobs.put((held, stock, os.path.splitext(os.path.basename(file))[0], is_raw))

    Handler.authorizer = auth
    Handler.passive_ports = range(60000, 60200)
    Handler.banner = "Bromide ready."

    _try(["termux-wake-lock"])  # keep running with the screen off
    server = FTPServer(("0.0.0.0", args.port), Handler)
    server.max_cons = 16

    print("\nBromide is running. Camera FTP settings:")
    ips = local_ips()
    for name, ip in ips:
        print(f"   host {ip:<16} ({name})")
    if not ips:
        print("   host  -> your hotspot's IP (hotspot settings)")
    print(f"   port {args.port}, passive mode ON, password '{args.password}'")
    print(f"   username = film stock (or 'film' = {args.stock}):")
    for k, v in STOCKS.items():
        print(f"      {k:<14} {v['name']}")
    print(f"Developed photos go to {outdir}\nCtrl+C to stop.\n", flush=True)
    try:
        server.serve_forever(handle_exit=False)
    except KeyboardInterrupt:
        print("\nStopping...")
        server.close_all()
        _try(["termux-wake-unlock"])


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------
def _images_in(path):
    if os.path.isdir(path):
        return sorted(os.path.join(path, f) for f in os.listdir(path)
                      if f.lower().endswith((".jpg", ".jpeg", ".png", ".tif", ".tiff") + RAW_EXTS))
    return [path]


def _look_kw(args):
    return dict(grain_size=args.grain_size, color=args.color, warmth=args.warmth, tint=args.tint)


def cmd_develop(args):
    for src in _images_in(args.input):
        base = os.path.splitext(os.path.basename(src))[0]
        outdir = args.out or os.path.dirname(os.path.abspath(src))
        t0 = time.time()
        info = {}
        dst, kind = develop_file(src, os.path.join(outdir, f"{base}_{args.stock}.jpg"), args.stock,
                                 ev=args.ev, grain=args.grain, halation=args.halation,
                                 lab=args.lab, max_long=args.max_size, info=info, **_look_kw(args))
        print(f"{dst}  ({kind}, print {info['ev']:+.1f} EV, {time.time() - t0:.1f}s)")


def read_recipe(path):
    try:
        desc = Image.open(path).getexif().get(0x010E)
        if isinstance(desc, bytes):
            desc = desc.decode("utf-8", "ignore")
        if desc and desc.startswith(RECIPE_TAG):
            return json.loads(desc[len(RECIPE_TAG):])
    except Exception:
        pass
    return None


def cmd_redo(args):
    """Rebuild full-resolution versions of the phone's Bromide photos from the
    camera card's originals, using the recipe stored in each phone photo."""
    originals = {}
    for root, _, files in os.walk(args.card):
        for f in files:
            stem, ext = os.path.splitext(f)
            ext = ext.lower()
            if ext in RAW_EXTS or ext in (".jpg", ".jpeg"):
                originals.setdefault(stem.upper(), []).append(os.path.join(root, f))
    os.makedirs(args.out, exist_ok=True)
    done = missing = 0
    for f in sorted(os.listdir(args.phone)):
        if not f.lower().endswith((".jpg", ".jpeg")):
            continue
        r = read_recipe(os.path.join(args.phone, f))
        if not r:
            continue
        cands = originals.get(r["source"], [])
        # same kind of file the phone used (RAW or JPEG), RAW preferred if asked
        pick = [c for c in cands if c.lower().endswith(RAW_EXTS) == (r["raw"] or args.prefer_raw)]
        pick = pick or cands
        if not pick:
            print(f"{f}: original {r['source']} not found on the card")
            missing += 1
            continue
        dst = os.path.join(args.out, f)
        t0 = time.time()
        develop_file(pick[0], dst, r["stock"], ev=r["print_ev"], grain=r["grain"],
                     halation=r["halation"], lab=0.0, grain_size=r.get("grain_size", 1.0),
                     color=r.get("color", 1.0), warmth=r.get("warmth", 0.0), tint=r.get("tint", 0.0))
        print(f"{dst}  (from {os.path.basename(pick[0])}, {time.time() - t0:.1f}s)")
        done += 1
    print(f"done: {done} rebuilt, {missing} originals missing")


def cmd_sheet(args):
    lin, _, _ = load_image(args.input)
    s = args.width / max(lin.shape[:2])
    lin = _resize(lin, (round(lin.shape[1] * s), round(lin.shape[0] * s)), Image.BOX)
    names = args.stocks.split(",") if args.stocks else list(STOCKS)
    orig = Image.open(args.input)
    orig = ImageOps.exif_transpose(orig).convert("RGB").resize((lin.shape[1], lin.shape[0]), Image.LANCZOS)
    tiles = [("camera JPEG" if not args.input.lower().endswith(RAW_EXTS) else "camera", orig)]
    tiles += [(STOCKS[k]["name"].split(" (")[0].split(" =")[0],
               Image.fromarray(develop_linear(lin, k, args.ev, lab=args.lab, seed=1))) for k in names]
    cols = args.cols
    rows = -(-len(tiles) // cols)
    pad, label = 8, 26
    tw, th = tiles[1][1].size
    sheet = Image.new("RGB", (cols * (tw + pad) + pad, rows * (th + label + pad) + pad), (18, 18, 18))
    dr = ImageDraw.Draw(sheet)
    for i, (name, t) in enumerate(tiles):
        x = pad + (i % cols) * (tw + pad)
        y = pad + (i // cols) * (th + label + pad)
        sheet.paste(t, (x, y))
        dr.text((x + 4, y + th + 6), name, fill=(235, 235, 235))
    dst = args.out or os.path.splitext(args.input)[0] + "_sheet.jpg"
    sheet.save(dst, quality=90)
    print(dst)


def main():
    inbox, outdir = default_dirs()
    ap = argparse.ArgumentParser(description="Bromide - film emulation powered by spektrafilm")
    sub = ap.add_subparsers(dest="cmd", required=True)

    def look_args(p):
        p.add_argument("--ev", type=float, default=0.0, help="exposure compensation in stops")
        p.add_argument("--grain", type=float, default=1.0, help="grain strength (0 = off)")
        p.add_argument("--grain-size", type=float, default=1.0,
                       help="grain size: 1 = the stock's own, 0.5 = finer, 2 = coarser")
        p.add_argument("--halation", type=float, default=1.0, help="halation strength (0 = off)")
        p.add_argument("--color", type=float, default=1.0, help="print saturation: 0 = none, 1 = the stock's own")
        p.add_argument("--warmth", type=float, default=0.0, help="white balance shift -1..1: + warmer, - cooler")
        p.add_argument("--tint", type=float, default=0.0, help="white balance shift -1..1: + magenta, - green")
        p.add_argument("--lab", type=float, default=0.5,
                       help="lab auto-print strength: 0 = print exactly as exposed, 1 = full auto")
        p.add_argument("--max-size", type=int, default=None,
                       help="long edge in px for the developed photo; 0 = full size "
                            "(serve defaults to 2048 to save phone storage)")

    sp = sub.add_parser("serve", help="FTP server that auto-develops incoming photos")
    sp.add_argument("--port", type=int, default=2121)
    sp.add_argument("--password", default="film")
    sp.add_argument("--stock", default=DEFAULT_STOCK, choices=STOCKS,
                    help="stock used when the camera logs in as 'film'")
    sp.add_argument("--inbox", default=inbox)
    sp.add_argument("--out", default=outdir)
    sp.add_argument("--no-raw", action="store_true", help="ignore RAW files, use JPEGs only")
    sp.add_argument("--keep-originals", action="store_true",
                    help="keep the camera's files on the phone too")
    sp.add_argument("--notify", action="store_true",
                    help="phone notification per photo (needs Termux:API)")
    look_args(sp)
    sp.set_defaults(func=serve, max_size=2048)

    dp = sub.add_parser("develop", help="develop a file or folder")
    dp.add_argument("input")
    dp.add_argument("-s", "--stock", default=DEFAULT_STOCK, choices=STOCKS)
    dp.add_argument("-o", "--out", default=None)
    look_args(dp)
    dp.set_defaults(func=cmd_develop)

    sh = sub.add_parser("sheet", help="contact sheet comparing stocks")
    sh.add_argument("input")
    sh.add_argument("-o", "--out", default=None)
    sh.add_argument("--width", type=int, default=600)
    sh.add_argument("--cols", type=int, default=3)
    sh.add_argument("--stocks", default=None, help="comma-separated, default all")
    sh.add_argument("--ev", type=float, default=0.0)
    sh.add_argument("--lab", type=float, default=0.5)
    sh.set_defaults(func=cmd_sheet)

    rp = sub.add_parser("redo", help="rebuild full-res versions from the camera card's originals")
    rp.add_argument("phone", help="folder with Bromide photos (e.g. copied from the phone)")
    rp.add_argument("card", help="camera card folder with the originals (searched recursively)")
    rp.add_argument("-o", "--out", required=True)
    rp.add_argument("--prefer-raw", action="store_true", help="use RAW originals when available")
    rp.set_defaults(func=cmd_redo)

    ls = sub.add_parser("stocks", help="list film stocks")
    ls.set_defaults(func=lambda a: [print(f"{k:<14} {v['name']}") for k, v in STOCKS.items()])

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
