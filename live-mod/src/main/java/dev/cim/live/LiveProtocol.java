package dev.cim.live;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import java.util.function.Function;

/**
 * Paper's way in. The plugin and the layer share no class, so the session is driven with
 * text: one JVM-wide {@code Function<String, String>} under the system property key
 * {@value #KEY}, a command in and the menu's state out. Item stacks travel whole, as the
 * JSON of {@link ItemStack#OPTIONAL_CODEC}: a filter's settings and a backpack's contents
 * are the mod's own data components and come back to Paper unchanged.
 *
 * <pre>
 * in:   open-item  TAB uuid TAB name        then one line per inventory slot: inv TAB i TAB stack
 *       click      TAB uuid TAB slot TAB button TAB PICKUP|QUICK_MOVE|...
 *       close      TAB uuid
 * out:  menu TAB type TAB class   (or: none)
 *       slot TAB n TAB menuIndex TAB player|menu TAB containerSlot TAB stack   (active slots, in order)
 *       carried TAB stack
 *       inv TAB i TAB stack
 *       drop TAB stack            (what the mod's code dropped from the player)
 * </pre>
 */
public final class LiveProtocol {

    public static final String KEY = "cim.live";

    private LiveProtocol() {
    }

    /** Publishes the entry point; called once the registries are complete. */
    static void publish() {
        Function<String, String> call = LiveProtocol::call;
        System.getProperties().put(KEY, call);
    }

    static synchronized String call(String request) {
        try {
            String[] lines = request.split("\n");
            String[] head = lines[0].split("\t");
            UUID id = UUID.fromString(head[1]);
            return switch (head[0]) {
                case "open-item" -> {
                    SessionServerPlayer player = LiveMenus.session(id, head[2]);
                    player.closeContainer();
                    readInventory(player, lines);
                    yield state(player, LiveMenus.useHeld(player, player.getMainHandItem()));
                }
                case "click" -> {
                    SessionServerPlayer player = LiveMenus.session(id, "cimlive");
                    LiveMenus.click(player, Integer.parseInt(head[2]), Integer.parseInt(head[3]),
                            ClickType.valueOf(head[4]));
                    yield state(player, player.opened());
                }
                case "button" -> {
                    // button TAB uuid TAB payloadClass TAB constructorDesc TAB arg...
                    SessionServerPlayer player = LiveMenus.session(id, "cimlive");
                    java.util.List<String> args = new java.util.ArrayList<>(java.util.Arrays.asList(head).subList(4, head.length));
                    String pressed = LivePayloads.press(player, head[2], head[3], args);
                    yield "pressed\t" + pressed + "\n" + state(player, player.opened());
                }
                case "close" -> {
                    SessionServerPlayer player = LiveMenus.session(id, "cimlive");
                    player.closeContainer();
                    String out = state(player, null);
                    LiveMenus.end(id);
                    yield out;
                }
                default -> "error\tunknown command " + head[0];
            };
        } catch (Throwable t) {
            return "error\t" + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private static void readInventory(SessionServerPlayer player, String[] lines) {
        player.getInventory().clearContent();
        for (int i = 1; i < lines.length; i++) {
            String[] p = lines[i].split("\t", 3);
            if (p.length == 3 && p[0].equals("inv")) {
                player.getInventory().setItem(Integer.parseInt(p[1]), decode(p[2]));
            }
        }
    }

    private static String state(SessionServerPlayer player, AbstractContainerMenu menu) {
        StringBuilder out = new StringBuilder();
        if (menu == null) {
            out.append("none\n");
        } else {
            String type;
            try {
                type = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(menu.getType()));
            } catch (UnsupportedOperationException none) {
                type = "null";
            }
            out.append("menu\t").append(type).append('\t').append(menu.getClass().getName()).append('\n');
            int n = 0;
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                if (!slot.isActive()) {
                    continue;   // the screen does not draw it, the capture does not list it
                }
                out.append("slot\t").append(n++).append('\t').append(i).append('\t')
                        .append(slot.container == player.getInventory() ? "player" : "menu").append('\t')
                        .append(slot.getContainerSlot()).append('\t').append(encode(slot.getItem())).append('\n');
            }
            out.append("carried\t").append(encode(menu.getCarried())).append('\n');
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                out.append("inv\t").append(i).append('\t').append(encode(stack)).append('\n');
            }
        }
        for (ItemStack stack : player.takeDropped()) {
            out.append("drop\t").append(encode(stack)).append('\n');
        }
        return out.toString();
    }

    static String encode(ItemStack stack) {
        if (stack.isEmpty()) {
            return "-";
        }
        RegistryOps<JsonElement> ops = LiveMenus.level().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        return ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).getOrThrow().toString();
    }

    static ItemStack decode(String json) {
        if (json.equals("-")) {
            return ItemStack.EMPTY;
        }
        RegistryOps<JsonElement> ops = LiveMenus.level().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        return ItemStack.OPTIONAL_CODEC.parse(ops, JsonParser.parseString(json)).getOrThrow();
    }
}
