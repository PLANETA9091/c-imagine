import com.simibubi.create.content.decoration.palettes.ConnectedPillarBlock;
import com.simibubi.create.content.decoration.palettes.LayeredBlock;
import com.simibubi.create.foundation.block.connected.AllCTTypes;
import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import com.simibubi.create.foundation.block.connected.CTType;
import com.simibubi.create.foundation.block.connected.ConnectedTextureBehaviour;
import com.simibubi.create.foundation.block.connected.RotatedPillarCTBehaviour;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 14-ak: run the REAL create 6.0.10 RotatedPillarCTBehaviour (buildContext)
 * + the REAL AllCTTypes index formulas against synthetic gallery
 * neighbourhoods. The python port (gallery_ct.py) computes the same cases;
 * the runner diffs — the port is verified against the real classes at the
 * context layer (the UV math is separately verified: catnip fraction +
 * the real TextureAtlasSprite.getU bytecode, both measured 14-ak).
 *
 * Classpath: the nfclient 1.21.1 mojmap libraries + create-1.21.1-6.0.10.jar
 * + the ponder jar (catnip Lang/Iterate for the create classes' static init).
 *
 * Input case line (TSV): center-state<TAB>dx,dy,dz=state;dx,dy,dz=state;...
 * The center sits at (0,0,0). Output: 6 tab-separated indices in the
 * Direction.values() order (down,up,north,south,west,east).
 */
public final class CTProbe {

    /** The neutral filler state: a plain unregistered block, built AFTER
     *  Bootstrap.bootStrap() in main. Its verdict convergence with air is
     *  proven in 14-ak (a different block fails connectsTo either way; a
     *  full-face blocking pos falls through to the connectsTo recursion on
     *  a non-pillar -> false — identical context). */
    static BlockState FILLER;

    static final class World implements BlockAndTintGetter {
        final Map<BlockPos, BlockState> cells = new HashMap<>();
        final Map<String, ConnectedPillarBlock> pillarByStone = new HashMap<>();

        World() {
            // ONE ConnectedPillarBlock INSTANCE PER STONE — the CT
            // connectsTo gates on BLOCK IDENTITY, so a single shared
            // instance would blind the gate across stones (the 14-ak
            // probe bug: tuff connected to ochrum)
            for (String stone : new String[]{"andesite", "asurine",
                    "calcite", "crimsite", "deepslate", "diorite",
                    "dripstone", "granite", "limestone", "ochrum",
                    "scorchia", "scoria", "tuff", "veridium"}) {
                pillarByStone.put(stone, new ConnectedPillarBlock(
                        BlockBehaviour.Properties.of()));
            }
        }

        BlockState stateOf(String state) {
            String block = state.substring(0, state.indexOf('['));
            String stone = block.substring(block.indexOf(':') + 1,
                    block.length() - "_pillar".length());
            ConnectedPillarBlock pb = pillarByStone.get(stone);
            if (pb == null) {
                throw new IllegalArgumentException("stone " + stone);
            }
            String props = state.substring(state.indexOf('[') + 1,
                    state.length() - 1);
            BlockState s = pb.defaultBlockState();
            for (String pair : props.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = pair.substring(0, eq).trim();
                String v = pair.substring(eq + 1).trim();
                switch (k) {
                    case "axis" -> s = s.setValue(BlockStateProperties.AXIS,
                            Axis.valueOf(v.toUpperCase(Locale.ROOT)));
                    case "north" -> s = s.setValue(ConnectedPillarBlock.NORTH,
                            Boolean.parseBoolean(v));
                    case "south" -> s = s.setValue(ConnectedPillarBlock.SOUTH,
                            Boolean.parseBoolean(v));
                    case "east" -> s = s.setValue(ConnectedPillarBlock.EAST,
                            Boolean.parseBoolean(v));
                    case "west" -> s = s.setValue(ConnectedPillarBlock.WEST,
                            Boolean.parseBoolean(v));
                    default -> throw new IllegalArgumentException("prop " + k);
                }
            }
            return s;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return cells.getOrDefault(pos, FILLER);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }

        @Override
        public int getBrightness(LightLayer type, BlockPos pos) {
            return 15;
        }

        @Override
        public int getBlockTint(BlockPos pos,
                                net.minecraft.world.level.ColorResolver resolver) {
            return 0xffffffff;
        }

        @Override
        public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() {
            throw new UnsupportedOperationException("probe stub");
        }

        @Override
        public float getShade(Direction direction, boolean shade) {
            return 1.0f;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

    }

    private CTProbe() {
    }

    public static void main(String[] args) throws Exception {
        // The NeoForge-patched bootStrap aborts at the FeatureFlags/FML
        // integration (no FML run here); everything the probe needs — the
        // registries, SoundType, the property constants — is initialised
        // BEFORE that abort point. The datafixer schema validation is the
        // other headless-unfriendly step — disabled for the probe.
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // the abort point (FeatureFlagLoader / fetchChoiceType) is past
            // everything the CT path touches — measured 14-ak
        }
        try {
            java.lang.reflect.Field f = SharedConstants.class
                    .getDeclaredField("CHECK_DATA_FIXER_SCHEMA");
            f.setAccessible(true);
            f.setBoolean(null, false);
        } catch (ReflectiveOperationException ignored) {
            // the flag may already be false
        }
        FILLER = new Block(BlockBehaviour.Properties.of()).defaultBlockState();
        String in = args[0];
        String out = args[1];
        boolean debug = args.length > 2 && "debug".equals(args[2]);
        World probeWorldCtor = null; // the per-stone blocks live in World
        RotatedPillarCTBehaviour behaviour = new RotatedPillarCTBehaviour(
                new CTSpriteShiftEntry(AllCTTypes.RECTANGLE),
                new CTSpriteShiftEntry(AllCTTypes.OMNIDIRECTIONAL));
        Direction[] faces = Direction.values(); // D,U,N,S,W,E order

        try (BufferedReader r = new BufferedReader(new FileReader(in));
             PrintWriter w = new PrintWriter(out)) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t");
                World world = new World();
                String center = parts[0];
                if (parts.length > 1 && !parts[1].isEmpty()) {
                    for (String nb : parts[1].split(";")) {
                        if (nb.isEmpty()) {
                            continue;
                        }
                        int eq = nb.indexOf('=');
                        String[] xyz = nb.substring(0, eq).split(",");
                        world.cells.put(new BlockPos(Integer.parseInt(xyz[0]),
                                        Integer.parseInt(xyz[1]),
                                        Integer.parseInt(xyz[2])),
                                world.stateOf(nb.substring(eq + 1)));
                    }
                }
                BlockState centerState = world.stateOf(center);
                StringBuilder sb = new StringBuilder();
                Axis axis = centerState.getValue(LayeredBlock.AXIS);
                for (Direction f : faces) {
                    CTType type = f.getAxis() == axis
                            ? AllCTTypes.OMNIDIRECTIONAL : AllCTTypes.RECTANGLE;
                    ConnectedTextureBehaviour.CTContext ctx = behaviour.buildContext(
                            world, BlockPos.ZERO, centerState, f,
                            type.getContextRequirement());
                    if (debug) {
                        w.println(f + " " + type + " up=" + ctx.up + " down="
                                + ctx.down + " left=" + ctx.left + " right="
                                + ctx.right + " tl=" + ctx.topLeft + " tr="
                                + ctx.topRight + " bl=" + ctx.bottomLeft
                                + " br=" + ctx.bottomRight);
                    }
                    sb.append(type.getTextureIndex(ctx));
                    if (f != Direction.EAST) {
                        sb.append('\t');
                    }
                }
                if (!debug) {
                    w.println(sb);
                }
                n++;
            }
            System.out.println("CTProbe: " + n + " cases");
        }
    }
}
