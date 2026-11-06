package dev.cim.hud;

/**
 * Advance widths of the vanilla ASCII font, in GUI pixels.
 *
 * <p>The encoder cancels every run's advance, so a wrong width shifts whatever
 * is drawn after it. These come from measuring {@code font/ascii.png} in the
 * 26.3 client jar; {@code HudPack --selftest <client.jar>} measures again.
 */
public final class HudText {

    /** Width of codepoint {@code 32 + i} is {@code WIDTHS.charAt(i) - '0'}. */
    static final String WIDTHS =
            "42466662444626266666666666225656766666666466666666666666666464663666665662653666666646666664247";

    private HudText() {
    }

    public static boolean encodable(char c) {
        return c >= 32 && c < 127;
    }

    public static int width(char c) {
        return encodable(c) ? WIDTHS.charAt(c - 32) - '0' : width('?');
    }

    public static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += width(s.charAt(i));
        }
        return w;
    }

    /** The HUD fonts carry ASCII only; anything else prints as {@code ?}. */
    public static String sanitize(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            out.append(encodable(s.charAt(i)) ? s.charAt(i) : '?');
        }
        return out.toString();
    }
}
