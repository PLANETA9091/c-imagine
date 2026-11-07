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
        checkNativeLighting();
        System.out.println("capture retained/interleaved buffer and native lighting tests: ALL OK");
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
