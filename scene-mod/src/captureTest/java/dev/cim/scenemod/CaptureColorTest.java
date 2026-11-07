package dev.cim.scenemod;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.world.phys.Vec3;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/** Compares both production recorders with the real NeoForge default consumer. */
public final class CaptureColorTest {
    public static void main(String[] args) throws Exception {
        var id = ResourceLocation.parse("fixture:block/color");
        try (var contents = new SpriteContents(id, new FrameSize(16, 16),
                new NativeImage(16, 16, true), ResourceMetadata.EMPTY)) {
            var constructor = TextureAtlasSprite.class.getDeclaredConstructor(ResourceLocation.class,
                    SpriteContents.class, int.class, int.class, int.class, int.class);
            constructor.setAccessible(true);
            var sprite = constructor.newInstance(ResourceLocation.parse("minecraft:textures/atlas/blocks.png"),
                    contents, 16, 16, 0, 0);
            for (boolean readColor : new boolean[]{true, false}) {
                for (float alpha : new float[]{1f, .37f, .73f}) {
                    compare(sprite, readColor, alpha);
                }
            }
        }
        System.out.println("capture baked RGBA vs native consumer: ALL 12 recorder comparisons OK");
    }

    private static void compare(TextureAtlasSprite sprite, boolean readColor, float alpha) throws Exception {
        int[] vertices = new int[32];
        int[] colors = {0x4c3399dd, 0x81e14023, 0x00ffffff, 0xffffffff};
        for (int i = 0; i < 4; i++) {
            int offset = i * 8;
            vertices[offset] = Float.floatToIntBits(i == 1 || i == 2 ? 1 : 0);
            vertices[offset + 1] = Float.floatToIntBits(i >= 2 ? 1 : 0);
            vertices[offset + 3] = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                    ? colors[i] : Integer.reverseBytes(colors[i]);
            vertices[offset + 4] = Float.floatToIntBits(i == 1 || i == 2 ? 1 : 0);
            vertices[offset + 5] = Float.floatToIntBits(i >= 2 ? 1 : 0);
        }
        var quad = new BakedQuad(vertices, -1, Direction.SOUTH, sprite, true);
        float[] brightness = {1f, .87f, .53f, .4f};
        var expected = new NativeRecorder();
        var pose = new PoseStack().last();
        expected.putBulkData(pose, quad, brightness, .83f, .61f, .97f, alpha, new int[4], 0, readColor);
        for (String name : List.of("ItemCapture", "Capture")) {
            var type = Arrays.stream(QuadDump.class.getDeclaredClasses())
                    .filter(c -> c.getSimpleName().equals(className)).findFirst().orElseThrow();
            Object capture;
            VertexConsumer consumer;
            if (name.equals("Capture")) {
                var ctor = type.getDeclaredConstructor(List.class, Vec3.class);
                ctor.setAccessible(true);
                capture = ctor.newInstance(List.of(sprite), Vec3.ZERO);
                consumer = (VertexConsumer) capture;
            } else {
                var ctor = type.getDeclaredConstructor(List.class, float.class);
                ctor.setAccessible(true);
                capture = ctor.newInstance(List.of(sprite), 0f);
                var buffer = type.getDeclaredMethod("bufferFor", Object.class, String.class, boolean.class);
                buffer.setAccessible(true);
                consumer = (VertexConsumer) buffer.invoke(capture, new Object(),
                        "texture[Optional[minecraft:textures/atlas/blocks.png]]", true);
            }
            consumer.putBulkData(pose, quad, brightness, .83f, .61f, .97f, alpha, new int[4], 0, readColor);
            var out = type.getDeclaredField("out");
            out.setAccessible(true);
            String[] row = out.get(capture).toString().strip().split("\\t");
            if (row.length != 39) throw new AssertionError(name + " lost vertex alpha: " + row.length);
            for (int i = 0; i < 4; i++) {
                for (int c = 0; c < 4; c++) {
                    int column = c == 3 ? 35 + i : 23 + i * 3 + c;
                    float actual = Float.parseFloat(row[column]);
                    float tolerance = c == 3 ? .000001f : .00051f; // TSV decimal precision
                    if (Math.abs(actual - expected.colors[i][c] / 255f) > tolerance) {
                        throw new AssertionError(name + " readColor=" + readColor + " alpha=" + alpha
                                + " vertex=" + i + " channel=" + c + " actual=" + actual
                                + " native=" + expected.colors[i][c] / 255f);
                    }
                }
            }
        }
    }

    private static final class NativeRecorder implements VertexConsumer {
        final int[][] colors = new int[4][4];
        int vertex = -1;
        public VertexConsumer addVertex(float x, float y, float z) { vertex++; return this; }
        public VertexConsumer setColor(int r, int g, int b, int a) {
            colors[vertex] = new int[]{r, g, b, a}; return this;
        }
        public VertexConsumer setUv(float u, float v) { return this; }
        public VertexConsumer setUv1(int u, int v) { return this; }
        public VertexConsumer setUv2(int u, int v) { return this; }
        public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}
