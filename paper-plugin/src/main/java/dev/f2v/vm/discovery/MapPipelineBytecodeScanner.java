package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers a mod's CPU-side block-to-map-pixel pipeline without loading its client classes.
 *
 * <p>The important distinction from a generic "map class" name match is executable shape. A
 * candidate must expose a mutable pixel object, a block-state writer, an int-channel colour
 * function which consumes a {@code Level}, and the tile/config objects used by that function.
 * Xaero's world-map pipeline has this shape, but none of the identifiers below are mod names or
 * package names. Runtime code can therefore call the discovered implementation instead of
 * maintaining an F2V copy of its lighting and colour arithmetic.</p>
 */
public final class MapPipelineBytecodeScanner {

    public static final class Pipeline {
        public String pixelClass;
        public String pixelConstructorDescriptor;
        public String writeMethod;
        public String writeDescriptor;
        public String colourMethod;
        public String colourDescriptor;
        public String tileClass;
        public String tileConstructorDescriptor;
        public String configClass;
        /** semantic lower-case field name -> exact bytecode field name */
        public final Map<String, String> configFields = new LinkedHashMap<>();

        @Override
        public String toString() {
            return pixelClass + "[write=" + writeMethod + writeDescriptor + ", colour="
                    + colourMethod + colourDescriptor + ", tile=" + tileClass
                    + ", config=" + configClass + ']';
        }
    }

    private MapPipelineBytecodeScanner() {}

    public static List<Pipeline> scan(Map<String, ClassNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        ArrayList<Pipeline> out = new ArrayList<>();
        for (ClassNode pixel : nodes.values()) {
            MethodNode write = blockWriter(pixel);
            if (write == null) continue;
            MethodNode colour = colourEntrypoint(pixel, nodes);
            if (colour == null) continue;
            Type[] arguments = Type.getArgumentTypes(colour.desc);
            String tile = tileType(arguments, nodes);
            String config = configType(arguments, nodes);
            if (tile == null || config == null) continue;

            Pipeline pipeline = new Pipeline();
            pipeline.pixelClass = pixel.name;
            pipeline.pixelConstructorDescriptor = constructor(pixel, "()V") == null ? null : "()V";
            pipeline.writeMethod = write.name;
            pipeline.writeDescriptor = write.desc;
            pipeline.colourMethod = colour.name;
            pipeline.colourDescriptor = colour.desc;
            pipeline.tileClass = tile;
            MethodNode tileCtor = varargsConstructor(nodes.get(tile));
            pipeline.tileConstructorDescriptor = tileCtor == null ? null : tileCtor.desc;
            pipeline.configClass = config;
            ClassNode configNode = nodes.get(config);
            if (configNode != null && configNode.fields != null) {
                for (FieldNode field : configNode.fields) {
                    if ("I".equals(field.desc) || "Z".equals(field.desc)) {
                        pipeline.configFields.put(normalize(field.name), field.name);
                    }
                }
            }
            if (pipeline.pixelConstructorDescriptor != null
                    && pipeline.tileConstructorDescriptor != null
                    && !pipeline.configFields.isEmpty()) out.add(pipeline);
        }
        out.sort(Comparator.comparing(value -> value.pixelClass));
        return List.copyOf(out);
    }

    public static List<Pipeline> all(Collection<ModScanner.Profile> profiles) {
        if (profiles == null) return List.of();
        ArrayList<Pipeline> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collect(profile, out, seen);
        out.sort(Comparator.comparing(value -> value.pixelClass));
        return List.copyOf(out);
    }

    private static void collect(ModScanner.Profile profile, List<Pipeline> out, Set<String> seen) {
        if (profile == null) return;
        if (profile.mapPipelines != null) {
            for (Pipeline pipeline : profile.mapPipelines) {
                if (pipeline != null && pipeline.pixelClass != null
                        && seen.add(pipeline.pixelClass)) out.add(pipeline);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile nested : profile.nested) collect(nested, out, seen);
        }
    }

    private static MethodNode blockWriter(ClassNode node) {
        if (node == null || node.methods == null) return null;
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0
                    || Type.getReturnType(method.desc).getSort() != Type.VOID) continue;
            Type[] args = Type.getArgumentTypes(method.desc);
            if (args.length < 5 || args[0].getSort() != Type.OBJECT
                    || !args[0].getInternalName().endsWith("/BlockState")) continue;
            int ints = 0;
            int booleans = 0;
            for (Type arg : args) {
                if (arg.getSort() == Type.INT) ints++;
                else if (arg.getSort() == Type.BOOLEAN) booleans++;
            }
            if (ints >= 2 && booleans >= 1) return method;
        }
        return null;
    }

    private static MethodNode colourEntrypoint(ClassNode node, Map<String, ClassNode> nodes) {
        if (node == null || node.methods == null) return null;
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0
                    || Type.getReturnType(method.desc).getSort() != Type.VOID) continue;
            Type[] args = Type.getArgumentTypes(method.desc);
            if (args.length < 8 || args[0].getSort() != Type.ARRAY
                    || args[0].getElementType().getSort() != Type.INT) continue;
            boolean level = false;
            boolean delegatesToPixelMath = false;
            for (Type arg : args) {
                level |= arg.getSort() == Type.OBJECT
                        && arg.getInternalName().endsWith("/world/level/Level");
            }
            for (AbstractInsnNode insn = method.instructions == null ? null
                    : method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(node.superName)
                        && call.desc.startsWith("([I") && call.desc.endsWith(")V")) {
                    ClassNode parent = nodes.get(call.owner);
                    MethodNode target = exact(parent, call.name, call.desc);
                    delegatesToPixelMath = target != null && readsMapColour(target);
                }
            }
            if (level && delegatesToPixelMath) return method;
        }
        return null;
    }

    private static boolean readsMapColour(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions == null ? null
                : method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.endsWith("/world/level/material/MapColor")
                    && "I".equals(field.desc)) return true;
        }
        return false;
    }

    private static String tileType(Type[] arguments, Map<String, ClassNode> nodes) {
        for (Type argument : arguments) {
            if (argument.getSort() != Type.OBJECT) continue;
            ClassNode candidate = nodes.get(argument.getInternalName());
            if (candidate == null) continue;
            boolean x = candidate.methods.stream().anyMatch(method -> "()I".equals(method.desc)
                    && methodReferencesField(method, candidate.name, "I"));
            long integerGetters = candidate.methods.stream()
                    .filter(method -> "()I".equals(method.desc)).count();
            if (x && integerGetters >= 2 && varargsConstructor(candidate) != null) {
                return candidate.name;
            }
        }
        return null;
    }

    private static String configType(Type[] arguments, Map<String, ClassNode> nodes) {
        for (int i = arguments.length - 1; i >= 0; i--) {
            Type argument = arguments[i];
            if (argument.getSort() != Type.OBJECT) continue;
            ClassNode candidate = nodes.get(argument.getInternalName());
            if (candidate == null || candidate.fields == null) continue;
            long primitiveSettings = candidate.fields.stream()
                    .filter(field -> "I".equals(field.desc) || "Z".equals(field.desc)).count();
            if (primitiveSettings >= 4) return candidate.name;
        }
        return null;
    }

    private static boolean methodReferencesField(MethodNode method, String owner, String descriptor) {
        for (AbstractInsnNode insn = method.instructions == null ? null
                : method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode field && owner.equals(field.owner)
                    && descriptor.equals(field.desc)) return true;
        }
        return false;
    }

    private static MethodNode varargsConstructor(ClassNode node) {
        if (node == null || node.methods == null) return null;
        for (MethodNode method : node.methods) {
            if ("<init>".equals(method.name) && "([Ljava/lang/Object;)V".equals(method.desc)) {
                return method;
            }
        }
        return null;
    }

    private static MethodNode constructor(ClassNode node, String descriptor) {
        return exact(node, "<init>", descriptor);
    }

    private static MethodNode exact(ClassNode node, String name, String descriptor) {
        if (node == null || node.methods == null) return null;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }
}
