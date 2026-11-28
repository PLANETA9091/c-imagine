package dev.f2v.vm.discovery;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memoized {@code toLowerCase(Locale.ROOT)} for the scanners' per-instruction name checks. ASM
 * names repeat heavily within a class (the same owner or descriptor string object is reused by
 * every call site), so a bounded memo removes most of the repeated lowercase scans. Results are
 * exactly those of String.toLowerCase(Locale.ROOT); only the work is shared.
 */
final class LowerCase {
    private static final int LIMIT = 1 << 16;
    private static final ConcurrentHashMap<String, String> MEMO = new ConcurrentHashMap<>();

    private LowerCase() {}

    static String of(String value) {
        if (value == null) return null;
        String cached = MEMO.get(value);
        if (cached != null) return cached;
        String lower = value.toLowerCase(Locale.ROOT);
        if (MEMO.size() >= LIMIT) MEMO.clear();
        MEMO.put(value, lower);
        return lower;
    }
}
