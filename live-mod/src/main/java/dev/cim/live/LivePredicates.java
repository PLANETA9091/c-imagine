package dev.cim.live;

import net.minecraft.world.inventory.AbstractContainerMenu;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * A widget's live state, as F2V's scanner proves it from the screen's tick bytecode: a
 * boolean expression over the server's own objects (a block entity method, a menu field),
 * written in the scanner's form, e.g.
 * {@code AND[NOT[SOURCE(M#owner#getAutoMode#()Z)], SOURCE(M#owner#hasRecipe#()Z)]}.
 * It is evaluated here against the real objects of the open menu: the menu itself, every
 * object its fields hold, and the block entity the session opened.
 */
final class LivePredicates {

    private LivePredicates() {
    }

    /** True/false, or null when a source cannot be found on this menu's objects. */
    static Boolean eval(String expr, AbstractContainerMenu menu, Object blockEntity) {
        try {
            return new Parser(expr.trim(), menu, blockEntity).expr();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class Parser {
        private final String s;
        private final AbstractContainerMenu menu;
        private final Object blockEntity;
        private int i;

        Parser(String s, AbstractContainerMenu menu, Object blockEntity) {
            this.s = s;
            this.menu = menu;
            this.blockEntity = blockEntity;
        }

        Boolean expr() {
            skip();
            if (s.startsWith("true", i)) {
                i += 4;
                return true;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return false;
            }
            int open = indexOfAny(i);
            String op = s.substring(i, open);
            char bracket = s.charAt(open);
            i = open + 1;
            if (bracket == '[') {
                List<Boolean> terms = new ArrayList<>();
                while (true) {
                    skip();
                    if (s.charAt(i) == ']') {
                        i++;
                        break;
                    }
                    terms.add(expr());
                    skip();
                    if (s.charAt(i) == ',') {
                        i++;
                    }
                }
                return switch (op) {
                    case "NOT" -> terms.get(0) == null ? null : !terms.get(0);
                    case "AND" -> terms.contains(null) ? null : !terms.contains(false);
                    case "OR" -> terms.contains(true) ? Boolean.TRUE : terms.contains(null) ? null : false;
                    default -> throw new IllegalArgumentException(op);
                };
            }
            int close = s.indexOf(')', i);
            String inside = s.substring(i, close);
            i = close + 1;
            String[] parts = inside.split(",");
            Object value = source(parts[0]);
            return switch (op) {
                case "SOURCE" -> value instanceof Boolean b ? b : null;
                case "NOT_NULL" -> value != null;
                case "STATE_EQUALS" -> value == null ? null : String.valueOf(value).equals(constantName(parts[1]));
                case "NUMERIC_COMPARE" -> value instanceof Number n ? compare(n.doubleValue(), parts[1],
                        Double.parseDouble(parts[2])) : null;
                default -> throw new IllegalArgumentException(op);
            };
        }

        private int indexOfAny(int from) {
            for (int k = from; k < s.length(); k++) {
                char c = s.charAt(k);
                if (c == '[' || c == '(') {
                    return k;
                }
            }
            throw new IllegalArgumentException("no operand in " + s);
        }

        private void skip() {
            while (i < s.length() && s.charAt(i) == ' ') {
                i++;
            }
        }

        /** {@code M#owner#name#desc} (a method) or {@code F#owner#name#desc} (a field). */
        private Object source(String spec) {
            String[] p = spec.split("#");
            String owner = p[1].replace('/', '.');
            Object target = targetOf(owner);
            if (target == null) {
                return null;
            }
            try {
                if (p[0].equals("M")) {
                    Method m = find(target.getClass(), p[2]);
                    m.setAccessible(true);
                    return m.invoke(target);
                }
                Field f = field(target.getClass(), p[2]);
                f.setAccessible(true);
                return f.get(target);
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }

        /** The object of the owner's class among the menu, its fields, and the opened block entity. */
        private Object targetOf(String owner) {
            List<Object> candidates = new ArrayList<>();
            candidates.add(menu);
            candidates.add(blockEntity);
            for (Class<?> c = menu.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!f.getType().isPrimitive() && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        try {
                            f.setAccessible(true);
                            candidates.add(f.get(menu));
                        } catch (RuntimeException | IllegalAccessException ignored) {
                            // not readable: not a candidate
                        }
                    }
                }
            }
            for (Object o : candidates) {
                for (Class<?> c = o == null ? null : o.getClass(); c != null; c = c.getSuperclass()) {
                    // The scanner names a game class as the jar has it; the layer's carries a relocation prefix.
                    if (c.getName().equals(owner) || c.getName().replaceFirst("^cim\\.shadow\\.[a-z0-9]+\\.", "").equals(owner)) {
                        return o;
                    }
                }
            }
            return null;
        }

        private static Method find(Class<?> c, String name) throws NoSuchMethodException {
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == 0) {
                        return m;
                    }
                }
            }
            throw new NoSuchMethodException(name);
        }

        private static Field field(Class<?> c, String name) throws NoSuchFieldException {
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                try {
                    return k.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                    // up
                }
            }
            throw new NoSuchFieldException(name);
        }

        private static String constantName(String expected) {
            int dot = expected.lastIndexOf('.');
            int hash = expected.lastIndexOf('#');
            return expected.substring(Math.max(dot, hash) + 1);
        }

        private static boolean compare(double v, String op, double t) {
            return switch (op) {
                case "GT" -> v > t;
                case "GE" -> v >= t;
                case "LT" -> v < t;
                case "LE" -> v <= t;
                case "EQ" -> v == t;
                default -> v != t;
            };
        }
    }
}
