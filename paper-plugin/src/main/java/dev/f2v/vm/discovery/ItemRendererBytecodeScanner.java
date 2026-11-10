package dev.f2v.vm.discovery;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Recovers static geometry selected by custom item renderers without loading client classes.
 *
 * <p>Two common registration styles are supported structurally. A client bootstrap may register an
 * item together with a {@code BlockEntityWithoutLevelRenderer} instance whose constructor receives
 * a model-layer key and texture, or one shared renderer may branch on {@code ItemStack#getItem()}
 * and select a static model/texture field. Both forms prove the exact item -> renderer geometry
 * association and avoid registry-name guesses.</p>
 */
public final class ItemRendererBytecodeScanner {
    public static final class Visual {
        public String itemId = "";
        public String rendererClass = "";
        public String modelClass = "";
        /** namespace:path relative to textures/, without .png. */
        public String textureRef = "";
        public ArmorVisualBytecodeScanner.LayerModel layerModel;
        /** Column-major 4x4 local PoseStack matrix applied by the custom renderer before drawing.
         * The model itself remains in ordinary ModelPart units; pack baking replays this matrix
         * before normalizing the geometry into vanilla item-model space. */
        public float[] rendererTransform = identity();
        public int confidence;
    }

    private ItemRendererBytecodeScanner() {}

    public static List<Visual> scan(Map<String, ClassNode> nodes,
                                    Map<String, BehaviorBytecodeScanner.RegistryRef> registryFields) {
        if (nodes == null || nodes.isEmpty() || registryFields == null || registryFields.isEmpty()) {
            return List.of();
        }
        Map<String, String> textures = staticTextureFields(nodes);
        Map<String, ArmorVisualBytecodeScanner.LayerModel> layers = modelLayerFactories(nodes);
        LinkedHashMap<String, Visual> byItem = new LinkedHashMap<>();

        scanRegistrationSites(nodes, registryFields, textures, layers, byItem);
        scanRendererBranches(nodes, registryFields, textures, byItem);

        ArrayList<Visual> out = new ArrayList<>(byItem.values());
        out.sort(Comparator.comparing(visual -> visual.itemId));
        return List.copyOf(out);
    }

    public static Visual forItem(Collection<ModScanner.Profile> profiles, String itemId) {
        if (profiles == null || itemId == null || itemId.isBlank()) return null;
        Visual best = null;
        for (ModScanner.Profile profile : profiles) {
            if (profile == null || profile.itemRendererVisuals == null) continue;
            for (Visual visual : profile.itemRendererVisuals) {
                if (visual == null || !itemId.equals(visual.itemId) || visual.layerModel == null
                        || visual.layerModel.cubes.isEmpty()) continue;
                if (best == null || visual.confidence > best.confidence
                        || visual.confidence == best.confidence
                        && visual.layerModel.cubes.size() > best.layerModel.cubes.size()) {
                    best = visual;
                }
            }
        }
        return best;
    }

    private static void scanRegistrationSites(Map<String, ClassNode> nodes,
                                              Map<String, BehaviorBytecodeScanner.RegistryRef> registryFields,
                                              Map<String, String> textures,
                                              Map<String, ArmorVisualBytecodeScanner.LayerModel> layers,
                                              Map<String, Visual> out) {
        for (ClassNode owner : nodes.values()) {
            if (owner == null || owner.methods == null) continue;
            for (MethodNode method : owner.methods) {
                if (method.instructions == null) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode sink)) continue;
                    Type[] sinkArgs = Type.getArgumentTypes(sink.desc);
                    if (sinkArgs.length < 2) continue;

                    TypeInsnNode allocation = nearestRendererAllocation(instruction, nodes, 28);
                    if (allocation == null) continue;
                    BehaviorBytecodeScanner.RegistryRef item = nearestRegistryItem(
                            allocation, registryFields, 24);
                    if (item == null || item.id == null || item.id.isBlank()) continue;
                    MethodInsnNode constructor = rendererConstructorBetween(allocation, instruction);
                    if (constructor == null) continue;

                    String layerField = staticFieldBetween(allocation, constructor,
                            descriptor -> descriptor.toLowerCase(Locale.ROOT).contains("modellayerlocation"));
                    String textureField = staticFieldBetween(allocation, constructor,
                            ItemRendererBytecodeScanner::resourceDescriptor);
                    ArmorVisualBytecodeScanner.LayerModel layer = layerField == null ? null : layers.get(layerField);
                    String texture = textureField == null ? null : textures.get(textureField);
                    String modelClass = layerFactoryOwner(layerField, nodes);

                    if (layer == null) {
                        ClassNode renderer = nodes.get(allocation.desc);
                        layer = ArmorVisualBytecodeScanner.parseLayerModel(renderer, nodes);
                        modelClass = renderer == null ? "" : renderer.name;
                    }
                    if (layer == null || layer.cubes.isEmpty()) continue;
                    if (texture == null || texture.isBlank()) {
                        texture = singleRendererTexture(nodes.get(allocation.desc), textures);
                    }
                    float[] transform = rendererTransform(nodes.get(allocation.desc), nodes);
                    putBest(out, visual(item.id, allocation.desc, modelClass, texture, layer,
                            transform, 110));
                }
            }
        }
    }

    private static void scanRendererBranches(Map<String, ClassNode> nodes,
                                             Map<String, BehaviorBytecodeScanner.RegistryRef> registryFields,
                                             Map<String, String> textures,
                                             Map<String, Visual> out) {
        for (ClassNode renderer : nodes.values()) {
            if (!isItemRenderer(renderer, nodes) || renderer.methods == null) continue;
            for (MethodNode method : renderer.methods) {
                if (!itemRenderMethod(method)) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof FieldInsnNode itemField)
                            || itemField.getOpcode() != Opcodes.GETSTATIC) continue;
                    BehaviorBytecodeScanner.RegistryRef item = registryFields.get(itemField.owner + '.' + itemField.name);
                    if (item == null || item.id == null || !itemKind(item.registryKind)) continue;
                    JumpInsnNode branch = itemComparisonAfter(itemField, 10);
                    if (branch == null) continue;
                    AbstractInsnNode start;
                    AbstractInsnNode end;
                    if (branch.getOpcode() == Opcodes.IF_ACMPNE) {
                        start = nextCode(branch);
                        end = branch.label;
                    } else if (branch.getOpcode() == Opcodes.IF_ACMPEQ) {
                        start = branch.label;
                        end = nextComparisonBoundary(branch.label, 220);
                    } else continue;

                    Visual candidate = branchVisual(renderer, method, item.id, start, end, nodes, textures);
                    if (candidate != null) putBest(out, candidate);
                }
            }
        }
    }

    private static Visual branchVisual(ClassNode renderer, MethodNode method, String itemId, AbstractInsnNode start,
                                       AbstractInsnNode end, Map<String, ClassNode> nodes,
                                       Map<String, String> textures) {
        ArmorVisualBytecodeScanner.LayerModel best = null;
        String modelClass = "";
        String texture = null;
        int code = 0;
        for (AbstractInsnNode cursor = start; cursor != null && cursor != end && code < 260;
             cursor = cursor.getNext()) {
            if (cursor.getOpcode() >= 0) code++;
            if (!(cursor instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC) continue;
            String fieldKey = field.owner + '.' + field.name;
            String foundTexture = textures.get(fieldKey);
            if (foundTexture != null && texture == null) texture = foundTexture;
            String type = objectType(field.desc);
            ClassNode model = type == null ? null : nodes.get(type);
            if (model == null || model == renderer) continue;
            ArmorVisualBytecodeScanner.LayerModel parsed = ArmorVisualBytecodeScanner.parseLayerModel(model, nodes);
            if (parsed != null && !parsed.cubes.isEmpty()
                    && (best == null || parsed.cubes.size() > best.cubes.size())) {
                best = parsed;
                modelClass = model.name;
            }
        }
        if (best == null) return null;
        float[] transform = transformForRange(renderer.name, method, start, end);
        return visual(itemId, renderer.name, modelClass, texture, best, transform, 105);
    }

    private static Visual visual(String itemId, String renderer, String modelClass, String texture,
                                 ArmorVisualBytecodeScanner.LayerModel layer, float[] transform,
                                 int confidence) {
        Visual visual = new Visual();
        visual.itemId = itemId;
        visual.rendererClass = renderer == null ? "" : renderer;
        visual.modelClass = modelClass == null ? "" : modelClass;
        visual.textureRef = texture == null ? "" : texture;
        visual.layerModel = layer;
        visual.rendererTransform = transform == null ? identity() : transform.clone();
        visual.confidence = confidence;
        return visual;
    }

    /** Replays the constant local PoseStack operations in one item renderer. This intentionally
     * ignores camera/player/state-driven operands: source item JSON already owns context transforms,
     * while only constants can be reproduced safely on a dedicated server. */
    private static float[] rendererTransform(ClassNode renderer, Map<String, ClassNode> nodes) {
        if (renderer == null || renderer.methods == null) return identity();
        MethodNode best = null;
        for (MethodNode method : renderer.methods) {
            if (!itemRenderMethod(method)) continue;
            if (best == null || method.instructions.size() > best.instructions.size()) best = method;
        }
        return best == null ? identity()
                : transformForRange(renderer.name, best, best.instructions.getFirst(), null);
    }

    private static float[] transformForRange(String owner, MethodNode method,
                                             AbstractInsnNode start, AbstractInsnNode end) {
        if (method == null || method.instructions == null || start == null) return identity();
        Map<AbstractInsnNode, Frame<SourceValue>> frames = analyze(owner, method);
        float[] matrix = identity();
        int code = 0;
        for (AbstractInsnNode cursor = start; cursor != null && cursor != end && code < 640;
             cursor = cursor.getNext()) {
            if (cursor.getOpcode() >= 0) code++;
            if (!(cursor instanceof MethodInsnNode call)) continue;
            if (poseTranslate(call)) {
                float[] xyz = numericArguments(call, frames, 3);
                if (xyz != null) multiply(matrix, translation(xyz[0], xyz[1], xyz[2]));
                continue;
            }
            if (poseScale(call)) {
                float[] xyz = numericArguments(call, frames, 3);
                if (xyz != null) multiply(matrix, scale(xyz[0], xyz[1], xyz[2]));
                continue;
            }
            if (axisRotation(call)) {
                Number degrees = argumentNumber(call, 0, frames, 0);
                String axis = axisField(call, frames);
                if (degrees != null && axis != null) {
                    multiply(matrix, rotation(axis, degrees.floatValue()));
                }
            }
        }
        return matrix;
    }

    private static boolean poseTranslate(MethodInsnNode call) {
        if (call == null || call.owner == null || call.name == null) return false;
        String owner = call.owner.toLowerCase(Locale.ROOT);
        if (!owner.contains("posestack")) return false;
        if (!"(FFF)V".equals(call.desc) && !"(DDD)V".equals(call.desc)) return false;
        String name = call.name.toLowerCase(Locale.ROOT);
        return name.contains("translat") || "m_252880_".equals(call.name) || "m_85837_".equals(call.name);
    }

    private static boolean poseScale(MethodInsnNode call) {
        if (call == null || call.owner == null || call.name == null || !"(FFF)V".equals(call.desc)) return false;
        String owner = call.owner.toLowerCase(Locale.ROOT);
        if (!owner.contains("posestack")) return false;
        String name = call.name.toLowerCase(Locale.ROOT);
        return name.contains("scale") || "m_85841_".equals(call.name);
    }

    private static boolean axisRotation(MethodInsnNode call) {
        if (call == null || call.owner == null || call.desc == null) return false;
        return "com/mojang/math/Axis".equals(call.owner)
                && "(F)Lorg/joml/Quaternionf;".equals(call.desc);
    }

    private static String axisField(MethodInsnNode call, Map<AbstractInsnNode, Frame<SourceValue>> frames) {
        Frame<SourceValue> frame = frames.get(call);
        if (frame == null || frame.getStackSize() < 2) return null;
        SourceValue receiver = frame.getStack(frame.getStackSize() - 2);
        if (receiver == null || receiver.insns == null || receiver.insns.size() != 1) return null;
        AbstractInsnNode source = receiver.insns.iterator().next();
        if (!(source instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC
                || !"com/mojang/math/Axis".equals(field.owner)) return null;
        String name = field.name.toUpperCase(Locale.ROOT);
        if (name.endsWith("XP")) return "XP";
        if (name.endsWith("XN")) return "XN";
        if (name.endsWith("YP")) return "YP";
        if (name.endsWith("YN")) return "YN";
        if (name.endsWith("ZP")) return "ZP";
        if (name.endsWith("ZN")) return "ZN";
        return null;
    }

    private static Map<AbstractInsnNode, Frame<SourceValue>> analyze(String owner, MethodNode method) {
        java.util.IdentityHashMap<AbstractInsnNode, Frame<SourceValue>> out = new java.util.IdentityHashMap<>();
        if (method == null || method.instructions == null || method.instructions.size() > 48_000) return out;
        try {
            Frame<SourceValue>[] analyzed = new Analyzer<>(new SourceInterpreter())
                    .analyze(owner, method);
            for (int i = 0; i < analyzed.length; i++) out.put(method.instructions.get(i), analyzed[i]);
        } catch (AnalyzerException | RuntimeException ignored) {
        }
        return out;
    }

    private static float[] numericArguments(MethodInsnNode call,
                                            Map<AbstractInsnNode, Frame<SourceValue>> frames,
                                            int count) {
        Type[] args = Type.getArgumentTypes(call.desc);
        if (args.length != count) return null;
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            Number value = argumentNumber(call, i, frames, 0);
            if (value == null) return null;
            out[i] = value.floatValue();
        }
        return out;
    }

    private static Number argumentNumber(MethodInsnNode call, int index,
                                         Map<AbstractInsnNode, Frame<SourceValue>> frames,
                                         int depth) {
        Type[] args = Type.getArgumentTypes(call.desc);
        Frame<SourceValue> frame = frames.get(call);
        if (frame == null || index < 0 || index >= args.length || frame.getStackSize() < args.length) return null;
        return number(frame.getStack(frame.getStackSize() - args.length + index), frames, depth + 1);
    }

    private static Number number(SourceValue value, Map<AbstractInsnNode, Frame<SourceValue>> frames,
                                 int depth) {
        if (value == null || value.insns == null || value.insns.size() != 1 || depth > 6) return null;
        AbstractInsnNode source = value.insns.iterator().next();
        Number direct = directNumber(source);
        if (direct != null) return direct;
        if (!(source instanceof InsnNode op)) return null;
        Frame<SourceValue> frame = frames.get(source);
        if (frame == null || frame.getStackSize() < 1) return null;
        Number input = number(frame.getStack(frame.getStackSize() - 1), frames, depth + 1);
        if (input == null) return null;
        return switch (op.getOpcode()) {
            case Opcodes.FNEG, Opcodes.DNEG, Opcodes.INEG, Opcodes.LNEG -> -input.doubleValue();
            case Opcodes.I2F, Opcodes.I2D, Opcodes.I2L, Opcodes.F2D, Opcodes.F2I, Opcodes.F2L,
                 Opcodes.D2F, Opcodes.D2I, Opcodes.D2L, Opcodes.L2D, Opcodes.L2F, Opcodes.L2I -> input;
            default -> null;
        };
    }

    private static Number directNumber(AbstractInsnNode instruction) {
        if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Number number) return number;
        if (instruction instanceof IntInsnNode value) return value.operand;
        if (instruction instanceof InsnNode op) {
            return switch (op.getOpcode()) {
                case Opcodes.ICONST_M1 -> -1;
                case Opcodes.ICONST_0, Opcodes.FCONST_0, Opcodes.DCONST_0, Opcodes.LCONST_0 -> 0;
                case Opcodes.ICONST_1, Opcodes.FCONST_1, Opcodes.DCONST_1, Opcodes.LCONST_1 -> 1;
                case Opcodes.ICONST_2, Opcodes.FCONST_2 -> 2;
                case Opcodes.ICONST_3 -> 3;
                case Opcodes.ICONST_4 -> 4;
                case Opcodes.ICONST_5 -> 5;
                default -> null;
            };
        }
        return null;
    }

    private static float[] identity() {
        return new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    }

    private static float[] translation(float x, float y, float z) {
        float[] out = identity();
        out[12] = x; out[13] = y; out[14] = z;
        return out;
    }

    private static float[] scale(float x, float y, float z) {
        float[] out = identity();
        out[0] = x; out[5] = y; out[10] = z;
        return out;
    }

    private static float[] rotation(String axis, float degrees) {
        float sign = axis.endsWith("N") ? -1f : 1f;
        float radians = (float) Math.toRadians(degrees * sign);
        float c = (float) Math.cos(radians), s = (float) Math.sin(radians);
        float[] out = identity();
        switch (axis.charAt(0)) {
            case 'X' -> { out[5]=c; out[6]=s; out[9]=-s; out[10]=c; }
            case 'Y' -> { out[0]=c; out[2]=-s; out[8]=s; out[10]=c; }
            case 'Z' -> { out[0]=c; out[1]=s; out[4]=-s; out[5]=c; }
            default -> { return identity(); }
        }
        return out;
    }

    /** Column-major post-multiply matching PoseStack's matrix order. */
    private static void multiply(float[] left, float[] right) {
        float[] result = new float[16];
        for (int col = 0; col < 4; col++) for (int row = 0; row < 4; row++) {
            float value = 0f;
            for (int k = 0; k < 4; k++) value += left[k * 4 + row] * right[col * 4 + k];
            result[col * 4 + row] = value;
        }
        System.arraycopy(result, 0, left, 0, 16);
    }

    private static void putBest(Map<String, Visual> out, Visual candidate) {
        if (candidate == null || candidate.itemId == null || candidate.itemId.isBlank()
                || candidate.layerModel == null || candidate.layerModel.cubes.isEmpty()) return;
        Visual previous = out.get(candidate.itemId);
        if (previous == null || candidate.confidence > previous.confidence
                || candidate.confidence == previous.confidence
                && candidate.layerModel.cubes.size() > previous.layerModel.cubes.size()) {
            out.put(candidate.itemId, candidate);
        }
    }

    private static Map<String, String> staticTextureFields(Map<String, ClassNode> nodes) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (ClassNode node : nodes.values()) {
            if (node == null || node.methods == null) continue;
            for (MethodNode method : node.methods) {
                if (!"<clinit>".equals(method.name)) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                            || !resourceDescriptor(field.desc)) continue;
                    String ref = resourceLiteralBefore(instruction, 24);
                    if (ref != null) out.put(field.owner + '.' + field.name, ref);
                }
            }
        }
        return out;
    }

    private static String resourceLiteralBefore(AbstractInsnNode instruction, int steps) {
        ArrayList<String> strings = new ArrayList<>(2);
        int code = 0;
        for (AbstractInsnNode cursor = instruction.getPrevious(); cursor != null && code < steps;
             cursor = cursor.getPrevious()) {
            if (cursor.getOpcode() >= 0) code++;
            if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC) break;
            if (cursor instanceof LdcInsnNode ldc && ldc.cst instanceof String value) strings.add(value);
        }
        String path = null, namespace = null;
        for (String value : strings) {
            String normalized = value.replace('\\', '/');
            String lower = normalized.toLowerCase(Locale.ROOT);
            if (path == null && lower.contains("textures/") && lower.endsWith(".png")) path = normalized;
            else if (path != null && namespace == null && normalized.matches("[a-z0-9_.-]+")) namespace = normalized;
        }
        if (path == null) return null;
        int colon = path.indexOf(':');
        if (colon >= 0) {
            namespace = path.substring(0, colon);
            path = path.substring(colon + 1);
        }
        int texture = path.toLowerCase(Locale.ROOT).indexOf("textures/");
        if (texture >= 0) path = path.substring(texture + "textures/".length());
        if (path.toLowerCase(Locale.ROOT).endsWith(".png")) path = path.substring(0, path.length() - 4);
        return namespace == null ? path : namespace + ':' + path;
    }

    private static Map<String, ArmorVisualBytecodeScanner.LayerModel> modelLayerFactories(
            Map<String, ClassNode> nodes) {
        LinkedHashMap<String, ArmorVisualBytecodeScanner.LayerModel> out = new LinkedHashMap<>();
        for (ClassNode node : nodes.values()) {
            if (node == null || node.methods == null) continue;
            for (MethodNode method : node.methods) {
                if (method.instructions == null) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode call)) continue;
                    Type[] args = Type.getArgumentTypes(call.desc);
                    boolean hasLayer = false, hasSupplier = false;
                    for (Type arg : args) {
                        if (arg.getSort() != Type.OBJECT) continue;
                        if (arg.getInternalName().toLowerCase(Locale.ROOT).contains("modellayerlocation")) hasLayer = true;
                        if ("java/util/function/Supplier".equals(arg.getInternalName())) hasSupplier = true;
                    }
                    if (!hasLayer || !hasSupplier) continue;
                    FieldInsnNode field = nearestStaticField(instruction, 18,
                            descriptor -> descriptor.toLowerCase(Locale.ROOT).contains("modellayerlocation"));
                    InvokeDynamicInsnNode supplier = nearestInvokeDynamic(instruction, 14);
                    if (field == null || supplier == null) continue;
                    MethodNode factory = null;
                    ClassNode factoryOwner = null;
                    for (Object arg : supplier.bsmArgs) {
                        if (!(arg instanceof Handle handle)) continue;
                        ClassNode candidate = nodes.get(handle.getOwner());
                        if (candidate == null || candidate.methods == null) continue;
                        for (MethodNode target : candidate.methods) {
                            if (target.name.equals(handle.getName()) && target.desc.equals(handle.getDesc())
                                    && Type.getReturnType(target.desc).getSort() == Type.OBJECT
                                    && Type.getReturnType(target.desc).getInternalName().endsWith("LayerDefinition")) {
                                factory = target; factoryOwner = candidate; break;
                            }
                        }
                        if (factory != null) break;
                    }
                    ArmorVisualBytecodeScanner.LayerModel layer = factory == null ? null
                            : ArmorVisualBytecodeScanner.parseLayerFactory(factoryOwner, factory);
                    if (layer != null && !layer.cubes.isEmpty()) out.put(field.owner + '.' + field.name, layer);
                }
            }
        }
        return out;
    }

    private static String layerFactoryOwner(String fieldKey, Map<String, ClassNode> nodes) {
        if (fieldKey == null) return "";
        int dot = fieldKey.lastIndexOf('.');
        return dot < 0 ? "" : fieldKey.substring(0, dot);
    }

    private static TypeInsnNode nearestRendererAllocation(AbstractInsnNode sink,
                                                          Map<String, ClassNode> nodes, int steps) {
        int code = 0;
        for (AbstractInsnNode cursor = sink.getPrevious(); cursor != null && code < steps;
             cursor = cursor.getPrevious()) {
            if (cursor.getOpcode() >= 0) code++;
            if (cursor instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                    && isItemRenderer(nodes.get(allocation.desc), nodes)) return allocation;
        }
        return null;
    }

    private static MethodInsnNode rendererConstructorBetween(TypeInsnNode allocation, AbstractInsnNode sink) {
        for (AbstractInsnNode cursor = allocation.getNext(); cursor != null && cursor != sink; cursor = cursor.getNext()) {
            if (cursor instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && "<init>".equals(call.name) && allocation.desc.equals(call.owner)) return call;
        }
        return null;
    }

    private static BehaviorBytecodeScanner.RegistryRef nearestRegistryItem(
            AbstractInsnNode start, Map<String, BehaviorBytecodeScanner.RegistryRef> registryFields, int steps) {
        int code = 0;
        for (AbstractInsnNode cursor = start.getPrevious(); cursor != null && code < steps;
             cursor = cursor.getPrevious()) {
            if (cursor.getOpcode() >= 0) code++;
            if (!(cursor instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC) continue;
            BehaviorBytecodeScanner.RegistryRef ref = registryFields.get(field.owner + '.' + field.name);
            if (ref != null && itemKind(ref.registryKind)) return ref;
        }
        return null;
    }

    private static boolean itemKind(String kind) {
        return "item".equals(kind) || "registry".equals(kind);
    }

    private static JumpInsnNode itemComparisonAfter(AbstractInsnNode start, int steps) {
        int code = 0;
        for (AbstractInsnNode cursor = start.getNext(); cursor != null && code < steps; cursor = cursor.getNext()) {
            if (cursor.getOpcode() >= 0) code++;
            if (cursor instanceof JumpInsnNode jump
                    && (jump.getOpcode() == Opcodes.IF_ACMPNE || jump.getOpcode() == Opcodes.IF_ACMPEQ)) return jump;
            if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) break;
        }
        return null;
    }

    private static AbstractInsnNode nextComparisonBoundary(AbstractInsnNode start, int steps) {
        int code = 0;
        for (AbstractInsnNode cursor = start; cursor != null && code < steps; cursor = cursor.getNext()) {
            if (cursor.getOpcode() >= 0) code++;
            if (cursor instanceof JumpInsnNode jump
                    && (jump.getOpcode() == Opcodes.IF_ACMPNE || jump.getOpcode() == Opcodes.IF_ACMPEQ)) return cursor;
        }
        return null;
    }

    private interface DescriptorPredicate { boolean test(String descriptor); }

    private static String staticFieldBetween(AbstractInsnNode start, AbstractInsnNode end,
                                             DescriptorPredicate predicate) {
        for (AbstractInsnNode cursor = start.getNext(); cursor != null && cursor != end; cursor = cursor.getNext()) {
            if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && predicate.test(field.desc)) return field.owner + '.' + field.name;
        }
        return null;
    }

    private static FieldInsnNode nearestStaticField(AbstractInsnNode start, int steps,
                                                    DescriptorPredicate predicate) {
        int code = 0;
        for (AbstractInsnNode cursor = start.getPrevious(); cursor != null && code < steps;
             cursor = cursor.getPrevious()) {
            if (cursor.getOpcode() >= 0) code++;
            if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && predicate.test(field.desc)) return field;
        }
        return null;
    }

    private static InvokeDynamicInsnNode nearestInvokeDynamic(AbstractInsnNode start, int steps) {
        int code = 0;
        for (AbstractInsnNode cursor = start.getPrevious(); cursor != null && code < steps;
             cursor = cursor.getPrevious()) {
            if (cursor.getOpcode() >= 0) code++;
            if (cursor instanceof InvokeDynamicInsnNode indy) return indy;
        }
        return null;
    }

    private static String singleRendererTexture(ClassNode renderer, Map<String, String> textures) {
        if (renderer == null) return null;
        LinkedHashSet<String> found = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : textures.entrySet()) {
            if (entry.getKey().startsWith(renderer.name + '.')) found.add(entry.getValue());
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }

    private static boolean isItemRenderer(ClassNode node, Map<String, ClassNode> nodes) {
        if (node == null) return false;
        for (ClassNode cursor = node; cursor != null; cursor = nodes.get(cursor.superName)) {
            if ("net/minecraft/client/renderer/BlockEntityWithoutLevelRenderer".equals(cursor.name)
                    || "net/minecraft/client/renderer/BlockEntityWithoutLevelRenderer".equals(cursor.superName)) return true;
            if (cursor.superName == null || cursor.superName.equals(cursor.name)) break;
        }
        if (node.methods == null) return false;
        for (MethodNode method : node.methods) if (itemRenderMethod(method)) return true;
        return false;
    }

    private static boolean itemRenderMethod(MethodNode method) {
        if (method == null || method.desc == null) return false;
        Type[] args = Type.getArgumentTypes(method.desc);
        boolean item = false, pose = false, buffer = false;
        for (Type arg : args) {
            if (arg.getSort() != Type.OBJECT) continue;
            String type = arg.getInternalName();
            if ("net/minecraft/world/item/ItemStack".equals(type)) item = true;
            else if (type != null && type.endsWith("/PoseStack")) pose = true;
            else if (type != null && type.contains("MultiBufferSource")) buffer = true;
        }
        return item && pose && buffer && Type.getReturnType(method.desc).getSort() == Type.VOID;
    }

    private static boolean resourceDescriptor(String descriptor) {
        return descriptor != null && descriptor.toLowerCase(Locale.ROOT).contains("resourcelocation");
    }

    private static String objectType(String descriptor) {
        try {
            Type type = Type.getType(descriptor);
            return type.getSort() == Type.OBJECT ? type.getInternalName() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getNext();
        return cursor;
    }
}
