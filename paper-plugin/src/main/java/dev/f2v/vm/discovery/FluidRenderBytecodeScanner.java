package dev.f2v.vm.discovery;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Recovers simple client-fluid material declarations directly from bytecode without loading any
 * client classes. A common registration shape is {@code register("fuel", () -> create(0xAARRGGBB))};
 * the supplier body is synthetic and the registration call carries its target through the lambda
 * bootstrap handle. This scanner joins those two facts structurally, so pack generation can retain
 * the authored tint on a dedicated server without naming the mod or its client API.
 */
public final class FluidRenderBytecodeScanner {
    private FluidRenderBytecodeScanner() {}

    public record Material(String path, int tint) {}

    public static List<Material> scan(Map<String, ClassNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        LinkedHashMap<String, Material> out = new LinkedHashMap<>();
        for (ClassNode node : nodes.values()) {
            if (node == null || node.methods == null) continue;
            Map<String, Integer> supplierTints = supplierTints(node);
            if (supplierTints.isEmpty()) continue;
            for (MethodNode method : node.methods) {
                if (!"<clinit>".equals(method.name)) continue;
                scanRegistrations(method, supplierTints, out);
            }
        }
        return List.copyOf(out.values());
    }

    static String baseFluidPath(String path) {
        if (path == null) return "";
        String value = path.toLowerCase(Locale.ROOT);
        if (value.startsWith("flowing_")) value = value.substring("flowing_".length());
        if (value.endsWith("_flowing")) value = value.substring(0, value.length() - "_flowing".length());
        return value;
    }

    private static Map<String, Integer> supplierTints(ClassNode node) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        for (MethodNode method : node.methods) {
            if (method == null || method.name == null || method.desc == null
                    || !method.desc.startsWith("()L") || !method.desc.endsWith(";")) continue;
            Integer tint = singlePackedColor(method);
            if (tint != null) out.put(method.name + method.desc, tint);
        }
        return out;
    }

    private static Integer singlePackedColor(MethodNode method) {
        Integer candidate = null;
        for (AbstractInsnNode instruction : method.instructions) {
            Integer value = intConstant(instruction);
            if (value == null || !looksLikePackedColor(value)) continue;
            if (candidate != null && candidate.intValue() != value.intValue()) return null;
            candidate = value;
        }
        return candidate;
    }

    private static void scanRegistrations(MethodNode method, Map<String, Integer> supplierTints,
                                          Map<String, Material> out) {
        List<AbstractInsnNode> instructions = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) instructions.add(instruction);
        for (int i = 0; i < instructions.size(); i++) {
            AbstractInsnNode instruction = instructions.get(i);
            if (!(instruction instanceof MethodInsnNode call) || call.name == null || call.desc == null) continue;
            String lower = call.name.toLowerCase(Locale.ROOT);
            if (!lower.contains("register") || !call.desc.contains("Ljava/lang/String;")
                    || !call.desc.contains("Ljava/util/function/Supplier;")) continue;

            String path = null;
            String supplier = null;
            for (int j = i - 1, walked = 0; j >= 0 && walked < 16; j--, walked++) {
                AbstractInsnNode previous = instructions.get(j);
                if (path == null && previous instanceof LdcInsnNode ldc && ldc.cst instanceof String value
                        && validPath(value)) {
                    path = value;
                }
                if (supplier == null && previous instanceof InvokeDynamicInsnNode indy) {
                    supplier = supplierTarget(indy);
                }
                if (path != null && supplier != null) break;
            }
            Integer tint = supplier == null ? null : supplierTints.get(supplier);
            if (path != null && tint != null) out.putIfAbsent(path, new Material(path, tint));
        }
    }

    private static String supplierTarget(InvokeDynamicInsnNode indy) {
        if (indy == null || indy.bsmArgs == null) return null;
        for (Object argument : indy.bsmArgs) {
            if (argument instanceof Handle handle) return handle.getName() + handle.getDesc();
        }
        return null;
    }

    private static Integer intConstant(AbstractInsnNode instruction) {
        if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) return value;
        if (instruction instanceof IntInsnNode integer) return integer.operand;
        return switch (instruction.getOpcode()) {
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

    static boolean looksLikePackedColor(int value) {
        // Render tints are normally RGB/ARGB values; excluding tiny constants prevents ordinary
        // enum ordinals, booleans and dimensions in synthetic suppliers from becoming colours.
        return value < -0xFFFF || value > 0xFFFF;
    }

    private static boolean validPath(String value) {
        if (value == null || value.isBlank() || value.length() > 128) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9')
                    && c != '_' && c != '-' && c != '/' && c != '.') return false;
        }
        return true;
    }
}
