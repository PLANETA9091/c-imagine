import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Java-only pixel diff for the M5.3 visual harness (replacement for
 * ImageMagick `compare -metric AE`): prints the count of differing pixels
 * between two PNGs. Exit code 0 always — the caller parses the number.
 *
 * Usage: java PixelDiff <a.png> <b.png> [x y w h]
 *   with the optional crop rect the diff is computed INSIDE the rect only
 *   (the parity-diff job crops the hotbar region out of two full-frame
 *   shots from DIFFERENT client stacks — identical vanilla GUI chrome,
 *   only the item pixels are allowed to differ).
 */
public final class PixelDiff {
    public static void main(String[] args) throws Exception {
        BufferedImage a = ImageIO.read(new File(args[0]));
        BufferedImage b = ImageIO.read(new File(args[1]));
        if (a == null || b == null) {
            System.out.println("unreadable");
            return;
        }
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            System.out.println("differ (dims " + a.getWidth() + "x" + a.getHeight()
                    + " vs " + b.getWidth() + "x" + b.getHeight() + ")");
            return;
        }
        int x0 = 0, y0 = 0, w = a.getWidth(), h = a.getHeight();
        if (args.length >= 6) {
            x0 = Integer.parseInt(args[2]);
            y0 = Integer.parseInt(args[3]);
            w = Integer.parseInt(args[4]);
            h = Integer.parseInt(args[5]);
            if (x0 < 0 || y0 < 0 || w <= 0 || h <= 0
                    || x0 + w > a.getWidth() || y0 + h > a.getHeight()) {
                System.out.println("bad crop " + x0 + "," + y0 + " " + w + "x" + h);
                return;
            }
        }
        long diff = 0;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    diff++;
                }
            }
        }
        System.out.println(diff);
    }
}
