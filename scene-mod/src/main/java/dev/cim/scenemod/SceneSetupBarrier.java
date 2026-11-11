package dev.cim.scenemod;

/** Server-thread barrier: the settle window begins after all native setups complete. */
final class SceneSetupBarrier {
    private int pending;
    private boolean sealed;
    private boolean fired;
    private Runnable ready;

    Runnable begin() {
        if (sealed) throw new IllegalStateException("Scene setup already sealed");
        pending++;
        boolean[] completed = {false};
        return () -> {
            if (completed[0]) return;
            completed[0] = true;
            pending--;
            fire();
        };
    }

    void finish(Runnable ready) {
        if (sealed) throw new IllegalStateException("Scene setup already sealed");
        this.ready = ready;
        sealed = true;
        fire();
    }

    private void fire() {
        if (sealed && pending == 0 && !fired) {
            fired = true;
            ready.run();
        }
    }
}
