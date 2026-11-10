package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

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
 * Server-safe armor visual discovery from class bytecode.
 *
 * <p>Forge/NeoForge armor hooks frequently live in client extension objects that a dedicated
 * server must never load.  This scanner follows class references from each ArmorItem subclass,
 * recovers constant texture ResourceLocations, finds the returned HumanoidModel class, and reads
 * the static LayerDefinition/CubeListBuilder program directly.  The result is plain data that pack
 * generation can consume without executing any client class or relying on armor file-name guesses.</p>
 */
public final class ArmorVisualBytecodeScanner {

    public static final class Cube {
        public String part = "";
        public String parent = "";
        public float x, y, z, dx, dy, dz;
        public float inflate;
        public int texU, texV;
        public boolean mirror;
        /** Part pivot relative to its parent, in ModelPart pixels. */
        public float pivotX, pivotY, pivotZ;
        /** Part rotation in radians when recoverable. */
        public float rotX, rotY, rotZ;
    }

    public static final class Visual {
        /** Binary JVM name using slashes, normally the concrete ArmorItem class. */
        public String ownerClass = "";
        public String modelClass = "";
        /** Model texture references (namespace:path, with textures/ + .png stripped). */
        public final List<String> textureRefs = new ArrayList<>();
        public final List<Cube> cubes = new ArrayList<>();
        public int textureWidth = 64;
        public int textureHeight = 32;
        public int confidence;
    }

    /** Generic vanilla ModelPart/LayerDefinition geometry recovered without loading the model. */
    public static final class LayerModel {
        public ModelPartAnimationScanner.Program animation;
        public final List<Cube> cubes = new ArrayList<>();
        /** Every authored ModelPart pose, including empty grouping nodes. */
        public final List<PartPose> parts = new ArrayList<>();
        public int textureWidth = 64;
        public int textureHeight = 32;
    }

    public static final class PartPose {
        public String name = "";
        public String parent = "";
        public float pivotX, pivotY, pivotZ;
        public float rotX, rotY, rotZ;
    }

    private record Evidence(Set<String> textures, Set<String> modelClasses,
                            Set<String> classRefs) {}

    private ArmorVisualBytecodeScanner() {}

    public static List<Visual> scan(Map<String, ClassNode> nodes, String modId) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        Map<String, Evidence> evidence = new LinkedHashMap<>();
        for (ClassNode node : nodes.values()) evidence.put(node.name, evidence(node, nodes, modId));

        List<Visual> out = new ArrayList<>();
        for (ClassNode node : nodes.values()) {
            if (!isArmorItem(node, nodes)) continue;
            Visual visual = resolve(node.name, nodes, evidence, modId);
            if (visual != null) out.add(visual);
        }
        // Some bytecode sets omit the concrete item class (nested jars / optional client split).
        // Preserve strong self-contained armor evidence so it is still visible in scan reports.
        if (out.isEmpty()) {
            for (ClassNode node : nodes.values()) {
                Evidence e = evidence.get(node.name);
                if (e == null || e.textures.isEmpty() && e.modelClasses.isEmpty()) continue;
                Visual visual = resolve(node.name, nodes, evidence, modId);
                if (visual != null) out.add(visual);
            }
        }
        out.sort(Comparator.comparing((Visual v) -> v.ownerClass)
                .thenComparing(v -> v.modelClass == null ? "" : v.modelClass));
        return List.copyOf(out);
    }

    /** Best exact scanner result for the live armor item's concrete class. */
    public static Visual forItemClass(Collection<ModScanner.Profile> profiles, String binaryName) {
        if (profiles == null || binaryName == null || binaryName.isBlank()) return null;
        String internal = binaryName.replace('.', '/');
        Visual best = null;
        for (ModScanner.Profile profile : profiles) {
            if (profile == null || profile.armorVisuals == null) continue;
            for (Visual visual : profile.armorVisuals) {
                if (visual == null || visual.ownerClass == null) continue;
                boolean exact = internal.equals(visual.ownerClass);
                boolean family = stripInner(internal).equals(stripInner(visual.ownerClass));
                if (!exact && !family) continue;
                if (best == null || exact && !internal.equals(best.ownerClass)
                        || visual.confidence > best.confidence) best = visual;
            }
        }
        return best;
    }

    private static Visual resolve(String owner, Map<String, ClassNode> nodes,
                                  Map<String, Evidence> evidence, String modId) {
        LinkedHashSet<String> textures = new LinkedHashSet<>();
        LinkedHashSet<String> models = new LinkedHashSet<>();
        // Short structural BFS: item -> client extension -> armor renderer/model.  This follows
        // actual bytecode references rather than package/name conventions.
        ArrayDeque<String> queue = new ArrayDeque<>();
        Map<String, Integer> depth = new HashMap<>();
        queue.add(owner);
        depth.put(owner, 0);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            int d = depth.get(current);
            Evidence e = evidence.get(current);
            if (e == null) continue;
            textures.addAll(e.textures);
            models.addAll(e.modelClasses);
            if (d >= 3) continue;
            for (String ref : e.classRefs) {
                if (!nodes.containsKey(ref) || depth.containsKey(ref)) continue;
                // Do not walk the entire mod graph. Client extensions/models and nested helper
                // classes are close to the owner; unrelated registries are not useful here.
                if (d > 0 && !looksArmorRelated(ref, nodes.get(ref))) continue;
                depth.put(ref, d + 1);
                queue.addLast(ref);
            }
        }
        if (textures.isEmpty() && models.isEmpty()) return null;

        Visual out = new Visual();
        out.ownerClass = owner;
        out.textureRefs.addAll(textures);
        String model = models.stream().filter(nodes::containsKey)
                .sorted(Comparator.comparingInt(value -> depth.getOrDefault(value, 99)))
                .findFirst().orElse(models.stream().findFirst().orElse(""));
        out.modelClass = model;
        if (!model.isBlank()) parseModel(nodes.get(model), out);
        out.confidence = (!out.textureRefs.isEmpty() ? 55 : 0)
                + (!out.modelClass.isBlank() ? 25 : 0)
                + (!out.cubes.isEmpty() ? 20 : 0);
        return out;
    }

    private static Evidence evidence(ClassNode node, Map<String, ClassNode> nodes, String modId) {
        LinkedHashSet<String> textures = new LinkedHashSet<>();
        LinkedHashSet<String> models = new LinkedHashSet<>();
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        if (node.superName != null) refs.add(node.superName);
        if (node.interfaces != null) refs.addAll(node.interfaces);
        for (MethodNode method : node.methods) {
            boolean armorMethod = method.name.toLowerCase(Locale.ROOT).contains("armor")
                    || method.desc.toLowerCase(Locale.ROOT).contains("armor")
                    || method.desc.contains("HumanoidModel")
                    || method.desc.contains("IClientItemExtensions");
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null;
                 insn = insn.getNext()) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String raw) {
                    String texture = normalizeTexture(raw, modId);
                    if (texture != null && (armorMethod || strongArmorTexture(raw))) textures.add(texture);
                } else if (insn instanceof FieldInsnNode field) {
                    refs.add(field.owner);
                    String type = objectType(field.desc);
                    if (type != null) refs.add(type);
                    if (type != null && isHumanoidModel(type, nodes)) models.add(type);
                    if (isHumanoidModel(field.owner, nodes)) models.add(field.owner);
                } else if (insn instanceof MethodInsnNode call) {
                    refs.add(call.owner);
                    for (Type arg : Type.getArgumentTypes(call.desc)) {
                        if (arg.getSort() == Type.OBJECT) refs.add(arg.getInternalName());
                    }
                    Type result = Type.getReturnType(call.desc);
                    if (result.getSort() == Type.OBJECT) {
                        refs.add(result.getInternalName());
                        if (isHumanoidModel(result.getInternalName(), nodes)) {
                            models.add(result.getInternalName());
                        }
                    }
                    if (isHumanoidModel(call.owner, nodes)) models.add(call.owner);
                    recoverResourceLocation(call, textures, modId);
                } else if (insn instanceof TypeInsnNode type) {
                    refs.add(type.desc);
                    if (isHumanoidModel(type.desc, nodes)) models.add(type.desc);
                }
            }
        }
        // Model classes often never say "armor" but their superclass proves their semantics.
        if (isHumanoidModel(node.name, nodes) && !node.name.startsWith("net/minecraft/")) {
            models.add(node.name);
        }
        return new Evidence(textures, models, refs);
    }

    private static void recoverResourceLocation(MethodInsnNode call, Set<String> textures,
                                                String modId) {
        String owner = call.owner == null ? "" : call.owner;
        if (!owner.endsWith("ResourceLocation")) return;
        List<String> strings = previousStrings(call, 2, 12);
        if (strings.isEmpty()) return;
        if (strings.size() >= 2) {
            String texture = normalizeTexture(strings.get(strings.size() - 2) + ":"
                    + strings.get(strings.size() - 1), modId);
            if (texture != null) textures.add(texture);
        }
        String texture = normalizeTexture(strings.get(strings.size() - 1), modId);
        if (texture != null) textures.add(texture);
    }

    private static List<String> previousStrings(AbstractInsnNode start, int max, int steps) {
        ArrayList<String> values = new ArrayList<>();
        AbstractInsnNode insn = start.getPrevious();
        for (int i = 0; insn != null && i < steps && values.size() < max; i++, insn = insn.getPrevious()) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String value) values.add(0, value);
        }
        return values;
    }

    private static String normalizeTexture(String raw, String modId) {
        if (raw == null) return null;
        String value = raw.trim().replace('\\', '/');
        if (!strongArmorTexture(value)) return null;
        int colon = value.indexOf(':');
        String namespace = colon > 0 ? value.substring(0, colon) : modId;
        String path = colon > 0 ? value.substring(colon + 1) : value;
        while (path.startsWith("/")) path = path.substring(1);
        if (path.startsWith("assets/")) {
            String rest = path.substring("assets/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                namespace = rest.substring(0, slash);
                path = rest.substring(slash + 1);
            }
        }
        if (path.startsWith("textures/")) path = path.substring("textures/".length());
        if (path.endsWith(".png")) path = path.substring(0, path.length() - 4);
        if (namespace == null || namespace.isBlank() || path.isBlank()) return null;
        return namespace + ':' + path;
    }

    private static boolean strongArmorTexture(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.ROOT).replace('\\', '/');
        return lower.contains("armor") && (lower.contains("texture") || lower.endsWith(".png")
                || lower.contains("models/armor/") || lower.contains("entity/armor/")
                || lower.contains("item/armor/"));
    }

    private static boolean isArmorItem(ClassNode node, Map<String, ClassNode> nodes) {
        if (node == null) return false;
        String current = node.name;
        Set<String> seen = new HashSet<>();
        while (current != null && seen.add(current)) {
            if (current.endsWith("/ArmorItem") || current.equals("net/minecraft/world/item/ArmorItem")) return true;
            ClassNode next = nodes.get(current);
            if (next == null) break;
            current = next.superName;
        }
        return false;
    }

    private static boolean isHumanoidModel(String internal, Map<String, ClassNode> nodes) {
        if (internal == null) return false;
        String current = internal;
        Set<String> seen = new HashSet<>();
        while (current != null && seen.add(current)) {
            if (current.endsWith("/HumanoidModel") || current.equals("net/minecraft/client/model/HumanoidModel")) {
                return true;
            }
            ClassNode node = nodes.get(current);
            if (node == null) break;
            current = node.superName;
        }
        return false;
    }

    private static boolean looksArmorRelated(String internal, ClassNode node) {
        String lower = internal == null ? "" : internal.toLowerCase(Locale.ROOT);
        if (lower.contains("armor") || lower.contains("armour") || lower.contains("helmet")
                || lower.contains("boots") || lower.contains("chest") || lower.contains("legging")) return true;
        if (node == null) return false;
        String superName = node.superName == null ? "" : node.superName;
        return superName.contains("ArmorItem") || superName.contains("HumanoidModel")
                || node.interfaces.stream().anyMatch(value -> value.contains("ClientItem"));
    }

    /** Reads CubeListBuilder + PartPose instructions from a standard static layer factory. */
    private static void parseModel(ClassNode model, Visual out) {
        LayerModel layer = parseLayerModel(model);
        if (layer == null) return;
        out.cubes.addAll(layer.cubes);
        out.textureWidth = layer.textureWidth;
        out.textureHeight = layer.textureHeight;
    }

    /**
     * Shared decoder for renderer-owned vanilla model layers. Armor is only one consumer; block
     * entity renderers frequently build their entire model through the same CubeListBuilder API.
     */
    public static LayerModel parseLayerModel(ClassNode model) {
        if (model == null) return null;
        ParseResult best = null;
        for (MethodNode method : model.methods) {
            if (!mentionsLayerBuilder(method)) continue;
            ParseResult parsed = parseLayerMethod(model.name, method);
            if (best == null || parsed.cubes.size() > best.cubes.size()) {
                best = parsed;
            }
        }
        if (best == null || best.cubes.isEmpty()) return null;
        LayerModel out = new LayerModel();
        out.cubes.addAll(best.cubes);
        out.parts.addAll(best.parts);
        out.textureWidth = best.width;
        out.textureHeight = best.height;
        java.util.Set<String> partNames = new java.util.HashSet<>();
        for (PartPose pose : best.parts) {
            if (pose != null && pose.name != null) partNames.add(pose.name);
        }
        out.animation = ModelPartAnimationScanner.scan(model, partNames);
        return out;
    }

    public static LayerModel parseLayerModel(ClassNode model, Map<String, ClassNode> classes) {
        LayerModel direct = parseLayerModel(model);
        if (direct != null || model == null) return direct;
        LayerModel factory = LayerFactoryInterpreter.read(model, classes);
        return factory != null ? factory : ModelConstructorBytecodeInterpreter.read(model, classes);
    }

    /** Reads one exact static LayerDefinition factory when another registration site proves which
     * factory belongs to a particular model-layer key. */
    static LayerModel parseLayerFactory(ClassNode owner, MethodNode method) {
        if (owner == null || method == null || !mentionsLayerBuilder(method)) return null;
        ParseResult parsed = parseLayerMethod(owner.name, method);
        if (parsed.cubes.isEmpty()) return null;
        LayerModel out = new LayerModel();
        out.cubes.addAll(parsed.cubes);
        out.parts.addAll(parsed.parts);
        out.textureWidth = parsed.width;
        out.textureHeight = parsed.height;
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (PartPose pose : parsed.parts) if (pose != null && pose.name != null) names.add(pose.name);
        out.animation = ModelPartAnimationScanner.scan(owner, names);
        return out;
    }

    private record ParseResult(List<Cube> cubes, List<PartPose> parts, int width, int height) {}

    private static ParseResult parseLayerMethod(String ownerClass, MethodNode method) {
        LayerOperands operands = new LayerOperands(ownerClass, method);
        Map<Integer, String> partLocal = discoverPartLocals(method, operands);
        List<Cube> cubes = new ArrayList<>();
        List<PartPose> parts = new ArrayList<>();
        List<Cube> pending = new ArrayList<>();
        String child = "";
        String parent = "";
        int texU = 0, texV = 0;
        boolean mirror = false;
        int width = 64, height = 32;

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            String owner = call.owner == null ? "" : call.owner;
            if (owner.endsWith("CubeListBuilder")) {
                Type[] args = Type.getArgumentTypes(call.desc);
                if (call.getOpcode() == Opcodes.INVOKESTATIC && args.length == 0) {
                    LdcInsnNode name = previousStringInsn(call, 8);
                    if (name != null) {
                        child = String.valueOf(name.cst);
                        parent = parentForName(name, partLocal);
                    }
                    texU = texV = 0;
                    mirror = false;
                } else if (args.length == 2 && args[0].getSort() == Type.INT
                        && args[1].getSort() == Type.INT) {
                    List<Number> values = previousNumbers(call, 2, 10, null);
                    if (values.size() == 2) {
                        texU = values.get(0).intValue();
                        texV = values.get(1).intValue();
                    }
                } else if (args.length == 0 && call.getOpcode() != Opcodes.INVOKESTATIC) {
                    // The standard builder's only fluent no-arg state toggle is mirror().
                    mirror = true;
                } else if (args.length == 1 && args[0].getSort() == Type.BOOLEAN) {
                    Number value = previousNumber(call, 5);
                    if (value != null) mirror = value.intValue() != 0;
                }

                if (hasSixFloatArguments(call.desc)) {
                    Number inflate = operands.inflation(call);
                    List<Number> values = operands.numbers(call, 6);
                    if (values.size() == 6) {
                        Cube cube = new Cube();
                        cube.part = child;
                        cube.parent = parent;
                        cube.x = values.get(0).floatValue();
                        cube.y = values.get(1).floatValue();
                        cube.z = values.get(2).floatValue();
                        cube.dx = values.get(3).floatValue();
                        cube.dy = values.get(4).floatValue();
                        cube.dz = values.get(5).floatValue();
                        cube.inflate = inflate == null ? 0f : inflate.floatValue();
                        cube.texU = texU;
                        cube.texV = texV;
                        cube.mirror = mirror;
                        pending.add(cube);
                    }
                }
            } else if (owner.endsWith("PartDefinition") && takesChildDefinition(call.desc)) {
                LdcInsnNode nameInsn = operands.string(call, 0);
                String name = nameInsn == null ? child : String.valueOf(nameInsn.cst);
                float[] pose = previousPartPose(call, 30);
                String receiverParent = nameInsn == null ? parent : parentForName(nameInsn, partLocal);
                PartPose partPose = new PartPose();
                partPose.name = name == null ? "" : name;
                partPose.parent = receiverParent == null ? "" : receiverParent;
                partPose.pivotX = pose[0]; partPose.pivotY = pose[1]; partPose.pivotZ = pose[2];
                partPose.rotX = pose[3]; partPose.rotY = pose[4]; partPose.rotZ = pose[5];
                if (!partPose.name.isBlank()) parts.add(partPose);
                for (Cube cube : pending) {
                    if (cube.part == null || cube.part.isBlank()) cube.part = name;
                    if (cube.parent == null || cube.parent.isBlank()) cube.parent = receiverParent;
                    cube.pivotX = pose[0]; cube.pivotY = pose[1]; cube.pivotZ = pose[2];
                    cube.rotX = pose[3]; cube.rotY = pose[4]; cube.rotZ = pose[5];
                    cubes.add(cube);
                }
                pending.clear();
                child = parent = "";
            } else if (owner.endsWith("LayerDefinition")) {
                Type[] args = Type.getArgumentTypes(call.desc);
                if (args.length >= 2 && args[args.length - 1].getSort() == Type.INT
                        && args[args.length - 2].getSort() == Type.INT) {
                    List<Number> dims = previousNumbers(call, 2, 12, null);
                    if (dims.size() == 2 && dims.get(0).intValue() > 0 && dims.get(1).intValue() > 0) {
                        width = dims.get(0).intValue();
                        height = dims.get(1).intValue();
                    }
                }
            }
        }
        return new ParseResult(List.copyOf(cubes), List.copyOf(parts), width, height);
    }

    private static Map<Integer, String> discoverPartLocals(MethodNode method, LayerOperands operands) {
        Map<Integer, String> out = new HashMap<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call) || !call.owner.endsWith("PartDefinition")) continue;
            Type[] args = Type.getArgumentTypes(call.desc);
            boolean namedChild = args.length == 1 && args[0].getSort() == Type.OBJECT
                    && "java/lang/String".equals(args[0].getInternalName());
            boolean addedChild = takesChildDefinition(call.desc);
            if (!namedChild && !addedChild) continue;
            LdcInsnNode name = operands.string(call, 0);
            AbstractInsnNode next = nextReal(call);
            if (name != null && next instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE) {
                out.put(store.var, String.valueOf(name.cst));
            }
        }
        return out;
    }

    private static String parentForName(LdcInsnNode name, Map<Integer, String> partLocal) {
        AbstractInsnNode insn = previousReal(name);
        for (int i = 0; insn != null && i < 5; i++, insn = previousReal(insn)) {
            if (insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
                String parent = partLocal.get(load.var);
                if (parent != null) return parent;
            }
        }
        return "";
    }

    /** Read builder arguments from the JVM operand stack. A deformation constructor and a long
     * fluent cube chain can contain many unrelated constants between a child name and its call. */
    private static final class LayerOperands {
        private final Map<AbstractInsnNode, org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>> frames
                = new java.util.IdentityHashMap<>();

        LayerOperands(String owner, MethodNode method) {
            if (method.instructions.size() > 24_000) return;
            try {
                var interpreter = new org.objectweb.asm.tree.analysis.SourceInterpreter(Opcodes.ASM9) {
                    @Override public org.objectweb.asm.tree.analysis.SourceValue copyOperation(
                            AbstractInsnNode instruction, org.objectweb.asm.tree.analysis.SourceValue value) {
                        return value;
                    }
                };
                var analyzed = new org.objectweb.asm.tree.analysis.Analyzer<>(interpreter).analyze(owner, method);
                for (int i = 0; i < analyzed.length; i++) frames.put(method.instructions.get(i), analyzed[i]);
            } catch (org.objectweb.asm.tree.analysis.AnalyzerException | RuntimeException ignored) {
            }
        }

        private AbstractInsnNode argument(MethodInsnNode call, int index) {
            var frame = frames.get(call);
            int count = Type.getArgumentTypes(call.desc).length;
            if (frame == null || index < 0 || index >= count || frame.getStackSize() < count) return null;
            var source = frame.getStack(frame.getStackSize() - count + index);
            return source.insns.size() == 1 ? source.insns.iterator().next() : null;
        }

        List<Number> numbers(MethodInsnNode call, int count) {
            List<Number> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Number value = number(argument(call, i));
                if (value == null) return List.of();
                result.add(value);
            }
            return result;
        }

        LdcInsnNode string(MethodInsnNode call, int index) {
            var source = argument(call, index);
            return source instanceof LdcInsnNode ldc && ldc.cst instanceof String ? ldc : null;
        }

        Number inflation(MethodInsnNode call) {
            Type[] types = Type.getArgumentTypes(call.desc);
            if (types.length < 7 || !types[6].getDescriptor().endsWith("CubeDeformation;")) return 0f;
            var allocation = argument(call, 6);
            if (allocation instanceof FieldInsnNode) return 0f;
            for (AbstractInsnNode cursor = call.getPrevious(); cursor != null && cursor != allocation;
                 cursor = cursor.getPrevious()) {
                if (!(cursor instanceof MethodInsnNode constructor) || !constructor.name.equals("<init>")
                        || !constructor.owner.endsWith("CubeDeformation")) continue;
                var frame = frames.get(constructor);
                int count = Type.getArgumentTypes(constructor.desc).length;
                if (frame == null || frame.getStackSize() <= count) continue;
                var receiver = frame.getStack(frame.getStackSize() - count - 1);
                if (receiver.insns.contains(allocation)) return number(argument(constructor, 0));
            }
            return null;
        }
    }

    private static boolean mentionsLayerBuilder(MethodNode method) {
        if (method.desc.contains("LayerDefinition")) return true;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && (call.owner.endsWith("CubeListBuilder")
                    || call.owner.endsWith("LayerDefinition"))) return true;
        }
        return false;
    }

    private static boolean hasSixFloatArguments(String desc) {
        Type[] args = Type.getArgumentTypes(desc);
        if (args.length < 6) return false;
        for (int i = 0; i < 6; i++) if (args[i].getSort() != Type.FLOAT) return false;
        return true;
    }

    private static boolean takesChildDefinition(String desc) {
        Type[] args = Type.getArgumentTypes(desc);
        return args.length >= 3 && args[0].getSort() == Type.OBJECT
                && "java/lang/String".equals(args[0].getInternalName())
                && args[1].getSort() == Type.OBJECT && args[1].getInternalName().endsWith("CubeListBuilder")
                && args[2].getSort() == Type.OBJECT && args[2].getInternalName().endsWith("PartPose");
    }

    private static float[] previousPartPose(AbstractInsnNode start, int steps) {
        AbstractInsnNode insn = start.getPrevious();
        for (int i = 0; insn != null && i < steps; i++, insn = insn.getPrevious()) {
            if (!(insn instanceof MethodInsnNode call) || !call.owner.endsWith("PartPose")) continue;
            Type[] args = Type.getArgumentTypes(call.desc);
            if (args.length == 3 || args.length == 6) {
                List<Number> values = previousNumbers(call, args.length, 20, null);
                if (values.size() == args.length) {
                    float[] out = new float[6];
                    for (int n = 0; n < Math.min(6, values.size()); n++) out[n] = values.get(n).floatValue();
                    return out;
                }
            }
        }
        return new float[6];
    }

    private static LdcInsnNode previousStringInsn(AbstractInsnNode start, int steps) {
        AbstractInsnNode insn = start.getPrevious();
        for (int i = 0; insn != null && i < steps; i++, insn = insn.getPrevious()) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String) return ldc;
        }
        return null;
    }

    private static Number previousNumber(AbstractInsnNode start, int steps) {
        List<Number> values = previousNumbers(start, 1, steps, null);
        return values.isEmpty() ? null : values.get(0);
    }

    /** Collects the last {@code count} numeric constants before start and returns program order. */
    private static List<Number> previousNumbers(AbstractInsnNode start, int count, int steps,
                                                AbstractInsnNode explicitStart) {
        ArrayList<Number> reversed = new ArrayList<>();
        AbstractInsnNode insn = explicitStart == null ? start.getPrevious() : explicitStart;
        for (int i = 0; insn != null && i < steps && reversed.size() < count;
             i++, insn = insn.getPrevious()) {
            Number number = number(insn);
            if (number != null) reversed.add(number);
        }
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private static Number number(AbstractInsnNode insn) {
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Number number) return number;
        if (insn instanceof IntInsnNode value) return value.operand;
        if (insn instanceof InsnNode op) {
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

    private static String objectType(String descriptor) {
        try {
            Type type = Type.getType(descriptor);
            return type.getSort() == Type.OBJECT ? type.getInternalName() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode cursor = insn == null ? null : insn.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getPrevious();
        return cursor;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode cursor = insn == null ? null : insn.getNext();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getNext();
        return cursor;
    }

    private static String stripInner(String internal) {
        int dollar = internal == null ? -1 : internal.indexOf('$');
        return dollar < 0 ? internal : internal.substring(0, dollar);
    }
}
