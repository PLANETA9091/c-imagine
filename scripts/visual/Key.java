import java.awt.Robot;
import java.awt.event.KeyEvent;

/**
 * Tiny X11 key-press tool for the visual harness (Robot-based — measured:
 * runners ship no ImageMagick xdotool). Usage: java -cp vtools Key F5
 * Presses and releases the named key on the focused window.
 */
public class Key {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: Key <F5|ESC|SPACE|ENTER>");
            System.exit(2);
        }
        int code = switch (args[0].toUpperCase()) {
            case "F5" -> KeyEvent.VK_F5;
            case "ESC", "ESCAPE" -> KeyEvent.VK_ESCAPE;
            case "SPACE" -> KeyEvent.VK_SPACE;
            case "ENTER" -> KeyEvent.VK_ENTER;
            default -> {
                System.err.println("unknown key: " + args[0]);
                yield -1;
            }
        };
        if (code < 0) {
            System.exit(2);
        }
        Robot r = new Robot();
        r.setAutoDelay(60);
        r.keyPress(code);
        r.keyRelease(code);
        System.out.println("key: " + args[0] + " pressed");
    }
}
