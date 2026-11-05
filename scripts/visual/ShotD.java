import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import javax.imageio.ImageIO;

/**
 * Shot, kept warm: one JVM serves every capture of a step. A cold Shot costs ~0.5 s of
 * JVM start, and a shard takes hundreds. Reads output paths from the FIFO argument;
 * each finished capture answers by creating {@code <path>.ok}.
 */
public final class ShotD {
    public static void main(String[] args) throws Exception {
        Robot robot = new Robot();
        Rectangle box = new Rectangle(0, 0, 1280, 720);
        while (true) {
            try (BufferedReader in = new BufferedReader(new FileReader(args[0]))) {
                for (String path; (path = in.readLine()) != null; ) {
                    BufferedImage img = robot.createScreenCapture(box);
                    File out = new File(path);
                    out.getAbsoluteFile().getParentFile().mkdirs();
                    File tmp = new File(path + ".tmp");
                    ImageIO.write(img, "png", tmp);
                    tmp.renameTo(out);
                    new File(path + ".ok").createNewFile();
                }
            }
        }
    }
}
