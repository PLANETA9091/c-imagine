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
        var getBuffer = type.getDeclaredMethod("bufferFor", Object.class, String.class);
        getBuffer.setAccessible(true);
        Object solid = new Object(), cutout = new Object();
        String state = "texture[Optional[minecraft:textures/atlas/blocks.png]]";
        VertexConsumer a = (VertexConsumer) getBuffer.invoke(capture, solid, state);
        VertexConsumer b = (VertexConsumer) getBuffer.invoke(capture, cutout, state);
        if (a == b) throw new AssertionError("different materials share a mutable consumer");
        vertex(a, 0, 0, 255, 0);
        vertex(b, 10, 0, 0, 255);
        vertex(b, 11, 0, 0, 255);
        vertex(b, 11, 1, 0, 255);
        vertex(b, 10, 1, 0, 255);
        if (getBuffer.invoke(capture, solid, state) != a) {
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
        System.out.println("capture retained/interleaved buffer tests: ALL OK");
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
