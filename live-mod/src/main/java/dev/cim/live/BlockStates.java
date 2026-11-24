package dev.cim.live;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A block state as text, the way the F3 screen shows it: {@code ns:id[k=v,k2=v2]}, the properties
 * sorted by name, the brackets absent for a block with none. The text is what Paper keeps for a
 * placed mod block, so a block comes back with the properties it was placed with.
 */
final class BlockStates {

    private BlockStates() {
    }

    /** The F3-style text of {@code state}. */
    static String format(BlockState state) {
        StringBuilder out = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        List<String> props = new ArrayList<>();
        for (Map.Entry<Property<?>, Comparable<?>> e : state.getValues().entrySet()) {
            props.add(e.getKey().getName() + "=" + valueName(e.getKey(), e.getValue()));
        }
        if (!props.isEmpty()) {
            props.sort(null);
            out.append('[').append(String.join(",", props)).append(']');
        }
        return out.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueName(Property property, Object value) {
        return property.getName((Comparable) value);
    }

    /**
     * The state {@code text} names, or null when its block is unknown to the layer. Properties the
     * block does not have, or values it does not take, are skipped: the state keeps the defaults for
     * them, so an odd string degrades to the block's default state instead of failing.
     */
    static BlockState parse(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int open = text.indexOf('[');
        String id = open < 0 ? text : text.substring(0, open);
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.get(rl);
        BlockState state = block.defaultBlockState();
        if (open < 0 || !text.endsWith("]")) {
            return state;
        }
        for (String pair : text.substring(open + 1, text.length() - 1).split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            Property<?> property = block.getStateDefinition().getProperty(pair.substring(0, eq));
            if (property != null) {
                state = withValue(state, property, pair.substring(eq + 1));
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String name) {
        return property.getValue(name).map(value -> state.setValue(property, value)).orElse(state);
    }
}
