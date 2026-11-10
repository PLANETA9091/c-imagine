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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exact BlockEntity -> filter mutation contracts recovered from bytecode semantics.
 *
 * <p>A field is not considered a filter because its name contains "filter". The pointed-to
 * object must expose an ItemStack mutation that actually writes behavior state and then performs
 * a callback/synchronisation side effect. This keeps obfuscated mods and differently named addon
 * APIs working while rejecting unrelated ItemStack caches.</p>
 */
public final class FilterBytecodeScanner {

    private FilterBytecodeScanner() {}

    public static final class Contract {
        public String targetType;
        public String behaviourField;
        public String setFilter;
        public String setDirectionalFilter;
        public String getFilter;
        public String isActive;
        public String testHit;
        public String shortInteract;
    }

    public static List<Contract> scan(Map<String, ClassNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        ArrayList<Contract> out = new ArrayList<>();
        for (ClassNode owner : nodes.values()) {
            if (owner == null || owner.name == null || owner.fields == null) continue;
            for (FieldNode field : owner.fields) {
                Type fieldType = Type.getType(field.desc);
                if (fieldType.getSort() != Type.OBJECT) continue;
                ClassNode behaviour = nodes.get(fieldType.getInternalName());
                if (behaviour == null || behaviour.methods == null) continue;
                Mutation setter = mutationSetter(behaviour);
                if (setter == null) continue;

                Contract contract = new Contract();
                contract.targetType = owner.name;
                contract.behaviourField = "F#" + owner.name + '#' + field.name + '#' + field.desc;
                contract.setFilter = spec(behaviour.name, setter.method);
                MethodNode directional = directionalWrapper(behaviour, setter.method);
                contract.setDirectionalFilter = spec(behaviour.name, directional);
                contract.getFilter = spec(behaviour.name, itemGetter(behaviour));
                // These are optional interaction refinements. Do not guess a random boolean method
                // when bytecode cannot prove it; held-item application only requires the mutation.
                contract.isActive = null;
                contract.testHit = spec(behaviour.name, namedByArgument(behaviour,
                        "net/minecraft/world/phys/Vec3", Type.BOOLEAN));
                contract.shortInteract = spec(behaviour.name, shortInteract(behaviour));
                out.add(contract);
            }
        }
        return out.stream().sorted(Comparator.comparing(contract -> contract.targetType)).toList();
    }

    public static List<Contract> contractsForTarget(Collection<ModScanner.Profile> profiles,
                                                    Collection<String> runtimeTypes) {
        if (profiles == null || runtimeTypes == null || runtimeTypes.isEmpty()) return List.of();
        ArrayList<Contract> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String type : runtimeTypes) {
            for (ModScanner.Profile profile : profiles) collect(profile, type, out, seen);
        }
        return List.copyOf(out);
    }

    private static void collect(ModScanner.Profile profile, String type, List<Contract> out,
                                Set<String> seen) {
        if (profile == null || type == null) return;
        if (profile.filterContracts != null) {
            for (Contract contract : profile.filterContracts) {
                if (contract == null || !type.equals(contract.targetType)) continue;
                String key = contract.targetType + '\n' + contract.behaviourField;
                if (seen.add(key)) out.add(contract);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collect(child, type, out, seen);
        }
    }

    private interface ArgsPredicate { boolean test(Type[] args); }
    private interface ReturnPredicate { boolean test(Type type); }

    private record Mutation(MethodNode method, int score) {}

    private static Mutation mutationSetter(ClassNode behaviour) {
        Mutation best = null;
        for (MethodNode method : behaviour.methods) {
            Type[] args = Type.getArgumentTypes(method.desc);
            Type result = Type.getReturnType(method.desc);
            if (args.length != 1 || !itemStack(args[0])
                    || result.getSort() != Type.BOOLEAN && result.getSort() != Type.VOID) continue;
            int score = mutationScore(behaviour.name, method);
            if (score < 80) continue;
            Mutation candidate = new Mutation(method, score);
            if (best == null || candidate.score > best.score) best = candidate;
        }
        return best;
    }

    /**
     * Require a real state write plus an externally visible post-write action. The ItemStack
     * argument itself is already guaranteed by the descriptor, so this recognizes direct stack
     * storage as well as wrapper factories such as ItemFilter.of(stack).
     */
    private static int mutationScore(String owner, MethodNode method) {
        boolean stateWrite = false;
        boolean itemFlow = false;
        boolean callback = false;
        boolean sync = false;
        int score = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && owner.equals(field.owner)) {
                stateWrite = true;
                score += 45;
                continue;
            }
            if (!(instruction instanceof MethodInsnNode call)) continue;
            Type[] args;
            try {
                args = Type.getArgumentTypes(call.desc);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            for (Type argument : args) if (itemStack(argument)) itemFlow = true;
            if (call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && "java/util/function/Consumer".equals(call.owner)
                    && call.desc.endsWith(")V")) {
                callback = true;
                score += 40;
            }
            if (stateWrite && call.desc.endsWith(")V")
                    && !call.owner.startsWith("java/") && !call.owner.startsWith("javax/")) {
                sync = true;
                score += 20;
            }
        }
        if (!stateWrite || !(itemFlow || writesItemStackField(owner, method))) return 0;
        if (!callback && !sync) return 0;
        return score;
    }

    private static boolean writesItemStackField(String owner, MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                    || !owner.equals(field.owner)) continue;
            try {
                Type type = Type.getType(field.desc);
                if (itemStack(type)) return true;
            } catch (IllegalArgumentException ignored) {
            }
        }
        return false;
    }

    private static MethodNode directionalWrapper(ClassNode behaviour, MethodNode setter) {
        if (setter == null) return null;
        for (MethodNode method : behaviour.methods) {
            Type[] args = Type.getArgumentTypes(method.desc);
            Type result = Type.getReturnType(method.desc);
            if (args.length != 2 || !direction(args[0]) || !itemStack(args[1])
                    || result.getSort() != Type.BOOLEAN && result.getSort() != Type.VOID) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && behaviour.name.equals(call.owner)
                        && setter.name.equals(call.name) && setter.desc.equals(call.desc)) return method;
            }
        }
        return null;
    }

    private static MethodNode itemGetter(ClassNode behaviour) {
        for (MethodNode method : behaviour.methods) {
            Type[] args = Type.getArgumentTypes(method.desc);
            if (args.length > 1 || args.length == 1 && !direction(args[0])) continue;
            if (itemStack(Type.getReturnType(method.desc))) return method;
        }
        return null;
    }

    private static MethodNode method(ClassNode node, ArgsPredicate args, boolean boolOrVoid,
                                     ReturnPredicate returns) {
        for (MethodNode method : node.methods) {
            Type[] parameters = Type.getArgumentTypes(method.desc);
            if (!args.test(parameters)) continue;
            Type result = Type.getReturnType(method.desc);
            if (boolOrVoid && result.getSort() != Type.BOOLEAN && result.getSort() != Type.VOID) continue;
            if (returns != null && !returns.test(result)) continue;
            return method;
        }
        return null;
    }

    private static MethodNode named(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) return method;
        }
        return null;
    }

    private static MethodNode namedByArgument(ClassNode node, String argument, int returnSort) {
        for (MethodNode method : node.methods) {
            Type[] args = Type.getArgumentTypes(method.desc);
            if (args.length != 1 || args[0].getSort() != Type.OBJECT
                    || !argument.equals(args[0].getInternalName())) continue;
            if (Type.getReturnType(method.desc).getSort() == returnSort) return method;
        }
        return null;
    }

    private static MethodNode shortInteract(ClassNode node) {
        for (MethodNode method : node.methods) {
            Type[] args = Type.getArgumentTypes(method.desc);
            if (args.length != 4) continue;
            boolean player = object(args[0], "net/minecraft/world/entity/player/Player");
            boolean hand = object(args[1], "net/minecraft/world/InteractionHand");
            boolean direction = direction(args[2]);
            boolean hit = object(args[3], "net/minecraft/world/phys/BlockHitResult");
            if (player && hand && direction && hit) return method;
        }
        return null;
    }

    private static boolean itemStack(Type type) {
        return object(type, "net/minecraft/world/item/ItemStack");
    }

    private static boolean direction(Type type) {
        return object(type, "net/minecraft/core/Direction");
    }

    private static boolean object(Type type, String name) {
        return type != null && type.getSort() == Type.OBJECT && name.equals(type.getInternalName());
    }

    private static String spec(String owner, MethodNode method) {
        return method == null ? null : "M#" + owner + '#' + method.name + '#' + method.desc;
    }
}
