package dev.f2v.vm.discovery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.f2v.bridge.F2VLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Fully server-side mod discovery: bytecode-analyzes every class in a mod jar with ASM
 * (no classloading, no client) and extracts the translation facts the runtime needs:
 *
 *  - menus: every AbstractContainerMenu subclass, its container slot count (slots added
 *    before the first player-inventory slot) and button-like slots (custom Slot classes)
 *  - screens: both inventory and client-only Screen subclasses, bound menu class where present,
 *    image dimensions and referenced PNGs
 *  - sound events + particle types: ids created in static initializers (LDC -> creator)
 *  - keybinds: KeyMapping LDC names (client mods still ship these fields)
 *  - block entities + entities: shapes (implemented capability interfaces, float-returning
 *    "speed"-like methods) so KineticProbe-style matching can be pre-seeded per mod
 *
 * Raw bytecode candidates are then VALIDATED against the live registries at cache-load
 * time, so only real runtime ids get wired into the pack/translator.
 */
public final class ModScanner {

    // Bump whenever a newly discovered surface changes runtime behaviour. Keeping the schema
    // coupled to the scanner contract prevents an otherwise-valid jar fingerprint from reviving
    // an older profile that predates Ponder/placeable/UI/spatial metadata.
    private static final int CACHE_SCHEMA = 89;

    private ModScanner() {}

    /** Extraction results for one jar. */
    public static final class Profile {
        public int schema = CACHE_SCHEMA;
        public String modId = "?";
        public String modName = "?";
        public String version = "?";
        public String jarFile = "";
        public boolean declaredClientOnly;
        /** True when the archive declares an {@code FMLModType} other than {@code MOD}: a plain
         *  library, game library or language provider. Such artifacts carry no loader mod id, so
         *  consumers must not treat a missing identity here as unreadable metadata. */
        public boolean declaredLibrary;
        /** Every top-level loader identity declared by this physical archive, in metadata order.
         *  modId remains the primary/first identity for existing runtime consumers. */
        public final List<String> modIds = new ArrayList<>();
        /** Exact loader-declared version for each mod id. Multi-mod archives cannot be represented
         * correctly by the legacy single {@link #version} field alone. */
        public final Map<String, String> modVersions = new LinkedHashMap<>();
        /** Loader-declared dependency relationships owned by the mod ids in this archive. */
        public final List<DependencyInfo> dependencies = new ArrayList<>();
        /** ServiceLoader interface -> implementation classes declared by META-INF/services. */
        public final Map<String, List<String>> serviceProviders = new LinkedHashMap<>();
        public final List<MenuInfo> menus = new ArrayList<>();
        public final List<ScreenInfo> screens = new ArrayList<>();
        public final List<EntityInfo> blockEntities = new ArrayList<>();
        public final List<EntityInfo> entities = new ArrayList<>();
        public final List<EntityInfo> blocks = new ArrayList<>();
        public final List<String> fluids = new ArrayList<>();
        public final List<String> soundIds = new ArrayList<>();
        public final List<String> particleIds = new ArrayList<>();
        public final List<String> keybindNames = new ArrayList<>();
        public final List<InputActionInfo> inputActions = new ArrayList<>();
        public final List<ClientSurfaceScanner.ClassSurface> clientSurfaces = new ArrayList<>();
        public final List<RendererBytecodeScanner.Surface> rendererSurfaces = new ArrayList<>();
        /** Exact common/server members proven by bytecode to drive kinetic rendering/control. */
        public final List<KineticBytecodeScanner.Contract> kineticContracts = new ArrayList<>();
        /** Exact BlockEntity field -> filtering behaviour mutation contracts. */
        public final List<FilterBytecodeScanner.Contract> filterContracts = new ArrayList<>();
        /** Exact BlockEntity -> value/settings board contracts recovered structurally. */
        public final List<ValueBoardBytecodeScanner.Contract> valueBoardContracts = new ArrayList<>();
        public final List<ContraptionBytecodeScanner.MovementSurface> contraptionMovements = new ArrayList<>();
        public final List<ContraptionBytecodeScanner.BogeySurface> bogeyRenderers = new ArrayList<>();
        /** Renderer-only shifted pivots on contraption entities, recovered without loading client classes. */
        public final List<ContraptionBytecodeScanner.RendererPivotSurface> contraptionRendererPivots = new ArrayList<>();
        /** Static renderer model fields recovered from client bytecode: owner.field -> model id. */
        public final Map<String, String> rendererModelFields = new LinkedHashMap<>();
        public final List<ServerMutationScanner.Recipe> serverMutations = new ArrayList<>();
        /** Fully joined vanilla rider-axis -> C2S packet -> authoritative vehicle-handler paths. */
        public final List<VehicleAxisBytecodeScanner.Surface> vehicleAxisControls = new ArrayList<>();
        public final List<BehaviorBytecodeScanner.BehaviorSurface> behaviors = new ArrayList<>();
        public final List<PacketCodecBytecodeScanner.Codec> packetCodecs = new ArrayList<>();
        public final List<MapPipelineBytecodeScanner.Pipeline> mapPipelines = new ArrayList<>();
        /** Client-only fluid material tints recovered without loading renderer classes. */
        public final List<FluidRenderBytecodeScanner.Material> fluidMaterials = new ArrayList<>();
        public final List<GuiSpriteBytecodeScanner.Sprite> guiSprites = new ArrayList<>();
        public final List<GuiSpriteBytecodeScanner.ScreenBackground> guiBackgrounds = new ArrayList<>();
        public final List<GuiSpriteBytecodeScanner.ScreenLayer> guiLayers = new ArrayList<>();
        /** Declarative recipes for textures that client bootstrap normally assembles into a
         * DynamicTexture. Pack generation replays these directly from source PNGs. */
        public final List<DynamicTextureBytecodeScanner.TextureRecipe> dynamicTextures = new ArrayList<>();
        public final List<PonderBytecodeScanner.Scene> ponderScenes = new ArrayList<>();
        public final List<PlaceableItemBytecodeScanner.Placement> placeableItems = new ArrayList<>();
        public final List<ArmorVisualBytecodeScanner.Visual> armorVisuals = new ArrayList<>();
        /** Dedicated item-renderer geometry proven by registration/ItemStack branch bytecode. */
        public final List<ItemRendererBytecodeScanner.Visual> itemRendererVisuals = new ArrayList<>();
        public final List<SpatialSurfaceBytecodeScanner.SurfaceFamily> spatialSurfaces = new ArrayList<>();
        /** Client-only dimension effect geometry recovered without loading renderer classes. */
        public final List<DimensionEffectsBytecodeScanner.Surface> dimensionEffects = new ArrayList<>();
        /** Screens whose bytecode renders a live physics/virtual sublevel as a 3D viewport. */
        public final List<SubLevelRenderBytecodeScanner.Surface> subLevelRenderSurfaces = new ArrayList<>();
        /** Joined client rendering plans: authored chrome + live canvas + context-owned bounds. */
        public final List<RenderCompositionBytecodeScanner.Surface> renderCompositions = new ArrayList<>();
        public final List<BlockUiScanner.Route> blockUiRoutes = new ArrayList<>();
        /** Entity interaction -> client-only screen routes proven across the packet/client resolver chain. */
        public final List<EntityScreenBytecodeScanner.Route> entityScreenRoutes = new ArrayList<>();
        public final List<String> audioAssets = new ArrayList<>();   // "ns:sounds/path.ogg" etc.
        public final List<String> objAssets = new ArrayList<>();     // "ns:models/.../x.obj"
        public final List<String> geometryAssets = new ArrayList<>(); // "ns:bedrock/.../x.geo.json"
        public final Map<String, String> soundFiles = new HashMap<>(); // "ns:event" -> relative sound name (from the mod's own sounds.json)
        public final List<Profile> nested = new ArrayList<>();
        public int classes;
    }

    /** Reads the mod's own assets/<any-ns>/sounds.json files: event id -> first sound name
     *  (the real relative path, e.g. "entity/siegeweapons/ballista_draw_3"). */
    private static void readSoundsJson(JarFile jar, Profile p) {
        try {
            Enumeration<JarEntry> en = jar.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                String n = e.getName();
                // Only the mod's live root sound registry belongs to discovery. Optional resource
                // packs embedded under packs/... may deliberately contain partial/invalid JSON and
                // use a different namespace context entirely.
                if (!n.matches("assets/[^/]+/sounds\\.json")) continue;
                int nsStart = "assets/".length();
                int nsEnd = n.indexOf('/', nsStart);
                if (nsEnd < 0) continue;
                String ns = n.substring(nsStart, nsEnd);
                String content;
                try (InputStream in = jar.getInputStream(e)) {
                    content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                com.google.gson.JsonObject root =
                        com.google.gson.JsonParser.parseString(content).getAsJsonObject();
                for (var ev : root.entrySet()) {
                    String eventKey = ns + ":" + ev.getKey();
                    if (p.soundFiles.containsKey(eventKey)) continue;
                    String name = null;
                    try {
                        var sounds = ev.getValue().getAsJsonObject().getAsJsonArray("sounds");
                        if (sounds == null || sounds.isEmpty()) continue;
                        var first = sounds.get(0);
                        name = first.isJsonPrimitive() ? first.getAsString()
                                : first.getAsJsonObject().get("name").getAsString();
                    } catch (RuntimeException t) {
                        continue;
                    }
                    // resolve nested namespaces; relative names stay relative to the event ns
                    p.soundFiles.put(eventKey, name);
                }
            }
        } catch (IOException | RuntimeException t) {
            F2VLog.warn("[vm] sounds.json parse failed in " + p.modId + ": " + t);
        }
    }

    public static final class MenuInfo {
        public String menuClass;
        public int containerSlots = -1;
        public int totalSlots = -1;
        public final List<Integer> buttonSlots = new ArrayList<>();  // custom-slot indices
    }

    public static final class ScreenInfo {
        public String screenClass;
        public String menuClass;
        /** Concrete menu classes that can use this screen. This is especially important when a
         * reusable screen is parameterized with an abstract menu family that has several concrete
         * runtime descendants. menuClass remains the declared/primary binding for cache and
         * compatibility consumers; menuClasses carries the exact runtime aliases. */
        public final List<String> menuClasses = new ArrayList<>();
        public int width = -1;
        public int height = -1;
        /** PNG/resource strings referenced by the screen bytecode, for server-only GUI baking. */
        public final List<String> textureAssets = new ArrayList<>();
    }

    public static final class InputActionInfo {
        public String packetClass;
        public String constructorDesc;
        public String trigger = "mouse_boolean";
    }

    public static final class DependencyInfo {
        public String ownerModId;
        public String modId;
        public String type = "required";
        public String versionRange = "";
        public String ordering = "NONE";
        public String side = "BOTH";
    }

    public static final class EntityInfo {
        public String clazz;
        public final List<String> interfaces = new ArrayList<>();
        public final List<String> methodShapes = new ArrayList<>();   // "name:desc" raw
        public final Set<String> hints = new LinkedHashSet<>();       // normalized tokens
        public final List<String> blockStateProperties = new ArrayList<>(); // blocks only
        public final List<String> blockIds = new ArrayList<>();       // runtime registry ids (validated)
        /** Concrete BlockEntity classes returned/constructed by this Block class. This is stronger
         * renderer ownership evidence than class-name token similarity. */
        public final List<String> blockEntityTypes = new ArrayList<>();
    }

    /** Vanilla blockstate properties registered directly on Block classes that matter for
     *  translation (orientation/state flags); property names survive remapping (LDC strings). */
    private static final java.util.Set<String> INTERESTING_PROPERTIES = java.util.Set.of(
            "axis", "facing", "rotation", "lit", "powered", "open", "half", "waterlogged",
            "active", "mode", "level", "variant", "type", "part");

    private static final java.util.Set<String> FLUID_ROOTS = java.util.Set.of(
            "net/minecraft/world/level/material/Fluid",
            "net/minecraft/world/level/material/FlowingFluid",
            "net/minecraftforge/fluids/ForgeFlowingFluid");

    // --- scan ----------------------------------------------------------------

    public static Profile scan(Path jarPath) throws IOException {
        return scan(jarPath, null);
    }

    /** Reads only archive identity/dependency metadata. Used by ModSetResolver before any
     * cross-archive bytecode graph is built, so conflicting providers cannot contaminate scans. */
    public static Profile scanMetadata(Path jarPath) throws IOException {
        Profile p = new Profile();
        p.jarFile = jarPath.getFileName().toString();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            readMeta(jar, p);
            readManifestMeta(jar.getManifest(), p);
            finalizeModVersions(p);
        }
        for (dev.f2v.generic.JarAccess.NestedArchive archive
                : dev.f2v.generic.JarAccess.nestedArchives(jarPath)) {
            Profile nested = scanNestedMetadata(archive);
            if (nested != null) p.nested.add(nested);
        }
        return p;
    }

    private static Profile scanNestedMetadata(dev.f2v.generic.JarAccess.NestedArchive archive) {
        if (archive == null || archive.bytes() == null) return null;
        Profile p = new Profile();
        p.jarFile = archive.reference();
        Map<String, byte[]> entries = new HashMap<>();
        java.util.jar.Manifest manifest;
        try (java.util.jar.JarInputStream jar = new java.util.jar.JarInputStream(
                new java.io.ByteArrayInputStream(archive.bytes()))) {
            manifest = jar.getManifest();
            java.util.jar.JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                String name = entry.getName();
                if (name.equals("META-INF/neoforge.mods.toml") || name.equals("META-INF/mods.toml")
                        || name.equals("fabric.mod.json") || name.equals("quilt.mod.json")) {
                    entries.put(name, jar.readAllBytes());
                }
            }
        } catch (IOException failure) {
            return null;
        }
        readMeta(entries, p);
        readManifestMeta(manifest, p);
        finalizeModVersions(p);
        return p;
    }

    static Profile scan(Path jarPath, CrossJarClassPath classPath) throws IOException {
        Profile p = new Profile();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Map<String, ClassNode> nodes = new HashMap<>();
            List<ZipEntry> classCandidates = new ArrayList<>();
            jar.stream().forEach(e -> {
                String n = e.getName();
                if (n.endsWith(".class")) classCandidates.add(e);
                else if (n.startsWith("assets/") && (n.endsWith(".obj") || n.endsWith(".geo.json")
                        || n.endsWith(".ogg") || n.endsWith(".wav")
                        || n.contains("/particles/") && n.endsWith(".json"))) collectAsset(n, p);
            });
            List<ZipEntry> classEntries = ArchiveClassSelector.select(
                    classCandidates, ZipEntry::getName, jar.isMultiRelease());
            for (ClassNode node : dev.f2v.pack.ParallelPackWork.mapOrdered(classEntries,
                    entry -> parseClass(jar, entry, jarPath.getFileName().toString()))) {
                if (node != null && node.name != null) nodes.put(node.name, node);
            }
            p.classes = nodes.size();
            Map<String, ClassNode> graph = classPath == null ? nodes : classPath.overlay(nodes);
            p.jarFile = jarPath.getFileName().toString();
            readMeta(jar, p);
            readManifestMeta(jar.getManifest(), p);
            finalizeModVersions(p);
            readServiceProviders(jar, p);
            readSoundsJson(jar, p);
            p.packetCodecs.addAll(PacketCodecBytecodeScanner.scan(graph));
            p.mapPipelines.addAll(MapPipelineBytecodeScanner.scan(graph));
            // Fluid material registrations and their synthetic tint suppliers live in the owning
            // archive. Scanning the cross-jar graph here can incorrectly attach a dependency's
            // common path such as "fuel" to this profile, so keep material ownership jar-local.
            p.fluidMaterials.addAll(FluidRenderBytecodeScanner.scan(nodes));
            GuiSpriteBytecodeScanner.Result guiSprites = GuiSpriteBytecodeScanner.scan(graph, p.modId);
            p.guiSprites.addAll(guiSprites.sprites);
            p.guiBackgrounds.addAll(guiSprites.backgrounds);
            p.guiLayers.addAll(guiSprites.layers);
            p.dynamicTextures.addAll(DynamicTextureBytecodeScanner.scan(graph, p.modId));
            List<String> languageKeyCandidates = ownedLanguageKeyCandidates(jar, p);
            Map<String, String> clientKeyFields = ClientSurfaceScanner.discoverKeyFields(
                    graph, languageKeyCandidates);
            Map<String, String> rendererModelFields = RendererBytecodeScanner.discoverModelFields(graph);
            p.rendererModelFields.putAll(rendererModelFields);
            ContraptionBytecodeScanner.Result contraptions = ContraptionBytecodeScanner.scan(
                    graph, p.modId, rendererModelFields);
            p.contraptionMovements.addAll(contraptions.movements);
            p.bogeyRenderers.addAll(contraptions.bogeys);
            p.contraptionRendererPivots.addAll(contraptions.rendererPivots);
            Map<String, BehaviorBytecodeScanner.RegistryRef> behaviorRegistryFields =
                    BehaviorBytecodeScanner.discoverRegistryFields(graph, p.modId);
            p.itemRendererVisuals.addAll(ItemRendererBytecodeScanner.scan(graph, behaviorRegistryFields));
            java.util.List<PonderBytecodeScanner.Scene> ponder =
                    PonderBytecodeScanner.scan(graph, p.modId, behaviorRegistryFields);
            ponder.forEach(scene -> scene.sourceJar = p.jarFile);
            p.ponderScenes.addAll(ponder);
            p.placeableItems.addAll(PlaceableItemBytecodeScanner.scan(graph, behaviorRegistryFields));
        p.armorVisuals.addAll(ArmorVisualBytecodeScanner.scan(graph, p.modId));
            p.spatialSurfaces.addAll(SpatialSurfaceBytecodeScanner.scan(graph));
            p.dimensionEffects.addAll(DimensionEffectsBytecodeScanner.scan(graph));
            p.subLevelRenderSurfaces.addAll(SubLevelRenderBytecodeScanner.scan(graph, guiSprites));
            p.entityScreenRoutes.addAll(EntityScreenBytecodeScanner.scan(graph));
            analyzeAll(graph, p, clientKeyFields, rendererModelFields, behaviorRegistryFields);
            RendererBytecodeScanner.bindRegisteredEntities(p.rendererSurfaces,
                    dev.f2v.capture.IsolatedModelCapture.entityRendererBindings(graph.values()));
            RendererBytecodeScanner.bindEntityStateAnimationHints(p.rendererSurfaces, graph);
            p.clientSurfaces.addAll(NamedHotkeyPacketScanner.scan(nodes, languageKeyCandidates));
            publishLanguageKeybinds(languageKeyCandidates, p);
            p.renderCompositions.addAll(RenderCompositionBytecodeScanner.scan(
                    graph, p.clientSurfaces, guiSprites, p.subLevelRenderSurfaces));
            p.kineticContracts.addAll(KineticBytecodeScanner.scan(
                    graph, p.rendererSurfaces, rendererModelFields));
            p.filterContracts.addAll(FilterBytecodeScanner.scan(graph));
            p.valueBoardContracts.addAll(ValueBoardBytecodeScanner.scan(graph));
        }
        List<dev.f2v.generic.JarAccess.NestedArchive> nestedArchives =
                dev.f2v.generic.JarAccess.nestedArchives(jarPath);
        for (Profile nested : dev.f2v.pack.ParallelPackWork.mapOrdered(nestedArchives,
                archive -> scanNestedSafe(archive, classPath))) {
            if (nested != null) p.nested.add(nested);
        }
        return p;
    }

    private static ClassNode parseClass(JarFile jar, ZipEntry entry, String source) {
        if (jar == null || entry == null) return null;
        try (InputStream in = jar.getInputStream(entry)) {
            ClassReader cr = new ClassReader(in);
            ClassNode node = new ClassNode();
            cr.accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException | RuntimeException failure) {
            F2VLog.warn("[vm] class parse skipped " + entry.getName() + " in " + source + ": " + failure);
            return null;
        }
    }

    private static Profile scanNestedSafe(dev.f2v.generic.JarAccess.NestedArchive archive,
                                          CrossJarClassPath classPath) {
        try {
            return scanNested(archive, classPath);
        } catch (Exception failure) {
            F2VLog.warn("[vm] nested discovery failed for " + archive.reference() + ": " + failure);
            return null;
        }
    }

    private static Profile scanNested(dev.f2v.generic.JarAccess.NestedArchive archive,
                                      CrossJarClassPath classPath)
            throws IOException {
        Profile p = new Profile();
        Map<String, byte[]> entries = new HashMap<>();
        Map<String, ClassNode> nodes = new HashMap<>();
        boolean multiRelease;
        java.util.jar.Manifest nestedManifest;
        try (java.util.jar.JarInputStream jar = new java.util.jar.JarInputStream(
                new java.io.ByteArrayInputStream(archive.bytes()))) {
            nestedManifest = jar.getManifest();
            multiRelease = nestedManifest != null && "true".equalsIgnoreCase(
                    nestedManifest.getMainAttributes().getValue("Multi-Release"));
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                boolean metadata = name.equals("META-INF/mods.toml")
                        || name.equals("META-INF/neoforge.mods.toml")
                        || name.equals("fabric.mod.json")
                        || name.equals("quilt.mod.json")
                        || name.startsWith("META-INF/services/")
                        || name.matches("assets/[^/]+/lang/en_us\\.json")
                        || name.matches("assets/[^/]+/sounds\\.json");
                boolean asset = name.startsWith("assets/") && (name.endsWith(".obj")
                        || name.endsWith(".geo.json")
                        || name.endsWith(".ogg") || name.endsWith(".wav")
                        || name.contains("/particles/") && name.endsWith(".json"));
                if (!name.endsWith(".class") && !metadata && !asset) continue;
                byte[] data = jar.readAllBytes();
                entries.put(name, data);
                if (name.endsWith(".class")) {
                } else if (asset) {
                    collectAsset(name, p);
                }
            }
        }
        List<Map.Entry<String, byte[]>> nestedClasses = entries.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(".class"))
                .toList();
        nestedClasses = ArchiveClassSelector.select(
                nestedClasses, Map.Entry::getKey, multiRelease);
        for (ClassNode node : dev.f2v.pack.ParallelPackWork.mapOrdered(nestedClasses,
                entry -> parseClass(entry.getValue(), archive.reference() + "!/" + entry.getKey()))) {
            if (node != null && node.name != null) nodes.put(node.name, node);
        }
        p.classes = nodes.size();
        Map<String, ClassNode> graph = classPath == null ? nodes : classPath.overlay(nodes);
        p.jarFile = archive.reference();
        readMeta(entries, p);
        readManifestMeta(nestedManifest, p);
        readServiceProviders(entries, p);
        readSoundsJson(entries, p);
        p.packetCodecs.addAll(PacketCodecBytecodeScanner.scan(graph));
        p.mapPipelines.addAll(MapPipelineBytecodeScanner.scan(graph));
        p.fluidMaterials.addAll(FluidRenderBytecodeScanner.scan(nodes));
        GuiSpriteBytecodeScanner.Result guiSprites = GuiSpriteBytecodeScanner.scan(graph, p.modId);
        p.guiSprites.addAll(guiSprites.sprites);
        p.guiBackgrounds.addAll(guiSprites.backgrounds);
        p.guiLayers.addAll(guiSprites.layers);
        p.dynamicTextures.addAll(DynamicTextureBytecodeScanner.scan(graph, p.modId));
        List<String> languageKeyCandidates = ownedLanguageKeyCandidates(entries, p);
        Map<String, String> clientKeyFields = ClientSurfaceScanner.discoverKeyFields(
                graph, languageKeyCandidates);
        Map<String, String> rendererModelFields = RendererBytecodeScanner.discoverModelFields(graph);
        p.rendererModelFields.putAll(rendererModelFields);
        ContraptionBytecodeScanner.Result contraptions = ContraptionBytecodeScanner.scan(
                graph, p.modId, rendererModelFields);
        p.contraptionMovements.addAll(contraptions.movements);
        p.bogeyRenderers.addAll(contraptions.bogeys);
        p.contraptionRendererPivots.addAll(contraptions.rendererPivots);
        Map<String, BehaviorBytecodeScanner.RegistryRef> behaviorRegistryFields =
                BehaviorBytecodeScanner.discoverRegistryFields(graph, p.modId);
        p.itemRendererVisuals.addAll(ItemRendererBytecodeScanner.scan(graph, behaviorRegistryFields));
        java.util.List<PonderBytecodeScanner.Scene> ponder =
                PonderBytecodeScanner.scan(graph, p.modId, behaviorRegistryFields);
        ponder.forEach(scene -> scene.sourceJar = p.jarFile);
        p.ponderScenes.addAll(ponder);
        p.placeableItems.addAll(PlaceableItemBytecodeScanner.scan(graph, behaviorRegistryFields));
        p.armorVisuals.addAll(ArmorVisualBytecodeScanner.scan(graph, p.modId));
        p.spatialSurfaces.addAll(SpatialSurfaceBytecodeScanner.scan(graph));
        p.dimensionEffects.addAll(DimensionEffectsBytecodeScanner.scan(graph));
        p.subLevelRenderSurfaces.addAll(SubLevelRenderBytecodeScanner.scan(graph, guiSprites));
        p.entityScreenRoutes.addAll(EntityScreenBytecodeScanner.scan(graph));
        analyzeAll(graph, p, clientKeyFields, rendererModelFields, behaviorRegistryFields);
        RendererBytecodeScanner.bindEntityStateAnimationHints(p.rendererSurfaces, graph);
        p.clientSurfaces.addAll(NamedHotkeyPacketScanner.scan(nodes, languageKeyCandidates));
        publishLanguageKeybinds(languageKeyCandidates, p);
        p.renderCompositions.addAll(RenderCompositionBytecodeScanner.scan(
                graph, p.clientSurfaces, guiSprites, p.subLevelRenderSurfaces));
        p.kineticContracts.addAll(KineticBytecodeScanner.scan(graph, p.rendererSurfaces,
                rendererModelFields));
        p.filterContracts.addAll(FilterBytecodeScanner.scan(graph));
        p.valueBoardContracts.addAll(ValueBoardBytecodeScanner.scan(graph));
        return p;
    }

    private static ClassNode parseClass(byte[] data, String source) {
        if (data == null || data.length == 0) return null;
        try {
            ClassReader reader = new ClassReader(data);
            ClassNode node = new ClassNode();
            reader.accept(node, ClassReader.SKIP_FRAMES);
            return node;
        } catch (RuntimeException failure) {
            F2VLog.warn("[vm] class parse skipped " + source + ": " + failure);
            return null;
        }
    }

    private static void analyzeAll(Map<String, ClassNode> nodes, Profile target,
                                   Map<String, String> clientKeyFields,
                                   Map<String, String> rendererModelFields,
                                   Map<String, BehaviorBytecodeScanner.RegistryRef> behaviorRegistryFields) {
        List<ClassNode> ordered = nodes.values().stream()
                .sorted(java.util.Comparator.comparing(node -> node.name == null ? "" : node.name))
                .toList();
        List<Profile> fragments = dev.f2v.pack.ParallelPackWork.mapOrdered(ordered, node -> {
            Profile fragment = new Profile();
            analyze(node, nodes, fragment, clientKeyFields, rendererModelFields, behaviorRegistryFields);
            return fragment;
        });
        for (Profile fragment : fragments) mergeAnalysis(target, fragment);
        BehaviorCallGraph.link(target.behaviors, nodes, behaviorRegistryFields);
        ServerMutationScanner.discoverServerbound(nodes, target.behaviors, target.serverMutations);
        ServerMutationScanner.discoverClientServerbound(nodes, target.clientSurfaces,
                target.serverMutations);
        target.vehicleAxisControls.addAll(VehicleAxisBytecodeScanner.discover(
                nodes, target.behaviors, target.serverMutations));
    }

    /** Merge per-class scanner output serially so discovery stays deterministic and thread-safe. */
    private static void mergeAnalysis(Profile target, Profile fragment) {
        target.menus.addAll(fragment.menus);
        target.screens.addAll(fragment.screens);
        target.blockEntities.addAll(fragment.blockEntities);
        target.entities.addAll(fragment.entities);
        target.blocks.addAll(fragment.blocks);
        target.fluids.addAll(fragment.fluids);
        target.soundIds.addAll(fragment.soundIds);
        target.particleIds.addAll(fragment.particleIds);
        target.clientSurfaces.addAll(fragment.clientSurfaces);
        target.rendererSurfaces.addAll(fragment.rendererSurfaces);
        target.serverMutations.addAll(fragment.serverMutations);
        target.behaviors.addAll(fragment.behaviors);
        target.blockUiRoutes.addAll(fragment.blockUiRoutes);
        target.entityScreenRoutes.addAll(fragment.entityScreenRoutes);
        for (String key : fragment.keybindNames) {
            if (!target.keybindNames.contains(key)) target.keybindNames.add(key);
        }
        for (InputActionInfo action : fragment.inputActions) {
            boolean duplicate = target.inputActions.stream().anyMatch(existing ->
                    java.util.Objects.equals(existing.packetClass, action.packetClass)
                            && java.util.Objects.equals(existing.constructorDesc, action.constructorDesc));
            if (!duplicate) target.inputActions.add(action);
        }
    }

    private static void collectAsset(String name, Profile p) {
        int firstSlash = name.indexOf('/');
        int secondSlash = name.indexOf('/', firstSlash + 1);
        if (firstSlash < 0 || secondSlash <= firstSlash + 1 || secondSlash >= name.length() - 1) return;
        String namespace = name.substring(firstSlash + 1, secondSlash);
        String relative = name.substring(secondSlash + 1);
        if (name.contains("/particles/") && name.endsWith(".json")) {
            int at = relative.indexOf("particles/");
            if (at >= 0) p.particleIds.add(namespace + ":"
                    + relative.substring(at + "particles/".length(), relative.length() - 5));
        } else if (name.endsWith(".obj")) {
            p.objAssets.add(namespace + ":" + relative);
        } else if (name.endsWith(".geo.json")) {
            p.geometryAssets.add(namespace + ":" + relative);
        } else {
            p.audioAssets.add(namespace + ":" + relative);
        }
    }

    private static void readMeta(Map<String, byte[]> entries, Profile p) {
        // On NeoForge, prefer the native descriptor when a compatibility jar happens to ship
        // both files. A legacy Forge descriptor may be retained for tooling/transition purposes
        // and can otherwise attribute the profile to stale metadata.
        for (String name : new String[]{"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
            byte[] data = entries.get(name);
            if (data == null) continue;
            String text = new String(data, StandardCharsets.UTF_8);
            readForgeTomlMeta(text, p);
            return;
        }
        byte[] fabric = entries.get("fabric.mod.json");
        if (fabric != null) {
            readFabricMeta(new String(fabric, StandardCharsets.UTF_8), p);
            return;
        }
        byte[] quilt = entries.get("quilt.mod.json");
        if (quilt != null) readQuiltMeta(new String(quilt, StandardCharsets.UTF_8), p);
    }

    /**
     * Forge/NeoForge dependency tables also contain {@code modId = ...}. Reading the first match
     * from the entire TOML can therefore attribute a profile to one of its dependencies when the
     * generated metadata happens to place [[dependencies.*]] before [[mods]] (Ad Astra is a real
     * example). Prefer the first actual [[mods]] record; only legacy files without that section
     * fall back to whole-file field lookup.
     */
    private static void readForgeTomlMeta(String text, Profile p) {
        if (text == null || p == null) return;
        java.util.regex.Matcher mods = java.util.regex.Pattern.compile(
                "(?ms)^\\s*\\[\\[\\s*mods\\s*\\]\\]\\s*(.*?)(?=^\\s*\\[\\[|\\z)")
                .matcher(text);
        boolean foundMod = false;
        while (mods.find()) {
            foundMod = true;
            readModRecord(mods.group(1), p);
        }
        if (!foundMod) {
            for (String scope : tomlInlineTables(text, "mods")) {
                foundMod = true;
                readModRecord(scope, p);
            }
        }
        if (!foundMod) {
            String modId = tomlString(text, "modId");
            if (modId != null) addModId(p, modId);
            String displayName = tomlString(text, "displayName");
            String version = tomlString(text, "version");
            if (displayName != null) p.modName = displayName;
            if (version != null) p.version = version;
        }
        readForgeDependencies(text, p);
    }

    private static void readForgeDependencies(String text, Profile p) {
        java.util.regex.Matcher dependencies = java.util.regex.Pattern.compile(
                "(?ms)^\\s*\\[\\[\\s*dependencies\\.(?:\\\"([^\\\"]+)\\\"|'([^']+)'|([A-Za-z0-9_.-]+))\\s*\\]\\]\\s*(.*?)(?=^\\s*\\[\\[|\\z)")
                .matcher(text);
        while (dependencies.find()) {
            String owner = firstNonBlank(dependencies.group(1),
                    firstNonBlank(dependencies.group(2), dependencies.group(3)));
            String scope = dependencies.group(4);
            String dependencyId = tomlString(scope, "modId");
            if (owner == null || dependencyId == null) continue;
            DependencyInfo info = new DependencyInfo();
            info.ownerModId = owner;
            info.modId = dependencyId;
            String type = tomlString(scope, "type");
            String versionRange = tomlString(scope, "versionRange");
            String ordering = tomlString(scope, "ordering");
            String side = tomlString(scope, "side");
            if (type != null) info.type = type.toLowerCase(Locale.ROOT);
            else if (java.util.regex.Pattern.compile("(?m)^\\s*mandatory\\s*=\\s*false\\s*(?:#.*)?$")
                    .matcher(scope).find()) info.type = "optional";
            if (versionRange != null) info.versionRange = versionRange;
            if (ordering != null) info.ordering = ordering.toUpperCase(Locale.ROOT);
            if (side != null) info.side = side.toUpperCase(Locale.ROOT);
            p.dependencies.add(info);
        }
    }

    private static void addModId(Profile p, String modId) {
        if (p == null || modId == null || modId.isBlank()) return;
        String value = modId.trim();
        if (p.modIds.isEmpty()) p.modId = value;
        if (!p.modIds.contains(value)) p.modIds.add(value);
    }

    private static String tomlString(String text, String key) {
        if (text == null || key == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?m)(?:^|[{,])\\s*" + java.util.regex.Pattern.quote(key)
                        + "\\s*=\\s*[\"']([^\"']+)[\"']")
                .matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Reads the TOML inline tables of an array value, e.g. {@code mods = [ { modId = 'a' }, ... ]}.
     *
     * <p>Inline arrays of tables are equivalent TOML to {@code [[mods]]} table arrays, and every
     * datapack wrapped for Forge by Modrinth's {@code lowcodefml} generator uses this compact form.
     * Reading only the expanded form silently leaves those archives without an identity, which
     * downstream drops as an unrecognised jar.
     *
     * <p>Returns each table body without its enclosing braces, in declaration order. Quoted values
     * are respected so that braces or brackets inside strings do not end a record early.
     */
    private static List<String> tomlInlineTables(String text, String key) {
        List<String> tables = new ArrayList<>();
        if (text == null || key == null) return tables;
        java.util.regex.Matcher start = java.util.regex.Pattern.compile(
                "(?m)^\\s*" + java.util.regex.Pattern.quote(key) + "\\s*=\\s*\\[")
                .matcher(text);
        if (!start.find()) return tables;
        int depth = 0;
        int begin = -1;
        boolean quoted = false;
        char quote = 0;
        for (int i = start.end(); i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == quote) quoted = false;
                continue;
            }
            if (c == '"' || c == '\'') {
                quoted = true;
                quote = c;
            } else if (c == '{') {
                if (depth++ == 0) begin = i + 1;
            } else if (c == '}') {
                if (--depth == 0 && begin >= 0) {
                    tables.add(text.substring(begin, i));
                    begin = -1;
                }
            } else if (c == ']' && depth == 0) {
                return tables;
            }
        }
        return tables;
    }

    /** Applies one mod record - table-array or inline - to the profile identity. */
    private static void readModRecord(String scope, Profile p) {
        String modId = tomlString(scope, "modId");
        if (modId != null) addModId(p, modId);
        String displayName = tomlString(scope, "displayName");
        String version = tomlString(scope, "version");
        if (modId != null && version != null) p.modVersions.put(modId.trim(), version);
        if (p.modIds.size() != 1) return;
        if (displayName != null) p.modName = displayName;
        if (version != null) p.version = version;
    }

    private static void readServiceProviders(JarFile jar, Profile p) {
        if (jar == null || p == null) return;
        try {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith("META-INF/services/")) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    addServiceProviders(entry.getName(),
                            new String(in.readAllBytes(), StandardCharsets.UTF_8), p);
                }
            }
        } catch (IOException failure) {
            F2VLog.warn("[vm] service metadata parse failed in " + p.modId + ": " + failure);
        }
    }

    private static void readServiceProviders(Map<String, byte[]> entries, Profile p) {
        if (entries == null || p == null) return;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().startsWith("META-INF/services/")) continue;
            addServiceProviders(entry.getKey(),
                    new String(entry.getValue(), StandardCharsets.UTF_8), p);
        }
    }

    private static void addServiceProviders(String path, String text, Profile p) {
        String prefix = "META-INF/services/";
        if (path == null || !path.startsWith(prefix) || path.length() <= prefix.length()) return;
        String service = path.substring(prefix.length()).trim();
        if (service.isEmpty()) return;
        List<String> providers = p.serviceProviders.computeIfAbsent(service, ignored -> new ArrayList<>());
        for (String line : text.split("\\R")) {
            int comment = line.indexOf('#');
            String provider = (comment >= 0 ? line.substring(0, comment) : line).trim();
            if (!provider.isEmpty() && !providers.contains(provider)) providers.add(provider);
        }
    }

    /**
     * Some mods build KeyMapping translation ids from their namespace at runtime instead of
     * storing the complete key string in bytecode. Once bytecode has independently proven that
     * the archive contains keybinding surfaces, its own en_us language table is a deterministic
     * source of those otherwise-lost ids. Restrict candidates to this profile's owner ids so a
     * bundled language table cannot donate controls belonging to another mod.
     */
    private static List<String> ownedLanguageKeyCandidates(JarFile jar, Profile p) {
        if (jar == null || p == null) return List.of();
        LinkedHashSet<String> found = new LinkedHashSet<>();
        try {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().matches("assets/[^/]+/lang/en_us\\.json")) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    collectLanguageKeybinds(new String(in.readAllBytes(), StandardCharsets.UTF_8), p, found);
                }
            }
        } catch (IOException failure) {
            F2VLog.warn("[vm] language keybind parse failed in " + p.modId + ": " + failure);
        }
        return List.copyOf(found);
    }

    private static List<String> ownedLanguageKeyCandidates(Map<String, byte[]> entries, Profile p) {
        if (entries == null || p == null) return List.of();
        LinkedHashSet<String> found = new LinkedHashSet<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().matches("assets/[^/]+/lang/en_us\\.json")) continue;
            collectLanguageKeybinds(new String(entry.getValue(), StandardCharsets.UTF_8), p, found);
        }
        return List.copyOf(found);
    }

    private static boolean hasKeybindingEvidence(Profile p) {
        if (p == null || p.clientSurfaces == null) return false;
        return p.clientSurfaces.stream().anyMatch(surface -> surface != null && surface.kinds != null
                && surface.kinds.contains(ClientSurfaceScanner.Kind.KEYBIND));
    }

    private static void collectLanguageKeybinds(String text, Profile p, Set<String> out) {
        if (text == null || p == null || out == null) return;
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            for (String key : root.keySet()) {
                if (!ownedKeybind(key, p) || !dev.f2v.capture.ServerCapture.looksLikeKeybind(key)) continue;
                out.add(key);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static void publishLanguageKeybinds(Collection<String> candidates, Profile p) {
        if (candidates == null || p == null || !hasKeybindingEvidence(p)) return;
        for (String key : candidates) {
            if (key != null && !p.keybindNames.contains(key)) p.keybindNames.add(key);
        }
    }

    private static boolean ownedKeybind(String key, Profile p) {
        if (key == null || p == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> owners = new LinkedHashSet<>();
        if (p.modId != null && !p.modId.isBlank() && !"?".equals(p.modId)) owners.add(p.modId);
        if (p.modIds != null) owners.addAll(p.modIds);
        for (String owner : owners) {
            if (owner == null || owner.isBlank()) continue;
            String id = owner.toLowerCase(Locale.ROOT);
            if (lower.startsWith("key." + id + ".") || lower.startsWith("keybind." + id + ".")) return true;
        }
        return false;
    }

    private static void readSoundsJson(Map<String, byte[]> entries, Profile p) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (!name.matches("assets/[^/]+/sounds\\.json")) continue;
            String namespace = name.split("/", 3)[1];
            try {
                JsonObject root = com.google.gson.JsonParser.parseString(
                        new String(entry.getValue(), StandardCharsets.UTF_8)).getAsJsonObject();
                for (var sound : root.entrySet()) {
                    String eventKey = namespace + ":" + sound.getKey();
                    if (p.soundFiles.containsKey(eventKey)) continue;
                    var sounds = sound.getValue().getAsJsonObject().getAsJsonArray("sounds");
                    if (sounds == null || sounds.isEmpty()) continue;
                    var first = sounds.get(0);
                    String value = first.isJsonPrimitive() ? first.getAsString()
                            : first.getAsJsonObject().get("name").getAsString();
                    p.soundFiles.put(eventKey, value);
                }
            } catch (RuntimeException t) {
                F2VLog.warn("[vm] nested sounds.json parse failed in " + p.modId + ": " + t);
            }
        }
    }

    private static void readMeta(JarFile jar, Profile p) {
        for (String mf : new String[]{"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
            ZipEntry e = jar.getEntry(mf);
            if (e == null) continue;
            try (InputStream in = jar.getInputStream(e)) {
                String text = new String(in.readAllBytes());
                readForgeTomlMeta(text, p);
                return;
            } catch (IOException ignored) {
            }
        }
        ZipEntry fabric = jar.getEntry("fabric.mod.json");
        if (fabric != null) {
            try (InputStream in = jar.getInputStream(fabric)) {
                readFabricMeta(new String(in.readAllBytes(), StandardCharsets.UTF_8), p);
            } catch (IOException ignored) {
            }
            return;
        }
        ZipEntry quilt = jar.getEntry("quilt.mod.json");
        if (quilt != null) {
            try (InputStream in = jar.getInputStream(quilt)) {
                readQuiltMeta(new String(in.readAllBytes(), StandardCharsets.UTF_8), p);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Infrastructure/library jars often participate in the mod loader without shipping a
     * mods.toml of their own. Keep their discovery profiles distinct and useful by accepting the
     * standard JAR identity fields as a fallback. This is intentionally lower priority than real
     * loader metadata, because display titles are not authoritative mod ids when one is declared.
     */
    private static void readManifestMeta(java.util.jar.Manifest manifest, Profile p) {
        if (manifest == null || p == null) return;
        java.util.jar.Attributes attributes = manifest.getMainAttributes();
        if (attributes == null) return;
        String implementationTitle = attributes.getValue("Implementation-Title");
        String specificationTitle = attributes.getValue("Specification-Title");
        String automaticModule = attributes.getValue("Automatic-Module-Name");
        String title = firstNonBlank(implementationTitle, specificationTitle);
        String modType = attributes.getValue("FMLModType");
        if (modType != null && !modType.isBlank()) {
            p.declaredLibrary = !"MOD".equalsIgnoreCase(modType.trim());
        }

        if (unknownIdentity(p.modName) && title != null) p.modName = title;
        String manifestVersion = firstNonBlank(attributes.getValue("Implementation-Version"),
                attributes.getValue("Specification-Version"));
        if (manifestVersion != null && p.version != null && p.version.contains("${file.jarVersion}")) {
            p.version = p.version.replace("${file.jarVersion}", manifestVersion);
        } else if (unknownIdentity(p.version) && manifestVersion != null) {
            p.version = manifestVersion;
        }
        if (!unknownIdentity(p.modId)) return;

        String candidate = normalizeManifestId(title);
        if (candidate == null && automaticModule != null) {
            int dot = automaticModule.lastIndexOf('.');
            candidate = normalizeManifestId(dot >= 0 ? automaticModule.substring(dot + 1) : automaticModule);
        }
        // A library, game library or language provider is loaded without a mod id at all. Falling
        // back to the artifact name keeps it addressable for dependency resolution and reporting
        // instead of leaving an unidentified archive behind.
        if (candidate == null && p.declaredLibrary) candidate = normalizeManifestId(artifactId(p.jarFile));
        if (candidate != null) addModId(p, candidate);
    }

    private static void finalizeModVersions(Profile p) {
        if (p == null) return;
        if (p.modIds.size() == 1 && !unknownIdentity(p.version)) {
            String id = p.modIds.get(0);
            String version = p.version;
            if (version.contains("${file.jarVersion}")) return;
            p.modVersions.put(id, version);
        }
        if (p.modVersions.isEmpty() && !unknownIdentity(p.modId) && !unknownIdentity(p.version)) {
            p.modVersions.put(p.modId, p.version);
        }
    }

    /** Strips version and classifier suffixes from an artifact file name: {@code foo-1.2-all.jar -> foo}. */
    private static String artifactId(String jarFile) {
        if (jarFile == null || jarFile.isBlank()) return null;
        String name = jarFile;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        java.util.regex.Matcher version = java.util.regex.Pattern.compile("-\\d").matcher(name);
        if (version.find()) name = name.substring(0, version.start());
        return name.isBlank() ? null : name;
    }

    private static boolean unknownIdentity(String value) {
        return value == null || value.isBlank() || "?".equals(value);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first.trim();
        if (second != null && !second.isBlank()) return second.trim();
        return null;
    }

    private static String normalizeManifestId(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "");
        return normalized.isBlank() ? null : normalized;
    }

    private static void readFabricMeta(String text, Profile p) {
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            if (root.has("id")) addModId(p, root.get("id").getAsString());
            if (root.has("name")) p.modName = root.get("name").getAsString();
            if (root.has("version")) p.version = root.get("version").getAsString();
            if (root.has("environment") && root.get("environment").isJsonPrimitive()) {
                p.declaredClientOnly = "client".equalsIgnoreCase(root.get("environment").getAsString());
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static void readQuiltMeta(String text, Profile p) {
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            JsonObject loader = root.has("quilt_loader") && root.get("quilt_loader").isJsonObject()
                    ? root.getAsJsonObject("quilt_loader") : null;
            if (loader != null) {
                if (loader.has("id")) addModId(p, loader.get("id").getAsString());
                if (loader.has("version")) p.version = loader.get("version").getAsString();
                if (loader.has("metadata") && loader.get("metadata").isJsonObject()) {
                    JsonObject metadata = loader.getAsJsonObject("metadata");
                    if (metadata.has("name")) p.modName = metadata.get("name").getAsString();
                }
            }
            if (root.has("minecraft") && root.get("minecraft").isJsonObject()) {
                JsonObject minecraft = root.getAsJsonObject("minecraft");
                if (minecraft.has("environment") && minecraft.get("environment").isJsonPrimitive()) {
                    p.declaredClientOnly = "client".equalsIgnoreCase(
                            minecraft.get("environment").getAsString());
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static void analyze(ClassNode node, Map<String, ClassNode> nodes, Profile p,
                                Map<String, String> clientKeyFields,
                                Map<String, String> rendererModelFields,
                                Map<String, BehaviorBytecodeScanner.RegistryRef> behaviorRegistryFields) {
        ClientSurfaceScanner.ClassSurface clientSurface =
                ClientSurfaceScanner.inspect(node, nodes, clientKeyFields);
        if (clientSurface != null) {
            p.clientSurfaces.add(clientSurface);
            for (String key : clientSurface.keybinds) {
                if (!p.keybindNames.contains(key)) p.keybindNames.add(key);
            }
        }
        RendererBytecodeScanner.Surface rendererSurface =
                RendererBytecodeScanner.inspect(node, nodes, rendererModelFields);
        if (rendererSurface != null) p.rendererSurfaces.add(rendererSurface);
        p.serverMutations.addAll(ServerMutationScanner.inspect(node));
        p.blockUiRoutes.addAll(BlockUiScanner.inspect(node, nodes));
        BehaviorBytecodeScanner.BehaviorSurface behaviorSurface =
                BehaviorBytecodeScanner.inspect(node, nodes, behaviorRegistryFields);
        if (behaviorSurface != null) p.behaviors.add(behaviorSurface);
        boolean menu = isSubclass(node, "net/minecraft/world/inventory/AbstractContainerMenu", nodes);
        // Client-only configuration screens (Create's station/assembly UI is a representative
        // case) do not extend AbstractContainerScreen and therefore have no MenuType packet for
        // a vanilla client. Their background asset is still useful to the server-side menu bridge.
        boolean screen = isSubclass(node, "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen", nodes)
                || isSubclass(node, "net/minecraft/client/gui/screens/Screen", nodes);
        // An unresolved client-library superclass can hide the Screen inheritance chain in a
        // nested jar. Keep concrete *Screen classes, but never promote every GuiGraphics helper,
        // widget, row or overlay into a standalone screen asset.
        String simpleClientName = node.name.substring(node.name.lastIndexOf('/') + 1);
        boolean unresolvedNamedScreen = simpleClientName.endsWith("Screen")
                && node.methods.stream().anyMatch(method -> method.desc != null
                && method.desc.contains("Lnet/minecraft/client/gui/GuiGraphics;"));
        boolean be = isSubclass(node, "net/minecraft/world/level/block/entity/BlockEntity", nodes);
        boolean entity = isSubclass(node, "net/minecraft/world/entity/Entity", nodes);

        if (menu) {
            MenuInfo mi = analyzeMenu(node, nodes);
            // Button-only/config menus can legitimately expose zero inventory slots. Keeping only
            // slot-bearing menus made those screens invisible to the server-side GUI bridge even
            // though their clickMenuButton/widget actions were discoverable. Record every concrete
            // menu class; abstract bases still stay out of the runtime profile.
            if ((node.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0) {
                p.menus.add(mi);
            }
        }
        if ((screen || unresolvedNamedScreen)
                && (node.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0) {
            p.screens.add(analyzeScreen(node, nodes));
        }
        if (be) {
            EntityInfo info = new EntityInfo();
            info.clazz = node.name;
            collectInterfaces(node, info.interfaces);
            collectShapes(node, info);
            p.blockEntities.add(info);
        } else if (entity) {
            EntityInfo info = new EntityInfo();
            info.clazz = node.name;
            collectInterfaces(node, info.interfaces);
            p.entities.add(info);
        }
        if ((node.access & Opcodes.ACC_INTERFACE) == 0
                && (isSubclass(node, "net/minecraft/world/level/block/Block", nodes)
                || isSubclass(node, "net/minecraft/world/level/block/EntityBlock", nodes))) {
            analyzeBlock(node, nodes, p);
        }
        if (isFluid(node, nodes)) {
            p.fluids.add(node.name);
        }
        collectRegistryKeys(node, p);
        // Key mappings are commonly registered from a client setup/event class rather than a
        // Screen subclass. Scanning only screens made the server-only capture miss most binds.
        collectKeybinds(node, p);
        collectClientInputActions(node, nodes, p);
    }

    private static void collectClientInputActions(ClassNode node, Map<String, ClassNode> nodes,
                                                  Profile profile) {
        for (MethodNode method : node.methods) {
            String lowerDesc = method.desc.toLowerCase(Locale.ROOT);
            String lowerName = method.name.toLowerCase(Locale.ROOT);
            boolean mouseInput = lowerDesc.contains("inputevent$mousebutton")
                    || lowerDesc.contains("mousebuttonevent")
                    || lowerName.contains("mouseclick") || lowerName.contains("mousebutton");
            if (!mouseInput) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof TypeInsnNode typeInsn)
                        || typeInsn.getOpcode() != Opcodes.NEW) continue;
                String packetClass = typeInsn.desc;
                ClassNode packetNode = nodes.get(packetClass);
                if (packetNode == null) continue;
                String simple = packetClass.substring(packetClass.lastIndexOf('/') + 1)
                        .toLowerCase(Locale.ROOT);
                if (!(simple.contains("serverbound") || simple.contains("c2s")
                        || simple.contains("packet") || simple.contains("message"))) continue;
                for (AbstractInsnNode next = instruction.getNext(); next != null; next = next.getNext()) {
                    if (next instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                            && call.owner.equals(packetClass) && call.name.equals("<init>")) {
                        if ("(Z)V".equals(call.desc)) {
                            boolean duplicate = profile.inputActions.stream().anyMatch(existing ->
                                    packetClass.equals(existing.packetClass)
                                            && call.desc.equals(existing.constructorDesc));
                            if (!duplicate) {
                                InputActionInfo info = new InputActionInfo();
                                info.packetClass = packetClass;
                                info.constructorDesc = call.desc;
                                profile.inputActions.add(info);
                            }
                        }
                        break;
                    }
                    if (next instanceof TypeInsnNode other && other.getOpcode() == Opcodes.NEW) break;
                }
            }
        }
    }

    /** Blocks: interesting blockstate property names (orientation/state flags) registered in
     *  createBlockStateDefinition, plus a flags hint for translation (redstone, light). */
    private static void analyzeBlock(ClassNode node, Map<String, ClassNode> nodes, Profile p) {
        EntityInfo info = new EntityInfo();
        info.clazz = node.name;
        Set<String> properties = new LinkedHashSet<>();
        boolean fluidCarrier = node.name.contains("Fluid") || node.name.contains("Liquid");
        for (MethodNode m : node.methods) {
            if (m.name.contains("StateDefinition") || m.name.contains("BlockState")) {
                for (AbstractInsnNode insn : m.instructions) {
                    if (insn instanceof LdcInsnNode l && l.cst instanceof String s
                            && INTERESTING_PROPERTIES.contains(s)) {
                        properties.add(s);
                    }
                }
            }
        }
        collectBlockEntityOwnership(node, nodes, info.blockEntityTypes);
        info.blockStateProperties.addAll(properties);
        if (!properties.containsAll(java.util.Set.of("axis", "facing"))) {
            // not an orientable block by property name
        }
        p.blocks.add(info);
    }

    /**
     * Recover the Block -> BlockEntity edge from inherited factory methods. Scanning every class
     * literal/NEW in a Block is too broad: recipe helpers and placement code can mention unrelated
     * block entities and would then poison renderer ownership. Restrict evidence to methods whose
     * return shape itself proves that they are a BE class/factory, and walk the block hierarchy so
     * inherited IBE-style getBlockEntityClass() implementations are retained.
     */
    private static void collectBlockEntityOwnership(ClassNode block, Map<String, ClassNode> nodes,
                                                    List<String> out) {
        ClassNode current = block;
        Set<String> visited = new HashSet<>();
        for (int depth = 0; current != null && depth < 24 && visited.add(current.name); depth++) {
            if (current.methods != null) {
                for (MethodNode method : current.methods) {
                    if (method.desc == null || method.instructions == null) continue;
                    org.objectweb.asm.Type returned;
                    org.objectweb.asm.Type[] args;
                    try {
                        returned = org.objectweb.asm.Type.getReturnType(method.desc);
                        args = org.objectweb.asm.Type.getArgumentTypes(method.desc);
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }
                    boolean returnsClass = args.length == 0
                            && returned.getSort() == org.objectweb.asm.Type.OBJECT
                            && "java/lang/Class".equals(returned.getInternalName());
                    boolean returnsBlockEntity = returned.getSort() == org.objectweb.asm.Type.OBJECT
                            && isSubclassName(returned.getInternalName(),
                            "net/minecraft/world/level/block/entity/BlockEntity", nodes);
                    if (!returnsClass && !returnsBlockEntity) continue;

                    for (AbstractInsnNode insn : method.instructions) {
                        String candidate = null;
                        if (returnsClass && insn instanceof LdcInsnNode ldc
                                && ldc.cst instanceof org.objectweb.asm.Type type
                                && type.getSort() == org.objectweb.asm.Type.OBJECT) {
                            candidate = type.getInternalName();
                        } else if (returnsBlockEntity && insn instanceof TypeInsnNode type
                                && type.getOpcode() == Opcodes.NEW) {
                            candidate = type.desc;
                        }
                        if (candidate != null && isSubclassName(candidate,
                                "net/minecraft/world/level/block/entity/BlockEntity", nodes)
                                && !out.contains(candidate)) {
                            out.add(candidate);
                        }
                    }
                }
            }
            current = current.superName == null || nodes == null ? null : nodes.get(current.superName);
        }
    }

    private static boolean isSubclassName(String type, String root, Map<String, ClassNode> nodes) {
        if (type == null || root == null) return false;
        if (root.equals(type)) return true;
        String current = type;
        Set<String> seen = new HashSet<>();
        while (current != null && seen.add(current)) {
            ClassNode candidate = nodes == null ? null : nodes.get(current);
            if (candidate == null) break;
            if (root.equals(candidate.superName)) return true;
            current = candidate.superName;
        }
        ClassNode candidate = nodes == null ? null : nodes.get(type);
        if (candidate != null && candidate.methods != null) {
            for (MethodNode method : candidate.methods) {
                if ("<init>".equals(method.name) && method.desc != null
                        && method.desc.startsWith("(Lnet/minecraft/world/level/block/entity/BlockEntityType;"
                        + "Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/world/level/block/state/BlockState;")) return true;
            }
        }
        return false;
    }

    private static boolean isFluid(ClassNode node, Map<String, ClassNode> nodes) {
        Set<String> seen = new HashSet<>();
        String cur = node.superName;
        while (cur != null && seen.add(cur)) {
            if (FLUID_ROOTS.contains(cur)) return true;
            ClassNode parent = nodes.get(cur);
            cur = parent == null ? null : parent.superName;
        }
        return false;
    }

    /** Vanilla superclasses that terminate a hierarchy walk (their names never appear in mod
     *  jars except as direct references; chains through them must still match). */
    private static final java.util.Set<String> VANILLA_ENTITY_ROOTS = java.util.Set.of(
            "net/minecraft/world/entity/Entity",
            "net/minecraft/world/entity/LivingEntity",
            "net/minecraft/world/entity/Mob",
            "net/minecraft/world/entity/PathfinderMob",
            "net/minecraft/world/entity/Animal",
            "net/minecraft/world/entity/AgeableMob",
            "net/minecraft/world/entity/TamableAnimal",
            "net/minecraft/world/entity/AmbientCreature",
            "net/minecraft/world/entity/Creature",
            "net/minecraft/world/entity/Monster",
            "net/minecraft/world/entity/AbstractGolem",
            "net/minecraft/world/entity/player/AbstractPlayer",
            "net/minecraft/world/entity/WaterAnimal",
            "net/minecraft/world/entity/AbstractFish",
            "net/minecraft/world/entity/FlyingMob",
            "net/minecraft/world/entity/npc/AbstractVillager",
            "net/minecraft/world/entity/animal/AbstractHorse",
            "net/minecraft/world/entity/raid/Raider",
            "net/minecraft/world/entity/decoration/Attachment",
            "net/minecraft/world/entity/vehicle/Dismountable",
            "net/minecraft/world/entity/vehicle/AbstractMinecart");

    private static final java.util.Set<String> VANILLA_MENU_ROOTS = java.util.Set.of(
            "net/minecraft/world/inventory/AbstractContainerMenu",
            "net/minecraft/world/inventory/AbstractFurnaceMenu",
            "net/minecraft/world/inventory/RecipeBookMenu",
            "net/minecraft/world/inventory/AbstractCraftingMenu",
            "net/minecraft/world/inventory/ItemCombinerMenu",
            "net/minecraft/world/inventory/InventoryMenu",
            "net/minecraft/world/inventory/ChestMenu",
            "net/minecraft/world/inventory/HopperMenu",
            "net/minecraft/world/inventory/DispenserMenu",
            "net/minecraft/world/inventory/ShulkerBoxMenu",
            "net/minecraft/world/inventory/BrewingStandMenu",
            "net/minecraft/world/inventory/EnchantmentMenu",
            "net/minecraft/world/inventory/MerchantMenu",
            "net/minecraft/world/inventory/BeaconMenu",
            "net/minecraft/world/inventory/LoomMenu",
            "net/minecraft/world/inventory/StonecutterMenu",
            "net/minecraft/world/inventory/CartographyTableMenu",
            "net/minecraft/world/inventory/GrindstoneMenu",
            "net/minecraft/world/inventory/SmithingMenu",
            "net/minecraft/world/inventory/AnvilMenu",
            "net/minecraft/world/inventory/CrafterMenu");

    private static boolean isSubclass(ClassNode node, String target, Map<String, ClassNode> nodes) {
        if (node == null || target == null) return false;
        Set<String> seen = new HashSet<>();
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
        if (node.superName != null) pending.addLast(node.superName);
        if (node.interfaces != null) {
            for (String iface : node.interfaces) if (iface != null) pending.addLast(iface);
        }
        while (!pending.isEmpty()) {
            String cur = pending.removeFirst();
            if (cur == null || !seen.add(cur)) continue;
            if (cur.equals(target)) return true;
            if (target.equals("net/minecraft/world/inventory/AbstractContainerMenu")
                    && VANILLA_MENU_ROOTS.contains(cur)) return true;
            if (VANILLA_ENTITY_ROOTS.contains(cur) && cur.startsWith("net/minecraft/world/entity/")
                    && target.equals("net/minecraft/world/entity/Entity")) return true;
            ClassNode parent = nodes.get(cur);
            if (parent == null) continue;
            if (parent.superName != null) pending.addLast(parent.superName);
            if (parent.interfaces != null) {
                for (String iface : parent.interfaces) if (iface != null) pending.addLast(iface);
            }
        }
        return false;
    }

    /** Counts addSlot() calls across the constructor and any slot-adding helper methods it
     *  invokes (mods commonly call addSlots()/initSlots() from <init>). */
    private static MenuInfo analyzeMenu(ClassNode node, Map<String, ClassNode> nodes) {
        MenuInfo mi = new MenuInfo();
        mi.menuClass = node.name;
        boolean hasPlayerInv = false;
        for (MethodNode m : node.methods) {
            if (!m.name.equals("<init>")) continue;
            if (m.desc.contains("Lnet/minecraft/world/entity/player/Inventory;")) {
                hasPlayerInv = true;
            }
        }
        int[] counter = new int[1];
        Map<Integer, String> slotKinds = new HashMap<>();
        Set<String> visited = new HashSet<>();
        for (MethodNode m : node.methods) {
            if (m.name.equals("<init>")) {
                collectSlots(node, m, nodes, visited, counter, slotKinds, 0);
            }
        }
        mi.totalSlots = counter[0];
        for (Map.Entry<Integer, String> e : slotKinds.entrySet()) {
            if (!e.getValue().equals("net/minecraft/world/inventory/Slot")) {
                mi.buttonSlots.add(e.getKey());
            }
        }
        if (hasPlayerInv && mi.totalSlots > 36) {
            mi.containerSlots = mi.totalSlots - 36;
        } else {
            mi.containerSlots = mi.totalSlots;
        }
        return mi;
    }

    /** abstract method descriptor of Slot-adding on AbstractContainerMenu; STABLE across
     *  SRG remapping (method names like addSlot become m_38897_ in shipped jars) */
    private static final String ADD_SLOT_DESC =
            "(Lnet/minecraft/world/inventory/Slot;)Lnet/minecraft/world/inventory/Slot;";

    /** DFS over slot-adding helper methods (max depth 3). Walks the whole ancestor chain of
     *  the menu (all methods of every chain class), so slot code living in concrete overrides
     *  with erased-generic descriptors (e.g. init(Inventory, Object) -> init(Inventory, T))
     *  is found without descriptor matching. addSlot is matched by DESCRIPTOR (its name is
     *  SRG-remapped in shipped mod jars). */
    private static void collectSlots(ClassNode owner, MethodNode m,
                                     Map<String, ClassNode> nodes,
                                     Set<String> visited, int[] counter,
                                     Map<Integer, String> slotKinds, int depth) {
        if (depth > 3 || owner == null || !visited.add(owner.name + m.name + m.desc)) return;
        // chain classes: the current class up to the vanilla root (all members get walked)
        Set<String> chain = new HashSet<>();
        ClassNode cur = owner;
        while (cur != null) {
            chain.add(cur.name);
            cur = nodes.get(cur.superName);
        }
        for (AbstractInsnNode insn : m.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.desc.equals(ADD_SLOT_DESC)) {
                int idx = counter[0]++;
                // slot class = the NEW/INVOKESPECIAL ctor right before addSlot
                String slotType = "net/minecraft/world/inventory/Slot";
                AbstractInsnNode prev = call.getPrevious();
                int guard = 0;
                while (prev != null && guard++ < 12) {
                    if (prev instanceof MethodInsnNode c2 && c2.getOpcode() == Opcodes.INVOKESPECIAL
                            && c2.name.equals("<init>")) {
                        slotType = c2.owner;
                        break;
                    }
                    prev = prev.getPrevious();
                }
                slotKinds.putIfAbsent(idx, slotType);
                continue;
            }
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && chain.contains(call.owner)) {
                // slot-adding helper in the same hierarchy; visit its (possibly overridden)
                // body. Resolve through the ACTUAL class graph: file owners may be abstract
                // and concrete bodies live one level down with different (erased) descs -
                // so instead of desc-matching, walk the helper's own method when found in
                // the declaring class, then fall through to any override in the chain.
                ClassNode helperOwner = nodes.get(call.owner);
                MethodNode helper = helperOwner == null ? null : methodOf(helperOwner, call.name, call.desc);
                if (helper != null && helper != m) {
                    collectSlots(helperOwner, helper, nodes, visited, counter, slotKinds, depth + 1);
                }
            }
        }
        // also walk every OTHER method the chain classes declare (concrete overrides with
        // erased descriptors wouldn't be reached via call sites otherwise).
        for (String className : chain) {
            ClassNode c = nodes.get(className);
            if (c == null) continue;
            for (MethodNode mm : c.methods) {
                if (mm == m) continue;
                if (mm.name.equals("<init>") || mm.name.equals("<clinit>")) continue;
                collectSlots(c, mm, nodes, visited, counter, slotKinds, depth + 1);
            }
        }
    }

    private static MethodNode methodOf(ClassNode c, String name, String desc) {
        for (MethodNode m : c.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) return m;
        }
        return null;
    }

    /**
     * Screens are sometimes parameterized with an abstract menu family while the actual MenuType
     * instantiates one loader-specific concrete subclass (service-loader implementations are a
     * common pattern). Prefer that unique concrete descendant so runtime menu-class lookup remains
     * exact. If the family has multiple concrete descendants, keep the declared family rather than
     * guessing which implementation is active.
     */
    private static List<String> concreteMenuClasses(String candidateName, Map<String, ClassNode> nodes) {
        if (candidateName == null || nodes == null) return List.of();
        ClassNode candidate = nodes.get(candidateName);
        if (candidate == null || !isSubclass(candidate,
                "net/minecraft/world/inventory/AbstractContainerMenu", nodes)) return List.of();
        if ((candidate.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0) {
            return List.of(candidateName);
        }
        List<String> resolved = new ArrayList<>();
        for (ClassNode possible : nodes.values()) {
            if (possible == null || possible.name == null || possible.name.equals(candidateName)
                    || (possible.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) continue;
            if (!isSubclass(possible, candidateName, nodes)
                    || !isSubclass(possible, "net/minecraft/world/inventory/AbstractContainerMenu", nodes)) {
                continue;
            }
            resolved.add(possible.name);
        }
        resolved.sort(String::compareTo);
        return resolved.isEmpty() ? List.of(candidateName) : resolved;
    }

    private static boolean bindMenuClasses(ScreenInfo screen, String candidateName,
                                           Map<String, ClassNode> nodes) {
        List<String> resolved = concreteMenuClasses(candidateName, nodes);
        if (resolved.isEmpty()) return false;
        // Preserve the declared abstract family as the primary key when several concrete menus
        // share it, while recording every exact runtime class as an alias. A unique concrete
        // descendant remains the primary binding, preserving the old behavior.
        String primary = resolved.size() == 1 ? resolved.get(0) : candidateName;
        screen.menuClass = primary.replace('/', '.');
        for (String menu : resolved) {
            String normalized = menu.replace('/', '.');
            if (!screen.menuClasses.contains(normalized)) screen.menuClasses.add(normalized);
        }
        return true;
    }

    private static ScreenInfo analyzeScreen(ClassNode node, Map<String, ClassNode> nodes) {
        ScreenInfo si = new ScreenInfo();
        si.screenClass = node.name;
        // menu type from the generic superclass signature e.g. ...AbstractContainerScreen<Lmod/XMenu;>;
        String sig = node.signature;
        if (sig != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("Screen<L([^;]+);>").matcher(sig);
            if (m.find()) {
                String candidateName = m.group(1);
                bindMenuClasses(si, candidateName, nodes);
            }
        }
        if (si.menuClass == null) {
            for (MethodNode method : node.methods) {
                if (!"<init>".equals(method.name) || method.desc == null) continue;
                try {
                    for (org.objectweb.asm.Type argument : org.objectweb.asm.Type.getArgumentTypes(method.desc)) {
                        if (argument.getSort() != org.objectweb.asm.Type.OBJECT) continue;
                        if (bindMenuClasses(si, argument.getInternalName(), nodes)) break;
                    }
                } catch (IllegalArgumentException ignored) {
                }
                if (si.menuClass != null) break;
            }
        }
        for (FieldNode f : node.fields) {
            String fn = f.name;
            if (fn.equals("f_97726_") || fn.equals("imageWidth")) si.width = intInit(f);
            if (fn.equals("f_97727_") || fn.equals("imageHeight")) si.height = intInit(f);
        }
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD) {
                    Integer value = previousInt(field.getPrevious());
                    if (value != null && value > 0 && value <= 2048) {
                        if (field.name.equals("f_97726_") || field.name.equals("imageWidth")) si.width = value;
                        if (field.name.equals("f_97727_") || field.name.equals("imageHeight")) si.height = value;
                    }
                }
                if (!(insn instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String value)) continue;
                String lower = value.toLowerCase(Locale.ROOT).replace('\\', '/');
                if (lower.endsWith(".png") && (lower.contains("gui/")
                        || lower.contains("screen/") || lower.contains("textures/"))) {
                    if (!si.textureAssets.contains(lower)) si.textureAssets.add(lower);
                }
            }
        }
        inferInheritedScreenSize(si, node);
        int[] sharedSize = GuiSpriteBytecodeScanner.screenSizeHint(node, nodes);
        if (si.width <= 0 && sharedSize[0] > 0) si.width = sharedSize[0];
        if (si.height <= 0 && sharedSize[1] > 0) si.height = sharedSize[1];
        if (isSubclass(node, "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen", nodes)) {
            if (si.width <= 0) si.width = 176;
            if (si.height <= 0) si.height = 166;
        }
        return si;
    }

    /** Screens built on small GUI libraries often pass their logical panel size to a shared
     * superclass constructor instead of assigning imageWidth/imageHeight themselves. Recover the
     * final two integer constructor arguments when the direct superclass accepts that shape. This
     * is bytecode-owned layout evidence and avoids treating a 256px atlas as the visible screen. */
    private static void inferInheritedScreenSize(ScreenInfo screen, ClassNode node) {
        if (screen == null || node == null || node.methods == null || node.superName == null
                || screen.width > 0 && screen.height > 0 || screen.textureAssets.isEmpty()) return;
        for (MethodNode method : node.methods) {
            if (!"<init>".equals(method.name) || method.instructions == null) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || !"<init>".equals(call.name) || !node.superName.equals(call.owner)) continue;
                org.objectweb.asm.Type[] args;
                try { args = org.objectweb.asm.Type.getArgumentTypes(call.desc); }
                catch (IllegalArgumentException ignored) { continue; }
                if (args.length < 2 || args[args.length - 1].getSort() != org.objectweb.asm.Type.INT
                        || args[args.length - 2].getSort() != org.objectweb.asm.Type.INT) continue;
                java.util.ArrayList<Integer> constants = new java.util.ArrayList<>(2);
                int remaining = 24;
                for (AbstractInsnNode cursor = call.getPrevious(); cursor != null && remaining-- > 0
                        && constants.size() < 2; cursor = cursor.getPrevious()) {
                    Integer value = previousInt(cursor);
                    if (value != null) constants.add(value);
                }
                if (constants.size() < 2) continue;
                int height = constants.get(0);
                int width = constants.get(1);
                if (screen.width <= 0 && width > 0 && width <= 2048) screen.width = width;
                if (screen.height <= 0 && height > 0 && height <= 2048) screen.height = height;
                return;
            }
        }
    }

    private static Integer previousInt(AbstractInsnNode insn) {
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) return value;
        if (insn instanceof IntInsnNode integer) return integer.operand;
        if (insn == null) return null;
        return switch (insn.getOpcode()) {
            case Opcodes.ICONST_M1 -> -1;
            case Opcodes.ICONST_0 -> 0;
            case Opcodes.ICONST_1 -> 1;
            case Opcodes.ICONST_2 -> 2;
            case Opcodes.ICONST_3 -> 3;
            case Opcodes.ICONST_4 -> 4;
            case Opcodes.ICONST_5 -> 5;
            default -> null;
        };
    }

    private static int intInit(FieldNode f) {
        if (f.value instanceof Integer v) return v;
        return -1;
    }

    private static void collectInterfaces(ClassNode node, List<String> out) {
        out.addAll(node.interfaces);
    }

    private static void collectShapes(ClassNode node, EntityInfo info) {
        for (MethodNode m : node.methods) {
            String desc = m.desc;
            info.methodShapes.add(m.name + ":" + desc);
            String name = m.name.toLowerCase(java.util.Locale.ROOT);
            if (name.contains("speed") || name.contains("rpm")) info.hints.add("speed");
            if (name.contains("angle") || name.contains("rotation")) info.hints.add("angle");
            if (name.contains("shaft")) info.hints.add("shaft");
            if (name.contains("head")) info.hints.add("head");
            if (name.contains("belt") || name.contains("scroll")) info.hints.add("belt");
            if (name.contains("helditem") || name.contains("displayeditem")) info.hints.add("helditem");
        }
        for (String itf : node.interfaces) {
            String i = itf.toLowerCase(java.util.Locale.ROOT);
            if (i.contains("energy")) info.hints.add("energy");
            if (i.contains("fluidexport") || i.contains("fluidd") || i.contains("tank")) info.hints.add("fluid");
            if (i.contains("kinetic") || i.contains("rotational")) info.hints.add("kinetic");
        }
    }

    /** Harvests candidate registry keys: creator-desc matches (with ns+path pairing), plain
     *  LDC registrations in SoundEvent/ParticleType fields, and - for classes clearly named
     *  after sounds/particles - ALL string constants in <clinit> (Create's AllSoundEvents
     *  registers through builders; the ids are still plain strings there). Noisy on purpose;
     *  validated against the live registries on load. */
    private static void collectRegistryKeys(ClassNode node, Profile p) {
        boolean soundishClass = node.name.toLowerCase(java.util.Locale.ROOT).contains("sound")
                || node.name.toLowerCase(java.util.Locale.ROOT).contains("soundevent");
        boolean particleishClass = node.name.toLowerCase(java.util.Locale.ROOT).contains("particle");
        for (MethodNode m : node.methods) {
            if (!m.name.equals("<clinit>") && !m.name.equals("<init>")) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof LdcInsnNode l && l.cst instanceof String s) {
                    if (soundishClass && s.matches("[a-z0-9_./-]+") && !s.contains(":")) {
                        p.soundIds.add(s);
                    } else if (particleishClass && s.matches("[a-z0-9_./-]+") && !s.contains(":")) {
                        p.particleIds.add(s);
                    }
                    continue;
                }
                if (insn instanceof MethodInsnNode call) {
                    String desc = call.desc;
                    boolean soundCreator = desc.endsWith(")Lnet/minecraft/sounds/SoundEvent;")
                            || desc.endsWith("Lnet/minecraft/sounds/SoundEvent;");
                    boolean particleCreator = desc.endsWith("Lnet/minecraft/core/particles/ParticleType;")
                            || desc.endsWith("Lnet/minecraft/core/particles/SimpleParticleType;");
                    if (!soundCreator && !particleCreator) continue;
                    String id = ldcArg(call);
                    if (id != null) {
                        if (soundCreator) p.soundIds.add(id);
                        else p.particleIds.add(id);
                    }
                }
            }
        }
        // plain LDC registrations in static fields of SoundEvent/ParticleType type
        for (FieldNode f : node.fields) {
            String d = f.desc;
            boolean soundField = d.endsWith("Lnet/minecraft/sounds/SoundEvent;");
            boolean particleField = d.endsWith("Lnet/minecraft/core/particles/ParticleType;")
                    || d.endsWith("Lnet/minecraft/core/particles/SimpleParticleType;");
            if (!soundField && !particleField) continue;
            if (f.value instanceof String s && s.matches("[a-z0-9_./-]+")) {
                if (soundField) p.soundIds.add(s);
                else p.particleIds.add(s);
            }
        }
    }

    /** Resolves the id passed to a registry/creator call: a single LDC, a two-LDC
     *  namespace+path pair, or the arguments of an inline `new ResourceLocation(ns, path)`
     *  created right before the call. Null when not string-based. */
    private static String ldcArg(MethodInsnNode call) {
        AbstractInsnNode prev = call.getPrevious();
        if (prev instanceof LdcInsnNode l && l.cst instanceof String s) {
            if (s.contains(":")) return s;
            AbstractInsnNode before = prev.getPrevious();
            if (before instanceof LdcInsnNode l2 && l2.cst instanceof String ns) {
                if (ns.matches("[a-z_0-9]+")) {
                    return ns + ":" + s;
                }
            }
            return s;
        }
        // inline ResourceLocation constructor inside the call's argument expression:
        //   createVariableRangeEvent(new ResourceLocation("create", "block_saw_break"))
        int guard = 0;
        AbstractInsnNode cur = prev;
        while (cur != null && guard++ < 10) {
            if (cur instanceof MethodInsnNode m
                    && m.getOpcode() == Opcodes.INVOKESPECIAL
                    && m.owner.equals("net/minecraft/resources/ResourceLocation")
                    && m.name.equals("<init>")) {
                // two LDC strings just before the ctor = ns + path
                AbstractInsnNode a = cur.getPrevious();
                if (a instanceof LdcInsnNode la && la.cst instanceof String path) {
                    AbstractInsnNode b = a.getPrevious();
                    if (b instanceof LdcInsnNode lb && lb.cst instanceof String ns
                            && ns.matches("[a-z_0-9]+")) {
                        return ns + ":" + path;
                    }
                    return path;
                }
                return null;
            }
            cur = cur.getPrevious();
        }
        return null;
    }

    private static void collectKeybinds(ClassNode node, Profile p) {
        Set<String> found = new LinkedHashSet<>();
        for (FieldNode f : node.fields) {
            String d = f.desc;
            if (!d.endsWith("Lcom/mojang/blaze3d/platform/InputConstants$Key;")
                    && !d.endsWith("Lnet/minecraft/client/KeyMapping;")) {
                continue;
            }
            if (f.value instanceof String s && s.matches("[a-z0-9_.-]+")) {
                if (dev.f2v.capture.ServerCapture.looksLikeKeybind(s)) found.add(s);
            }
        }
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode call)
                        || !call.owner.equals("net/minecraft/client/KeyMapping")
                        || !call.name.equals("<init>")) continue;
                // The translation name/category are LDC strings immediately before the
                // constructor. Walk a bounded window and keep key.* but not categories.
                int remaining = 24;
                for (AbstractInsnNode previous = insn.getPrevious(); previous != null && remaining-- > 0;
                     previous = previous.getPrevious()) {
                    if (previous instanceof LdcInsnNode ldc && ldc.cst instanceof String s
                            && dev.f2v.capture.ServerCapture.looksLikeKeybind(s)) {
                        found.add(s);
                    }
                }
            }
        }
        for (String key : found) {
            if (!p.keybindNames.contains(key)) p.keybindNames.add(key);
        }
    }

    // --- cache + validation ---------------------------------------------------

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson CACHE_GSON = new Gson();

    /** Returns a cached profile for the jar or null. Fingerprint = size:mtime. */
    public static Profile cached(Path jarPath, Path cacheDir) {
        return cached(jarPath, cacheDir, null);
    }

    static Profile cached(Path jarPath, Path cacheDir, String classPathFingerprint) {
        String fp = fingerprint(jarPath);
        if (fp == null) return null;
        Path legacy = cacheDir.resolve(cacheFileName(jarPath, fp, classPathFingerprint));
        Path compressed = cacheDir.resolve(cacheFileName(jarPath, fp, classPathFingerprint) + ".gz");
        Path f = Files.isRegularFile(compressed) ? compressed : legacy;
        if (!Files.isRegularFile(f)) return null;
        try {
            JsonObject object;
            if (f.equals(compressed)) {
                try (var input = new GZIPInputStream(Files.newInputStream(f));
                     var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    object = CACHE_GSON.fromJson(reader, JsonObject.class);
                }
            } else {
                try (var reader = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    object = CACHE_GSON.fromJson(reader, JsonObject.class);
                }
            }
            if (object == null || !object.has("schema")
                    || object.get("schema").getAsInt() < CACHE_SCHEMA) return null;
            Profile profile = CACHE_GSON.fromJson(object, Profile.class);
            if (profile == null || profile.schema < CACHE_SCHEMA) return null;
            if (f.equals(legacy)) store(jarPath, cacheDir, profile, classPathFingerprint);
            return profile;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static void store(Path jarPath, Path cacheDir, Profile p) {
        store(jarPath, cacheDir, p, null);
    }

    static void store(Path jarPath, Path cacheDir, Profile p, String classPathFingerprint) {
        String fp = fingerprint(jarPath);
        if (fp == null) return;
        try {
            Files.createDirectories(cacheDir);
            Path target = cacheDir.resolve(cacheFileName(jarPath, fp, classPathFingerprint) + ".gz");
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.deleteIfExists(temp);
            try (var output = new GZIPOutputStream(Files.newOutputStream(temp), 64 * 1024);
                 var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                CACHE_GSON.toJson(p, writer);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
            cleanupStaleCaches(cacheDir, jarPath, target);
        } catch (IOException e) {
            F2VLog.warn("[vm] discovery cache write failed: " + e);
        }
    }

    private static void cleanupStaleCaches(Path cacheDir, Path jarPath, Path keep) {
        String prefix = jarPath.getFileName() + ".";
        try (var files = Files.list(cacheDir)) {
            for (Path candidate : files.toList()) {
                if (candidate.equals(keep) || !Files.isRegularFile(candidate)) continue;
                String name = candidate.getFileName().toString();
                if (name.startsWith(prefix) && (name.endsWith(".json") || name.endsWith(".json.gz"))) {
                    Files.deleteIfExists(candidate);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static String cacheFileName(Path jarPath, String jarFingerprint,
                                        String classPathFingerprint) {
        String classPath = classPathFingerprint == null || classPathFingerprint.isBlank()
                ? "" : ".cp-" + classPathFingerprint.replaceAll("[^a-zA-Z0-9_-]", "_");
        return jarPath.getFileName() + "." + jarFingerprint + classPath + ".json";
    }

    public static String fingerprint(Path jarPath) {
        try {
            long size = Files.size(jarPath);
            long mtime = Files.getLastModifiedTime(jarPath).toMillis();
            return Long.toHexString(size) + "-" + Long.toHexString(mtime);
        } catch (IOException e) {
            return null;
        }
    }
}
