package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Learns renderer-owned model parts from client bytecode without loading renderer classes.
 *
 * <p>Many mod blocks have a deliberately incomplete blockstate model because their block-entity
 * renderer composes extra partials at runtime. The scanner resolves static model handles from
 * class initializers (for example, a string passed to a PartialModel factory and stored in a
 * static field), then follows the renderer's actual GETSTATIC references. Pack generation can use
 * those exact references instead of guessing that every JSON beside the block belongs to it.</p>
 */
public final class RendererBytecodeScanner {

    private static final int MAX_MODEL_REFS = 96;
    private static final Set<String> GENERIC_TOKENS = Set.of(
            "renderer", "render", "block", "entity", "tile", "standard", "default",
            "abstract", "client", "model", "models", "base", "impl", "item", "inventory");
    private record CachedBlockRenderPlan(long generation, BlockRenderPlan plan) {}
    private static final ConcurrentHashMap<String, CachedBlockRenderPlan> DISCOVERED_BLOCK_PLANS =
            new ConcurrentHashMap<>();

    private RendererBytecodeScanner() {}

    /** One renderer class and the model resources its render path actually reads. */
    public static final class Surface {
        public String clazz;
        /** True only when this renderer inherits vanilla EntityRenderer. Kept separate from
         * entityTypes because non-entity render frameworks can still mention entity classes in
         * helper signatures (Create bogey rendering is one example). */
        public boolean entityRenderer;
        /** Abstract renderer bases are useful evidence for their concrete subclasses but are never
         * independently registered entity renderers and should not count as missing bindings. */
        public boolean abstractType;
        public final Set<String> modelRefs = new LinkedHashSet<>();
        /** Model handles recovered from renderer-local nested helper/enum classes. */
        public final Set<String> nestedModelRefs = new LinkedHashSet<>();
        /** Concrete block-entity types accepted by renderer roots. */
        public final Set<String> blockEntityTypes = new LinkedHashSet<>();
        /** Model ref -> concrete block-entity types whose render roots read that ref. */
        public final Map<String, Set<String>> modelRefTargets = new LinkedHashMap<>();
        /** Model ref -> number of renderer instances emitted from one render invocation. This is
         * recovered from bounded counted render loops instead of filename guesses (for example a
         * renderer that draws one authored flap strip four times). */
        public final Map<String, Integer> modelRefCopies = new LinkedHashMap<>();
        /** Exact boolean branch which owns a renderer model. This prevents optional renderer
         * components (pulleys, guards, indicators, etc.) from being guessed from method names at
         * runtime. The expression contains only server-safe BlockEntity fields/methods. */
        public final Map<String, BooleanPredicate> modelPredicates = new LinkedHashMap<>();
        /** Concrete entity classes rendered by this surface, recovered from render/texture method
         * descriptors. Kept separately from semantic class-name matching so renderer-owned texture
         * evidence can be applied to registry entities whose names differ from the renderer. */
        public final Set<String> entityTypes = new LinkedHashSet<>();
        /** Server-readable Bedrock/renderer asset-family tokens exposed by a model repository used
         * by this renderer. These are structural hints only: pack generation still requires an
         * exact matching asset family before using one. */
        public final Set<String> entityAssetFamilyHints = new LinkedHashSet<>();
        /** Enum state constant -> branch-local string/method tokens recovered from client state
         * dispatch. Pack generation intersects these tokens with real animation clip names, so the
         * scanner never guesses that an arbitrary string is an animation. */
        public final Map<String, Set<String>> entityStateAnimationHints = new LinkedHashMap<>();
        /** Exact zero-argument server accessor chains whose values feed a renderer-owned model
         * repository lookup. Runtime may use only these proven chains to select a generated
         * appearance variant; arbitrary entity methods are never probed. */
        public final List<EntityAppearanceAccessorSource> entityAppearanceAccessorSources = new ArrayList<>();
        /** Server-authoritative component of a renderer-wide uniform PoseStack scale. Unlike
         * ModelSet.entityScale (which only reverses pack normalization), this transform is applied
         * around the live entity origin and therefore scales both multipart geometry and rig
         * offsets. Client-only cosmetic factors are intentionally omitted from this recipe. */
        public EntityRootScaleSource entityRootScaleSource;
        /** Registry paths explicitly bound to this renderer, including differently named NPCs. */
        public final Set<String> entityRegistryPaths = new LinkedHashSet<>();
        /** Registry paths whose direct registration selected this renderer before alternate
         * config/client renderer branches were propagated by {@link #bindRegisteredEntities}.
         * Single-valued renderer contracts must prefer these paths so a secondary branch cannot
         * replace the source mod's shipped/default visual. */
        public final Set<String> primaryEntityRegistryPaths = new LinkedHashSet<>();
        /** Texture paths or StringConcatFactory recipes observed in the renderer bytecode. A \u0001
         * marker denotes one dynamic string insertion (wood type, variant, skin id, ...). */
        public final Set<String> textureRefs = new LinkedHashSet<>();
        /** Simple entity-state selector used by getTextureLocation when the renderer swaps the
         * entire material on one otherwise-identical surface. Pack generation can emit each proven
         * texture once and runtime can select it from the same server-side accessor. */
        public EntityTextureSelector entityTextureSelector;
        /** True when the entity renderer submits a simple quad after applying the render
         * dispatcher's camera quaternion. This is stronger evidence than an entity-name heuristic:
         * pack/runtime code can preserve the renderer's billboard contract even for oddly named
         * projectiles or particles. */
        public boolean cameraFacingQuad;
        /** Uniform PoseStack scale applied before the proven camera-facing quad, in renderer/world
         * units. The canonical quad helpers used by vanilla-style renderers span one unit before
         * this transform, so a value of .5 means the visible surface is half a block wide/high. */
        public float cameraFacingQuadScale;
        /** Entity renderer that draws a live ordered collection of BlockStates with
         * BlockRenderDispatcher rather than a fixed entity mesh. */
        public EntityBlockSequence entityBlockSequence;
        /** Procedural entity geometry which cannot be represented by one static model. The
         * scanner only emits these contracts for structural renderer programs whose live inputs
         * are server-visible: repeated camera-facing members, or a boolean-selected box trail. */
        public EntityProceduralGeometry entityProceduralGeometry;
        /** Direction-aware runtime attachment selectors reached by a model/renderer path. These
         * are kept as owner#name#descriptor strings so the server can match an equivalent method
         * on a live behaviour object without loading client renderer classes. */
        public final Set<String> directionalSelectors = new LinkedHashSet<>();
        /** Boolean per-direction presence predicates reached by a renderer path. Unlike an
         * attachment selector these do not return a model/component; they answer whether the
         * renderer should instantiate its owned directional template for this face (for example
         * a half shaft or connector). */
        public final Set<String> directionalPresenceSelectors = new LinkedHashSet<>();
        /** Branch predicates that control whether a directional renderer part exists even when
         * no helper method is called. Example: iterate Direction.values() and render only when the
         * face axis differs from the blockstate axis. */
        public final List<DirectionalPresenceRule> directionalPresenceRules = new ArrayList<>();
        /** Geometry authored directly through vanilla LayerDefinition/ModelPart bytecode. */
        public ArmorVisualBytecodeScanner.LayerModel layerModel;
        /** ItemStack sources read by a renderer root which subsequently invokes ItemRenderer.
         * Entries are M#owner#name#desc for zero-arg methods or F#owner#name#desc for fields. */
        public final Set<String> itemStackSources = new LinkedHashSet<>();
        /** ItemDisplayContext enum constants proven to be passed to ItemRenderer for the owned
         * ItemStack source. A single value can be replayed directly by a vanilla ItemDisplay. */
        public final Set<String> itemDisplayContexts = new LinkedHashSet<>();
        /** Exact zero-argument accessor chains from the rendered BlockEntity to a container whose
         * elements are proven by the renderer loop to become ItemStacks passed to ItemRenderer.
         * Each path entry uses the same M#owner#name#desc encoding as {@link #itemStackSources}.
         * Runtime may traverse only these scanner-proven members; arbitrary object graphs are not
         * searched reflectively. */
        public final List<ItemStackCollectionSource> itemStackCollectionSources = new ArrayList<>();
        /** Live renderer loops which repeatedly draw one visual source along a translated axis.
         * This is the bytecode contract used by ropes, hoses, elevator belts, hanging chains and
         * similar extendable renderers: a numeric BE value drives a whole-segment loop, an
         * optional fractional segment, and an optional terminal piece. */
        public final List<LinearRepeat> linearRepeats = new ArrayList<>();
    }

    public static final class ItemStackCollectionSource {
        public final List<String> accessors = new ArrayList<>();

        public ItemStackCollectionSource() {}

        ItemStackCollectionSource(Collection<String> accessors) {
            if (accessors != null) this.accessors.addAll(accessors);
        }
    }

    public static final class EntityAppearanceAccessorSource {
        public final List<String> accessors = new ArrayList<>();

        public EntityAppearanceAccessorSource() {}

        EntityAppearanceAccessorSource(Collection<String> accessors) {
            if (accessors != null) this.accessors.addAll(accessors);
        }
    }

    public static final class EntityRootScaleSource {
        public float constant = 1f;
        public final List<List<String>> accessorPaths = new ArrayList<>();

        public EntityRootScaleSource() {}

        EntityRootScaleSource(float constant, Collection<? extends Collection<String>> paths) {
            this.constant = constant;
            if (paths != null) for (Collection<String> path : paths) {
                if (path != null && !path.isEmpty()) this.accessorPaths.add(List.copyOf(path));
            }
        }
    }

    /** Declarative full-surface texture selection recovered from an entity renderer. The current
     * bytecode proof covers the common boolean branch shape; the map form deliberately leaves room
     * for integer/enum selectors without changing pack metadata later. Variant key {@code 0} is
     * the true branch for {@code selectorKind=boolean}; {@link #defaultTexture} is the false branch. */
    public static final class EntityTextureSelector {
        public String selectorKind;
        public String selector;
        public String defaultTexture;
        public final Map<Integer, String> variants = new LinkedHashMap<>();
    }

    /** Declarative contract for an entity whose renderer reuses ordinary block models as an
     * ordered local-space sequence (rockets assembled from actual loaded blocks are one example).
     * Accessor names are recovered from renderer bytecode so runtime never guesses mod APIs. */
    public static final class EntityBlockSequence {
        /** Optional zero-argument entity method returning one BlockState rendered as the body. */
        public String blockAccessor;
        /** Zero-argument entity method returning Collection/List<BlockState>. */
        public String blocksAccessor;
        /** Optional zero-argument entity method returning a world-space orientation Vec3. */
        public String orientationAccessor;
        /** Optional zero-argument entity method returning Direction when no vector is available. */
        public String directionAccessor;
        /** Local translation axis used for successive block origins: 0=X, 1=Y, 2=Z. */
        public int axis = 1;
        /** Signed block-space distance between successive block origins. */
        public float step = 1f;
    }

    public enum EntityProceduralKind {
        BILLBOARD_COLLECTION,
        CONDITIONAL_BOX_TRAIL
    }

    /**
     * Declarative server-safe description of renderer-authored procedural entity geometry.
     *
     * <p>Unlike captured ModelParts this geometry is generated every render from live entity data.
     * Keeping the accessors and dimensions in discovery IR lets the compatibility runtime replay
     * the same program with viewer-only displays without loading any client renderer class.</p>
     */
    public static final class EntityProceduralGeometry {
        public EntityProceduralKind kind;

        /** BILLBOARD_COLLECTION: zero-arg entity accessor returning the live member collection. */
        public String collectionAccessor;
        /** BILLBOARD_COLLECTION: zero-arg member accessor returning double[]/float[]/Vec3 position. */
        public String memberPositionAccessor;
        /** Optional member velocity accessor used for client-interpolation-equivalent smoothing. */
        public String memberVelocityAccessor;
        /** Source billboard span after the renderer's uniform PoseStack scale. */
        public float billboardScale = 1f;
        /** Optional enum/index selector which changes the source billboard's uniform scale. */
        public String billboardScaleSelector;
        public int billboardScaleSelectorOrdinal = -1;
        public float selectedBillboardScale;
        public float defaultBillboardScale;

        /** CONDITIONAL_BOX_TRAIL: exact live predicate selecting the procedural box branch. */
        public BooleanPredicate activePredicate;
        /** Direct zero-argument boolean accessor when the predicate is a single SOURCE method.
         * Pack generation uses this with the existing live entity variant selector. */
        public String activeSelector;
        /** Optional zero-arg entity Vec3 accessor which drives box orientation. */
        public String orientationAccessor;
        /** Cross-section half extent for the ordinary branch. */
        public float halfExtent;
        /** Optional enum/index accessor selecting a second authored half extent. */
        public String extentSelector;
        public int extentSelectorOrdinal = -1;
        public float selectedHalfExtent;
        /** RGB vertex color recovered from the renderer's procedural box call. */
        public int red = 255, green = 255, blue = 255;
        /** Texture consumed by the procedural box render type, when recoverable. */
        public String boxTexture;
        /** Texture consumed by the camera-facing fallback branch, when recoverable. */
        public String billboardTexture;
    }

    /** A visual consumed by a renderer loop. Exactly one source is normally populated. */
    public static final class VisualSource {
        /** Partial/model resource recovered from the renderer's model handle. */
        public String modelRef;
        /** Static server-side registry holder field whose default BlockState is rendered. */
        public String blockField;
        /** Exact zero-argument accessor invoked on {@link #blockField}'s value to obtain the
         * rendered Block/BlockState. Keeping this method in the IR avoids runtime registry-holder
         * name guesses such as get/getDefaultState and makes wrapper registries work generically. */
        public String blockAccessor;

        public boolean present() {
            return modelRef != null && !modelRef.isBlank() || blockField != null && !blockField.isBlank();
        }

        @Override public String toString() {
            return modelRef != null ? modelRef : blockField
                    + (blockAccessor == null ? "" : ">" + blockAccessor);
        }
    }

    public enum DirectionalRuleKind {
        FACE_AXIS_DIFFERS_FROM_STATE_AXIS
    }

    public static final class DirectionalPresenceRule {
        public DirectionalRuleKind kind;

        @Override public String toString() {
            return kind == null ? "unknown" : kind.name();
        }
    }

    public enum BooleanOp {
        CONSTANT,
        SOURCE,
        NOT_NULL,
        NUMERIC_COMPARE,
        STATE_EQUALS,
        NOT,
        AND,
        OR
    }

    /**
     * Small declarative boolean expression recovered from renderer control flow. SOURCE is an
     * exact BlockEntity-rooted F#/M# access chain; NOT_NULL uses the same chain for object
     * presence. Compound nodes are intentionally limited to ordinary JVM boolean control flow so
     * runtime never needs to execute a client renderer class.
     */
    public static final class BooleanPredicate {
        public BooleanOp op;
        public String source;
        /** Exact static enum/constant field for STATE_EQUALS. */
        public String expected;
        /** Comparison encoded for NUMERIC_COMPARE (GT, GE, LT, LE, EQ, NE). */
        public String comparison;
        /** Constant right-hand operand for NUMERIC_COMPARE. */
        public double threshold;
        public boolean constant;
        public List<BooleanPredicate> terms = new ArrayList<>();

        @Override public String toString() {
            if (op == BooleanOp.CONSTANT) return Boolean.toString(constant);
            if (op == BooleanOp.SOURCE || op == BooleanOp.NOT_NULL) {
                return op + "(" + source + ")";
            }
            if (op == BooleanOp.NUMERIC_COMPARE) {
                return op + "(" + source + ',' + comparison + ',' + threshold + ')';
            }
            if (op == BooleanOp.STATE_EQUALS) return op + "(" + source + ',' + expected + ')';
            return op + String.valueOf(terms);
        }
    }

    /** Declarative form of a renderer's value-driven translated segment loop. */
    public static final class LinearRepeat {
        /** M#owner#name#descriptor for the live numeric BlockEntity accessor. */
        public String valueSource;
        public VisualSource segment;
        public VisualSource halfSegment;
        public VisualSource terminal;
        public VisualSource halfTerminal;
        /** Translation axis in model/world block coordinates: 0=X, 1=Y, 2=Z. */
        public int axis = 1;
        /** Renderer helper translation sign. */
        public float direction = -1f;
        /** Loop condition is index < value - loopThreshold. */
        public float loopThreshold;
        /** Rendered whole segment position is value - index + positionBias. */
        public float positionBias;
        /** Fractional segment is shown near either end of a whole-block boundary. */
        public float fractionLow = .25f;
        public float fractionHigh = .75f;
        /** Optional lower value gate for the fractional segment. */
        public float halfMinimumValue;
        /** Optional terminal switch: use terminal above this value, halfTerminal below it. */
        public Float terminalThreshold;
        /** Exact renderer condition which enables the repeated whole-segment loop. */
        public BooleanPredicate activityPredicate;

        public Set<String> modelRefs() {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (VisualSource source : new VisualSource[]{segment, halfSegment, terminal, halfTerminal}) {
                if (source != null && source.modelRef != null) out.add(source.modelRef);
            }
            return out;
        }
    }

    /**
     * Resolves static model-handle fields to resource paths. The common bytecode shape is
     * {@code LDC "machine/rotor"; invokestatic block(...); PUTSTATIC ROTOR PartialModel}.
     */
    static Map<String, String> discoverModelFields(Map<String, ClassNode> nodes) {
        java.util.LinkedHashMap<String, String> found = new java.util.LinkedHashMap<>();
        if (nodes == null) return found;
        for (ClassNode node : nodes.values()) {
            if (node == null || node.methods == null) continue;
            for (MethodNode method : node.methods) {
                if (!"<clinit>".equals(method.name)) continue;
                for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                     instruction = instruction.getNext()) {
                    if (!(instruction instanceof FieldInsnNode field)
                            || field.getOpcode() != Opcodes.PUTSTATIC
                            || !(looksLikeModelDescriptor(field.desc)
                            || looksLikeResourceLocationDescriptor(field.desc))) continue;
                    String ref = modelRefBefore(instruction);
                    if (ref != null) found.put(field.owner + '.' + field.name, ref);
                }
            }
        }
        return found;
    }

    /** Returns null for classes that do not behave like client renderers or reference no models. */
    static Surface inspect(ClassNode node, Map<String, ClassNode> nodes,
                           Map<String, String> modelFields) {
        if (node == null || node.methods == null) return null;
        boolean namedRenderer = node.name != null
                && node.name.toLowerCase(Locale.ROOT).contains("renderer");
        List<MethodNode> roots = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (renderMethod(method) || modelCompositionMethod(method)) roots.add(method);
        }
        boolean namedModel = node.name != null
                && node.name.toLowerCase(Locale.ROOT).contains("model");
        if (!namedRenderer && !namedModel && roots.isEmpty()) return null;
        if (roots.isEmpty()) {
            for (MethodNode method : node.methods) {
                if (method.name.toLowerCase(Locale.ROOT).contains("render")
                        || rendererModelProvider(method)) roots.add(method);
            }
        }
        if (roots.isEmpty()) return null;

        Surface surface = new Surface();
        surface.clazz = node.name;
        surface.entityRenderer = isEntityRendererType(node, nodes);
        surface.abstractType = (node.access & Opcodes.ACC_ABSTRACT) != 0;
        surface.layerModel = rendererLayerModel(node, nodes);
        discoverEntityTypesAndTextures(node, roots, surface);
        discoverEntityAssetFamilyHints(roots, nodes, surface);
        discoverEntityAppearanceAccessorSources(roots, nodes, surface);
        discoverEntityRootScale(node, nodes, surface);
        discoverCameraFacingEntityQuad(node, roots, surface);
        discoverEntityBlockSequence(node, roots, surface);
        discoverEntityProceduralGeometry(node, roots, nodes, surface);
        LinkedHashSet<String> declaredRendererTargets = rendererSignatureBlockEntityTargets(node);
        surface.blockEntityTypes.addAll(declaredRendererTargets);
        Set<String> family = rendererFamily(node, nodes);
        Map<String, String> effectiveModelFields = new LinkedHashMap<>();
        if (modelFields != null) effectiveModelFields.putAll(modelFields);
        effectiveModelFields.putAll(discoverInjectedModelFields(node, nodes, effectiveModelFields));
        for (MethodNode root : roots) {
            LinkedHashSet<String> rootTargets = blockEntityTargets(root, nodes);
            if (!declaredRendererTargets.isEmpty()
                    && (rootTargets.isEmpty() || rootTargets.size() == 1
                    && rootTargets.contains("net/minecraft/world/level/block/entity/BlockEntity"))) {
                rootTargets.addAll(declaredRendererTargets);
            }
            LinkedHashSet<String> inlineRefs = new LinkedHashSet<>();
            collectInlineModelRefs(root, inlineRefs);
            surface.modelRefs.addAll(inlineRefs);
            if (!rootTargets.isEmpty()) {
                for (String ref : inlineRefs) {
                    surface.modelRefTargets.computeIfAbsent(ref,
                            ignored -> new LinkedHashSet<>()).addAll(rootTargets);
                }
            }
        }
        Set<String> selectorVisiting = new HashSet<>();
        for (MethodNode root : roots) {
            surface.blockEntityTypes.addAll(blockEntityTargets(root, nodes));
            collectDirectionalSelectors(node.name, root, nodes, family, selectorVisiting,
                    surface.directionalSelectors, surface.directionalPresenceSelectors, 0);
            discoverDirectionalBranchRules(root, surface.directionalPresenceRules);
        }
        if (!effectiveModelFields.isEmpty()) {
            for (MethodNode root : roots) {
                LinkedHashSet<String> rootTargets = blockEntityTargets(root, nodes);
                if (!declaredRendererTargets.isEmpty()
                        && (rootTargets.isEmpty() || rootTargets.size() == 1
                        && rootTargets.contains("net/minecraft/world/level/block/entity/BlockEntity"))) {
                    rootTargets.addAll(declaredRendererTargets);
                }
                surface.blockEntityTypes.addAll(rootTargets);
                LinkedHashSet<String> rootRefs = new LinkedHashSet<>();
                Set<String> visiting = new HashSet<>();
                collectModelRefs(node.name, root, nodes, effectiveModelFields, family, visiting,
                        rootRefs, 0);
                surface.modelRefs.addAll(rootRefs);
                int renderCopies = renderCopies(root, nodes, new HashSet<>(), 0);
                if (renderCopies > 1) {
                    for (String ref : rootRefs) {
                        surface.modelRefCopies.merge(ref, renderCopies, Math::max);
                    }
                }
                if (!rootTargets.isEmpty()) {
                    for (String ref : rootRefs) {
                        surface.modelRefTargets.computeIfAbsent(ref,
                                ignored -> new LinkedHashSet<>()).addAll(rootTargets);
                    }
                }
                discoverModelPredicatesRecursive(node, node.name, root, nodes, effectiveModelFields,
                        family, rootTargets, surface.modelPredicates, new HashSet<>(), 0);
                discoverBooleanSelectorPredicates(node, root, nodes, effectiveModelFields,
                        rootTargets, surface.modelPredicates);
                if (surface.modelRefs.size() >= MAX_MODEL_REFS) break;
            }
            collectNestedRendererModelRefs(node, nodes, effectiveModelFields, surface);
        }
        discoverLinearRepeats(node, nodes, effectiveModelFields, surface);
        for (LinearRepeat repeat : surface.linearRepeats) {
            for (String ref : repeat.modelRefs()) {
                surface.modelRefs.add(ref);
                if (!surface.blockEntityTypes.isEmpty()) {
                    surface.modelRefTargets.computeIfAbsent(ref, ignored -> new LinkedHashSet<>())
                            .addAll(surface.blockEntityTypes);
                }
            }
        }
        removeErasedBlockEntityTargets(surface);
        discoverRenderedItemSources(node, roots, nodes, surface);
        if (surface.entityRenderer) {
            inheritEntityRendererEvidence(node, nodes, effectiveModelFields, surface);
        }
        // Static ResourceLocations are commonly initialized in <clinit>, outside render roots.
        for (MethodNode method : node.methods) {
            if ("<clinit>".equals(method.name)) collectTextureEvidence(method, surface.textureRefs);
        }
        return surface.modelRefs.isEmpty() && surface.textureRefs.isEmpty()
                && surface.directionalSelectors.isEmpty() && surface.directionalPresenceSelectors.isEmpty()
                && surface.directionalPresenceRules.isEmpty()
                && surface.layerModel == null && surface.entityBlockSequence == null
                && surface.entityProceduralGeometry == null
                && surface.entityAssetFamilyHints.isEmpty()
                && surface.entityAppearanceAccessorSources.isEmpty()
                && surface.entityRootScaleSource == null
                && surface.itemStackSources.isEmpty() && surface.itemStackCollectionSources.isEmpty()
                && surface.linearRepeats.isEmpty() ? null : surface;
    }

    /**
     * Concrete renderer subclasses frequently inherit the actual body/model/material implementation
     * from a renderer supplied by another mod while overriding only a small hook or one extra layer.
     * CrossJarClassPath deliberately exposes dependency classes through Map#get(), so preserve that
     * inherited renderer evidence here instead of making those subclasses disappear as empty
     * surfaces. Registry ownership and concrete target types remain subclass-local; only visual
     * evidence that Java inheritance actually supplies is merged from the parent surface.
     */
    private static void inheritEntityRendererEvidence(ClassNode node, Map<String, ClassNode> nodes,
                                                       Map<String, String> modelFields, Surface surface) {
        if (node == null || nodes == null || surface == null || node.superName == null
                || node.superName.equals(node.name)) return;
        ClassNode parent = nodes.get(node.superName);
        if (parent == null || !isEntityRendererType(parent, nodes)) return;
        Surface inherited = inspect(parent, nodes, modelFields);
        if (inherited == null || !inherited.entityRenderer) return;

        if (surface.layerModel == null) surface.layerModel = inherited.layerModel;
        surface.modelRefs.addAll(inherited.modelRefs);
        surface.nestedModelRefs.addAll(inherited.nestedModelRefs);
        inherited.modelRefCopies.forEach((ref, copies) ->
                surface.modelRefCopies.merge(ref, copies, Math::max));
        inherited.modelPredicates.forEach(surface.modelPredicates::putIfAbsent);
        surface.textureRefs.addAll(inherited.textureRefs);
        if (surface.entityTextureSelector == null) surface.entityTextureSelector = inherited.entityTextureSelector;
        if (!surface.cameraFacingQuad && inherited.cameraFacingQuad) {
            surface.cameraFacingQuad = true;
            surface.cameraFacingQuadScale = inherited.cameraFacingQuadScale;
        }
        if (surface.entityBlockSequence == null) surface.entityBlockSequence = inherited.entityBlockSequence;
        if (surface.entityProceduralGeometry == null) {
            surface.entityProceduralGeometry = inherited.entityProceduralGeometry;
        }
        surface.entityAssetFamilyHints.addAll(inherited.entityAssetFamilyHints);
        inherited.entityStateAnimationHints.forEach((state, tokens) ->
                surface.entityStateAnimationHints.computeIfAbsent(state, ignored -> new LinkedHashSet<>())
                        .addAll(tokens));
        if (surface.entityAppearanceAccessorSources.isEmpty()) {
            surface.entityAppearanceAccessorSources.addAll(inherited.entityAppearanceAccessorSources);
        }
        if (surface.entityRootScaleSource == null && inherited.entityRootScaleSource != null) {
            surface.entityRootScaleSource = copy(inherited.entityRootScaleSource);
        }
        surface.itemStackSources.addAll(inherited.itemStackSources);
        surface.itemDisplayContexts.addAll(inherited.itemDisplayContexts);
        for (ItemStackCollectionSource source : inherited.itemStackCollectionSources) {
            if (source != null && surface.itemStackCollectionSources.stream()
                    .noneMatch(existing -> existing != null && existing.accessors.equals(source.accessors))) {
                surface.itemStackCollectionSources.add(source);
            }
        }
        surface.directionalSelectors.addAll(inherited.directionalSelectors);
        surface.directionalPresenceSelectors.addAll(inherited.directionalPresenceSelectors);
        surface.directionalPresenceRules.addAll(inherited.directionalPresenceRules);
        surface.linearRepeats.addAll(inherited.linearRepeats);
        if (surface.entityTypes.isEmpty()) surface.entityTypes.addAll(inherited.entityTypes);
    }

    /** Renderer geometry is often declared in a separate EntityModel/Model class instantiated by
     * the renderer constructor. Follow direct bytecode class references and select the richest
     * vanilla LayerDefinition program instead of requiring the renderer itself to own it. */
    private static ArmorVisualBytecodeScanner.LayerModel rendererLayerModel(
            ClassNode renderer, Map<String, ClassNode> nodes) {
        ArmorVisualBytecodeScanner.LayerModel best = ArmorVisualBytecodeScanner.parseLayerModel(renderer, nodes);
        int bestCubes = best == null ? 0 : best.cubes.size();
        LinkedHashSet<String> referenced = new LinkedHashSet<>();
        for (MethodNode method : renderer.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode type) referenced.add(type.desc);
                else if (instruction instanceof MethodInsnNode call) referenced.add(call.owner);
                else if (instruction instanceof FieldInsnNode field) {
                    try {
                        Type value = Type.getType(field.desc);
                        if (value.getSort() == Type.OBJECT) referenced.add(value.getInternalName());
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        for (String name : referenced) {
            ClassNode candidate = nodes.get(name);
            if (candidate == null || candidate == renderer) continue;
            ArmorVisualBytecodeScanner.LayerModel layer = ArmorVisualBytecodeScanner.parseLayerModel(candidate, nodes);
            if (layer != null && layer.cubes.size() > bestCubes) {
                best = layer;
                bestCubes = layer.cubes.size();
            }
        }
        return best;
    }

    private static boolean rendererModelProvider(MethodNode method) {
        if (method == null || method.desc == null || method.name == null
                || "<init>".equals(method.name) || "<clinit>".equals(method.name)) return false;
        String lowerName = method.name.toLowerCase(Locale.ROOT);
        if (lowerName.contains("model")) return true;
        try {
            Type returned = Type.getReturnType(method.desc);
            if (returned.getSort() != Type.OBJECT) return false;
            String type = returned.getInternalName();
            if (type == null) return false;
            return type.contains("BakedModel") || type.contains("PartialModel")
                    || type.contains("SuperByteBuffer") || type.endsWith("/ModelPart");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void discoverDirectionalBranchRules(MethodNode method,
                                                       List<DirectionalPresenceRule> out) {
        if (method == null || method.instructions == null || out == null) return;
        boolean directionArray = false;
        int axisCalls = 0;
        boolean differingAxisGuard = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && "[Lnet/minecraft/core/Direction;".equals(field.desc)) {
                directionArray = true;
            } else if (instruction instanceof MethodInsnNode call) {
                try {
                    Type returned = Type.getReturnType(call.desc);
                    if (returned.getSort() == Type.OBJECT
                            && "net/minecraft/core/Direction$Axis".equals(returned.getInternalName())) {
                        axisCalls++;
                    }
                } catch (Exception ignored) {
                }
            } else if (instruction.getOpcode() == Opcodes.IF_ACMPNE) {
                // A Direction loop that compares two Axis objects and continues rendering only on
                // inequality is a structural per-face rule. The exact API/member names are irrelevant.
                differingAxisGuard = true;
            }
        }
        if (!directionArray || axisCalls < 2 || !differingAxisGuard) return;
        boolean duplicate = out.stream().anyMatch(rule -> rule != null
                && rule.kind == DirectionalRuleKind.FACE_AXIS_DIFFERS_FROM_STATE_AXIS);
        if (!duplicate) {
            DirectionalPresenceRule rule = new DirectionalPresenceRule();
            rule.kind = DirectionalRuleKind.FACE_AXIS_DIFFERS_FROM_STATE_AXIS;
            out.add(rule);
        }
    }

    /**
     * Resolves model handles injected through a superclass constructor. Render frameworks often
     * keep the generic segment algorithm in an abstract renderer and pass concrete partials from
     * each subclass constructor. A plain GETSTATIC scan misses those because the render method
     * later reads a GETFIELD. This follows the verifier-stable shape
     * {@code GETSTATIC model; invokespecial super(...model...)} -> {@code ALOAD arg; PUTFIELD}.
     */
    private static Map<String, String> discoverInjectedModelFields(ClassNode concrete,
                                                                   Map<String, ClassNode> nodes,
                                                                   Map<String, String> staticModels) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (concrete == null || concrete.methods == null || nodes == null || staticModels == null) return out;
        for (MethodNode ctor : concrete.methods) {
            if (!"<init>".equals(ctor.name) || ctor.instructions == null) continue;
            for (AbstractInsnNode instruction : ctor.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || !"<init>".equals(call.name) || !nodes.containsKey(call.owner)) continue;
                MethodNode targetCtor = findMethod(nodes.get(call.owner), "<init>", call.desc);
                if (targetCtor == null) continue;
                Map<Integer, String> parameterFields = constructorModelParameterFields(call.owner, targetCtor);
                if (parameterFields.isEmpty()) continue;
                List<String> argumentModels = modelArgumentsBefore(call, staticModels,
                        parameterFields.size());
                if (argumentModels.isEmpty()) continue;
                List<Integer> parameters = new ArrayList<>(parameterFields.keySet());
                parameters.sort(Integer::compareTo);
                int count = Math.min(parameters.size(), argumentModels.size());
                for (int i = 0; i < count; i++) {
                    String ref = argumentModels.get(i);
                    if (ref != null) out.put(parameterFields.get(parameters.get(i)), ref);
                }
            }
        }
        return out;
    }

    private static Map<Integer, String> constructorModelParameterFields(String owner, MethodNode ctor) {
        LinkedHashMap<Integer, String> out = new LinkedHashMap<>();
        if (ctor == null || ctor.instructions == null) return out;
        for (AbstractInsnNode instruction : ctor.instructions) {
            if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                    || !looksLikeModelDescriptor(field.desc)) continue;
            AbstractInsnNode value = previousReal(instruction);
            AbstractInsnNode self = previousReal(value);
            if (value instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                    && self instanceof VarInsnNode ownerLoad && ownerLoad.getOpcode() == Opcodes.ALOAD
                    && ownerLoad.var == 0 && load.var > 0) {
                out.put(load.var, owner + '.' + field.name);
            }
        }
        return out;
    }

    private static List<String> modelArgumentsBefore(MethodInsnNode constructor,
                                                     Map<String, String> modelFields, int wanted) {
        ArrayList<String> reversed = new ArrayList<>();
        int remaining = 32;
        for (AbstractInsnNode cursor = previousReal(constructor); cursor != null && remaining-- > 0;
             cursor = previousReal(cursor)) {
            if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && looksLikeModelDescriptor(field.desc)) {
                String ref = modelFields.get(field.owner + '.' + field.name);
                if (ref != null) reversed.add(ref);
                if (reversed.size() >= wanted) break;
            }
            if (cursor.getOpcode() == Opcodes.INVOKESPECIAL && cursor instanceof MethodInsnNode call
                    && "<init>".equals(call.name)) break;
        }
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private static void discoverRenderedItemSources(ClassNode renderer, List<MethodNode> roots,
                                                    Map<String, ClassNode> nodes, Surface surface) {
        if (renderer == null || roots == null || nodes == null || surface == null) return;
        BytecodeValueFlow flow = new BytecodeValueFlow(nodes);
        Set<String> targetTypes = surface.entityRenderer ? surface.entityTypes : surface.blockEntityTypes;
        for (MethodNode method : roots) {
            if (method == null || method.instructions == null || !invokesItemRenderer(method)) continue;
            int sourceCount = surface.itemStackSources.size() + surface.itemStackCollectionSources.size();
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.desc != null
                        && Type.getArgumentTypes(call.desc).length == 0
                        && returnsItemStack(call.desc)
                        && feedsItemRenderer(instruction)) {
                    if (sourceOwnerMatchesTarget(call.owner, targetTypes)) {
                        surface.itemStackSources.add("M#" + call.owner + '#' + call.name + '#' + call.desc);
                    } else {
                        // Entity renderers often expose an Item rather than an ItemStack, then call
                        // Item#getDefaultInstance before ItemRenderer. BytecodeValueFlow can prove
                        // the complete zero-arg chain back to the rendered entity, and the existing
                        // runtime path evaluator already accepts a terminal ItemStack value.
                        List<String> path = itemCollectionAccessorPath(renderer, method, call, flow,
                                nodes, targetTypes, new HashSet<>(), 0);
                        addItemAccessorPath(surface, path);
                    }
                } else if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETFIELD
                        && "Lnet/minecraft/world/item/ItemStack;".equals(field.desc)
                        && sourceOwnerMatchesTarget(field.owner, targetTypes)
                        && feedsItemRenderer(instruction)) {
                    surface.itemStackSources.add("F#" + field.owner + '#' + field.name + '#' + field.desc);
                } else if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() != Opcodes.INVOKESTATIC
                        && Type.getArgumentTypes(call.desc).length == 0
                        && containerReturn(call.desc, nodes)
                        && collectionFeedsItemRenderer(call)) {
                    List<String> path = itemCollectionAccessorPath(renderer, method, call, flow,
                            nodes, targetTypes, new HashSet<>(), 0);
                    addItemAccessorPath(surface, path);
                }
            }
            if (surface.itemStackSources.size() + surface.itemStackCollectionSources.size() > sourceCount) {
                collectItemDisplayContexts(method, surface.itemDisplayContexts);
            }
        }
    }

    private static void collectItemDisplayContexts(MethodNode method, Set<String> output) {
        if (method == null || method.instructions == null || output == null) return;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC
                    || !"net/minecraft/world/item/ItemDisplayContext".equals(field.owner)
                    || !"Lnet/minecraft/world/item/ItemDisplayContext;".equals(field.desc)) continue;
            // Require a nearby ItemRenderer invocation so unrelated context constants in the same
            // renderer helper are not promoted into the live entity-item contract.
            AbstractInsnNode cursor = instruction;
            for (int steps = 0; cursor != null && steps < 48; steps++, cursor = nextReal(cursor)) {
                if (cursor instanceof MethodInsnNode call && itemRendererCall(call)) {
                    output.add(field.name);
                    break;
                }
                if (cursor != instruction && cursor instanceof FieldInsnNode other
                        && other.getOpcode() == Opcodes.GETSTATIC
                        && "net/minecraft/world/item/ItemDisplayContext".equals(other.owner)) break;
            }
        }
    }

    private static void addItemAccessorPath(Surface surface, List<String> path) {
        if (surface == null || path == null || path.isEmpty()) return;
        if (surface.itemStackCollectionSources.stream()
                .noneMatch(existing -> existing != null && existing.accessors.equals(path))) {
            surface.itemStackCollectionSources.add(new ItemStackCollectionSource(path));
        }
    }

    /**
     * Reconstructs a zero-argument accessor chain whose root is the concrete rendered target.
     * ASM provenance makes locals/casts transparent, so both `be.getState().getItems()` and
     * `entity.getRenderItem().getDefaultInstance()` remain exact paths. Client-owned accessors and
     * ambiguous receiver merges are rejected.
     */
    private static List<String> itemCollectionAccessorPath(ClassNode renderer, MethodNode method,
                                                           MethodInsnNode terminal,
                                                           BytecodeValueFlow flow,
                                                           Map<String, ClassNode> nodes,
                                                           Set<String> targetTypes,
                                                           Set<MethodInsnNode> visiting, int depth) {
        if (renderer == null || method == null || terminal == null || flow == null || nodes == null
                || targetTypes == null || targetTypes.isEmpty() || depth > 8
                || terminal.getOpcode() == Opcodes.INVOKESTATIC || !visiting.add(terminal)
                || clientOwner(terminal.owner)) return List.of();
        try {
            Set<AbstractInsnNode> receivers = flow.receiverSources(renderer, method, terminal);
            if (receivers.size() != 1) return List.of();
            AbstractInsnNode receiver = receivers.iterator().next();
            ArrayList<String> path = new ArrayList<>();
            if (receiver instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
                String parameterType = referenceParameterType(method, load.var);
                if (parameterType == null || !targetTypes.contains(parameterType)) return List.of();
            } else if (receiver instanceof MethodInsnNode call
                    && call.getOpcode() != Opcodes.INVOKESTATIC
                    && Type.getArgumentTypes(call.desc).length == 0
                    && !clientOwner(call.owner)) {
                List<String> parent = itemCollectionAccessorPath(renderer, method, call, flow,
                        nodes, targetTypes, visiting, depth + 1);
                if (parent.isEmpty()) return List.of();
                path.addAll(parent);
            } else {
                return List.of();
            }
            path.add("M#" + terminal.owner + '#' + terminal.name + '#' + terminal.desc);
            return List.copyOf(path);
        } finally {
            visiting.remove(terminal);
        }
    }

    private static String referenceParameterType(MethodNode method, int local) {
        if (method == null || local < 0) return null;
        int cursor = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            if (cursor == local && argument.getSort() == Type.OBJECT) return argument.getInternalName();
            cursor += argument.getSize();
        }
        return null;
    }

    private static boolean containerReturn(String descriptor, Map<String, ClassNode> nodes) {
        if (descriptor == null) return false;
        Type result;
        try {
            result = Type.getReturnType(descriptor);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        if (result.getSort() == Type.ARRAY) return true;
        if (result.getSort() != Type.OBJECT) return false;
        String type = result.getInternalName();
        if ("java/lang/Iterable".equals(type) || "java/util/Collection".equals(type)
                || "java/util/List".equals(type) || "java/util/Set".equals(type)
                || "java/util/Map".equals(type)) return true;
        return inheritsContainer(type, nodes, new HashSet<>());
    }

    private static boolean inheritsContainer(String type, Map<String, ClassNode> nodes, Set<String> seen) {
        if (type == null || !seen.add(type)) return false;
        if ("java/lang/Iterable".equals(type) || "java/util/Collection".equals(type)
                || "java/util/List".equals(type) || "java/util/Set".equals(type)
                || "java/util/Map".equals(type)) return true;
        ClassNode node = nodes.get(type);
        if (node == null) return false;
        if (inheritsContainer(node.superName, nodes, seen)) return true;
        if (node.interfaces != null) for (String itf : node.interfaces) {
            if (inheritsContainer(itf, nodes, seen)) return true;
        }
        return false;
    }

    /** Structural proof that iteration of this container produces ItemStacks consumed by the same
     * renderer's ItemRenderer call. The proof intentionally requires the ordinary iterator/next
     * shape plus an ItemStack CHECKCAST; helper-produced ItemStacks remain a separate capability. */
    private static boolean collectionFeedsItemRenderer(MethodInsnNode source) {
        boolean iterator = false;
        boolean next = false;
        boolean itemStackCast = false;
        for (AbstractInsnNode cursor = nextReal(source); cursor != null; cursor = nextReal(cursor)) {
            int distance = instructionDistance(source, cursor, 360);
            if (distance < 0 || distance > 360) return false;
            if (cursor instanceof MethodInsnNode call) {
                if ("()Ljava/util/Iterator;".equals(call.desc) && "iterator".equals(call.name)) {
                    iterator = true;
                } else if (iterator && "java/util/Iterator".equals(call.owner)
                        && "next".equals(call.name) && "()Ljava/lang/Object;".equals(call.desc)) {
                    next = true;
                }
                if (itemRendererCall(call) && next && itemStackCast) return true;
            } else if (next && cursor instanceof TypeInsnNode cast
                    && cast.getOpcode() == Opcodes.CHECKCAST
                    && "net/minecraft/world/item/ItemStack".equals(cast.desc)) {
                itemStackCast = true;
            }
            if (cursor.getOpcode() >= Opcodes.IRETURN && cursor.getOpcode() <= Opcodes.RETURN) return false;
        }
        return false;
    }

    private static int instructionDistance(AbstractInsnNode from, AbstractInsnNode to, int limit) {
        int distance = 0;
        for (AbstractInsnNode cursor = from; cursor != null && distance <= limit;
             cursor = cursor.getNext(), distance++) {
            if (cursor == to) return distance;
        }
        return -1;
    }

    private static boolean clientOwner(String owner) {
        if (owner == null) return true;
        String normalized = owner.replace('.', '/').toLowerCase(Locale.ROOT);
        return normalized.startsWith("net/minecraft/client/") || normalized.contains("/client/");
    }

    /**
     * Proves a lightweight local-value flow from an ItemStack source to ItemRenderer. Renderer
     * roots overwhelmingly store the selected stack in a local before applying transforms. We
     * also accept a direct stack expression immediately preceding the render call. This is much
     * narrower than treating every ItemStack getter mentioned anywhere in a BER as visible data.
     */
    private static boolean feedsItemRenderer(AbstractInsnNode source) {
        AbstractInsnNode cursor = nextReal(source);
        if (cursor instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE) {
            int local = store.var;
            cursor = nextReal(cursor);
            for (int steps = 0; cursor != null && steps < 180; steps++, cursor = nextReal(cursor)) {
                if (cursor instanceof VarInsnNode overwrite && overwrite.getOpcode() == Opcodes.ASTORE
                        && overwrite.var == local) return false;
                if (!(cursor instanceof MethodInsnNode call) || !itemRendererCall(call)) continue;
                if (localLoadedBefore(call, local, 36)) return true;
            }
            return false;
        }
        cursor = nextReal(source);
        for (int steps = 0; cursor != null && steps < 24; steps++, cursor = nextReal(cursor)) {
            if (cursor instanceof MethodInsnNode call && itemRendererCall(call)) return true;
            // Another ItemStack-producing expression means the source can no longer be proven to
            // occupy the stack argument position without a full verifier data-flow analysis.
            if (cursor instanceof MethodInsnNode call && call.desc != null && returnsItemStack(call.desc)) {
                return false;
            }
            if (cursor instanceof FieldInsnNode field
                    && "Lnet/minecraft/world/item/ItemStack;".equals(field.desc)) return false;
        }
        return false;
    }

    private static boolean localLoadedBefore(MethodInsnNode call, int local, int steps) {
        AbstractInsnNode cursor = previousReal(call);
        for (int i = 0; cursor != null && i < steps; i++, cursor = previousReal(cursor)) {
            if (cursor instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                    && load.var == local) return true;
        }
        return false;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getNext();
        return cursor;
    }

    private static boolean invokesItemRenderer(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && itemRendererCall(call)) return true;
        }
        return false;
    }

    private static boolean itemRendererCall(MethodInsnNode call) {
        return call != null && call.owner != null
                && call.owner.contains("/client/renderer/entity/ItemRenderer");
    }

    private static boolean returnsItemStack(String descriptor) {
        try {
            Type type = Type.getReturnType(descriptor);
            return type.getSort() == Type.OBJECT
                    && "net/minecraft/world/item/ItemStack".equals(type.getInternalName());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean sourceOwnerMatchesTarget(String owner, Set<String> targets) {
        if (owner == null || targets == null || targets.isEmpty()) return false;
        if (targets.contains(owner)) return true;
        String ownerLeaf = leaf(owner).toLowerCase(Locale.ROOT).replace("blockentity", "");
        for (String target : targets) {
            String targetLeaf = leaf(target).toLowerCase(Locale.ROOT).replace("blockentity", "");
            if (!ownerLeaf.isBlank() && (ownerLeaf.contains(targetLeaf) || targetLeaf.contains(ownerLeaf))) {
                return true;
            }
        }
        return false;
    }

    /** Exact renderer-proven ItemStack sources for one live target hierarchy. */
    public static Set<String> itemStackSourcesForTarget(Collection<ModScanner.Profile> profiles,
                                                        Collection<String> runtimeTypes) {
        if (profiles == null || runtimeTypes == null || runtimeTypes.isEmpty()) return Set.of();
        LinkedHashSet<String> wanted = new LinkedHashSet<>(runtimeTypes);
        LinkedHashSet<String> exact = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collectItemSources(profile, wanted, exact);
        return Set.copyOf(exact);
    }

    /** Exact scanner-proven accessor chains to renderer-owned ItemStack containers. */
    public static List<List<String>> itemStackCollectionSourcesForTarget(
            Collection<ModScanner.Profile> profiles, Collection<String> runtimeTypes) {
        if (profiles == null || runtimeTypes == null || runtimeTypes.isEmpty()) return List.of();
        LinkedHashSet<String> wanted = new LinkedHashSet<>(runtimeTypes);
        LinkedHashMap<String, List<String>> exact = new LinkedHashMap<>();
        for (ModScanner.Profile profile : profiles) collectItemCollectionSources(profile, wanted, exact);
        return List.copyOf(exact.values());
    }

    /** Returns the one ItemDisplayContext proven for this renderer target, or null when the
     * renderer legitimately uses several contexts and runtime would need a stronger selector. */
    public static String itemDisplayContextForTarget(Collection<ModScanner.Profile> profiles,
                                                     Collection<String> runtimeTypes) {
        if (profiles == null || runtimeTypes == null || runtimeTypes.isEmpty()) return null;
        LinkedHashSet<String> wanted = new LinkedHashSet<>(runtimeTypes);
        LinkedHashSet<String> contexts = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collectItemDisplayContexts(profile, wanted, contexts);
        return contexts.size() == 1 ? contexts.iterator().next() : null;
    }

    private static void collectItemDisplayContexts(ModScanner.Profile profile, Set<String> runtimeTypes,
                                                   Set<String> output) {
        if (profile == null) return;
        if (profile.rendererSurfaces != null) {
            for (Surface surface : profile.rendererSurfaces) {
                if (surface == null || surface.itemDisplayContexts.isEmpty()) continue;
                Set<String> targets = surface.entityRenderer ? surface.entityTypes : surface.blockEntityTypes;
                if (targets.stream().anyMatch(runtimeTypes::contains)) output.addAll(surface.itemDisplayContexts);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                collectItemDisplayContexts(child, runtimeTypes, output);
            }
        }
    }

    private static void collectItemSources(ModScanner.Profile profile, Set<String> runtimeTypes,
                                           Set<String> output) {
        if (profile == null) return;
        if (profile.rendererSurfaces != null) {
            for (Surface surface : profile.rendererSurfaces) {
                if (surface == null || surface.itemStackSources.isEmpty()) continue;
                Set<String> targets = surface.entityRenderer ? surface.entityTypes : surface.blockEntityTypes;
                boolean exact = targets.stream().anyMatch(runtimeTypes::contains);
                if (exact) output.addAll(surface.itemStackSources);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collectItemSources(child, runtimeTypes, output);
        }
    }

    private static void collectItemCollectionSources(ModScanner.Profile profile, Set<String> runtimeTypes,
                                                     Map<String, List<String>> output) {
        if (profile == null) return;
        if (profile.rendererSurfaces != null) {
            for (Surface surface : profile.rendererSurfaces) {
                if (surface == null || surface.itemStackCollectionSources.isEmpty()) continue;
                Set<String> targets = surface.entityRenderer ? surface.entityTypes : surface.blockEntityTypes;
                boolean exact = targets.stream().anyMatch(runtimeTypes::contains);
                if (!exact) continue;
                for (ItemStackCollectionSource source : surface.itemStackCollectionSources) {
                    if (source == null || source.accessors.isEmpty()) continue;
                    List<String> path = List.copyOf(source.accessors);
                    output.putIfAbsent(String.join("\n", path), path);
                }
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                collectItemCollectionSources(child, runtimeTypes, output);
            }
        }
    }

    private static LinkedHashSet<String> blockEntityTargets(MethodNode method,
                                                            Map<String, ClassNode> nodes) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (method == null) return out;
        try {
            for (org.objectweb.asm.Type type : org.objectweb.asm.Type.getArgumentTypes(method.desc)) {
                if (type.getSort() != org.objectweb.asm.Type.OBJECT) continue;
                String name = type.getInternalName();
                if (isBlockEntityType(name, nodes)) out.add(name);
            }
        } catch (IllegalArgumentException ignored) {
        }
        if (method.instructions != null) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode type
                        && (type.getOpcode() == Opcodes.CHECKCAST
                        || type.getOpcode() == Opcodes.INSTANCEOF)
                        && isBlockEntityType(type.desc, nodes)) {
                    out.add(type.desc);
                }
            }
        }
        if (out.size() > 1) {
            out.remove("net/minecraft/world/level/block/entity/BlockEntity");
        }
        return out;
    }

    /**
     * Java emits a bridge render(BlockEntity,...) method for many generic block-entity renderers.
     * When the concrete block entity extends a superclass from another jar, archive-local
     * hierarchy walking cannot prove the concrete render overload is a BlockEntity and ownership
     * degrades to the erased vanilla type. The implemented generic interface is stronger evidence:
     * {@code BlockEntityRenderer<MyPipeBlockEntity>} is verifier-enforced by javac even when the
     * superclass is external. Read that signature without loading either client class.
     */
    private static LinkedHashSet<String> rendererSignatureBlockEntityTargets(ClassNode node) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (node == null || node.signature == null) return out;
        String signature = node.signature;
        int search = 0;
        while (search < signature.length()) {
            int renderer = signature.indexOf("BlockEntityRenderer<", search);
            if (renderer < 0) break;
            int argument = signature.indexOf('<', renderer);
            if (argument < 0) break;
            int classStart = signature.indexOf('L', argument + 1);
            int classEnd = classStart < 0 ? -1 : signature.indexOf(';', classStart + 1);
            if (classStart >= 0 && classEnd > classStart + 1) {
                String type = signature.substring(classStart + 1, classEnd);
                if (!type.startsWith("java/") && !type.equals(
                        "net/minecraft/world/level/block/entity/BlockEntity")) {
                    out.add(type);
                }
                search = classEnd + 1;
            } else {
                search = argument + 1;
            }
        }
        return out;
    }

    private static boolean isBlockEntityType(String type, Map<String, ClassNode> nodes) {
        if (type == null) return false;
        if ("net/minecraft/world/level/block/entity/BlockEntity".equals(type)) return true;
        String current = type;
        for (int depth = 0; current != null && depth < 32; depth++) {
            if ("net/minecraft/world/level/block/entity/BlockEntity".equals(current)) return true;
            ClassNode node = nodes == null ? null : nodes.get(current);
            if (node == null) break;
            current = node.superName;
        }
        ClassNode node = nodes == null ? null : nodes.get(type);
        if (node == null || node.methods == null) return false;
        for (MethodNode method : node.methods) {
            if (!"<init>".equals(method.name) || method.desc == null) continue;
            if (method.desc.startsWith("(Lnet/minecraft/world/level/block/entity/BlockEntityType;"
                    + "Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;")) return true;
        }
        return false;
    }

    /** True when {@code concrete} is the same type as, extends, or implements {@code ancestor}.
     * Renderer arguments are frequently erased to a shared base/interface while CHECKCAST or
     * generic-signature evidence names the concrete BlockEntity, so argument ownership needs a
     * small hierarchy check rather than exact string equality. */
    private static boolean compatibleTarget(String concrete, String ancestor,
                                            Map<String, ClassNode> nodes) {
        if (concrete == null || ancestor == null) return false;
        if (concrete.equals(ancestor)) return true;
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        queue.add(concrete);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!seen.add(current)) continue;
            if (ancestor.equals(current)) return true;
            ClassNode node = nodes == null ? null : nodes.get(current);
            if (node == null) continue;
            if (node.superName != null) queue.addLast(node.superName);
            if (node.interfaces != null) queue.addAll(node.interfaces);
        }
        return false;
    }

    private static void removeErasedBlockEntityTargets(Surface surface) {
        if (surface == null) return;
        String erased = "net/minecraft/world/level/block/entity/BlockEntity";
        if (surface.blockEntityTypes.size() > 1) surface.blockEntityTypes.remove(erased);
        for (Set<String> targets : surface.modelRefTargets.values()) {
            if (targets != null && targets.size() > 1) targets.remove(erased);
        }
    }

    /**
     * Nested renderer helpers and enum variants often own the concrete PartialModel handles while
     * the renderer root only asks them for a BakedModel. Follow those renderer-local classes and
     * attach their static model reads to the renderer's already-proven BlockEntity ownership.
     */
    private static void collectNestedRendererModelRefs(ClassNode renderer,
                                                       Map<String, ClassNode> nodes,
                                                       Map<String, String> modelFields,
                                                       Surface surface) {
        if (renderer == null || renderer.name == null || nodes == null || modelFields == null
                || modelFields.isEmpty() || surface == null) return;
        String prefix = renderer.name + "$";
        for (ClassNode nested : nodes.values()) {
            if (nested == null || nested.name == null || !nested.name.startsWith(prefix)
                    || nested.methods == null) continue;
            for (MethodNode method : nested.methods) {
                if (method.instructions == null) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof FieldInsnNode field)
                            || field.getOpcode() != Opcodes.GETSTATIC) continue;
                    String ref = modelFields.get(field.owner + '.' + field.name);
                    if (ref == null) continue;
                    surface.modelRefs.add(ref);
                    surface.nestedModelRefs.add(ref);
                    if (!surface.blockEntityTypes.isEmpty()) {
                        surface.modelRefTargets.computeIfAbsent(ref,
                                ignored -> new LinkedHashSet<>()).addAll(surface.blockEntityTypes);
                    }
                }
            }
        }
    }

    private record RenderCall(MethodInsnNode call, VisualSource source, boolean looped) {}

    /** Finds a repeated translated renderer contract in this class or one of its renderer bases. */
    private static void discoverLinearRepeats(ClassNode concrete, Map<String, ClassNode> nodes,
                                              Map<String, String> modelFields, Surface surface) {
        if (concrete == null || nodes == null || surface == null) return;
        Set<String> family = rendererFamily(concrete, nodes);
        for (String owner : family) {
            ClassNode candidate = nodes.get(owner);
            if (candidate == null || candidate.methods == null) continue;
            for (MethodNode root : candidate.methods) {
                if (!renderMethod(root) || root.instructions == null) continue;
                LinearRepeat repeat = linearRepeatFromRoot(concrete, owner, root, nodes,
                        modelFields, surface.blockEntityTypes);
                if (repeat == null || repeat.valueSource == null || repeat.segment == null
                        || !repeat.segment.present()) continue;
                boolean duplicate = surface.linearRepeats.stream().anyMatch(existing ->
                        java.util.Objects.equals(existing.valueSource, repeat.valueSource)
                                && visualSourceEquals(existing.segment, repeat.segment));
                if (!duplicate) surface.linearRepeats.add(repeat);
            }
        }
    }

    private static boolean visualSourceEquals(VisualSource a, VisualSource b) {
        return a != null && b != null && java.util.Objects.equals(a.modelRef, b.modelRef)
                && java.util.Objects.equals(a.blockField, b.blockField)
                && java.util.Objects.equals(a.blockAccessor, b.blockAccessor);
    }

    /** Associates a model handle with the nearest forward branch that encloses its use. */
    private static void discoverModelPredicates(ClassNode concrete, MethodNode method,
                                                Map<String, ClassNode> nodes,
                                                Map<String, String> modelFields,
                                                Set<String> targetTypes,
                                                Map<String, BooleanPredicate> out) {
        if (concrete == null || method == null || method.instructions == null
                || modelFields == null || modelFields.isEmpty() || out == null) return;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETSTATIC && field.getOpcode() != Opcodes.GETFIELD) continue;
            String ref = modelFields.get(field.owner + '.' + field.name);
            if (ref == null || out.containsKey(ref)) continue;
            BooleanPredicate predicate = guardingPredicate(concrete, method, instruction, nodes,
                    targetTypes, 0);
            if (predicate != null && !isConstant(predicate, true)) out.put(ref, predicate);
        }
        discoverTernaryModelPredicates(concrete, method, nodes, modelFields, targetTypes, out);
    }

    /** Renderer roots frequently delegate the actual partial choice to a small helper such as
     * {@code getCurrentModel(be)}. Model refs already follow those calls, so their branch ownership
     * must follow the same family call graph or mutually-exclusive alternatives get flattened into
     * one unconditional renderer surface. */
    private static void discoverModelPredicatesRecursive(ClassNode concrete, String owner,
                                                         MethodNode method,
                                                         Map<String, ClassNode> nodes,
                                                         Map<String, String> modelFields,
                                                         Set<String> family,
                                                         Set<String> targetTypes,
                                                         Map<String, BooleanPredicate> out,
                                                         Set<String> visiting, int depth) {
        if (method == null || nodes == null || depth > 5) return;
        String methodId = owner + '#' + method.name + method.desc;
        if (!visiting.add(methodId)) return;
        try {
            discoverModelPredicates(concrete, method, nodes, modelFields, targetTypes, out);
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || "<init>".equals(call.name)
                        || !modelHelperCall(call, nodes, family)) continue;
                MethodNode callee = findMethod(nodes.get(call.owner), call.name, call.desc);
                if (callee != null) {
                    discoverModelPredicatesRecursive(concrete, call.owner, callee, nodes, modelFields,
                            family, targetTypes, out, visiting, depth + 1);
                }
            }
        } finally {
            visiting.remove(methodId);
        }
    }

    /** Decodes the common verifier-stable model selector shape
     * {@code condition ? MODEL_A : MODEL_B}. Guarding the fall-through alone only labels one side;
     * this labels both branches with complementary predicates. */
    private static void discoverTernaryModelPredicates(ClassNode concrete, MethodNode method,
                                                       Map<String, ClassNode> nodes,
                                                       Map<String, String> modelFields,
                                                       Set<String> targetTypes,
                                                       Map<String, BooleanPredicate> out) {
        if (method == null || method.instructions == null) return;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof JumpInsnNode branch) || !predicateJump(branch)) continue;
            int branchIndex = method.instructions.indexOf(branch);
            int targetIndex = method.instructions.indexOf(branch.label);
            if (branchIndex < 0 || targetIndex <= branchIndex) continue;

            JumpInsnNode joinJump = null;
            for (int i = branchIndex + 1; i < targetIndex; i++) {
                AbstractInsnNode candidate = method.instructions.get(i);
                if (candidate instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO
                        && method.instructions.indexOf(jump.label) > targetIndex) {
                    joinJump = jump;
                }
            }
            if (joinJump == null) continue;
            int joinIndex = method.instructions.indexOf(joinJump.label);
            String fallthroughRef = firstModelRef(method, branchIndex + 1, targetIndex, modelFields);
            String takenRef = firstModelRef(method, targetIndex, joinIndex, modelFields);
            if (fallthroughRef == null || takenRef == null || fallthroughRef.equals(takenRef)) continue;

            BooleanPredicate taken = jumpTakenPredicate(concrete, method, branch, nodes, targetTypes, 0);
            if (taken == null) continue;
            mergePredicate(out, takenRef, taken);
            mergePredicate(out, fallthroughRef, negate(taken));
        }
    }

    private static String firstModelRef(MethodNode method, int from, int to,
                                        Map<String, String> modelFields) {
        int start = Math.max(0, from);
        int end = Math.min(method.instructions.size(), Math.max(start, to));
        for (int i = start; i < end; i++) {
            AbstractInsnNode instruction = method.instructions.get(i);
            if (!(instruction instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETSTATIC && field.getOpcode() != Opcodes.GETFIELD) continue;
            String ref = modelFields.get(field.owner + '.' + field.name);
            if (ref != null) return ref;
        }
        return null;
    }

    private static void mergePredicate(Map<String, BooleanPredicate> out, String ref,
                                       BooleanPredicate predicate) {
        if (ref == null || predicate == null || isConstant(predicate, true)) return;
        out.merge(ref, predicate, (first, second) -> combine(BooleanOp.OR, first, second));
    }

    /**
     * Decode pure boolean model selectors such as `select(diagonal,start,end,bottom)`. The selector
     * itself is interpreted as a tiny truth table from bytecode; each call argument is then replaced
     * with the predicate which produced that boolean in the renderer. Unknown arguments become
     * wildcards, which intentionally keeps both legitimate variants when the renderer loops a
     * top/bottom toggle while still making state-owned alternatives mutually exclusive.
     */
    private static void discoverBooleanSelectorPredicates(ClassNode concrete, MethodNode root,
                                                           Map<String, ClassNode> nodes,
                                                           Map<String, String> modelFields,
                                                           Set<String> targetTypes,
                                                           Map<String, BooleanPredicate> out) {
        if (concrete == null || root == null || root.instructions == null || nodes == null
                || modelFields == null || modelFields.isEmpty()) return;
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(concrete.name, root);
        } catch (AnalyzerException | RuntimeException ignored) {
            return;
        }
        int instructionIndex = 0;
        for (AbstractInsnNode instruction : root.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) {
                instructionIndex++;
                continue;
            }
            Type[] args = Type.getArgumentTypes(call.desc);
            Type result = Type.getReturnType(call.desc);
            if (args.length == 0 || args.length > 6 || result.getSort() != Type.OBJECT
                    || java.util.Arrays.stream(args).anyMatch(type -> type.getSort() != Type.BOOLEAN)) {
                instructionIndex++;
                continue;
            }
            MethodNode selector = findMethod(nodes.get(call.owner), call.name, call.desc);
            Map<Integer, String> table = booleanModelSelectorTable(selector, modelFields, args.length);
            if (table.isEmpty() || new HashSet<>(table.values()).size() < 2) {
                instructionIndex++;
                continue;
            }
            Frame<SourceValue> frame = instructionIndex < frames.length ? frames[instructionIndex] : null;
            instructionIndex++;
            if (frame == null || frame.getStackSize() < args.length) continue;
            int base = frame.getStackSize() - args.length;
            ArrayList<BooleanPredicate> argumentPredicates = new ArrayList<>(args.length);
            for (int argument = 0; argument < args.length; argument++) {
                SourceValue sourceValue = frame.getStack(base + argument);
                BooleanPredicate predicate = null;
                if (sourceValue != null && sourceValue.insns != null && sourceValue.insns.size() == 1) {
                    predicate = booleanValuePredicate(concrete, root,
                            sourceValue.insns.iterator().next(), nodes, targetTypes, 0);
                }
                argumentPredicates.add(predicate);
            }

            LinkedHashMap<String, BooleanPredicate> selected = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> entry : table.entrySet()) {
                BooleanPredicate condition = constant(true);
                for (int argument = 0; argument < args.length; argument++) {
                    BooleanPredicate source = argumentPredicates.get(argument);
                    if (source == null) continue;
                    boolean enabled = (entry.getKey() & (1 << argument)) != 0;
                    condition = combine(BooleanOp.AND, condition, enabled ? source : negate(source));
                }
                selected.merge(entry.getValue(), condition,
                        (first, second) -> combine(BooleanOp.OR, first, second));
            }
            for (Map.Entry<String, BooleanPredicate> entry : selected.entrySet()) {
                BooleanPredicate predicate = entry.getValue();
                if (predicate == null || isConstant(predicate, true)) continue;
                out.merge(entry.getKey(), predicate,
                        (first, second) -> combine(BooleanOp.OR, first, second));
            }
        }
    }

    /** Tiny verifier-safe interpreter for a static boolean-only helper returning model fields. */
    private static Map<Integer, String> booleanModelSelectorTable(MethodNode method,
                                                                  Map<String, String> modelFields,
                                                                  int arguments) {
        if (method == null || method.instructions == null || arguments <= 0 || arguments > 6) {
            return Map.of();
        }
        LinkedHashMap<Integer, String> out = new LinkedHashMap<>();
        for (int mask = 0; mask < (1 << arguments); mask++) {
            String model = interpretBooleanModelSelector(method, modelFields, arguments, mask);
            if (model == null) return Map.of();
            out.put(mask, model);
        }
        return Map.copyOf(out);
    }

    private static String interpretBooleanModelSelector(MethodNode method,
                                                        Map<String, String> modelFields,
                                                        int arguments, int mask) {
        ArrayList<Object> stack = new ArrayList<>();
        int pc = firstRealIndex(method, 0);
        int steps = 0;
        while (pc >= 0 && pc < method.instructions.size() && steps++ < 256) {
            AbstractInsnNode instruction = method.instructions.get(pc);
            int opcode = instruction.getOpcode();
            if (opcode < 0) {
                pc++;
                continue;
            }
            if (instruction instanceof VarInsnNode load && opcode == Opcodes.ILOAD) {
                int argument = load.var - ((method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0);
                if (argument < 0 || argument >= arguments) return null;
                stack.add((mask & (1 << argument)) != 0);
                pc++;
                continue;
            }
            if (opcode == Opcodes.ICONST_0 || opcode == Opcodes.ICONST_1) {
                stack.add(opcode == Opcodes.ICONST_1);
                pc++;
                continue;
            }
            if (instruction instanceof FieldInsnNode field && opcode == Opcodes.GETSTATIC) {
                String model = modelFields.get(field.owner + '.' + field.name);
                if (model == null) return null;
                stack.add(model);
                pc++;
                continue;
            }
            if (instruction instanceof JumpInsnNode jump) {
                if (opcode == Opcodes.GOTO) {
                    pc = firstRealIndex(method, method.instructions.indexOf(jump.label));
                    continue;
                }
                if (opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE) {
                    if (stack.isEmpty() || !(stack.remove(stack.size() - 1) instanceof Boolean value)) return null;
                    boolean taken = opcode == Opcodes.IFEQ ? !value : value;
                    pc = taken ? firstRealIndex(method, method.instructions.indexOf(jump.label)) : pc + 1;
                    continue;
                }
                return null;
            }
            if (opcode == Opcodes.ARETURN) {
                if (stack.isEmpty()) return null;
                Object value = stack.remove(stack.size() - 1);
                return value instanceof String model ? model : null;
            }
            // Selector helpers should contain no side effects. Refuse anything more complex instead
            // of accidentally treating client execution logic as a declarative model selector.
            return null;
        }
        return null;
    }

    /** Predicate for executing the fall-through region containing {@code target}. */
    private static BooleanPredicate guardingPredicate(ClassNode concrete, MethodNode method,
                                                      AbstractInsnNode target,
                                                      Map<String, ClassNode> nodes,
                                                      Set<String> targetTypes, int depth) {
        if (method == null || target == null || depth > 7) return null;
        int targetIndex = method.instructions.indexOf(target);
        JumpInsnNode best = null;
        int bestSpan = Integer.MAX_VALUE;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof JumpInsnNode jump) || !predicateJump(jump)) continue;
            int branch = method.instructions.indexOf(jump);
            int destination = method.instructions.indexOf(jump.label);
            if (branch < 0 || destination <= targetIndex || branch >= targetIndex) continue;
            int span = destination - branch;
            if (span < bestSpan) {
                best = jump;
                bestSpan = span;
            }
        }
        if (best == null) return null;
        BooleanPredicate taken = jumpTakenPredicate(concrete, method, best, nodes, targetTypes,
                depth + 1);
        return taken == null ? null : negate(taken);
    }

    /**
     * Finds the boolean guard which enables a repeated renderer loop. The common bytecode shape is
     * {@code if (active) goto loop; return; loop: ...}; unlike motion heuristics this remains true
     * for a stalled-but-running machine and for helpers which combine several state bits.
     */
    private static BooleanPredicate repeatActivityPredicate(ClassNode concrete, MethodNode method,
                                                            MethodInsnNode repeated,
                                                            Map<String, ClassNode> nodes,
                                                            Set<String> targetTypes) {
        int renderIndex = method.instructions.indexOf(repeated);
        if (renderIndex < 0) return null;
        JumpInsnNode best = null;
        boolean invert = false;
        int bestDistance = Integer.MAX_VALUE;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof JumpInsnNode jump) || !booleanJump(jump.getOpcode())) continue;
            int branch = method.instructions.indexOf(jump);
            int destination = method.instructions.indexOf(jump.label);
            if (branch < 0 || branch >= renderIndex || destination < 0) continue;

            // Fall-through returns, taken branch continues to the repeated draw.
            if (destination <= renderIndex && containsReturn(method, branch + 1, destination)) {
                int distance = renderIndex - branch;
                if (distance < bestDistance) {
                    best = jump;
                    invert = false;
                    bestDistance = distance;
                }
            // Taken branch skips over the repeated draw and returns; fall-through is active.
            } else if (destination > renderIndex
                    && containsReturn(method, destination, Math.min(method.instructions.size(), destination + 12))) {
                int distance = renderIndex - branch;
                if (distance < bestDistance) {
                    best = jump;
                    invert = true;
                    bestDistance = distance;
                }
            }
        }
        if (best == null) return null;
        BooleanPredicate predicate = jumpTakenPredicate(concrete, method, best, nodes, targetTypes, 0);
        return predicate == null ? null : invert ? negate(predicate) : predicate;
    }

    private static boolean containsReturn(MethodNode method, int from, int to) {
        int start = Math.max(0, from);
        int end = Math.min(method.instructions.size(), Math.max(start, to));
        for (int i = start; i < end; i++) {
            int opcode = method.instructions.get(i).getOpcode();
            if (opcode == Opcodes.RETURN || opcode == Opcodes.IRETURN || opcode == Opcodes.ARETURN
                    || opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN
                    || opcode == Opcodes.LRETURN) return true;
        }
        return false;
    }

    private static boolean booleanJump(int opcode) {
        return opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE
                || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL
                || opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE;
    }

    /** Renderer model selectors frequently compare a live float/double value (RPM, progress,
     * pressure, etc.) against a constant before choosing a partial. Those branches are not JVM
     * boolean jumps: javac emits FCMPx/DCMPx followed by IFGT/IFLE/etc. Treating only IFEQ/IFNE
     * as predicate guards can bind a farther unrelated boolean branch to the model and collapse a
     * perfectly reachable model to CONSTANT(false). */
    private static boolean predicateJump(JumpInsnNode jump) {
        if (jump == null) return false;
        if (booleanJump(jump.getOpcode())) return true;
        AbstractInsnNode previous = previousReal(jump);
        int compare = previous == null ? -1 : previous.getOpcode();
        return compare == Opcodes.FCMPL || compare == Opcodes.FCMPG
                || compare == Opcodes.DCMPL || compare == Opcodes.DCMPG;
    }

    /** Expression which must be true for this JVM conditional jump to be taken. */
    private static BooleanPredicate jumpTakenPredicate(ClassNode concrete, MethodNode method,
                                                       JumpInsnNode jump,
                                                       Map<String, ClassNode> nodes,
                                                       Set<String> targetTypes, int depth) {
        if (jump == null || depth > 8) return null;
        BooleanPredicate numeric = numericJumpPredicate(concrete, method, jump, nodes, targetTypes);
        if (numeric != null) return numeric;
        AbstractInsnNode producer = previousReal(jump);
        return switch (jump.getOpcode()) {
            case Opcodes.IFEQ -> negate(booleanValuePredicate(concrete, method, producer, nodes,
                    targetTypes, depth + 1));
            case Opcodes.IFNE -> booleanValuePredicate(concrete, method, producer, nodes,
                    targetTypes, depth + 1);
            case Opcodes.IFNULL -> negate(notNullPredicate(method, producer, nodes, targetTypes));
            case Opcodes.IFNONNULL -> notNullPredicate(method, producer, nodes, targetTypes);
            case Opcodes.IF_ACMPEQ -> stateComparisonPredicate(method, jump, nodes, targetTypes, false);
            case Opcodes.IF_ACMPNE -> stateComparisonPredicate(method, jump, nodes, targetTypes, true);
            default -> null;
        };
    }

    /** Decodes the verifier-stable JVM shape
     * {@code liveValue; constant; FCMPx/DCMPx; IFxx label} into a server-safe numeric predicate.
     * The source must trace back to a BlockEntity-rooted numeric method/field, so arbitrary client
     * renderer calculations are never executed at runtime. */
    private static BooleanPredicate numericJumpPredicate(ClassNode concrete, MethodNode method,
                                                         JumpInsnNode jump,
                                                         Map<String, ClassNode> nodes,
                                                         Set<String> targetTypes) {
        AbstractInsnNode compare = previousReal(jump);
        if (compare == null) return null;
        int compareOpcode = compare.getOpcode();
        if (compareOpcode != Opcodes.FCMPL && compareOpcode != Opcodes.FCMPG
                && compareOpcode != Opcodes.DCMPL && compareOpcode != Opcodes.DCMPG) return null;

        String relation = numericJumpRelation(jump.getOpcode());
        if (relation == null) return null;
        AbstractInsnNode right = previousReal(compare);
        AbstractInsnNode left = previousReal(right);
        Double threshold = numericConstant(right);
        String source = numericSource(concrete, method, left, nodes, targetTypes, 0);
        boolean reversed = false;
        if (threshold == null || source == null) {
            threshold = numericConstant(left);
            source = numericSource(concrete, method, right, nodes, targetTypes, 0);
            reversed = true;
        }
        if (threshold == null || source == null) return null;
        if (reversed) relation = reverseComparison(relation);

        BooleanPredicate predicate = new BooleanPredicate();
        predicate.op = BooleanOp.NUMERIC_COMPARE;
        predicate.source = source;
        predicate.comparison = relation;
        predicate.threshold = threshold;
        return predicate;
    }

    private static String numericJumpRelation(int opcode) {
        return switch (opcode) {
            case Opcodes.IFGT -> "GT";
            case Opcodes.IFGE -> "GE";
            case Opcodes.IFLT -> "LT";
            case Opcodes.IFLE -> "LE";
            case Opcodes.IFEQ -> "EQ";
            case Opcodes.IFNE -> "NE";
            default -> null;
        };
    }

    private static String reverseComparison(String comparison) {
        return switch (comparison) {
            case "GT" -> "LT";
            case "GE" -> "LE";
            case "LT" -> "GT";
            case "LE" -> "GE";
            default -> comparison;
        };
    }

    private static Double numericConstant(AbstractInsnNode instruction) {
        if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Number number) {
            return number.doubleValue();
        }
        int opcode = instruction == null ? -1 : instruction.getOpcode();
        return switch (opcode) {
            case Opcodes.ICONST_M1 -> -1d;
            case Opcodes.ICONST_0, Opcodes.FCONST_0, Opcodes.DCONST_0 -> 0d;
            case Opcodes.ICONST_1, Opcodes.FCONST_1, Opcodes.DCONST_1 -> 1d;
            case Opcodes.ICONST_2, Opcodes.FCONST_2 -> 2d;
            case Opcodes.ICONST_3 -> 3d;
            case Opcodes.ICONST_4 -> 4d;
            case Opcodes.ICONST_5 -> 5d;
            default -> null;
        };
    }

    private static String numericSource(ClassNode concrete, MethodNode method,
                                        AbstractInsnNode producer, Map<String, ClassNode> nodes,
                                        Set<String> targetTypes, int depth) {
        if (producer == null || depth > 8) return null;
        if (producer instanceof VarInsnNode load) {
            int storeOpcode = switch (load.getOpcode()) {
                case Opcodes.FLOAD -> Opcodes.FSTORE;
                case Opcodes.DLOAD -> Opcodes.DSTORE;
                case Opcodes.ILOAD -> Opcodes.ISTORE;
                case Opcodes.LLOAD -> Opcodes.LSTORE;
                default -> -1;
            };
            if (storeOpcode >= 0) {
                AbstractInsnNode stored = localProducer(method, load, storeOpcode);
                return numericSource(concrete, method, stored, nodes, targetTypes, depth + 1);
            }
        }
        if (producer instanceof MethodInsnNode call && numericReturn(call.desc)) {
            return numericLeafSource(concrete, call, nodes, targetTypes, new HashSet<>(), depth + 1);
        }
        if (producer instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                && numericDescriptor(field.desc)) {
            String receiver = objectPathBefore(method, field, nodes, targetTypes, depth + 1);
            return receiver == null ? null : appendBooleanStep(receiver,
                    "F#" + field.owner + '#' + field.name + '#' + field.desc);
        }
        int opcode = producer.getOpcode();
        if (opcode == Opcodes.F2D || opcode == Opcodes.D2F || opcode == Opcodes.I2F
                || opcode == Opcodes.I2D || opcode == Opcodes.L2F || opcode == Opcodes.L2D) {
            return numericSource(concrete, method, previousReal(producer), nodes, targetTypes, depth + 1);
        }
        return null;
    }

    private static boolean numericDescriptor(String descriptor) {
        try {
            int sort = Type.getType(descriptor).getSort();
            return sort == Type.BYTE || sort == Type.SHORT || sort == Type.INT || sort == Type.LONG
                    || sort == Type.FLOAT || sort == Type.DOUBLE;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static BooleanPredicate booleanValuePredicate(ClassNode concrete, MethodNode method,
                                                          AbstractInsnNode producer,
                                                          Map<String, ClassNode> nodes,
                                                          Set<String> targetTypes, int depth) {
        if (producer == null || depth > 8) return null;
        int opcode = producer.getOpcode();
        if (opcode == Opcodes.ICONST_0) return constant(false);
        if (opcode == Opcodes.ICONST_1) return constant(true);
        if (producer instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD) {
            BooleanPredicate assignment = booleanLocalAssignmentPredicate(concrete, method, load,
                    nodes, targetTypes, depth + 1);
            if (assignment != null) return assignment;
            AbstractInsnNode stored = localProducer(method, load, Opcodes.ISTORE);
            return stored == null ? null : booleanValuePredicate(concrete, method, stored, nodes,
                    targetTypes, depth + 1);
        }
        if (producer instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                && "Z".equals(field.desc)) {
            String receiver = objectPathBefore(method, field, nodes, targetTypes, depth + 1);
            return receiver == null ? null : source(appendBooleanStep(receiver,
                    "F#" + field.owner + '#' + field.name + '#' + field.desc));
        }
        if (producer instanceof MethodInsnNode call && booleanReturn(call.desc)) {
            if (call.getOpcode() != Opcodes.INVOKESTATIC
                    && "java/lang/Boolean".equals(call.owner)
                    && "booleanValue".equals(call.name)) {
                String property = statePropertyField(method, previousReal(call));
                if (property != null) {
                    BooleanPredicate predicate = new BooleanPredicate();
                    predicate.op = BooleanOp.STATE_EQUALS;
                    predicate.source = property;
                    predicate.expected = "C#true";
                    return predicate;
                }
            }
            // A zero-argument call directly on a BlockEntity-rooted object is safe to invoke at
            // runtime; everything else is decoded recursively so client renderer helpers never
            // become executable runtime dependencies.
            if (call.getOpcode() != Opcodes.INVOKESTATIC
                    && Type.getArgumentTypes(call.desc).length == 0) {
                String receiver = objectPathBefore(method, call, nodes, targetTypes, depth + 1);
                if (receiver != null) {
                    return source(appendBooleanStep(receiver,
                            "M#" + call.owner + '#' + call.name + '#' + call.desc));
                }
            }
            MethodNode implementation = dispatchMethod(concrete, call, nodes);
            if (implementation != null) {
                return decodeBooleanMethod(concrete, call.owner, implementation, nodes, targetTypes,
                        depth + 1, new HashSet<>());
            }
        }
        return null;
    }

    /** javac/Forge frequently lowers an enum comparison to `if (...) 1 else 0; istore`. Recover
     * that control-flow expression instead of treating the instruction immediately before ISTORE
     * as the value of every path. */
    private static BooleanPredicate booleanLocalAssignmentPredicate(ClassNode concrete,
                                                                    MethodNode method,
                                                                    VarInsnNode load,
                                                                    Map<String, ClassNode> nodes,
                                                                    Set<String> targetTypes,
                                                                    int depth) {
        if (depth > 9) return null;
        VarInsnNode store = null;
        for (AbstractInsnNode cursor = previousReal(load); cursor != null; cursor = previousReal(cursor)) {
            if (cursor instanceof VarInsnNode candidate && candidate.getOpcode() == Opcodes.ISTORE
                    && candidate.var == load.var) {
                store = candidate;
                break;
            }
        }
        if (store == null) return null;
        int storeIndex = method.instructions.indexOf(store);
        int scanned = 0;
        for (AbstractInsnNode cursor = previousReal(store); cursor != null && scanned++ < 18;
             cursor = previousReal(cursor)) {
            if (!(cursor instanceof JumpInsnNode go) || go.getOpcode() != Opcodes.GOTO) continue;
            int destination = firstRealIndex(method, method.instructions.indexOf(go.label));
            if (destination != storeIndex) continue;
            AbstractInsnNode fallValueInsn = previousReal(go);
            BooleanPredicate fallValue = booleanValuePredicate(concrete, method, fallValueInsn,
                    nodes, targetTypes, depth + 1);
            if (fallValue == null || fallValue.op != BooleanOp.CONSTANT) continue;
            int inner = 0;
            for (AbstractInsnNode branchCursor = previousReal(fallValueInsn);
                 branchCursor != null && inner++ < 12; branchCursor = previousReal(branchCursor)) {
                if (!(branchCursor instanceof JumpInsnNode branch) || !booleanJump(branch.getOpcode())
                        || branch.getOpcode() == Opcodes.GOTO) continue;
                int takenIndex = firstRealIndex(method, method.instructions.indexOf(branch.label));
                if (takenIndex < 0 || takenIndex >= storeIndex) continue;
                AbstractInsnNode takenInsn = method.instructions.get(takenIndex);
                BooleanPredicate takenValue = booleanValuePredicate(concrete, method, takenInsn,
                        nodes, targetTypes, depth + 1);
                if (takenValue == null || takenValue.op != BooleanOp.CONSTANT) continue;
                BooleanPredicate condition = jumpTakenPredicate(concrete, method, branch, nodes,
                        targetTypes, depth + 1);
                if (condition != null) return choose(condition, takenValue, fallValue);
            }
        }
        return null;
    }

    /** Decode `state.getValue(STATIC_PROPERTY) == STATIC_ENUM_CONSTANT` without relying on either
     * field's source name. Runtime later resolves the exact server-side objects and compares them. */
    private static BooleanPredicate stateComparisonPredicate(MethodNode method, JumpInsnNode jump,
                                                             Map<String, ClassNode> nodes,
                                                             Set<String> targetTypes,
                                                             boolean negate) {
        AbstractInsnNode right = previousReal(jump);
        AbstractInsnNode left = previousReal(right);
        FieldInsnNode constant = right instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETSTATIC ? field : null;
        AbstractInsnNode stateValue = left;
        if (constant == null && left instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETSTATIC) {
            constant = field;
            stateValue = right;
        }
        if (constant == null) return null;
        String property = statePropertyField(method, stateValue);
        if (property == null) return null;
        BooleanPredicate predicate = new BooleanPredicate();
        predicate.op = BooleanOp.STATE_EQUALS;
        predicate.source = property;
        predicate.expected = "F#" + constant.owner + '#' + constant.name + '#' + constant.desc;
        return negate ? negate(predicate) : predicate;
    }

    private static String statePropertyField(MethodNode method, AbstractInsnNode producer) {
        if (producer == null) return null;
        if (producer instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) {
            return statePropertyField(method, previousReal(cast));
        }
        if (producer instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
            AbstractInsnNode stored = localProducer(method, load, Opcodes.ASTORE);
            return stored == null ? null : statePropertyField(method, stored);
        }
        if (producer instanceof MethodInsnNode call && call.getOpcode() != Opcodes.INVOKESTATIC
                && Type.getArgumentTypes(call.desc).length == 1) {
            AbstractInsnNode property = previousReal(call);
            if (property instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && field.desc != null && field.desc.contains("Property")) {
                return "F#" + field.owner + '#' + field.name + '#' + field.desc;
            }
        }
        return null;
    }

    private static BooleanPredicate notNullPredicate(MethodNode method, AbstractInsnNode producer,
                                                     Map<String, ClassNode> nodes,
                                                     Set<String> targetTypes) {
        String path = objectPath(method, producer, nodes, targetTypes, 0);
        return path == null ? null : notNull(path);
    }

    /** Decodes an acyclic boolean helper into an expression over BlockEntity-rooted sources. */
    private static BooleanPredicate decodeBooleanMethod(ClassNode concrete, String owner,
                                                        MethodNode method, Map<String, ClassNode> nodes,
                                                        Set<String> targetTypes, int depth,
                                                        Set<String> visiting) {
        if (method == null || depth > 8) return null;
        String key = owner + '#' + method.name + method.desc + '@' + System.identityHashCode(method);
        if (!visiting.add(key)) return null;
        try {
            int start = firstRealIndex(method, 0);
            return booleanFlow(concrete, owner, method, start, null, nodes, targetTypes,
                    depth + 1, visiting, new HashSet<>());
        } finally {
            visiting.remove(key);
        }
    }

    private static BooleanPredicate booleanFlow(ClassNode concrete, String owner, MethodNode method,
                                                int start, BooleanPredicate pending,
                                                Map<String, ClassNode> nodes, Set<String> targetTypes,
                                                int depth, Set<String> visiting, Set<Integer> path) {
        if (start < 0 || start >= method.instructions.size() || depth > 16 || !path.add(start)) return null;
        try {
            BooleanPredicate value = pending;
            for (int i = start; i < method.instructions.size(); i++) {
                AbstractInsnNode instruction = method.instructions.get(i);
                int opcode = instruction.getOpcode();
                if (opcode < 0) continue;
                if (opcode == Opcodes.ICONST_0 || opcode == Opcodes.ICONST_1
                        || instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                        && "Z".equals(field.desc)
                        || instruction instanceof MethodInsnNode call && booleanReturn(call.desc)
                        || instruction instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD) {
                    BooleanPredicate decoded = booleanValuePredicate(concrete, method, instruction, nodes,
                            targetTypes, depth + 1);
                    if (decoded != null) value = decoded;
                }
                if (instruction instanceof JumpInsnNode jump) {
                    if (opcode == Opcodes.GOTO) {
                        return booleanFlow(concrete, owner, method,
                                firstRealIndex(method, method.instructions.indexOf(jump.label)), value,
                                nodes, targetTypes, depth + 1, visiting, new HashSet<>(path));
                    }
                    if (booleanJump(opcode)) {
                        BooleanPredicate condition = jumpTakenPredicate(concrete, method, jump, nodes,
                                targetTypes, depth + 1);
                        if (condition == null) return null;
                        int destination = firstRealIndex(method, method.instructions.indexOf(jump.label));
                        int fallthrough = firstRealIndex(method, i + 1);
                        BooleanPredicate taken = booleanFlow(concrete, owner, method, destination, null,
                                nodes, targetTypes, depth + 1, visiting, new HashSet<>(path));
                        BooleanPredicate fall = booleanFlow(concrete, owner, method, fallthrough, null,
                                nodes, targetTypes, depth + 1, visiting, new HashSet<>(path));
                        return choose(condition, taken, fall);
                    }
                }
                if (opcode == Opcodes.IRETURN) return value != null ? value
                        : booleanValuePredicate(concrete, method, previousReal(instruction), nodes,
                        targetTypes, depth + 1);
                if (opcode == Opcodes.RETURN || opcode == Opcodes.ATHROW) return null;
            }
            return null;
        } finally {
            path.remove(start);
        }
    }

    private static int firstRealIndex(MethodNode method, int from) {
        if (method == null) return -1;
        for (int i = Math.max(0, from); i < method.instructions.size(); i++) {
            if (method.instructions.get(i).getOpcode() >= 0) return i;
        }
        return -1;
    }

    private static AbstractInsnNode localProducer(MethodNode method, VarInsnNode load, int storeOpcode) {
        if (method == null || load == null) return null;
        for (AbstractInsnNode cursor = previousReal(load); cursor != null; cursor = previousReal(cursor)) {
            if (cursor instanceof VarInsnNode store && store.getOpcode() == storeOpcode
                    && store.var == load.var) return previousReal(store);
        }
        return null;
    }

    /** Object path ending immediately before a field/method receiver. */
    private static String objectPathBefore(MethodNode method, AbstractInsnNode consumer,
                                           Map<String, ClassNode> nodes, Set<String> targetTypes,
                                           int depth) {
        return objectPath(method, previousReal(consumer), nodes, targetTypes, depth + 1);
    }

    private static String objectPath(MethodNode method, AbstractInsnNode producer,
                                     Map<String, ClassNode> nodes, Set<String> targetTypes, int depth) {
        if (method == null || producer == null || depth > 8) return null;
        if (producer instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) {
            return objectPath(method, previousReal(cast), nodes, targetTypes, depth + 1);
        }
        if (producer instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
            if (blockEntityArgumentLocals(method, nodes, targetTypes).contains(load.var)) return "";
            AbstractInsnNode stored = localProducer(method, load, Opcodes.ASTORE);
            return stored == null ? null : objectPath(method, stored, nodes, targetTypes, depth + 1);
        }
        if (producer instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) {
            String receiver = objectPathBefore(method, field, nodes, targetTypes, depth + 1);
            if (receiver == null) return null;
            return appendBooleanStep(receiver, "F#" + field.owner + '#' + field.name + '#' + field.desc);
        }
        if (producer instanceof MethodInsnNode call && call.getOpcode() != Opcodes.INVOKESTATIC
                && Type.getArgumentTypes(call.desc).length == 0
                && Type.getReturnType(call.desc).getSort() == Type.OBJECT) {
            String receiver = objectPathBefore(method, call, nodes, targetTypes, depth + 1);
            if (receiver == null) return null;
            return appendBooleanStep(receiver, "M#" + call.owner + '#' + call.name + '#' + call.desc);
        }
        return null;
    }

    private static Set<Integer> blockEntityArgumentLocals(MethodNode method, Map<String, ClassNode> nodes,
                                                          Set<String> targetTypes) {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        int local = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            if (argument.getSort() == Type.OBJECT) {
                String type = argument.getInternalName();
                boolean target = isBlockEntityType(type, nodes);
                if (!target && targetTypes != null) {
                    target = targetTypes.stream().anyMatch(candidate ->
                            compatibleTarget(candidate, type, nodes) || compatibleTarget(type, candidate, nodes));
                }
                if (target) out.add(local);
            }
            local += argument.getSize();
        }
        return out;
    }

    private static boolean booleanReturn(String descriptor) {
        try {
            return Type.getReturnType(descriptor).getSort() == Type.BOOLEAN;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String appendBooleanStep(String path, String step) {
        if (path == null) return null;
        return path.isBlank() ? step : path + '>' + step;
    }

    private static BooleanPredicate constant(boolean value) {
        BooleanPredicate predicate = new BooleanPredicate();
        predicate.op = BooleanOp.CONSTANT;
        predicate.constant = value;
        return predicate;
    }

    private static BooleanPredicate source(String value) {
        if (value == null || value.isBlank()) return null;
        BooleanPredicate predicate = new BooleanPredicate();
        predicate.op = BooleanOp.SOURCE;
        predicate.source = value;
        return predicate;
    }

    private static BooleanPredicate notNull(String value) {
        if (value == null || value.isBlank()) return null;
        BooleanPredicate predicate = new BooleanPredicate();
        predicate.op = BooleanOp.NOT_NULL;
        predicate.source = value;
        return predicate;
    }

    private static BooleanPredicate negate(BooleanPredicate value) {
        if (value == null) return null;
        if (value.op == BooleanOp.CONSTANT) return constant(!value.constant);
        if (value.op == BooleanOp.NUMERIC_COMPARE && value.comparison != null) {
            BooleanPredicate predicate = new BooleanPredicate();
            predicate.op = BooleanOp.NUMERIC_COMPARE;
            predicate.source = value.source;
            predicate.threshold = value.threshold;
            predicate.comparison = switch (value.comparison) {
                case "GT" -> "LE";
                case "GE" -> "LT";
                case "LT" -> "GE";
                case "LE" -> "GT";
                case "EQ" -> "NE";
                case "NE" -> "EQ";
                default -> null;
            };
            if (predicate.comparison != null) return predicate;
        }
        if (value.op == BooleanOp.NOT && value.terms != null && value.terms.size() == 1) {
            return value.terms.get(0);
        }
        BooleanPredicate predicate = new BooleanPredicate();
        predicate.op = BooleanOp.NOT;
        predicate.terms = new ArrayList<>(List.of(value));
        return predicate;
    }

    private static BooleanPredicate combine(BooleanOp op, BooleanPredicate first,
                                            BooleanPredicate second) {
        if (first == null) return second;
        if (second == null) return first;
        if (op == BooleanOp.AND) {
            if (isConstant(first, false) || isConstant(second, false)) return constant(false);
            if (isConstant(first, true)) return second;
            if (isConstant(second, true)) return first;
        } else if (op == BooleanOp.OR) {
            if (isConstant(first, true) || isConstant(second, true)) return constant(true);
            if (isConstant(first, false)) return second;
            if (isConstant(second, false)) return first;
        }
        BooleanPredicate predicate = new BooleanPredicate();
        predicate.op = op;
        predicate.terms = new ArrayList<>();
        if (first.op == op && first.terms != null) predicate.terms.addAll(first.terms);
        else predicate.terms.add(first);
        if (second.op == op && second.terms != null) predicate.terms.addAll(second.terms);
        else predicate.terms.add(second);
        // The same model handle can be reached through bridge/overload render roots. Keep one
        // copy of an equivalent term so duplicated call paths do not bloat runtime predicates.
        LinkedHashMap<String, BooleanPredicate> unique = new LinkedHashMap<>();
        for (BooleanPredicate term : predicate.terms) {
            if (term != null) unique.putIfAbsent(term.toString(), term);
        }
        predicate.terms = new ArrayList<>(unique.values());
        if (predicate.terms.size() == 1) return predicate.terms.get(0);
        return predicate;
    }

    /** if condition then taken else fallthrough, simplified into AND/OR/NOT. */
    private static BooleanPredicate choose(BooleanPredicate condition, BooleanPredicate taken,
                                           BooleanPredicate fallthrough) {
        if (condition == null || taken == null || fallthrough == null) return null;
        if (isConstant(taken, true) && isConstant(fallthrough, false)) return condition;
        if (isConstant(taken, false) && isConstant(fallthrough, true)) return negate(condition);
        if (isConstant(taken, true)) return combine(BooleanOp.OR, condition, fallthrough);
        if (isConstant(taken, false)) return combine(BooleanOp.AND, negate(condition), fallthrough);
        if (isConstant(fallthrough, true)) return combine(BooleanOp.OR, negate(condition), taken);
        if (isConstant(fallthrough, false)) return combine(BooleanOp.AND, condition, taken);
        return combine(BooleanOp.OR,
                combine(BooleanOp.AND, condition, taken),
                combine(BooleanOp.AND, negate(condition), fallthrough));
    }

    private static boolean isConstant(BooleanPredicate predicate, boolean value) {
        return predicate != null && predicate.op == BooleanOp.CONSTANT && predicate.constant == value;
    }

    private static LinearRepeat linearRepeatFromRoot(ClassNode concrete, String owner, MethodNode root,
                                                     Map<String, ClassNode> nodes,
                                                     Map<String, String> modelFields,
                                                     Set<String> targetTypes) {
        List<MethodInsnNode> translationCalls = new ArrayList<>();
        MethodInsnNode helperShape = null;
        int axis = 1;
        float direction = -1f;
        for (AbstractInsnNode instruction : root.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || call.desc == null
                    || call.getOpcode() != Opcodes.INVOKESTATIC || !nodes.containsKey(call.owner)) continue;
            TranslationAxis translated = translatedFloatArgument(nodes.get(call.owner), call);
            if (translated == null) continue;
            translationCalls.add(call);
            helperShape = call;
            axis = translated.axis;
            direction = translated.sign;
        }
        // One ordinary translated part is not a segment renderer. Require a repeated call plus at
        // least one companion/fractional call so generic moving heads do not get classified here.
        if (translationCalls.size() < 2 || helperShape == null) return null;

        Map<Integer, VisualSource> locals = visualLocals(concrete, root, nodes, modelFields);
        List<RenderCall> rendered = new ArrayList<>();
        for (MethodInsnNode call : translationCalls) {
            VisualSource source = sourceLocalBefore(call, locals);
            if (source != null && source.present()) rendered.add(new RenderCall(call, source,
                    insideBackwardLoop(root, call)));
        }
        RenderCall repeated = rendered.stream().filter(RenderCall::looped).findFirst().orElse(null);
        if (repeated == null) return null;

        int driver = driverLocal(root, translationCalls);
        if (driver < 0) return null;
        MethodInsnNode producer = numericProducerForLocal(root, driver);
        String valueSource = numericLeafSource(concrete, producer, nodes, targetTypes,
                new HashSet<>(), 0);
        if (valueSource == null) return null;

        LinearRepeat rule = new LinearRepeat();
        rule.valueSource = valueSource;
        rule.segment = repeated.source;
        rule.axis = axis;
        rule.direction = direction;
        rule.activityPredicate = repeatActivityPredicate(concrete, root, repeated.call, nodes,
                targetTypes);
        LoopFormula formula = loopFormula(root, repeated.call, driver);
        if (formula != null) {
            rule.loopThreshold = formula.threshold;
            rule.positionBias = formula.positionBias;
        }

        // The first non-loop render is the terminal in the common renderer shape. Calls after a
        // remainder operation are fractional segment draws. Distinguish those structurally.
        int remainderIndex = firstOpcodeIndex(root, Opcodes.FREM);
        int firstCallIndex = Integer.MAX_VALUE;
        RenderCall terminalCall = null;
        for (RenderCall call : rendered) {
            if (call.looped) continue;
            int index = root.instructions.indexOf(call.call);
            if (index < firstCallIndex && (remainderIndex < 0 || index < remainderIndex)) {
                firstCallIndex = index;
                terminalCall = call;
            }
            if (remainderIndex >= 0 && index > remainderIndex && rule.halfSegment == null
                    && !visualSourceEquals(call.source, rule.segment)) {
                rule.halfSegment = call.source;
            }
        }
        if (terminalCall != null) {
            ThresholdVisuals terminalBranch = thresholdVisuals(root, terminalCall.call, driver, locals);
            if (terminalBranch != null) {
                rule.terminal = terminalBranch.above;
                rule.halfTerminal = terminalBranch.below;
                rule.terminalThreshold = terminalBranch.threshold;
            } else {
                rule.terminal = terminalCall.source;
                List<VisualSource> alternatives = visualSourcesBeforeBranchCall(terminalCall.call, locals);
                if (alternatives.size() > 1) {
                    for (VisualSource alternative : alternatives) {
                        if (!visualSourceEquals(alternative, rule.terminal)) {
                            rule.halfTerminal = alternative;
                            break;
                        }
                    }
                    Float threshold = nearestComparedFloatConstant(root, terminalCall.call, driver, 48);
                    if (threshold != null) rule.terminalThreshold = threshold;
                }
            }
        }
        float[] fraction = fractionalThresholds(root, remainderIndex);
        if (fraction != null) {
            rule.fractionLow = fraction[0];
            rule.fractionHigh = fraction[1];
        }
        Float halfMinimum = halfMinimumValue(root, remainderIndex, driver);
        if (halfMinimum != null) rule.halfMinimumValue = halfMinimum;
        return rule;
    }

    private record TranslationAxis(int axis, float sign) {}

    /** Sources selected by a simple javac ternary/if around a numeric threshold. */
    private record ThresholdVisuals(VisualSource above, VisualSource below, float threshold) {}

    private static ThresholdVisuals thresholdVisuals(MethodNode root, MethodInsnNode call, int driver,
                                                     Map<Integer, VisualSource> locals) {
        int callIndex = root.instructions.indexOf(call);
        if (callIndex < 0) return null;
        for (int i = callIndex - 1, remaining = 56; i >= 0 && remaining-- > 0; i--) {
            AbstractInsnNode instruction = root.instructions.get(i);
            if (!(instruction instanceof JumpInsnNode jump) || jump.label == null) continue;
            int target = root.instructions.indexOf(jump.label);
            if (target <= i || target >= callIndex) continue;
            AbstractInsnNode compare = previousReal(jump);
            if (compare == null || compare.getOpcode() != Opcodes.FCMPL
                    && compare.getOpcode() != Opcodes.FCMPG) continue;
            AbstractInsnNode rhs = previousReal(compare);
            AbstractInsnNode lhs = previousReal(rhs);
            if (!(lhs instanceof VarInsnNode load) || load.getOpcode() != Opcodes.FLOAD
                    || load.var != driver) continue;
            Float threshold = floatConstant(rhs);
            if (threshold == null) continue;

            VisualSource fallthrough = visualLocalBetween(root, i + 1, target, locals);
            VisualSource jumped = visualLocalBetween(root, target, callIndex, locals);
            if (fallthrough == null || jumped == null || visualSourceEquals(fallthrough, jumped)) continue;
            return switch (jump.getOpcode()) {
                case Opcodes.IFLE, Opcodes.IFLT -> new ThresholdVisuals(fallthrough, jumped, threshold);
                case Opcodes.IFGE, Opcodes.IFGT -> new ThresholdVisuals(jumped, fallthrough, threshold);
                default -> null;
            };
        }
        return null;
    }

    private static VisualSource visualLocalBetween(MethodNode root, int from, int to,
                                                   Map<Integer, VisualSource> locals) {
        int start = Math.max(0, from);
        int end = Math.min(root.instructions.size(), to);
        for (int i = start; i < end; i++) {
            AbstractInsnNode instruction = root.instructions.get(i);
            if (instruction instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
                VisualSource source = locals.get(load.var);
                if (source != null && source.present()) return source;
            }
        }
        return null;
    }

    /** Proves which XYZ argument of a renderer helper is driven by its float parameter. */
    private static TranslationAxis translatedFloatArgument(ClassNode owner, MethodInsnNode invocation) {
        MethodNode helper = findMethod(owner, invocation.name, invocation.desc);
        if (helper == null || helper.instructions == null) return null;
        int floatLocal = argumentLocal(invocation.desc, (invocation.getOpcode() == Opcodes.INVOKESTATIC),
                Type.FLOAT_TYPE);
        if (floatLocal < 0) return null;
        for (AbstractInsnNode instruction : helper.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || call.desc == null
                    || !call.desc.startsWith("(FFF)")) continue;
            ArrayList<AbstractInsnNode> args = new ArrayList<>();
            AbstractInsnNode cursor = previousReal(call);
            for (int i = 0; cursor != null && i < 8; i++, cursor = previousReal(cursor)) {
                args.add(cursor);
            }
            java.util.Collections.reverse(args);
            int component = 0;
            for (int i = 0; i < args.size() && component < 3; i++) {
                AbstractInsnNode arg = args.get(i);
                if (arg instanceof VarInsnNode load && load.getOpcode() == Opcodes.FLOAD
                        && load.var == floatLocal) {
                    boolean negative = i + 1 < args.size() && args.get(i + 1).getOpcode() == Opcodes.FNEG;
                    return new TranslationAxis(component, negative ? -1f : 1f);
                }
                if (floatStackProducer(arg)) component++;
            }
        }
        return null;
    }

    private static boolean floatStackProducer(AbstractInsnNode instruction) {
        if (instruction == null) return false;
        int op = instruction.getOpcode();
        return op == Opcodes.FCONST_0 || op == Opcodes.FCONST_1 || op == Opcodes.FCONST_2
                || op == Opcodes.FLOAD || instruction instanceof LdcInsnNode ldc
                && ldc.cst instanceof Float;
    }

    private static int argumentLocal(String descriptor, boolean isStatic, Type wanted) {
        if (descriptor == null) return -1;
        int local = isStatic ? 0 : 1;
        for (Type argument : Type.getArgumentTypes(descriptor)) {
            if (argument.equals(wanted)) return local;
            local += argument.getSize();
        }
        return -1;
    }

    private static Map<Integer, VisualSource> visualLocals(ClassNode concrete, MethodNode root,
                                                           Map<String, ClassNode> nodes,
                                                           Map<String, String> modelFields) {
        LinkedHashMap<Integer, VisualSource> out = new LinkedHashMap<>();
        for (AbstractInsnNode instruction : root.instructions) {
            if (!(instruction instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) continue;
            VisualSource source = visualSourceBefore(concrete, instruction, nodes, modelFields,
                    new HashSet<>(), 0);
            if (source != null && source.present()) out.put(store.var, source);
        }
        return out;
    }

    private static VisualSource visualSourceBefore(ClassNode concrete, AbstractInsnNode end,
                                                   Map<String, ClassNode> nodes,
                                                   Map<String, String> modelFields,
                                                   Set<String> visiting, int depth) {
        if (depth > 6) return null;
        AbstractInsnNode producer = previousReal(end);
        if (producer instanceof MethodInsnNode call) {
            VisualSource source = visualSourceFromCall(concrete, call, nodes, modelFields,
                    visiting, depth + 1);
            if (source != null) return source;
            // The ASTORE owns this invocation's result. Search only the invocation expression for
            // an injected/static model argument; never walk through the call into an earlier
            // statement. That old window scan let VertexConsumer locals inherit a nearby coil.
            return visualSourceInInvocationArguments(concrete, call, nodes, modelFields,
                    visiting, depth + 1);
        }
        int remaining = 16;
        for (AbstractInsnNode cursor = producer; cursor != null && remaining-- > 0;
             cursor = previousReal(cursor)) {
            if (cursor instanceof FieldInsnNode field
                    && (field.getOpcode() == Opcodes.GETSTATIC || field.getOpcode() == Opcodes.GETFIELD)) {
                String ref = modelFields.get(field.owner + '.' + field.name);
                if (ref != null) return modelSource(ref);
            }
            if (cursor instanceof MethodInsnNode call) {
                VisualSource source = visualSourceFromCall(concrete, call, nodes, modelFields,
                        visiting, depth + 1);
                if (source != null) return source;
                break;
            }
            if (cursor.getOpcode() == Opcodes.ASTORE) break;
        }
        return null;
    }

    private static VisualSource visualSourceInInvocationArguments(ClassNode concrete,
                                                                  MethodInsnNode invocation,
                                                                  Map<String, ClassNode> nodes,
                                                                  Map<String, String> modelFields,
                                                                  Set<String> visiting, int depth) {
        if (invocation == null || depth > 6) return null;
        int remaining = 24;
        for (AbstractInsnNode cursor = previousReal(invocation); cursor != null && remaining-- > 0;
             cursor = previousReal(cursor)) {
            if (cursor instanceof FieldInsnNode field
                    && (field.getOpcode() == Opcodes.GETSTATIC || field.getOpcode() == Opcodes.GETFIELD)) {
                String ref = modelFields.get(field.owner + '.' + field.name);
                if (ref != null) return modelSource(ref);
            }
            if (cursor instanceof MethodInsnNode nested) {
                VisualSource source = visualSourceFromCall(concrete, nested, nodes, modelFields,
                        visiting, depth + 1);
                if (source != null) return source;
                // A nested object-producing invocation is the producer for one argument. If it
                // carries no scanner-owned model, earlier statements are not part of this value.
                try {
                    if (Type.getReturnType(nested.desc).getSort() == Type.OBJECT) break;
                } catch (Exception ignored) {
                    break;
                }
            }
            if (cursor.getOpcode() == Opcodes.ASTORE) break;
        }
        return null;
    }

    private static VisualSource visualSourceFromCall(ClassNode concrete, MethodInsnNode call,
                                                     Map<String, ClassNode> nodes,
                                                     Map<String, String> modelFields,
                                                     Set<String> visiting, int depth) {
        if (call == null || depth > 6) return null;
        MethodNode implementation = dispatchMethod(concrete, call, nodes);
        if (implementation == null || implementation.instructions == null) return null;
        String id = call.name + call.desc + '@' + System.identityHashCode(implementation);
        if (!visiting.add(id)) return null;
        try {
            FieldInsnNode registryCandidate = null;
            for (AbstractInsnNode instruction : implementation.instructions) {
                if (instruction instanceof FieldInsnNode field
                        && (field.getOpcode() == Opcodes.GETSTATIC || field.getOpcode() == Opcodes.GETFIELD)) {
                    String ref = modelFields.get(field.owner + '.' + field.name);
                    if (ref != null) return modelSource(ref);
                    if (field.getOpcode() == Opcodes.GETSTATIC && registryCandidate == null
                            && !field.owner.startsWith("java/") && !field.owner.startsWith("net/minecraft/")) {
                        registryCandidate = field;
                    }
                }
                if (!(instruction instanceof MethodInsnNode nested)) continue;
                if (registryCandidate != null && returnsBlockState(nested.desc)) {
                    VisualSource source = new VisualSource();
                    source.blockField = "F#" + registryCandidate.owner + '#' + registryCandidate.name
                            + '#' + registryCandidate.desc;
                    source.blockAccessor = "M#" + nested.owner + '#' + nested.name + '#' + nested.desc;
                    return source;
                }
                VisualSource nestedSource = visualSourceFromCall(concrete, nested, nodes, modelFields,
                        visiting, depth + 1);
                if (nestedSource != null) return nestedSource;
            }
        } finally {
            visiting.remove(id);
        }
        return null;
    }

    private static MethodNode dispatchMethod(ClassNode concrete, MethodInsnNode call,
                                             Map<String, ClassNode> nodes) {
        if (call == null || nodes == null) return null;
        for (ClassNode current = concrete; current != null; current = nodes.get(current.superName)) {
            MethodNode method = findMethod(current, call.name, call.desc);
            if (method != null && (method.access & Opcodes.ACC_ABSTRACT) == 0) return method;
            if (current.name.equals(call.owner)) break;
        }
        MethodNode exact = findMethod(nodes.get(call.owner), call.name, call.desc);
        return exact != null && (exact.access & Opcodes.ACC_ABSTRACT) == 0 ? exact : null;
    }

    private static boolean returnsBlockState(String descriptor) {
        try {
            Type type = Type.getReturnType(descriptor);
            return type.getSort() == Type.OBJECT
                    && "net/minecraft/world/level/block/state/BlockState".equals(type.getInternalName());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static VisualSource modelSource(String ref) {
        VisualSource source = new VisualSource();
        source.modelRef = ref;
        return source;
    }

    private static VisualSource sourceLocalBefore(MethodInsnNode call, Map<Integer, VisualSource> locals) {
        int remaining = 24;
        for (AbstractInsnNode cursor = previousReal(call); cursor != null && remaining-- > 0;
             cursor = previousReal(cursor)) {
            if (cursor instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
                VisualSource source = locals.get(load.var);
                if (source != null) return source;
            }
        }
        return null;
    }

    private static List<VisualSource> visualSourcesBeforeBranchCall(MethodInsnNode call,
                                                                   Map<Integer, VisualSource> locals) {
        LinkedHashMap<String, VisualSource> found = new LinkedHashMap<>();
        int remaining = 42;
        for (AbstractInsnNode cursor = previousReal(call); cursor != null && remaining-- > 0;
             cursor = previousReal(cursor)) {
            if (cursor instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
                VisualSource source = locals.get(load.var);
                if (source != null) found.put(source.toString(), source);
            }
            if (cursor instanceof MethodInsnNode previous && previous != call
                    && previous.desc != null && previous.desc.contains("PoseStack")) break;
        }
        return List.copyOf(found.values());
    }

    private static boolean insideBackwardLoop(MethodNode method, AbstractInsnNode target) {
        int targetIndex = method.instructions.indexOf(target);
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof JumpInsnNode jump) || jump.label == null) continue;
            int jumpIndex = method.instructions.indexOf(jump);
            int labelIndex = method.instructions.indexOf(jump.label);
            if (labelIndex < targetIndex && targetIndex < jumpIndex) return true;
        }
        return false;
    }

    private static int driverLocal(MethodNode root, List<MethodInsnNode> calls) {
        LinkedHashMap<Integer, Integer> uses = new LinkedHashMap<>();
        for (MethodInsnNode call : calls) {
            int remaining = 18;
            for (AbstractInsnNode cursor = previousReal(call); cursor != null && remaining-- > 0;
                 cursor = previousReal(cursor)) {
                if (cursor instanceof VarInsnNode load && load.getOpcode() == Opcodes.FLOAD) {
                    uses.merge(load.var, 1, Integer::sum);
                }
            }
        }
        return uses.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(-1);
    }

    private static MethodInsnNode numericProducerForLocal(MethodNode root, int local) {
        for (AbstractInsnNode instruction : root.instructions) {
            if (!(instruction instanceof VarInsnNode store) || store.getOpcode() != Opcodes.FSTORE
                    || store.var != local) continue;
            AbstractInsnNode previous = previousReal(store);
            if (previous instanceof MethodInsnNode call) return call;
        }
        return null;
    }

    private static String numericLeafSource(ClassNode concrete, MethodInsnNode call,
                                            Map<String, ClassNode> nodes, Set<String> targetTypes,
                                            Set<String> visiting, int depth) {
        if (call == null || depth > 7) return null;
        if (numericReturn(call.desc) && isBlockEntityType(call.owner, nodes)
                && !rendererLike(call.owner)) {
            return "M#" + call.owner + '#' + call.name + '#' + call.desc;
        }
        MethodNode method = dispatchMethod(concrete, call, nodes);
        if (method == null || method.instructions == null) return null;
        String id = call.owner + '#' + call.name + call.desc + '@' + System.identityHashCode(method);
        if (!visiting.add(id)) return null;
        try {
            String fallback = null;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode nested) || !numericReturn(nested.desc)) continue;
                String source = numericLeafSource(concrete, nested, nodes, targetTypes, visiting, depth + 1);
                if (source == null) continue;
                if (targetTypes != null && targetTypes.stream().anyMatch(source::contains)) return source;
                fallback = source;
            }
            return fallback;
        } finally {
            visiting.remove(id);
        }
    }

    private static boolean rendererLike(String owner) {
        return owner != null && owner.toLowerCase(Locale.ROOT).contains("renderer");
    }

    private static boolean numericReturn(String descriptor) {
        try {
            int sort = Type.getReturnType(descriptor).getSort();
            return sort == Type.FLOAT || sort == Type.DOUBLE || sort == Type.INT || sort == Type.LONG;
        } catch (Exception ignored) {
            return false;
        }
    }

    private record LoopFormula(float threshold, float positionBias) {}

    private static LoopFormula loopFormula(MethodNode root, MethodInsnNode render, int driver) {
        int renderIndex = root.instructions.indexOf(render);
        JumpInsnNode back = null;
        for (AbstractInsnNode instruction : root.instructions) {
            if (!(instruction instanceof JumpInsnNode jump)) continue;
            int jumpIndex = root.instructions.indexOf(jump);
            int labelIndex = root.instructions.indexOf(jump.label);
            if (labelIndex < renderIndex && renderIndex < jumpIndex) {
                back = jump;
                break;
            }
        }
        if (back == null) return null;
        int labelIndex = root.instructions.indexOf(back.label);
        float threshold = loopThreshold(root, labelIndex, renderIndex, driver);
        float bias = 0f;
        int remaining = 14;
        boolean sawDriver = false;
        for (AbstractInsnNode cursor = previousReal(render); cursor != null && remaining-- > 0;
             cursor = previousReal(cursor)) {
            if (cursor instanceof VarInsnNode load && load.getOpcode() == Opcodes.FLOAD
                    && load.var == driver) {
                sawDriver = true;
                break;
            }
            Float value = floatConstant(cursor);
            if (value != null && previousReal(cursor) != null
                    && (nextReal(cursor) != null && nextReal(cursor).getOpcode() == Opcodes.FSUB)) {
                bias = -value;
            }
        }
        return sawDriver ? new LoopFormula(threshold, bias) : null;
    }

    /** Decodes the loop guard expression around a repeated renderer call. The common verifier
     * shape is {@code index; value; constant; FSUB; FCMP*; IF* exit}, meaning
     * {@code index < value - constant}. Looking specifically at the guard prevents placement
     * constants later in the loop body from being mistaken for the repetition threshold. */
    private static float loopThreshold(MethodNode root, int labelIndex, int renderIndex, int driver) {
        int end = Math.min(renderIndex, labelIndex + 28);
        for (int i = labelIndex; i < end; i++) {
            AbstractInsnNode instruction = root.instructions.get(i);
            if (!(instruction instanceof VarInsnNode load) || load.getOpcode() != Opcodes.FLOAD
                    || load.var != driver) continue;
            AbstractInsnNode cursor = nextReal(load);
            Float constant = cursor == null ? null : floatConstant(cursor);
            if (constant == null) continue;
            AbstractInsnNode arithmetic = nextReal(cursor);
            if (arithmetic != null && arithmetic.getOpcode() == Opcodes.FSUB) return constant;
        }
        return 0f;
    }

    private static int firstOpcodeIndex(MethodNode method, int opcode) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) return method.instructions.indexOf(instruction);
        }
        return -1;
    }

    private static float[] fractionalThresholds(MethodNode root, int after) {
        if (after < 0) return null;
        ArrayList<Float> values = new ArrayList<>();
        for (int i = after + 1; i < root.instructions.size() && i < after + 60; i++) {
            Float value = floatConstant(root.instructions.get(i));
            if (value != null && value > 0f && value < 1f && !values.contains(value)) values.add(value);
        }
        if (values.size() < 2) return null;
        values.sort(Float::compare);
        return new float[]{values.get(0), values.get(values.size() - 1)};
    }

    private static Float halfMinimumValue(MethodNode root, int remainderIndex, int driver) {
        if (remainderIndex < 0) return null;
        // The fractional-piece gate belongs to the code which consumes the remainder, not to an
        // earlier terminal/attachment branch.  In javac output the renderer typically computes
        // `fraction = value % 1`, stores it, then immediately compares the original driver against
        // the minimum length before testing the fractional interval itself.  Searching backwards
        // confused unrelated terminal thresholds (e.g. magnet-at-.25) with this gate.
        for (int i = remainderIndex + 1; i < root.instructions.size() && i < remainderIndex + 28; i++) {
            AbstractInsnNode instruction = root.instructions.get(i);
            if (!(instruction instanceof VarInsnNode load) || load.getOpcode() != Opcodes.FLOAD
                    || load.var != driver) continue;
            AbstractInsnNode rhs = nextReal(load);
            Float value = floatConstant(rhs);
            if (value == null || value < 0f) continue;
            AbstractInsnNode compare = nextReal(rhs);
            AbstractInsnNode branch = nextReal(compare);
            if (compare != null && (compare.getOpcode() == Opcodes.FCMPL
                    || compare.getOpcode() == Opcodes.FCMPG)
                    && branch instanceof JumpInsnNode) {
                return value;
            }
        }
        // Compatibility fallback for renderers which place the minimum gate before calculating
        // the remainder.  Require an actual floating comparison so a nearby transform constant is
        // never mistaken for a length threshold.
        for (int i = Math.max(0, remainderIndex - 20); i < remainderIndex; i++) {
            AbstractInsnNode instruction = root.instructions.get(i);
            if (!(instruction instanceof VarInsnNode load) || load.getOpcode() != Opcodes.FLOAD
                    || load.var != driver) continue;
            AbstractInsnNode rhs = nextReal(load);
            Float value = floatConstant(rhs);
            AbstractInsnNode compare = nextReal(rhs);
            AbstractInsnNode branch = nextReal(compare);
            if (value != null && value >= 0f && compare != null
                    && (compare.getOpcode() == Opcodes.FCMPL || compare.getOpcode() == Opcodes.FCMPG)
                    && branch instanceof JumpInsnNode) return value;
        }
        return null;
    }

    private static Float nearestComparedFloatConstant(MethodNode root, MethodInsnNode before,
                                                      int driver, int window) {
        int remaining = window;
        for (AbstractInsnNode cursor = previousReal(before); cursor != null && remaining-- > 0;
             cursor = previousReal(cursor)) {
            if (!(cursor instanceof VarInsnNode load) || load.getOpcode() != Opcodes.FLOAD
                    || load.var != driver) continue;
            AbstractInsnNode next = nextReal(cursor);
            for (int i = 0; next != null && i < 5; i++, next = nextReal(next)) {
                Float value = floatConstant(next);
                if (value != null) return value;
            }
        }
        return null;
    }

    private static Float floatConstant(AbstractInsnNode instruction) {
        if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Float value) return value;
        int opcode = instruction == null ? -1 : instruction.getOpcode();
        return switch (opcode) {
            case Opcodes.FCONST_0 -> 0f;
            case Opcodes.FCONST_1 -> 1f;
            case Opcodes.FCONST_2 -> 2f;
            default -> null;
        };
    }


    private static void collectDirectionalSelectors(String owner, MethodNode method,
                                                    Map<String, ClassNode> nodes,
                                                    Set<String> family, Set<String> visiting,
                                                    Set<String> out, Set<String> presenceOut,
                                                    int depth) {
        if (method == null || method.instructions == null || depth > 5) return;
        String methodId = owner + '#' + method.name + method.desc;
        if (!visiting.add(methodId)) return;
        try {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || call.desc == null) continue;
                if (directionalSelectorCall(call)) {
                    out.add(call.owner + '#' + call.name + '#' + call.desc);
                }
                if (directionalPresenceCall(call)) {
                    presenceOut.add(call.owner + '#' + call.name + '#' + call.desc);
                }
                // Model wrappers commonly delegate attachment selection to a small helper. Follow
                // only the renderer/model inheritance family, with the same bounded recursion used
                // for model-ref discovery, so unrelated utility call graphs cannot explode scan cost.
                if (nodes == null || family == null || !family.contains(call.owner)
                        || "<init>".equals(call.name)) continue;
                MethodNode callee = findMethod(nodes.get(call.owner), call.name, call.desc);
                if (callee != null) {
                    collectDirectionalSelectors(call.owner, callee, nodes, family, visiting,
                            out, presenceOut, depth + 1);
                }
            }
        } finally {
            visiting.remove(methodId);
        }
    }

    private static boolean directionalSelectorCall(MethodInsnNode call) {
        if (call == null || call.desc == null) return false;
        String descriptor = call.desc;
        if (!descriptor.contains("Lnet/minecraft/core/Direction;")
                || !descriptor.contains("Lnet/minecraft/core/BlockPos;")
                || !descriptor.contains("Lnet/minecraft/world/level/block/state/BlockState;")) {
            return false;
        }
        org.objectweb.asm.Type returned;
        try {
            returned = org.objectweb.asm.Type.getReturnType(descriptor);
        } catch (Exception ignored) {
            return false;
        }
        if (returned.getSort() != org.objectweb.asm.Type.OBJECT) return false;
        String name = call.name == null ? "" : call.name.toLowerCase(Locale.ROOT);
        String type = returned.getInternalName() == null ? ""
                : returned.getInternalName().toLowerCase(Locale.ROOT);
        return name.contains("attach") || name.contains("rim") || name.contains("connect")
                || name.contains("render") || type.contains("attach") || type.contains("connect");
    }

    private static boolean directionalPresenceCall(MethodInsnNode call) {
        if (call == null || call.desc == null) return false;
        String descriptor = call.desc;
        if (!descriptor.contains("Lnet/minecraft/core/Direction;")
                || !descriptor.contains("Lnet/minecraft/core/BlockPos;")
                || !descriptor.contains("Lnet/minecraft/world/level/block/state/BlockState;")) {
            return false;
        }
        Type returned;
        try {
            returned = Type.getReturnType(descriptor);
        } catch (Exception ignored) {
            return false;
        }
        // The renderer is the semantic evidence: a boolean call receiving world/position/state/
        // direction and guarding renderer-owned geometry is a per-face presence predicate. Do not
        // require API names such as hasShaftTowards; addons and obfuscated mods may name it anything.
        return returned.getSort() == Type.BOOLEAN;
    }

    /** Scanner-owned render plan for one block id. When authoritative is true the runtime
     * registry identified the concrete Block class, so callers must not add filename-guessed
     * renderer parts that are absent from this plan. */
    public record BlockRenderPlan(boolean authoritative, Set<String> modelRefs,
                                  Map<String, Integer> modelCopies,
                                  Map<String, BooleanPredicate> modelPredicates,
                                  Set<String> directionalSelectors,
                                  Set<String> directionalPresenceSelectors,
                                  List<DirectionalPresenceRule> directionalPresenceRules,
                                  Set<String> targetTypes,
                                  List<LinearRepeat> linearRepeats) {
        public int copies(String modelRef) {
            return Math.max(1, modelCopies == null ? 1 : modelCopies.getOrDefault(modelRef, 1));
        }
    }

    /** Direction-aware attachment selectors used by the scanner-owned render plan. */
    public static Set<String> directionalSelectorsForBlock(Collection<ModScanner.Profile> profiles,
                                                            String namespace, String blockPath) {
        return renderPlanForBlock(profiles, namespace, blockPath).directionalSelectors();
    }

    public static Set<String> directionalPresenceSelectorsForBlock(
            Collection<ModScanner.Profile> profiles, String namespace, String blockPath) {
        return renderPlanForBlock(profiles, namespace, blockPath).directionalPresenceSelectors();
    }

    private static void discoverEntityTypesAndTextures(ClassNode renderer, List<MethodNode> roots,
                                                       Surface surface) {
        Map<String, String> textureFields = discoverStaticTextureFields(renderer);
        for (MethodNode method : renderer.methods) {
            boolean textureMethod = false;
            try {
                org.objectweb.asm.Type ret = org.objectweb.asm.Type.getReturnType(method.desc);
                textureMethod = ret.getSort() == org.objectweb.asm.Type.OBJECT
                        && "net/minecraft/resources/ResourceLocation".equals(ret.getInternalName());
            } catch (Exception ignored) {}
            boolean render = roots.contains(method) || renderMethod(method);
            if (!textureMethod && !render) continue;
            try {
                for (org.objectweb.asm.Type arg : org.objectweb.asm.Type.getArgumentTypes(method.desc)) {
                    if (arg.getSort() != org.objectweb.asm.Type.OBJECT) continue;
                    String type = arg.getInternalName();
                    if (type == null || type.startsWith("java/") || type.startsWith("com/mojang/")
                            || type.startsWith("net/minecraft/client/")
                            || type.equals("net/minecraft/world/entity/Entity")) continue;
                    if (type.startsWith("net/minecraft/world/") && !type.contains("entity")) continue;
                    surface.entityTypes.add(type);
                    break;
                }
            } catch (Exception ignored) {}
            collectTextureEvidence(method, surface.textureRefs);
            if (textureMethod && surface.entityTextureSelector == null) {
                surface.entityTextureSelector = booleanTextureSelector(method, textureFields);
            }
        }
    }

    /** Maps static ResourceLocation fields initialized from literal texture paths. Keeping the
     * field identity lets a later getTextureLocation branch preserve which literal belongs to
     * which state instead of reducing the renderer to an unordered set of textures. */
    private static Map<String, String> discoverStaticTextureFields(ClassNode renderer) {
        if (renderer == null || renderer.methods == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (MethodNode method : renderer.methods) {
            if (!"<clinit>".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field)
                        || instruction.getOpcode() != Opcodes.PUTSTATIC
                        || !looksLikeResourceLocationDescriptor(field.desc)) continue;
                String path = null;
                String explicitNamespace = null;
                int code = 0;
                for (AbstractInsnNode previous = instruction.getPrevious(); previous != null && code < 18;
                     previous = previous.getPrevious()) {
                    if (previous.getOpcode() < 0) continue;
                    code++;
                    if (previous instanceof FieldInsnNode other && other.getOpcode() == Opcodes.PUTSTATIC) break;
                    if (!(previous instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String value)) continue;
                    String normalized = value.replace('\\', '/');
                    if (path == null && normalized.toLowerCase(Locale.ROOT).contains("textures/")
                            && normalized.toLowerCase(Locale.ROOT).endsWith(".png")) {
                        path = normalized.substring(normalized.toLowerCase(Locale.ROOT).indexOf("textures/"));
                    } else if (path != null && explicitNamespace == null
                            && normalized.matches("[a-z0-9_.-]+")) {
                        explicitNamespace = normalized;
                        break;
                    }
                }
                if (path != null) {
                    out.put(field.owner + '.' + field.name,
                            explicitNamespace == null ? path : explicitNamespace + ':' + path);
                }
            }
        }
        return out;
    }

    /** Proves {@code entity.booleanAccessor() ? TEX_A : TEX_B} directly from the texture method's
     * control flow. Anything more complex deliberately returns null rather than assigning the wrong
     * skin to a live entity. */
    private static EntityTextureSelector booleanTextureSelector(MethodNode method,
                                                                Map<String, String> textureFields) {
        if (method == null || method.instructions == null || textureFields == null
                || textureFields.isEmpty()) return null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode selector)
                    || !"()Z".equals(selector.desc)
                    || selector.getOpcode() == Opcodes.INVOKESTATIC) continue;
            AbstractInsnNode next = nextCode(instruction);
            if (!(next instanceof JumpInsnNode jump)
                    || (jump.getOpcode() != Opcodes.IFEQ && jump.getOpcode() != Opcodes.IFNE)) continue;
            String fallthrough = textureFieldBetween(nextCode(jump), jump.label, textureFields);
            String target = textureFieldUntilReturn(jump.label, textureFields);
            if (fallthrough == null || target == null || fallthrough.equals(target)) continue;

            String trueTexture = jump.getOpcode() == Opcodes.IFNE ? target : fallthrough;
            String falseTexture = jump.getOpcode() == Opcodes.IFNE ? fallthrough : target;
            EntityTextureSelector out = new EntityTextureSelector();
            out.selectorKind = "boolean";
            out.selector = selector.name;
            out.defaultTexture = falseTexture;
            out.variants.put(0, trueTexture);
            return out;
        }
        return null;
    }

    private static String textureFieldBetween(AbstractInsnNode start, AbstractInsnNode endExclusive,
                                              Map<String, String> textureFields) {
        for (AbstractInsnNode instruction = start; instruction != null && instruction != endExclusive;
             instruction = instruction.getNext()) {
            String texture = staticTextureField(instruction, textureFields);
            if (texture != null) return texture;
        }
        return null;
    }

    private static String textureFieldUntilReturn(AbstractInsnNode start,
                                                  Map<String, String> textureFields) {
        int code = 0;
        for (AbstractInsnNode instruction = start; instruction != null && code < 24;
             instruction = instruction.getNext()) {
            if (instruction.getOpcode() >= 0) code++;
            String texture = staticTextureField(instruction, textureFields);
            if (texture != null) return texture;
            if (instruction.getOpcode() == Opcodes.ARETURN) break;
        }
        return null;
    }

    private static String staticTextureField(AbstractInsnNode instruction,
                                             Map<String, String> textureFields) {
        if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC) return null;
        return textureFields.get(field.owner + '.' + field.name);
    }

    /**
     * Recovers the common vanilla-style camera-facing quad contract without loading client code.
     *
     * <p>The proof intentionally requires four static calls to the same renderer-local vertex
     * helper plus a camera quaternion applied before those calls. That rejects entity renderers
     * which merely query camera state for some unrelated effect. A uniform PoseStack scale is
     * folded into the returned authored span; unknown/non-uniform scales fail closed to the
     * unscaled unit quad rather than guessing an axis.</p>
     */
    private static void discoverCameraFacingEntityQuad(ClassNode renderer, List<MethodNode> roots,
                                                       Surface surface) {
        if (renderer == null || roots == null || surface == null || !surface.entityRenderer) return;
        float best = 0f;
        for (MethodNode root : roots) {
            if (!renderMethod(root)) continue;
            float scale = cameraFacingQuadScale(renderer, root);
            if (scale > 0f && Float.isFinite(scale)) best = Math.max(best, scale);
        }
        if (best > 0f) {
            surface.cameraFacingQuad = true;
            surface.cameraFacingQuadScale = best;
        }
    }

    /** Detects entity renderers that delegate their visible body to BlockRenderDispatcher for each
     * member of a live BlockState collection. This is deliberately structural: the entity/mod name
     * is irrelevant, and the contract is emitted only when the same render root contains both the
     * collection accessor and an actual single-block render call. */
    private static void discoverEntityBlockSequence(ClassNode renderer, List<MethodNode> roots,
                                                    Surface surface) {
        if (renderer == null || roots == null || surface == null || !surface.entityRenderer) return;
        for (MethodNode root : roots) {
            if (!renderMethod(root) || !rendersSingleBlock(root)) continue;
            EntityBlockSequence sequence = new EntityBlockSequence();
            sequence.blocksAccessor = blockCollectionAccessor(root, surface.entityTypes);
            sequence.blockAccessor = sequence.blocksAccessor == null
                    ? blockStateAccessor(root, surface.entityTypes) : null;
            for (AbstractInsnNode instruction : root.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESTATIC
                        || !surface.entityTypes.contains(call.owner)) continue;
                Type result;
                try {
                    if (Type.getArgumentTypes(call.desc).length != 0) continue;
                    result = Type.getReturnType(call.desc);
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (result.getSort() != Type.OBJECT) continue;
                String returned = result.getInternalName();
                if (sequence.orientationAccessor == null
                        && "net/minecraft/world/phys/Vec3".equals(returned)) {
                    sequence.orientationAccessor = call.name;
                } else if (sequence.directionAccessor == null
                        && "net/minecraft/core/Direction".equals(returned)) {
                    sequence.directionAccessor = call.name;
                }
            }
            if (sequence.blocksAccessor == null && sequence.blockAccessor == null) continue;
            int axis = translatedLoopAxis(root);
            if (axis >= 0) {
                sequence.axis = axis;
                sequence.step = translatedLoopStep(root, axis);
            }
            surface.entityBlockSequence = sequence;
            return;
        }
    }

    /**
     * Detects two common renderer programs which deliberately have no static model asset:
     *
     * <ul>
     *   <li>a parent render loop which translates once per live collection member and dispatches
     *       an overridden camera-facing quad helper, and</li>
     *   <li>a boolean-selected branch which emits a vertex-authored box/trail while the opposite
     *       branch is a camera-facing quad.</li>
     * </ul>
     *
     * <p>The proof is structural and namespace-agnostic. Every runtime input retained in the IR is
     * a zero-argument method on the authoritative entity/member object, so replay never loads the
     * client renderer class.</p>
     */
    private static void discoverEntityProceduralGeometry(ClassNode renderer, List<MethodNode> roots,
                                                         Map<String, ClassNode> nodes,
                                                         Surface surface) {
        if (renderer == null || roots == null || nodes == null || surface == null
                || !surface.entityRenderer) return;

        EntityProceduralGeometry collection = proceduralBillboardCollection(renderer, nodes, surface);
        if (collection != null) {
            surface.entityProceduralGeometry = collection;
            return;
        }

        EntityProceduralGeometry box = proceduralConditionalBox(renderer, roots, nodes, surface);
        if (box != null) surface.entityProceduralGeometry = box;
    }

    private static EntityProceduralGeometry proceduralBillboardCollection(
            ClassNode renderer, Map<String, ClassNode> nodes, Surface surface) {
        if (!surface.cameraFacingQuad) return null;
        for (ClassNode current = renderer; current != null; current = nodes.get(current.superName)) {
            for (MethodNode method : current.methods) {
                if (!renderMethod(method) || method.instructions == null) continue;
                MethodInsnNode collection = entityCollectionAccessor(method, nodes, surface.entityTypes);
                if (collection == null || !hasPoseTranslation(method)) continue;

                List<MethodInsnNode> memberVectors = memberVectorAccessors(method, collection);
                if (memberVectors.isEmpty()) continue;
                MethodInsnNode dispatch = cameraQuadDispatch(renderer, method, nodes);
                if (dispatch == null) continue;

                MethodNode implementation = dispatchMethod(renderer, dispatch, nodes);
                float scale = implementation == null ? 0f : cameraFacingQuadScale(renderer, implementation);
                if (!(scale > 0f) || !Float.isFinite(scale)) continue;

                EntityProceduralGeometry out = new EntityProceduralGeometry();
                out.kind = EntityProceduralKind.BILLBOARD_COLLECTION;
                out.collectionAccessor = collection.name;
                out.memberPositionAccessor = memberVectors.get(0).name;
                if (memberVectors.size() > 1) out.memberVelocityAccessor = memberVectors.get(1).name;
                out.billboardScale = scale;
                Map<String, String> renderTypeTextures = discoverStaticRenderTypeTextures(renderer);
                AbstractInsnNode quadCall = firstCameraQuadCall(renderer, implementation);
                if (quadCall != null) {
                    out.billboardTexture = renderTypeTextureBefore(implementation, quadCall,
                            renderTypeTextures);
                }
                return out;
            }
        }
        return null;
    }

    private static MethodInsnNode entityCollectionAccessor(MethodNode method,
                                                           Map<String, ClassNode> nodes,
                                                           Set<String> entityTypes) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESTATIC) continue;
            Type[] arguments;
            Type result;
            try {
                arguments = Type.getArgumentTypes(call.desc);
                result = Type.getReturnType(call.desc);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (arguments.length != 0 || result.getSort() != Type.OBJECT) continue;
            String returned = result.getInternalName();
            if (!"java/util/List".equals(returned) && !"java/util/Collection".equals(returned)) continue;
            String receiver = objectPathBefore(method, call, nodes, entityTypes, 0);
            if (receiver != null && receiver.isBlank()) return call;
        }
        return null;
    }

    private static boolean hasPoseTranslation(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !"com/mojang/blaze3d/vertex/PoseStack".equals(call.owner)
                    || !"translate".equals(call.name)) continue;
            if ("(DDD)V".equals(call.desc) || "(FFF)V".equals(call.desc)) return true;
        }
        return false;
    }

    /** Returns the member-local position/velocity accessors in first-use order. */
    private static List<MethodInsnNode> memberVectorAccessors(MethodNode method,
                                                             MethodInsnNode collectionAccessor) {
        LinkedHashMap<String, MethodInsnNode> found = new LinkedHashMap<>();
        int collectionIndex = method.instructions.indexOf(collectionAccessor);
        for (int i = Math.max(0, collectionIndex + 1); i < method.instructions.size(); i++) {
            AbstractInsnNode instruction = method.instructions.get(i);
            if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESTATIC) continue;
            Type[] arguments;
            Type result;
            try {
                arguments = Type.getArgumentTypes(call.desc);
                result = Type.getReturnType(call.desc);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (arguments.length != 0) continue;
            boolean vector = result.getSort() == Type.ARRAY
                    && (result.getElementType().getSort() == Type.DOUBLE
                    || result.getElementType().getSort() == Type.FLOAT)
                    || result.getSort() == Type.OBJECT
                    && "net/minecraft/world/phys/Vec3".equals(result.getInternalName());
            if (!vector) continue;
            found.putIfAbsent(call.owner + '#' + call.name + call.desc, call);
            if (found.size() >= 2) break;
        }
        return List.copyOf(found.values());
    }

    /** Finds the virtual renderer-helper dispatch whose concrete implementation is the proven quad. */
    private static MethodInsnNode cameraQuadDispatch(ClassNode concrete, MethodNode parentRender,
                                                     Map<String, ClassNode> nodes) {
        for (AbstractInsnNode instruction : parentRender.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESTATIC) continue;
            MethodNode implementation = dispatchMethod(concrete, call, nodes);
            if (implementation == null || implementation == parentRender) continue;
            if (cameraFacingQuadScale(concrete, implementation) > 0f) return call;
        }
        return null;
    }

    private record ScalarSelector(String selector, int ordinal, float selected, float fallback) {}

    private static EntityProceduralGeometry proceduralConditionalBox(
            ClassNode renderer, List<MethodNode> roots, Map<String, ClassNode> nodes, Surface surface) {
        if (!surface.cameraFacingQuad) return null;
        Map<String, String> renderTypeTextures = discoverStaticRenderTypeTextures(renderer);
        for (MethodNode root : roots) {
            if (!renderMethod(root) || root.instructions == null) continue;
            List<MethodInsnNode> boxCalls = proceduralBoxCalls(renderer, root);
            MethodInsnNode boxCall = boxCalls.isEmpty() ? null : boxCalls.get(0);
            if (boxCall == null) continue;
            BooleanPredicate active = guardingPredicate(renderer, root, boxCall, nodes,
                    surface.entityTypes, 0);
            String activeSelector = directBooleanSelector(active);
            if (active == null || activeSelector == null) continue;

            ScalarSelector extent = scalarSelector(root, nodes, surface.entityTypes,
                    root.instructions.indexOf(boxCall), 0f, .25f);
            if (extent == null || !(extent.fallback > 0f) || !(extent.selected > 0f)) continue;

            EntityProceduralGeometry out = new EntityProceduralGeometry();
            out.kind = EntityProceduralKind.CONDITIONAL_BOX_TRAIL;
            out.activePredicate = active;
            out.activeSelector = activeSelector;
            out.orientationAccessor = entityVectorAccessorBefore(root, boxCall, nodes, surface.entityTypes);
            out.halfExtent = extent.fallback;
            out.extentSelector = extent.selector;
            out.extentSelectorOrdinal = extent.ordinal;
            out.selectedHalfExtent = extent.selected;
            int[] rgb = integerArguments(root, boxCall, 3, 3);
            if (rgb != null) {
                out.red = clampColor(rgb[0]);
                out.green = clampColor(rgb[1]);
                out.blue = clampColor(rgb[2]);
            }
            out.boxTexture = renderTypeTextureBefore(root, boxCall, renderTypeTextures);
            MethodInsnNode quadCall = firstCameraQuadCall(renderer, root);
            if (quadCall != null) {
                out.billboardTexture = renderTypeTextureBefore(root, quadCall, renderTypeTextures);
            }
            ScalarSelector billboard = scalarSelector(root, nodes, surface.entityTypes,
                    quadCall == null ? root.instructions.size() : root.instructions.indexOf(quadCall),
                    .25f, 2.0f);
            if (billboard != null && billboard.selector.equals(extent.selector)
                    && billboard.ordinal == extent.ordinal) {
                out.billboardScaleSelector = billboard.selector;
                out.billboardScaleSelectorOrdinal = billboard.ordinal;
                out.selectedBillboardScale = billboard.selected;
                out.defaultBillboardScale = billboard.fallback;
            } else {
                out.defaultBillboardScale = surface.cameraFacingQuadScale;
            }
            out.billboardScale = out.defaultBillboardScale > 0f
                    ? out.defaultBillboardScale : surface.cameraFacingQuadScale;
            return out;
        }
        return null;
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /** Renderer-local helpers which emit at least two quads worth of direct vertex-helper calls. */
    private static List<MethodInsnNode> proceduralBoxCalls(ClassNode renderer, MethodNode root) {
        ArrayList<MethodInsnNode> out = new ArrayList<>();
        for (AbstractInsnNode instruction : root.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !renderer.name.equals(call.owner)) continue;
            MethodNode helper = findMethod(renderer, call.name, call.desc);
            if (helper == null) continue;
            int vertices = 0;
            for (AbstractInsnNode nestedInstruction : helper.instructions) {
                if (!(nestedInstruction instanceof MethodInsnNode nested)
                        || nested.getOpcode() != Opcodes.INVOKESTATIC
                        || !renderer.name.equals(nested.owner)) continue;
                MethodNode vertex = findMethod(renderer, nested.name, nested.desc);
                if (vertex != null && vertexQuadHelper(vertex)) vertices++;
            }
            if (vertices >= 8) out.add(call);
        }
        return List.copyOf(out);
    }

    private static String directBooleanSelector(BooleanPredicate predicate) {
        if (predicate == null || predicate.op != BooleanOp.SOURCE || predicate.source == null
                || predicate.source.indexOf('>') >= 0 || !predicate.source.startsWith("M#")) return null;
        String[] parts = predicate.source.split("#", 4);
        return parts.length == 4 && "()Z".equals(parts[3]) ? parts[2] : null;
    }

    /**
     * Finds a javac enum/index ternary selecting between two literal scalar values before target.
     * Range bounds keep unrelated timing/angle constants from being mistaken for authored size.
     */
    private static ScalarSelector scalarSelector(MethodNode method, Map<String, ClassNode> nodes,
                                                 Set<String> entityTypes, int targetIndex,
                                                 float minimum, float maximum) {
        ScalarSelector best = null;
        int bestIndex = -1;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof JumpInsnNode jump)
                    || jump.getOpcode() != Opcodes.IF_ACMPEQ && jump.getOpcode() != Opcodes.IF_ACMPNE) continue;
            int branchIndex = method.instructions.indexOf(jump);
            if (branchIndex < 0 || branchIndex >= targetIndex || branchIndex < bestIndex) continue;
            AbstractInsnNode expectedInsn = previousReal(jump);
            AbstractInsnNode selectorInsn = previousReal(expectedInsn);
            if (!(expectedInsn instanceof FieldInsnNode expected)
                    || expected.getOpcode() != Opcodes.GETSTATIC
                    || !(selectorInsn instanceof MethodInsnNode selector)
                    || selector.getOpcode() == Opcodes.INVOKESTATIC
                    || Type.getArgumentTypes(selector.desc).length != 0) continue;
            String receiver = objectPathBefore(method, selector, nodes, entityTypes, 0);
            if (receiver == null || !receiver.isBlank()) continue;

            Float fall = floatConstant(nextCode(jump));
            Float taken = floatConstant(nextCode(jump.label));
            if (fall == null || taken == null || !Float.isFinite(fall) || !Float.isFinite(taken)
                    || fall <= minimum || taken <= minimum || fall > maximum || taken > maximum
                    || Math.abs(fall - taken) < 1.0e-6f) continue;
            int ordinal = enumOrdinal(nodes.get(expected.owner), expected.name);
            if (ordinal < 0) continue;
            float selected = jump.getOpcode() == Opcodes.IF_ACMPNE ? fall : taken;
            float fallback = jump.getOpcode() == Opcodes.IF_ACMPNE ? taken : fall;
            best = new ScalarSelector(selector.name, ordinal, selected, fallback);
            bestIndex = branchIndex;
        }
        return best;
    }

    private static int enumOrdinal(ClassNode owner, String fieldName) {
        if (owner == null || owner.fields == null || fieldName == null) return -1;
        int ordinal = 0;
        for (var field : owner.fields) {
            if ((field.access & Opcodes.ACC_ENUM) == 0 || (field.access & Opcodes.ACC_STATIC) == 0) continue;
            if (field.name.equals(fieldName)) return ordinal;
            ordinal++;
        }
        return -1;
    }

    private static String entityVectorAccessorBefore(MethodNode method, AbstractInsnNode target,
                                                     Map<String, ClassNode> nodes,
                                                     Set<String> entityTypes) {
        int targetIndex = method.instructions.indexOf(target);
        String found = null;
        for (int i = 0; i >= 0 && i < targetIndex; i++) {
            AbstractInsnNode instruction = method.instructions.get(i);
            if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESTATIC) continue;
            Type result;
            try {
                if (Type.getArgumentTypes(call.desc).length != 0) continue;
                result = Type.getReturnType(call.desc);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (result.getSort() != Type.OBJECT
                    || !"net/minecraft/world/phys/Vec3".equals(result.getInternalName())) continue;
            String receiver = objectPathBefore(method, call, nodes, entityTypes, 0);
            if (receiver != null && receiver.isBlank()) found = call.name;
        }
        return found;
    }

    private static int[] integerArguments(MethodNode method, MethodInsnNode call, int first, int count) {
        if (method == null || call == null || count <= 0) return null;
        try {
            Frame<SourceValue>[] frames = new Analyzer<>(new SourceInterpreter()).analyze("renderer", method);
            int index = method.instructions.indexOf(call);
            if (index < 0 || index >= frames.length || frames[index] == null) return null;
            Type[] args = Type.getArgumentTypes(call.desc);
            if (first < 0 || first + count > args.length) return null;
            Frame<SourceValue> frame = frames[index];
            int base = frame.getStackSize() - args.length;
            if (base < 0) return null;
            int[] out = new int[count];
            for (int i = 0; i < count; i++) {
                SourceValue value = frame.getStack(base + first + i);
                if (value == null || value.insns == null || value.insns.size() != 1) return null;
                Integer constant = integerConstant(value.insns.iterator().next());
                if (constant == null) return null;
                out[i] = constant;
            }
            return out;
        } catch (AnalyzerException | RuntimeException ignored) {
            return null;
        }
    }

    /** ResourceLocation field -> RenderType field propagation in the renderer class initializer. */
    private static Map<String, String> discoverStaticRenderTypeTextures(ClassNode renderer) {
        Map<String, String> textureFields = discoverStaticTextureFields(renderer);
        if (textureFields.isEmpty()) return Map.of();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (MethodNode method : renderer.methods) {
            if (!"<clinit>".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode target) || target.getOpcode() != Opcodes.PUTSTATIC
                        || target.desc == null || !target.desc.contains("RenderType")) continue;
                int remaining = 16;
                for (AbstractInsnNode cursor = previousReal(target); cursor != null && remaining-- > 0;
                     cursor = previousReal(cursor)) {
                    if (cursor instanceof FieldInsnNode source && source.getOpcode() == Opcodes.GETSTATIC) {
                        String texture = textureFields.get(source.owner + '.' + source.name);
                        if (texture != null) {
                            out.put(target.owner + '.' + target.name, texture);
                            break;
                        }
                    }
                    if (cursor instanceof FieldInsnNode other && other.getOpcode() == Opcodes.PUTSTATIC) break;
                }
            }
        }
        return out;
    }

    private static String renderTypeTextureBefore(MethodNode method, AbstractInsnNode target,
                                                  Map<String, String> renderTypeTextures) {
        if (method == null || target == null || renderTypeTextures == null || renderTypeTextures.isEmpty()) {
            return null;
        }
        int remaining = 96;
        for (AbstractInsnNode cursor = previousReal(target); cursor != null && remaining-- > 0;
             cursor = previousReal(cursor)) {
            if (!(cursor instanceof MethodInsnNode call) || !"getBuffer".equals(call.name)) continue;
            int fields = 10;
            for (AbstractInsnNode before = previousReal(call); before != null && fields-- > 0;
                 before = previousReal(before)) {
                if (!(before instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC) continue;
                String texture = renderTypeTextures.get(field.owner + '.' + field.name);
                if (texture != null) return texture;
            }
        }
        return null;
    }

    private static MethodInsnNode firstCameraQuadCall(ClassNode renderer, MethodNode method) {
        if (renderer == null || method == null || method.instructions == null) return null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !renderer.name.equals(call.owner)) continue;
            MethodNode helper = findMethod(renderer, call.name, call.desc);
            if (helper != null && vertexQuadHelper(helper)) return call;
        }
        return null;
    }

    /** Proves that the entity collection accessor is the collection whose members become the
     * BlockState consumed by BlockRenderDispatcher. Merely seeing an unrelated List in the same
     * renderer is not enough: vehicle renderers frequently inspect passengers, inventories or
     * effects while also drawing one static block. */
    private static String blockCollectionAccessor(MethodNode root, Set<String> entityTypes) {
        if (root == null || root.instructions == null || entityTypes == null || entityTypes.isEmpty()) return null;
        for (AbstractInsnNode instruction : root.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESTATIC
                    || !entityTypes.contains(call.owner)) continue;
            Type result;
            try {
                if (Type.getArgumentTypes(call.desc).length != 0) continue;
                result = Type.getReturnType(call.desc);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (result.getSort() != Type.OBJECT
                    || !("java/util/List".equals(result.getInternalName())
                    || "java/util/Collection".equals(result.getInternalName()))) continue;
            AbstractInsnNode store = nextCode(instruction);
            if (!(store instanceof org.objectweb.asm.tree.VarInsnNode stored)
                    || store.getOpcode() != Opcodes.ASTORE) continue;
            int local = stored.var;
            int code = 0;
            for (AbstractInsnNode cursor = store.getNext(); cursor != null && code < 160;
                 cursor = cursor.getNext()) {
                if (cursor.getOpcode() >= 0) code++;
                if (!(cursor instanceof org.objectweb.asm.tree.VarInsnNode load)
                        || load.getOpcode() != Opcodes.ALOAD || load.var != local) continue;
                for (AbstractInsnNode look = nextCode(cursor), end = look; look != null && code < 168;
                     look = nextCode(look)) {
                    if (look instanceof MethodInsnNode get
                            && ("get".equals(get.name) || "next".equals(get.name))) {
                        AbstractInsnNode cast = nextCode(look);
                        if (cast instanceof org.objectweb.asm.tree.TypeInsnNode type
                                && cast.getOpcode() == Opcodes.CHECKCAST
                                && "net/minecraft/world/level/block/state/BlockState".equals(type.desc)) {
                            return call.name;
                        }
                    }
                    if (look.getOpcode() == Opcodes.GOTO || look.getOpcode() == Opcodes.RETURN) break;
                    if (look == end && look != null) end = look.getNext();
                    if (look instanceof org.objectweb.asm.tree.VarInsnNode another
                            && another.getOpcode() == Opcodes.ALOAD && another.var != local) break;
                }
            }
        }
        return null;
    }

    /** Finds a zero-argument entity BlockState accessor whose value is the state passed to the
     * renderer's BlockRenderDispatcher call. This covers one-state projectile bodies without
     * weakening the collection proof used by assembled rockets. */
    private static String blockStateAccessor(MethodNode root, Set<String> entityTypes) {
        if (root == null || root.instructions == null || entityTypes == null || entityTypes.isEmpty()) return null;
        for (AbstractInsnNode instruction : root.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESTATIC
                    || !entityTypes.contains(call.owner)) continue;
            Type result;
            try {
                if (Type.getArgumentTypes(call.desc).length != 0) continue;
                result = Type.getReturnType(call.desc);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (result.getSort() != Type.OBJECT
                    || !"net/minecraft/world/level/block/state/BlockState".equals(result.getInternalName())) continue;

            AbstractInsnNode next = nextCode(instruction);
            if (next instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE) {
                int local = store.var;
                boolean reloaded = false;
                int code = 0;
                for (AbstractInsnNode cursor = store.getNext(); cursor != null && code < 240;
                     cursor = cursor.getNext()) {
                    if (cursor.getOpcode() >= 0) code++;
                    if (cursor instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                            && load.var == local) reloaded = true;
                    if (cursor instanceof MethodInsnNode render && isSingleBlockRenderCall(render)) {
                        if (reloaded) return call.name;
                        break;
                    }
                    if (cursor.getOpcode() == Opcodes.RETURN) break;
                }
            } else {
                int code = 0;
                for (AbstractInsnNode cursor = next; cursor != null && code < 24; cursor = nextCode(cursor)) {
                    if (cursor.getOpcode() >= 0) code++;
                    if (cursor instanceof MethodInsnNode render && isSingleBlockRenderCall(render)) return call.name;
                }
            }
        }
        return null;
    }

    private static boolean rendersSingleBlock(MethodNode root) {
        if (root == null || root.instructions == null) return false;
        for (AbstractInsnNode instruction : root.instructions) {
            if (instruction instanceof MethodInsnNode call && isSingleBlockRenderCall(call)) return true;
        }
        return false;
    }

    private static boolean isSingleBlockRenderCall(MethodInsnNode call) {
        if (call == null) return false;
        String owner = call.owner == null ? "" : call.owner;
        String descriptor = call.desc == null ? "" : call.desc;
        return owner.endsWith("/BlockRenderDispatcher")
                && descriptor.contains("Lnet/minecraft/world/level/block/state/BlockState;")
                && descriptor.contains("Lcom/mojang/blaze3d/vertex/PoseStack;");
    }

    /** Finds which argument of PoseStack.translate(float,float,float) is driven directly by an
     * integer loop index. The common renderer bytecode is const, ILOAD/I2F, const. */
    private static int translatedLoopAxis(MethodNode root) {
        if (root == null || root.instructions == null) return -1;
        for (AbstractInsnNode instruction : root.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !"com/mojang/blaze3d/vertex/PoseStack".equals(call.owner)
                    || !"translate".equals(call.name) || !"(FFF)V".equals(call.desc)) continue;
            AbstractInsnNode zEnd = previousCode(instruction);
            int z = loopIndexSign(zEnd);
            if (z != 0) return 2;
            AbstractInsnNode yEnd = previousCode(expressionStart(zEnd));
            int y = loopIndexSign(yEnd);
            if (y != 0) return 1;
            AbstractInsnNode xEnd = previousCode(expressionStart(yEnd));
            int x = loopIndexSign(xEnd);
            if (x != 0) return 0;
        }
        return -1;
    }

    private static float translatedLoopStep(MethodNode root, int axis) {
        if (root == null || root.instructions == null || axis < 0 || axis > 2) return 1f;
        for (AbstractInsnNode instruction : root.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !"com/mojang/blaze3d/vertex/PoseStack".equals(call.owner)
                    || !"translate".equals(call.name) || !"(FFF)V".equals(call.desc)) continue;
            AbstractInsnNode zEnd = previousCode(instruction);
            AbstractInsnNode yEnd = previousCode(expressionStart(zEnd));
            AbstractInsnNode xEnd = previousCode(expressionStart(yEnd));
            int sign = loopIndexSign(axis == 0 ? xEnd : axis == 1 ? yEnd : zEnd);
            if (sign != 0) return sign;
        }
        return 1f;
    }

    private static int loopIndexSign(AbstractInsnNode end) {
        if (end == null) return 0;
        boolean negative = end.getOpcode() == Opcodes.FNEG || end.getOpcode() == Opcodes.DNEG;
        AbstractInsnNode conversion = negative ? previousCode(end) : end;
        if (conversion == null || conversion.getOpcode() != Opcodes.I2F && conversion.getOpcode() != Opcodes.I2D) {
            return 0;
        }
        AbstractInsnNode load = previousCode(conversion);
        return load instanceof org.objectweb.asm.tree.VarInsnNode
                && load.getOpcode() == Opcodes.ILOAD ? (negative ? -1 : 1) : 0;
    }

    private static AbstractInsnNode expressionStart(AbstractInsnNode end) {
        if (end == null) return null;
        AbstractInsnNode cursor = end;
        if (cursor.getOpcode() == Opcodes.FNEG || cursor.getOpcode() == Opcodes.DNEG) cursor = previousCode(cursor);
        if (cursor != null && (cursor.getOpcode() == Opcodes.I2F || cursor.getOpcode() == Opcodes.I2D)) {
            AbstractInsnNode load = previousCode(cursor);
            if (load != null) return load;
        }
        return cursor;
    }

    private static float cameraFacingQuadScale(ClassNode renderer, MethodNode root) {
        Map<String, Integer> helperCalls = new LinkedHashMap<>();
        for (AbstractInsnNode instruction : root.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !renderer.name.equals(call.owner)) continue;
            MethodNode helper = findMethod(renderer, call.name, call.desc);
            if (helper == null || !vertexQuadHelper(helper)) continue;
            helperCalls.merge(call.name + call.desc, 1, Integer::sum);
        }
        String helperKey = helperCalls.entrySet().stream()
                .filter(entry -> entry.getValue() >= 4)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);
        if (helperKey == null) return 0f;

        AbstractInsnNode firstQuad = null;
        for (AbstractInsnNode instruction : root.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && renderer.name.equals(call.owner)
                    && helperKey.equals(call.name + call.desc)) {
                firstQuad = instruction;
                break;
            }
        }
        if (firstQuad == null) return 0f;

        boolean camera = false;
        float uniformScale = 1f;
        for (AbstractInsnNode instruction = root.instructions.getFirst(); instruction != null
                && instruction != firstQuad; instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (cameraOrientationCall(call) && cameraQuaternionConsumed(call, firstQuad)) {
                camera = true;
            }
            if (!"com/mojang/blaze3d/vertex/PoseStack".equals(call.owner)
                    || !"(FFF)V".equals(call.desc)) continue;
            Double z = numericConstant(previousCode(instruction));
            Double y = numericConstant(previousCode(previousCode(instruction)));
            Double x = numericConstant(previousCode(previousCode(previousCode(instruction))));
            if (x == null || y == null || z == null
                    || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) continue;
            if (Math.abs(x - y) > 1.0e-5d || Math.abs(x - z) > 1.0e-5d || Math.abs(x) < 1.0e-5d) {
                continue;
            }
            uniformScale *= (float) Math.abs(x);
        }
        return camera && uniformScale > 0f && Float.isFinite(uniformScale) ? uniformScale : 0f;
    }

    private static boolean vertexQuadHelper(MethodNode helper) {
        if (helper == null || helper.instructions == null || !helper.desc.endsWith(")V")) return false;
        for (AbstractInsnNode instruction : helper.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (!"com/mojang/blaze3d/vertex/VertexConsumer".equals(call.owner)) continue;
            Type[] args;
            try {
                args = Type.getArgumentTypes(call.desc);
            } catch (RuntimeException ignored) {
                continue;
            }
            int trailingFloats = 0;
            for (int i = args.length - 1; i >= 0 && args[i].getSort() == Type.FLOAT; i--) {
                trailingFloats++;
            }
            if (trailingFloats >= 3) return true;
        }
        return false;
    }

    private static boolean cameraOrientationCall(MethodInsnNode call) {
        return call != null
                && "net/minecraft/client/renderer/entity/EntityRenderDispatcher".equals(call.owner)
                && "()Lorg/joml/Quaternionf;".equals(call.desc);
    }

    private static boolean cameraQuaternionConsumed(MethodInsnNode camera, AbstractInsnNode limit) {
        int steps = 0;
        for (AbstractInsnNode next = camera.getNext(); next != null && next != limit && steps++ < 8;
             next = next.getNext()) {
            if (!(next instanceof MethodInsnNode call)) continue;
            return "com/mojang/blaze3d/vertex/PoseStack".equals(call.owner)
                    && "(Lorg/joml/Quaternionf;)V".equals(call.desc);
        }
        return false;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction == null ? null : instruction.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    private static void collectTextureEvidence(MethodNode method, Set<String> out) {
        if (method == null || method.instructions == null) return;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                addTextureLiteral(out, value);
            } else if (instruction instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
                for (Object argument : indy.bsmArgs) {
                    if (argument instanceof String value) addTextureLiteral(out, value);
                }
            }
        }
    }

    private static void addTextureLiteral(Set<String> out, String value) {
        if (value == null || value.isBlank() || value.length() > 512) return;
        String normalized = value.replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);
        int textures = lower.indexOf("textures/");
        if (textures < 0 || !lower.endsWith(".png")) return;
        // Strip incidental prefix text from concat recipes while retaining dynamic \u0001 markers.
        normalized = normalized.substring(textures);
        out.add(normalized);
    }

    /**
     * Finds model repositories used directly by an entity render root and records any exact,
     * constant zero-argument String metadata they expose. The returned model type must inherit
     * vanilla EntityModel, which keeps unrelated renderer helpers out. Pack generation later
     * intersects these tokens with actual Bedrock family path segments before trusting them.
     */
    private static void discoverEntityAssetFamilyHints(List<MethodNode> roots,
                                                       Map<String, ClassNode> nodes,
                                                       Surface surface) {
        if (surface == null || !surface.entityRenderer || roots == null || nodes == null) return;
        for (MethodNode root : roots) {
            if (root == null || root.instructions == null) continue;
            for (AbstractInsnNode instruction : root.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                Type returned;
                try {
                    returned = Type.getReturnType(call.desc);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (returned.getSort() != Type.OBJECT
                        || !inheritsEntityModel(returned.getInternalName(), nodes)) continue;
                ClassNode repository = nodes.get(call.owner);
                if (repository == null || repository.methods == null) continue;
                for (MethodNode method : repository.methods) {
                    if (!"()Ljava/lang/String;".equals(method.desc)) continue;
                    String value = constantStringReturn(repository, method, nodes);
                    if (assetFamilyToken(value)) surface.entityAssetFamilyHints.add(value);
                }
            }
        }
    }

    /**
     * Recovers a short entity-rooted identity chain only when its result is subsequently consumed
     * by a repository lookup that returns an EntityModel. This turns renderer expressions such as
     * {@code entity.variant().id()} into a safe server-side appearance selector without teaching
     * runtime code either accessor name.
     */
    private static void discoverEntityAppearanceAccessorSources(List<MethodNode> roots,
                                                                Map<String, ClassNode> nodes,
                                                                Surface surface) {
        if (surface == null || !surface.entityRenderer || roots == null || nodes == null
                || surface.entityTypes.isEmpty()) return;
        for (MethodNode root : roots) {
            if (root == null || root.instructions == null) continue;
            for (AbstractInsnNode instruction : root.instructions) {
                if (!(instruction instanceof MethodInsnNode first)
                        || !surface.entityTypes.contains(first.owner)
                        || Type.getArgumentTypes(first.desc).length != 0) continue;
                Type result;
                try { result = Type.getReturnType(first.desc); }
                catch (IllegalArgumentException ignored) { continue; }
                if (result.getSort() != Type.OBJECT) continue;

                ArrayList<String> path = new ArrayList<>();
                path.add("M#" + first.owner + '#' + first.name + '#' + first.desc);
                MethodInsnNode terminal = first;
                Type current = result;
                AbstractInsnNode cursor = nextReal(first);
                for (int depth = 0; depth < 3 && cursor != null; depth++) {
                    while (cursor instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) {
                        cursor = nextReal(cursor);
                    }
                    if (!(cursor instanceof MethodInsnNode next) || next.getOpcode() == Opcodes.INVOKESTATIC
                            || Type.getArgumentTypes(next.desc).length != 0
                            || current.getSort() != Type.OBJECT
                            || !current.getInternalName().equals(next.owner)) break;
                    path.add("M#" + next.owner + '#' + next.name + '#' + next.desc);
                    terminal = next;
                    try { current = Type.getReturnType(next.desc); }
                    catch (IllegalArgumentException ignored) { break; }
                    cursor = nextReal(next);
                    if (identityType(current, nodes)) break;
                }
                if (!identityType(current, nodes) || !feedsEntityModelRepository(terminal, nodes)) continue;
                List<String> frozen = List.copyOf(path);
                if (surface.entityAppearanceAccessorSources.stream().noneMatch(source ->
                        source != null && source.accessors.equals(frozen))) {
                    surface.entityAppearanceAccessorSources.add(new EntityAppearanceAccessorSource(frozen));
                }
            }
        }
    }

    private record EntityScaleProduct(double constant, List<List<String>> accessorPaths,
                                      boolean concrete) {}

    /**
     * Discovers the ordinary LivingEntityRenderer-style scale hook by JVM shape rather than method
     * name. Only multiplication trees made from numeric constants and exact zero-argument accessor
     * chains rooted in the rendered entity are retained. A client-only factor contributes neutral
     * scale because it is not server-authoritative; any other unknown factor rejects the recipe.
     */
    private static void discoverEntityRootScale(ClassNode renderer, Map<String, ClassNode> nodes,
                                                Surface surface) {
        if (renderer == null || nodes == null || surface == null || !surface.entityRenderer
                || surface.entityTypes.isEmpty()) return;
        EntityRootScaleSource agreed = null;
        String agreedSignature = null;
        for (MethodNode method : renderer.methods) {
            Type[] arguments;
            Type returned;
            try {
                arguments = Type.getArgumentTypes(method.desc);
                returned = Type.getReturnType(method.desc);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (returned.getSort() != Type.VOID || arguments.length != 3
                    || arguments[0].getSort() != Type.OBJECT
                    || arguments[1].getSort() != Type.OBJECT
                    || !"com/mojang/blaze3d/vertex/PoseStack".equals(arguments[1].getInternalName())
                    || arguments[2].getSort() != Type.FLOAT
                    || !rendererEntityArgument(arguments[0].getInternalName(), surface.entityTypes, nodes)) {
                continue;
            }
            int entityLocal = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
            int poseLocal = entityLocal + arguments[0].getSize();
            BytecodeValueFlow flow = new BytecodeValueFlow(nodes);
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !"com/mojang/blaze3d/vertex/PoseStack".equals(call.owner)
                        || !"(FFF)V".equals(call.desc)
                        || call.getOpcode() == Opcodes.INVOKESTATIC
                        || !Integer.valueOf(poseLocal).equals(
                        flow.receiverParameterLocal(renderer, method, call))) continue;
                EntityScaleProduct product = null;
                boolean uniform = true;
                for (int axis = 0; axis < 3; axis++) {
                    EntityScaleProduct axisProduct = entityScaleProduct(
                            renderer, method, flow.argumentSources(renderer, method, call, axis),
                            flow, entityLocal, nodes, 0);
                    if (axisProduct == null) {
                        uniform = false;
                        break;
                    }
                    if (product == null) product = axisProduct;
                    else if (!scaleProductSignature(product).equals(scaleProductSignature(axisProduct))) {
                        uniform = false;
                        break;
                    }
                }
                if (!uniform || product == null || !product.concrete()
                        || !(product.constant() > 0d) || !Double.isFinite(product.constant())) continue;
                EntityRootScaleSource source = new EntityRootScaleSource(
                        (float) product.constant(), product.accessorPaths());
                String signature = rootScaleSignature(source);
                if (agreed == null) {
                    agreed = source;
                    agreedSignature = signature;
                } else if (!agreedSignature.equals(signature)) {
                    // Two independent scale hooks disagree. Do not guess which renderer path wins.
                    surface.entityRootScaleSource = null;
                    return;
                }
            }
        }
        surface.entityRootScaleSource = agreed;
    }

    private static boolean rendererEntityArgument(String type, Collection<String> entityTypes,
                                                  Map<String, ClassNode> nodes) {
        if (type == null || entityTypes == null) return false;
        for (String target : entityTypes) {
            if (type.equals(target) || compatibleTarget(type, target, nodes)
                    || compatibleTarget(target, type, nodes)) return true;
        }
        return false;
    }

    private static EntityScaleProduct entityScaleProduct(
            ClassNode owner, MethodNode method, Set<AbstractInsnNode> producers,
            BytecodeValueFlow flow, int entityLocal, Map<String, ClassNode> nodes, int depth) {
        if (producers == null || producers.isEmpty() || depth > 12) return null;
        EntityScaleProduct agreed = null;
        String signature = null;
        for (AbstractInsnNode producer : producers) {
            EntityScaleProduct candidate = entityScaleProduct(
                    owner, method, producer, flow, entityLocal, nodes, depth + 1);
            if (candidate == null) return null;
            String candidateSignature = scaleProductSignature(candidate);
            if (agreed == null) {
                agreed = candidate;
                signature = candidateSignature;
            } else if (!signature.equals(candidateSignature)) {
                return null;
            }
        }
        return agreed;
    }

    private static EntityScaleProduct entityScaleProduct(
            ClassNode owner, MethodNode method, AbstractInsnNode producer,
            BytecodeValueFlow flow, int entityLocal, Map<String, ClassNode> nodes, int depth) {
        if (producer == null || depth > 12) return null;
        Double constant = numericConstant(producer);
        if (constant != null) {
            return Double.isFinite(constant) && constant > 0d
                    ? new EntityScaleProduct(constant, List.of(), true) : null;
        }
        int opcode = producer.getOpcode();
        if (opcode == Opcodes.FMUL || opcode == Opcodes.DMUL
                || opcode == Opcodes.IMUL || opcode == Opcodes.LMUL) {
            List<Set<AbstractInsnNode>> operands = flow.binaryOperandSources(owner, method, producer);
            if (operands.size() != 2) return null;
            EntityScaleProduct left = entityScaleProduct(
                    owner, method, operands.get(0), flow, entityLocal, nodes, depth + 1);
            EntityScaleProduct right = entityScaleProduct(
                    owner, method, operands.get(1), flow, entityLocal, nodes, depth + 1);
            return multiplyScaleProducts(left, right);
        }
        if (opcode == Opcodes.F2D || opcode == Opcodes.D2F || opcode == Opcodes.I2F
                || opcode == Opcodes.I2D || opcode == Opcodes.L2F || opcode == Opcodes.L2D) {
            return entityScaleProduct(owner, method,
                    flow.unaryOperandSources(owner, method, producer),
                    flow, entityLocal, nodes, depth + 1);
        }
        if (producer instanceof MethodInsnNode call && call.getOpcode() != Opcodes.INVOKESTATIC
                && Type.getArgumentTypes(call.desc).length == 0 && numericReturn(call.desc)) {
            List<String> path = flow.zeroArgAccessorPathFromParameter(owner, method, call, entityLocal);
            if (!path.isEmpty()) {
                if (path.stream().anyMatch(RendererBytecodeScanner::encodedClientAccessor)) {
                    // This is a real renderer factor, but the server cannot observe it. Preserve
                    // the authoritative entity-rooted component instead of executing client state.
                    return new EntityScaleProduct(1d, List.of(), false);
                }
                return new EntityScaleProduct(1d, List.of(List.copyOf(path)), true);
            }
            if (clientOwner(call.owner)) return new EntityScaleProduct(1d, List.of(), false);
        }
        return null;
    }

    private static EntityScaleProduct multiplyScaleProducts(EntityScaleProduct left,
                                                            EntityScaleProduct right) {
        if (left == null || right == null) return null;
        double constant = left.constant() * right.constant();
        if (!(constant > 0d) || !Double.isFinite(constant)) return null;
        ArrayList<List<String>> paths = new ArrayList<>(
                left.accessorPaths().size() + right.accessorPaths().size());
        paths.addAll(left.accessorPaths());
        paths.addAll(right.accessorPaths());
        return new EntityScaleProduct(constant, List.copyOf(paths),
                left.concrete() || right.concrete());
    }

    private static boolean encodedClientAccessor(String encoded) {
        if (encoded == null) return true;
        String[] parts = encoded.split("#", 4);
        return parts.length != 4 || clientOwner(parts[1]);
    }

    private static String scaleProductSignature(EntityScaleProduct product) {
        if (product == null) return "";
        ArrayList<String> paths = new ArrayList<>();
        for (List<String> path : product.accessorPaths()) paths.add(String.join(">", path));
        java.util.Collections.sort(paths);
        return Float.floatToIntBits((float) product.constant()) + "|" + String.join("|", paths);
    }

    private static String rootScaleSignature(EntityRootScaleSource source) {
        if (source == null) return "";
        ArrayList<String> paths = new ArrayList<>();
        for (List<String> path : source.accessorPaths) paths.add(String.join(">", path));
        java.util.Collections.sort(paths);
        return Float.floatToIntBits(source.constant) + "|" + String.join("|", paths);
    }

    private static boolean identityType(Type type, Map<String, ClassNode> nodes) {
        if (type == null || type.getSort() != Type.OBJECT) return false;
        String name = type.getInternalName();
        if ("java/lang/String".equals(name) || "net/minecraft/resources/ResourceLocation".equals(name)) {
            return true;
        }
        ClassNode node = nodes.get(name);
        return node != null && (node.access & Opcodes.ACC_ENUM) != 0;
    }

    private static boolean feedsEntityModelRepository(MethodInsnNode terminal,
                                                      Map<String, ClassNode> nodes) {
        AbstractInsnNode cursor = terminal;
        for (int i = 0; i < 40 && (cursor = nextReal(cursor)) != null; i++) {
            if (cursor instanceof MethodInsnNode call) {
                Type returned;
                try { returned = Type.getReturnType(call.desc); }
                catch (IllegalArgumentException ignored) { continue; }
                if (returned.getSort() == Type.OBJECT
                        && inheritsEntityModel(returned.getInternalName(), nodes)) return true;
            }
            if (cursor.getOpcode() == Opcodes.ARETURN || cursor.getOpcode() == Opcodes.RETURN) break;
        }
        return false;
    }

    private static boolean inheritsEntityModel(String type, Map<String, ClassNode> nodes) {
        String current = type;
        Set<String> seen = new HashSet<>();
        int remaining = 24;
        while (current != null && remaining-- > 0 && seen.add(current)) {
            if ("net/minecraft/client/model/EntityModel".equals(current)) return true;
            ClassNode node = nodes.get(current);
            current = node == null ? null : node.superName;
        }
        return false;
    }

    private static String constantStringReturn(ClassNode owner, MethodNode method,
                                               Map<String, ClassNode> nodes) {
        if (owner == null || method == null || method.instructions == null) return null;
        String agreed = null;
        boolean sawReturn = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() != Opcodes.ARETURN) continue;
            AbstractInsnNode value = previousReal(instruction);
            String candidate = value instanceof LdcInsnNode ldc && ldc.cst instanceof String text
                    ? text : value instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && "Ljava/lang/String;".equals(field.desc)
                    ? constantStaticString(field, nodes) : null;
            if (candidate == null || sawReturn && !candidate.equals(agreed)) return null;
            agreed = candidate;
            sawReturn = true;
        }
        return sawReturn ? agreed : null;
    }

    private static String constantStaticString(FieldInsnNode wanted, Map<String, ClassNode> nodes) {
        if (wanted == null || nodes == null) return null;
        ClassNode owner = nodes.get(wanted.owner);
        if (owner == null || owner.methods == null) return null;
        String agreed = null;
        for (MethodNode method : owner.methods) {
            if (!"<clinit>".equals(method.name) || method.instructions == null) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                        || !wanted.owner.equals(field.owner) || !wanted.name.equals(field.name)
                        || !wanted.desc.equals(field.desc)) continue;
                AbstractInsnNode value = previousReal(field);
                if (!(value instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String text)) return null;
                if (agreed != null && !agreed.equals(text)) return null;
                agreed = text;
            }
        }
        return agreed;
    }

    private static boolean assetFamilyToken(String value) {
        return value != null && !value.isBlank() && value.length() <= 96
                && value.matches("[A-Za-z0-9_./:-]+");
    }

    public static void bindRegisteredEntities(Collection<Surface> surfaces, Map<String, String> bindings) {
        if (surfaces == null || surfaces.isEmpty() || bindings == null || bindings.isEmpty()) return;
        for (Surface surface : surfaces) {
            if (surface.clazz == null) continue;
            String renderer = leaf(surface.clazz).replace("Renderer", "")
                    .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            bindings.forEach((entity, owner) -> {
                if (renderer.equals(owner)) {
                    surface.entityRegistryPaths.add(entity);
                    surface.primaryEntityRegistryPaths.add(entity);
                }
            });
        }

        // Some mods choose between two renderer implementations behind a client/config branch and
        // register the same EntityType set in both branches. The capture binding map deliberately
        // keeps the first registration (the shipped/default branch), but discovery still needs to
        // know that the alternate renderer is owned by those entities. Propagate only through an
        // exact mod-defined renderer target type; erased vanilla Entity/Mob/LivingEntity signatures
        // are intentionally excluded because they are shared by unrelated renderer families.
        Map<String, Set<String>> pathsByTarget = new LinkedHashMap<>();
        for (Surface surface : surfaces) {
            if (surface == null || surface.entityRegistryPaths.isEmpty()) continue;
            for (String type : surface.entityTypes) {
                if (!modDefinedEntityTarget(type)) continue;
                pathsByTarget.computeIfAbsent(type, ignored -> new LinkedHashSet<>())
                        .addAll(surface.entityRegistryPaths);
            }
        }
        for (Surface surface : surfaces) {
            if (surface == null || !surface.entityRenderer || !surface.entityRegistryPaths.isEmpty()) continue;
            LinkedHashSet<String> inherited = new LinkedHashSet<>();
            for (String type : surface.entityTypes) {
                if (!modDefinedEntityTarget(type)) continue;
                Set<String> paths = pathsByTarget.get(type);
                if (paths != null) inherited.addAll(paths);
            }
            surface.entityRegistryPaths.addAll(inherited);
        }
    }

    private static boolean modDefinedEntityTarget(String type) {
        return type != null && !type.isBlank() && !type.startsWith("net/minecraft/");
    }

    /**
     * Binds client-side enum dispatch to renderer surfaces without executing the client state
     * machine. A target entity contributes only enum types returned by its own zero-argument
     * accessors. For each such enum, ordinary tableswitch/lookupswitch bytecode is decoded and the
     * branch's nested lambdas are reduced to short string/method tokens. Those tokens are only
     * evidence; the pack builder later intersects them with real animation clips from the asset.
     */
    public static void bindEntityStateAnimationHints(Collection<Surface> surfaces,
                                                     Map<String, ClassNode> nodes) {
        if (surfaces == null || surfaces.isEmpty() || nodes == null || nodes.isEmpty()) return;
        LinkedHashSet<String> wantedEnums = new LinkedHashSet<>();
        Map<Surface, Set<String>> enumsBySurface = new LinkedHashMap<>();
        for (Surface surface : surfaces) {
            if (surface == null || !surface.entityRenderer || surface.entityTypes.isEmpty()) continue;
            LinkedHashSet<String> enums = new LinkedHashSet<>();
            for (String entityType : surface.entityTypes) {
                ClassNode entity = nodes.get(entityType);
                if (entity == null || entity.methods == null) continue;
                for (MethodNode method : entity.methods) {
                    if (method == null || Type.getArgumentTypes(method.desc).length != 0) continue;
                    Type result;
                    try { result = Type.getReturnType(method.desc); }
                    catch (IllegalArgumentException ignored) { continue; }
                    if (result.getSort() != Type.OBJECT) continue;
                    ClassNode enumNode = nodes.get(result.getInternalName());
                    if (enumNode != null && (enumNode.access & Opcodes.ACC_ENUM) != 0) {
                        enums.add(enumNode.name);
                        wantedEnums.add(enumNode.name);
                    }
                }
            }
            if (!enums.isEmpty()) enumsBySurface.put(surface, Set.copyOf(enums));
        }
        if (wantedEnums.isEmpty()) return;

        Map<String, Map<String, Set<String>>> evidence = new LinkedHashMap<>();
        for (String enumType : wantedEnums) {
            Map<String, Set<String>> found = enumBranchEvidence(enumType, nodes);
            if (!found.isEmpty()) evidence.put(enumType, found);
        }
        for (Map.Entry<Surface, Set<String>> entry : enumsBySurface.entrySet()) {
            Surface surface = entry.getKey();
            for (String enumType : entry.getValue()) {
                Map<String, Set<String>> found = evidence.get(enumType);
                if (found == null) continue;
                found.forEach((state, tokens) -> surface.entityStateAnimationHints
                        .computeIfAbsent(state, ignored -> new LinkedHashSet<>()).addAll(tokens));
            }
        }
    }

    private static Map<String, Set<String>> enumBranchEvidence(String enumType,
                                                               Map<String, ClassNode> nodes) {
        LinkedHashMap<String, Set<String>> out = new LinkedHashMap<>();
        for (ClassNode owner : nodes.values()) {
            if (owner == null || owner.methods == null) continue;
            for (MethodNode method : owner.methods) {
                Type[] args;
                try { args = Type.getArgumentTypes(method.desc); }
                catch (IllegalArgumentException ignored) { continue; }
                if (args.length != 1 || args[0].getSort() != Type.OBJECT
                        || !enumType.equals(args[0].getInternalName())) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof TableSwitchInsnNode)
                            && !(instruction instanceof LookupSwitchInsnNode)) continue;
                    FieldInsnNode mapping = enumSwitchMappingField(instruction);
                    if (mapping == null) continue;
                    Map<Integer, String> states = enumSwitchCases(mapping.owner, enumType, nodes);
                    if (states.isEmpty()) continue;
                    Map<Integer, AbstractInsnNode> labels = switchLabels(instruction);
                    LinkedHashSet<AbstractInsnNode> boundaries = new LinkedHashSet<>(labels.values());
                    AbstractInsnNode fallback = instruction instanceof TableSwitchInsnNode table
                            ? table.dflt : ((LookupSwitchInsnNode) instruction).dflt;
                    if (fallback != null) boundaries.add(fallback);
                    for (Map.Entry<Integer, AbstractInsnNode> branch : labels.entrySet()) {
                        String state = states.get(branch.getKey());
                        if (state == null) continue;
                        LinkedHashSet<String> tokens = new LinkedHashSet<>();
                        collectBranchEvidence(nextReal(branch.getValue()), boundaries, nodes,
                                tokens, new HashSet<>(), 0);
                        if (!tokens.isEmpty()) out.computeIfAbsent(state,
                                ignored -> new LinkedHashSet<>()).addAll(tokens);
                    }
                }
            }
        }
        return out;
    }

    private static FieldInsnNode enumSwitchMappingField(AbstractInsnNode switchInsn) {
        AbstractInsnNode cursor = switchInsn;
        for (int i = 0; i < 10 && (cursor = previousReal(cursor)) != null; i++) {
            if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && "[I".equals(field.desc)) return field;
        }
        return null;
    }

    private static Map<Integer, String> enumSwitchCases(String mappingOwner, String enumType,
                                                        Map<String, ClassNode> nodes) {
        ClassNode mapping = nodes.get(mappingOwner);
        if (mapping == null || mapping.methods == null) return Map.of();
        LinkedHashMap<Integer, String> out = new LinkedHashMap<>();
        for (MethodNode method : mapping.methods) {
            if (!"<clinit>".equals(method.name) || method.instructions == null) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode state) || state.getOpcode() != Opcodes.GETSTATIC
                        || !enumType.equals(state.owner)) continue;
                Integer caseNumber = null;
                AbstractInsnNode cursor = state;
                for (int i = 0; i < 8 && (cursor = nextReal(cursor)) != null; i++) {
                    Integer constant = integerConstant(cursor);
                    if (constant != null) caseNumber = constant;
                    if (cursor.getOpcode() == Opcodes.IASTORE) break;
                    if (cursor instanceof JumpInsnNode) break;
                }
                if (caseNumber != null) out.put(caseNumber, state.name);
            }
        }
        return out;
    }

    private static Map<Integer, AbstractInsnNode> switchLabels(AbstractInsnNode instruction) {
        LinkedHashMap<Integer, AbstractInsnNode> out = new LinkedHashMap<>();
        if (instruction instanceof TableSwitchInsnNode table) {
            for (int i = 0; i < table.labels.size(); i++) out.put(table.min + i, table.labels.get(i));
        } else if (instruction instanceof LookupSwitchInsnNode lookup) {
            for (int i = 0; i < lookup.labels.size() && i < lookup.keys.size(); i++) {
                out.put(lookup.keys.get(i), lookup.labels.get(i));
            }
        }
        return out;
    }

    private static void collectBranchEvidence(AbstractInsnNode start, Set<AbstractInsnNode> boundaries,
                                              Map<String, ClassNode> nodes, Set<String> out,
                                              Set<String> visiting, int depth) {
        if (start == null || depth > 6) return;
        int remaining = 160;
        for (AbstractInsnNode cursor = start; cursor != null && remaining-- > 0; cursor = nextReal(cursor)) {
            if (cursor != start && boundaries.contains(cursor)) break;
            if (cursor instanceof LdcInsnNode ldc && ldc.cst instanceof String text) {
                addAnimationEvidenceToken(out, text);
            } else if (cursor instanceof MethodInsnNode call) {
                addAnimationEvidenceMethod(out, call.name);
            } else if (cursor instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
                for (Object argument : indy.bsmArgs) if (argument instanceof String text) {
                    addAnimationEvidenceToken(out, text.replace("\u0001", ""));
                }
            } else if (cursor instanceof TypeInsnNode type && cursor.getOpcode() == Opcodes.NEW
                    && nodes.containsKey(type.desc) && visiting.add(type.desc)) {
                try {
                    collectNestedAnimationEvidence(nodes.get(type.desc), nodes, out, visiting, depth + 1);
                } finally {
                    visiting.remove(type.desc);
                }
            }
            if (cursor instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO) break;
        }
    }

    private static void collectNestedAnimationEvidence(ClassNode node, Map<String, ClassNode> nodes,
                                                       Set<String> out, Set<String> visiting, int depth) {
        if (node == null || node.methods == null || depth > 6) return;
        for (MethodNode method : node.methods) {
            if (method == null || method.instructions == null || "<init>".equals(method.name)) continue;
            collectBranchEvidence(method.instructions.getFirst(), Set.of(), nodes, out, visiting, depth);
        }
    }

    private static void addAnimationEvidenceMethod(Set<String> out, String method) {
        if (method == null) return;
        String value = method;
        if (value.startsWith("get") && value.length() > 3) value = value.substring(3);
        else if (value.startsWith("set") && value.length() > 3) value = value.substring(3);
        addAnimationEvidenceToken(out, value);
    }

    private static void addAnimationEvidenceToken(Set<String> out, String raw) {
        if (out == null || raw == null) return;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < value.length()) value = value.substring(colon + 1);
        int dot = value.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < value.length()) value = value.substring(dot + 1);
        if (!value.isBlank() && value.length() <= 64 && value.matches("[a-z0-9_/-]+")) out.add(value);
    }

    private static boolean registeredEntity(Surface surface, String path) {
        return surface.entityRegistryPaths.contains(path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
    }

    private static boolean primaryRegisteredEntity(Surface surface, String path) {
        return surface != null && path != null && surface.primaryEntityRegistryPaths.contains(
                path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
    }

    /** Exact repository-family hints attached to the renderer for one registry entity. */
    public static Set<String> assetFamilyHintsForEntity(Collection<ModScanner.Profile> profiles,
                                                        String namespace, String entityPath) {
        if (profiles == null || namespace == null || entityPath == null) return Set.of();
        List<Surface> surfaces = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectSurfaces(profile, namespace, surfaces);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Surface surface : surfaces) {
            if (surface == null || surface.entityAssetFamilyHints.isEmpty()
                    || !ownsEntity(surface, entityPath)) continue;
            out.addAll(surface.entityAssetFamilyHints);
        }
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(out));
    }

    /** Exact server-side identity accessor chains used by this entity renderer to select a model
     * repository variant. Each step is scanner-proven zero-argument bytecode in M#owner#name#desc
     * form; runtime consumers must validate every owner/descriptor again before invocation. */
    public static List<List<String>> appearanceAccessorSourcesForEntity(
            Collection<ModScanner.Profile> profiles, String namespace, String entityPath) {
        if (profiles == null || namespace == null || entityPath == null) return List.of();
        List<Surface> surfaces = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectSurfaces(profile, namespace, surfaces);
        LinkedHashSet<List<String>> out = new LinkedHashSet<>();
        for (Surface surface : surfaces) {
            if (surface == null || surface.entityAppearanceAccessorSources.isEmpty()
                    || !ownsEntity(surface, entityPath)) continue;
            for (EntityAppearanceAccessorSource source : surface.entityAppearanceAccessorSources) {
                if (source == null || source.accessors == null || source.accessors.isEmpty()) continue;
                out.add(List.copyOf(source.accessors));
            }
        }
        return List.copyOf(out);
    }

    /** Server-authoritative renderer root-scale component for one entity. Conflicting renderer
     * bindings fail closed; callers must still validate every accessor against the live object. */
    public static EntityRootScaleSource rootScaleSourceForEntity(
            Collection<ModScanner.Profile> profiles, String namespace, String entityPath) {
        if (profiles == null || namespace == null || entityPath == null) return null;
        List<Surface> surfaces = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectSurfaces(profile, namespace, surfaces);
        EntityRootScaleSource agreed = null;
        String signature = null;
        for (Surface surface : surfaces) {
            if (surface == null || surface.entityRootScaleSource == null
                    || !ownsEntity(surface, entityPath)) continue;
            String candidate = rootScaleSignature(surface.entityRootScaleSource);
            if (agreed == null) {
                agreed = surface.entityRootScaleSource;
                signature = candidate;
            } else if (!signature.equals(candidate)) {
                return null;
            }
        }
        return copy(agreed);
    }

    /** Scanner-proven enum branch evidence for state-driven renderer animations. */
    public static Map<String, Set<String>> stateAnimationHintsForEntity(
            Collection<ModScanner.Profile> profiles, String namespace, String entityPath) {
        if (profiles == null || namespace == null || entityPath == null) return Map.of();
        List<Surface> surfaces = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectSurfaces(profile, namespace, surfaces);
        LinkedHashMap<String, Set<String>> out = new LinkedHashMap<>();
        for (Surface surface : surfaces) {
            if (surface == null || surface.entityStateAnimationHints.isEmpty()
                    || !ownsEntity(surface, entityPath)) continue;
            surface.entityStateAnimationHints.forEach((state, tokens) -> out
                    .computeIfAbsent(state, ignored -> new LinkedHashSet<>()).addAll(tokens));
        }
        LinkedHashMap<String, Set<String>> frozen = new LinkedHashMap<>();
        out.forEach((state, tokens) -> frozen.put(state,
                java.util.Collections.unmodifiableSet(new LinkedHashSet<>(tokens))));
        return java.util.Collections.unmodifiableMap(frozen);
    }

    private static boolean ownsEntity(Surface surface, String entityPath) {
        if (surface == null || entityPath == null) return false;
        if (registeredEntity(surface, entityPath)) return true;
        String wanted = entityPath.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String type : surface.entityTypes) {
            String candidate = leaf(type).toLowerCase(Locale.ROOT)
                    .replace("entity", "").replace("mob", "")
                    .replaceAll("[^a-z0-9]", "");
            if (!candidate.isBlank() && (candidate.equals(wanted)
                    || candidate.contains(wanted) || wanted.contains(candidate))) return true;
        }
        return false;
    }

    /** Renderer-owned texture recipes for an entity. Exact descriptor ownership wins; semantic
     * renderer/entity matching is only a fallback for erased bridge methods. */
    public static Set<String> textureRefsForEntity(Collection<ModScanner.Profile> profiles,
                                                   String namespace, String entityPath) {
        if (profiles == null || namespace == null || entityPath == null) return Set.of();
        List<Surface> surfaces = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectSurfaces(profile, namespace, surfaces);
        if (surfaces.isEmpty()) return Set.of();
        String normalizedPath = entityPath.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> exact = new LinkedHashSet<>();
        LinkedHashSet<String> semantic = new LinkedHashSet<>();
        for (Surface surface : surfaces) {
            if (!surface.textureRefs.isEmpty() && primaryRegisteredEntity(surface, entityPath)) {
                return Set.copyOf(surface.textureRefs);
            }
        }
        for (Surface surface : surfaces) {
            if (surface.textureRefs.isEmpty()) continue;
            if (registeredEntity(surface, entityPath)) return Set.copyOf(surface.textureRefs);
            boolean descriptorMatch = surface.entityTypes.stream().anyMatch(type -> {
                String leaf = leaf(type).toLowerCase(Locale.ROOT)
                        .replace("entity", "").replace("mob", "");
                String wanted = normalizedPath.replace("_", "").replace("-", "");
                String candidate = leaf.replace("_", "").replace("-", "");
                return !candidate.isBlank() && (candidate.contains(wanted) || wanted.contains(candidate));
            });
            if (descriptorMatch) exact.addAll(surface.textureRefs);
            else if (semanticScore(entityPath, surface.clazz) >= 20) semantic.addAll(surface.textureRefs);
        }
        return Set.copyOf(exact.isEmpty() ? semantic : exact);
    }

    /** Authored uniform world scale of a bytecode-proven camera-facing unit quad, or zero when the
     * entity renderer does not expose that contract. Registry binding wins over descriptor and
     * semantic fallback in the same order as texture ownership. */
    public static float cameraFacingQuadScaleForEntity(Collection<ModScanner.Profile> profiles,
                                                       String namespace, String entityPath) {
        if (profiles == null || namespace == null || entityPath == null) return 0f;
        List<Surface> surfaces = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectSurfaces(profile, namespace, surfaces);
        if (surfaces.isEmpty()) return 0f;
        String normalizedPath = entityPath.toLowerCase(Locale.ROOT);
        Surface best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Surface surface : surfaces) {
            if (surface.cameraFacingQuad && surface.cameraFacingQuadScale > 0f
                    && primaryRegisteredEntity(surface, entityPath)) {
                return surface.cameraFacingQuadScale;
            }
        }
        for (Surface surface : surfaces) {
            if (!surface.cameraFacingQuad || !(surface.cameraFacingQuadScale > 0f)) continue;
            if (registeredEntity(surface, entityPath)) return surface.cameraFacingQuadScale;
            boolean descriptorMatch = surface.entityTypes.stream().anyMatch(type -> {
                String candidate = leaf(type).toLowerCase(Locale.ROOT)
                        .replace("entity", "").replace("mob", "")
                        .replace("_", "").replace("-", "");
                String wanted = normalizedPath.replace("_", "").replace("-", "");
                return !candidate.isBlank() && (candidate.contains(wanted) || wanted.contains(candidate));
            });
            int semantic = semanticScore(entityPath, surface.clazz);
            if (!descriptorMatch && semantic < 20) continue;
            int score = (descriptorMatch ? 10_000 : 0) + semantic;
            if (score > bestScore) {
                bestScore = score;
                best = surface;
            }
        }
        return best == null ? 0f : best.cameraFacingQuadScale;
    }

    /** Ordered live BlockState composition owned by an entity renderer. Exact registry ownership
     * wins; descriptor/semantic matching is retained only for erased bridge registrations. */
    public static EntityBlockSequence blockSequenceForEntity(Collection<ModScanner.Profile> profiles,
                                                              String namespace, String entityPath) {
        if (profiles == null || namespace == null || entityPath == null) return null;
        List<Surface> surfaces = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectSurfaces(profile, namespace, surfaces);
        if (surfaces.isEmpty()) return null;
        String normalizedPath = entityPath.toLowerCase(Locale.ROOT);
        Surface best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Surface surface : surfaces) {
            if (surface.entityBlockSequence != null
                    && (surface.entityBlockSequence.blocksAccessor != null
                    || surface.entityBlockSequence.blockAccessor != null)
                    && primaryRegisteredEntity(surface, entityPath)) {
                return copy(surface.entityBlockSequence);
            }
        }
        for (Surface surface : surfaces) {
            if (surface.entityBlockSequence == null || surface.entityBlockSequence.blocksAccessor == null
                    && surface.entityBlockSequence.blockAccessor == null) continue;
            if (registeredEntity(surface, entityPath)) return copy(surface.entityBlockSequence);
            boolean descriptorMatch = surface.entityTypes.stream().anyMatch(type -> {
                String candidate = leaf(type).toLowerCase(Locale.ROOT)
                        .replace("entity", "").replace("mob", "")
                        .replace("_", "").replace("-", "");
                String wanted = normalizedPath.replace("_", "").replace("-", "");
                return !candidate.isBlank() && (candidate.contains(wanted) || wanted.contains(candidate));
            });
            int semantic = semanticScore(entityPath, surface.clazz);
            if (!descriptorMatch && semantic < 20) continue;
            int score = (descriptorMatch ? 10_000 : 0) + semantic;
            if (score > bestScore) {
                bestScore = score;
                best = surface;
            }
        }
        return best == null ? null : copy(best.entityBlockSequence);
    }

    /** Procedural renderer contract for one registry entity. Registry ownership wins over erased
     * descriptor/semantic fallback in the same way as block-sequence and texture contracts. */
    public static EntityProceduralGeometry proceduralGeometryForEntity(
            Collection<ModScanner.Profile> profiles, String namespace, String entityPath) {
        if (profiles == null || namespace == null || entityPath == null) return null;
        List<Surface> surfaces = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectSurfaces(profile, namespace, surfaces);
        if (surfaces.isEmpty()) return null;
        String normalizedPath = entityPath.toLowerCase(Locale.ROOT);
        Surface best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Surface surface : surfaces) {
            if (surface.entityProceduralGeometry != null
                    && primaryRegisteredEntity(surface, entityPath)) {
                return copy(surface.entityProceduralGeometry);
            }
        }
        for (Surface surface : surfaces) {
            if (surface.entityProceduralGeometry == null) continue;
            if (registeredEntity(surface, entityPath)) return copy(surface.entityProceduralGeometry);
            boolean descriptorMatch = surface.entityTypes.stream().anyMatch(type -> {
                String candidate = leaf(type).toLowerCase(Locale.ROOT)
                        .replace("entity", "").replace("mob", "")
                        .replace("_", "").replace("-", "");
                String wanted = normalizedPath.replace("_", "").replace("-", "");
                return !candidate.isBlank() && (candidate.contains(wanted) || wanted.contains(candidate));
            });
            int semantic = semanticScore(entityPath, surface.clazz);
            if (!descriptorMatch && semantic < 20) continue;
            int score = (descriptorMatch ? 10_000 : 0) + semantic;
            if (score > bestScore) {
                bestScore = score;
                best = surface;
            }
        }
        return best == null ? null : copy(best.entityProceduralGeometry);
    }

    private static EntityBlockSequence copy(EntityBlockSequence source) {
        if (source == null) return null;
        EntityBlockSequence copy = new EntityBlockSequence();
        copy.blockAccessor = source.blockAccessor;
        copy.blocksAccessor = source.blocksAccessor;
        copy.orientationAccessor = source.orientationAccessor;
        copy.directionAccessor = source.directionAccessor;
        copy.axis = source.axis;
        copy.step = source.step;
        return copy;
    }

    private static EntityRootScaleSource copy(EntityRootScaleSource source) {
        return source == null ? null : new EntityRootScaleSource(source.constant, source.accessorPaths);
    }

    private static EntityProceduralGeometry copy(EntityProceduralGeometry source) {
        if (source == null) return null;
        EntityProceduralGeometry copy = new EntityProceduralGeometry();
        copy.kind = source.kind;
        copy.collectionAccessor = source.collectionAccessor;
        copy.memberPositionAccessor = source.memberPositionAccessor;
        copy.memberVelocityAccessor = source.memberVelocityAccessor;
        copy.billboardScale = source.billboardScale;
        copy.billboardScaleSelector = source.billboardScaleSelector;
        copy.billboardScaleSelectorOrdinal = source.billboardScaleSelectorOrdinal;
        copy.selectedBillboardScale = source.selectedBillboardScale;
        copy.defaultBillboardScale = source.defaultBillboardScale;
        copy.activePredicate = source.activePredicate;
        copy.activeSelector = source.activeSelector;
        copy.orientationAccessor = source.orientationAccessor;
        copy.halfExtent = source.halfExtent;
        copy.extentSelector = source.extentSelector;
        copy.extentSelectorOrdinal = source.extentSelectorOrdinal;
        copy.selectedHalfExtent = source.selectedHalfExtent;
        copy.red = source.red;
        copy.green = source.green;
        copy.blue = source.blue;
        copy.boxTexture = source.boxTexture;
        copy.billboardTexture = source.billboardTexture;
        return copy;
    }

    /** Full-surface texture selector for one entity renderer. Exact registry ownership wins, then
     * descriptor ownership, then the same conservative semantic fallback used by texture refs. */
    public static EntityTextureSelector textureSelectorForEntity(Collection<ModScanner.Profile> profiles,
                                                                 String namespace, String entityPath) {
        if (profiles == null || namespace == null || entityPath == null) return null;
        List<Surface> surfaces = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectSurfaces(profile, namespace, surfaces);
        if (surfaces.isEmpty()) return null;
        String normalizedPath = entityPath.toLowerCase(Locale.ROOT);
        Surface best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Surface surface : surfaces) {
            if (surface.entityTextureSelector != null && primaryRegisteredEntity(surface, entityPath)) {
                return surface.entityTextureSelector;
            }
        }
        for (Surface surface : surfaces) {
            if (surface.entityTextureSelector == null) continue;
            if (registeredEntity(surface, entityPath)) return surface.entityTextureSelector;
            boolean descriptorMatch = surface.entityTypes.stream().anyMatch(type -> {
                String candidate = leaf(type).toLowerCase(Locale.ROOT)
                        .replace("entity", "").replace("mob", "")
                        .replace("_", "").replace("-", "");
                String wanted = normalizedPath.replace("_", "").replace("-", "");
                return !candidate.isBlank() && (candidate.contains(wanted) || wanted.contains(candidate));
            });
            int semantic = semanticScore(entityPath, surface.clazz);
            if (!descriptorMatch && semantic < 20) continue;
            int score = (descriptorMatch ? 10_000 : 0) + semantic;
            if (score > bestScore) {
                bestScore = score;
                best = surface;
            }
        }
        return best == null ? null : best.entityTextureSelector;
    }

    /** Vanilla LayerDefinition geometry owned by the renderer for an entity. Descriptor evidence
     * is preferred; semantic matching is retained only for erased Entity bridge methods. */
    public static ArmorVisualBytecodeScanner.LayerModel layerModelForEntity(
            Collection<ModScanner.Profile> profiles, String namespace, String entityPath) {
        if (profiles == null || namespace == null || entityPath == null) return null;
        List<Surface> surfaces = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectSurfaces(profile, namespace, surfaces);
        String normalized = entityPath.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        Surface best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Surface surface : surfaces) {
            if (surface.layerModel != null && !surface.layerModel.cubes.isEmpty()
                    && primaryRegisteredEntity(surface, entityPath)) {
                return surface.layerModel;
            }
        }
        for (Surface surface : surfaces) {
            if (surface.layerModel == null || surface.layerModel.cubes.isEmpty()) continue;
            if (registeredEntity(surface, entityPath)) return surface.layerModel;
            boolean descriptorMatch = surface.entityTypes.stream().anyMatch(type -> {
                String candidate = leaf(type).toLowerCase(Locale.ROOT)
                        .replace("entity", "").replace("mob", "")
                        .replace("_", "").replace("-", "");
                return !candidate.isBlank()
                        && (candidate.contains(normalized) || normalized.contains(candidate));
            });
            int semantic = semanticScore(entityPath, surface.clazz);
            if (!descriptorMatch && semantic < 20) continue;
            int score = (descriptorMatch ? 10_000 : 0) + semantic
                    + Math.min(500, surface.layerModel.cubes.size());
            if (score > bestScore) {
                best = surface;
                bestScore = score;
            }
        }
        return best == null ? null : best.layerModel;
    }

    /** Exact model refs selected by the scanner-owned block render plan. */
    public static Set<String> modelRefsForBlock(Collection<ModScanner.Profile> profiles,
                                                 String namespace, String blockPath) {
        return renderPlanForBlock(profiles, namespace, blockPath).modelRefs();
    }

    /** Runtime fast path for the already-completed discovery pass. */
    public static BlockRenderPlan discoveredRenderPlanForBlock(String namespace, String blockPath) {
        if (namespace == null || blockPath == null) {
            return new BlockRenderPlan(false, Set.of(), Map.of(), Map.of(), Set.of(), Set.of(),
                    List.of(), Set.of(), List.of());
        }
        long generation = DiscoveryManager.generation();
        String key = namespace + ':' + blockPath;
        CachedBlockRenderPlan cached = DISCOVERED_BLOCK_PLANS.get(key);
        if (cached != null && cached.generation == generation) return cached.plan;
        BlockRenderPlan computed = renderPlanForBlock(
                DiscoveryManager.profiles().values(), namespace, blockPath);
        DISCOVERED_BLOCK_PLANS.put(key, new CachedBlockRenderPlan(generation, computed));
        return computed;
    }

    public static BlockRenderPlan renderPlanForBlock(Collection<ModScanner.Profile> profiles,
                                                     String namespace, String blockPath) {
        if (profiles == null || namespace == null || blockPath == null) {
            return new BlockRenderPlan(false, Set.of(), Map.of(), Map.of(), Set.of(), Set.of(), List.of(), Set.of(), List.of());
        }
        List<Surface> candidates = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) {
            collectSurfaces(profile, namespace, candidates);
        }
        LinkedHashSet<String> exactBlockClasses = new LinkedHashSet<>();
        LinkedHashSet<String> exactBlockEntityTypes = new LinkedHashSet<>();
        String blockId = namespace + ':' + blockPath;
        for (ModScanner.Profile profile : profiles) {
            collectExactBlockClasses(profile, namespace, blockId, exactBlockClasses);
            collectExactBlockEntityTypes(profile, namespace, blockId, exactBlockEntityTypes);
        }
        boolean authoritative = !exactBlockClasses.isEmpty();
        Set<String> sharedExactBlockEntityTypes = sharedBlockEntityTypes(
                profiles, namespace, exactBlockEntityTypes);
        if (candidates.isEmpty()) {
            return new BlockRenderPlan(authoritative, Set.of(), Map.of(), Map.of(), Set.of(), Set.of(), List.of(), Set.of(), List.of());
        }

        int best = Integer.MIN_VALUE;
        List<Surface> selected = new ArrayList<>();
        for (Surface surface : candidates) {
            if (surface.modelRefs.isEmpty()) continue;
            // Entity renderers and block renderers share this scanner inventory, but an entity-only
            // surface is never valid evidence for a block model. This must hold even before live
            // registry enrichment makes the block plan authoritative; otherwise semantic names
            // such as "shell" can leak a projectile-only partial into every similarly named block.
            if (surface.entityRenderer) continue;
            if (authoritative && surface.blockEntityTypes.isEmpty()) continue;
            int score = ownershipScore(blockPath, exactBlockClasses, exactBlockEntityTypes, surface);
            if (usesOnlySharedExactTarget(surface.blockEntityTypes, exactBlockEntityTypes,
                    sharedExactBlockEntityTypes)) {
                score = strictSemanticOwnershipScore(blockPath, exactBlockClasses, surface.clazz);
            }
            if (score < 20) continue;
            if (score > best) {
                best = score;
                selected.clear();
                selected.add(surface);
            } else if (score == best) {
                selected.add(surface);
            }
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        LinkedHashMap<String, Integer> copies = new LinkedHashMap<>();
        LinkedHashMap<String, BooleanPredicate> predicates = new LinkedHashMap<>();
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (Surface surface : selected) {
            targets.addAll(surface.blockEntityTypes);
            for (String ref : surface.modelRefs) {
                Set<String> refTargets = surface.modelRefTargets.get(ref);
                if (refTargets != null && !refTargets.isEmpty()
                        && targetOwnershipScore(blockPath, exactBlockClasses,
                        exactBlockEntityTypes, refTargets) < 20) {
                    continue;
                }
                String normalized = expandBlockModelRef(namespace, ref, blockPath);
                if (normalized != null && surface.nestedModelRefs.contains(ref)
                        && !nestedModelOwnedByBlock(blockPath, normalized, surface.nestedModelRefs)) {
                    continue;
                }
                if (normalized != null) {
                    refs.add(normalized);
                    int count = Math.max(1, surface.modelRefCopies.getOrDefault(ref, 1));
                    copies.merge(normalized, count, Math::max);
                    BooleanPredicate predicate = surface.modelPredicates.get(ref);
                    if (predicate != null) predicates.putIfAbsent(normalized, predicate);
                }
            }
        }

        int selectorBest = Integer.MIN_VALUE;
        List<Surface> selectorSurfaces = new ArrayList<>();
        for (Surface surface : candidates) {
            if (surface.directionalSelectors.isEmpty()) continue;
            if (surface.entityRenderer) continue;
            int score = ownershipScore(blockPath, exactBlockClasses, exactBlockEntityTypes, surface);
            if (usesOnlySharedExactTarget(surface.blockEntityTypes, exactBlockEntityTypes,
                    sharedExactBlockEntityTypes)) {
                score = strictSemanticOwnershipScore(blockPath, exactBlockClasses, surface.clazz);
            }
            if (score < 20) continue;
            if (score > selectorBest) {
                selectorBest = score;
                selectorSurfaces.clear();
                selectorSurfaces.add(surface);
            } else if (score == selectorBest) {
                selectorSurfaces.add(surface);
            }
        }
        LinkedHashSet<String> selectors = new LinkedHashSet<>();
        for (Surface surface : selectorSurfaces) selectors.addAll(surface.directionalSelectors);
        LinkedHashSet<String> presenceSelectors = new LinkedHashSet<>();
        for (Surface surface : selected) presenceSelectors.addAll(surface.directionalPresenceSelectors);
        ArrayList<DirectionalPresenceRule> presenceRules = new ArrayList<>();
        for (Surface surface : selected) presenceRules.addAll(surface.directionalPresenceRules);
        ArrayList<LinearRepeat> repeats = new ArrayList<>();
        for (Surface surface : selected) repeats.addAll(surface.linearRepeats);
        return new BlockRenderPlan(authoritative, Set.copyOf(refs), Map.copyOf(copies), Map.copyOf(predicates),
                Set.copyOf(selectors), Set.copyOf(presenceSelectors), List.copyOf(presenceRules), Set.copyOf(targets),
                List.copyOf(repeats));
    }

    private static boolean isEntityRendererType(ClassNode node, Map<String, ClassNode> nodes) {
        if (node == null) return false;
        String type = node.superName;
        Set<String> seen = new HashSet<>();
        while (type != null && seen.add(type)) {
            // Minecraft classes are intentionally not copied into a mod profile's ASM node map.
            // Most real entity renderers extend MobRenderer/LivingEntityRenderer/ArrowRenderer/etc
            // directly, so requiring EntityRenderer.class itself to be present makes only direct
            // EntityRenderer subclasses discoverable. Any vanilla class in the entity-renderer
            // package whose type name is a Renderer is part of that hierarchy; block-entity and
            // item GUI renderers live in different packages and therefore do not match this rule.
            if (isVanillaEntityRendererAncestor(type)) return true;
            ClassNode parent = nodes == null ? null : nodes.get(type);
            if (parent == null) return false;
            type = parent.superName;
        }
        return false;
    }

    private static boolean isVanillaEntityRendererAncestor(String type) {
        if (type == null || !type.startsWith("net/minecraft/client/renderer/entity/")) return false;
        int slash = type.lastIndexOf('/');
        String leaf = slash < 0 ? type : type.substring(slash + 1);
        return leaf.endsWith("Renderer");
    }

    private static boolean nestedModelOwnedByBlock(String blockPath, String normalizedRef,
                                                   Set<String> nestedRefs) {
        if (blockPath == null || blockPath.isBlank() || normalizedRef == null
                || nestedRefs == null || nestedRefs.size() <= 1) return true;
        boolean familyHasExactOwner = false;
        for (String ref : nestedRefs) {
            if (hasPathSegment(ref, blockPath)) {
                familyHasExactOwner = true;
                break;
            }
        }
        return !familyHasExactOwner || hasPathSegment(normalizedRef, blockPath);
    }

    private static boolean hasPathSegment(String ref, String segment) {
        if (ref == null || segment == null) return false;
        String path = ref;
        int colon = path.indexOf(':');
        if (colon >= 0) path = path.substring(colon + 1);
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.equals(segment)) return true;
        }
        return false;
    }

    /**
     * Decodes small counted loops in renderer helpers. A common renderer pattern constructs one
     * model buffer in the root and passes it to a helper which loops N times, translating or
     * rotating between {@code renderInto}/{@code blit}/{@code draw} calls. Keeping N in the scan
     * profile lets pack generation emit the same number of independent display parts without a
     * hard-coded machine/asset name.
     */
    private static int renderCopies(MethodNode method, Map<String, ClassNode> nodes,
                                    Set<String> visiting, int depth) {
        if (method == null || method.instructions == null || depth > 3) return 1;
        String identity = method.name + method.desc + '@' + System.identityHashCode(method);
        if (!visiting.add(identity)) return 1;
        int best = countedRenderLoop(method);
        try {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || nodes == null
                        || !nodes.containsKey(call.owner) || "<init>".equals(call.name)) continue;
                MethodNode helper = findMethod(nodes.get(call.owner), call.name, call.desc);
                if (helper == null || !renderHelper(call, helper)) continue;
                best = Math.max(best, renderCopies(helper, nodes, visiting, depth + 1));
            }
        } finally {
            visiting.remove(identity);
        }
        return Math.max(1, Math.min(64, best));
    }

    private static boolean renderHelper(MethodInsnNode call, MethodNode method) {
        String descriptor = (call == null || call.desc == null ? "" : call.desc)
                + (method == null || method.desc == null ? "" : method.desc);
        String name = ((call == null || call.name == null ? "" : call.name) + ' '
                + (method == null || method.name == null ? "" : method.name)).toLowerCase(Locale.ROOT);
        return descriptor.contains("PoseStack") || descriptor.contains("GuiGraphics")
                || descriptor.contains("VertexConsumer") || descriptor.contains("MultiBufferSource")
                || descriptor.toLowerCase(Locale.ROOT).contains("buffer")
                || name.contains("render") || name.contains("draw") || name.contains("blit");
    }

    private static int countedRenderLoop(MethodNode method) {
        int best = 1;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof JumpInsnNode jump) || !forwardJump(jump)) continue;
            int opcode = jump.getOpcode();
            if (opcode != Opcodes.IF_ICMPGE && opcode != Opcodes.IF_ICMPGT
                    && opcode != Opcodes.IF_ICMPLE && opcode != Opcodes.IF_ICMPLT) continue;
            AbstractInsnNode constantInsn = previousReal(instruction);
            AbstractInsnNode variableInsn = previousReal(constantInsn);
            Integer limit = integerConstant(constantInsn);
            if (!(variableInsn instanceof VarInsnNode variable)
                    || variable.getOpcode() != Opcodes.ILOAD || limit == null
                    || limit <= 1 || limit > 64) continue;
            int start = loopStartValue(variableInsn, variable.var);
            int increment = loopIncrement(instruction, jump.label, variable.var);
            if (increment == 0 || !containsRenderCall(instruction, jump.label)) continue;
            // A counted loop that calls a method returning a model/partial is selecting one
            // mutually-exclusive visual per iteration, not rendering every discovered model N
            // times. Conveyor renderers commonly loop over top/bottom passes and call a
            // getPartial(...) selector inside the loop. Treating that trip count as ownership of
            // every ref reachable from the selector stacked all state alternatives together.
            if (containsModelSelectionCall(instruction, jump.label)) continue;
            int count = loopTripCount(start, limit, increment, opcode);
            if (count > 1 && count <= 64) best = Math.max(best, count);
        }
        return best;
    }

    private static boolean containsModelSelectionCall(AbstractInsnNode start, AbstractInsnNode end) {
        for (AbstractInsnNode cursor = start.getNext(); cursor != null && cursor != end;
             cursor = cursor.getNext()) {
            if (!(cursor instanceof MethodInsnNode call) || call.desc == null) continue;
            try {
                Type returnType = Type.getReturnType(call.desc);
                if (returnType.getSort() == Type.OBJECT
                        && looksLikeModelDescriptor(returnType.getDescriptor())) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static int loopStartValue(AbstractInsnNode compareLoad, int variable) {
        int remaining = 24;
        for (AbstractInsnNode cursor = compareLoad.getPrevious(); cursor != null && remaining-- > 0;
             cursor = cursor.getPrevious()) {
            if (!(cursor instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ISTORE
                    || store.var != variable) continue;
            Integer value = integerConstant(previousReal(cursor));
            return value == null ? 0 : value;
        }
        return 0;
    }

    private static int loopIncrement(AbstractInsnNode start, AbstractInsnNode end, int variable) {
        for (AbstractInsnNode cursor = start.getNext(); cursor != null && cursor != end;
             cursor = cursor.getNext()) {
            if (cursor instanceof IincInsnNode increment && increment.var == variable) {
                return increment.incr;
            }
        }
        return 0;
    }

    private static boolean containsRenderCall(AbstractInsnNode start, AbstractInsnNode end) {
        for (AbstractInsnNode cursor = start.getNext(); cursor != null && cursor != end;
             cursor = cursor.getNext()) {
            if (!(cursor instanceof MethodInsnNode call)) continue;
            String semantic = ((call.owner == null ? "" : call.owner) + '/' + call.name)
                    .toLowerCase(Locale.ROOT);
            if (semantic.contains("render") || semantic.contains("draw") || semantic.contains("blit")) {
                return true;
            }
        }
        return false;
    }

    private static int loopTripCount(int start, int limit, int increment, int exitOpcode) {
        if (increment == 0) return 1;
        int value = start;
        int count = 0;
        while (count <= 64) {
            boolean exit = switch (exitOpcode) {
                case Opcodes.IF_ICMPGE -> value >= limit;
                case Opcodes.IF_ICMPGT -> value > limit;
                case Opcodes.IF_ICMPLE -> value <= limit;
                case Opcodes.IF_ICMPLT -> value < limit;
                default -> true;
            };
            if (exit) break;
            count++;
            value += increment;
        }
        return count;
    }

    private static boolean forwardJump(JumpInsnNode jump) {
        if (jump == null || jump.label == null) return false;
        for (AbstractInsnNode cursor = jump.getNext(); cursor != null; cursor = cursor.getNext()) {
            if (cursor == jump.label) return true;
        }
        return false;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getPrevious();
        return cursor;
    }

    private static Integer integerConstant(AbstractInsnNode instruction) {
        if (instruction instanceof org.objectweb.asm.tree.IntInsnNode value) return value.operand;
        if (instruction instanceof LdcInsnNode value && value.cst instanceof Integer integer) return integer;
        int opcode = instruction == null ? -1 : instruction.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return opcode - Opcodes.ICONST_0;
        }
        return null;
    }

    /**
     * Some mods intentionally reuse one broad BlockEntity implementation for many unrelated
     * blocks. An exact registry edge to that shared type proves the runtime object type, but not
     * which renderer-owned partials apply to a particular block. Treat it as ambiguous unless the
     * renderer has no extra semantic qualifier beyond the concrete block (for example
     * ShaftBlock must not inherit SplitShaftRenderer merely because both use KineticBlockEntity).
     */
    private static boolean usesOnlySharedExactTarget(Collection<String> surfaceTargets,
                                                     Set<String> exactTargets,
                                                     Set<String> sharedTargets) {
        if (surfaceTargets == null || surfaceTargets.isEmpty() || exactTargets == null
                || exactTargets.isEmpty() || sharedTargets == null || sharedTargets.isEmpty()) {
            return false;
        }
        boolean matched = false;
        for (String target : surfaceTargets) {
            if (!exactTargets.contains(target)) continue;
            matched = true;
            if (!sharedTargets.contains(target)) return false;
        }
        return matched;
    }

    private static int strictSemanticOwnershipScore(String blockPath, Set<String> exactBlockClasses,
                                                    String rendererClass) {
        Set<String> renderer = semanticTokens(rendererClass == null ? "" : leaf(rendererClass));
        if (renderer.isEmpty()) return Integer.MIN_VALUE;
        int best = strictSemanticScore(blockPath, renderer);
        if (exactBlockClasses != null) {
            for (String blockClass : exactBlockClasses) {
                best = Math.max(best, strictSemanticScore(blockClass, renderer));
            }
        }
        return best;
    }

    private static int strictSemanticScore(String blockValue, Set<String> rendererTokens) {
        Set<String> block = semanticTokens(blockValue == null ? "" : leaf(blockValue));
        if (block.isEmpty() || rendererTokens.isEmpty() || !block.containsAll(rendererTokens)) {
            return Integer.MIN_VALUE;
        }
        int score = 0;
        for (String token : rendererTokens) {
            if (block.contains(token)) score += token.length() >= 6 ? 24 : 20;
        }
        return score;
    }

    private static Set<String> sharedBlockEntityTypes(Collection<ModScanner.Profile> profiles,
                                                       String namespace, Set<String> candidates) {
        if (profiles == null || candidates == null || candidates.isEmpty()) return Set.of();
        Map<String, Set<String>> owners = new LinkedHashMap<>();
        for (String candidate : candidates) owners.put(candidate, new LinkedHashSet<>());
        for (ModScanner.Profile profile : profiles) {
            collectBlockEntityOwners(profile, namespace, owners);
        }
        LinkedHashSet<String> shared = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : owners.entrySet()) {
            if (entry.getValue().size() > 1) shared.add(entry.getKey());
        }
        return shared;
    }

    private static void collectBlockEntityOwners(ModScanner.Profile profile, String namespace,
                                                 Map<String, Set<String>> owners) {
        if (profile == null || owners == null || owners.isEmpty()) return;
        if (namespace.equals(profile.modId) && profile.blocks != null) {
            for (ModScanner.EntityInfo block : profile.blocks) {
                if (block == null || block.clazz == null || block.blockEntityTypes == null) continue;
                for (String type : block.blockEntityTypes) {
                    Set<String> classes = owners.get(type);
                    if (classes != null) classes.add(block.clazz);
                }
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                collectBlockEntityOwners(child, namespace, owners);
            }
        }
    }

    private static int ownershipScore(String blockPath, Set<String> exactBlockClasses,
                                      Set<String> exactBlockEntityTypes,
                                      Surface surface) {
        if (surface == null) return Integer.MIN_VALUE;
        if (!surface.blockEntityTypes.isEmpty()) {
            int target = targetOwnershipScore(blockPath, exactBlockClasses, exactBlockEntityTypes,
                    surface.blockEntityTypes);
            if (exactBlockEntityTypes != null && !exactBlockEntityTypes.isEmpty()) return target;
            if (!exactBlockClasses.isEmpty()) return target;
            if (target >= 20) return target + 100;
        }
        return semanticScore(blockPath, surface.clazz);
    }

    private static int targetOwnershipScore(String blockPath, Set<String> exactBlockClasses,
                                            Set<String> exactBlockEntityTypes,
                                            Collection<String> targetTypes) {
        if (targetTypes == null || targetTypes.isEmpty()) return Integer.MIN_VALUE;
        if (exactBlockEntityTypes != null && !exactBlockEntityTypes.isEmpty()) {
            for (String target : targetTypes) {
                if (exactBlockEntityTypes.contains(target)) return 10_000;
            }
            // A direct Block -> BlockEntity edge is definitive. Do not fall back to names and
            // accidentally borrow a renderer from a sibling machine in the same package.
            return Integer.MIN_VALUE;
        }
        int best = Integer.MIN_VALUE;
        if (exactBlockClasses != null && !exactBlockClasses.isEmpty()) {
            for (String blockClass : exactBlockClasses) {
                for (String target : targetTypes) {
                    best = Math.max(best, semanticScore(blockClass, target));
                }
            }
            return best;
        }
        for (String target : targetTypes) {
            best = Math.max(best, semanticScore(blockPath, target));
        }
        return best;
    }

    static int semanticScore(String blockPath, String rendererClass) {
        Set<String> block = semanticTokens(blockPath == null ? "" : leaf(blockPath));
        Set<String> renderer = semanticTokens(rendererClass == null ? "" : rendererClass);
        if (block.isEmpty() || renderer.isEmpty()) return Integer.MIN_VALUE;

        if (oppositeVariant(block, renderer, "small", "large")
                || oppositeVariant(block, renderer, "single", "double")
                || oppositeVariant(block, renderer, "horizontal", "vertical")) {
            return -100;
        }
        int score = 0;
        for (String token : block) {
            if (renderer.contains(token)) score += token.length() >= 6 ? 24 : 20;
        }
        return score;
    }

    private static boolean oppositeVariant(Set<String> block, Set<String> renderer,
                                           String first, String second) {
        return block.contains(first) && renderer.contains(second)
                || block.contains(second) && renderer.contains(first);
    }

    private static Set<String> semanticTokens(String value) {
        String separated = value.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_").toLowerCase(Locale.ROOT);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String token : separated.split("_+")) {
            if (token.length() < 3 || GENERIC_TOKENS.contains(token)) continue;
            out.add(token);
        }
        return out;
    }

    private static String leaf(String value) {
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('.'));
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private static void collectSurfaces(ModScanner.Profile profile, String namespace,
                                        List<Surface> out) {
        if (profile == null) return;
        if (namespace.equals(profile.modId) && profile.rendererSurfaces != null) {
            out.addAll(profile.rendererSurfaces);
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collectSurfaces(child, namespace, out);
        }
    }

    private static void collectExactBlockClasses(ModScanner.Profile profile, String namespace,
                                                 String blockId, Set<String> out) {
        if (profile == null) return;
        if (namespace.equals(profile.modId) && profile.blocks != null) {
            for (ModScanner.EntityInfo block : profile.blocks) {
                if (block == null || block.clazz == null || block.blockIds == null) continue;
                if (block.blockIds.contains(blockId)) out.add(block.clazz);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                collectExactBlockClasses(child, namespace, blockId, out);
            }
        }
    }

    private static void collectExactBlockEntityTypes(ModScanner.Profile profile, String namespace,
                                                     String blockId, Set<String> out) {
        if (profile == null) return;
        if (namespace.equals(profile.modId) && profile.blocks != null) {
            for (ModScanner.EntityInfo block : profile.blocks) {
                if (block == null || block.blockIds == null || block.blockEntityTypes == null
                        || !block.blockIds.contains(blockId)) continue;
                out.addAll(block.blockEntityTypes);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                collectExactBlockEntityTypes(child, namespace, blockId, out);
            }
        }
    }

    private static Set<String> rendererFamily(ClassNode node, Map<String, ClassNode> nodes) {
        LinkedHashSet<String> family = new LinkedHashSet<>();
        ClassNode current = node;
        for (int depth = 0; current != null && depth < 12; depth++) {
            family.add(current.name);
            current = nodes == null ? null : nodes.get(current.superName);
        }
        return family;
    }

    private static void collectModelRefs(String owner, MethodNode method,
                                         Map<String, ClassNode> nodes,
                                         Map<String, String> modelFields,
                                         Set<String> family, Set<String> visiting,
                                         Set<String> out, int depth) {
        if (method == null || out.size() >= MAX_MODEL_REFS || depth > 5) return;
        String methodId = owner + '#' + method.name + method.desc;
        if (!visiting.add(methodId)) return;
        try {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                 instruction = instruction.getNext()) {
                if (instruction instanceof FieldInsnNode field
                        && (field.getOpcode() == Opcodes.GETSTATIC || field.getOpcode() == Opcodes.GETFIELD)) {
                    String ref = modelFields.get(field.owner + '.' + field.name);
                    if (ref != null) {
                        out.add(ref);
                        if (out.size() >= MAX_MODEL_REFS) return;
                    }
                }
                if (!(instruction instanceof MethodInsnNode call) || nodes == null
                        || "<init>".equals(call.name) || !modelHelperCall(call, nodes, family)) continue;
                ClassNode calleeOwner = nodes.get(call.owner);
                MethodNode callee = findMethod(calleeOwner, call.name, call.desc);
                if (callee != null) {
                    collectModelRefs(call.owner, callee, nodes, modelFields, family, visiting,
                            out, depth + 1);
                }
            }
        } finally {
            visiting.remove(methodId);
        }
    }

    /**
     * Renderers sometimes delegate state-to-model selection to a sibling client helper. Following
     * every same-jar call would pull unrelated utility code into the renderer surface, so leave the
     * renderer family only when the call's declared return type is itself model-like. The descriptor
     * is verifier-stable evidence that the helper participates in visual selection.
     */
    private static boolean modelHelperCall(MethodInsnNode call, Map<String, ClassNode> nodes,
                                           Set<String> family) {
        if (call == null || call.owner == null || call.desc == null || nodes == null
                || !nodes.containsKey(call.owner)) return false;
        if (family != null && family.contains(call.owner)) return true;
        try {
            Type returned = Type.getReturnType(call.desc);
            return returned.getSort() == Type.OBJECT
                    && looksLikeModelDescriptor(returned.getDescriptor());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static MethodNode findMethod(ClassNode node, String name, String desc) {
        if (node == null || node.methods == null) return null;
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }


    private static boolean modelCompositionMethod(MethodNode method) {
        if (method == null || method.desc == null) return false;
        String desc = method.desc;
        String name = method.name == null ? "" : method.name.toLowerCase(Locale.ROOT);
        boolean modelApi = desc.contains("BakedModel") || desc.contains("BakedQuad")
                || desc.contains("ModelData") || desc.contains("ChunkRenderTypeSet");
        return modelApi && (name.contains("quad") || name.contains("modeldata")
                || name.contains("rendertype") || name.contains("gather") || name.contains("render"));
    }

    private static boolean renderMethod(MethodNode method) {
        if (method == null) return false;
        String name = method.name.toLowerCase(Locale.ROOT);
        String desc = method.desc == null ? "" : method.desc;
        boolean renderApi = desc.contains("PoseStack") || desc.contains("MultiBufferSource")
                || desc.contains("GuiGraphics") || desc.contains("VertexConsumer")
                || desc.contains("RenderType");
        return renderApi && (name.contains("render") || name.startsWith("m_"));
    }

    private static boolean looksLikeModelDescriptor(String descriptor) {
        if (descriptor == null) return false;
        String lower = descriptor.toLowerCase(Locale.ROOT);
        return lower.contains("model") || lower.contains("partial");
    }

    private static boolean looksLikeResourceLocationDescriptor(String descriptor) {
        if (descriptor == null) return false;
        String lower = descriptor.toLowerCase(Locale.ROOT);
        return lower.contains("resourcelocation");
    }

    /**
     * Some renderers ask the model manager for a ResourceLocation directly instead of storing a
     * PartialModel/BakedModel handle. Recover those calls only when the ResourceLocation flows
     * immediately into a model-returning lookup, so texture/sound ids in the same render method
     * cannot become geometry by accident. A {@code %s} path is kept as a block-path template and
     * resolved later by {@link #renderPlanForBlock(Collection, String, String)}.
     */
    private static void collectInlineModelRefs(MethodNode method, Set<String> out) {
        if (method == null || method.instructions == null || out == null) return;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || !resourceLocationFactory(call)
                    || !feedsModelLookup(call)) continue;
            String ref = inlineModelRefBefore(call);
            if (ref != null) out.add(ref);
        }
    }

    private static boolean resourceLocationFactory(MethodInsnNode call) {
        if (call == null || call.owner == null || call.desc == null
                || !"net/minecraft/resources/ResourceLocation".equals(call.owner)) return false;
        try {
            Type result = Type.getReturnType(call.desc);
            if (result.getSort() != Type.OBJECT
                    || !"net/minecraft/resources/ResourceLocation".equals(result.getInternalName())) {
                return false;
            }
            Type[] args = Type.getArgumentTypes(call.desc);
            return args.length >= 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean feedsModelLookup(AbstractInsnNode resourceLocationCall) {
        AbstractInsnNode cursor = nextReal(resourceLocationCall);
        for (int steps = 0; cursor != null && steps < 8; steps++, cursor = nextReal(cursor)) {
            if (!(cursor instanceof MethodInsnNode call) || call.desc == null) continue;
            try {
                Type returned = Type.getReturnType(call.desc);
                if (returned.getSort() == Type.OBJECT
                        && looksLikeModelDescriptor(returned.getDescriptor())) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** Recovers {@code namespace:path} from a nearby ResourceLocation factory call. */
    private static String inlineModelRefBefore(AbstractInsnNode factoryCall) {
        LdcInsnNode pathLiteral = null;
        int remaining = 48;
        for (AbstractInsnNode cursor = previousReal(factoryCall); cursor != null && remaining-- > 0;
             cursor = previousReal(cursor)) {
            if (cursor instanceof LdcInsnNode ldc && ldc.cst instanceof String value
                    && explicitModelPath(value)) {
                pathLiteral = ldc;
                break;
            }
            if (cursor instanceof MethodInsnNode previous && resourceLocationFactory(previous)) break;
        }
        if (pathLiteral == null) return null;
        String namespace = null;
        remaining = 24;
        for (AbstractInsnNode cursor = previousReal(pathLiteral); cursor != null && remaining-- > 0;
             cursor = previousReal(cursor)) {
            if (cursor instanceof LdcInsnNode ldc && ldc.cst instanceof String value
                    && value.matches("[a-z0-9_.-]+")) {
                namespace = value;
                break;
            }
            if (cursor instanceof MethodInsnNode previous && resourceLocationFactory(previous)) break;
        }
        String path = (String) pathLiteral.cst;
        String normalized = normalizeLiteral(path, "block");
        return namespace == null ? normalized : namespace + ':' + normalized;
    }

    private static boolean explicitModelPath(String value) {
        if (value == null || value.length() < 3 || value.length() > 220 || value.indexOf(' ') >= 0) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (lower.endsWith(".png") || lower.endsWith(".ogg")) return false;
        return lower.startsWith("block/") || lower.startsWith("item/") || lower.startsWith("entity/")
                || lower.contains("/models/") || lower.startsWith("models/");
    }

    private static String modelRefBefore(AbstractInsnNode putStatic) {
        // ModelResourceLocation is itself a ResourceLocation subtype/name and therefore matches
        // the generic descriptor predicate below. Decode the more-specific three-string shape
        // first; otherwise (namespace, path, variant) is misread as (path, variant) and discarded.
        if (putStatic instanceof FieldInsnNode target && target.desc != null
                && target.desc.toLowerCase(Locale.ROOT).contains("modelresourcelocation")) {
            ArrayList<String> strings = new ArrayList<>();
            int scan = 28;
            for (AbstractInsnNode previous = putStatic.getPrevious(); previous != null && scan-- > 0;
                 previous = previous.getPrevious()) {
                if (previous instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                    strings.add(0, value);
                }
                if (previous.getOpcode() == Opcodes.PUTSTATIC) break;
            }
            // ModelResourceLocation(ResourceLocation(namespace,path), variant) is a common vanilla
            // model handle. The variant (usually "inventory") is not a model path; preserve the
            // namespace/path pair used to construct the nested ResourceLocation instead.
            if (strings.size() >= 3) {
                String namespace = strings.get(strings.size() - 3);
                String path = strings.get(strings.size() - 2);
                if (namespace.matches("[a-z0-9_.-]+") && looksLikeModelPath(path, "block")) {
                    return namespace + ':' + normalizeLiteral(path, "block");
                }
            }
        }
        if (putStatic instanceof FieldInsnNode target
                && looksLikeResourceLocationDescriptor(target.desc)) {
            ArrayList<String> strings = new ArrayList<>();
            int scan = 24;
            for (AbstractInsnNode previous = putStatic.getPrevious(); previous != null && scan-- > 0;
                 previous = previous.getPrevious()) {
                if (previous instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                    strings.add(0, value);
                }
                if (previous.getOpcode() == Opcodes.PUTSTATIC) break;
            }
            if (strings.size() >= 2) {
                String namespace = strings.get(strings.size() - 2);
                String path = strings.get(strings.size() - 1);
                if (namespace.matches("[a-z0-9_.-]+") && explicitModelPath(path)) {
                    return namespace + ':' + normalizeLiteral(path, "block");
                }
            }
            return null;
        }
        String literal = null;
        String factory = null;
        int remaining = 14;
        for (AbstractInsnNode previous = putStatic.getPrevious(); previous != null && remaining-- > 0;
             previous = previous.getPrevious()) {
            if (factory == null && previous instanceof MethodInsnNode call) factory = call.name;
            if (previous instanceof LdcInsnNode ldc && ldc.cst instanceof String value
                    && looksLikeModelPath(value, factory)) {
                literal = value;
                break;
            }
            if (previous.getOpcode() == Opcodes.PUTSTATIC) break;
        }
        if (literal == null) return null;
        return normalizeLiteral(literal, factory);
    }

    private static boolean looksLikeModelPath(String value, String factory) {
        if (value == null || value.length() < 3 || value.length() > 220 || value.indexOf(' ') >= 0) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".ogg") || lower.endsWith(".json")
                || lower.startsWith("gui.") || lower.startsWith("key.")) return false;
        if (value.contains("/") || value.contains(":")) return true;
        // A model-typed PUTSTATIC is already strong ownership evidence. Some render libraries use
        // leaf resource ids ("shaft_half", "cogwheel", ...) rather than namespaced/path strings;
        // rejecting those made otherwise explicit renderer partials invisible. Require a nearby
        // factory call and a ResourceLocation-safe leaf so arbitrary UI/log strings still cannot
        // become model refs.
        return factory != null && !factory.isBlank()
                && value.matches("[a-z0-9_.-]+");
    }

    private static String normalizeLiteral(String literal, String factory) {
        String ref = literal.replace('\\', '/');
        if (ref.endsWith(".json")) ref = ref.substring(0, ref.length() - 5);
        int models = ref.indexOf("models/");
        if (models >= 0) ref = ref.substring(0, models) + ref.substring(models + "models/".length());
        int colon = ref.indexOf(':');
        String namespace = colon >= 0 ? ref.substring(0, colon + 1) : "";
        String path = colon >= 0 ? ref.substring(colon + 1) : ref;
        if (!(path.startsWith("block/") || path.startsWith("item/") || path.startsWith("entity/"))) {
            String method = factory == null ? "" : factory.toLowerCase(Locale.ROOT);
            String prefix = method.contains("item") ? "item/"
                    : method.contains("entity") ? "entity/" : "block/";
            path = prefix + path;
        }
        return namespace + path;
    }

    private static String namespacedModelRef(String fallbackNamespace, String ref) {
        if (ref == null || ref.isBlank()) return null;
        int colon = ref.indexOf(':');
        if (colon >= 0) return ref;
        return fallbackNamespace + ':' + ref;
    }

    /** Resolves the small string-template shapes used by renderer-owned block model lookups. */
    private static String expandBlockModelRef(String namespace, String ref, String blockPath) {
        String normalized = namespacedModelRef(namespace, ref);
        if (normalized == null || blockPath == null || blockPath.isBlank()) return normalized;
        normalized = normalized.replace("%1$s", blockPath).replace("%s", blockPath);
        int marker = normalized.indexOf('\u0001');
        if (marker >= 0 && marker == normalized.lastIndexOf('\u0001')) {
            normalized = normalized.substring(0, marker) + blockPath + normalized.substring(marker + 1);
        }
        // An unresolved formatter/concat marker is not a concrete resource location.
        return normalized.indexOf('%') >= 0 || normalized.indexOf('\u0001') >= 0 ? null : normalized;
    }
}
