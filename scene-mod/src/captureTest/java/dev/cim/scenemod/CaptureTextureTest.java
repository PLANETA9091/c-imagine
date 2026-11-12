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
        String customAtlas = "mekanism:textures/atlas/robit.png";
        if (!customAtlas.equals(CaptureTexture.resourceFromRenderState(
                "texture[Optional[" + customAtlas + "]]"))) {
            throw new AssertionError("custom atlas texture-manager key was rewritten");
        }
        if (CaptureTexture.fromRenderState("texture[Optional.empty]") != null) {
            throw new AssertionError("unbound texture");
        }
        // 14-dg: the font glyph atlases register WITHOUT a .png suffix
        // (FontSet.stitch: minecraft:<font>/<n>) — optionalLocation resolves
        // any Optional[...] location, the .png contract stays intact.
        checkLocation("minecraft:default/0",
                "text_intensity[texture[Optional[minecraft:default/0]]]");
        checkLocation("minecraft:default/1",
                "text[texture[Optional[minecraft:default/1]]]");
        checkLocation("create:nixie_font/0",
                "text_see_through[texture[Optional[create:nixie_font/0]]]");
        checkLocation(null, "entity_solid[texture[Optional.empty]]");
        checkLocation("mod:textures/entity/gear.png",
                "entity_solid[texture[Optional[mod:textures/entity/gear.png]]]");
        // 14-ds THE READBACK-CHANNEL LAW: mask (RED) only for the glyph
        // marker (boolean `colored`, false on fonts); every other runtime
        // texture captures full RGBA. The old default-false read the
        // supplementaries globe's DynamicTexture map as a white mask —
        // mean RGBA [255,255,255,106], 0 non-white pixels — and its 75
        // cells measured 18.45M ae (41% of the run's whole red mass).
        if (!CaptureTexture.isCoverageMaskReadback(new FakeColoredMaskTexture())) {
            throw new AssertionError("colored=false marker must read back as a mask");
        }
        if (CaptureTexture.isCoverageMaskReadback(new FakeColoredTrueTexture())) {
            throw new AssertionError("colored=true marker must capture RGBA");
        }
        if (!CaptureTexture.isCoverageMaskReadback(new FakeMaskChild())) {
            throw new AssertionError("the marker is honored at any hierarchy depth");
        }
        if (CaptureTexture.isCoverageMaskReadback(new Object())) {
            throw new AssertionError("a marker-less runtime texture is full color — the globe map bug");
        }
        System.out.println("capture texture tests: ALL OK (17 cases)");
    }

    /** The vanilla glyph-atlas shape: boolean `colored`, false = single channel. */
    private static class FakeColoredMaskTexture {
        @SuppressWarnings("unused")
        private final boolean colored = false;
    }

    private static final class FakeColoredTrueTexture {
        @SuppressWarnings("unused")
        private final boolean colored = true;
    }

    /** The marker lives on the SUPERCLASS (FontTexture's real position). */
    private static final class FakeMaskChild extends FakeColoredMaskTexture {
    }

    private static void check(String expected, String resource) {
        String actual = CaptureTexture.fromRenderState(
                "entity_solid[texture[Optional[" + resource + "](blur=false, mipmap=false)]]");
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(resource + ": expected " + expected + ", got " + actual);
        }
    }

    private static void checkLocation(String expected, String state) {
        String actual = CaptureTexture.optionalLocation(state);
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(state + ": expected " + expected + ", got " + actual);
        }
    }
}
