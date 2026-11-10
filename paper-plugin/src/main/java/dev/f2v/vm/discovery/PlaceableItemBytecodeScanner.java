package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers mod items whose ordinary server-side use-on path places world content.
 *
 * <p>This is intentionally semantic rather than name based. A candidate must be a concrete item
 * registry entry and its implementation must either inherit vanilla {@code BlockItem}, call a
 * block-placement mutation from a {@code UseOnContext} method, or spawn an entity from that method.
 * This covers conventional blocks as well as block-like placeables such as carts, cubes and other
 * entities which a mod exposes as an inventory item.</p>
 */
public final class PlaceableItemBytecodeScanner {
    public static final int SCHEMA = 1;

    public enum Mode { BLOCK_ITEM, BLOCK_MUTATION, ENTITY_SPAWN }

    public static final class Placement {
        public String itemId;
        public String implementationClass;
        public Mode mode;
        public int confidence;
    }

    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String BLOCK_ITEM = "net/minecraft/world/item/BlockItem";
    private static final String USE_ON_CONTEXT = "Lnet/minecraft/world/item/context/UseOnContext;";
    private static final String BLOCK_POS = "Lnet/minecraft/core/BlockPos;";
    private static final String BLOCK_STATE = "Lnet/minecraft/world/level/block/state/BlockState;";

    private PlaceableItemBytecodeScanner() {}

    public static List<Placement> scan(Map<String, ClassNode> nodes,
                                       Map<String, BehaviorBytecodeScanner.RegistryRef> registryFields) {
        if (registryFields == null || registryFields.isEmpty()) return List.of();
        Map<String, ClassNode> classes = nodes == null ? Map.of() : nodes;
        ArrayList<Placement> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (BehaviorBytecodeScanner.RegistryRef ref : registryFields.values()) {
            if (ref == null || ref.id == null || ref.implementationClass == null
                    || !"item".equals(ref.registryKind) || seen.contains(ref.id)) continue;
            ClassNode implementation = classes.get(ref.implementationClass);
            // Vanilla BlockItem itself lives outside the scanned mod archive, so the supplier can
            // legitimately resolve straight to that external class. Keep it as a first-class
            // scanner result instead of requiring a ClassNode that cannot exist in this map.
            Mode mode = BLOCK_ITEM.equals(ref.implementationClass) ? Mode.BLOCK_ITEM : null;
            if (mode == null) {
                if (implementation == null
                        || !inherits(implementation, ITEM, classes, new HashSet<>())) continue;
                mode = classify(implementation, classes);
            }
            if (mode == null) continue;
            seen.add(ref.id);
            Placement placement = new Placement();
            placement.itemId = ref.id;
            placement.implementationClass = ref.implementationClass;
            placement.mode = mode;
            placement.confidence = mode == Mode.BLOCK_ITEM ? 100 : 98;
            result.add(placement);
        }
        result.sort(Comparator.comparing((Placement p) -> p.itemId)
                .thenComparing(p -> p.mode.name()));
        return List.copyOf(result);
    }

    public static boolean isPlaceable(Collection<ModScanner.Profile> profiles, String itemId) {
        if (profiles == null || itemId == null) return false;
        for (ModScanner.Profile profile : profiles) {
            if (contains(profile, itemId)) return true;
        }
        return false;
    }

    public static List<Placement> forItem(Collection<ModScanner.Profile> profiles, String itemId) {
        if (profiles == null || itemId == null) return List.of();
        ArrayList<Placement> result = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collect(profile, itemId, result);
        return List.copyOf(result);
    }

    private static boolean contains(ModScanner.Profile profile, String itemId) {
        if (profile == null) return false;
        if (profile.placeableItems != null) {
            for (Placement placement : profile.placeableItems) {
                if (placement != null && itemId.equals(placement.itemId)) return true;
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) if (contains(child, itemId)) return true;
        }
        return false;
    }

    private static void collect(ModScanner.Profile profile, String itemId, List<Placement> out) {
        if (profile == null) return;
        if (profile.placeableItems != null) {
            for (Placement placement : profile.placeableItems) {
                if (placement != null && itemId.equals(placement.itemId)) out.add(placement);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collect(child, itemId, out);
        }
    }

    private static Mode classify(ClassNode implementation, Map<String, ClassNode> nodes) {
        if (inherits(implementation, BLOCK_ITEM, nodes, new HashSet<>())) return Mode.BLOCK_ITEM;
        for (ClassNode current : hierarchy(implementation, nodes)) {
            if (current.methods == null) continue;
            for (MethodNode method : current.methods) {
                if (method == null || method.desc == null || !method.desc.contains(USE_ON_CONTEXT)) continue;
                Mode mutation = mutation(method);
                if (mutation != null) return mutation;
            }
        }
        return null;
    }

    private static Mode mutation(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            String owner = call.owner == null ? "" : call.owner;
            String desc = call.desc == null ? "" : call.desc;
            if (owner.equals("net/minecraft/world/entity/EntityType")
                    && desc.contains("Lnet/minecraft/server/level/ServerLevel;")
                    && desc.contains(BLOCK_POS)
                    && desc.endsWith("Lnet/minecraft/world/entity/Entity;")) {
                return Mode.ENTITY_SPAWN;
            }
            if ((owner.equals("net/minecraft/world/level/Level")
                    || owner.equals("net/minecraft/server/level/ServerLevel")
                    || owner.equals("net/minecraft/world/level/LevelAccessor")
                    || owner.equals("net/minecraft/world/level/LevelWriter"))
                    // Requiring BlockState as an ARGUMENT matters here. Level#getBlockState has
                    // both descriptors too (BlockPos in, BlockState out) and is a read, not a
                    // placement mutation.
                    && hasArgument(desc, BLOCK_POS) && hasArgument(desc, BLOCK_STATE)) {
                return Mode.BLOCK_MUTATION;
            }
            if (owner.equals(BLOCK_ITEM)
                    && (desc.contains(USE_ON_CONTEXT)
                    || desc.contains("Lnet/minecraft/world/item/context/BlockPlaceContext;"))) {
                return Mode.BLOCK_ITEM;
            }
        }
        return null;
    }

    private static boolean hasArgument(String descriptor, String wanted) {
        if (descriptor == null || wanted == null) return false;
        try {
            for (Type type : Type.getArgumentTypes(descriptor)) {
                if (wanted.equals(type.getDescriptor())) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static List<ClassNode> hierarchy(ClassNode start, Map<String, ClassNode> nodes) {
        ArrayList<ClassNode> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        ClassNode current = start;
        while (current != null && seen.add(current.name)) {
            out.add(current);
            current = current.superName == null ? null : nodes.get(current.superName);
        }
        return out;
    }

    private static boolean inherits(ClassNode node, String target, Map<String, ClassNode> nodes,
                                    Set<String> visiting) {
        if (node == null || !visiting.add(node.name)) return false;
        if (target.equals(node.name) || target.equals(node.superName)) return true;
        if (node.interfaces != null && node.interfaces.contains(target)) return true;
        if (node.superName != null && inherits(nodes.get(node.superName), target, nodes, visiting)) return true;
        if (node.interfaces != null) {
            for (String iface : node.interfaces) {
                if (inherits(nodes.get(iface), target, nodes, visiting)) return true;
            }
        }
        return false;
    }
}
