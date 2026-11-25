import com.bromide.engine.FilmEngine;
import com.bromide.engine.Lut;
import com.bromide.engine.Stock;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;

/**
 * Develops a photo with Bromide's own engine on a desktop, for the store images: the
 * before/afters in the listing are real Bromide output, not mockups. See render.sh.
 *
 * Usage: java Render LUT_DIR INPUT.jpg OUT_PREFIX LONG_EDGE STOCK...
 * Writes OUT_PREFIX_original.png and OUT_PREFIX_STOCK.png for each stock.
 */
public final class Render {
    public static void main(String[] a) throws Exception {
        File lutDir = new File(a[0]);
        BufferedImage in = ImageIO.read(new File(a[1]));
        String prefix = a[2];
        int longEdge = Integer.parseInt(a[3]);

        // Scaled to LONG_EDGE, up or down: grain and halation are then drawn at that size.
        double s = longEdge / (double) Math.max(in.getWidth(), in.getHeight());
        int w = (int) Math.round(in.getWidth() * s), h = (int) Math.round(in.getHeight() * s);
        BufferedImage src = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = src.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(in, 0, 0, w, h, null);
        g.dispose();
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        ImageIO.write(src, "png", new File(prefix + "_original.png"));

        ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        try {
            for (int i = 4; i < a.length; i++) {
                Stock stock = Stock.byId(a[i]);
                if (stock == null) throw new IllegalArgumentException("unknown stock " + a[i]);
                Lut lut;
                try (InputStream lin = new BufferedInputStream(new FileInputStream(new File(lutDir, stock.lut + ".lut")))) {
                    lut = Lut.read(lin);
                }
                FilmEngine.Params p = new FilmEngine.Params();
                p.seed = 7;
                // Like the app's film previews: grain and halation at the size they'd have on a
                // 2048 px photo, so the film's texture still shows on a store-sized image.
                p.filmScale = Math.max(1f, 2048f / Math.max(w, h));
                int[] out = px.clone();
                FilmEngine.Result r = FilmEngine.develop(out, w, h, stock, lut, p, pool);
                BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                dst.setRGB(0, 0, w, h, out, 0, w);
                ImageIO.write(dst, "png", new File(prefix + "_" + stock.id + ".png"));
                System.out.printf("%s_%s.png  %dx%d  print %+.2f EV  %d ms%n", prefix, stock.id, w, h, r.printEv, r.millis);
            }
        } finally {
            pool.shutdown();
        }
    }
}
