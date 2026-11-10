package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Recovers renderer paths that only exist while a block is part of a moving/virtual structure.
 *
 * <p>A movement-behaviour renderer is a different ownership domain from a normal block renderer:
 * its partials must not leak onto the stationary block. Registrate-style APIs make this relation
 * explicit in bytecode as {@code block("id", ...).onRegister(movementBehaviour(new X()))}; the
 * behaviour's render path then receives a MovementContext/contraption object and delegates into
 * one or more renderer helpers. This scanner keeps that relation as first-class data.</p>
 */
public final class ContraptionBytecodeScanner {

    public static final class MovementSurface {
        public String blockId;
        public String behaviourClass;
        /** Exact model refs read by the movement render call graph. */
        public final Set<String> modelRefs = new LinkedHashSet<>();
        /** Runtime types guarded by INSTANCEOF on the render path (carriage type, temp data, ...). */
        public final Set<String> contextTypes = new LinkedHashSet<>();
        /** Static fields on the movement context read by the renderer; useful for later state IR. */
        public final Set<String> contextFields = new LinkedHashSet<>();

        @Override public String toString() {
            return blockId + " -> " + behaviourClass + " models=" + modelRefs
                    + " context=" + contextTypes;
        }
    }

    public static final class BogeySurface {
        public String namespace;
        public String rendererClass;
        public final Set<String> modelRefs = new LinkedHashSet<>();
        /** Semantic variant keys derived from the concrete renderer type (small/large/style/etc.). */
        public final Set<String> variantKeys = new LinkedHashSet<>();

        @Override public String toString() {
            return rendererClass + " " + variantKeys + " -> " + modelRefs;
        }
    }

    /**
     * Renderer-owned shifted rotation pivot on a contraption entity.
     *
     * <p>Create's {@code toGlobalVector} normally matches {@code applyLocalTransforms}, but addon
     * entities can author an extra {@code translate(-pivot) -> rotate(q) -> translate(pivot)}
     * sequence only in the render path. The server cannot load PoseStack/Flywheel classes, so the
     * scanner preserves the small server-readable contract needed to reproduce that translation:</p>
     *
     * <ul>
     *   <li>the concrete entity class,</li>
     *   <li>a Direction field used to construct the pivot vector,</li>
     *   <li>the constant pivot distance, and</li>
     *   <li>a float-parameter quaternion getter used by the renderer rotation.</li>
     * </ul>
     */
    public static final class RendererPivotSurface {
        public String entityClass;
        public String directionField;
        public double pivotDistance;
        public String rotationMethod;
        public String rotationMethodDesc;
        public int confidence;

        @Override public String toString() {
            return entityClass + " pivot=" + directionField + '*' + pivotDistance
                    + " rotate=" + rotationMethod + rotationMethodDesc;
        }
    }

    public static final class Result {
        public final List<MovementSurface> movements = new ArrayList<>();
        public final List<BogeySurface> bogeys = new ArrayList<>();
        public final List<RendererPivotSurface> rendererPivots = new ArrayList<>();
    }

    private ContraptionBytecodeScanner() {}

    public static Result scan(Map<String, ClassNode> nodes, String modId,
                              Map<String, String> modelFields) {
        Result result = new Result();
        if (nodes == null || nodes.isEmpty()) return result;
        Map<String, String> fields = modelFields == null ? Map.of() : modelFields;
        scanMovementRegistrations(nodes, modId, fields, result.movements);
        scanBogeyRenderers(nodes, modId, fields, result.bogeys);
        scanRendererPivots(nodes, result.rendererPivots);
        return result;
    }

    /** Most-specific scanner-proven renderer pivot for a live entity class hierarchy. */
    public static RendererPivotSurface rendererPivotFor(Collection<ModScanner.Profile> profiles,
                                                         Class<?> runtimeClass) {
        if (profiles == null || runtimeClass == null) return null;
        for (Class<?> type = runtimeClass; type != null && type != Object.class;
             type = type.getSuperclass()) {
            String internalName = type.getName().replace('.', '/');
            for (ModScanner.Profile profile : profiles) {
                RendererPivotSurface found = rendererPivotFor(profile, internalName);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static RendererPivotSurface rendererPivotFor(ModScanner.Profile profile,
                                                          String internalName) {
        if (profile == null || internalName == null) return null;
        if (profile.contraptionRendererPivots != null) {
            for (RendererPivotSurface surface : profile.contraptionRendererPivots) {
                if (surface != null && internalName.equals(surface.entityClass)) return surface;
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                RendererPivotSurface found = rendererPivotFor(child, internalName);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Finds the common renderer sequence
     * {@code center(); translate(pivot.scale(-1)); rotate(quaternion); translate(pivot); uncenter()}.
     * The accepted pivot must be a Direction vector multiplied by one positive constant. Requiring
     * both translations to reuse the same local variable keeps this evidence narrow and avoids
     * treating ordinary model offsets as a contraption-origin correction.
     */
    private static void scanRendererPivots(Map<String, ClassNode> nodes,
                                           List<RendererPivotSurface> output) {
        for (ClassNode node : nodes.values()) {
            if (node == null || node.methods == null) continue;
            for (MethodNode method : node.methods) {
                if (method.instructions == null || method.desc == null
                        || !method.desc.startsWith("(Lcom/mojang/blaze3d/vertex/PoseStack;F)")) {
                    continue;
                }
                RendererPivotSurface surface = rendererPivot(node, method);
                if (surface != null) output.add(surface);
            }
        }
    }

    private static RendererPivotSurface rendererPivot(ClassNode node, MethodNode method) {
        MethodInsnNode firstTranslate = null, quaternionRotate = null, secondTranslate = null;
        boolean centered = false, uncentered = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            String lower = call.name == null ? "" : call.name.toLowerCase(Locale.ROOT);
            if ("center".equals(lower)) {
                centered = true;
                continue;
            }
            if (!centered) continue;
            if ("translate".equals(lower) && call.desc != null
                    && call.desc.startsWith("(Lnet/minecraft/world/phys/Vec3;)")) {
                if (firstTranslate == null) firstTranslate = call;
                else if (quaternionRotate != null && secondTranslate == null) secondTranslate = call;
                continue;
            }
            if (firstTranslate != null && quaternionRotate == null && "rotate".equals(lower)
                    && call.desc != null && call.desc.contains("Lorg/joml/Quaternion")) {
                quaternionRotate = call;
                continue;
            }
            if (secondTranslate != null && "uncenter".equals(lower)) {
                uncentered = true;
                break;
            }
        }
        if (!centered || !uncentered || firstTranslate == null || quaternionRotate == null
                || secondTranslate == null) return null;

        int pivotLocal = pivotLocal(firstTranslate, true);
        if (pivotLocal < 0 || pivotLocal(secondTranslate, false) != pivotLocal) return null;
        AbstractInsnNode pivotStore = previousStore(firstTranslate, pivotLocal);
        if (pivotStore == null) return null;
        Double distance = creationScale(pivotStore);
        if (distance == null || !(distance > 0.0) || !Double.isFinite(distance)) return null;

        FieldInsnNode direction = uniqueDirectionField(method, pivotStore);
        MethodInsnNode rotation = quaternionProducer(quaternionRotate, node.name);
        if (direction == null || rotation == null) return null;

        RendererPivotSurface surface = new RendererPivotSurface();
        surface.entityClass = node.name;
        surface.directionField = direction.name;
        surface.pivotDistance = distance;
        surface.rotationMethod = rotation.name;
        surface.rotationMethodDesc = rotation.desc;
        surface.confidence = 100;
        return surface;
    }

    private static int pivotLocal(MethodInsnNode translate, boolean requireNegation) {
        int remaining = 10;
        int local = -1;
        boolean negated = false;
        for (AbstractInsnNode cursor = translate.getPrevious(); cursor != null && remaining-- > 0;
             cursor = cursor.getPrevious()) {
            if (cursor instanceof MethodInsnNode call && "scale".equals(call.name)
                    && call.owner.endsWith("/Vec3") && call.desc.startsWith("(D)")) {
                Double constant = numericConstant(previousReal(cursor));
                if (constant != null && Math.abs(constant + 1.0) < 1.0e-9) negated = true;
            }
            if (cursor instanceof VarInsnNode var && var.getOpcode() == Opcodes.ALOAD) {
                local = var.var;
                break;
            }
        }
        if (local < 0 || requireNegation && !negated || !requireNegation && negated) return -1;
        return local;
    }

    private static AbstractInsnNode previousStore(AbstractInsnNode before, int local) {
        int remaining = 80;
        for (AbstractInsnNode cursor = before.getPrevious(); cursor != null && remaining-- > 0;
             cursor = cursor.getPrevious()) {
            if (cursor instanceof VarInsnNode var && var.getOpcode() == Opcodes.ASTORE
                    && var.var == local) return cursor;
        }
        return null;
    }

    private static Double creationScale(AbstractInsnNode store) {
        int remaining = 10;
        for (AbstractInsnNode cursor = store.getPrevious(); cursor != null && remaining-- > 0;
             cursor = cursor.getPrevious()) {
            if (!(cursor instanceof MethodInsnNode call) || !"scale".equals(call.name)
                    || !call.owner.endsWith("/Vec3") || !call.desc.startsWith("(D)")) continue;
            Double value = numericConstant(previousReal(cursor));
            if (value != null && value > 0.0) return value;
        }
        return null;
    }

    private static FieldInsnNode uniqueDirectionField(MethodNode method, AbstractInsnNode before) {
        FieldInsnNode found = null;
        for (AbstractInsnNode cursor = method.instructions.getFirst(); cursor != null && cursor != before;
             cursor = cursor.getNext()) {
            if (!(cursor instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                    || !"Lnet/minecraft/core/Direction;".equals(field.desc)) continue;
            if (found != null && (!found.owner.equals(field.owner) || !found.name.equals(field.name))) {
                return null;
            }
            found = field;
        }
        return found;
    }

    private static MethodInsnNode quaternionProducer(MethodInsnNode rotate, String entityClass) {
        int remaining = 10;
        for (AbstractInsnNode cursor = rotate.getPrevious(); cursor != null && remaining-- > 0;
             cursor = cursor.getPrevious()) {
            if (!(cursor instanceof MethodInsnNode call) || call.desc == null) continue;
            Type returned;
            try {
                returned = Type.getReturnType(call.desc);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (returned.getSort() != Type.OBJECT
                    || !returned.getInternalName().startsWith("org/joml/Quaternion")) continue;
            if (!call.owner.equals(entityClass) || !call.desc.startsWith("(F)")) return null;
            return call;
        }
        return null;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        for (AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious(); cursor != null;
             cursor = cursor.getPrevious()) {
            if (cursor.getOpcode() >= 0) return cursor;
        }
        return null;
    }

    private static Double numericConstant(AbstractInsnNode instruction) {
        if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Number number) {
            return number.doubleValue();
        }
        if (instruction == null) return null;
        return switch (instruction.getOpcode()) {
            case Opcodes.DCONST_0, Opcodes.FCONST_0, Opcodes.ICONST_0 -> 0.0;
            case Opcodes.DCONST_1, Opcodes.FCONST_1, Opcodes.ICONST_1 -> 1.0;
            case Opcodes.ICONST_M1 -> -1.0;
            default -> null;
        };
    }

    /** Scanner-owned movement renderer for one concrete runtime block id. */
    public static MovementSurface movementForBlock(Collection<ModScanner.Profile> profiles,
                                                   String blockId) {
        if (profiles == null || blockId == null) return null;
        for (ModScanner.Profile profile : profiles) {
            MovementSurface found = movementForBlock(profile, blockId);
            if (found != null) return found;
        }
        return null;
    }

    private static MovementSurface movementForBlock(ModScanner.Profile profile, String blockId) {
        if (profile == null) return null;
        if (profile.contraptionMovements != null) {
            for (MovementSurface movement : profile.contraptionMovements) {
                if (movement != null && blockId.equals(movement.blockId)) return movement;
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                MovementSurface found = movementForBlock(child, blockId);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Scanner-owned articulated renderer families in one asset namespace. */
    public static List<BogeySurface> bogeySurfaces(Collection<ModScanner.Profile> profiles,
                                                   String namespace) {
        if (profiles == null || namespace == null) return List.of();
        ArrayList<BogeySurface> out = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectBogeys(profile, namespace, out);
        return List.copyOf(out);
    }

    private static void collectBogeys(ModScanner.Profile profile, String namespace,
                                      List<BogeySurface> out) {
        if (profile == null) return;
        if (profile.bogeyRenderers != null) {
            for (BogeySurface surface : profile.bogeyRenderers) {
                if (surface == null) continue;
                String owner = surface.namespace == null || surface.namespace.isBlank()
                        ? profile.modId : surface.namespace;
                if (namespace.equals(owner)) out.add(surface);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile nested : profile.nested) collectBogeys(nested, namespace, out);
        }
    }

    private static void scanMovementRegistrations(Map<String, ClassNode> nodes, String modId,
                                                  Map<String, String> modelFields,
                                                  List<MovementSurface> output) {
        Map<String, MovementSurface> unique = new LinkedHashMap<>();
        for (ClassNode owner : nodes.values()) {
            if (owner == null || owner.methods == null) continue;
            for (MethodNode method : owner.methods) {
                if (method.instructions == null) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode call)
                            || !movementRegistrationCall(call)) continue;
                    String behaviour = movementBehaviourBefore(instruction, nodes);
                    String block = registeredBlockBefore(instruction, modId);
                    if (behaviour == null || block == null) continue;
                    MovementSurface surface = unique.computeIfAbsent(block + '\n' + behaviour,
                            ignored -> {
                                MovementSurface created = new MovementSurface();
                                created.blockId = block;
                                created.behaviourClass = behaviour;
                                return created;
                            });
                    collectMovementRenderSurface(behaviour, nodes, modelFields, surface);
                }
            }
        }
        output.addAll(unique.values());
    }

    private static boolean movementRegistrationCall(MethodInsnNode call) {
        if (call == null || call.desc == null) return false;
        String owner = call.owner == null ? "" : call.owner.toLowerCase(Locale.ROOT);
        String name = call.name == null ? "" : call.name.toLowerCase(Locale.ROOT);
        return name.contains("movementbehaviour") || name.contains("movementbehavior")
                || owner.contains("behaviour/movement/movementbehaviour")
                || owner.contains("behavior/movement/movementbehavior");
    }

    private static String movementBehaviourBefore(AbstractInsnNode registration,
                                                  Map<String, ClassNode> nodes) {
        int remaining = 24;
        for (AbstractInsnNode cursor = registration.getPrevious(); cursor != null && remaining-- > 0;
             cursor = cursor.getPrevious()) {
            if (cursor instanceof MethodInsnNode call) {
                if (call.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(call.name)
                        && movementBehaviourType(call.owner, nodes)) return call.owner;
                try {
                    Type returned = Type.getReturnType(call.desc);
                    if (returned.getSort() == Type.OBJECT
                            && movementBehaviourType(returned.getInternalName(), nodes)) {
                        return returned.getInternalName();
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (cursor instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && movementBehaviourType(type.desc, nodes)) return type.desc;
        }
        return null;
    }

    private static boolean movementBehaviourType(String type, Map<String, ClassNode> nodes) {
        if (type == null) return false;
        ClassNode node = nodes == null ? null : nodes.get(type);
        if (node == null) return false;
        for (String itf : node.interfaces) {
            String lower = itf.toLowerCase(Locale.ROOT);
            if (lower.contains("movementbehaviour") || lower.contains("movementbehavior")) return true;
        }
        if (node.methods != null) for (MethodNode method : node.methods) {
            String desc = method.desc == null ? "" : method.desc;
            if (desc.contains("MovementContext") && method.name.toLowerCase(Locale.ROOT).contains("render")) {
                return true;
            }
        }
        String parent = node.superName;
        return parent != null && !"java/lang/Object".equals(parent)
                && movementBehaviourType(parent, nodes);
    }

    /** Finds the block(...) builder invocation belonging to the current fluent registration. */
    private static String registeredBlockBefore(AbstractInsnNode movementCall, String modId) {
        int remaining = 180;
        for (AbstractInsnNode cursor = movementCall.getPrevious(); cursor != null && remaining-- > 0;
             cursor = cursor.getPrevious()) {
            if (!(cursor instanceof MethodInsnNode call) || call.desc == null) continue;
            String name = call.name == null ? "" : call.name.toLowerCase(Locale.ROOT);
            if (!"block".equals(name) || !call.desc.contains("Ljava/lang/String;")) continue;
            String literal = nearestStringBefore(cursor, 12);
            if (literal == null || literal.isBlank() || literal.length() > 160) continue;
            if (literal.contains(" ") || literal.contains(".")) continue;
            return literal.indexOf(':') >= 0 ? literal
                    : (modId == null || modId.isBlank() ? literal : modId + ':' + literal);
        }
        return null;
    }

    private static String nearestStringBefore(AbstractInsnNode instruction, int max) {
        int remaining = max;
        for (AbstractInsnNode cursor = instruction.getPrevious(); cursor != null && remaining-- > 0;
             cursor = cursor.getPrevious()) {
            if (cursor instanceof LdcInsnNode ldc && ldc.cst instanceof String value) return value;
        }
        return null;
    }

    private static void collectMovementRenderSurface(String behaviour, Map<String, ClassNode> nodes,
                                                     Map<String, String> modelFields,
                                                     MovementSurface surface) {
        ClassNode node = nodes.get(behaviour);
        if (node == null || node.methods == null) return;
        for (MethodNode method : node.methods) {
            String desc = method.desc == null ? "" : method.desc;
            String name = method.name == null ? "" : method.name.toLowerCase(Locale.ROOT);
            if (!desc.contains("MovementContext") || !name.contains("render")) continue;
            collectContextEvidence(method, surface);
            collectMovementModels(behaviour, method, nodes, modelFields, surface,
                    new HashSet<>(), 0);
        }
    }

    private static void collectContextEvidence(MethodNode method, MovementSurface surface) {
        if (method == null || method.instructions == null) return;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF) {
                surface.contextTypes.add(type.desc);
            } else if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && (field.owner.contains("MovementContext") || field.owner.contains("Contraption"))) {
                surface.contextFields.add(field.owner + '.' + field.name + ':' + field.desc);
            }
        }
    }

    private static void collectMovementModels(String owner, MethodNode method,
                                              Map<String, ClassNode> nodes,
                                              Map<String, String> modelFields,
                                              MovementSurface surface,
                                              Set<String> visiting, int depth) {
        if (method == null || method.instructions == null || depth > 6) return;
        String key = owner + '#' + method.name + method.desc;
        if (!visiting.add(key)) return;
        try {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) {
                    String ref = modelFields.get(field.owner + '.' + field.name);
                    if (ref != null) surface.modelRefs.add(ref);
                    continue;
                }
                if (!(instruction instanceof MethodInsnNode call) || "<init>".equals(call.name)) continue;
                ClassNode calleeOwner = nodes.get(call.owner);
                if (calleeOwner == null) continue;
                MethodNode callee = findMethod(calleeOwner, call.name, call.desc);
                if (callee == null || !movementRenderHelper(call, callee)) continue;
                collectContextEvidence(callee, surface);
                collectMovementModels(call.owner, callee, nodes, modelFields, surface,
                        visiting, depth + 1);
            }
        } finally {
            visiting.remove(key);
        }
    }

    private static boolean movementRenderHelper(MethodInsnNode call, MethodNode method) {
        String owner = call.owner == null ? "" : call.owner.toLowerCase(Locale.ROOT);
        String descriptor = (call.desc == null ? "" : call.desc)
                + (method.desc == null ? "" : method.desc);
        String name = ((call.name == null ? "" : call.name) + ' '
                + (method.name == null ? "" : method.name)).toLowerCase(Locale.ROOT);
        return owner.contains("renderer") || descriptor.contains("MovementContext")
                || descriptor.contains("ContraptionMatrices") || descriptor.contains("VirtualRenderWorld")
                || descriptor.contains("MultiBufferSource") || descriptor.contains("PoseStack")
                || name.contains("render");
    }

    private static void scanBogeyRenderers(Map<String, ClassNode> nodes, String modId,
                                           Map<String, String> modelFields,
                                           List<BogeySurface> output) {
        for (ClassNode node : nodes.values()) {
            if (node == null || node.methods == null) continue;
            BogeySurface surface = null;
            for (MethodNode method : node.methods) {
                if (!bogeyRenderShape(method)) continue;
                if (surface == null) {
                    surface = new BogeySurface();
                    surface.namespace = modId;
                    surface.rendererClass = node.name;
                    surface.variantKeys.addAll(rendererVariantKeys(node.name));
                }
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof FieldInsnNode field)
                            || field.getOpcode() != Opcodes.GETSTATIC) continue;
                    String ref = modelFields.get(field.owner + '.' + field.name);
                    if (ref != null) surface.modelRefs.add(ref);
                }
            }
            if (surface != null && !surface.modelRefs.isEmpty()) output.add(surface);
        }
    }

    private static Set<String> rendererVariantKeys(String className) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (className == null) return keys;
        String leaf = className.substring(className.lastIndexOf('/') + 1);
        String inner = leaf.contains("$") ? leaf.substring(leaf.lastIndexOf('$') + 1) : leaf;
        addVariantToken(keys, inner);
        if (!inner.equals(leaf)) addVariantToken(keys, leaf.substring(0, leaf.lastIndexOf('$')));
        return keys;
    }

    private static void addVariantToken(Set<String> keys, String raw) {
        if (raw == null) return;
        String spaced = raw.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replace('$', '_').toLowerCase(Locale.ROOT);
        for (String token : spaced.split("[^a-z0-9]+")) {
            if (token.isBlank() || token.equals("renderer") || token.equals("render")
                    || token.equals("bogey") || token.equals("standard") || token.equals("visual")) continue;
            keys.add(token);
        }
    }

    private static boolean bogeyRenderShape(MethodNode method) {
        if (method == null || method.desc == null) return false;
        String desc = method.desc;
        return desc.contains("Lnet/minecraft/nbt/CompoundTag;")
                && desc.contains("Lcom/mojang/blaze3d/vertex/PoseStack;")
                && desc.contains("Lnet/minecraft/client/renderer/MultiBufferSource;")
                && Type.getReturnType(desc).getSort() == Type.VOID;
    }

    private static MethodNode findMethod(ClassNode node, String name, String desc) {
        if (node == null || node.methods == null) return null;
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }
}
