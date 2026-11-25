package com.bromide.engine;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/** A film's color response, input S-Log3 / S-Gamut3.Cine, output sRGB.
 *
 *  Color films: a 3D LUT baked from spektrafilm. File format "FBL1": int32 LE
 *  size n, then n^3 RGB uint16 LE triplets, red varying fastest (like .cube).
 *
 *  Black & white films ("FBM1"): int32 LE n, three float32 LE weights that mix
 *  linear S-Gamut3.Cine into the film's panchromatic exposure, then n uint16 LE
 *  display values indexed by the S-Log3 code of that exposure. */
public final class Lut {
    public final int n;
    /** Color: flattened [b][g][r][rgb] table, values 0..1. Null for B&W. */
    public final float[] table;
    /** B&W: true, with {@link #weights} and {@link #curve} set. */
    public final boolean mono;
    public final float[] weights;
    public final float[] curve;

    public Lut(int n, float[] table) {
        this.n = n;
        this.table = table;
        this.mono = false;
        this.weights = null;
        this.curve = null;
    }

    public Lut(int n, float[] weights, float[] curve) {
        this.n = n;
        this.table = null;
        this.mono = true;
        this.weights = weights;
        this.curve = curve;
    }

    /** B&W lookup: S-Log3 code of the mixed exposure -> display value. */
    public float curveAt(float code) {
        float p = clamp01(code) * (n - 1);
        int i = Math.min((int) p, n - 2);
        float f = p - i;
        return curve[i] * (1 - f) + curve[i + 1] * f;
    }

    public static Lut read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        byte[] magic = new byte[4];
        d.readFully(magic);
        if (magic[0] == 'F' && magic[1] == 'B' && magic[2] == 'M' && magic[3] == '1') {
            int n = Integer.reverseBytes(d.readInt());
            if (n < 2 || n > 1 << 20) throw new IOException("bad curve size " + n);
            float[] w = new float[3];
            for (int i = 0; i < 3; i++) w[i] = Float.intBitsToFloat(Integer.reverseBytes(d.readInt()));
            byte[] raw = new byte[n * 2];
            d.readFully(raw);
            float[] c = new float[n];
            for (int i = 0, j = 0; i < n; i++, j += 2) c[i] = ((raw[j] & 0xff) | ((raw[j + 1] & 0xff) << 8)) / 65535f;
            return new Lut(n, w, c);
        }
        if (magic[0] != 'F' || magic[1] != 'B' || magic[2] != 'L' || magic[3] != '1') {
            throw new IOException("not a Bromide LUT");
        }
        int n = Integer.reverseBytes(d.readInt());
        if (n < 2 || n > 129) throw new IOException("bad LUT size " + n);
        int count = n * n * n * 3;
        byte[] raw = new byte[count * 2];
        d.readFully(raw);
        float[] t = new float[count];
        float k = 1f / 65535f;
        for (int i = 0, j = 0; i < count; i++, j += 2) {
            t[i] = ((raw[j] & 0xff) | ((raw[j + 1] & 0xff) << 8)) * k;
        }
        return new Lut(n, t);
    }

    /** Tetrahedral interpolation. in = encoded rgb in [0,1]; writes rgb to out[o..o+2]. */
    public void apply(float r, float g, float b, float[] out, int o) {
        int n1 = n - 1;
        float pr = clamp01(r) * n1, pg = clamp01(g) * n1, pb = clamp01(b) * n1;
        int ir = Math.min((int) pr, n - 2), ig = Math.min((int) pg, n - 2), ib = Math.min((int) pb, n - 2);
        float fr = pr - ir, fg = pg - ig, fb = pb - ib;
        int nn = n * n;
        int base = (ib * nn + ig * n + ir);
        int dr = 1, dg = n, db = nn;
        int a, ab;
        float w0, w1, w2, w3;
        if (fr >= fg) {
            if (fg >= fb) {        // r > g > b
                a = dr; ab = dr + dg; w0 = 1 - fr; w1 = fr - fg; w2 = fg - fb; w3 = fb;
            } else if (fr >= fb) { // r > b > g
                a = dr; ab = dr + db; w0 = 1 - fr; w1 = fr - fb; w2 = fb - fg; w3 = fg;
            } else {               // b > r > g
                a = db; ab = db + dr; w0 = 1 - fb; w1 = fb - fr; w2 = fr - fg; w3 = fg;
            }
        } else {
            if (fb >= fg) {        // b > g > r
                a = db; ab = db + dg; w0 = 1 - fb; w1 = fb - fg; w2 = fg - fr; w3 = fr;
            } else if (fb >= fr) { // g > b > r
                a = dg; ab = dg + db; w0 = 1 - fg; w1 = fg - fb; w2 = fb - fr; w3 = fr;
            } else {               // g > r > b
                a = dg; ab = dg + dr; w0 = 1 - fg; w1 = fg - fr; w2 = fr - fb; w3 = fb;
            }
        }
        int i0 = base * 3, ia = (base + a) * 3, iab = (base + ab) * 3, i1 = (base + dr + dg + db) * 3;
        float[] t = table;
        out[o] = w0 * t[i0] + w1 * t[ia] + w2 * t[iab] + w3 * t[i1];
        out[o + 1] = w0 * t[i0 + 1] + w1 * t[ia + 1] + w2 * t[iab + 1] + w3 * t[i1 + 1];
        out[o + 2] = w0 * t[i0 + 2] + w1 * t[ia + 2] + w2 * t[iab + 2] + w3 * t[i1 + 2];
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
