import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.EntityType;

import java.io.PrintWriter;
import java.nio.file.Path;

/**
 * M4 ground-truth probe: prints the frozen 26.3 network ids the CLIENT also
 * uses (static registries are NOT streamed via registry_data — the client
 * builds them from its own jar, in the same Bootstrap order as the server).
 *
 * Outputs (into <outdir>):
 *   entity-type-777.tsv   id \t key
 *   blockstate-777.tsv    state_id \t block_id \t block_key \t state_string
 *
 * Build+run (JDK 25, paper-26.3.jar FIRST on the classpath — it carries
 * Paper's patched com.mojang.logging.LogUtils):
 *   javac -cp "versions/26.3/paper-26.3.jar:$(find libraries -name '*.jar' | tr '\n' ':')" -d probe-out probe-registry-ids.java
 *   java  -cp "versions/26.3/paper-26.3.jar:$(find libraries -name '*.jar' | tr '\n' ':'):probe-out" Probe probe-out
 */
public class Probe {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : ".");
        // launcher contract: detect the version BEFORE bootStrap (Game version
        // not set otherwise — DataFixers.<clinit> needs it)
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        try (PrintWriter w = new PrintWriter(out.resolve("entity-type-777.tsv").toFile(), "utf-8")) {
            var reg = BuiltInRegistries.ENTITY_TYPE;
            for (EntityType<?> t : reg) {
                Identifier key = reg.getKey(t);
                w.println(reg.getId(t) + "\t" + key);
            }
        }
        try (PrintWriter w = new PrintWriter(out.resolve("blockstate-777.tsv").toFile(), "utf-8")) {
            var blocks = BuiltInRegistries.BLOCK;
            var states = Block.BLOCK_STATE_REGISTRY;
            for (Block b : blocks) {
                int blockId = blocks.getId(b);
                Identifier key = blocks.getKey(b);
                for (BlockState s : b.getStateDefinition().getPossibleStates()) {
                    int sid = states.getId(s);
                    w.println(sid + "\t" + blockId + "\t" + key + "\t" + s);
                }
            }
        }
        System.out.println("probe done: entity_types="
                + BuiltInRegistries.ENTITY_TYPE.size()
                + " block_states=" + Block.BLOCK_STATE_REGISTRY.size());
    }
}
