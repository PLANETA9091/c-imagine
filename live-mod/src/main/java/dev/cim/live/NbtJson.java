package dev.cim.live;

import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * An NBT tree as JSON text, for {@code inspect-be}: compounds are objects, lists and typed arrays are
 * arrays, strings are strings, numbers are JSON numbers (the SNBT type suffixes are dropped, so the
 * truth side's SNBT converts to the same JSON). Non-finite floats become strings ("NaN", "Infinity").
 */
final class NbtJson {
    private NbtJson() {
    }

    static String of(Tag tag) {
        StringBuilder out = new StringBuilder();
        write(out, tag);
        return out.toString();
    }

    private static void write(StringBuilder out, Tag tag) {
        if (tag == null) {
            out.append("null");
        } else if (tag instanceof CompoundTag c) {
            out.append('{');
            boolean first = true;
            for (String key : new java.util.TreeSet<>(c.getAllKeys())) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(out, key);
                out.append(':');
                write(out, c.get(key));
            }
            out.append('}');
        } else if (tag instanceof CollectionTag<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(out, list.get(i));
            }
            out.append(']');
        } else if (tag instanceof StringTag) {
            string(out, tag.getAsString());
        } else if (tag instanceof NumericTag n) {
            double d = n.getAsDouble();
            if (tag.getId() == 5 || tag.getId() == 6) { // float, double: JSON has no NaN or infinity
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    string(out, Double.isNaN(d) ? "NaN" : d > 0 ? "Infinity" : "-Infinity");
                    return;
                }
                out.append(tag.getId() == 5 ? Float.toString(n.getAsFloat()) : Double.toString(d));
            } else {
                out.append(n.getAsLong());
            }
        } else {
            string(out, tag.toString());
        }
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
