package dev.cim.live;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.heightproviders.BiasedToBottomHeight;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.heightproviders.TrapezoidHeight;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
import net.minecraft.world.level.levelgen.heightproviders.VeryBiasedToBottomHeight;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.NoiseBasedCountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.BiomeModifiers;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Mod world generation for Paper's chunks (survival slice 1): the placed features that the real
 * server's biome modifiers add (Create's zinc and striated ores, every {@code neoforge:add_features}
 * entry whose feature is not a vanilla one), run for one new chunk at a time by the real code of the
 * feature, against a {@link WorldGenLevel} facade over a snapshot of that chunk.
 *
 * <p>Paper asks two things, both as text on its own entry ({@value #KEY}, never the cimlive protocol's
 * {@code LiveProtocol.call}, so a chunk decoration never waits for the main thread's tick):
 * <pre>
 * in:  list                                            the boot listing (or "wait" until the real server runs)
 * out: ready TAB features TAB modifiers TAB skipped TAB winLo TAB winHi TAB reasons
 *
 * in:  decorate TAB cx TAB cz TAB seed TAB snapMin TAB snapMax TAB worldMin TAB worldMax   then:
 *        mat TAB i TAB blockId                        the block palette of the chunk (i in order)
 *        bpal TAB i TAB biomeId                       the biome palette (i in order)
 *        col TAB lx TAB lz TAB i:n,i:n,...            one column over the snapshot rows, bottom up, as runs
 *        bio TAB qx TAB qz TAB i:n,...                one 4x4 biome column over the world's quart rows, as runs
 * out: set TAB lx TAB y TAB lz TAB blockState      a block the features changed in the chunk (local x, z)
 *      fail TAB exception TAB count                 a feature that threw (the chunk keeps the rest)
 *      stat TAB placed TAB clipped TAB blockEntity TAB emitted TAB outsideWindow TAB partialHeight
 * </pre>
 *
 * <p>The snapshot window ({@code winLo..winHi}, section-aligned) is the height range of every runnable
 * feature plus {@link #WINDOW_MARGIN} blocks, computed once from the placement modifiers. Paper reads
 * only that window, so the decoration costs in proportion to the window, not to the build height. A
 * feature whose height range cannot be read (an unknown placement modifier, reflection refused) widens
 * the window to the whole build height, which is the exact but slow path.
 *
 * <p>Parity: the decoration seed and each feature's seed are the vanilla ones ({@code
 * ChunkGenerator.applyBiomeDecoration}: the world seed, the chunk origin, the feature's index in the
 * step's sorted list, the step), with the feature list sorted by the same {@link FeatureSorter} over the
 * vanilla overworld biome source's biomes. Deviations, all named in the design notes: only the mod
 * features run (no vanilla ones); writes outside the chunk are dropped, and reads outside it see air;
 * reads outside the snapshot window see air (exact while a feature's footprint stays inside the margin;
 * a write that lands outside the window is counted as {@code outsideWindow}); a height query while the
 * window is partial is answered from the window ({@code partialHeight} counts them); the snapshot holds
 * the default state of each material; the snapshot is the chunk's own blocks before any other populator
 * ran.
 *
 * <p>Thread safety: the plan is built once under {@link #LOCK}, and each decorate builds its own
 * facade and state from the call's own snapshot. The lock serializes the decorations of Paper's
 * worldgen threads against each other (the mod code's own thread-safety is not documented, so it is not
 * relied on), and it is never the lock of {@code LiveProtocol.call}, so the main thread's tick does not
 * wait for a chunk.
 */
public final class Worldgen {

    public static final String KEY = "cim.worldgen";

    /** Blocks past a feature's height range that its footprint may reach (ore veins spill a few blocks). */
    static final int WINDOW_MARGIN = 16;
    private static final int SECTION = 16;

    /** Serializes worldgen calls among themselves only. Never held by the main thread's protocol. */
    private static final Object LOCK = new Object();

    /** The boot plan, built once from the real server's registries. Guarded by {@link #LOCK}. */
    private static Plan plan;

    /** Reused across decorations (the lock serializes them): the snapshot's block and biome grids. */
    private static short[] grid = new short[0];
    private static short[] biomeGrid = new short[0];

    private Worldgen() {
    }

    /** Publishes the entry point; called once the registries are complete. */
    static void publish() {
        java.util.function.Function<String, String> call = Worldgen::call;
        System.getProperties().put(KEY, call);
    }

    static String call(String request) {
        try {
            String[] lines = request.split("\n");
            String[] head = lines[0].split("\t");
            return switch (head[0]) {
                case "list" -> list(head);
                case "biomes" -> biomes(head);
                case "locate" -> locate(head);
                case "decorate" -> decorate(head, lines);
                default -> "error\tunknown worldgen command " + head[0] + "\n";
            };
        } catch (Throwable t) {
            return "error\t" + t.getClass().getSimpleName() + ": " + t.getMessage() + "\n";
        }
    }

    /** The real server, once it runs with its overworld; null before that (Paper waits). */
    private static MinecraftServer realServer() {
        MinecraftServer real = ServerLifecycleHooks.getCurrentServer();
        if (real == null || real instanceof FacadeServer || real.overworld() == null) {
            return null;
        }
        // The plan reads the generator's biome source; TerraBlender injects the
        // modded biomes while the level starts, BEFORE the first tick. Answering
        // "wait" until then keeps the cached plan from being built vanilla-only.
        if (real.getTickCount() == 0) {
            return null;
        }
        return real;
    }

    private static String list(String[] head) {
        MinecraftServer real = realServer();
        if (real == null) {
            return "wait\n";
        }
        synchronized (LOCK) {
            String listing = plan(real).listing;
            // Warm the biome sampler while the plugin still waits for the plan:
            // RandomState.create builds every noise instance (~1 s), and the
            // first chunk must not pay that on Paper's chunk thread.
            if (head.length > 1) {
                try {
                    warm(Long.parseLong(head[1]), real);
                } catch (NumberFormatException ignored) {
                    // no seed in the request: the first biomes call warms it
                }
            }
            return listing + "\n";
        }
    }

    /** Build (or reuse) the layer's biome sampler for one world seed. */
    private static void warm(long seed, MinecraftServer real) {
        ServerLevel overworld = real.overworld();
        ChunkGenerator generator = overworld.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) {
            return;
        }
        if (randomState != null && randomStateSeed == seed) {
            return;
        }
        HolderGetter<NormalNoise.NoiseParameters> noises =
                overworld.registryAccess().lookupOrThrow(Registries.NOISE);
        RandomState state = RandomState.create(noise.generatorSettings().value(), noises, seed);
        randomStateSeed = seed;
        randomState = state;
    }

    /** The plan, built on first use. Caller holds {@link #LOCK}. */
    private static Plan plan(MinecraftServer real) {
        if (plan == null) {
            plan = Plan.build(real);
        }
        return plan;
    }

    /** One boot plan: the sorted feature lists of the overworld, and the mod features the decorate runs. */
    private record Plan(List<FeatureSorter.StepFeatureData> steps, List<Map<PlacedFeature, Integer>> index,
                        Set<Holder<Biome>> possible, Set<PlacedFeature> runnable, String listing) {

        /** A mod feature the listing skips, by a Java class name heuristic: structure or entity code. */
        private static final String[][] SKIP_MARKERS = {
                {"Structure", "structure"}, {"Jigsaw", "structure"}, {"Spawner", "entity"}, {"Entity", "entity"}};

        /** Placement modifiers that never move a position in y: they count, filter or pick x-z only. */
        private static final Set<Class<?>> Y_NEUTRAL = Set.of(CountPlacement.class, NoiseBasedCountPlacement.class,
                InSquarePlacement.class, BiomeFilter.class, RarityFilter.class, BlockPredicateFilter.class);

        static Plan build(MinecraftServer real) {
            RegistryAccess access = real.registryAccess();
            ServerLevel overworld = real.overworld();
            ResourceKey<MultiNoiseBiomeSourceParameterList> key = ResourceKey.create(
                    Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST, ResourceLocation.withDefaultNamespace("overworld"));
            Holder<MultiNoiseBiomeSourceParameterList> preset = access.lookupOrThrow(
                    Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(key);
            // EVERY registered biome, not the generator source's memoized set:
            // a world that was loaded (not created) this boot builds its biome
            // source before TerraBlender finishes injecting, and the memo then
            // misses the modded biomes entirely. The registry has them all; the
            // decorate filters by the chunk's own biomes anyway.
            Set<Holder<Biome>> possible = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Holder<Biome>> biomes = new ArrayList<>();
            for (Holder<Biome> holder : access.registryOrThrow(Registries.BIOME).holders().toList()) {
                possible.add(holder);
                biomes.add(holder);
            }
            List<FeatureSorter.StepFeatureData> steps = FeatureSorter.buildFeaturesPerStep(biomes,
                    b -> b.value().getGenerationSettings().features(), true);

            Registry<PlacedFeature> placed = access.registryOrThrow(Registries.PLACED_FEATURE);
            List<Map<PlacedFeature, Integer>> index = new ArrayList<>();
            Set<PlacedFeature> mods = Collections.newSetFromMap(new IdentityHashMap<>());
            for (FeatureSorter.StepFeatureData step : steps) {
                Map<PlacedFeature, Integer> at = new IdentityHashMap<>();
                for (int i = 0; i < step.features().size(); i++) {
                    PlacedFeature pf = step.features().get(i);
                    at.put(pf, i);
                    ResourceLocation id = placed.getKey(pf);
                    if (id != null && !id.getNamespace().equals("minecraft")) {
                        mods.add(pf);
                    }
                }
                index.add(at);
            }

            // A mod feature the decorate does not run is counted by reason, not run.
            Set<PlacedFeature> runnable = Collections.newSetFromMap(new IdentityHashMap<>());
            Map<String, Integer> skipped = new TreeMap<>();
            for (PlacedFeature pf : mods) {
                String reason = skipReason(pf);
                if (reason == null) {
                    runnable.add(pf);
                } else {
                    skipped.merge(reason, 1, Integer::sum);
                }
            }

            // The biome modifiers that add at least one mod feature of the overworld.
            int modifiers = 0;
            Registry<BiomeModifier> modifierRegistry = access.registryOrThrow(NeoForgeRegistries.Keys.BIOME_MODIFIERS);
            for (BiomeModifier modifier : modifierRegistry) {
                if (modifier instanceof BiomeModifiers.AddFeaturesBiomeModifier add) {
                    for (Holder<PlacedFeature> h : add.features()) {
                        if (mods.contains(h.value())) {
                            modifiers++;
                            break;
                        }
                    }
                }
            }

            WorldGenerationContext context = new WorldGenerationContext(overworld.getChunkSource().getGenerator(), overworld);
            int[] window = window(runnable, context, overworld.getMinBuildHeight(), overworld.getMaxBuildHeight());

            String reasons = skipped.isEmpty() ? "none" : String.join(",", skipped.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).toList());
            System.out.println("[cimlive] plan: biomes=" + biomes.size() + " modFeatures=" + mods.size()
                    + " runnable=" + runnable.size() + " possible=" + possible.size());
            String listing = "ready\t" + runnable.size() + "\t" + modifiers + "\t" + (mods.size() - runnable.size())
                    + "\t" + window[0] + "\t" + window[1] + "\t" + reasons;
            return new Plan(steps, index, possible, runnable, listing);
        }

        /** The reason a mod feature is not run in a chunk, or null when it runs. */
        private static String skipReason(PlacedFeature pf) {
            String type = pf.feature().value().feature().getClass().getName();
            for (String[] marker : SKIP_MARKERS) {
                if (type.contains(marker[0])) {
                    return marker[1];
                }
            }
            return null;
        }

        /**
         * The snapshot's y range [lo, hi): the runnable features' height ranges plus the margin, aligned to
         * whole sections of the world. The whole build height when one feature's range cannot be read.
         */
        static int[] window(Set<PlacedFeature> runnable, WorldGenerationContext context, int worldMin, int worldMax) {
            if (runnable.isEmpty()) {
                return new int[]{worldMin, worldMin};
            }
            if ((worldMax - worldMin) % SECTION != 0) {
                return new int[]{worldMin, worldMax};
            }
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            for (PlacedFeature pf : runnable) {
                int[] range = heightRange(pf, context);
                if (range == null) {
                    return new int[]{worldMin, worldMax};
                }
                lo = Math.min(lo, range[0]);
                hi = Math.max(hi, range[1]);
            }
            int from = Math.max(worldMin, lo - WINDOW_MARGIN);
            int to = Math.min(worldMax, hi + 1 + WINDOW_MARGIN);
            from = worldMin + Math.floorDiv(from - worldMin, SECTION) * SECTION;
            to = Math.min(worldMax, worldMin + Math.ceilDiv(to - worldMin, SECTION) * SECTION);
            return to <= from ? new int[]{worldMin, worldMin} : new int[]{from, to};
        }

        /** The inclusive y range of a feature's placement, or null when the placement cannot be bounded. */
        static int[] heightRange(PlacedFeature pf, WorldGenerationContext context) {
            int[] found = null;
            for (PlacementModifier modifier : pf.placement()) {
                if (modifier instanceof HeightRangePlacement range) {
                    if (found != null) {
                        return null;
                    }
                    found = bounds(field(range, "height"), context);
                    if (found == null) {
                        return null;
                    }
                } else if (!Y_NEUTRAL.contains(modifier.getClass())) {
                    return null;
                }
            }
            return found;
        }

        /** The inclusive y bounds of a height provider the 1.21.1 classes define, or null when unknown. */
        static int[] bounds(Object provider, WorldGenerationContext context) {
            if (provider instanceof ConstantHeight constant) {
                int y = constant.getValue().resolveY(context);
                return new int[]{y, y};
            }
            if (provider instanceof UniformHeight || provider instanceof TrapezoidHeight
                    || provider instanceof BiasedToBottomHeight || provider instanceof VeryBiasedToBottomHeight) {
                Object min = field(provider, "minInclusive");
                Object max = field(provider, "maxInclusive");
                if (min instanceof VerticalAnchor a && max instanceof VerticalAnchor b) {
                    int lo = a.resolveY(context);
                    int hi = b.resolveY(context);
                    return new int[]{Math.min(lo, hi), Math.max(lo, hi)};
                }
            }
            return null;
        }

        /** A private field of a 1.21.1 class, or null when it cannot be read (the caller then widens). */
        static Object field(Object owner, String name) {
            try {
                Field f = owner.getClass().getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    }

    /** The layer's overworld biome source, sampled with an explicit seed (Paper's world seed). */
    private static RandomState randomState;
    private static long randomStateSeed = Long.MIN_VALUE;

    /**
     * {@code biomes TAB cx TAB cz TAB seed} — the modded biome of every 4x4x4 cell of a
     * chunk, computed from the layer's generator with Paper's world seed. The plugin
     * writes the answer into the Paper chunk ({@code LimitedRegion.setBiome}), so the
     * chunk carries real modded biome ids and the client renders them.
     */
    private static String biomes(String[] head) {
        MinecraftServer real = realServer();
        if (real == null) {
            return "wait\n";
        }
        int cx = Integer.parseInt(head[1]);
        int cz = Integer.parseInt(head[2]);
        long seed = Long.parseLong(head[3]);
        synchronized (LOCK) {
            ServerLevel overworld = real.overworld();
            ChunkGenerator generator = overworld.getChunkSource().getGenerator();
            if (!(generator instanceof NoiseBasedChunkGenerator noise)) {
                return "error\tgenerator is " + generator.getClass().getSimpleName() + "\n";
            }
            if (randomState == null || randomStateSeed != seed) {
                // The plugin sent the world seed with the list request; if this
                // arrives first the chunk simply has no biome map yet.
                return "wait\n";
            }
            Climate.Sampler sampler = randomState.sampler();
            BiomeSource source = generator.getBiomeSource();
            Registry<Biome> registry = overworld.registryAccess().registryOrThrow(Registries.BIOME);
            int worldMin = overworld.getMinBuildHeight();
            int worldMax = overworld.getMaxBuildHeight();
            int quartMin = worldMin >> 2;
            int quartCount = ((worldMax - 1) >> 2) - quartMin + 1;
            List<String> palette = new ArrayList<>();
            Map<String, Integer> ids = new HashMap<>();
            int[][] columns = new int[16][quartCount];
            for (int qz = 0; qz < 4; qz++) {
                for (int qx = 0; qx < 4; qx++) {
                    int[] column = columns[qz * 4 + qx];
                    for (int qy = 0; qy < quartCount; qy++) {
                        Holder<Biome> biome = source.getNoiseBiome(cx * 4 + qx, quartMin + qy, cz * 4 + qz, sampler);
                        ResourceLocation id = registry.getKey(biome.value());
                        String key = id == null ? "minecraft:plains" : id.toString();
                        Integer index = ids.get(key);
                        if (index == null) {
                            index = palette.size();
                            ids.put(key, index);
                            palette.add(key);
                        }
                        column[qy] = index;
                    }
                }
            }
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < palette.size(); i++) {
                out.append("bpal\t").append(i).append('\t').append(palette.get(i)).append('\n');
            }
            for (int qz = 0; qz < 4; qz++) {
                for (int qx = 0; qx < 4; qx++) {
                    out.append("bio\t").append(qx).append('\t').append(qz).append('\t')
                            .append(runs(columns[qz * 4 + qx])).append('\n');
                }
            }
            return out.toString();
        }
    }

    /** Vanilla {@code /locate biome} steps: ServerLevel.findClosestBiome3d(pos, 6400, 32, 64). */
    private static final int LOCATE_HORIZONTAL_STEP = 32;
    private static final int LOCATE_VERTICAL_STEP = 64;
    /** A locate gives up after this long; the answer is then "none" (not found). */
    private static final long LOCATE_BUDGET_NANOS = 30_000_000_000L;

    /** Thrown from inside the biome predicate to stop the search at the time budget. */
    private static final class LocateTimeout extends RuntimeException {
        LocateTimeout() {
            super(null, null, false, false);
        }
    }

    /**
     * {@code locate TAB biomeId TAB x TAB z TAB radius TAB seed} — the nearest
     * position of a modded biome, from the same biome source the chunk map uses.
     * The plugin intercepts {@code /locate biome <modded id>} and answers with
     * this, because the vanilla command searches the generator's vanilla source.
     *
     * <p>Runs on the caller's thread (the plugin calls it from an async task, never
     * from Paper's main thread). The lock is held only to read the sampler and the
     * world, NOT during the search: a search under {@link #LOCK} would stall every
     * {@code biomes} call of the chunk threads. The sampler is the shared one that
     * chunk generation also reads from several threads, so the search reads it the
     * same way.
     */
    private static String locate(String[] head) {
        MinecraftServer real = realServer();
        if (real == null) {
            return "wait\n";
        }
        String id = head[1];
        int x = Integer.parseInt(head[2]);
        int z = Integer.parseInt(head[3]);
        int radius = Integer.parseInt(head[4]);
        long seed = Long.parseLong(head[5]);
        ServerLevel overworld;
        BiomeSource source;
        RandomState state;
        Registry<Biome> registry;
        synchronized (LOCK) {
            overworld = real.overworld();
            ChunkGenerator generator = overworld.getChunkSource().getGenerator();
            if (!(generator instanceof NoiseBasedChunkGenerator)) {
                return "error\tgenerator is " + generator.getClass().getSimpleName() + "\n";
            }
            if (randomState == null || randomStateSeed != seed) {
                return "wait\n";
            }
            source = generator.getBiomeSource();
            state = randomState;
            registry = overworld.registryAccess().registryOrThrow(Registries.BIOME);
        }
        ResourceLocation want = ResourceLocation.parse(id);
        long deadline = System.nanoTime() + LOCATE_BUDGET_NANOS;
        com.mojang.datafixers.util.Pair<BlockPos, Holder<Biome>> found;
        try {
            found = source.findClosestBiome3d(
                    new BlockPos(x, 63, z), radius, LOCATE_HORIZONTAL_STEP, LOCATE_VERTICAL_STEP,
                    holder -> {
                        if (System.nanoTime() - deadline > 0) {
                            throw new LocateTimeout();
                        }
                        return want.equals(registry.getKey(holder.value()));
                    },
                    state.sampler(), overworld);
        } catch (LocateTimeout timeout) {
            return "none\n";
        }
        if (found == null) {
            return "none\n";
        }
        BlockPos at = found.getFirst();
        return "found\t" + at.getX() + "\t" + at.getZ() + "\n";
    }

    /** Run-length form of one biome column: {@code i:n,i:n,...}. */
    private static String runs(int[] column) {
        StringBuilder sb = new StringBuilder();
        int at = 0;
        while (at < column.length) {
            int value = column[at];
            int end = at + 1;
            while (end < column.length && column[end] == value) {
                end++;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(value).append(':').append(end - at);
            at = end;
        }
        return sb.toString();
    }

    // ---- decorate -----------------------------------------------------------------------------------

    private static String decorate(String[] head, String[] lines) {
        MinecraftServer real = realServer();
        if (real == null) {
            return "wait\n";
        }
        int cx = Integer.parseInt(head[1]);
        int cz = Integer.parseInt(head[2]);
        long seed = Long.parseLong(head[3]);
        int snapMin = Integer.parseInt(head[4]);
        int snapMax = Integer.parseInt(head[5]);
        int worldMin = Integer.parseInt(head[6]);
        int worldMax = Integer.parseInt(head[7]);
        ServerLevel overworld = real.overworld();
        if (worldMin != overworld.getMinBuildHeight() || worldMax != overworld.getMaxBuildHeight()) {
            return "error\tbuild height " + worldMin + ".." + worldMax + " is not the overworld's\n";
        }
        if (snapMin < worldMin || snapMax > worldMax || snapMin >= snapMax
                || (snapMin - worldMin) % SECTION != 0 || (snapMax - worldMin) % SECTION != 0) {
            return "error\tsnapshot " + snapMin + ".." + snapMax + " is not a section range of the world\n";
        }
        int height = snapMax - snapMin;
        synchronized (LOCK) {
            RegistryAccess access = real.registryAccess();
            Plan p = plan(real);
            int quartMin = worldMin >> 2;
            int quartCount = ((worldMax - 1) >> 2) - quartMin + 1;
            if (grid.length < 256 * height) {
                grid = new short[256 * height];
            }
            if (biomeGrid.length < 16 * quartCount) {
                biomeGrid = new short[16 * quartCount];
            }
            List<BlockState> table = new ArrayList<>();
            List<Holder<Biome>> biomeTable = new ArrayList<>();
            int columns = 0;
            int quartColumns = 0;
            for (int i = 1; i < lines.length; i++) {
                String[] f = lines[i].split("\t", -1);
                switch (f[0]) {
                    case "mat" -> table.add(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(f[2])).defaultBlockState());
                    case "bpal" -> biomeTable.add(biome(access, f[2]));
                    case "col" -> {
                        int base = (Integer.parseInt(f[2]) * 16 + Integer.parseInt(f[1])) * height;
                        int written = fillRuns(f[3], grid, base, height);
                        if (written != height) {
                            return "error\tcolumn " + f[1] + "," + f[2] + " has " + written + " of " + height + " blocks\n";
                        }
                        columns++;
                    }
                    case "bio" -> {
                        int base = (Integer.parseInt(f[2]) * 4 + Integer.parseInt(f[1])) * quartCount;
                        int written = fillRuns(f[3], biomeGrid, base, quartCount);
                        if (written != quartCount) {
                            return "error\tbiome column " + f[1] + "," + f[2] + " has " + written + " of " + quartCount + "\n";
                        }
                        quartColumns++;
                    }
                    default -> {
                    }
                }
            }
            if (columns != 256 || quartColumns != 16) {
                return "error\tincomplete chunk: " + columns + " columns, " + quartColumns + " biome columns\n";
            }

            Region region = new Region(real, overworld, access, seed, cx, cz, worldMin, worldMax, snapMin, snapMax,
                    quartMin, quartCount, grid, table, biomeGrid, biomeTable.toArray(new Holder[0]));
            WorldGenLevel level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                    new Class<?>[]{WorldGenLevel.class}, region);
            ChunkGenerator generator = overworld.getChunkSource().getGenerator();
            WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
            long decoration = random.setDecorationSeed(seed, cx * 16, cz * 16);
            BlockPos origin = new BlockPos(cx * 16, worldMin, cz * 16);

            Set<Holder<Biome>> here = new HashSet<>();
            for (int q = 0; q < 16 * quartCount; q++) {
                Holder<Biome> b = region.biomeAt(q);
                if (b != null && p.possible.contains(b)) {
                    here.add(b);
                }
            }

            int placed = 0;
            Map<String, Integer> failed = new TreeMap<>();
            for (int k = 0; k < p.steps.size(); k++) {
                TreeSet<Integer> ids = new TreeSet<>();
                for (Holder<Biome> b : here) {
                    List<HolderSet<PlacedFeature>> sets = b.value().getGenerationSettings().features();
                    if (k < sets.size()) {
                        for (Holder<PlacedFeature> h : sets.get(k)) {
                            Integer i = p.index.get(k).get(h.value());
                            if (i != null && p.runnable.contains(h.value())) {
                                ids.add(i);
                            }
                        }
                    }
                }
                for (int i : ids) {
                    PlacedFeature feature = p.steps.get(k).features().get(i);
                    random.setFeatureSeed(decoration, i, k);
                    try {
                        if (feature.placeWithBiomeCheck(level, generator, random, origin)) {
                            placed++;
                        }
                    } catch (RuntimeException e) {
                        failed.merge(e.getClass().getSimpleName(), 1, Integer::sum);
                    }
                }
            }

            StringBuilder out = new StringBuilder();
            region.emitChanges(out);
            for (Map.Entry<String, Integer> e : failed.entrySet()) {
                out.append("fail\t").append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            }
            out.append("stat\t").append(placed).append('\t').append(region.clipped).append('\t')
                    .append(region.blockEntities).append('\t').append(region.emitted).append('\t')
                    .append(region.outsideWindow).append('\t').append(region.partialHeight).append('\n');
            return out.toString();
        }
    }

    /** Fills {@code target} from {@code base} with the runs {@code i:n,...}; returns the cells written. */
    private static int fillRuns(String runs, short[] target, int base, int length) {
        int at = 0;
        for (String run : runs.split(",")) {
            int colon = run.indexOf(':');
            short index = Short.parseShort(run.substring(0, colon));
            int count = Integer.parseInt(run.substring(colon + 1));
            if (at + count > length) {
                throw new IllegalArgumentException("run past the column end");
            }
            Arrays.fill(target, base + at, base + at + count, index);
            at += count;
        }
        return at;
    }

    private static Holder<Biome> biome(RegistryAccess access, String id) {
        return access.lookupOrThrow(Registries.BIOME)
                .get(ResourceKey.create(Registries.BIOME, ResourceLocation.parse(id))).orElse(null);
    }

    /** A block state as the Paper side parses it: {@code minecraft:oak_log[axis=y]}, the mod id bare. */
    private static String stateString(BlockState state) {
        StringBuilder sb = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        if (!state.getValues().isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (Map.Entry<Property<?>, Comparable<?>> e : state.getValues().entrySet()) {
                sb.append(first ? "" : ",").append(e.getKey().getName()).append('=').append(valueName(e));
                first = false;
            }
            sb.append(']');
        }
        return sb.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueName(Map.Entry<Property<?>, Comparable<?>> e) {
        return ((Property) e.getKey()).getName((Comparable) e.getValue());
    }

    /**
     * The chunk being decorated, as the features see a level: a {@link WorldGenLevel} whose blocks, biomes
     * and heights are the snapshot plus what the features placed. The snapshot is kept in two forms: the
     * grids (cheap, used while no feature asks for a chunk object) and, once a feature asks for the
     * chunk ({@code getChunk}: Create's layered ore reads and writes its sections through the level), a
     * {@link ProtoChunk} filled section by section from the snapshot's sections only. The truth is then the
     * chunk object; the changes are read back from its sections (and any section outside the window that
     * a feature wrote into). Only the methods a placed feature uses are answered; any other abstract method
     * throws, the feature is counted as failed, and the rest of the chunk runs.
     */
    private static final class Region implements InvocationHandler {
        final MinecraftServer server;
        final ServerLevel overworld;
        final RegistryAccess access;
        final long seed;
        final int chunkX;
        final int chunkZ;
        final int blockX;
        final int blockZ;
        /** The world's build range: what the level answers for getMinBuildHeight and friends. */
        final int worldMin;
        final int worldMax;
        /** The snapshot's range (section-aligned): the cells the grid holds. */
        final int snapMin;
        final int snapMax;
        final int quartMin;
        final int quartCount;
        final short[] grid;
        final List<BlockState> table;
        final short[] biomeGrid;
        final Holder<Biome>[] biomes;
        final BitSet dirty = new BitSet();
        final Map<BlockState, Short> placedIndex = new HashMap<>();
        final Map<Long, ChunkAccess> neighbours = new HashMap<>();
        final int[][] heights = new int[Heightmap.Types.values().length][];
        final RandomSource random = RandomSource.create(0L);
        /** The chunk as a chunk object, once a feature asked for it; null while the grids hold the truth. */
        ChunkAccess proto;
        int clipped;
        int blockEntities;
        int emitted;
        int outsideWindow;
        int partialHeight;

        Region(MinecraftServer server, ServerLevel overworld, RegistryAccess access, long seed, int chunkX, int chunkZ,
               int worldMin, int worldMax, int snapMin, int snapMax, int quartMin, int quartCount, short[] grid,
               List<BlockState> table, short[] biomeGrid, Holder<Biome>[] biomes) {
            this.server = server;
            this.overworld = overworld;
            this.access = access;
            this.seed = seed;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.blockX = chunkX * 16;
            this.blockZ = chunkZ * 16;
            this.worldMin = worldMin;
            this.worldMax = worldMax;
            this.snapMin = snapMin;
            this.snapMax = snapMax;
            this.quartMin = quartMin;
            this.quartCount = quartCount;
            this.grid = grid;
            this.table = table;
            this.biomeGrid = biomeGrid;
            this.biomes = biomes;
        }

        boolean inside(int x, int z) {
            return x >= blockX && x < blockX + 16 && z >= blockZ && z < blockZ + 16;
        }

        boolean inSnapshot(int y) {
            return y >= snapMin && y < snapMax;
        }

        boolean partial() {
            return snapMin > worldMin || snapMax < worldMax;
        }

        /** The grid cell of an in-chunk position inside the snapshot window. */
        int cell(int x, int y, int z) {
            return ((z - blockZ) * 16 + (x - blockX)) * (snapMax - snapMin) + (y - snapMin);
        }

        BlockState stateAt(int x, int y, int z) {
            if (y < worldMin || y >= worldMax || !inside(x, z)) {
                return Blocks.AIR.defaultBlockState();
            }
            if (proto != null) {
                return proto.getBlockState(new BlockPos(x, y, z));
            }
            if (!inSnapshot(y)) {
                return Blocks.AIR.defaultBlockState();
            }
            return table.get(grid[cell(x, y, z)]);
        }

        /** The chunk object of the populated chunk: made from the snapshot's sections on first use, then the truth. */
        ChunkAccess chunk() {
            if (proto == null) {
                proto = new ProtoChunk(new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY, overworld,
                        access.registryOrThrow(Registries.BIOME), null);
                for (int sectionMin = snapMin; sectionMin < snapMax; sectionMin += SECTION) {
                    LevelChunkSection section = proto.getSection(proto.getSectionIndex(sectionMin));
                    for (int ly = 0; ly < SECTION; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            for (int lx = 0; lx < 16; lx++) {
                                BlockState state = table.get(grid[cell(blockX + lx, sectionMin + ly, blockZ + lz)]);
                                if (!state.isAir()) {
                                    section.setBlockState(lx, ly, lz, state);
                                }
                            }
                        }
                    }
                }
            }
            return proto;
        }

        /** A chunk next to the populated one: empty, so its reads are air and its writes are dropped. */
        ChunkAccess neighbour(int x, int z) {
            return neighbours.computeIfAbsent(ChunkPos.asLong(x, z),
                    k -> new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, overworld,
                            access.registryOrThrow(Registries.BIOME), null));
        }

        Holder<Biome> biomeAt(int quartIndex) {
            return biomes[biomeGrid[quartIndex]];
        }

        Holder<Biome> biomeAtQuart(int qx, int qy, int qz) {
            int lx = Math.min(3, Math.max(0, qx - blockX / 4));
            int lz = Math.min(3, Math.max(0, qz - blockZ / 4));
            int q = Math.min(quartCount - 1, Math.max(0, qy - quartMin));
            return biomes[biomeGrid[(lz * 4 + lx) * quartCount + q]];
        }

        boolean setBlock(BlockPos p, BlockState state) {
            int x = p.getX();
            int y = p.getY();
            int z = p.getZ();
            if (y < worldMin || y >= worldMax) {
                return false;
            }
            if (!inside(x, z)) {
                // A write into a neighbour chunk: that chunk is decorated (or not) by its own call.
                clipped++;
                return true;
            }
            if (proto == null && !inSnapshot(y)) {
                // A write outside the window keeps the block: the chunk object (made now) holds it, as the
                // grids have no cell for it. Counted, so the window's margin can be checked from the stats.
                outsideWindow++;
                chunk();
            }
            if (proto != null) {
                proto.getSection(proto.getSectionIndex(y)).setBlockState(x & 15, Math.floorMod(y, SECTION), z & 15, state);
                return true;
            }
            int at = cell(x, y, z);
            Short index = placedIndex.get(state);
            if (index == null) {
                index = (short) table.size();
                table.add(state);
                placedIndex.put(state, index);
            }
            grid[at] = index;
            dirty.set(at);
            return true;
        }

        int height(Heightmap.Types type, int x, int z) {
            if (partial()) {
                partialHeight++;
            }
            int g = type.ordinal();
            if (heights[g] == null) {
                int[] column = new int[256];
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int top = snapMin;
                        for (int y = snapMax - 1; y >= snapMin; y--) {
                            if (type.isOpaque().test(stateAt(blockX + lx, y, blockZ + lz))) {
                                top = y + 1;
                                break;
                            }
                        }
                        column[lz * 16 + lx] = top;
                    }
                }
                heights[g] = column;
            }
            int lx = Math.min(15, Math.max(0, x - blockX));
            int lz = Math.min(15, Math.max(0, z - blockZ));
            return heights[g][lz * 16 + lx];
        }

        /**
         * The changed blocks. On the grids: the dirty cells. Once the chunk object holds the truth: the
         * snapshot's sections compared with the grid (a section write through the chunk object is not a level
         * write, so it leaves no dirty bit), plus every non-air block a feature put into a section outside the
         * window (counted as outsideWindow). Sections outside the window that are still empty are skipped.
         */
        void emitChanges(StringBuilder out) {
            if (proto == null) {
                int height = snapMax - snapMin;
                for (int cell = dirty.nextSetBit(0); cell >= 0; cell = dirty.nextSetBit(cell + 1)) {
                    int column = cell / height;
                    emit(out, column % 16, snapMin + cell % height, column / 16, table.get(grid[cell]));
                }
                return;
            }
            LevelChunkSection[] sections = proto.getSections();
            for (int s = 0; s < sections.length; s++) {
                int sectionMin = worldMin + s * SECTION;
                LevelChunkSection section = sections[s];
                boolean inWindow = sectionMin >= snapMin && sectionMin + SECTION <= snapMax;
                if (!inWindow && section.hasOnlyAir()) {
                    continue;
                }
                for (int ly = 0; ly < SECTION; ly++) {
                    int y = sectionMin + ly;
                    for (int lz = 0; lz < 16; lz++) {
                        for (int lx = 0; lx < 16; lx++) {
                            BlockState now = section.getBlockState(lx, ly, lz);
                            if (inWindow) {
                                if (now == table.get(grid[cell(blockX + lx, y, blockZ + lz)])) {
                                    continue;
                                }
                            } else {
                                if (now.isAir()) {
                                    continue;
                                }
                                outsideWindow++;
                            }
                            emit(out, lx, y, lz, now);
                        }
                    }
                }
            }
        }

        private void emit(StringBuilder out, int lx, int y, int lz, BlockState state) {
            if (state.hasBlockEntity()) {
                blockEntities++;
                return;
            }
            out.append("set\t").append(lx).append('\t').append(y).append('\t').append(lz).append('\t')
                    .append(stateString(state)).append('\n');
            emitted++;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] a) throws Throwable {
            String name = m.getName();
            int argc = m.getParameterCount();
            if (m.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "equals" -> proxy == a[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "worldgen region of chunk " + chunkX + "," + chunkZ;
                };
            }
            if (name.equals("getBlockState") && argc == 1) {
                BlockPos p = (BlockPos) a[0];
                return stateAt(p.getX(), p.getY(), p.getZ());
            }
            if (name.equals("getFluidState") && argc == 1) {
                BlockPos p = (BlockPos) a[0];
                return stateAt(p.getX(), p.getY(), p.getZ()).getFluidState();
            }
            if (name.equals("getBlockEntity") && argc == 1) {
                return null;
            }
            if (name.equals("setBlock") && argc == 4) {
                return setBlock((BlockPos) a[0], (BlockState) a[1]);
            }
            if (name.equals("getChunk") && argc == 4) {
                int x = (Integer) a[0];
                int z = (Integer) a[1];
                return x == chunkX && z == chunkZ ? chunk() : neighbour(x, z);
            }
            if (name.equals("hasChunk") && argc == 2) {
                return true;
            }
            if (name.equals("ensureCanWrite") && argc == 1) {
                BlockPos p = (BlockPos) a[0];
                return p.getY() >= worldMin && p.getY() < worldMax;
            }
            if (name.equals("getHeight") && argc == 3) {
                return height((Heightmap.Types) a[0], (Integer) a[1], (Integer) a[2]);
            }
            if (name.equals("getHeight") && argc == 0) {
                return worldMax - worldMin;
            }
            if (name.equals("getMinBuildHeight") && argc == 0) {
                return worldMin;
            }
            if (name.equals("getMaxBuildHeight") && argc == 0) {
                return worldMax;
            }
            if (name.equals("getSeed") && argc == 0) {
                return seed;
            }
            if (name.equals("getSeaLevel") && argc == 0) {
                return 63;
            }
            if (name.equals("isClientSide") && argc == 0) {
                return false;
            }
            if (name.equals("getRandom") && argc == 0) {
                return random;
            }
            if (name.equals("getBiome") && argc == 1) {
                BlockPos p = (BlockPos) a[0];
                return biomeAtQuart(p.getX() >> 2, p.getY() >> 2, p.getZ() >> 2);
            }
            if ((name.equals("getNoiseBiome") || name.equals("getUncachedNoiseBiome")) && argc == 3) {
                return biomeAtQuart((Integer) a[0], (Integer) a[1], (Integer) a[2]);
            }
            if (name.equals("registryAccess") && argc == 0) {
                return access;
            }
            if (name.equals("dimensionType") && argc == 0) {
                return overworld.dimensionType();
            }
            if (name.equals("getServer") && argc == 0) {
                return server;
            }
            if (name.equals("setCurrentlyGenerating")) {
                return null;
            }
            if (m.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, m, a);
            }
            throw new UnsupportedOperationException("worldgen region does not answer " + name);
        }
    }
}
