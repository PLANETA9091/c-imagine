package dev.cim.scenemod;

import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

/** Native property-codec round trips, including the real cannon rotation failure. */
public final class SceneStateNamesTest {
    private enum Stage implements StringRepresentable {
        FIRST("readyStage"), SECOND("nextStage");
        private final String name;
        Stage(String name) { this.name = name; }
        public String getSerializedName() { return name; }
    }

    public static void main(String[] args) {
        var rotation = EnumProperty.create("rotate_tile", Rotation.class);
        if (rotation.getValue(Rotation.CLOCKWISE_180.toString().toLowerCase(java.util.Locale.ROOT)).isPresent()) {
            throw new AssertionError("native negative control did not reproduce CLOCKWISE_180 refusal");
        }
        if (!SceneStateNames.value(rotation, Rotation.CLOCKWISE_180).equals("180")) {
            throw new AssertionError("half-turn must use the native serialized value 180");
        }
        check(rotation);
        check(EnumProperty.create("facing", Direction.class));
        check(BooleanProperty.create("powered"));
        check(IntegerProperty.create("level", 0, 15));
        var stage = EnumProperty.create("stage", Stage.class);
        check(stage);
        if (!SceneStateNames.value(stage, Stage.FIRST).equals("readyStage")
                || stage.getValue("readystage").isPresent()) {
            throw new AssertionError("case-sensitive native property name was normalized");
        }
        System.out.println("scene native state-name round trips: ALL OK");
    }

    private static <T extends Comparable<T>> void check(Property<T> property) {
        for (T value : property.getPossibleValues()) {
            String emitted = SceneStateNames.value(property, value);
            if (!property.getValue(emitted).orElseThrow().equals(value)) {
                throw new AssertionError("scene value changed native state: " + property.getName() + "=" + emitted);
            }
        }
    }
}
