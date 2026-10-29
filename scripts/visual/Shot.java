import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Headless screenshot for the M5.3 visual harness: a Java-only replacement
 * for the missing ImageMagick `import` (measured: ubuntu-latest runners ship
 * IM7 without the legacy X tools). Requires a running X display (Xvfb) and
 * must run INSIDE the display's session.
 *
 * Usage: java Shot <out.png> [w h x y]  (defaults 1280x720@0,0)
 */
public final class Shot {
    public static void main(String[] args) throws Exception {
        int w = args.length >= 4 ? Integer.parseInt(args[1]) : 1280;
        int h = args.length >= 4 ? Integer.parseInt(args[2]) : 720;
        int x = args.length >= 4 ? Integer.parseInt(args[3]) : 0;
        int y = args.length >= 5 ? Integer.parseInt(args[4]) : 0;
        Rectangle box = new Rectangle(x, y, w, h);
        BufferedImage img = new Robot().createScreenCapture(box);
        File out = new File(args[0]);
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("shot: " + out.getAbsolutePath() + " " + w + "x" + h);
    }
}
