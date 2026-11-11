import java.awt.Robot;
import java.awt.event.KeyEvent;

/**
 * Tiny X11 key-press tool for the visual harness (Robot-based — measured:
 * runners ship no ImageMagick xdotool). Usage: java -cp vtools Key F1
 * Presses and releases the named keys on the focused window, in argv order
 * (one JVM start serves the whole sequence). F1 hides the vanilla HUD
 * (hideGui) — the camera-lock identity leg shoots the world without GUI
 * chrome on BOTH parity sides.
 *
 * <p>DIGIT KEYS (2026-11-04, the HELD leg): "1".."9" press the vanilla
 * hotbar-slot keys — the first-person held-item shot selects the slot the
 * held-item channel staged its subject in (hotbar.0 = the "1" key).
 *
 * <p>MULTI-KEY (the shard overhead removal): {@code Key F1 1} / {@code Key
 * F1 E} press the whole sequence in ONE JVM (~60 ms apart via autoDelay)
 * where the walk used to pay a cold JVM start per key. Only adjacent
 * presses are merged in the walk — every sequence that has a sleep, an RCON
 * call or a shot between two keys keeps its separate invocations.
 *
 * <p>MOUSEOFF (the menus lane, 477489e6): {@code Key MOUSEOFF} parks the
 * mouse at (1,1) instead of pressing a key — an open screen shows a free
 * cursor, and a cursor left hovering a widget leaks its hover state into
 * the truth shot.
 */
public class Key {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: Key <F1|F5|ESC|SPACE|ENTER|E|1..9> [...more keys] | MOUSEOFF");
            System.exit(2);
        }
        // Parks the mouse off every menu: an open screen shows a free cursor.
        if (args[0].equalsIgnoreCase("MOUSEOFF")) {
            new Robot().mouseMove(1, 1);
            Thread.sleep(150);
            return;
        }
        Robot r = new Robot();
        r.setAutoDelay(60);
        for (String name : args) {
            int code = switch (name.toUpperCase()) {
                case "F1" -> KeyEvent.VK_F1;
                case "F5" -> KeyEvent.VK_F5;
                case "ESC", "ESCAPE" -> KeyEvent.VK_ESCAPE;
                case "SPACE" -> KeyEvent.VK_SPACE;
                case "ENTER" -> KeyEvent.VK_ENTER;
                case "E" -> KeyEvent.VK_E;   // the inventory-view leg (round 2): open/close the player inventory screen
                case "1" -> KeyEvent.VK_1;
                case "2" -> KeyEvent.VK_2;
                case "3" -> KeyEvent.VK_3;
                case "4" -> KeyEvent.VK_4;
                case "5" -> KeyEvent.VK_5;
                case "6" -> KeyEvent.VK_6;
                case "7" -> KeyEvent.VK_7;
                case "8" -> KeyEvent.VK_8;
                case "9" -> KeyEvent.VK_9;
                default -> -1;
            };
            if (code < 0) {
                System.err.println("unknown key: " + name);
                System.exit(2);
            }
            r.keyPress(code);
            r.keyRelease(code);
            System.out.println("key: " + name + " pressed");
        }
    }
}
