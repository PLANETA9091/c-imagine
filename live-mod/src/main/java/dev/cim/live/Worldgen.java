package dev.cim.live;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
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
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.BiomeModifiers;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

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
 * out: ready TAB features TAB modifiers TAB skipped TAB reasons
 *
 * in:  decorate TAB cx TAB cz TAB seed TAB minY TAB maxY   then:
 *        mat TAB i TAB blockId                        the block palette of the chunk (i in order)
 *        bpal TAB i TAB biomeId                       the biome palette (i in order)
 *        col TAB lx TAB lz TAB i:n,i:n,...            one column, bottom up, as runs of palette indices
 *        bio TAB qx TAB qz TAB i:n,...                one 4x4 biome column over the quart rows, as runs
 * out: set TAB lx TAB y TAB lz TAB blockState      a block the features changed in the chunk (local x, z)
 *      fail TAB exception TAB count                 a feature that threw (the chunk keeps the rest)
 *      stat TAB placed TAB clipped TAB blockEntity  the counts (clipped: writes outside the chunk)
 * </pre>
 *
 * <p>Parity: the decoration seed and each feature's seed are the vanilla ones ({@code
 * ChunkGenerator.applyBiomeDecoration}: the world seed, the chunk origin, the feature's index in the
 * step's sorted list, the step), with the feature list sorted by the same {@link FeatureSorter} over the
 * vanilla overworld biome source's biomes. Deviations, all named in the design notes: only the mod
 * features run (no vanilla ones), writes outside the chunk are dropped and reads outside it see air
 * (in-chunk results do not depend on that for the ores, whose tests are per target block), heights of
 * the columns outside the chunk are the chunk's edge columns, and the snapshot is the chunk's own blocks
 * before any other populator ran.
 *
 * <p>Thread safety: the plan is built once under {@link #LOCK}, and each decorate builds its own
 * facade and state from the call's own snapshot. The lock serializes the decorations of Paper's
 * worldgen threads against each other (the mod code's own thread-safety is not documented, so it is not
 * relied on), and it is never the lock of {@code LiveProtocol.call}, so the main thread's tick does not
 * wait for a chunk.
 */
public final class Worldgen {

    public static final String KEY = "cim.worldgen";

    /** Serializes worldgen calls among themselves only. Never held by the main thread's protocol. */
    private static final Object LOCK = new Object();

    /** The boot plan, built once from the real server's registries. Guarded by {@link #LOCK}. */
    private static Plan plan;

    /** Reused across decorations (the lock serializes them): the chunk's block and biome grids. */
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
                case "list" -> list();
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
        return real;
    }

    private static String list() {
        MinecraftServer real = realServer();
        if (real == null) {
            return "wait\n";
        }
        synchronized (LOCK) {
            return plan(real.registryAccess()).listing + "\n";
        }
    }

    /** The plan, built on first use. Caller holds {@link #LOCK}. */
    private static Plan plan(RegistryAccess access) {
        if (plan == null) {
            plan = Plan.build(access);
        }
        return plan;
    }

    /** One boot plan: the sorted feature lists of the overworld, and the mod features the decorate runs. */
    private record Plan(List<FeatureSorter.StepFeatureData> steps, List<Map<PlacedFeature, Integer>> index,
                        Set<Holder<Biome>> possible, Set<PlacedFeature> runnable, String listing) {

        /** A mod feature the listing skips, by a Java class name heuristic: structure or entity code. */
        private static final String[][] SKIP_MARKERS = {
                {"Structure", "structure"}, {"Jigsaw", "structure"}, {"Spawner", "entity"}, {"Entity", "entity"}};

        static Plan build(RegistryAccess access) {
            ResourceKey<MultiNoiseBiomeSourceParameterList> key = ResourceKey.create(
                    Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST, ResourceLocation.withDefaultNamespace("overworld"));
            Holder<MultiNoiseBiomeSourceParameterList> preset = access.lookupOrThrow(
                    Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(key);
            // The vanilla overworld's biomes, in the order its generator lists them (FeatureSorter's input).
            Set<Holder<Biome>> possible = MultiNoiseBiomeSource.createFromPreset(preset).possibleBiomes();
            List<Holder<Biome>> biomes = List.copyOf(possible);
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

            String reasons = skipped.isEmpty() ? "none" : String.join(",", skipped.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).toList());
            String listing = "ready\t" + runnable.size() + "\t" + modifiers + "\t" + (mods.size() - runnable.size())
                    + "\t" + reasons;
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
        int minY = Integer.parseInt(head[4]);
        int maxY = Integer.parseInt(head[5]);
        int height = maxY - minY;
        if (height <= 0 || height > 1024) {
            return "error\tbuild height " + minY + ".." + maxY + " out of range\n";
        }
        synchronized (LOCK) {
            RegistryAccess access = real.registryAccess();
            Plan p = plan(access);
            int quartMin = minY >> 2;
            int quartCount = ((maxY - 1) >> 2) - quartMin + 1;
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

            ServerLevel overworld = real.overworld();
            Region region = new Region(real, overworld, access, seed, cx, cz, minY, maxY, quartMin, quartCount,
                    grid, table, biomeGrid, biomeTable.toArray(new Holder[0]));
            WorldGenLevel level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                    new Class<?>[]{WorldGenLevel.class}, region);
            ChunkGenerator generator = overworld.getChunkSource().getGenerator();
            WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
            long decoration = random.setDecorationSeed(seed, cx * 16, cz * 16);
            BlockPos origin = new BlockPos(cx * 16, minY, cz * 16);

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
                    .append(region.blockEntities).append('\t').append(region.emitted).append('\n');
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
     * {@link ProtoChunk} that then holds the truth. Only the methods a placed feature uses are answered;
     * any other abstract method throws, the feature is counted as failed, and the rest of the chunk runs.
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
        final int minY;
        final int maxY;
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
        /** The grid as it stood when {@link #proto} was made: the base of the changes read back. */
        short[] base;
        int clipped;
        int blockEntities;
        int emitted;

        Region(MinecraftServer server, ServerLevel overworld, RegistryAccess access, long seed, int chunkX, int chunkZ,
               int minY, int maxY, int quartMin, int quartCount, short[] grid, List<BlockState> table,
               short[] biomeGrid, Holder<Biome>[] biomes) {
            this.server = server;
            this.overworld = overworld;
            this.access = access;
            this.seed = seed;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.blockX = chunkX * 16;
            this.blockZ = chunkZ * 16;
            this.minY = minY;
            this.maxY = maxY;
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

        /** The grid cell of an in-chunk position (y inside the build range). */
        int cell(int x, int y, int z) {
            return ((z - blockZ) * 16 + (x - blockX)) * (maxY - minY) + (y - minY);
        }

        BlockState stateAt(int x, int y, int z) {
            if (y < minY || y >= maxY || !inside(x, z)) {
                return Blocks.AIR.defaultBlockState();
            }
            if (proto != null) {
                return proto.getBlockState(new BlockPos(x, y, z));
            }
            return table.get(grid[cell(x, y, z)]);
        }

        /** The chunk object of the populated chunk: made from the grid on first use, then the truth. */
        ChunkAccess chunk() {
            if (proto == null) {
                proto = new ProtoChunk(new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY, overworld,
                        access.registryOrThrow(Registries.BIOME), null);
                base = grid.clone();
                BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
                for (int z = blockZ; z < blockZ + 16; z++) {
                    for (int x = blockX; x < blockX + 16; x++) {
                        for (int y = minY; y < maxY; y++) {
                            BlockState state = table.get(grid[cell(x, y, z)]);
                            if (!state.isAir()) {
                                proto.setBlockState(at.set(x, y, z), state, false);
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
            if (p.getY() < minY || p.getY() >= maxY) {
                return false;
            }
            if (!inside(p.getX(), p.getZ())) {
                // A write into a neighbour chunk: that chunk is decorated (or not) by its own call.
                clipped++;
                return true;
            }
            if (proto != null) {
                proto.setBlockState(p, state, false);
            } else {
                int at = cell(p.getX(), p.getY(), p.getZ());
                Short index = placedIndex.get(state);
                if (index == null) {
                    index = (short) table.size();
                    table.add(state);
                    placedIndex.put(state, index);
                }
                grid[at] = index;
            }
            dirty.set(cell(p.getX(), p.getY(), p.getZ()));
            return true;
        }

        int height(Heightmap.Types type, int x, int z) {
            int g = type.ordinal();
            if (heights[g] == null) {
                int[] column = new int[256];
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int top = minY;
                        for (int y = maxY - 1; y >= minY; y--) {
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
         * The changed blocks, in cell order (column by column, bottom up): the dirty cells on the grids, or
         * the whole chunk compared with its base once the chunk object holds the truth (a section write
         * through the chunk object is not a level write, so it leaves no dirty bit).
         */
        void emitChanges(StringBuilder out) {
            if (proto == null) {
                for (int cell = dirty.nextSetBit(0); cell >= 0; cell = dirty.nextSetBit(cell + 1)) {
                    emitCell(out, cell, table.get(grid[cell]));
                }
                return;
            }
            BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
            int cell = 0;
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int y = minY; y < maxY; y++, cell++) {
                        BlockState now = proto.getBlockState(at.set(blockX + lx, y, blockZ + lz));
                        if (now != table.get(base[cell])) {
                            emitCell(out, cell, now);
                        }
                    }
                }
            }
        }

        private void emitCell(StringBuilder out, int cell, BlockState state) {
            if (state.hasBlockEntity()) {
                blockEntities++;
                return;
            }
            int height = maxY - minY;
            int column = cell / height;
            out.append("set\t").append(column % 16).append('\t').append(minY + cell % height).append('\t')
                    .append(column / 16).append('\t').append(stateString(state)).append('\n');
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
                return p.getY() >= minY && p.getY() < maxY;
            }
            if (name.equals("getHeight") && argc == 3) {
                return height((Heightmap.Types) a[0], (Integer) a[1], (Integer) a[2]);
            }
            if (name.equals("getHeight") && argc == 0) {
                return maxY - minY;
            }
            if (name.equals("getMinBuildHeight") && argc == 0) {
                return minY;
            }
            if (name.equals("getMaxBuildHeight") && argc == 0) {
                return maxY;
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
