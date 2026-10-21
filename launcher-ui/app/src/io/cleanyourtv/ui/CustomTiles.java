package io.cleanyourtv.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * A shelf tile the person styled: a logo (a PNG from a URL, a file pushed with launcher-ui/config.sh, or the app's own
 * icon) on a background in their color, drawn like the built-in brands. Set in config.json "app_tiles" or on the TV
 * (Settings > Shelf, Menu button on an app).
 */
final class CustomTiles {
    private CustomTiles() {}

    /** {logo source, "#RRGGBB"} for the package, or null when it keeps its own look. */
    static String[] get(String pkg) {
        String v = Prefs.tile(pkg);
        if (v.isEmpty()) return null;
        int bar = v.lastIndexOf('|');
        return bar < 0 ? new String[] {v, ""} : new String[] {v.substring(0, bar), v.substring(bar + 1)};
    }

    static int color(String pkg) {
        String[] t = get(pkg);
        try {
            return t == null || t[1].isEmpty() ? 0xFF1C1C1E : Color.parseColor(t[1]);
        } catch (IllegalArgumentException e) {
            return 0xFF1C1C1E;
        }
    }

    /** The tile's colors, top and bottom, like HomeView's built-in styles; the logo is drawn as it is. */
    static int[] style(String pkg) {
        int top = color(pkg);
        float[] hsv = new float[3];
        Color.colorToHSV(top, hsv);
        hsv[2] *= 0.62f;
        return new int[] {top, Color.HSVToColor(hsv), 0};
    }

    /**
     * The logo: the cached download, the pushed file, or the app's icon. A URL not cached yet is downloaded only when
     * `download` is true (the shelf's background load); until then this returns null and the shelf keeps the TV art.
     */
    static Bitmap logo(Context c, String pkg, boolean download) {
        String[] t = get(pkg);
        if (t == null) return null;
        String source = t[0].trim();
        if (source.isEmpty()) return icon(c, pkg);
        if (!source.startsWith("https://")) {
            if (!TvConfig.allowedName(source)) return null;
            return decode(new File(TvConfig.dir(c), new File(source).getName()));
        }
        File cached = new File(new File(c.getFilesDir(), "tiles"), Integer.toHexString((pkg + source).hashCode()) + ".png");
        if (cached.isFile()) return decode(cached);
        if (!download) return null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(source).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            Bitmap b;
            try (InputStream in = conn.getInputStream()) {
                b = BitmapFactory.decodeStream(in);
            } finally {
                conn.disconnect();
            }
            if (b == null) return null;
            b = fit(b);
            //noinspection ResultOfMethodCallIgnored
            cached.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(cached)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return b;
        } catch (Exception e) {
            AppLog.w("tile logo for " + pkg + ": " + e.getMessage());
            return null;
        }
    }

    private static Bitmap decode(File f) {
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        return b == null ? null : fit(b);
    }

    private static Bitmap fit(Bitmap b) {
        int largest = Math.max(b.getWidth(), b.getHeight());
        if (largest <= 640) return b;
        float s = 640f / largest;
        return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
    }

    private static Bitmap icon(Context c, String pkg) {
        try {
            Drawable d = c.getPackageManager().getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 384, 384);
            d.draw(new Canvas(b));
            return b;
        } catch (Exception e) {
            return null;
        }
    }
}
