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
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'u' -> {
                        // unicode escape: four hex digits, per RFC 8259. Previously
                        // this decoded as the literal 'u' + digits, silently
                        // corrupting any escaped value.
                        if (i + 4 < json.length()) {
                            try {
                                out.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                out.append(n); // keep the raw escape on bad input
                            }
                        } else {
                            out.append(n);
                        }
                    }
                    default -> out.append(n);
                }
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
