package dev.cim.hud;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * What the automatic menu walk recorded: for each menu, its class, the blocks
 * and items that open it, and where its slots are. Slot positions are server
 * state, so the walk reads them from the real open menu instead of guessing.
 */
public final class GuiCaptures {

    /**
     * {@code index}: the slot in its container; {@code menuIndex}: the slot in the menu's list,
     * the number a click names (-1 when the row does not say).
     */
    public record Slot(int x, int y, boolean player, int index, int menuIndex) {

        public Slot(int x, int y, boolean player, int index) {
            this(x, y, player, index, -1);
        }
    }

    public record Capture(String menuId, String menuClass, String title, List<String> openers, List<Slot> slots) {
    }

    private GuiCaptures() {
    }

    /** Reads every {@code gallery-gui-*.tsv}; a missing directory is no captures. */
    public static List<Capture> read(Path scenes) throws IOException {
        List<Capture> out = new ArrayList<>();
        if (!Files.isDirectory(scenes)) {
            return out;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(scenes)) {
            files = s.filter(p -> p.getFileName().toString().matches("gallery-gui-.*\\.tsv")).sorted().toList();
        }
        for (Path file : files) {
            Capture c = parse(Files.readAllLines(file, StandardCharsets.UTF_8));
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    static Capture parse(List<String> lines) {
        String menu = null;
        String menuClass = null;
        String title = null;
        List<String> openers = new ArrayList<>();
        List<Slot> slots = new ArrayList<>();
        for (String line : lines) {
            String[] p = line.split("\t", -1);
            if (p.length < 6) {
                continue;
            }
            if (p[0].equals("cell") && !p[1].equals("x")) {
                menu = p[4];
                menuClass = p.length > 6 ? p[6].replace('.', '/') : null;
                if (p[5].startsWith("opened-by:")) {
                    openers.add(p[5].substring("opened-by:".length()));
                }
            } else if (p[0].equals("title")) {
                title = p[5];
            } else if (p[0].equals("opener") && !openers.contains(p[5])) {
                openers.add(p[5]);
            } else if (p[0].equals("slot")) {
                slots.add(new Slot(Integer.parseInt(p[2]), Integer.parseInt(p[3]), p[5].equals("player"),
                        p.length > 6 ? Integer.parseInt(p[6]) : -1, menuIndexOf(p[1])));
            }
        }
        return menu == null ? null : new Capture(menu, menuClass, title, openers, slots);
    }

    private static int menuIndexOf(String field) {
        try {
            return Integer.parseInt(field);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Menu id to capture. An id is unique; a class is often shared by a whole mod. */
    public static Map<String, Capture> byId(List<Capture> captures) {
        Map<String, Capture> out = new HashMap<>();
        for (Capture c : captures) {
            out.put(c.menuId(), c);
        }
        return out;
    }
}
