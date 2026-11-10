package dev.cim.hud;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * Builds one boss bar name holding every element of a player's HUD.
 *
 * <p>Each run ends with the pen back where it started, so the client's centring
 * of the name never moves anything and elements cannot push each other.
 */
public final class HudFrame {

    private final List<Component> parts = new ArrayList<>();

    /** Text at a row of an anchor, aligned to that anchor. */
    public HudFrame text(HudLayout.Anchor anchor, int row, String raw, TextColor color) {
        String s = HudText.sanitize(raw);
        int w = HudText.width(s);
        int lead = anchor.right() ? -w : anchor.centered() ? -(w / 2) : 0;
        Key font = font(anchor, row);
        parts.add(Component.text(move(lead) + s + move(-(lead + w))).font(font).color(color));
        return this;
    }

    /**
     * Glyphs from the free font at a pixel, measured from the screen centre.
     * {@code advance} is what they move the pen, so the run can return it.
     */
    public HudFrame free(int x, int y, String glyphs, int advance) {
        parts.add(Component.text(glyphs + move(-advance))
                .font(font(HudLayout.freeId()))
                .color(TextColor.color(HudLayout.freeColor(x, y)))
                .shadowColor(ShadowColor.none()));
        return this;
    }

    /** ASCII at a pixel. It draws white: the colour channel is holding the position. */
    public HudFrame freeText(int x, int y, String raw) {
        String s = HudText.sanitize(raw);
        return free(x, y, s, HudText.width(s));
    }

    /** ASCII the way vanilla draws white text with its drop shadow: the shadow a pixel down and right. */
    public HudFrame freeShadowed(int x, int y, String raw) {
        String s = HudText.sanitize(raw);
        int w = HudText.width(s);
        parts.add(Component.text(s + move(-w)).font(font(HudLayout.freeShadowId()))
                .color(TextColor.color(HudLayout.freeColor(x + 1, y + 1))).shadowColor(ShadowColor.none()));
        parts.add(Component.text(s + move(-w)).font(font(HudLayout.freeTextId()))
                .color(TextColor.color(HudLayout.freeColor(x, y))).shadowColor(ShadowColor.none()));
        return this;
    }

    /** ASCII at a pixel in label grey, for text on a light panel. */
    public HudFrame freeLabel(int x, int y, String raw) {
        String s = HudText.sanitize(raw);
        int w = HudText.width(s);
        parts.add(Component.text(s + move(-w)).font(font(HudLayout.freeDarkId()))
                .color(TextColor.color(HudLayout.freeColor(x, y))).shadowColor(ShadowColor.none()));
        return this;
    }

    /** A prepared run in label grey, with its own pen moves. */
    public HudFrame freeRun(int x, int y, String text, int advance) {
        parts.add(Component.text(text + move(-advance)).font(font(HudLayout.freeDarkId()))
                .color(TextColor.color(HudLayout.freeColor(x, y))).shadowColor(ShadowColor.none()));
        return this;
    }

    /** Takes over another frame's runs. */
    public HudFrame append(HudFrame other) {
        parts.addAll(other.parts);
        return this;
    }

    public Component build() {
        return Component.textOfChildren(parts.toArray(Component[]::new));
    }

    public boolean isEmpty() {
        return parts.isEmpty();
    }

    private static Key font(HudLayout.Anchor anchor, int row) {
        return font(HudLayout.id(anchor, row));
    }

    private static Key font(int id) {
        return Key.key(HudPack.NS, HudLayout.fontName(id));
    }

    /** Glyphs that move the pen by {@code distance} pixels. */
    public static String move(int distance) {
        int d = Math.abs(distance);
        if (d >= (1 << HudPack.SPACE_BITS)) {
            throw new IllegalArgumentException("pen move too far: " + distance);
        }
        char base = distance < 0 ? HudPack.BACK_BASE : HudPack.SPACE_BASE;
        StringBuilder out = new StringBuilder(4);
        for (int bit = 0; d != 0; bit++, d >>= 1) {
            if ((d & 1) != 0) {
                out.append((char) (base + bit));
            }
        }
        return out.toString();
    }
}
