package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Semantic kinetic/render scanner.
 *
 * <p>This scanner does not decide that a member is kinetic because its name contains "speed",
 * "angle", "belt" or another mod-specific word. Instead it follows numeric values from a concrete
 * BlockEntity argument through renderer bytecode and classifies them by the rendering operation
 * they reach: a time-driven rotation, a direct rotation, a translation, or UV movement. The exact
 * field/method chain is persisted and runtime code invokes only that proven chain.</p>
 *
 * <p>The same principle is used for writable controls. A nested numeric behavior is considered a
 * kinetic control only when its getter/setter operate on the same backing value, the setter owns a
 * callback/sync side effect, and the BlockEntity projects that value into code which also touches
 * the backing state of a scanner-proven rotational source.</p>
 */
public final class KineticBytecodeScanner {

    private static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    private static final int MAX_RENDER_METHODS = 160;
    private static final int MAX_CALL_DEPTH = 8;
    /** Keep symbolic analysis finite even for renderers with large branch/loop fan-out. */
    private static final int MAX_PROVENANCE = 32;

    private KineticBytecodeScanner() {}

    public enum Operation {
        ROTATION_RATE,
        ROTATION_ANGLE,
        LINEAR_OFFSET,
        UV_SCROLL
    }

    /** One source -> renderer operation relation. valueSource is an exact runtime access chain. */
    public static final class Signal {
        public Operation operation;
        public String valueSource;
        public String rendererClass;
        public String rendererMethod;
        /** Exact partial/model ref when object provenance remains unambiguous. */
        public String modelRef;
        public int confidence;

        @Override public String toString() {
            return operation + " " + valueSource + (modelRef == null ? "" : " -> " + modelRef);
        }
    }

    /** Scanner-owned runtime contract for a concrete BlockEntity type (or a kinetic base type). */
    public static final class Contract {
        public String targetType;
        public final List<Signal> signals = new ArrayList<>();

        /** Exact target field containing a writable numeric behavior. */
        public String controlField;
        public String controlGetter;
        public String controlSetter;
        /** Exact numeric fields read by the setter's clamp, when present. */
        public String controlMinSource;
        public String controlMaxSource;

        public boolean useful() {
            return !signals.isEmpty() || controlSetter != null;
        }

        public boolean has(Operation operation) {
            return signals.stream().anyMatch(signal -> signal.operation == operation);
        }
    }

    /** Compatibility overload used by focused tests/callers that do not need model ownership. */
    public static List<Contract> scan(Map<String, ClassNode> nodes,
                                      Collection<RendererBytecodeScanner.Surface> renderers) {
        return scan(nodes, renderers, Map.of());
    }

    public static List<Contract> scan(Map<String, ClassNode> nodes,
                                      Collection<RendererBytecodeScanner.Surface> renderers,
                                      Map<String, String> modelFields) {
        if (nodes == null || nodes.isEmpty()) return List.of();

        Map<String, Set<String>> scannerOwnedTargets = new HashMap<>();
        if (renderers != null) {
            for (RendererBytecodeScanner.Surface surface : renderers) {
                if (surface == null || surface.clazz == null || surface.blockEntityTypes == null) continue;
                scannerOwnedTargets.computeIfAbsent(surface.clazz, ignored -> new LinkedHashSet<>())
                        .addAll(surface.blockEntityTypes);
            }
        }

        LinkedHashMap<String, Contract> contracts = new LinkedHashMap<>();
        AnimationSemantics animationSemantics = AnimationSemantics.discover(nodes);
        SemanticAnalyzer semantic = new SemanticAnalyzer(nodes,
                modelFields == null ? Map.of() : modelFields, contracts, animationSemantics);

        List<ClassNode> ordered = nodes.values().stream()
                .filter(node -> node != null && node.name != null && node.methods != null)
                .sorted(Comparator.comparing(node -> node.name))
                .toList();
        for (ClassNode renderer : ordered) {
            for (MethodNode root : renderer.methods) {
                LinkedHashSet<String> targets = blockEntityArguments(root, nodes);
                boolean renderRoot = renderRoot(root, nodes);
                if (!renderRoot && targets.isEmpty()) continue;
                Set<String> owned = scannerOwnedTargets.get(renderer.name);
                if (owned != null) targets.addAll(owned);
                removeErasedTarget(targets);
                if (targets.isEmpty()) continue;
                if (!renderRoot && !semantic.mayReachRenderEffect(renderer, renderer.name, root,
                        targets, 0)) continue;
                semantic.scanRenderer(renderer, root, targets);
            }
        }

        semantic.discoverControls();
        return contracts.values().stream().filter(Contract::useful)
                .sorted(Comparator.comparing(contract -> contract.targetType == null ? "" : contract.targetType))
                .toList();
    }

    /** Most-specific-first contracts for a live runtime hierarchy. */
    public static List<Contract> contractsForTarget(Collection<ModScanner.Profile> profiles,
                                                    Collection<String> runtimeTypes) {
        if (profiles == null || runtimeTypes == null || runtimeTypes.isEmpty()) return List.of();
        ArrayList<Contract> out = new ArrayList<>();
        Set<Contract> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (String type : runtimeTypes) {
            if (type == null) continue;
            for (ModScanner.Profile profile : profiles) collect(profile, type, out, seen);
        }
        return List.copyOf(out);
    }

    private static void collect(ModScanner.Profile profile, String type, List<Contract> out,
                                Set<Contract> seen) {
        if (profile == null) return;
        if (profile.kineticContracts != null) {
            for (Contract contract : profile.kineticContracts) {
                if (contract == null || !type.equals(contract.targetType)) continue;
                if (seen.add(contract)) out.add(contract);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collect(child, type, out, seen);
        }
    }

    private enum Effect {
        ROTATION,
        ROTATION_RATE,
        ROTATION_ANGLE,
        TRANSLATION,
        UV
    }

    private record MethodRef(String owner, MethodNode method, int depth, Set<String> modelRefs) {}
    private record SummaryKey(String concrete, String owner, String name, String desc) {}
    private record ClampFields(String min, String max) {}
    private record ValuePair(MethodNode getter, MethodNode setter, FieldInsnNode storage,
                             ClampFields clamp, int sideEffectScore) {}
    private record ControlCandidate(FieldNode field, String behaviorType, ValuePair pair,
                                    MethodNode projection, int score) {}
    private record LayoutSlot(String name, int offset, int bytes, int components) {}

    /**
     * Learns shader/instance animation sinks from the rendering library's declared instance ABI.
     *
     * <p>Modern renderers such as Flywheel visuals do not call PoseStack.rotate every frame. They
     * write BlockEntity-derived values into instance-data fields and the vertex shader performs
     * the animation. The layout names and shader resource are stable ABI strings (unlike mod Java
     * member names), while the InstanceWriter lambda proves which Java field occupies each layout
     * slot. This lets the ordinary source-flow scanner treat those fields exactly like a render
     * transform sink without depending on Create, Flywheel class names, or deobfuscated setters.</p>
     */
    private static final class AnimationSemantics {
        private final Map<String, Effect> fieldEffects;
        private final Map<String, Effect> methodEffects;

        private AnimationSemantics(Map<String, Effect> fieldEffects,
                                   Map<String, Effect> methodEffects) {
            this.fieldEffects = fieldEffects;
            this.methodEffects = methodEffects;
        }

        static AnimationSemantics discover(Map<String, ClassNode> nodes) {
            if (nodes == null || nodes.isEmpty()) return new AnimationSemantics(Map.of(), Map.of());
            LinkedHashMap<String, Effect> fields = new LinkedHashMap<>();
            for (ClassNode node : nodes.values()) {
                if (node == null || node.methods == null) continue;
                for (MethodNode method : node.methods) {
                    if (!"<clinit>".equals(method.name) || method.instructions == null) continue;
                    ArrayList<AbstractInsnNode> segment = new ArrayList<>();
                    for (AbstractInsnNode instruction : method.instructions) {
                        segment.add(instruction);
                        if (!(instruction instanceof FieldInsnNode field)
                                || field.getOpcode() != Opcodes.PUTSTATIC
                                || !instanceTypeDescriptor(field.desc)) continue;
                        discoverSegment(node, segment, nodes, fields);
                        segment.clear();
                    }
                }
            }

            LinkedHashMap<String, Effect> methods = new LinkedHashMap<>();
            for (ClassNode node : nodes.values()) {
                if (node == null || node.methods == null) continue;
                for (MethodNode method : node.methods) {
                    LinkedHashSet<Effect> writes = new LinkedHashSet<>();
                    for (AbstractInsnNode instruction : method.instructions) {
                        if (!(instruction instanceof FieldInsnNode field)
                                || field.getOpcode() != Opcodes.PUTFIELD) continue;
                        Effect effect = fields.get(fieldKey(field.owner, field.name, field.desc));
                        if (effect != null) writes.add(effect);
                    }
                    if (writes.size() == 1 && hasNumericArgument(method.desc)) {
                        methods.put(methodKey(node.name, method.name, method.desc),
                                writes.iterator().next());
                    }
                }
            }
            return new AnimationSemantics(Map.copyOf(fields), Map.copyOf(methods));
        }

        Effect fieldEffect(FieldInsnNode field) {
            return field == null ? null : fieldEffects.get(fieldKey(field.owner, field.name, field.desc));
        }

        Effect methodEffect(MethodInsnNode call) {
            return call == null ? null : methodEffects.get(methodKey(call.owner, call.name, call.desc));
        }

        private static void discoverSegment(ClassNode owner, List<AbstractInsnNode> segment,
                                            Map<String, ClassNode> nodes,
                                            Map<String, Effect> out) {
            ArrayList<LayoutSlot> slots = new ArrayList<>();
            ArrayList<String> shaders = new ArrayList<>();
            int offset = 0;
            Handle writer = null;
            for (int i = 0; i < segment.size(); i++) {
                AbstractInsnNode instruction = segment.get(i);
                if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String literal) {
                    String lower = literal.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".vert") || lower.endsWith(".glsl")
                            || lower.endsWith(".vsh")) shaders.add(lower);
                }
                if (instruction instanceof InvokeDynamicInsnNode indy) {
                    Handle candidate = writerHandle(indy);
                    if (candidate != null) writer = candidate;
                }
                if (!(instruction instanceof MethodInsnNode call) || call.owner == null
                        || !call.owner.contains("/layout/LayoutBuilder")) continue;
                String kind = call.name == null ? "" : call.name.toLowerCase(Locale.ROOT);
                if (!kind.equals("scalar") && !kind.equals("vector") && !kind.equals("matrix")) continue;
                String name = nearestString(segment, i, 8);
                FieldInsnNode repr = nearestRepresentation(segment, i, 8);
                if (name == null || repr == null) continue;
                int components = kind.equals("scalar") ? 1 : nearestInt(segment, i, 5, 1);
                if (components <= 0) components = 1;
                int primitiveBytes = representationBytes(repr.name);
                int bytes = primitiveBytes * components * (kind.equals("matrix") ? components : 1);
                slots.add(new LayoutSlot(name, offset, bytes, components));
                offset += bytes;
            }
            if (writer == null || slots.isEmpty()) return;
            ClassNode writerOwner = nodes.get(writer.getOwner());
            MethodNode writerMethod = findMethod(writerOwner, writer.getName(), writer.getDesc());
            if (writerMethod == null) return;
            Type[] writerArgs;
            try {
                writerArgs = Type.getArgumentTypes(writer.getDesc());
            } catch (IllegalArgumentException ignored) {
                return;
            }
            if (writerArgs.length < 2 || writerArgs[1].getSort() != Type.OBJECT) return;
            String instanceType = writerArgs[1].getInternalName();
            for (AbstractInsnNode instruction : writerMethod.instructions) {
                if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                        || !instanceType.equals(field.owner) || !numeric(safeType(field.desc))) continue;
                Integer fieldOffset = memoryOffsetBefore(field);
                if (fieldOffset == null) continue;
                LayoutSlot slot = slotAt(slots, fieldOffset);
                Effect effect = slot == null ? null : layoutEffect(slot, slots, shaders);
                if (effect != null) out.put(fieldKey(field.owner, field.name, field.desc), effect);
            }
        }

        private static Effect layoutEffect(LayoutSlot slot, List<LayoutSlot> slots,
                                           List<String> shaders) {
            String name = slot.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            String shader = String.join(" ", shaders);
            boolean hasAxis = slots.stream().anyMatch(value -> normalized(value.name).contains("axis"));
            boolean hasRotation = slots.stream().anyMatch(value -> normalized(value.name).contains("rotation"));
            boolean hasUvShape = slots.stream().anyMatch(value -> normalized(value.name).contains("scale"))
                    && slots.stream().anyMatch(value -> normalized(value.name).contains("offset"));
            boolean rotational = shader.contains("rotat") || hasAxis && hasRotation;
            boolean scrolling = shader.contains("scroll") || shader.contains("uv")
                    || slot.components >= 2 && hasUvShape && !hasAxis;
            boolean translating = shader.contains("translat") || shader.contains("linear")
                    || shader.contains("move");

            if (rotational && (name.contains("speed") || name.contains("rate")
                    || name.contains("velocity"))) return Effect.ROTATION_RATE;
            if (rotational && (name.contains("angle") || name.contains("offset")
                    || name.contains("phase"))) return Effect.ROTATION_ANGLE;
            if (scrolling && (name.contains("speed") || name.contains("rate")
                    || name.contains("velocity") || name.contains("offset")
                    || name.contains("phase"))) return Effect.UV;
            if (translating && (name.contains("offset") || name.contains("position")
                    || name.equals("pos") || name.contains("progress"))) return Effect.TRANSLATION;
            return null;
        }

        private static String normalized(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        }

        private static LayoutSlot slotAt(List<LayoutSlot> slots, int offset) {
            for (LayoutSlot slot : slots) {
                if (offset >= slot.offset && offset < slot.offset + slot.bytes) return slot;
            }
            return null;
        }

        private static Handle writerHandle(InvokeDynamicInsnNode indy) {
            if (indy == null || indy.bsmArgs == null) return null;
            for (Object argument : indy.bsmArgs) {
                if (!(argument instanceof Handle handle)) continue;
                try {
                    Type[] args = Type.getArgumentTypes(handle.getDesc());
                    if (Type.getReturnType(handle.getDesc()) == Type.VOID_TYPE && args.length >= 2
                            && args[0].getSort() == Type.LONG && args[1].getSort() == Type.OBJECT) {
                        return handle;
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            return null;
        }

        private static String nearestString(List<AbstractInsnNode> code, int before, int limit) {
            for (int i = before - 1, remaining = limit; i >= 0 && remaining-- > 0; i--) {
                AbstractInsnNode instruction = code.get(i);
                if (instruction.getOpcode() < 0) continue;
                if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String value) return value;
            }
            return null;
        }

        private static FieldInsnNode nearestRepresentation(List<AbstractInsnNode> code, int before,
                                                           int limit) {
            for (int i = before - 1, remaining = limit; i >= 0 && remaining-- > 0; i--) {
                AbstractInsnNode instruction = code.get(i);
                if (instruction.getOpcode() < 0) continue;
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                        && field.owner != null && field.owner.contains("/layout/")
                        && field.owner.endsWith("Repr")) return field;
            }
            return null;
        }

        private static int nearestInt(List<AbstractInsnNode> code, int before, int limit,
                                      int fallback) {
            for (int i = before - 1, remaining = limit; i >= 0 && remaining-- > 0; i--) {
                AbstractInsnNode instruction = code.get(i);
                if (instruction.getOpcode() < 0) continue;
                Integer value = integerValue(instruction);
                if (value != null) return value;
            }
            return fallback;
        }

        private static int representationBytes(String repr) {
            String upper = repr == null ? "" : repr.toUpperCase(Locale.ROOT);
            if (upper.contains("BYTE")) return 1;
            if (upper.contains("SHORT") || upper.contains("HALF")) return 2;
            if (upper.contains("DOUBLE") || upper.contains("LONG")) return 8;
            return 4;
        }

        private static Integer memoryOffsetBefore(AbstractInsnNode field) {
            Long constant = null;
            int remaining = 8;
            for (AbstractInsnNode cursor = previousReal(field); cursor != null && remaining-- > 0;
                 cursor = previousReal(cursor)) {
                if (cursor instanceof VarInsnNode load && load.getOpcode() == Opcodes.LLOAD
                        && load.var == 0) return constant == null ? 0 : Math.toIntExact(constant);
                Long value = longValue(cursor);
                if (value != null) constant = value;
                if (cursor instanceof MethodInsnNode || cursor.getOpcode() == Opcodes.PUTFIELD
                        || cursor.getOpcode() == Opcodes.GETFIELD) break;
            }
            return null;
        }

        private static boolean instanceTypeDescriptor(String descriptor) {
            return descriptor != null && descriptor.contains("InstanceType;");
        }

        private static boolean hasNumericArgument(String descriptor) {
            try {
                for (Type arg : Type.getArgumentTypes(descriptor)) if (numeric(arg)) return true;
            } catch (IllegalArgumentException ignored) {
            }
            return false;
        }
    }

    /** Interprocedural scanner shared by all renderer roots in one archive. */
    private static final class SemanticAnalyzer {
        private final Map<String, ClassNode> nodes;
        private final Map<String, String> modelFields;
        private final Map<String, Contract> contracts;
        private final AnimationSemantics animationSemantics;
        private final Map<SummaryKey, FlowValue> numericSummaries = new HashMap<>();
        private final Set<SummaryKey> numericVisiting = new HashSet<>();
        private final Map<SummaryKey, FlowValue> modelSummaries = new HashMap<>();
        private final Set<SummaryKey> modelVisiting = new HashSet<>();
        private final Map<SummaryKey, Map<Effect, Set<Integer>>> effectSummaries = new HashMap<>();
        private final Set<SummaryKey> effectVisiting = new HashSet<>();
        private final Map<SummaryKey, Boolean> renderReachability = new HashMap<>();
        private final Set<SummaryKey> renderReachabilityVisiting = new HashSet<>();

        SemanticAnalyzer(Map<String, ClassNode> nodes, Map<String, String> modelFields,
                         Map<String, Contract> contracts, AnimationSemantics animationSemantics) {
            this.nodes = nodes;
            this.modelFields = modelFields;
            this.contracts = contracts;
            this.animationSemantics = animationSemantics;
        }

        void scanRenderer(ClassNode concrete, MethodNode root, Set<String> targets) {
            ArrayDeque<MethodRef> queue = new ArrayDeque<>();
            LinkedHashSet<String> visited = new LinkedHashSet<>();
            queue.add(new MethodRef(concrete.name, root, 0, Set.of()));
            while (!queue.isEmpty() && visited.size() < MAX_RENDER_METHODS) {
                MethodRef ref = queue.removeFirst();
                String key = ref.owner + '#' + ref.method.name + ref.method.desc
                        + '@' + modelContextKey(ref.modelRefs);
                if (!visited.add(key)) continue;
                boolean timedRoot = ref.method == root && renderRoot(ref.method, nodes);
                scanSignals(concrete, ref.owner, ref.method, targets,
                        timedRoot, ref.modelRefs);
                if (ref.depth >= MAX_CALL_DEPTH) continue;
                Frame<FlowValue>[] frames = frames(concrete, ref.owner, ref.method, targets, timedRoot);
                int index = 0;
                for (AbstractInsnNode instruction : ref.method.instructions) {
                    Frame<FlowValue> frame = frames == null || index >= frames.length ? null : frames[index];
                    index++;
                    if (!(instruction instanceof MethodInsnNode call) || !traversable(call, targets)) continue;
                    MethodNode callee = dispatchMethod(concrete, call, nodes);
                    if (callee != null && mayReachRenderEffect(concrete, call.owner, callee,
                            targets, 0)) {
                        Set<String> callModels = modelRefs(frame, call);
                        if (callModels.isEmpty()) callModels = ref.modelRefs;
                        queue.addLast(new MethodRef(call.owner, callee, ref.depth + 1, callModels));
                    }
                }
            }
        }

        private void scanSignals(ClassNode concrete, String owner, MethodNode method,
                                 Set<String> targets, boolean root, Set<String> inheritedModels) {
            Frame<FlowValue>[] frames = frames(concrete, owner, method, targets, root);
            if (frames == null) return;
            int index = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                Frame<FlowValue> frame = frames[index++];
                if (frame == null) continue;
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD) {
                    Effect fieldEffect = animationSemantics.fieldEffect(field);
                    if (fieldEffect != null) {
                        String model = modelRef(frame, field);
                        if (model == null) model = uniqueModel(inheritedModels);
                        recordSignals(targets, concrete.name, owner, method, fieldEffect,
                                fieldStoredValue(frame), model, 100);
                    }
                    continue;
                }
                if (!(instruction instanceof MethodInsnNode call)) continue;

                Effect primitive = primitiveEffect(call);
                if (primitive != null) {
                    for (int argument : effectNumericArguments(call, primitive)) {
                        FlowValue value = argumentValue(frame, call, argument);
                        String model = modelRef(frame, call);
                        if (model == null) model = uniqueModel(inheritedModels);
                        recordSignals(targets, concrete.name, owner, method,
                                primitive, value, model, 100);
                    }
                    continue;
                }

                MethodNode callee = dispatchMethod(concrete, call, nodes);
                if (callee == null || !mayReachRenderEffect(concrete, call.owner, callee,
                        targets, 0)) continue;
                Map<Effect, Set<Integer>> summary = effectSummary(concrete, call.owner, callee,
                        targets, 0);
                for (Map.Entry<Effect, Set<Integer>> effect : summary.entrySet()) {
                    for (int argument : effect.getValue()) {
                        FlowValue value = argumentValue(frame, call, argument);
                        String model = modelRef(frame, call);
                        if (model == null) model = uniqueModel(inheritedModels);
                        recordSignals(targets, concrete.name, owner, method,
                                effect.getKey(), value, model, 84);
                    }
                }
            }
        }

        private void recordSignals(Set<String> targets, String rendererClass, String owner,
                                   MethodNode method, Effect effect,
                                   FlowValue value, String modelRef, int confidence) {
            if (value == null || value.sources.isEmpty()) return;
            for (String source : value.sources) {
                Operation operation = switch (effect) {
                    // Rotation expressions commonly mix `time * rpm` with a direct phase/offset.
                    // Classify each source by whether that source actually participated in a
                    // multiplication with render time instead of promoting the whole expression.
                    case ROTATION -> value.timeScaledSources.contains(source)
                            ? Operation.ROTATION_RATE : Operation.ROTATION_ANGLE;
                    case ROTATION_RATE -> Operation.ROTATION_RATE;
                    case ROTATION_ANGLE -> Operation.ROTATION_ANGLE;
                    case TRANSLATION -> Operation.LINEAR_OFFSET;
                    case UV -> Operation.UV_SCROLL;
                };
                RootedSource rooted = RootedSource.parse(source);
                if (rooted == null || rooted.steps.isBlank()) continue;
                for (String target : targets) {
                    if (!compatibleTarget(target, rooted.rootType, nodes)) continue;
                    // Generic renderer signatures can erase an intersection target to its
                    // BlockEntity base. If the actual numeric source is invoked through an
                    // additional interface reached by CHECKCAST (bearing-style renderers), that
                    // interface is a real runtime requirement. Store the signal on it instead of
                    // leaking an angle/offset capability to every instance of the erased base.
                    String required = firstStepOwner(rooted.steps);
                    String contractTarget = required != null
                            && !compatibleTarget(rooted.rootType, required, nodes)
                            ? required : target;
                    Contract contract = contracts.computeIfAbsent(contractTarget, ignored -> {
                        Contract created = new Contract();
                        created.targetType = contractTarget;
                        return created;
                    });
                    addSignal(contract, operation, rooted.steps, rendererClass,
                            owner + '#' + method.name + method.desc, modelRef, confidence);
                }
            }
        }

        private Map<Effect, Set<Integer>> effectSummary(ClassNode concrete, String owner,
                                                        MethodNode method, Set<String> targets,
                                                        int depth) {
            if (depth > MAX_CALL_DEPTH) return Map.of();
            SummaryKey key = new SummaryKey(concrete.name, owner, method.name, method.desc);
            Map<Effect, Set<Integer>> cached = effectSummaries.get(key);
            if (cached != null) return cached;
            if (!effectVisiting.add(key)) return Map.of();
            try {
                LinkedHashMap<Effect, Set<Integer>> out = new LinkedHashMap<>();
                Frame<FlowValue>[] frames = frames(concrete, owner, method, targets, false);
                if (frames == null) return Map.of();
                int index = 0;
                for (AbstractInsnNode instruction : method.instructions) {
                    Frame<FlowValue> frame = frames[index++];
                    if (frame == null) continue;
                    if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD) {
                        Effect fieldEffect = animationSemantics.fieldEffect(field);
                        if (fieldEffect != null) {
                            addArgumentOrigins(out, fieldEffect, fieldStoredValue(frame));
                        }
                        continue;
                    }
                    if (!(instruction instanceof MethodInsnNode call)) continue;
                    Effect primitive = primitiveEffect(call);
                    if (primitive != null) {
                        for (int argument : effectNumericArguments(call, primitive)) {
                            addArgumentOrigins(out, primitive, argumentValue(frame, call, argument));
                        }
                        continue;
                    }
                    MethodNode callee = dispatchMethod(concrete, call, nodes);
                    if (callee == null || !traversable(call, targets)
                            || !mayReachRenderEffect(concrete, call.owner, callee, targets,
                            depth + 1)) continue;
                    Map<Effect, Set<Integer>> child = effectSummary(concrete, call.owner, callee,
                            targets, depth + 1);
                    for (Map.Entry<Effect, Set<Integer>> effect : child.entrySet()) {
                        for (int childArgument : effect.getValue()) {
                            addArgumentOrigins(out, effect.getKey(),
                                    argumentValue(frame, call, childArgument));
                        }
                    }
                }
                Map<Effect, Set<Integer>> frozen = freezeEffects(out);
                effectSummaries.put(key, frozen);
                return frozen;
            } finally {
                effectVisiting.remove(key);
            }
        }

        /** Cheap call-graph filter used before running ASM Analyzer on a helper method. */
        private boolean mayReachRenderEffect(ClassNode concrete, String owner, MethodNode method,
                                             Set<String> targets, int depth) {
            if (method == null || method.instructions == null || depth > MAX_CALL_DEPTH) return false;
            SummaryKey key = new SummaryKey(concrete.name, owner, method.name, method.desc);
            Boolean cached = renderReachability.get(key);
            if (cached != null) return cached;
            if (!renderReachabilityVisiting.add(key)) return false;
            try {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                            && animationSemantics.fieldEffect(field) != null) {
                        renderReachability.put(key, true);
                        return true;
                    }
                    if (!(instruction instanceof MethodInsnNode call)) continue;
                    if (primitiveEffect(call) != null) {
                        renderReachability.put(key, true);
                        return true;
                    }
                    if (!traversable(call, targets)) continue;
                    MethodNode child = dispatchMethod(concrete, call, nodes);
                    if (child != null && mayReachRenderEffect(concrete, call.owner, child,
                            targets, depth + 1)) {
                        renderReachability.put(key, true);
                        return true;
                    }
                }
                renderReachability.put(key, false);
                return false;
            } finally {
                renderReachabilityVisiting.remove(key);
            }
        }

        private void addArgumentOrigins(Map<Effect, Set<Integer>> out, Effect effect,
                                        FlowValue value) {
            if (value == null) return;
            for (String source : value.sources) {
                if (source.startsWith("A#")) {
                    try {
                        int argument = Integer.parseInt(source.substring(2));
                        out.computeIfAbsent(effect, ignored -> new LinkedHashSet<>()).add(argument);
                    } catch (NumberFormatException ignored) {
                    }
                    continue;
                }
                RootedSource rooted = RootedSource.parse(source);
                if (rooted != null) out.computeIfAbsent(effect, ignored -> new LinkedHashSet<>())
                        .add(rooted.rootArgument);
            }
        }

        private Effect primitiveEffect(MethodInsnNode call) {
            Effect instance = animationSemantics.methodEffect(call);
            return instance == null ? primitiveRenderEffect(call) : instance;
        }

        private Map<Effect, Set<Integer>> freezeEffects(Map<Effect, Set<Integer>> input) {
            LinkedHashMap<Effect, Set<Integer>> out = new LinkedHashMap<>();
            for (Map.Entry<Effect, Set<Integer>> entry : input.entrySet()) {
                out.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
            return Map.copyOf(out);
        }

        private Frame<FlowValue>[] frames(ClassNode concrete, String owner, MethodNode method,
                                          Set<String> targets, boolean root) {
            try {
                return new Analyzer<>(new FlowInterpreter(this, concrete, method, targets, root))
                        .analyze(owner, method);
            } catch (AnalyzerException | RuntimeException ignored) {
                return null;
            }
        }

        FlowValue numericSummary(ClassNode concrete, MethodInsnNode call, Set<String> targets,
                                 int depth) {
            if (call == null || depth > MAX_CALL_DEPTH || targetReceiverCall(call, targets)) return null;
            MethodNode method = dispatchMethod(concrete, call, nodes);
            if (method == null || !numeric(Type.getReturnType(method.desc))) return null;
            SummaryKey key = new SummaryKey(concrete.name, call.owner, method.name, method.desc);
            FlowValue cached = numericSummaries.get(key);
            if (cached != null) return cached;
            if (!numericVisiting.add(key)) return null;
            try {
                Frame<FlowValue>[] frames = frames(concrete, call.owner, method, targets, false);
                if (frames == null) return null;
                FlowValue result = null;
                int index = 0;
                for (AbstractInsnNode instruction : method.instructions) {
                    int opcode = instruction.getOpcode();
                    Frame<FlowValue> frame = frames[index++];
                    if (frame == null || !numericReturnOpcode(opcode) || frame.getStackSize() == 0) continue;
                    FlowValue value = frame.getStack(frame.getStackSize() - 1);
                    result = FlowValue.merge(result, value);
                }
                if (result != null) numericSummaries.put(key, result);
                return result;
            } finally {
                numericVisiting.remove(key);
            }
        }

        /** Summarises only render-model provenance returned through helper methods. Numeric/source
         * semantics stay in {@link #numericSummary}; this path exists so a PartialModel returned by
         * getX(), wrapped into a render buffer, and later transformed still owns that transform. */
        FlowValue modelSummary(ClassNode concrete, MethodInsnNode call, Set<String> targets,
                               int depth) {
            if (call == null || depth > MAX_CALL_DEPTH) return null;
            Type resultType;
            try {
                resultType = Type.getReturnType(call.desc);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
            if (resultType.getSort() != Type.OBJECT && resultType.getSort() != Type.ARRAY) return null;
            MethodNode method = dispatchMethod(concrete, call, nodes);
            if (method == null) return null;
            SummaryKey key = new SummaryKey(concrete.name, call.owner, method.name, method.desc);
            FlowValue cached = modelSummaries.get(key);
            if (cached != null) return cached;
            if (!modelVisiting.add(key)) return null;
            try {
                Frame<FlowValue>[] frames = frames(concrete, call.owner, method, targets, false);
                if (frames == null) return null;
                FlowValue result = null;
                int index = 0;
                for (AbstractInsnNode instruction : method.instructions) {
                    Frame<FlowValue> frame = frames[index++];
                    if (frame == null || instruction.getOpcode() != Opcodes.ARETURN
                            || frame.getStackSize() == 0) continue;
                    FlowValue value = frame.getStack(frame.getStackSize() - 1);
                    if (value == null || value.modelRefs.isEmpty()) continue;
                    result = FlowValue.merge(result, value);
                }
                if (result != null && !result.modelRefs.isEmpty()) modelSummaries.put(key, result);
                return result;
            } finally {
                modelVisiting.remove(key);
            }
        }

        /**
         * Finds writable numeric behaviors correlated with scanner-proven rotational state.
         * No behavior/member names participate in the decision.
         */
        void discoverControls() {
            List<String> kineticRoots = contracts.values().stream()
                    .filter(contract -> contract.has(Operation.ROTATION_RATE)
                            || contract.has(Operation.UV_SCROLL))
                    .map(contract -> contract.targetType)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (kineticRoots.isEmpty()) return;

            List<ClassNode> candidates = nodes.values().stream()
                    .filter(node -> node != null && node.name != null && node.fields != null
                            && isBlockEntityType(node.name, nodes)
                            && kineticRoots.stream().anyMatch(root -> compatibleTarget(node.name, root, nodes)))
                    .sorted(Comparator.comparing(node -> node.name))
                    .toList();
            for (ClassNode target : candidates) {
                Set<String> networkFields = kineticBackingFields(target.name);
                if (networkFields.isEmpty()) continue;
                ControlCandidate best = null;
                for (FieldNode field : target.fields) {
                    Type type;
                    try {
                        type = Type.getType(field.desc);
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }
                    if (type.getSort() != Type.OBJECT) continue;
                    ClassNode behavior = nodes.get(type.getInternalName());
                    if (behavior == null || behavior.methods == null) continue;
                    for (ValuePair pair : numericPairs(behavior)) {
                        MethodNode projection = projectionMethod(target, field, behavior, pair.getter);
                        if (projection == null) continue;
                        int score = pair.sideEffectScore + projectionCorrelation(target, projection, networkFields);
                        if (score < 100) continue;
                        ControlCandidate candidate = new ControlCandidate(field, behavior.name, pair,
                                projection, score);
                        if (best == null || candidate.score > best.score) best = candidate;
                    }
                }
                if (best == null) continue;
                Contract contract = contracts.computeIfAbsent(target.name, ignored -> {
                    Contract created = new Contract();
                    created.targetType = target.name;
                    return created;
                });
                contract.controlField = fieldSpec(target.name, best.field);
                contract.controlGetter = methodSpec(best.behaviorType, best.pair.getter);
                contract.controlSetter = methodSpec(best.behaviorType, best.pair.setter);
                if (best.pair.clamp != null) {
                    contract.controlMinSource = best.pair.clamp.min;
                    contract.controlMaxSource = best.pair.clamp.max;
                }
            }
        }

        private Set<String> kineticBackingFields(String targetType) {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (Map.Entry<String, Contract> entry : contracts.entrySet()) {
                if (!compatibleTarget(targetType, entry.getKey(), nodes)) continue;
                for (Signal signal : entry.getValue().signals) {
                    if (signal.operation != Operation.ROTATION_RATE
                            && signal.operation != Operation.UV_SCROLL) continue;
                    String last = lastStep(signal.valueSource);
                    if (last == null) continue;
                    if (last.startsWith("F#")) {
                        out.add(last);
                    } else if (last.startsWith("M#")) {
                        MethodSpec spec = MethodSpec.parse(last);
                        if (spec != null) collectNumericFields(spec.owner, spec.name, spec.desc,
                                out, new HashSet<>(), 0);
                    }
                }
            }
            return Set.copyOf(out);
        }

        private void collectNumericFields(String owner, String name, String desc, Set<String> out,
                                          Set<String> visiting, int depth) {
            if (depth > 6) return;
            String id = owner + '#' + name + desc;
            if (!visiting.add(id)) return;
            try {
                ClassNode node = nodes.get(owner);
                MethodNode method = findMethod(node, name, desc);
                if (method == null) return;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) {
                        try {
                            if (numeric(Type.getType(field.desc))) {
                                out.add("F#" + field.owner + '#' + field.name + '#' + field.desc);
                            }
                        } catch (IllegalArgumentException ignored) {
                        }
                    } else if (instruction instanceof MethodInsnNode call
                            && numeric(Type.getReturnType(call.desc))
                            && compatibleTarget(owner, call.owner, nodes)) {
                        collectNumericFields(call.owner, call.name, call.desc, out, visiting, depth + 1);
                    }
                }
            } finally {
                visiting.remove(id);
            }
        }

        private List<ValuePair> numericPairs(ClassNode behavior) {
            ArrayList<ValuePair> out = new ArrayList<>();
            for (MethodNode getter : behavior.methods) {
                Type[] getterArgs = Type.getArgumentTypes(getter.desc);
                if (getterArgs.length != 0 || !numeric(Type.getReturnType(getter.desc))) continue;
                FieldInsnNode storage = numericFieldRead(getter, behavior.name);
                if (storage == null) continue;
                for (MethodNode setter : behavior.methods) {
                    Type[] args = Type.getArgumentTypes(setter.desc);
                    Type returned = Type.getReturnType(setter.desc);
                    if (args.length != 1 || !numeric(args[0])
                            || returned.getSort() != Type.VOID && returned.getSort() != Type.BOOLEAN) continue;
                    if (!writesField(setter, storage.owner, storage.name)) continue;
                    int effects = setterSideEffects(setter, storage);
                    if (effects < 20) continue;
                    out.add(new ValuePair(getter, setter, storage,
                            clampFields(setter, behavior.name), effects));
                }
            }
            return out;
        }

        private MethodNode projectionMethod(ClassNode target, FieldNode field, ClassNode behavior,
                                            MethodNode getter) {
            for (MethodNode method : target.methods) {
                if (!numeric(Type.getReturnType(method.desc))) continue;
                boolean fieldRead = false;
                boolean getterCall = false;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETFIELD
                            && target.name.equals(read.owner) && field.name.equals(read.name)) {
                        fieldRead = true;
                    } else if (instruction instanceof MethodInsnNode call
                            && behavior.name.equals(call.owner) && getter.name.equals(call.name)
                            && getter.desc.equals(call.desc)) {
                        getterCall = true;
                    }
                }
                if (fieldRead && getterCall) return method;
            }
            return null;
        }

        private int projectionCorrelation(ClassNode leaf, MethodNode projection,
                                          Set<String> networkFields) {
            int best = 0;
            for (ClassNode current = leaf; current != null; current = nodes.get(current.superName)) {
                if (current.methods == null) continue;
                for (MethodNode method : current.methods) {
                    boolean callsProjection = false;
                    boolean touchesNetwork = false;
                    for (AbstractInsnNode instruction : method.instructions) {
                        if (instruction instanceof MethodInsnNode call
                                && projection.name.equals(call.name) && projection.desc.equals(call.desc)
                                && compatibleTarget(leaf.name, call.owner, nodes)) {
                            callsProjection = true;
                        } else if (instruction instanceof FieldInsnNode field) {
                            String spec = "F#" + field.owner + '#' + field.name + '#' + field.desc;
                            if (networkFields.contains(spec)) touchesNetwork = true;
                        }
                    }
                    if (callsProjection && touchesNetwork) best = Math.max(best, 100);
                    else if (callsProjection) best = Math.max(best, 35);
                }
            }
            return best;
        }

        private ClampFields clampFields(MethodNode setter, String behaviorType) {
            List<FieldInsnNode> recent = new ArrayList<>();
            for (AbstractInsnNode instruction : setter.instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                        && behaviorType.equals(field.owner)) {
                    try {
                        if (numeric(Type.getType(field.desc))) {
                            recent.add(field);
                            if (recent.size() > 4) recent.remove(0);
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
                Type[] args = Type.getArgumentTypes(call.desc);
                if (args.length != 3 || !numeric(args[0]) || !numeric(args[1]) || !numeric(args[2])
                        || !numeric(Type.getReturnType(call.desc)) || recent.size() < 2) continue;
                FieldInsnNode min = recent.get(recent.size() - 2);
                FieldInsnNode max = recent.get(recent.size() - 1);
                return new ClampFields(fieldSpec(min), fieldSpec(max));
            }
            return null;
        }
    }

    /** A symbolic value used only for semantic provenance, not for executing mod code. */
    private static final class FlowValue implements Value {
        final int size;
        final Set<String> sources;
        final Set<String> objectPaths;
        final Set<String> modelRefs;
        final boolean time;
        final Set<String> timeScaledSources;

        FlowValue(int size, Collection<String> sources, Collection<String> objectPaths,
                  Collection<String> modelRefs, boolean time,
                  Collection<String> timeScaledSources) {
            this.size = Math.max(1, size);
            this.sources = sources == null || sources.isEmpty() ? Set.of() : Set.copyOf(sources);
            this.objectPaths = objectPaths == null || objectPaths.isEmpty()
                    ? Set.of() : Set.copyOf(objectPaths);
            this.modelRefs = modelRefs == null || modelRefs.isEmpty() ? Set.of() : Set.copyOf(modelRefs);
            this.time = time;
            this.timeScaledSources = timeScaledSources == null || timeScaledSources.isEmpty()
                    ? Set.of() : Set.copyOf(timeScaledSources);
        }

        static FlowValue unknown(int size) {
            return new FlowValue(size, Set.of(), Set.of(), Set.of(), false, Set.of());
        }

        static FlowValue merge(FlowValue first, FlowValue second) {
            if (first == null) return second;
            if (second == null) return first;
            int size = Math.max(first.size, second.size);
            boolean time = first.time || second.time;
            Set<String> sources = boundedUnion(first.sources, second.sources);
            Set<String> paths = boundedUnion(first.objectPaths, second.objectPaths);
            Set<String> models = boundedUnion(first.modelRefs, second.modelRefs);
            Set<String> timed = boundedUnion(first.timeScaledSources, second.timeScaledSources);
            if (first.size == size && first.time == time && first.sources.equals(sources)
                    && first.objectPaths.equals(paths) && first.modelRefs.equals(models)
                    && first.timeScaledSources.equals(timed)) return first;
            if (second.size == size && second.time == time && second.sources.equals(sources)
                    && second.objectPaths.equals(paths) && second.modelRefs.equals(models)
                    && second.timeScaledSources.equals(timed)) return second;
            return new FlowValue(size, sources, paths, models, time, timed);
        }

        static FlowValue mergeAll(Collection<? extends FlowValue> values, int size) {
            FlowValue out = unknown(size);
            if (values != null) for (FlowValue value : values) out = merge(out, value);
            return new FlowValue(size, out.sources, out.objectPaths, out.modelRefs, out.time,
                    out.timeScaledSources);
        }

        private static Set<String> boundedUnion(Set<String> first, Set<String> second) {
            if (first.isEmpty()) return second.size() <= MAX_PROVENANCE
                    ? second : firstN(second, MAX_PROVENANCE);
            if (second.isEmpty()) return first.size() <= MAX_PROVENANCE
                    ? first : firstN(first, MAX_PROVENANCE);
            LinkedHashSet<String> out = new LinkedHashSet<>(Math.min(MAX_PROVENANCE,
                    first.size() + second.size()));
            for (String value : first) {
                out.add(value);
                if (out.size() >= MAX_PROVENANCE) return Set.copyOf(out);
            }
            for (String value : second) {
                out.add(value);
                if (out.size() >= MAX_PROVENANCE) break;
            }
            return Set.copyOf(out);
        }

        private static Set<String> firstN(Set<String> values, int limit) {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (String value : values) {
                out.add(value);
                if (out.size() >= limit) break;
            }
            return Set.copyOf(out);
        }

        @Override public int getSize() { return size; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof FlowValue value)) return false;
            return size == value.size && time == value.time
                    && sources.equals(value.sources)
                    && objectPaths.equals(value.objectPaths)
                    && modelRefs.equals(value.modelRefs)
                    && timeScaledSources.equals(value.timeScaledSources);
        }

        @Override public int hashCode() {
            return java.util.Objects.hash(size, sources, objectPaths, modelRefs, time, timeScaledSources);
        }
    }

    /** Minimal JVM data-flow interpreter which preserves source and model provenance. */
    private static final class FlowInterpreter extends Interpreter<FlowValue> {
        private final SemanticAnalyzer semantic;
        private final ClassNode concrete;
        private final MethodNode method;
        private final Set<String> targets;
        private final boolean renderRoot;
        private final Map<Integer, Integer> argumentByLocal;
        private final Map<Integer, String> argumentTypeByIndex;

        FlowInterpreter(SemanticAnalyzer semantic, ClassNode concrete, MethodNode method,
                        Set<String> targets, boolean renderRoot) {
            super(Opcodes.ASM9);
            this.semantic = semantic;
            this.concrete = concrete;
            this.method = method;
            this.targets = targets;
            this.renderRoot = renderRoot;
            this.argumentByLocal = argumentLocals(method);
            this.argumentTypeByIndex = argumentTypes(method);
        }

        @Override public FlowValue newValue(Type type) {
            if (type == null) return FlowValue.unknown(1);
            if (type == Type.VOID_TYPE) return null;
            return FlowValue.unknown(type.getSize());
        }

        @Override public FlowValue newParameterValue(boolean isInstanceMethod, int local, Type type) {
            Integer argument = argumentByLocal.get(local);
            if (argument == null) return newValue(type);
            LinkedHashSet<String> sources = new LinkedHashSet<>();
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            if (numeric(type)) sources.add("A#" + argument);
            if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
                String objectType = type.getSort() == Type.OBJECT ? type.getInternalName() : type.getDescriptor();
                if (objectType != null && targets.stream()
                        .anyMatch(target -> compatibleTarget(target, objectType, semantic.nodes))) {
                    paths.add("R#" + argument + ':' + objectType + '|');
                }
            }
            boolean time = renderRoot && numeric(type) && renderTimeArgument(method, argument);
            return new FlowValue(type.getSize(), sources, paths, Set.of(), time, Set.of());
        }

        @Override public FlowValue newOperation(AbstractInsnNode instruction) {
            int opcode = instruction.getOpcode();
            if (instruction instanceof FieldInsnNode field && opcode == Opcodes.GETSTATIC) {
                Type type = safeType(field.desc);
                if (type == null) return FlowValue.unknown(1);
                Set<String> models = Set.of();
                String model = semantic.modelFields.get(field.owner + '.' + field.name);
                if (model != null) models = Set.of(model);
                return new FlowValue(type.getSize(), Set.of(), Set.of(), models, false, Set.of());
            }
            if (instruction instanceof org.objectweb.asm.tree.LdcInsnNode ldc
                    && (ldc.cst instanceof Long || ldc.cst instanceof Double)) return FlowValue.unknown(2);
            if (opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1
                    || opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1) return FlowValue.unknown(2);
            return FlowValue.unknown(1);
        }

        @Override public FlowValue copyOperation(AbstractInsnNode instruction, FlowValue value) {
            return value;
        }

        @Override public FlowValue unaryOperation(AbstractInsnNode instruction, FlowValue value) {
            int opcode = instruction.getOpcode();
            if (instruction instanceof FieldInsnNode field && opcode == Opcodes.GETFIELD) {
                Type type = safeType(field.desc);
                if (type == null) return FlowValue.unknown(1);
                LinkedHashSet<String> sources = new LinkedHashSet<>();
                LinkedHashSet<String> paths = new LinkedHashSet<>();
                for (String path : value == null ? Set.<String>of() : value.objectPaths) {
                    String extended = appendPath(path, fieldSpec(field));
                    if (numeric(type)) sources.add(extended);
                    else paths.add(extended);
                }
                Set<String> models = value == null ? Set.of() : value.modelRefs;
                String model = semantic.modelFields.get(field.owner + '.' + field.name);
                if (model != null) {
                    LinkedHashSet<String> combined = new LinkedHashSet<>(models);
                    combined.add(model);
                    models = Set.copyOf(combined);
                }
                return new FlowValue(type.getSize(), sources, paths, models,
                        value != null && value.time,
                        value == null ? Set.of() : value.timeScaledSources);
            }
            if (opcode == Opcodes.PUTSTATIC || opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE
                    || opcode == Opcodes.IFLT || opcode == Opcodes.IFGE || opcode == Opcodes.IFGT
                    || opcode == Opcodes.IFLE || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL
                    || opcode == Opcodes.ATHROW || opcode == Opcodes.MONITORENTER
                    || opcode == Opcodes.MONITOREXIT) return null;
            int size = switch (opcode) {
                case Opcodes.I2L, Opcodes.I2D, Opcodes.F2L, Opcodes.F2D,
                     Opcodes.LNEG, Opcodes.DNEG -> 2;
                default -> value == null ? 1 : value.size;
            };
            return value == null ? FlowValue.unknown(size)
                    : new FlowValue(size, value.sources, value.objectPaths, value.modelRefs, value.time,
                    value.timeScaledSources);
        }

        @Override public FlowValue binaryOperation(AbstractInsnNode instruction,
                                                   FlowValue left, FlowValue right) {
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.IF_ICMPEQ || opcode == Opcodes.IF_ICMPNE
                    || opcode == Opcodes.IF_ICMPLT || opcode == Opcodes.IF_ICMPGE
                    || opcode == Opcodes.IF_ICMPGT || opcode == Opcodes.IF_ICMPLE
                    || opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE) return null;
            int size = switch (opcode) {
                case Opcodes.LALOAD, Opcodes.DALOAD, Opcodes.LADD, Opcodes.DADD,
                     Opcodes.LSUB, Opcodes.DSUB, Opcodes.LMUL, Opcodes.DMUL,
                     Opcodes.LDIV, Opcodes.DDIV, Opcodes.LREM, Opcodes.DREM,
                     Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR -> 2;
                default -> 1;
            };
            FlowValue merged = FlowValue.merge(left, right);
            if (merged == null) return FlowValue.unknown(size);
            Set<String> timeScaled = merged.timeScaledSources;
            if (opcode == Opcodes.IMUL || opcode == Opcodes.LMUL || opcode == Opcodes.FMUL
                    || opcode == Opcodes.DMUL) {
                LinkedHashSet<String> scaled = new LinkedHashSet<>(timeScaled);
                if (left != null && left.time && right != null) scaled.addAll(right.sources);
                if (right != null && right.time && left != null) scaled.addAll(left.sources);
                timeScaled = scaled.isEmpty() ? Set.of() : Set.copyOf(scaled);
            }
            return new FlowValue(size, merged.sources, merged.objectPaths, merged.modelRefs, merged.time,
                    timeScaled);
        }

        @Override public FlowValue ternaryOperation(AbstractInsnNode instruction, FlowValue first,
                                                    FlowValue second, FlowValue third) {
            return null;
        }

        @Override public FlowValue naryOperation(AbstractInsnNode instruction,
                                                List<? extends FlowValue> values) {
            if (!(instruction instanceof MethodInsnNode call)) {
                Type type = instruction instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy
                        ? Type.getReturnType(indy.desc) : Type.getObjectType("java/lang/Object");
                return type == Type.VOID_TYPE ? null : FlowValue.mergeAll(values, type.getSize());
            }
            Type result = Type.getReturnType(call.desc);
            if (result == Type.VOID_TYPE) return null;
            FlowValue receiver = call.getOpcode() == Opcodes.INVOKESTATIC || values.isEmpty()
                    ? null : values.get(0);

            // A numeric call on an object reached from a BlockEntity is itself the authoritative
            // source. Do not descend into it; runtime can invoke this exact common/server method.
            if (numeric(result) && receiver != null && !receiver.objectPaths.isEmpty()) {
                LinkedHashSet<String> sources = new LinkedHashSet<>();
                String step = methodSpec(call.owner, call.name, call.desc);
                for (String path : receiver.objectPaths) sources.add(appendPath(path, step));
                return new FlowValue(result.getSize(), sources, Set.of(), receiver.modelRefs,
                        timeLikeCall(call), Set.of());
            }

            if (numeric(result)) {
                FlowValue summary = semantic.numericSummary(concrete, call, targets, 0);
                FlowValue substituted = substituteSummary(summary, values, call);
                if (substituted != null && (!substituted.sources.isEmpty() || substituted.time)) {
                    return new FlowValue(result.getSize(), substituted.sources, Set.of(),
                            substituted.modelRefs, substituted.time || timeLikeCall(call),
                            substituted.timeScaledSources);
                }
                return new FlowValue(result.getSize(), Set.of(), Set.of(), Set.of(),
                        timeLikeCall(call), Set.of());
            }

            LinkedHashSet<String> paths = new LinkedHashSet<>();
            if (receiver != null && !receiver.objectPaths.isEmpty() && result.getSort() == Type.OBJECT) {
                String step = methodSpec(call.owner, call.name, call.desc);
                for (String path : receiver.objectPaths) paths.add(appendPath(path, step));
            }
            FlowValue merged = FlowValue.mergeAll(values, result.getSize());
            LinkedHashSet<String> models = new LinkedHashSet<>(merged.modelRefs);
            FlowValue helperModels = semantic.modelSummary(concrete, call, targets, 0);
            if (helperModels != null) models.addAll(helperModels.modelRefs);
            return new FlowValue(result.getSize(), Set.of(), paths, models, merged.time,
                    merged.timeScaledSources);
        }

        @Override public void returnOperation(AbstractInsnNode instruction, FlowValue value,
                                              FlowValue expected) {}

        @Override public FlowValue merge(FlowValue first, FlowValue second) {
            return FlowValue.merge(first, second);
        }

        private FlowValue substituteSummary(FlowValue summary, List<? extends FlowValue> values,
                                            MethodInsnNode call) {
            if (summary == null) return null;
            LinkedHashSet<String> sources = new LinkedHashSet<>();
            LinkedHashSet<String> timeScaledSources = new LinkedHashSet<>();
            boolean time = summary.time;
            for (String source : summary.sources) {
                if (source.startsWith("A#")) {
                    Integer argument = integerSuffix(source, "A#");
                    FlowValue actual = argument == null ? null : argumentValue(values, call, argument);
                    if (actual != null) {
                        sources.addAll(actual.sources);
                        timeScaledSources.addAll(actual.timeScaledSources);
                        if (summary.timeScaledSources.contains(source)) {
                            timeScaledSources.addAll(actual.sources);
                        }
                        time |= actual.time;
                    }
                    continue;
                }
                RootedSource rooted = RootedSource.parse(source);
                if (rooted != null) {
                    FlowValue actual = argumentValue(values, call, rooted.rootArgument);
                    if (actual != null) {
                        for (String path : actual.objectPaths) {
                            String resolved = rooted.steps.isBlank() ? path : appendRaw(path, rooted.steps);
                            sources.add(resolved);
                            if (summary.timeScaledSources.contains(source)) {
                                timeScaledSources.add(resolved);
                            }
                        }
                        timeScaledSources.addAll(actual.timeScaledSources);
                        time |= actual.time;
                    }
                } else {
                    sources.add(source);
                    if (summary.timeScaledSources.contains(source)) timeScaledSources.add(source);
                }
            }
            return new FlowValue(summary.size, sources, Set.of(), summary.modelRefs, time,
                    timeScaledSources);
        }
    }

    private record RootedSource(int rootArgument, String rootType, String steps) {
        static RootedSource parse(String source) {
            if (source == null || !source.startsWith("R#")) return null;
            int colon = source.indexOf(':', 2);
            int pipe = source.indexOf('|', colon + 1);
            if (colon < 0 || pipe < 0) return null;
            try {
                return new RootedSource(Integer.parseInt(source.substring(2, colon)),
                        source.substring(colon + 1, pipe), source.substring(pipe + 1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private record MethodSpec(String owner, String name, String desc) {
        static MethodSpec parse(String spec) {
            if (spec == null || !spec.startsWith("M#")) return null;
            String[] parts = spec.split("#", 4);
            return parts.length == 4 ? new MethodSpec(parts[1], parts[2], parts[3]) : null;
        }
    }

    private static void addSignal(Contract contract, Operation operation, String source,
                                  String rendererClass, String rendererMethod, String modelRef,
                                  int confidence) {
        for (Signal signal : contract.signals) {
            if (signal.operation == operation && java.util.Objects.equals(signal.valueSource, source)
                    && java.util.Objects.equals(signal.modelRef, modelRef)) {
                signal.confidence = Math.max(signal.confidence, confidence);
                return;
            }
        }
        Signal signal = new Signal();
        signal.operation = operation;
        signal.valueSource = source;
        signal.rendererClass = rendererClass;
        signal.rendererMethod = rendererMethod;
        signal.modelRef = modelRef;
        signal.confidence = confidence;
        contract.signals.add(signal);
    }

    private static boolean renderRoot(MethodNode method, Map<String, ClassNode> nodes) {
        if (method == null || method.instructions == null || method.desc == null) return false;
        Type[] args;
        try {
            args = Type.getArgumentTypes(method.desc);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        boolean blockEntity = false;
        boolean pose = false;
        boolean output = false;
        for (Type arg : args) {
            if (arg.getSort() != Type.OBJECT) continue;
            String type = arg.getInternalName();
            blockEntity |= isBlockEntityType(type, nodes);
            pose |= "com/mojang/blaze3d/vertex/PoseStack".equals(type);
            output |= "net/minecraft/client/renderer/MultiBufferSource".equals(type)
                    || "com/mojang/blaze3d/vertex/VertexConsumer".equals(type);
        }
        return blockEntity && pose && output;
    }

    private static LinkedHashSet<String> blockEntityArguments(MethodNode method,
                                                              Map<String, ClassNode> nodes) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Type arg : Type.getArgumentTypes(method.desc)) {
            if (arg.getSort() == Type.OBJECT && isBlockEntityType(arg.getInternalName(), nodes)) {
                out.add(arg.getInternalName());
            }
        }
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.TypeInsnNode cast
                    && cast.getOpcode() == Opcodes.CHECKCAST && isBlockEntityType(cast.desc, nodes)) {
                out.add(cast.desc);
            }
        }
        return out;
    }

    private static void removeErasedTarget(Set<String> targets) {
        if (targets.size() > 1) targets.remove(BLOCK_ENTITY);
        if (targets.size() == 1 && targets.contains(BLOCK_ENTITY)) targets.clear();
    }

    private static boolean isBlockEntityType(String type, Map<String, ClassNode> nodes) {
        if (type == null) return false;
        if (BLOCK_ENTITY.equals(type)) return true;
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!seen.add(current)) continue;
            if (BLOCK_ENTITY.equals(current)) return true;
            ClassNode node = nodes.get(current);
            if (node == null) continue;
            if (node.superName != null) queue.addLast(node.superName);
            if (node.interfaces != null) queue.addAll(node.interfaces);
        }
        return false;
    }

    /** true when instances of concrete can be used where ancestor/interface is expected. */
    private static boolean compatibleTarget(String concrete, String ancestor,
                                            Map<String, ClassNode> nodes) {
        if (concrete == null || ancestor == null) return false;
        if (concrete.equals(ancestor) || BLOCK_ENTITY.equals(ancestor)) return true;
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(concrete);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!seen.add(current)) continue;
            if (ancestor.equals(current)) return true;
            ClassNode node = nodes.get(current);
            if (node == null) continue;
            if (node.superName != null) queue.addLast(node.superName);
            if (node.interfaces != null) queue.addAll(node.interfaces);
        }
        return false;
    }

    private static Effect primitiveRenderEffect(MethodInsnNode call) {
        if (call == null || call.desc == null) return null;
        Type[] args;
        Type returned;
        try {
            args = Type.getArgumentTypes(call.desc);
            returned = Type.getReturnType(call.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        boolean hasFloat = false;
        boolean direction = false;
        boolean texture = false;
        for (Type arg : args) {
            hasFloat |= arg.getSort() == Type.FLOAT || arg.getSort() == Type.DOUBLE;
            if (arg.getSort() == Type.OBJECT) {
                String type = arg.getInternalName();
                direction |= "net/minecraft/core/Direction".equals(type)
                        || "net/minecraft/core/Direction$Axis".equals(type)
                        || type != null && type.contains("Quaternion");
                texture |= textureType(type);
            }
        }
        if (!hasFloat) return null;
        String owner = call.owner == null ? "" : call.owner;
        String returnedType = returned.getSort() == Type.OBJECT ? returned.getInternalName() : "";
        String methodName = call.name == null ? "" : call.name.toLowerCase(Locale.ROOT);
        boolean renderish = renderType(owner) || renderType(returnedType);

        if (texture && renderish) return Effect.UV;
        if (direction && renderish) return Effect.ROTATION;
        if ("com/mojang/blaze3d/vertex/PoseStack".equals(owner)) {
            long doubles = java.util.Arrays.stream(args).filter(type -> type.getSort() == Type.DOUBLE).count();
            if (doubles == 3) return Effect.TRANSLATION;
        }
        if (returnedType != null && returnedType.toLowerCase(Locale.ROOT).contains("/transform/translate")) {
            return Effect.TRANSLATION;
        }
        if (owner.toLowerCase(Locale.ROOT).contains("quaternion")
                || returnedType != null && returnedType.toLowerCase(Locale.ROOT).contains("quaternion")) {
            return Effect.ROTATION;
        }
        // Rendering library method names are semantic operations, unlike mod member names. They are
        // a final fallback after descriptor/type shape so obfuscated mod APIs remain irrelevant.
        if (renderish && (methodName.contains("uv") || methodName.contains("scroll"))) return Effect.UV;
        if (renderish && methodName.contains("translate")) return Effect.TRANSLATION;
        if (renderish && (methodName.contains("rotate") || methodName.contains("rotation"))) return Effect.ROTATION;
        return null;
    }

    private static List<Integer> effectNumericArguments(MethodInsnNode call, Effect effect) {
        Type[] args = Type.getArgumentTypes(call.desc);
        ArrayList<Integer> floating = new ArrayList<>();
        ArrayList<Integer> numeric = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (!numeric(args[i])) continue;
            numeric.add(i);
            if (args[i].getSort() == Type.FLOAT || args[i].getSort() == Type.DOUBLE) floating.add(i);
        }
        return floating.isEmpty() ? numeric : floating;
    }

    private static boolean textureType(String type) {
        if (type == null) return false;
        String lower = type.toLowerCase(Locale.ROOT);
        return lower.contains("texture") || lower.contains("sprite") || lower.contains("atlas")
                || lower.endsWith("/uv") || lower.contains("texcoord");
    }

    private static boolean renderType(String type) {
        if (type == null) return false;
        String lower = type.toLowerCase(Locale.ROOT);
        return lower.contains("render") || lower.contains("vertex") || lower.contains("buffer")
                || lower.contains("model") || lower.contains("pose") || lower.contains("transform")
                || lower.contains("affine") || lower.contains("quaternion");
    }

    private static boolean targetReceiverCall(MethodInsnNode call, Set<String> targets) {
        if (call == null || call.getOpcode() == Opcodes.INVOKESTATIC) return false;
        for (String target : targets) {
            if (call.owner.equals(target)) return true;
        }
        return false;
    }

    private static boolean traversable(MethodInsnNode call, Set<String> targets) {
        if (call == null || call.owner == null) return false;
        if (call.owner.startsWith("java/") || call.owner.startsWith("javax/")
                || call.owner.startsWith("jdk/") || call.owner.startsWith("sun/")
                || call.owner.startsWith("org/joml/") || call.owner.startsWith("net/minecraft/")) {
            return false;
        }
        for (String target : targets) {
            if (call.owner.equals(target)) return false;
        }
        return true;
    }

    private static boolean renderTimeArgument(MethodNode method, int argument) {
        if (method == null || argument < 0) return false;
        Type[] args = Type.getArgumentTypes(method.desc);
        if (argument >= args.length) return false;
        Type type = args[argument];
        if (type.getSort() != Type.FLOAT && type.getSort() != Type.DOUBLE) return false;
        // BER render roots conventionally have exactly one floating interpolation parameter. This
        // is structural and remains true when the method itself is obfuscated.
        int floating = 0;
        for (Type arg : args) {
            if (arg.getSort() == Type.FLOAT || arg.getSort() == Type.DOUBLE) floating++;
        }
        return floating == 1;
    }

    private static boolean timeLikeCall(MethodInsnNode call) {
        if (call == null || !numeric(Type.getReturnType(call.desc))) return false;
        if ("java/lang/System".equals(call.owner) && "()J".equals(call.desc)) return true;
        Type[] args = Type.getArgumentTypes(call.desc);
        int numericArgs = 0;
        boolean world = false;
        for (Type arg : args) {
            if (numeric(arg)) numericArgs++;
            if (arg.getSort() == Type.OBJECT) {
                String type = arg.getInternalName();
                world |= "net/minecraft/world/level/LevelAccessor".equals(type)
                        || "net/minecraft/world/level/Level".equals(type)
                        || "net/minecraft/world/level/BlockAndTintGetter".equals(type);
            }
        }
        return call.getOpcode() == Opcodes.INVOKESTATIC && world && numericArgs == 0;
    }

    private static Frame<FlowValue> frameAt(Frame<FlowValue>[] frames, MethodNode method,
                                            AbstractInsnNode instruction) {
        if (frames == null || method == null || instruction == null) return null;
        int index = method.instructions.indexOf(instruction);
        return index < 0 || index >= frames.length ? null : frames[index];
    }

    private static FlowValue argumentValue(Frame<FlowValue> frame, MethodInsnNode call, int argument) {
        if (frame == null || call == null) return null;
        Type[] args = Type.getArgumentTypes(call.desc);
        if (argument < 0 || argument >= args.length) return null;
        int index = frame.getStackSize() - args.length + argument;
        if (index < 0 || index >= frame.getStackSize()) return null;
        return frame.getStack(index);
    }

    private static FlowValue argumentValue(List<? extends FlowValue> values, MethodInsnNode call,
                                           int argument) {
        if (values == null || call == null) return null;
        Type[] args = Type.getArgumentTypes(call.desc);
        if (argument < 0 || argument >= args.length) return null;
        int offset = call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
        int index = offset + argument;
        return index < values.size() ? values.get(index) : null;
    }

    private static String modelRef(Frame<FlowValue> frame, MethodInsnNode call) {
        return uniqueModel(modelRefs(frame, call));
    }

    private static Set<String> modelRefs(Frame<FlowValue> frame, MethodInsnNode call) {
        if (frame == null || call == null) return Set.of();
        LinkedHashSet<String> models = new LinkedHashSet<>();
        Type[] args = Type.getArgumentTypes(call.desc);
        int base = frame.getStackSize() - args.length;
        if (call.getOpcode() != Opcodes.INVOKESTATIC) {
            int receiver = base - 1;
            if (receiver >= 0) models.addAll(frame.getStack(receiver).modelRefs);
        }
        for (int i = 0; i < args.length; i++) {
            int index = base + i;
            if (index >= 0 && index < frame.getStackSize()) models.addAll(frame.getStack(index).modelRefs);
        }
        return models.isEmpty() ? Set.of() : Set.copyOf(models);
    }

    private static String modelRef(Frame<FlowValue> frame, FieldInsnNode field) {
        if (frame == null || field == null || frame.getStackSize() < 2) return null;
        LinkedHashSet<String> models = new LinkedHashSet<>();
        FlowValue receiver = frame.getStack(frame.getStackSize() - 2);
        FlowValue value = frame.getStack(frame.getStackSize() - 1);
        if (receiver != null) models.addAll(receiver.modelRefs);
        if (value != null) models.addAll(value.modelRefs);
        return uniqueModel(models);
    }

    private static FlowValue fieldStoredValue(Frame<FlowValue> frame) {
        return frame == null || frame.getStackSize() == 0
                ? null : frame.getStack(frame.getStackSize() - 1);
    }

    private static String uniqueModel(Collection<String> models) {
        if (models == null || models.size() != 1) return null;
        return models.iterator().next();
    }

    private static String modelContextKey(Collection<String> models) {
        if (models == null || models.isEmpty()) return "";
        return models.stream().sorted().reduce((a, b) -> a + ',' + b).orElse("");
    }

    private static MethodNode dispatchMethod(ClassNode concrete, MethodInsnNode call,
                                             Map<String, ClassNode> nodes) {
        if (call == null) return null;
        if (call.getOpcode() != Opcodes.INVOKESTATIC && concrete != null) {
            for (ClassNode current = concrete; current != null; current = nodes.get(current.superName)) {
                MethodNode method = findMethod(current, call.name, call.desc);
                if (method != null && (method.access & Opcodes.ACC_ABSTRACT) == 0) return method;
                if (current.name.equals(call.owner)) break;
            }
        }
        MethodNode exact = findMethod(nodes.get(call.owner), call.name, call.desc);
        return exact != null && (exact.access & Opcodes.ACC_ABSTRACT) == 0 ? exact : null;
    }

    private static MethodNode findMethod(ClassNode node, String name, String desc) {
        if (node == null || node.methods == null || name == null || desc == null) return null;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) return method;
        }
        return null;
    }

    private static FieldInsnNode numericFieldRead(MethodNode method, String owner) {
        if (method == null) return null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                    || !owner.equals(field.owner)) continue;
            Type type = safeType(field.desc);
            if (numeric(type)) return field;
        }
        return null;
    }

    private static boolean writesField(MethodNode method, String owner, String name) {
        if (method == null) return false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && owner.equals(field.owner) && name.equals(field.name)) return true;
        }
        return false;
    }

    private static int setterSideEffects(MethodNode setter, FieldInsnNode storage) {
        int score = 0;
        boolean afterWrite = false;
        for (AbstractInsnNode instruction : setter.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && storage.owner.equals(field.owner) && storage.name.equals(field.name)) {
                afterWrite = true;
                continue;
            }
            if (!afterWrite || !(instruction instanceof MethodInsnNode call)) continue;
            String owner = call.owner == null ? "" : call.owner;
            if (call.getOpcode() == Opcodes.INVOKEINTERFACE && call.desc.endsWith(")V")) score += 35;
            if (!owner.startsWith("java/") && !owner.startsWith("net/minecraft/util/")) score += 20;
            if (owner.contains("BlockEntity") || call.desc.contains("BlockEntity")) score += 20;
        }
        return score;
    }

    private static String lastStep(String chain) {
        if (chain == null || chain.isBlank()) return null;
        int split = chain.lastIndexOf('>');
        return split < 0 ? chain : chain.substring(split + 1);
    }

    private static String firstStepOwner(String chain) {
        if (chain == null || chain.isBlank()) return null;
        int split = chain.indexOf('>');
        String step = split < 0 ? chain : chain.substring(0, split);
        String[] parts = step.split("#", 4);
        return parts.length == 4 && ("M".equals(parts[0]) || "F".equals(parts[0]))
                ? parts[1] : null;
    }

    private static String methodSpec(String owner, MethodNode method) {
        return method == null ? null : methodSpec(owner, method.name, method.desc);
    }

    private static String methodSpec(String owner, String name, String desc) {
        return owner == null || name == null || desc == null ? null
                : "M#" + owner + '#' + name + '#' + desc;
    }

    private static String methodKey(String owner, String name, String desc) {
        return owner == null || name == null || desc == null ? null
                : owner + '#' + name + '#' + desc;
    }

    private static String fieldKey(String owner, String name, String desc) {
        return owner == null || name == null || desc == null ? null
                : owner + '#' + name + '#' + desc;
    }

    private static String fieldSpec(String owner, FieldNode field) {
        return field == null ? null : "F#" + owner + '#' + field.name + '#' + field.desc;
    }

    private static String fieldSpec(FieldInsnNode field) {
        return field == null ? null : "F#" + field.owner + '#' + field.name + '#' + field.desc;
    }

    private static String appendPath(String path, String step) {
        if (path == null || path.isBlank()) return step;
        if (step == null || step.isBlank()) return path;
        return path.endsWith("|") ? path + step : path + '>' + step;
    }

    private static String appendRaw(String path, String steps) {
        if (path == null || path.isBlank()) return steps;
        if (steps == null || steps.isBlank()) return path;
        return path.endsWith("|") ? path + steps : path + '>' + steps;
    }

    private static Integer integerSuffix(String value, String prefix) {
        if (value == null || !value.startsWith(prefix)) return null;
        try {
            return Integer.parseInt(value.substring(prefix.length()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Map<Integer, Integer> argumentLocals(MethodNode method) {
        LinkedHashMap<Integer, Integer> out = new LinkedHashMap<>();
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        int local = isStatic ? 0 : 1;
        Type[] args = Type.getArgumentTypes(method.desc);
        for (int i = 0; i < args.length; i++) {
            out.put(local, i);
            local += args[i].getSize();
        }
        return out;
    }

    private static Map<Integer, String> argumentTypes(MethodNode method) {
        LinkedHashMap<Integer, String> out = new LinkedHashMap<>();
        Type[] args = Type.getArgumentTypes(method.desc);
        for (int i = 0; i < args.length; i++) {
            if (args[i].getSort() == Type.OBJECT) out.put(i, args[i].getInternalName());
        }
        return out;
    }

    private static boolean numeric(Type type) {
        if (type == null) return false;
        int sort = type.getSort();
        return sort == Type.BYTE || sort == Type.SHORT || sort == Type.INT || sort == Type.LONG
                || sort == Type.FLOAT || sort == Type.DOUBLE;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getPrevious();
        return cursor;
    }

    private static Integer integerValue(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        int opcode = instruction.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return opcode - Opcodes.ICONST_0;
        }
        if (instruction instanceof IntInsnNode value
                && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) return value.operand;
        if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) return value;
        return null;
    }

    private static Long longValue(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        if (instruction.getOpcode() == Opcodes.LCONST_0) return 0L;
        if (instruction.getOpcode() == Opcodes.LCONST_1) return 1L;
        if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Number value) {
            return value.longValue();
        }
        Integer integer = integerValue(instruction);
        return integer == null ? null : integer.longValue();
    }

    private static Type safeType(String descriptor) {
        try {
            return Type.getType(descriptor);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean numericReturnOpcode(int opcode) {
        return opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN || opcode == Opcodes.FRETURN
                || opcode == Opcodes.DRETURN;
    }
}
