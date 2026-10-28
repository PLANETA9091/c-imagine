import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Pixel diff with a PER-CHANNEL TOLERANCE for the camera-lock identity rig
 * (camera-lock-26-3.md §3): the two parity stacks render the SAME textures
 * under the SAME light, but the lightmap drifts by ±2/255 between the
 * 1.21.1 and 26.3 clients (measured on the identical grass_block_top.png:
 * truth (68,88,42) vs cim (66,86,41)) — a strict exact-match AE counts
 * EVERY pixel as differing even when the renders are visually identical.
 * A pixel counts as EQUAL when |dR|,|dG|,|dB| are all <= tol.
 *
 * Usage: java PixelDiffTol <a.png> <b.png> [x y w h tol]  (tol default 0)
 */
public final class PixelDiffTol {
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
        int x0 = 0, y0 = 0, w = a.getWidth(), h = a.getHeight(), tol = 0;
        if (args.length >= 6) {
            x0 = Integer.parseInt(args[2]);
            y0 = Integer.parseInt(args[3]);
            w = Integer.parseInt(args[4]);
            h = Integer.parseInt(args[5]);
        }
        if (args.length >= 7) {
            tol = Integer.parseInt(args[6]);
        }
        if (x0 < 0 || y0 < 0 || w <= 0 || h <= 0
                || x0 + w > a.getWidth() || y0 + h > a.getHeight()) {
            System.out.println("bad crop " + x0 + "," + y0 + " " + w + "x" + h);
            return;
        }
        long diff = 0;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                int ca = a.getRGB(x, y), cb = b.getRGB(x, y);
                int dr = Math.abs(((ca >> 16) & 0xFF) - ((cb >> 16) & 0xFF));
                int dg = Math.abs(((ca >> 8) & 0xFF) - ((cb >> 8) & 0xFF));
                int db = Math.abs((ca & 0xFF) - (cb & 0xFF));
                if (dr > tol || dg > tol || db > tol) {
                    diff++;
                }
            }
        }
        System.out.println(diff);
    }
}
