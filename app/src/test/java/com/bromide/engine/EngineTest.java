package com.bromide.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class EngineTest {

    private static Lut lut(String name) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream("src/main/assets/luts/" + name + ".lut"))) {
            return Lut.read(in);
        }
    }

    @Test
    public void allStocksHaveLuts() throws Exception {
        for (Stock s : Stock.ALL) {
            Lut l = lut(s.lut);
            assertEquals(s.mono, l.mono);
            assertEquals(s.mono ? 4096 : 65, l.n);
        }
    }

    @Test
    public void midGreyStaysRoughlyNeutral() throws Exception {
        Lut l = lut("portra400");
        int[] sw = FilmEngine.swatch(l);
        int grey = sw[0];
        int r = (grey >> 16) & 0xff, g = (grey >> 8) & 0xff, b = grey & 0xff;
        assertTrue("grey too dark/bright: " + r, r > 70 && r < 170);
        assertTrue("grey not neutral", Math.abs(r - g) < 20 && Math.abs(g - b) < 20);
    }

    @Test
    public void blackAndWhiteIsNeutral() throws Exception {
        int[] px = {0xffc83c28, 0xff3c8c3c, 0xff2850b4, 0xff808080};
        FilmEngine.Params p = new FilmEngine.Params();
        p.lab = 0;
        p.grain = 0;
        FilmEngine.develop(px, 2, 2, Stock.byId("trix400"), lut("bw_trix400"), p, null);
        for (int v : px) {
            int r = (v >> 16) & 0xff, g = (v >> 8) & 0xff, b = v & 0xff;
            assertTrue("not grey: " + Integer.toHexString(v), r == g && g == b);
        }
    }

    private static int[] flat(int argb, int n) {
        int[] px = new int[n];
        java.util.Arrays.fill(px, argb);
        return px;
    }

    private static int channel(int v, int shift) {
        return (v >> shift) & 0xff;
    }

    @Test
    public void colorZeroPrintsGrey() throws Exception {
        int[] px = {0xffc83c28, 0xff3c8c3c, 0xff2850b4, 0xff808080};
        FilmEngine.Params p = new FilmEngine.Params();
        p.lab = 0;
        p.grain = 0;
        p.color = 0;
        FilmEngine.develop(px, 2, 2, Stock.byId("portra400"), lut("portra400"), p, null);
        for (int v : px) {
            int r = channel(v, 16), g = channel(v, 8), b = channel(v, 0);
            assertTrue("not grey: " + Integer.toHexString(v), Math.abs(r - g) <= 1 && Math.abs(g - b) <= 1);
        }
    }

    @Test
    public void warmthShiftsRedAndBlue() throws Exception {
        int[] warm = flat(0xff808080, 16), cool = flat(0xff808080, 16);
        FilmEngine.Params p = new FilmEngine.Params();
        p.lab = 0;
        p.grain = 0;
        p.warmth = 0.5f;
        FilmEngine.develop(warm, 4, 4, Stock.byId("portra400"), lut("portra400"), p, null);
        p.warmth = -0.5f;
        FilmEngine.develop(cool, 4, 4, Stock.byId("portra400"), lut("portra400"), p, null);
        assertTrue("warm isn't warmer", channel(warm[0], 16) - channel(warm[0], 0) > 15);
        assertTrue("cool isn't cooler", channel(cool[0], 0) - channel(cool[0], 16) > 15);
    }

    /** Standard deviation of the green channel. */
    private static double noise(int[] px) {
        double sum = 0, sq = 0;
        for (int v : px) {
            int g = channel(v, 8);
            sum += g;
            sq += g * g;
        }
        double m = sum / px.length;
        return Math.sqrt(sq / px.length - m * m);
    }

    @Test
    public void previewScaleMakesGrainVisible() throws Exception {
        // a 200 px picture is a whole 35 mm frame shrunk down: its grain all but averages out.
        // Previews pretend it's a crop of a full-size photo so the grain shows.
        int n = 200 * 200;
        int[] honest = flat(0xff808080, n), preview = flat(0xff808080, n);
        FilmEngine.Params p = new FilmEngine.Params();
        p.lab = 0;
        FilmEngine.develop(honest, 200, 200, Stock.byId("portra400"), lut("portra400"), p, null);
        p.filmScale = 10;
        FilmEngine.develop(preview, 200, 200, Stock.byId("portra400"), lut("portra400"), p, null);
        assertTrue("honest grain should be faint: " + noise(honest), noise(honest) < 1.0);
        assertTrue("preview grain should show: " + noise(preview), noise(preview) > 2.0);
    }

    @Test
    public void grainSizeChangesTheGrain() throws Exception {
        int n = 300 * 300;
        int[] fine = flat(0xff808080, n), coarse = flat(0xff808080, n);
        FilmEngine.Params p = new FilmEngine.Params();
        p.lab = 0;
        p.filmScale = 8;
        p.grainSize = 0.5f;
        FilmEngine.develop(fine, 300, 300, Stock.byId("portra400"), lut("portra400"), p, null);
        p.grainSize = 2f;
        FilmEngine.develop(coarse, 300, 300, Stock.byId("portra400"), lut("portra400"), p, null);
        // relative to how strong the grain is, coarse grain varies less from pixel to pixel
        double c = neighborDiff(coarse, 300) / noise(coarse), f = neighborDiff(fine, 300) / noise(fine);
        assertTrue("coarse " + c + " vs fine " + f, c < f * 0.8);
    }

    private static double neighborDiff(int[] px, int w) {
        double s = 0;
        for (int i = 0; i + 1 < px.length; i++) if ((i + 1) % w != 0) s += Math.abs(channel(px[i], 8) - channel(px[i + 1], 8));
        return s / px.length;
    }

    @Test
    public void clippedLightsGlowRedOnCineStill() throws Exception {
        // a clipped white light on CineStill (no anti-halation layer) gets a red halo around it
        int w = 120, h = 120;
        int[] px = flat(0xff101418, w * h);
        for (int y = 50; y < 70; y++) for (int x = 50; x < 70; x++) px[y * w + x] = 0xffffffff;
        int[] noHalo = px.clone();
        FilmEngine.Params p = new FilmEngine.Params();
        p.lab = 0;
        p.grain = 0;
        p.filmScale = 20;
        FilmEngine.develop(px, w, h, Stock.byId("cinestill800t"), lut("vision500t"), p, null);
        p.halation = 0;
        FilmEngine.develop(noHalo, w, h, Stock.byId("cinestill800t"), lut("vision500t"), p, null);
        int near = 60 * w + 74;  // 4 px right of the light
        assertTrue("no red glow next to the light",
                   channel(px[near], 16) - channel(noHalo[near], 16) > 10);
        assertTrue("glow should be red", channel(px[near], 16) - channel(noHalo[near], 16)
                                         > 2 * (channel(px[near], 0) - channel(noHalo[near], 0)));
    }

    @Test
    public void rawExposureMatchesCameraJpeg() throws Exception {
        int w = 200, h = 100;
        short[] raw = new short[w * h * 3];
        int[] thumb = new int[50 * 25];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float lin = 0.02f + 0.2f * x / w;              // raw: what the sensor saw
                short v = (short) Math.round(lin * 65535);
                int i = (y * w + x) * 3;
                raw[i] = raw[i + 1] = raw[i + 2] = v;
            }
        for (int y = 0; y < 25; y++)
            for (int x = 0; x < 50; x++) {
                double lin = 2.0 * (0.02 + 0.2 * (x + 0.5) / 50);  // camera JPEG is one stop brighter
                double s = lin <= 0.0031308 ? lin * 12.92 : 1.055 * Math.pow(lin, 1 / 2.4) - 0.055;
                int g = (int) Math.round(Math.min(1, s) * 255);
                thumb[y * 50 + x] = 0xff000000 | (g << 16) | (g << 8) | g;
            }
        float gain = FilmEngine.matchExposure(raw, w, h, thumb, 50, 25);
        assertEquals(2.0f, gain, 0.15f);

        int[] out = new int[w * h];
        FilmEngine.Params p = new FilmEngine.Params();
        p.grain = 0;
        FilmEngine.develop(FilmEngine.Source.raw(raw, w, h, gain), out, Stock.byId("portra400"), lut("portra400"), p, null);
        assertTrue((out[h / 2 * w + 10] & 0xff) < (out[h / 2 * w + w - 10] & 0xff));
    }

    @Test
    public void developsAGradient() throws Exception {
        int w = 600, h = 400;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int v = x * 255 / (w - 1);
                px[y * w + x] = 0xff000000 | (v << 16) | (v << 8) | v;
            }
        FilmEngine.Params p = new FilmEngine.Params();
        p.lab = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        FilmEngine.Result res = FilmEngine.develop(px, w, h, Stock.byId("cinestill800t"), lut("vision500t"), p, pool);
        pool.shutdown();
        assertEquals(0f, res.printEv, 1e-6f);
        // brightness increases left to right
        int left = px[h / 2 * w + 20] & 0xff, right = px[h / 2 * w + w - 20] & 0xff;
        assertTrue(left < right);
    }

    @Test
    public void ftpOnlyServesLocalNetworks() throws Exception {
        assertTrue(FtpServer.isLocal(java.net.InetAddress.getByName("192.168.43.12")));  // a phone hotspot
        assertTrue(FtpServer.isLocal(java.net.InetAddress.getByName("10.0.0.7")));
        assertTrue(FtpServer.isLocal(java.net.InetAddress.getByName("172.20.10.3")));
        assertTrue(FtpServer.isLocal(java.net.InetAddress.getByName("127.0.0.1")));
        assertTrue(FtpServer.isLocal(java.net.InetAddress.getByName("fd12::1")));
        assertTrue(!FtpServer.isLocal(java.net.InetAddress.getByName("8.8.8.8")));
        assertTrue(!FtpServer.isLocal(java.net.InetAddress.getByName("2001:4860::8888")));
    }

    @Test
    public void ftpWrongPasswordIsRefused() throws Exception {
        File root = Files.createTempDirectory("ftp").toFile();
        AtomicReference<String> got = new AtomicReference<>();
        FtpServer s = new FtpServer(21220, root, "k7mq2x", new FtpServer.Listener() {
            public void onFileReceived(File file, String user) { got.set(file.getName()); }
            public void onClientConnected(String remote, String local) { }
            public void onLog(String message) { }
        });
        s.start();
        try {
            URLConnection c = new URL("ftp://film:guess@127.0.0.1:21220/DSC00003.JPG;type=i").openConnection();
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) {
                o.write(new byte[10]);
                fail("upload with a wrong password should be refused");
            } catch (java.io.IOException expected) {
            }
            assertEquals(null, got.get());
        } finally {
            s.stop();
        }
    }

    @Test
    public void ftpUploadArrives() throws Exception {
        File root = Files.createTempDirectory("ftp").toFile();
        AtomicReference<String> got = new AtomicReference<>();
        FtpServer s = new FtpServer(21219, root, "film", new FtpServer.Listener() {
            public void onFileReceived(File file, String user) { got.set(user + ":" + file.getName() + ":" + file.length()); }
            public void onClientConnected(String remote, String local) { }
            public void onLog(String message) { }
        });
        s.start();
        try {
            URLConnection c = new URL("ftp://gold200:film@127.0.0.1:21219/DSC00001.JPG;type=i").openConnection();
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) {
                o.write(new byte[12345]);
            }
            for (int i = 0; i < 50 && got.get() == null; i++) Thread.sleep(50);
            assertEquals("gold200:DSC00001.JPG:12345", got.get());

            // upload only: what a camera sent can't be read back by anyone on the network
            File kept = new File(root, "DSC00002.JPG");
            Files.write(kept.toPath(), new byte[100]);
            URLConnection r = new URL("ftp://gold200:film@127.0.0.1:21219/DSC00002.JPG;type=i").openConnection();
            try (InputStream in = r.getInputStream()) {
                fail("download should be refused, got " + in.read());
            } catch (java.io.IOException expected) {
            }
        } finally {
            s.stop();
        }
    }
}
