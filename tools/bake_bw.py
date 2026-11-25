#!/usr/bin/env python3
"""
Bake Bromide's black & white film curves.

spektrafilm has no black & white profiles yet, so these are Bromide's own,
simpler model (not datasheet-fitted spectral simulations):

  scene linear (S-Gamut3.Cine)
    -> panchromatic response: weighted sum of the R, G, B channels
       (each film's spectral sensitivity, approximated)
    -> negative: characteristic curve (base+fog, toe, straight line of slope
       "gamma", shoulder) in log exposure
    -> print: enlarger light through the negative onto grade-2 paper,
       print exposure set so 18% grey prints at 18% reflectance
    -> sRGB

Output: one .lut per stock in app/src/main/assets/luts, format "FBM1":
  magic, int32 n, float32 weights[3] (applied to linear S-Gamut3.Cine),
  then n uint16 values = display value for S-Log3 code i/(n-1) of the mix.
"""
import os
import struct

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "luts")

# linear sRGB -> linear S-Gamut3.Cine (rows normalized), and inverse
M = np.array([[0.6457234746, 0.2591258951, 0.0952441824],
              [0.0874721750, 0.7597328340, 0.1527667879],
              [0.0369304287, 0.1292865667, 0.8337346656]])
M = M / M.sum(1, keepdims=True)
M_INV = np.linalg.inv(M)

# Each stock: spectral weights (in linear sRGB primaries, sum 1), negative
# contrast (gamma at box speed / normal development), toe softness and paper grade.
#   Panchromatic films see more blue and less red than the eye, so skies render
#   lighter and reds darker than a plain luminance conversion.
STOCKS = {
    "bw_trix400":    dict(w=(0.21, 0.57, 0.22), gamma=0.66, toe=0.30, grade=2.3),
    "bw_hp5":        dict(w=(0.22, 0.58, 0.20), gamma=0.60, toe=0.34, grade=2.0),
    "bw_tmax100":    dict(w=(0.24, 0.60, 0.16), gamma=0.64, toe=0.22, grade=2.0),
    "bw_delta3200":  dict(w=(0.25, 0.56, 0.19), gamma=0.56, toe=0.38, grade=2.4),
    "bw_panf50":     dict(w=(0.18, 0.59, 0.23), gamma=0.72, toe=0.24, grade=2.2),
    "bw_acros100":   dict(w=(0.21, 0.61, 0.18), gamma=0.66, toe=0.20, grade=2.1),
    "bw_sfx200":     dict(w=(0.46, 0.44, 0.10), gamma=0.62, toe=0.30, grade=2.2),
}


def slog3_decode(v):
    v = np.asarray(v, dtype=np.float64)
    hi = (10 ** ((v * 1023.0 - 420.0) / 261.5)) * 0.19 - 0.01
    lo = (v * 1023.0 - 95.0) * 0.01125 / (171.2102946929 - 95.0)
    return np.where(v >= 171.2102946929 / 1023.0, hi, lo)


def softplus(x, k):
    return k * np.logaddexp(0, x / k)


def negative_density(logE, gamma, toe):
    """Base+fog 0.25, toe about 5 stops under grey, shoulder far up (+4 log = 13 stops)."""
    base, x_toe, x_sh = 0.25, -1.55, 4.0
    return base + gamma * (softplus(logE - x_toe, toe) - softplus(logE - x_sh, 0.4))


def paper_density(logP, grade):
    """Grade-2-ish glossy paper: Dmin 0.06, Dmax 2.05, steeper for higher grades."""
    dmin, dmax = 0.06, 2.05
    slope = 1.1 + 0.35 * grade  # gradient at the middle of the curve
    t = 1.0 / (1.0 + np.exp(-4.0 * slope / (dmax - dmin) * logP))
    return dmin + (dmax - dmin) * t


def render(y_lin, p):
    logE = np.log10(np.maximum(y_lin, 1e-7) / 0.18)
    d_neg = negative_density(logE, p["gamma"], p["toe"])
    # choose the print exposure so that 18% grey prints at 18% reflectance
    target = -np.log10(0.18)
    d_grey = negative_density(np.array(0.0), p["gamma"], p["toe"])
    lo, hi = -5.0, 5.0
    for _ in range(80):
        mid = (lo + hi) / 2
        if paper_density(mid - d_grey, p["grade"]) < target:
            lo = mid
        else:
            hi = mid
    offset = (lo + hi) / 2
    refl = 10 ** (-paper_density(offset - d_neg, p["grade"]))
    white = 10 ** (-0.06)
    disp = np.clip(refl / white, 0, 1)
    return np.where(disp <= 0.0031308, disp * 12.92, 1.055 * disp ** (1 / 2.4) - 0.055)


def main(n=4096):
    os.makedirs(OUT, exist_ok=True)
    codes = np.linspace(0, 1, n)
    y = slog3_decode(codes)
    for name, p in STOCKS.items():
        w_srgb = np.array(p["w"]) / sum(p["w"])
        w_sg = w_srgb @ M_INV  # same response, expressed on S-Gamut3.Cine channels
        curve = render(y, p)
        with open(os.path.join(OUT, name + ".lut"), "wb") as f:
            f.write(b"FBM1")
            f.write(struct.pack("<i", n))
            f.write(struct.pack("<3f", *w_sg))
            f.write(np.round(np.clip(curve, 0, 1) * 65535).astype("<u2").tobytes())
        grey = np.interp(0.41055718, codes, curve)
        print(f"{name:14} weights(SG3C)={np.round(w_sg, 3)}  18% grey -> {grey:.3f}")


if __name__ == "__main__":
    main()
