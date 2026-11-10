import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Real input for the HUD click test. Usage: Mouse left|right | Mouse move X Y
 * | Mouse click X Y | Mouse sweep DX DY | Mouse say TEXT | Mouse sneak MILLIS. Runs inside the display's own session.
 */
public final class Mouse {
    public static void main(String[] a) throws Throwable {
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
            case "sweep" -> relative(Integer.parseInt(a[1]), Integer.parseInt(a[2]));
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

    /**
     * A grabbed client reads relative motion, and Robot only makes absolute moves, so
     * this is the same XTest call a real relative mouse event takes.
     */
    private static void relative(int dx, int dy) throws Throwable {
        Linker linker = Linker.nativeLinker();
        Arena arena = Arena.global();
        SymbolLookup x11 = SymbolLookup.libraryLookup("libX11.so.6", arena);
        SymbolLookup xtst = SymbolLookup.libraryLookup("libXtst.so.6", arena);
        MethodHandle open = linker.downcallHandle(x11.find("XOpenDisplay").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MethodHandle flush = linker.downcallHandle(x11.find("XFlush").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        MethodHandle move = linker.downcallHandle(xtst.find("XTestFakeRelativeMotionEvent").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
        MemorySegment display = (MemorySegment) open.invoke(MemorySegment.NULL);
        for (int i = 0; i < 30; i++) {
            int ignored = (int) move.invoke(display, dx, dy, 0L);
            ignored = (int) flush.invoke(display);
            Thread.sleep(25);
        }
    }

    private static void tap(Robot r, int button) {
        r.mousePress(button);
        r.delay(90);
        r.mouseRelease(button);
    }
}
