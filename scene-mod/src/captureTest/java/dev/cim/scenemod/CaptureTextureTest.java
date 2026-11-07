package dev.cim.scenemod;

/** No client, GL context or third-party test dependency is needed for binding parsing. */
public final class CaptureTextureTest {
    public static void main(String[] args) {
        check("@mekanism:render/jetpack.png", "mekanism:render/jetpack.png");
        check("@mekanism:render/flamethrower.png", "mekanism:render/flamethrower.png");
        check("mod:entity/gear", "mod:textures/entity/gear.png");
        check(null, "minecraft:textures/atlas/blocks.png");
        check("@mod:block/custom.png", "mod:block/custom.png");
        // Only the vanilla block atlas has the recorded block-sprite UV domain.
        check("mod:atlas/blocks", "mod:textures/atlas/blocks.png");
        if (CaptureTexture.fromRenderState("texture[Optional.empty]") != null) {
            throw new AssertionError("unbound texture");
        }
        System.out.println("capture texture tests: ALL OK (7 cases)");
    }

    private static void check(String expected, String resource) {
        String actual = CaptureTexture.fromRenderState(
                "entity_solid[texture[Optional[" + resource + "](blur=false, mipmap=false)]]");
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(resource + ": expected " + expected + ", got " + actual);
        }
    }
}
