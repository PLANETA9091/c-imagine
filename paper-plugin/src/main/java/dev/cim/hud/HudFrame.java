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

    /** One map row: {@code rgb[i]} is a cell colour, negative leaves the cell empty. */
    public HudFrame cells(int row, int[] rgb) {
        Key font = font(HudLayout.Anchor.MAP, row);
        TextComponent.Builder line = Component.text().font(font).shadowColor(ShadowColor.none());
        int cell = HudLayout.MAP_CELL;
        int advance = cell + 1;
        StringBuilder run = new StringBuilder();
        int i = 0;
        int pen = 0;
        while (i < rgb.length) {
            int color = rgb[i];
            int start = i;
            run.setLength(0);
            int target = start * cell;
            run.append(move(target - pen));
            pen = target;
            while (i < rgb.length && rgb[i] == color) {
                if (color >= 0) {
                    run.append(HudPack.CELL).append(move(cell - advance));
                    pen += cell;
                }
                i++;
            }
            if (color >= 0) {
                line.append(Component.text(run.toString()).color(TextColor.color(color)));
            } else {
                line.append(Component.text(run.toString()));
            }
        }
        line.append(Component.text(move(-pen)));
        parts.add(line.build());
        return this;
    }

    /** A 9-pixel marker centred on a map cell. */
    public HudFrame marker(int row, int col, char glyph) {
        int x = col * HudLayout.MAP_CELL + 1 - 4;
        Key font = font(HudLayout.Anchor.MAP, row);
        parts.add(Component.text(move(x) + glyph + move(-x - 10)).font(font)
                .color(TextColor.color(0xFFFFFF)).shadowColor(ShadowColor.none()));
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
