package android.graphics;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
/** Real JVM decoding, not a claim about Android codecs or memory formats. */
public final class BitmapFactory {
    public static final class Options { public Bitmap.Config inPreferredConfig; }
    private static Bitmap read(InputStream in) {
        try (InputStream stream = in) {
            BufferedImage image = ImageIO.read(stream);
            return image == null ? null : new Bitmap(image);
        } catch (IOException invalid) { return null; }
    }
    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Options options) {
        return read(new ByteArrayInputStream(data, offset, length));
    }
    public static Bitmap decodeFile(String file, Options options) {
        try { return read(new FileInputStream(file)); }
        catch (IOException missing) { return null; }
    }
}
