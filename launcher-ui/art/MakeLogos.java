import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.font.TextLayout;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Shelf logos drawn here instead of lifted from the app's TV art, when that art is too small or busy to look good:
 * app/assets/logos/<key>.png (HomeApps uses them first). Run: java art/MakeLogos.java
 */
public class MakeLogos {
    public static void main(String[] args) throws Exception {
        youtube(new File("app/assets/logos/youtube.png"));
    }

    /** YouTube's mark: the red rounded play button and the wordmark, in white for the dark tile. */
    static void youtube(File out) throws Exception {
        int h = 280;
        Font font = new Font("Helvetica Neue", Font.BOLD, 210);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        TextLayout word = new TextLayout("YouTube", font.deriveFont(java.util.Map.of(java.awt.font.TextAttribute.TRACKING, -0.035f)), pg.getFontRenderContext());
        pg.dispose();
        int iconW = 392, gap = 34;
        int w = iconW + gap + (int) Math.ceil(word.getAdvance()) + 8;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        int iconH = 276;
        double top = (h - iconH) / 2.0;
        g.setColor(new Color(0xFF0033));
        g.fill(new RoundRectangle2D.Double(0, top, iconW, iconH, 124, 124));
        Path2D play = new Path2D.Double();
        double cx = iconW / 2.0 + 10, cy = h / 2.0, s = 74;
        play.moveTo(cx - s * 0.85, cy - s);
        play.lineTo(cx + s * 1.05, cy);
        play.lineTo(cx - s * 0.85, cy + s);
        play.closePath();
        g.setColor(Color.WHITE);
        g.fill(play);
        float baseline = (float) (h / 2.0 + word.getAscent() * 0.36);
        g.setColor(Color.WHITE);
        word.draw(g, iconW + gap, baseline);
        g.dispose();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out + " " + w + "x" + h);
    }
}
