package com.bromide.engine;

/**
 * Camera RAW decoding through LibRaw (native, see app/src/main/cpp).
 * Output is scene-linear Rec.2020, 16 bit, white balance as shot, oriented.
 */
public final class RawDecoder {

    /** A decoded RAW. */
    public static final class Image {
        public int width;
        public int height;
        /** Interleaved linear Rec.2020 RGB, 0..65535 = 0..sensor clip. */
        public short[] rgb;
        /** The camera's embedded JPEG preview (for matching its exposure), or null. */
        public byte[] thumbnail;
        public String camera;
        /** Rotation the embedded thumbnail still needs to match the decoded image, in degrees. */
        public int thumbFlip;
    }

    private static volatile boolean loaded;
    private static volatile Throwable loadError;

    public static synchronized boolean available() {
        if (!loaded && loadError == null) {
            try {
                System.loadLibrary("fbraw");
                loaded = true;
            } catch (Throwable t) {
                loadError = t;
            }
        }
        return loaded;
    }

    /**
     * @param half decode at half resolution (fast: no demosaicing; a 12 MP file gives 3 MP)
     * @throws Exception with LibRaw's message if the file can't be decoded
     */
    public static Image decode(String path, boolean half) throws Exception {
        if (!available()) throw new IllegalStateException("RAW decoder not available", loadError);
        Image out = new Image();
        String err = nativeDecode(path, half, out);
        if (err != null) throw new Exception(err);
        return out;
    }

    public static boolean isRaw(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        for (String ext : EXTS) if (n.endsWith(ext)) return true;
        return false;
    }

    public static final String[] EXTS = {
        ".arw", ".srf", ".sr2", ".dng", ".nef", ".nrw", ".cr2", ".cr3", ".crw", ".raf",
        ".orf", ".rw2", ".pef", ".srw", ".x3f", ".3fr", ".iiq", ".rwl", ".erf", ".mrw"};

    /** Returns null on success, or an error message. Fills {@code out}. */
    private static native String nativeDecode(String path, boolean half, Image out);
}
