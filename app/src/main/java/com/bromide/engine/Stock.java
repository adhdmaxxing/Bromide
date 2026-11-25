package com.bromide.engine;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A film stock: which baked spektrafilm LUT to use, and the physical traits
 *  (speed, anti-halation layer) that drive grain and halation. */
public final class Stock {
    public final String id;
    public final String name;
    public final String lut;
    public final int iso;
    /** Film base: "still" (triacetate, ~65 um halation spread) or "cine" (PET, ~50 um). */
    public final String base;
    /** Anti-halation layer: "strong", "weak" or "no" (rem-jet removed). */
    public final String antiHalation;
    public final boolean slide;
    public final String blurb;
    /** Black & white (Bromide's own model; spektrafilm has no B&W data yet). */
    public final boolean mono;

    public Stock(String id, String name, String lut, int iso, String base, String antiHalation,
                 boolean slide, String blurb) {
        this.id = id;
        this.name = name;
        this.lut = lut;
        this.iso = iso;
        this.base = base;
        this.antiHalation = antiHalation;
        this.slide = slide;
        this.blurb = blurb;
        this.mono = lut.startsWith("bw_");
    }

    public boolean isCine() {
        return "cine".equals(base);
    }

    /** Short category label for filters: "Color", "B&W", "Slide" or "Cinema". */
    public String category() {
        if (mono) return "B&W";
        if (slide) return "Slide";
        if (isCine()) return "Cinema";
        return "Color";
    }

    /** Lower-case text used by search. */
    public String searchText() {
        return (id + " " + name + " " + blurb + " " + category() + " iso " + iso).toLowerCase(java.util.Locale.ROOT);
    }

    public static final List<Stock> ALL = Collections.unmodifiableList(Arrays.asList(
        new Stock("portra400", "Kodak Portra 400", "portra400", 400, "still", "strong", false,
                  "Soft contrast, natural skin, gentle color"),
        new Stock("portra160", "Kodak Portra 160", "portra160", 160, "still", "strong", false,
                  "Finer grain, a touch cleaner than 400"),
        new Stock("portra800", "Kodak Portra 800", "portra800", 800, "still", "strong", false,
                  "Low light Portra, warmer, more grain"),
        new Stock("gold200", "Kodak Gold 200", "gold200", 200, "still", "weak", false,
                  "Warm, sunny, nostalgic consumer film"),
        new Stock("ultramax400", "Kodak Ultramax 400", "ultramax400", 400, "still", "weak", false,
                  "Punchy everyday color, visible grain"),
        new Stock("ektar100", "Kodak Ektar 100", "ektar100", 100, "still", "strong", false,
                  "Saturated, fine grain, vivid landscapes"),
        new Stock("c200", "Fujifilm C200", "c200", 200, "still", "weak", false,
                  "Cool greens, classic Fuji consumer look"),
        new Stock("superia400", "Fujifilm Superia X-Tra 400", "superia400", 400, "still", "weak", false,
                  "Fuji greens and magentas, gritty"),
        new Stock("pro400h", "Fujifilm Pro 400H", "pro400h", 400, "still", "strong", false,
                  "Airy pastels, cyan-leaning shadows"),
        new Stock("vision250d", "Kodak Vision3 250D", "vision250d", 250, "cine", "strong", false,
                  "Daylight motion picture film, 2383 print"),
        new Stock("vision500t", "Kodak Vision3 500T", "vision500t", 500, "cine", "strong", false,
                  "Tungsten cinema film: cool by day, rich at night"),
        new Stock("cinestill800t", "CineStill 800T", "vision500t", 800, "cine", "no", false,
                  "500T without rem-jet: red halos around lights"),
        new Stock("provia100f", "Fujifilm Provia 100F", "provia100f", 100, "still", "strong", true,
                  "Neutral, clean slide film"),
        new Stock("velvia100", "Fujifilm Velvia 100", "velvia100", 100, "still", "strong", true,
                  "Dense, saturated slide film"),
        new Stock("ektachrome100", "Kodak Ektachrome 100", "ektachrome100", 100, "still", "strong", true,
                  "Crisp, cool-leaning slide film"),
        new Stock("kodachrome64", "Kodak Kodachrome 64", "kodachrome64", 64, "still", "weak", true,
                  "The legendary slide film, warm reds"),
        new Stock("trix400", "Kodak Tri-X 400", "bw_trix400", 400, "still", "strong", false,
                  "Classic gritty black & white, deep blacks"),
        new Stock("hp5", "Ilford HP5 Plus 400", "bw_hp5", 400, "still", "strong", false,
                  "Forgiving, softer contrast, timeless grain"),
        new Stock("tmax100", "Kodak T-Max 100", "bw_tmax100", 100, "still", "strong", false,
                  "Crisp, fine-grained, modern black & white"),
        new Stock("delta3200", "Ilford Delta 3200", "bw_delta3200", 3200, "still", "strong", false,
                  "Night film: big grain, moody low contrast"),
        new Stock("panf50", "Ilford Pan F Plus 50", "bw_panf50", 50, "still", "strong", false,
                  "Ultra fine grain, punchy contrast"),
        new Stock("acros100", "Fujifilm Acros 100 II", "bw_acros100", 100, "still", "strong", false,
                  "Smooth, silky tones, very fine grain"),
        new Stock("sfx200", "Ilford SFX 200", "bw_sfx200", 200, "still", "strong", false,
                  "Extended red: dark skies, glowing foliage")
    ));

    public static Stock byId(String id) {
        for (Stock s : ALL) if (s.id.equalsIgnoreCase(id)) return s;
        return null;
    }

    public static Stock defaultStock() {
        return ALL.get(0);
    }
}
