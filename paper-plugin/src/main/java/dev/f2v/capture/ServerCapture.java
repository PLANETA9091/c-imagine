package dev.f2v.capture;

/** The one check the scanner needs from F2V's capture class, copied as is. */
public final class ServerCapture {
    private ServerCapture() {
    }

    public static boolean looksLikeKeybind(String value) {
        if (value == null || value.length() < 5 || value.length() > 160) return false;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return (lower.startsWith("key.") || lower.startsWith("keybind."))
                && !lower.startsWith("key.categories.")
                && !lower.endsWith(".category")
                && !lower.contains(".category.")
                && !lower.startsWith("key.minecraft.");
    }
}
