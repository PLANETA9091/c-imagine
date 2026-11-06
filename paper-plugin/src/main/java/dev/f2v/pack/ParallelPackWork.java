package dev.f2v.pack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Bounded dedicated work-stealing pool for CPU-heavy/read-only pack and discovery preparation.
 *
 * <p>Fork/join is intentional here: pack generation contains nested parallel stages (mod jars ->
 * classes/scanners -> model parts). A fixed executor either deadlocks when every outer worker waits
 * for inner work, or has to force nested stages serial. Work stealing keeps one bounded F2V pool
 * busy without borrowing Minecraft's common-pool threads.</p>
 */
public final class ParallelPackWork {

    private ParallelPackWork() {}

    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final Object POOL_LOCK = new Object();
    private static volatile ForkJoinPool pool;
    private static volatile boolean accepting = true;

    private static ForkJoinPool createPool() {
        return new ForkJoinPool(
                configuredThreads(),
                owner -> {
                    ForkJoinWorkerThread thread =
                            ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(owner);
                    thread.setName("f2v-pack-worker-" + THREAD_IDS.incrementAndGet());
                    thread.setDaemon(true);
                    // Forge's EventSubclassTransformer resolves parent classes through the
                    // current thread's context loader. A vanilla ForkJoin worker can otherwise
                    // retain the AppClassLoader, which cannot see libraries shaded into F2V's
                    // ModLauncher module (for example JOrbis FuncMapping -> Mapping0).
                    thread.setContextClassLoader(ParallelPackWork.class.getClassLoader());
                    return thread;
                },
                null,
                false);
    }

    private static ForkJoinPool pool() {
        ForkJoinPool current = pool;
        if (current != null && !current.isShutdown()) return current;
        synchronized (POOL_LOCK) {
            if (!accepting) throw new java.util.concurrent.CancellationException(
                    "f2v pack work is stopping");
            current = pool;
            if (current == null || current.isShutdown()) {
                current = createPool();
                pool = current;
            }
            return current;
        }
    }

    /** Reopens the lazily-created pool for a new Minecraft server lifecycle. */
    public static void beginServerLifecycle() {
        synchronized (POOL_LOCK) {
            accepting = true;
        }
    }

    /** Cancels queued/nested pack work and retires every worker belonging to the stopped server. */
    public static void endServerLifecycle() {
        ForkJoinPool current;
        synchronized (POOL_LOCK) {
            accepting = false;
            current = pool;
            pool = null;
        }
        if (current == null) return;
        current.shutdownNow();
        try {
            current.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public static int configuredThreads() {
        int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
        int configured = Integer.getInteger("f2v.packThreads", 0);
        if (configured > 0) return Math.max(1, Math.min(configured, cpus));
        // Pack generation shares the JVM/heap with the live server. Saturating every CPU except
        // one creates large simultaneous allocation bursts and stop-the-world GC pauses that show
        // up as periodic MSPT spikes. Keep the default bounded; operators can still override it.
        return Math.max(1, Math.min(4, cpus / 2));
    }

    /** Submit independent read-only preparation without blocking the caller. This uses the same
     * bounded F2V work-stealing pool as pack generation, so startup helpers can use spare cores
     * without creating another executor or competing with Minecraft's common pool. */
    public static void execute(Runnable work) {
        if (work == null) return;
        pool().execute(work);
    }

    public static <T> void forEach(Collection<T> values, Consumer<T> work) {
        if (values == null || values.isEmpty() || work == null) return;
        List<T> snapshot = values instanceof List<T> list ? new ArrayList<>(list)
                : new ArrayList<>(values);
        if (configuredThreads() <= 1 || snapshot.size() <= 1) {
            snapshot.forEach(work);
            return;
        }
        invoke(new ForEachAction<>(snapshot, work, 0, snapshot.size(), threshold(snapshot.size())));
    }

    /** Parallel map with results returned in the input's iteration order. */
    public static <T, R> List<R> mapOrdered(Collection<T> values, Function<T, R> work) {
        if (values == null || values.isEmpty() || work == null) return List.of();
        List<T> snapshot = values instanceof List<T> list ? new ArrayList<>(list)
                : new ArrayList<>(values);
        if (configuredThreads() <= 1 || snapshot.size() <= 1) {
            List<R> result = new ArrayList<>(snapshot.size());
            for (T value : snapshot) result.add(work.apply(value));
            return result;
        }

        Object[] result = new Object[snapshot.size()];
        invoke(new MapAction<>(snapshot, work, result, 0, snapshot.size(), threshold(snapshot.size())));
        List<R> ordered = new ArrayList<>(result.length);
        for (Object value : result) {
            @SuppressWarnings("unchecked") R cast = (R) value;
            ordered.add(cast);
        }
        return ordered;
    }

    private static int threshold(int size) {
        int parallelism = Math.max(1, configuredThreads());
        return Math.max(1, (size + parallelism * 4 - 1) / (parallelism * 4));
    }

    private static void invoke(ForkJoinTask<?> task) {
        // Nested pack/discovery work stays in the same pool. The waiting worker participates in the
        // inner task, so a giant mod can consume cores as smaller outer jar tasks finish.
        ForkJoinPool current = pool();
        if (Thread.currentThread() instanceof ForkJoinWorkerThread worker
                && worker.getPool() == current) {
            task.invoke();
        } else {
            current.invoke(task);
        }
    }

    private static void requireAccepting() {
        if (!accepting || Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("f2v pack work is stopping");
        }
    }

    private static final class ForEachAction<T> extends RecursiveAction {
        private final List<T> values;
        private final Consumer<T> work;
        private final int start;
        private final int end;
        private final int threshold;

        private ForEachAction(List<T> values, Consumer<T> work, int start, int end, int threshold) {
            this.values = values;
            this.work = work;
            this.start = start;
            this.end = end;
            this.threshold = threshold;
        }

        @Override
        protected void compute() {
            requireAccepting();
            if (end - start <= threshold) {
                for (int i = start; i < end; i++) {
                    requireAccepting();
                    work.accept(values.get(i));
                }
                return;
            }
            int middle = (start + end) >>> 1;
            invokeAll(new ForEachAction<>(values, work, start, middle, threshold),
                    new ForEachAction<>(values, work, middle, end, threshold));
        }
    }

    private static final class MapAction<T, R> extends RecursiveAction {
        private final List<T> values;
        private final Function<T, R> work;
        private final Object[] result;
        private final int start;
        private final int end;
        private final int threshold;

        private MapAction(List<T> values, Function<T, R> work, Object[] result,
                          int start, int end, int threshold) {
            this.values = values;
            this.work = work;
            this.result = result;
            this.start = start;
            this.end = end;
            this.threshold = threshold;
        }

        @Override
        protected void compute() {
            requireAccepting();
            if (end - start <= threshold) {
                for (int i = start; i < end; i++) {
                    requireAccepting();
                    result[i] = work.apply(values.get(i));
                }
                return;
            }
            int middle = (start + end) >>> 1;
            invokeAll(new MapAction<>(values, work, result, start, middle, threshold),
                    new MapAction<>(values, work, result, middle, end, threshold));
        }
    }
}
