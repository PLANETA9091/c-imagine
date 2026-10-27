import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Java-only pixel diff for the M5.3 visual harness (replacement for
 * ImageMagick `compare -metric AE`): prints the count of differing pixels
 * between two PNGs. Exit code 0 always — the caller parses the number.
 *
 * Usage: java PixelDiff <a.png> <b.png>
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
        long diff = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    diff++;
                }
            }
        }
        System.out.println(diff);
    }
}
