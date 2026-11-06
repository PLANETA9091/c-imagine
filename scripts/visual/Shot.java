import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import javax.imageio.ImageIO;

/**
 * Headless screenshot for the M5.3 visual harness: a Java-only replacement
 * for the missing ImageMagick `import` (measured: ubuntu-latest runners ship
 * IM7 without the legacy X tools). Requires a running X display (Xvfb) and
 * must run INSIDE the display's session.
 *
 * Usage: java Shot <out.png> [w h x y]   (defaults 1280x720@0,0)
 *        java Shot --m <manifest>        multi-shot: ONE JVM takes N captures
 *
 * The multi-shot manifest line format (per line, '#' comments and blanks
 * skipped): {@code <path> [w h x y]}. Every capture costs the same Robot
 * grab + PNG encode; only ONE JVM start is paid for the whole file. The
 * per-shot walk loops keep their timing logic in bash (the settle windows,
 * the 2s ANIM gap) — this mode covers the cold-path N-capture case where a
 * single JVM must serve several frames without ShotD's FIFO daemon.
 */
public final class Shot {
    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && args[0].equals("--m")) {
            multi(args[1]);
            return;
        }
        single(args.length >= 4
                ? new String[] { args[0], args[1], args[2], args[3], args[4] }
                : new String[] { args[0] });
    }

    /** Legacy single capture: unchanged CLI, unchanged framing. */
    private static void single(String[] a) throws Exception {
        int w = a.length >= 5 ? Integer.parseInt(a[1]) : 1280;
        int h = a.length >= 5 ? Integer.parseInt(a[2]) : 720;
        int x = a.length >= 5 ? Integer.parseInt(a[3]) : 0;
        int y = a.length >= 5 ? Integer.parseInt(a[4]) : 0;
        BufferedImage img = new Robot().createScreenCapture(new Rectangle(x, y, w, h));
        File out = new File(a[0]);
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("shot: " + out.getAbsolutePath() + " " + w + "x" + h);
    }

    /** --m manifest: one JVM, N captures from <path> [w h x y] lines. */
    private static void multi(String manifest) throws Exception {
        try (BufferedReader in = new BufferedReader(new FileReader(manifest))) {
            for (String line; (line = in.readLine()) != null; ) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                String[] f = t.split("\\s+");
                int w = f.length >= 5 ? Integer.parseInt(f[1]) : 1280;
                int h = f.length >= 5 ? Integer.parseInt(f[2]) : 720;
                int x = f.length >= 5 ? Integer.parseInt(f[3]) : 0;
                int y = f.length >= 5 ? Integer.parseInt(f[4]) : 0;
                BufferedImage img = new Robot().createScreenCapture(new Rectangle(x, y, w, h));
                File out = new File(f[0]);
                out.getAbsoluteFile().getParentFile().mkdirs();
                ImageIO.write(img, "png", out);
                System.out.println("shot: " + out.getAbsolutePath() + " " + w + "x" + h);
            }
        }
    }
}
