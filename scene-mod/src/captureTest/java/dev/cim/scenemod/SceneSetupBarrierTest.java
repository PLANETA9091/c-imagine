package dev.cim.scenemod;

/** Setup order, synchronous failure, duplicate callbacks and one settle window. */
public final class SceneSetupBarrierTest {
    public static void main(String[] args) {
        int[] scheduled = {0};
        var page = new SceneSetupBarrier();
        Runnable first = page.begin();
        Runnable second = page.begin();
        first.run();
        first.run();
        page.finish(() -> scheduled[0]++);
        if (scheduled[0] != 0) throw new AssertionError("settle started before native setup finished");
        second.run();
        second.run();
        if (scheduled[0] != 1) throw new AssertionError("native callbacks scheduled multiple settle windows");
        try {
            page.begin();
            throw new AssertionError("late native setup entered an already settled page");
        } catch (IllegalStateException expected) {}
        var synchronous = new SceneSetupBarrier();
        synchronous.begin().run();
        synchronous.finish(() -> scheduled[0]++);
        var empty = new SceneSetupBarrier();
        empty.finish(() -> scheduled[0]++);
        if (scheduled[0] != 3) throw new AssertionError("empty/synchronously failed page lost its settle window");
        System.out.println("scene native setup barrier: ALL OK");
    }
}
