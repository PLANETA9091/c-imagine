import java.awt.Robot;
import java.awt.event.KeyEvent;

/**
 * Tiny X11 key-press tool for the visual harness (Robot-based — measured:
 * runners ship no ImageMagick xdotool). Usage: java -cp vtools Key F1
 * Presses and releases the named key on the focused window. F1 hides the
 * vanilla HUD (hideGui) — the camera-lock identity leg shoots the world
 * without GUI chrome on BOTH parity sides.
 *
 * <p>DIGIT KEYS (2026-11-04, the HELD leg): "1".."9" press the vanilla
 * hotbar-slot keys — the first-person held-item shot selects the slot the
 * held-item channel staged its subject in (hotbar.0 = the "1" key).
 */
public class Key {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: Key <F1|F5|ESC|SPACE|ENTER|1..9>");
            System.exit(2);
        }
        int code = switch (args[0].toUpperCase()) {
            case "F1" -> KeyEvent.VK_F1;
            case "F5" -> KeyEvent.VK_F5;
            case "ESC", "ESCAPE" -> KeyEvent.VK_ESCAPE;
            case "SPACE" -> KeyEvent.VK_SPACE;
            case "ENTER" -> KeyEvent.VK_ENTER;
            case "1" -> KeyEvent.VK_1;
            case "2" -> KeyEvent.VK_2;
            case "3" -> KeyEvent.VK_3;
            case "4" -> KeyEvent.VK_4;
            case "5" -> KeyEvent.VK_5;
            case "6" -> KeyEvent.VK_6;
            case "7" -> KeyEvent.VK_7;
            case "8" -> KeyEvent.VK_8;
            case "9" -> KeyEvent.VK_9;
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
