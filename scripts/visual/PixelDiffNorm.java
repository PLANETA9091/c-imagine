import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * LUMINANCE-NORMALIZED pixel diff for the camera-lock identity rig.
 *
 * MEASURED (parity runs 5-7 + artifact forensics): the 26.3 client renders
 * block faces a CONSTANT ~1.0703x brighter than the 1.21.1 client — same
 * texture pixels (the vanilla iron_block.png bytes are identical after the
 * 4-bit→8-bit recode; the mod carries its texture), same face ratios
 * (top/front = 0.727 both sides), same edge alignment. The factor is a
 * version-level render change (light/tonemap), NOT a channel difference —
 * the F2V channel delivers the SAME model through the SAME registry surface.
 *
 * The metric: ratio = mean(a)/mean(b) over the region; then a pixel counts
 * as differing when |a - b*ratio| exceeds tol on any channel. The datum is
 * therefore "structurally identical at tolerance tol, luminance factor r".
 *
 * Prints: "<normAE> <ratio>" (one line; parse with `read AE RATIO`).
 *
 * Usage: java PixelDiffNorm <a.png> <b.png> [x y w h tol]  (tol default 4)
 */
public final class PixelDiffNorm {
    public static void main(String[] args) throws Exception {
        BufferedImage a = ImageIO.read(new File(args[0]));
        BufferedImage b = ImageIO.read(new File(args[1]));
        if (a == null || b == null) {
            System.out.println("-1 -1");
            return;
        }
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            System.out.println("-1 -1");
            return;
        }
        int x0 = 0, y0 = 0, w = a.getWidth(), h = a.getHeight(), tol = 4;
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
            System.out.println("-1 -1");
            return;
        }
        long sa = 0, sb = 0;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                int ca = a.getRGB(x, y), cb = b.getRGB(x, y);
                sa += ((ca >> 16) & 0xFF) + ((ca >> 8) & 0xFF) + (ca & 0xFF);
                sb += ((cb >> 16) & 0xFF) + ((cb >> 8) & 0xFF) + (cb & 0xFF);
            }
        }
        double ratio = sa == 0 || sb == 0 ? 1.0 : (double) sa / (double) sb;
        long diff = 0;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                int ca = a.getRGB(x, y), cb = b.getRGB(x, y);
                // scale B (the 26.3 side, brighter by the measured factor)
                // DOWN to A's luminance: b*ratio ~= a when the renders are
                // structurally identical (scaling the darker side UP would
                // over-penalize dark pixels — measured 21682 vs 1531)
                double br = ((cb >> 16) & 0xFF) * ratio;
                double bg = ((cb >> 8) & 0xFF) * ratio;
                double bb = (cb & 0xFF) * ratio;
                double ar = ((ca >> 16) & 0xFF);
                double ag = ((ca >> 8) & 0xFF);
                double ab = (ca & 0xFF);
                if (Math.abs(ar - br) > tol || Math.abs(ag - bg) > tol
                        || Math.abs(ab - bb) > tol) {
                    diff++;
                }
            }
        }
        System.out.printf("%d %.5f%n", diff, ratio);
    }
}
