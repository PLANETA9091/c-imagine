import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.format.Tiny2Writer;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/**
 * Ground-truth helper: convert Mojang's proguard-format mappings
 * (client.txt / server.txt) into tiny-v2 for tiny-remapper.
 *
 * Usage: java ConvertMappings <proguard-mappings> <output.tiny>
 */
public final class ConvertMappings {

    private ConvertMappings() {
    }

    public static void main(String[] args) throws IOException {
        Path src = Path.of(args[0]);
        Path dst = Path.of(args[1]);
        MemoryMappingTree tree = new MemoryMappingTree();
        // MappingReader detects proguard (Mojang official mappings) natively.
        MappingReader.read(src, MappingFormat.PROGUARD_FILE, tree);
        try (Tiny2Writer writer = new Tiny2Writer(Files.newBufferedWriter(dst), false)) {
            tree.accept(writer);
        }
        System.out.println("converted " + src + " -> " + dst
                + " (" + Files.size(dst) + " bytes)");
    }
}
