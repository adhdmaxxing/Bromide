package com.bromide.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * The film pipeline. Pure Java (no Android APIs) so it can be tested on a desktop.
 *
 * Color and tone come from spektrafilm (github.com/andreavolpato/spektrafilm): a
 * spectral simulation of each stock built from manufacturer datasheets, baked
 * into a 3D LUT per stock. Halation follows spektrafilm's physical model; grain
 * is a fast approximation.
 *
 * Steps: camera sRGB JPEG -> scene-linear S-Gamut3.Cine (with a small highlight
 * stretch, since JPEGs squash highlights) -> lab-style print exposure -> halation
 * -> S-Log3 -> stock LUT (negative + paper, or slide) -> grain -> sRGB.
 */
public final class FilmEngine {

    public static final class Params {
        /** Exposure compensation in stops. */
        public float ev = 0f;
        /** Grain strength, 0 = off. */
        public float grain = 1f;
        /** Grain clump size: 1 = the stock's own, 0.5 = finer, 2 = coarser. */
        public float grainSize = 1f;
        /** Halation strength, 0 = off. */
        public float halation = 1f;
        /** Color saturation of the print: 0 = none, 1 = the stock's own, 2 = double. */
        public float color = 1f;
        /** White balance shift before the film, -1..1: + warmer (amber), - cooler (blue). */
        public float warmth = 0f;
        /** White balance shift before the film, -1..1: + magenta, - green. */
        public float tint = 0f;
        /** Lab auto-print strength: 0 = print as exposed, 1 = full auto. */
        public float lab = 0.5f;
        /** Seeds the grain pattern (use a hash of the file name to make re-runs identical). */
        public long seed = 0;
        /** Threads to use (0 = all cores). */
        public int threads = 0;
        /** How much of the 35 mm frame the image shows: 1 = all of it; 4 = a quarter of its
         *  width, as if looking at a crop, so grain and halation come out 4x larger. Previews
         *  use it so a small picture still shows the film's texture. */
        public float filmScale = 1f;
    }

    public static final class Result {
        /** Total print exposure applied, in stops (your ev + lab correction). */
        public final float printEv;
        public final long millis;

        Result(float printEv, long millis) {
            this.printEv = printEv;
            this.millis = millis;
        }
    }

    // ---- color constants ------------------------------------------------------------

    /** Linear sRGB -> linear S-Gamut3.Cine, rows normalized so white maps to white. */
    static final float[] M;
    /** Luminance weights of S-Gamut3.Cine (sRGB luma pulled through M^-1). */
    static final float[] LUMA_SG;
    static final float[] LUMA = {0.2126f, 0.7152f, 0.0722f};
    static final float[] SRGB_DECODE = new float[256];

    static final float SLOG_MAX = 40f;
    static final int SLOG_N = 32768;
    static final float[] SLOG_TABLE = new float[SLOG_N];

    static {
        double[][] m = {
            {0.6457234746, 0.2591258951, 0.0952441824},
            {0.0874721750, 0.7597328340, 0.1527667879},
            {0.0369304287, 0.1292865667, 0.8337346656}};
        M = new float[9];
        for (int i = 0; i < 3; i++) {
            double s = m[i][0] + m[i][1] + m[i][2];
            for (int j = 0; j < 3; j++) {
                m[i][j] /= s;
                M[i * 3 + j] = (float) m[i][j];
            }
        }
        double[][] inv = invert3(m);
        LUMA_SG = new float[3];
        double[] l = {0.2126, 0.7152, 0.0722};
        for (int j = 0; j < 3; j++) {
            LUMA_SG[j] = (float) (l[0] * inv[0][j] + l[1] * inv[1][j] + l[2] * inv[2][j]);
        }
        for (int i = 0; i < 256; i++) {
            double x = i / 255.0;
            SRGB_DECODE[i] = (float) (x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4));
        }
        for (int i = 0; i < SLOG_N; i++) {
            double t = i / (double) (SLOG_N - 1);
            SLOG_TABLE[i] = (float) slog3(t * t * SLOG_MAX);
        }
    }

    static double slog3(double x) {
        if (x < 0) x = 0;
        if (x >= 0.01125) return (420.0 + Math.log10((x + 0.01) / 0.19) * 261.5) / 1023.0;
        return (x * ((171.2102946929 - 95.0) / 0.01125) + 95.0) / 1023.0;
    }

    static float slog3Fast(float x) {
        if (x <= 0) return SLOG_TABLE[0];
        if (x > SLOG_MAX) x = SLOG_MAX;
        int idx = (int) (Math.sqrt(x / SLOG_MAX) * (SLOG_N - 1) + 0.5);
        return SLOG_TABLE[idx];
    }

    // spektrafilm halation presets: first-bounce sigma (um on film), strength R,G,B
    static float halationSigmaUm(Stock s) {
        return "cine".equals(s.base) ? 50f : 65f;
    }

    static float[] halationStrength(Stock s) {
        switch (s.antiHalation) {
            case "no": return new float[]{0.30f, 0.10f, 0.015f};
            case "weak": return new float[]{0.08f, 0.02f, 0f};
            default: return new float[]{0.015f, 0.005f, 0f};
        }
    }

    static final int HALATION_BOUNCES = 3;
    static final float HALATION_DECAY = 0.5f;
    /** A 35 mm frame is 36 mm wide: sets how large halation and grain are. */
    static final float FILM_WIDTH_UM = 36000f;
    static final int GLOW_TARGET = 900;

    // ---- input sources --------------------------------------------------------------

    /** Where pixels come from: a camera JPEG (sRGB) or a RAW (16-bit linear Rec.2020). */
    public abstract static class Source {
        public final int w, h;

        Source(int w, int h) {
            this.w = w;
            this.h = h;
        }

        /** Scene-linear S-Gamut3.Cine for pixel i. */
        abstract void linear(int i, float[] out);

        public static Source jpeg(int[] argb, int w, int h) {
            return new JpegSource(argb, w, h);
        }

        /** @param gain exposure scale so mid-grey lands where the camera put it */
        public static Source raw(short[] rgb, int w, int h, float gain) {
            return new RawSource(rgb, w, h, gain);
        }
    }

    static final class JpegSource extends Source {
        final int[] argb;

        JpegSource(int[] argb, int w, int h) {
            super(w, h);
            this.argb = argb;
        }

        @Override
        void linear(int i, float[] out) {
            toLinear(argb[i], out, 0);
        }
    }

    static final class RawSource extends Source {
        final short[] rgb;
        final float[] m = new float[9];

        RawSource(short[] rgb, int w, int h, float gain) {
            super(w, h);
            this.rgb = rgb;
            float k = gain / 65535f;
            for (int i = 0; i < 9; i++) m[i] = M_2020[i] * k;
        }

        @Override
        void linear(int i, float[] out) {
            int j = i * 3;
            float r = rgb[j] & 0xffff, g = rgb[j + 1] & 0xffff, b = rgb[j + 2] & 0xffff;
            out[0] = m[0] * r + m[1] * g + m[2] * b;
            out[1] = m[3] * r + m[4] * g + m[5] * b;
            out[2] = m[6] * r + m[7] * g + m[8] * b;
        }
    }

    /** Linear Rec.2020 -> linear S-Gamut3.Cine. */
    static final float[] M_2020 = {
        1.0381440619f, -0.0954526920f, 0.0573086300f,
        0.0479489221f, 0.7938618493f, 0.1581892286f,
        0.0301320543f, 0.0408857929f, 0.9289821528f};

    /**
     * Gain that puts a RAW's mid-tones where the camera's own JPEG put them, so
     * the film respects your metering and exposure compensation.
     *
     * @return the gain, or 2.0 (a typical value) if it can't be measured
     */
    public static float matchExposure(short[] rgb, int w, int h, int[] thumb, int tw, int th) {
        if (thumb == null || tw < 16 || th < 16) return 2f;
        // average the RAW into the thumbnail's grid
        double[] sum = new double[tw * th];
        int[] cnt = new int[tw * th];
        float[] lin = new float[3];
        RawSource src = new RawSource(rgb, w, h, 1f);
        for (int y = 0; y < h; y += 2) {
            int ty = Math.min(th - 1, (int) ((long) y * th / h));
            for (int x = 0; x < w; x += 2) {
                int tx = Math.min(tw - 1, (int) ((long) x * tw / w));
                src.linear(y * w + x, lin);
                sum[ty * tw + tx] += LUMA_SG[0] * lin[0] + LUMA_SG[1] * lin[1] + LUMA_SG[2] * lin[2];
                cnt[ty * tw + tx]++;
            }
        }
        List<Float> ratios = new ArrayList<>();
        for (int i = 0; i < tw * th; i++) {
            if (cnt[i] == 0) continue;
            int px = thumb[i];
            float yj = LUMA[0] * SRGB_DECODE[(px >> 16) & 0xff] + LUMA[1] * SRGB_DECODE[(px >> 8) & 0xff]
                       + LUMA[2] * SRGB_DECODE[px & 0xff];
            double yr = sum[i] / cnt[i];
            if (yj > 0.03f && yj < 0.5f && yr > 1e-4) ratios.add((float) (yj / yr));
        }
        if (ratios.size() < 200) return 2f;
        java.util.Collections.sort(ratios);
        float g = ratios.get(ratios.size() / 2);
        return Math.max(0.25f, Math.min(16f, g));
    }

    /** A RAW image resized for output. */
    public static final class Raw16 {
        public final short[] rgb;
        public final int w, h;

        public Raw16(short[] rgb, int w, int h) {
            this.rgb = rgb;
            this.w = w;
            this.h = h;
        }
    }

    /** Area-average a 16-bit RGB image down so its long edge is at most {@code maxLong}. */
    public static Raw16 downscale(short[] rgb, int w, int h, int maxLong) {
        int l = Math.max(w, h);
        if (maxLong <= 0 || l <= maxLong) return new Raw16(rgb, w, h);
        double s = maxLong / (double) l;
        int ow = Math.max(1, (int) Math.round(w * s)), oh = Math.max(1, (int) Math.round(h * s));
        short[] out = new short[ow * oh * 3];
        for (int oy = 0; oy < oh; oy++) {
            int y0 = (int) ((long) oy * h / oh), y1 = Math.max(y0 + 1, (int) ((long) (oy + 1) * h / oh));
            for (int ox = 0; ox < ow; ox++) {
                int x0 = (int) ((long) ox * w / ow), x1 = Math.max(x0 + 1, (int) ((long) (ox + 1) * w / ow));
                long r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = y0; y < y1; y++) {
                    int row = y * w;
                    for (int x = x0; x < x1; x++) {
                        int i = (row + x) * 3;
                        r += rgb[i] & 0xffff;
                        g += rgb[i + 1] & 0xffff;
                        b += rgb[i + 2] & 0xffff;
                        n++;
                    }
                }
                int o = (oy * ow + ox) * 3;
                out[o] = (short) (r / n);
                out[o + 1] = (short) (g / n);
                out[o + 2] = (short) (b / n);
            }
        }
        return new Raw16(out, ow, oh);
    }

    // ---- public API -----------------------------------------------------------------

    /**
     * Develop an image in place.
     *
     * @param argb pixels (0xAARRGGBB, sRGB), overwritten with the result
     */
    public static Result develop(int[] argb, int w, int h, Stock stock, Lut lut, Params p,
                                 ExecutorService pool) throws Exception {
        return develop(Source.jpeg(argb, w, h), argb, stock, lut, p, pool);
    }

    /**
     * Develop from any source into {@code out} (w*h ARGB). For a JPEG source,
     * {@code out} may be the source's own pixel array.
     */
    public static Result develop(Source src, int[] out, Stock stock, Lut lut, Params p,
                                 ExecutorService pool) throws Exception {
        long t0 = System.currentTimeMillis();
        final int w = src.w, h = src.h;
        int longEdge = Math.max(w, h);
        float pxUm = FILM_WIDTH_UM / (longEdge * Math.max(1f, p.filmScale));

        // 1. small linear copy for metering and halation
        int f = Math.max(1, (int) Math.ceil(longEdge / (double) GLOW_TARGET));
        int sw = (w + f - 1) / f, sh = (h + f - 1) / f;
        float[] lights = p.halation > 0 ? new float[sw * sh * 3] : null;
        float[] small = downscaleLinear(src, f, sw, sh, lights);

        // 2. lab-style print exposure
        float ev = p.ev + labPrintEv(small, sw, sh, p.lab);
        float gain = (float) Math.pow(2, ev);

        // 3. halation glow (on the small copy, upsampled per pixel later)
        float[] aTot = halationStrength(stock);
        for (int c = 0; c < 3; c++) aTot[c] *= p.halation;
        float[] glow = null;
        if (Math.max(aTot[0], Math.max(aTot[1], aTot[2])) > 0) {
            glow = halationGlow(lights, sw, sh, gain, halationSigmaUm(stock) / pxUm / f, aTot);
        }
        final float[] norm = {1f / (1f + aTot[0]), 1f / (1f + aTot[1]), 1f / (1f + aTot[2])};

        // 4. grain setup
        float amt = (float) (p.grain * 0.020 * Math.sqrt(stock.iso / 400.0)
                             * (stock.slide ? 0.75 : 1.0) * (lut.mono ? 1.35 : 1.0));
        float gsize = (float) ((6.0 + 6.0 * Math.sqrt(stock.iso / 400.0)) / pxUm * Math.max(0.25f, p.grainSize));
        if (gsize < 1f) {
            amt *= gsize;
            gsize = 1f;
        }
        Random rnd = new Random(p.seed);
        final int[][] off = new int[5][2];
        for (int o = 1; o <= 4; o++) {
            off[o][0] = rnd.nextInt(TILE);
            off[o][1] = rnd.nextInt(TILE);
        }
        final float[] g1 = amt > 0 ? grainTile(gsize, 1) : null;
        final float[] g2 = amt > 0 ? grainTile(gsize * 2.3f, 2) : null;
        final float[] g3 = amt > 0 ? grainTile(gsize * 1.6f, 3) : null;
        final float[] g4 = amt > 0 ? grainTile(gsize * 1.6f, 4) : null;
        final float fAmt = amt;
        // white balance shift: up to about a stop apart between red and blue at +-1
        final float[] wb = {
            gain * (float) Math.pow(2, 0.5 * p.warmth + 0.15 * p.tint),
            gain * (float) Math.pow(2, -0.3 * p.tint),
            gain * (float) Math.pow(2, -0.5 * p.warmth + 0.15 * p.tint)};
        final float sat = Math.max(0f, p.color);
        final float[] fGlow = glow;
        final int fsw = sw, fsh = sh;

        // 5. per-pixel pass, in row bands across threads
        int threads = p.threads > 0 ? p.threads : Runtime.getRuntime().availableProcessors();
        int band = Math.max(16, (h + threads * 4 - 1) / (threads * 4));
        List<Future<?>> jobs = new ArrayList<>();
        for (int y0 = 0; y0 < h; y0 += band) {
            final int ya = y0, yb = Math.min(h, y0 + band);
            Runnable r = () -> processRows(src, out, ya, yb, wb, fGlow, fsw, fsh, norm, lut, sat,
                                           fAmt, g1, g2, g3, g4, off);
            if (pool != null) jobs.add(pool.submit(r));
            else r.run();
        }
        for (Future<?> j : jobs) j.get();
        return new Result(ev, System.currentTimeMillis() - t0);
    }

    /** What a few reference colors look like on a stock (for UI swatches). sRGB ints. */
    public static int[] swatch(Lut lut) {
        float[][] patches = {
            {0.18f, 0.18f, 0.18f}, {0.50f, 0.33f, 0.24f}, {0.20f, 0.35f, 0.62f},
            {0.13f, 0.25f, 0.07f}, {0.55f, 0.06f, 0.05f}, {0.70f, 0.55f, 0.10f}};
        int[] out = new int[patches.length];
        float[] tmp = new float[3];
        for (int i = 0; i < patches.length; i++) {
            float[] c = patches[i];
            float r = M[0] * c[0] + M[1] * c[1] + M[2] * c[2];
            float g = M[3] * c[0] + M[4] * c[1] + M[5] * c[2];
            float b = M[6] * c[0] + M[7] * c[1] + M[8] * c[2];
            if (lut.mono) {
                float v = lut.curveAt(slog3Fast(lut.weights[0] * r + lut.weights[1] * g + lut.weights[2] * b));
                out[i] = pack(v, v, v);
            } else {
                lut.apply(slog3Fast(r), slog3Fast(g), slog3Fast(b), tmp, 0);
                out[i] = pack(tmp[0], tmp[1], tmp[2]);
            }
        }
        return out;
    }

    // ---- stages ---------------------------------------------------------------------

    /** sRGB pixel -> linear S-Gamut3.Cine, with the JPEG highlight stretch. */
    static void toLinear(int px, float[] out, int o) {
        float r = SRGB_DECODE[(px >> 16) & 0xff];
        float g = SRGB_DECODE[(px >> 8) & 0xff];
        float b = SRGB_DECODE[px & 0xff];
        float m = Math.max(r, Math.max(g, b));
        if (m > 0.85f) {  // camera JPEGs squash highlights into 1.0: stretch them back (up to +1 stop)
            float t = (m - 0.85f) / 0.15f;
            if (t > 1) t = 1;
            float k = (float) Math.pow(2, t * t * (3 - 2 * t));
            r *= k; g *= k; b *= k;
        }
        out[o] = M[0] * r + M[1] * g + M[2] * b;
        out[o + 1] = M[3] * r + M[4] * g + M[5] * b;
        out[o + 2] = M[6] * r + M[7] * g + M[8] * b;
    }

    static float[] downscaleLinear(Source src, int f, int sw, int sh) {
        return downscaleLinear(src, f, sw, sh, null);
    }

    /** Area-average the linear image down by {@code f}. If {@code lights} is given, it gets the
     *  same average of {@link #lightSource} values: what halation sees. */
    static float[] downscaleLinear(Source src, int f, int sw, int sh, float[] lights) {
        int w = src.w, h = src.h;
        float[] small = new float[sw * sh * 3];
        int[] count = new int[sw * sh];
        float[] tmp = new float[3];
        for (int y = 0; y < h; y++) {
            int sy = y / f;
            int row = y * w;
            for (int x = 0; x < w; x++) {
                src.linear(row + x, tmp);
                int si = sy * sw + x / f;
                small[si * 3] += tmp[0];
                small[si * 3 + 1] += tmp[1];
                small[si * 3 + 2] += tmp[2];
                if (lights != null) {
                    float k = lightSource(tmp);
                    lights[si * 3] += tmp[0] * k;
                    lights[si * 3 + 1] += tmp[1] * k;
                    lights[si * 3 + 2] += tmp[2] * k;
                }
                count[si]++;
            }
        }
        for (int i = 0; i < count.length; i++) {
            float k = 1f / Math.max(1, count[i]);
            small[i * 3] *= k;
            small[i * 3 + 1] *= k;
            small[i * 3 + 2] *= k;
            if (lights != null) {
                lights[i * 3] *= k;
                lights[i * 3 + 1] *= k;
                lights[i * 3 + 2] *= k;
            }
        }
        return small;
    }

    /** Lamps, the sun and specular glints are many stops brighter than white, but a camera file
     *  clips them near 1-2. Halation is what shows that extra light on film, so pixels at the
     *  clip point count up to {@code LIGHT_BOOST} times brighter for it (not for the image itself). */
    static float lightSource(float[] lin) {
        float m = Math.max(lin[0], Math.max(lin[1], lin[2]));
        if (m <= LIGHT_KNEE) return 1f;
        float t = Math.min(1f, (m - LIGHT_KNEE) / (LIGHT_CLIP - LIGHT_KNEE));
        return 1f + (LIGHT_BOOST - 1f) * t * t * (3 - 2 * t);
    }

    static final float LIGHT_KNEE = 0.9f, LIGHT_CLIP = 1.9f, LIGHT_BOOST = 8f;

    /** Like a lab's auto printer: nudge exposure part of the way toward a
     *  center-weighted mid-grey, limited to +-1.5 stops. */
    static float labPrintEv(float[] small, int sw, int sh, float strength) {
        if (strength <= 0) return 0f;
        double half = Math.max(sw, sh) / 2.0;
        double sumW = 0, sumL = 0;
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int i = (y * sw + x) * 3;
                double lum = LUMA_SG[0] * small[i] + LUMA_SG[1] * small[i + 1] + LUMA_SG[2] * small[i + 2];
                if (lum < 1e-4) lum = 1e-4;
                double dy = (y - sh / 2.0) / half, dx = (x - sw / 2.0) / half;
                double wgt = Math.exp(-(dx * dx + dy * dy) / (2 * 0.35 * 0.35));
                sumW += wgt;
                sumL += wgt * Math.log(lum);
            }
        }
        double geo = Math.exp(sumL / sumW);
        double ev = strength * (Math.log(0.18 / geo) / Math.log(2));
        return (float) Math.max(-1.5, Math.min(1.5, ev));
    }

    /** spektrafilm halation: sum of N gaussian bounces with sqrt(k)-growing widths,
     *  scaled per channel. Returns the additive glow at small resolution. */
    static float[] halationGlow(float[] small, int sw, int sh, float gain, float sigmaPx, float[] aTot) {
        int n = sw * sh;
        float[] src = new float[n * 3];
        for (int i = 0; i < src.length; i++) src[i] = small[i] * gain;
        float[] acc = new float[n * 3];
        double wsum = 0;
        for (int k = 0; k < HALATION_BOUNCES; k++) wsum += Math.pow(HALATION_DECAY, k);
        for (int k = 1; k <= HALATION_BOUNCES; k++) {
            float wk = (float) (Math.pow(HALATION_DECAY, k - 1) / wsum);
            float[] b = blur3(src, sw, sh, sigmaPx * (float) Math.sqrt(k));
            for (int i = 0; i < acc.length; i++) acc[i] += wk * b[i];
        }
        for (int i = 0; i < n; i++) {
            acc[i * 3] *= aTot[0];
            acc[i * 3 + 1] *= aTot[1];
            acc[i * 3 + 2] *= aTot[2];
        }
        return acc;
    }

    /** Approximate gaussian blur of an interleaved RGB image: 3 box passes per axis. */
    static float[] blur3(float[] img, int w, int h, float sigma) {
        int r = (int) Math.round((Math.sqrt(4 * sigma * sigma + 1) - 1) / 2);
        if (r < 1) return img.clone();
        float[] a = img.clone(), b = new float[img.length];
        for (int pass = 0; pass < 3; pass++) {
            boxH(a, b, w, h, r);
            boxV(b, a, w, h, r);
        }
        return a;
    }

    private static void boxH(float[] src, float[] dst, int w, int h, int r) {
        float k = 1f / (2 * r + 1);
        for (int y = 0; y < h; y++) {
            int row = y * w * 3;
            for (int c = 0; c < 3; c++) {
                float s = 0;
                for (int x = -r - 1; x < r; x++) s += src[row + clampI(x, w) * 3 + c];
                for (int x = 0; x < w; x++) {
                    s += src[row + clampI(x + r, w) * 3 + c] - src[row + clampI(x - r - 1, w) * 3 + c];
                    dst[row + x * 3 + c] = s * k;
                }
            }
        }
    }

    private static void boxV(float[] src, float[] dst, int w, int h, int r) {
        float k = 1f / (2 * r + 1);
        for (int x = 0; x < w; x++) {
            for (int c = 0; c < 3; c++) {
                int col = x * 3 + c;
                float s = 0;
                for (int y = -r - 1; y < r; y++) s += src[clampI(y, h) * w * 3 + col];
                for (int y = 0; y < h; y++) {
                    s += src[clampI(y + r, h) * w * 3 + col] - src[clampI(y - r - 1, h) * w * 3 + col];
                    dst[y * w * 3 + col] = s * k;
                }
            }
        }
    }

    private static int clampI(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }

    static void processRows(Source src, int[] out, int y0, int y1, float[] wb, float[] glow,
                            int sw, int sh, float[] norm, Lut lut, float sat, float amt,
                            float[] g1, float[] g2, float[] g3, float[] g4, int[][] off) {
        final int w = src.w, h = src.h;
        float[] lin = new float[3];
        float[] d = new float[3];
        final boolean mono = lut.mono;
        float sxk = sw / (float) w, syk = sh / (float) h;
        for (int y = y0; y < y1; y++) {
            // bilinear sampling row of the small glow image
            float fy = (y + 0.5f) * syk - 0.5f;
            int gy0 = (int) Math.floor(fy);
            float ty = fy - gy0;
            int gya = clampI(gy0, sh), gyb = clampI(gy0 + 1, sh);
            int row = y * w;
            int ty1 = (y + off[1][0]) % TILE, ty2 = (y + off[2][0]) % TILE;
            int ty3 = (y + off[3][0]) % TILE, ty4 = (y + off[4][0]) % TILE;
            for (int x = 0; x < w; x++) {
                src.linear(row + x, lin);
                float r = lin[0] * wb[0], g = lin[1] * wb[1], b = lin[2] * wb[2];
                if (glow != null) {
                    float fx = (x + 0.5f) * sxk - 0.5f;
                    int gx0 = (int) Math.floor(fx);
                    float tx = fx - gx0;
                    int gxa = clampI(gx0, sw), gxb = clampI(gx0 + 1, sw);
                    int i00 = (gya * sw + gxa) * 3, i01 = (gya * sw + gxb) * 3;
                    int i10 = (gyb * sw + gxa) * 3, i11 = (gyb * sw + gxb) * 3;
                    float w00 = (1 - tx) * (1 - ty), w01 = tx * (1 - ty), w10 = (1 - tx) * ty, w11 = tx * ty;
                    r = (r + w00 * glow[i00] + w01 * glow[i01] + w10 * glow[i10] + w11 * glow[i11]) * norm[0];
                    g = (g + w00 * glow[i00 + 1] + w01 * glow[i01 + 1] + w10 * glow[i10 + 1] + w11 * glow[i11 + 1]) * norm[1];
                    b = (b + w00 * glow[i00 + 2] + w01 * glow[i01 + 2] + w10 * glow[i10 + 2] + w11 * glow[i11 + 2]) * norm[2];
                }
                float dr, dg, db;
                if (mono) {
                    float[] mw = lut.weights;
                    dr = dg = db = lut.curveAt(slog3Fast(mw[0] * r + mw[1] * g + mw[2] * b));
                } else {
                    lut.apply(slog3Fast(r), slog3Fast(g), slog3Fast(b), d, 0);
                    dr = d[0]; dg = d[1]; db = d[2];
                    if (sat != 1f) {
                        float yl = LUMA[0] * dr + LUMA[1] * dg + LUMA[2] * db;
                        dr = yl + (dr - yl) * sat;
                        dg = yl + (dg - yl) * sat;
                        db = yl + (db - yl) * sat;
                    }
                }
                if (amt > 0) {
                    float yc = LUMA[0] * dr + LUMA[1] * dg + LUMA[2] * db;
                    yc = yc < 0 ? 0 : (yc > 1 ? 1 : yc);
                    float t = yc / 0.2f;
                    if (t > 1) t = 1;
                    float wgt = amt * (0.25f + 2.7f * yc * (1 - yc)) * (0.35f + 0.65f * t * t * (3 - 2 * t));
                    int tx1 = (x + off[1][1]) % TILE, tx2 = (x + off[2][1]) % TILE;
                    float n = wgt * (0.8f * g1[ty1 * TILE + tx1] + 0.4f * g2[ty2 * TILE + tx2]);
                    int tx3 = (x + off[3][1]) % TILE, tx4 = (x + off[4][1]) % TILE;
                    float c1 = mono ? 0f : wgt * 0.3f * g3[ty3 * TILE + tx3];
                    float c2 = mono ? 0f : wgt * 0.3f * g4[ty4 * TILE + tx4];
                    dr += n + c1;
                    dg += n - 0.3f * (c1 + c2);
                    db += n + c2;
                }
                out[row + x] = pack(dr, dg, db);
            }
        }
    }

    static int pack(float r, float g, float b) {
        return 0xff000000 | (to8(r) << 16) | (to8(g) << 8) | to8(b);
    }

    private static int to8(float v) {
        int i = (int) (v * 255f + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    // ---- grain tiles ----------------------------------------------------------------

    static final int TILE = 1024;
    private static final Map<String, float[]> GRAIN_CACHE = new HashMap<>();

    /** Seamless, normalized noise tile with grains about `size` px across. */
    static synchronized float[] grainTile(float size, int octave) {
        String key = Math.round(size * 100) + ":" + octave;
        float[] cached = GRAIN_CACHE.get(key);
        if (cached != null) return cached;
        int t = Math.max(8, Math.round(TILE / size));
        Random rnd = new Random(1000 + octave);
        float[] src = new float[t * t];
        for (int i = 0; i < src.length; i++) src[i] = (float) rnd.nextGaussian();
        float[] tile = new float[TILE * TILE];
        if (t == TILE) {
            System.arraycopy(src, 0, tile, 0, src.length);
        } else {
            // Catmull-Rom upsampling with wrap-around, so the tile repeats seamlessly
            float k = t / (float) TILE;
            float[] wy = new float[4], wx = new float[4];
            for (int y = 0; y < TILE; y++) {
                float fy = (y + 0.5f) * k - 0.5f;
                int iy = (int) Math.floor(fy);
                cubicWeights(fy - iy, wy);
                for (int x = 0; x < TILE; x++) {
                    float fx = (x + 0.5f) * k - 0.5f;
                    int ix = (int) Math.floor(fx);
                    cubicWeights(fx - ix, wx);
                    float s = 0;
                    for (int j = 0; j < 4; j++) {
                        int yy = Math.floorMod(iy - 1 + j, t) * t;
                        float rs = 0;
                        for (int i = 0; i < 4; i++) rs += wx[i] * src[yy + Math.floorMod(ix - 1 + i, t)];
                        s += wy[j] * rs;
                    }
                    tile[y * TILE + x] = s;
                }
            }
        }
        double mean = 0, var = 0;
        for (float v : tile) mean += v;
        mean /= tile.length;
        for (float v : tile) var += (v - mean) * (v - mean);
        float sd = (float) Math.sqrt(var / tile.length) + 1e-6f;
        for (int i = 0; i < tile.length; i++) tile[i] = (float) ((tile[i] - mean) / sd);
        GRAIN_CACHE.put(key, tile);
        return tile;
    }

    private static void cubicWeights(float t, float[] w) {
        float t2 = t * t, t3 = t2 * t;
        w[0] = -0.5f * t3 + t2 - 0.5f * t;
        w[1] = 1.5f * t3 - 2.5f * t2 + 1;
        w[2] = -1.5f * t3 + 2f * t2 + 0.5f * t;
        w[3] = 0.5f * t3 - 0.5f * t2;
    }

    // ---- small math -----------------------------------------------------------------

    static double[][] invert3(double[][] a) {
        double det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1])
                   - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
                   + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
        double[][] r = new double[3][3];
        r[0][0] = (a[1][1] * a[2][2] - a[1][2] * a[2][1]) / det;
        r[0][1] = (a[0][2] * a[2][1] - a[0][1] * a[2][2]) / det;
        r[0][2] = (a[0][1] * a[1][2] - a[0][2] * a[1][1]) / det;
        r[1][0] = (a[1][2] * a[2][0] - a[1][0] * a[2][2]) / det;
        r[1][1] = (a[0][0] * a[2][2] - a[0][2] * a[2][0]) / det;
        r[1][2] = (a[0][2] * a[1][0] - a[0][0] * a[1][2]) / det;
        r[2][0] = (a[1][0] * a[2][1] - a[1][1] * a[2][0]) / det;
        r[2][1] = (a[0][1] * a[2][0] - a[0][0] * a[2][1]) / det;
        r[2][2] = (a[0][0] * a[1][1] - a[0][1] * a[1][0]) / det;
        return r;
    }
}
