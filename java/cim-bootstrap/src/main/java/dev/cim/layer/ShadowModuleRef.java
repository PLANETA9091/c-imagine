package dev.cim.layer;

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Real JPMS {@link ModuleLayer} over the shadow loader (M2).
 *
 * <p>{@code FMLModContainer} requires a {@link ModuleLayer}:
 * {@code gameLayer.findModule(info.getOwningFile().moduleName())}, where
 * {@code ModFileInfo.moduleName()} is the <b>mod id</b> of the first mod in
 * the file. This class therefore builds a layer with one module per mod,
 * named by mod id, whose descriptors carry the union of the original and the
 * relocated package set — relocated mod classes are attributed to their
 * module, and {@code Class.forName(layer, name)} resolves them through the
 * shadow loader.
 *
 * <p>Module {@code reads} are total (every mod module requires-transitive
 * every other, mirroring how automatic modules read everyone) so cross-mod
 * API usage resolves. Exports/opens are wide open: JPMS accessibility only
 * gates reflection setAccessible paths, and the real linkage happens
 * loader-level anyway.
 */
public final class ShadowModuleRef {

    private ShadowModuleRef() {
    }

    /**
     * Define the layer.
     *
     * @param names      module names (mod ids), load order
     * @param packagesBy mod id → module package set (internal, slash form)
     * @param jarsBy     mod id → jar path (location only)
     * @param loaderFn   module name → class loader (always the shadow loader)
     */
    public static ModuleLayer defineLayer(List<String> names,
                                          Map<String, Set<String>> packagesBy,
                                          Map<String, String> jarsBy,
                                          Map<String, Map<String, List<String>>> servicesBy,
                                          java.util.function.Function<String, ClassLoader> loaderFn) {
        Map<String, Ref> refs = new LinkedHashMap<>();
        for (String name : names) {
            // AUTOMATIC module: reads every other module AND the unnamed
            // module of the shadow loader (where the layer jars live),
            // exports/opens everything implicitly. Only the package list is
            // declared — it drives class attribution, which
            // Class.forName(module, name) depends on. (Named modules cannot
            // read the unnamed module, so the layer libraries would be
            // inaccessible: IllegalAccessError on the first log4j call.)
            ModuleDescriptor.Builder b = ModuleDescriptor.newAutomaticModule(name);
            Set<String> dotted = new LinkedHashSet<>();
            for (String internal : packagesBy.get(name)) {
                dotted.add(internal.replace('/', '.'));
            }
            b.packages(dotted);
            // Services must be declared in the descriptor: ServiceLoader
            // silently skips classpath providers living in named modules,
            // so providers reach it only through the provides directives.
            Map<String, List<String>> services = servicesBy.get(name);
            if (services != null) {
                for (Map.Entry<String, List<String>> e : services.entrySet()) {
                    // uses() is forbidden on automatic modules (and only a
                    // compile-time directive anyway); provides() is allowed
                    // and is what ServiceLoader consults.
                    b.provides(e.getKey(), e.getValue());
                }
            }
            ModuleDescriptor descriptor = b.build();
            String jar = jarsBy.get(name);
            URI uri = jar == null || jar.isEmpty()
                    ? URI.create("file:/" + name + ".jar")
                    : Path.of(jar).toUri();
            refs.put(name, new Ref(descriptor, uri));
        }
        Finder finder = new Finder(refs.values());
        Configuration config = Configuration.resolveAndBind(
                finder,
                List.of(ModuleLayer.boot().configuration()),
                ModuleFinder.of(),
                new LinkedHashSet<>(names));
        return ModuleLayer.defineModules(config, List.of(ModuleLayer.boot()), loaderFn)
                .layer();
    }

    /** No-op reader: classes resolve through the shadow loader, not the reader. */
    private static final class EmptyReader implements ModuleReader {
        @Override
        public Optional<java.io.InputStream> open(String name) {
            return Optional.empty();
        }

        @Override
        public Optional<URI> find(String name) {
            return Optional.empty();
        }

        @Override
        public void close() {
        }

        @Override
        public java.util.stream.Stream<String> list() {
            return java.util.stream.Stream.empty();
        }
    }

    private static final class Ref extends ModuleReference {
        final ModuleDescriptor descriptor;

        Ref(ModuleDescriptor descriptor, URI uri) {
            super(descriptor, uri);
            this.descriptor = descriptor;
        }

        @Override
        public ModuleReader open() {
            return new EmptyReader();
        }

        @Override
        public String toString() {
            return "cim-shadow-module:" + descriptor.name();
        }
    }

    private static final class Finder implements ModuleFinder {
        private final Map<String, Ref> byName = new LinkedHashMap<>();

        Finder(Iterable<Ref> refs) {
            for (Ref ref : refs) {
                byName.put(ref.descriptor.name(), ref);
            }
        }

        @Override
        public Optional<ModuleReference> find(String name) {
            return Optional.ofNullable(byName.get(name));
        }

        @Override
        public Set<ModuleReference> findAll() {
            return Set.copyOf(byName.values());
        }
    }
}
