package android.graphics;
import java.awt.image.BufferedImage;
public final class Bitmap {
    public enum Config { ARGB_8888, RGB_565 }
    final BufferedImage image;
    Bitmap(BufferedImage image) { this.image = image; }
    public int getByteCount() { return image.getWidth() * image.getHeight() * 4; }
    public int getPixel(int x, int y) { return image.getRGB(x, y); }
}
