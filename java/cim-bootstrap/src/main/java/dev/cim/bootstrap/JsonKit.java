package dev.cim.bootstrap;

/**
 * Tiny hand-rolled JSON readers/writers so the bootstrap stays
 * dependency-free (JDK only). Not a general JSON parser — just enough for
 * the flat option/status objects crossing the native boundary.
 */
final class JsonKit {

    private JsonKit() {
    }

    /** Extract a top-level string field from a flat JSON object. */
    static String str(String json, String key) {
        if (json == null) {
            return null;
        }
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) {
            return null;
        }
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) {
            return null;
        }
        int open = json.indexOf('"', colon);
        if (open < 0) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = open + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                out.append(switch (n) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    default -> n;
                });
                continue;
            }
            if (c == '"') {
                return out.toString();
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Escape a value for embedding in a JSON string. */
    static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
