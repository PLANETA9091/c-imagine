import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Real input for the HUD click test. Usage: Mouse left|right | Mouse move X Y
 * | Mouse click X Y | Mouse say TEXT | Mouse sneak MILLIS. Runs inside the display's own session.
 */
public final class Mouse {
    public static void main(String[] a) throws Exception {
        Robot r = new Robot();
        r.setAutoDelay(60);
        switch (a[0]) {
            case "left" -> tap(r, InputEvent.BUTTON1_DOWN_MASK);
            case "right" -> tap(r, InputEvent.BUTTON3_DOWN_MASK);
            case "move" -> r.mouseMove(Integer.parseInt(a[1]), Integer.parseInt(a[2]));
            case "click" -> {
                r.mouseMove(Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                Thread.sleep(300);
                tap(r, InputEvent.BUTTON1_DOWN_MASK);
            }
            case "say" -> {
                r.keyPress(KeyEvent.VK_T);
                r.keyRelease(KeyEvent.VK_T);
                Thread.sleep(900);
                for (char c : a[1].toUpperCase().toCharArray()) {
                    int code = c == '/' ? KeyEvent.VK_SLASH : c == ' ' ? KeyEvent.VK_SPACE : c;
                    r.keyPress(code);
                    r.keyRelease(code);
                }
                Thread.sleep(300);
                r.keyPress(KeyEvent.VK_ENTER);
                r.keyRelease(KeyEvent.VK_ENTER);
            }
            case "walk" -> {
                r.keyPress(KeyEvent.VK_W);
                Thread.sleep(Long.parseLong(a[1]));
                r.keyRelease(KeyEvent.VK_W);
            }
            case "sneak" -> {
                r.keyPress(KeyEvent.VK_SHIFT);
                Thread.sleep(Long.parseLong(a[1]));
                r.keyRelease(KeyEvent.VK_SHIFT);
            }
            default -> throw new IllegalArgumentException(a[0]);
        }
        Thread.sleep(150);
    }

    private static void tap(Robot r, int button) {
        r.mousePress(button);
        r.delay(90);
        r.mouseRelease(button);
    }
}
