package dev.cim.scenemod;

import com.mojang.blaze3d.vertex.VertexConsumer;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

/** Recorder contract regression without initializing a game, GL context or FML loader. */
public final class CaptureBufferTest {
    public static void main(String[] args) throws Exception {
        Class<?> type = Arrays.stream(QuadDump.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals("ItemCapture")).findFirst().orElseThrow();
        var ctor = type.getDeclaredConstructor(List.class, float.class);
        ctor.setAccessible(true);
        Object capture = ctor.newInstance(List.of(), 0f);
        var getBuffer = type.getDeclaredMethod("bufferFor", Object.class, String.class, boolean.class);
        getBuffer.setAccessible(true);
        Object solid = new Object(), cutout = new Object();
        String state = "texture[Optional[minecraft:textures/atlas/blocks.png]]";
        VertexConsumer a = (VertexConsumer) getBuffer.invoke(capture, solid, state, false);
        VertexConsumer b = (VertexConsumer) getBuffer.invoke(capture, cutout, state, true);
        if (a == b) throw new AssertionError("different materials share a mutable consumer");
        if (field(a.getClass(), "rawShade").getBoolean(a)
                || !field(b.getClass(), "rawShade").getBoolean(b)) {
            throw new AssertionError("source shader lighting was not retained per material");
        }
        vertex(a, 0, 0, 255, 0);
        vertex(b, 10, 0, 0, 255);
        vertex(b, 11, 0, 0, 255);
        vertex(b, 11, 1, 0, 255);
        vertex(b, 10, 1, 0, 255);
        if (getBuffer.invoke(capture, solid, state, false) != a) {
            throw new AssertionError("retained consumer replaced before its quad finished");
        }
        vertex(a, 1, 0, 255, 0);
        vertex(a, 1, 1, 255, 0);
        vertex(a, 0, 1, 255, 0);
        check(a, 0, 1, 0);
        check(b, 10, 0, 1);
        var flush = type.getDeclaredMethod("flush");
        flush.setAccessible(true);
        flush.invoke(capture);
        // Empty atlas is deliberate: each complete quad is counted independently.
        if (field(type, "foreign").getLong(capture) != 2) {
            throw new AssertionError("both pending buffers were not flushed");
        }
        // LiquidBlockRenderer emits independently chunk-relative x/y/z,
        // including negative world coordinates. Exercise the real recorder.
        var vectorCtor = type.getDeclaredConstructor(List.class, net.minecraft.world.phys.Vec3.class);
        vectorCtor.setAccessible(true);
        for (var pos : List.of(new net.minecraft.core.BlockPos(-5, -60, 9),
                new net.minecraft.core.BlockPos(-1, -1, -1), new net.minecraft.core.BlockPos(16, 32, 0))) {
            Object liquid = vectorCtor.newInstance(List.of(), QuadDump.liquidCaptureOrigin(pos));
            var vertices = (VertexConsumer) getBuffer.invoke(liquid, new Object(), state, false);
            vertices.addVertex((pos.getX() & 15) + .25f, (pos.getY() & 15) + .5f,
                    (pos.getZ() & 15) + .75f);
            float[][] normalized = (float[][]) field(vertices.getClass(), "pos").get(vertices);
            if (!Arrays.equals(normalized[0], new float[]{.25f, .5f, .75f})) {
                throw new AssertionError("liquid chunk-origin normalization failed: " + pos);
            }
        }
        checkNativeLighting();
        checkRecordedLight(type, ctor, getBuffer, flush, state);
        System.out.println("capture retained/interleaved buffer and native lighting tests: ALL OK");
    }

    private static void checkRecordedLight(Class<?> type, java.lang.reflect.Constructor<?> ctor,
                                           java.lang.reflect.Method getBuffer,
                                           java.lang.reflect.Method flush, String state) throws Exception {
        Object capture = ctor.newInstance(List.of(), 0f);
        VertexConsumer buffer = (VertexConsumer) getBuffer.invoke(capture, new Object(), state, false);
        // A named fixture texture avoids initializing the GPU atlas in this transport test.
        field(buffer.getClass(), "texture").set(buffer, "fixture:textures/light.png");
        for (int quad = 0; quad < 3; quad++) {
            for (int v = 0; v < 4; v++) {
                vertex(buffer, v & 1, v >> 1, 255, 255);
                if (quad == 0) buffer.setUv2(3 << 4, 12 << 4);
                if (quad == 2) buffer.setUv2(15 << 4, 15 << 4);
            }
        }
        flush.invoke(capture);
        int[] baked = new int[32];
        for (int v = 0; v < 4; v++) {
            baked[v * 8 + 3] = -1;
            baked[v * 8 + 6] = (7 << 4) | (4 << 20);
        }
        buffer.putBulkData(new com.mojang.blaze3d.vertex.PoseStack().last(),
                new net.minecraft.client.renderer.block.model.BakedQuad(
                        baked, -1, net.minecraft.core.Direction.UP, null, false),
                new float[]{1, 1, 1, 1}, 1, 1, 1, 1,
                new int[]{(3 << 4) | (12 << 20), 0, 0, 0}, 0, false);
        String[] rows = field(type, "out").get(capture).toString().split("\n");
        if (rows.length != 4 || !rows[0].endsWith("\t3\t12")
                || rows[1].split("\t").length != 39 || !rows[2].endsWith("\t15\t15")
                || !rows[3].endsWith("\t7\t12")) {
            throw new AssertionError("native stream/baked light lost, leaked or merged incorrectly: "
                    + Arrays.toString(rows));
        }
        String[] fluid = QuadDump.withLiquidMaterial(String.join("\n", rows) + "\n", "translucent")
                .split("\n");
        if (!fluid[0].endsWith("\ttranslucent\t3\t12")
                || fluid[0].split("\t").length != 42
                || !fluid[1].endsWith("\ttranslucent") || fluid[1].split("\t").length != 40
                || !fluid[3].endsWith("\ttranslucent\t7\t12")) {
            throw new AssertionError("liquid material overwrote or followed the packed light tail");
        }
    }

    private static void checkNativeLighting() throws Exception {
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        var lightsField = com.mojang.blaze3d.systems.RenderSystem.class.getDeclaredField("shaderLightDirections");
        lightsField.setAccessible(true);
        // Use native inventory state as the independently reproduced contaminant.
        com.mojang.blaze3d.platform.Lighting.setupForEntityInInventory();
        org.joml.Vector3f[] lights = (org.joml.Vector3f[]) lightsField.get(null);
        if (lights[0].y >= 0) throw new AssertionError("native inventory-light precondition");
        QuadDump.setupCaptureLighting(false);
        lights = (org.joml.Vector3f[]) lightsField.get(null);
        org.joml.Vector3f first = new org.joml.Vector3f(.2f, 1, -.7f).normalize();
        org.joml.Vector3f second = new org.joml.Vector3f(-.2f, 1, .7f).normalize();
        if (lights[0].distance(first) > 1e-6 || lights[1].distance(second) > 1e-6) {
            throw new AssertionError("capture retained stale GUI lighting instead of native world lighting");
        }
        QuadDump.setupCaptureLighting(true);
        lights = (org.joml.Vector3f[]) lightsField.get(null);
        second.y = -second.y;
        if (lights[0].distance(first) > 1e-6 || lights[1].distance(second) > 1e-6) {
            throw new AssertionError("capture lost native constant-ambient dimension lighting");
        }
    }

    private static void vertex(VertexConsumer buffer, float x, float y, int red, int green) {
        buffer.addVertex(x, y, 0).setUv(0, 0).setColor(red, green, 0, 255);
    }

    private static void check(VertexConsumer buffer, float offset, float red, float green) throws Exception {
        float[][] positions = (float[][]) field(buffer.getClass(), "pos").get(buffer);
        float[][] colors = (float[][]) field(buffer.getClass(), "rgb").get(buffer);
        if (field(buffer.getClass(), "n").getInt(buffer) != 3) {
            throw new AssertionError("interleaving truncated a pending quad");
        }
        for (int i = 0; i < 4; i++) {
            if ((positions[i][0] != offset && positions[i][0] != offset + 1)
                    || colors[i][0] != red || colors[i][1] != green) {
                throw new AssertionError("material buffers mixed positions or colors");
            }
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
