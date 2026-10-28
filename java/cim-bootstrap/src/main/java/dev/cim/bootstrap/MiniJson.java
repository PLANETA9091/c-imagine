package dev.cim.bootstrap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser (JDK-only, dependency-free by repo rule).
 *
 * Produces {@code Map<String,Object>} / {@code List<Object>} /
 * {@code String} / {@code Double} / {@code Long} / {@code Boolean} / null.
 * Object key order is preserved (LinkedHashMap) — the layer manifest is
 * order-significant.
 *
 * Not a general-purpose JSON toolkit: no streaming, no duplicate-key
 * detection (last wins), numbers become Long when integral, Double otherwise.
 * Sized for the layer manifest (~hundreds of KB worst case) — recursion depth
 * is capped defensively.
 */
public final class MiniJson {

    private static final int MAX_DEPTH = 64;

    private final String src;
    private int pos;

    private MiniJson(String src) {
        this.src = src;
    }

    /** Parse a complete JSON document. Throws IllegalArgumentException on malformed input. */
    public static Object parse(String json) {
        MiniJson p = new MiniJson(json);
        p.ws();
        Object value = p.value(0);
        p.ws();
        if (p.pos != p.src.length()) {
            throw p.err("trailing content at " + p.pos);
        }
        return value;
    }

    /** Convenience: parse and cast the document root to a map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object root = parse(json);
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("expected JSON object at root");
        }
        return (Map<String, Object>) root;
    }

    /** Serialize a {@code Map<String,?>} (string keys) back to compact JSON. */
    public static String json(Map<String, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(e.getKey())).append(':').append(valueJson(e.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String valueJson(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Boolean || v instanceof Number) {
            return String.valueOf(v);
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(String.valueOf(e.getKey()))).append(':')
                        .append(valueJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (v instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(valueJson(item));
            }
            return sb.append(']').toString();
        }
        return quote(String.valueOf(v));
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw err("nesting too deep");
        }
        char c = peek();
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return number();
        }
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++; // '{'
        ws();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            ws();
            if (peek() != '"') {
                throw err("expected object key at " + pos);
            }
            String key = string();
            ws();
            if (peek() != ':') {
                throw err("expected ':' at " + pos);
            }
            pos++;
            ws();
            out.put(key, value(depth + 1));
            ws();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return out;
            }
            throw err("expected ',' or '}' at " + pos);
        }
    }

    private List<Object> array(int depth) {
        List<Object> out = new ArrayList<>();
        pos++; // '['
        ws();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            ws();
            out.add(value(depth + 1));
            ws();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return out;
            }
            throw err("expected ',' or ']' at " + pos);
        }
    }

    private String string() {
        pos++; // '"'
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw err("unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= src.length()) {
                    throw err("bad escape at end");
                }
                char e = src.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) {
                            throw err("bad unicode escape at " + pos);
                        }
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw err("bad escape '\\" + e + "'");
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Object number() {
        int start = pos;
        while (pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        if (pos == start) {
            throw err("unexpected character '" + peek() + "' at " + pos);
        }
        String num = src.substring(start, pos);
        if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) {
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException ignored) {
                // fall through to Double
            }
        }
        return Double.parseDouble(num);
    }

    private void expect(String word) {
        if (!src.startsWith(word, pos)) {
            throw err("expected '" + word + "' at " + pos);
        }
        pos += word.length();
    }

    private void ws() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw err("unexpected end of input");
        }
        return src.charAt(pos);
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("MiniJson: " + msg);
    }
}
