package dev.cim.scenemod;

import java.lang.reflect.Proxy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;

/** Transport contracts only; real dual-stack captures remain the lighting parity proof. */
public final class CaptureLightDataTest {
    public static void main(String[] args) {
        int[] liveBlock = {3};
        BlockAndTintGetter world = (BlockAndTintGetter) Proxy.newProxyInstance(
                CaptureLightDataTest.class.getClassLoader(), new Class<?>[]{BlockAndTintGetter.class},
                (proxy, method, values) -> {
                    if (!method.getName().equals("getBrightness")) throw new AssertionError(method);
                    return values[0] == LightLayer.BLOCK ? liveBlock[0] : 14;
                });
        String manifest = "cell\tx\ty\tz\tstate\ncell\t0\t-60\t9\tminecraft:stone\n"
                + "cell\t1\t-60\t9\tminecraft:stone\nskip\t-1\t-1\t-1\tmissing\n";
        String serialized = CaptureLightData.snapshot(world, manifest);
        if (!serialized.equals(CaptureLightData.snapshot(world, manifest))) {
            throw new AssertionError("snapshot serialization must be deterministic");
        }
        // The later live page must have no influence on an earlier page's light.
        liveBlock[0] = 12;
        CaptureLightData captured = CaptureLightData.parse(serialized);
        BlockPos center = new BlockPos(0, -60, 9);
        check(captured.brightness(LightLayer.BLOCK, center), 3);
        check(captured.brightness(LightLayer.SKY, center), 14);
        check(captured.brightness(LightLayer.BLOCK, center.offset(-2, -2, -2)), 3);
        check(captured.brightness(LightLayer.SKY, center.offset(3, 2, 2)), 14);
        check(captured.rawBrightness(center, 4), 10);
        check(captured.rawBrightness(center, 15), 3);
        expectFailure(() -> captured.brightness(LightLayer.BLOCK, center.offset(4, 0, 0)));
        expectFailure(() -> CaptureLightData.parse("bad\n"));
        expectFailure(() -> CaptureLightData.parse("x\ty\tz\tblock\tsky\n0\t0\t0\t16\t15\n"));
        expectFailure(() -> CaptureLightData.parse("x\ty\tz\tblock\tsky\n0\t0\t0\t1\t2\n0\t0\t0\t1\t2\n"));
        System.out.println("capture light data ALL OK");
    }

    private static void check(int actual, int expected) {
        if (actual != expected) throw new AssertionError(actual + " != " + expected);
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return;
        }
        throw new AssertionError("invalid or missing light snapshot accepted");
    }
}
