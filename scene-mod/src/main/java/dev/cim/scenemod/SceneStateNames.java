package dev.cim.scenemod;

import net.minecraft.world.level.block.state.properties.Property;

/** Scene values use the same names as the native block-state parser. */
final class SceneStateNames {
    private SceneStateNames() {}

    static <T extends Comparable<T>> String value(Property<T> property, T value) {
        return property.getName(value);
    }
}
