package dev.cim.scenemod;

import java.lang.reflect.Proxy;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

/** Pins SectionCompiler's view-data -> model-data handoff. */
public final class CaptureModelDataTest {
    public static void main(String[] args) {
        var property = new ModelProperty<Integer>();
        ModelData base = ModelData.builder().with(property, 7).build();
        ModelData derived = base.derive().with(property, 21).build();
        var pos = BlockPos.ZERO;
        int[] calls = {0};
        var view = (BlockAndTintGetter) Proxy.newProxyInstance(
                CaptureModelDataTest.class.getClassLoader(), new Class<?>[]{BlockAndTintGetter.class},
                (proxy, method, values) -> {
                    if (!method.getName().equals("getModelData") || values[0] != pos) {
                        throw new AssertionError("unexpected view access: " + method.getName());
                    }
                    calls[0]++;
                    return base;
                });
        var model = (BakedModel) Proxy.newProxyInstance(
                CaptureModelDataTest.class.getClassLoader(), new Class<?>[]{BakedModel.class},
                (proxy, method, values) -> {
                    if (!method.getName().equals("getModelData") || values[0] != view
                            || values[1] != pos || values[3] != base) {
                        throw new AssertionError("capture lost the view's native model data");
                    }
                    return derived;
                });
        if (QuadDump.captureModelData(model, view, pos, null) != derived || calls[0] != 1) {
            throw new AssertionError("capture discarded model-derived data or skipped the view");
        }
        System.out.println("capture native model-data handoff: ALL OK");
    }
}
