package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structural value-board discovery for arbitrary mods.
 *
 * <p>No method/member names are used. A value behavior is proven by a board factory taking a
 * Player + BlockHitResult, an authoritative settings mutation taking Player + settings + boolean,
 * and a zero-argument getter returning that same settings type. The returned board is then decoded
 * from simple field accessors (component title, row list, numeric limits/milestones and formatter).
 * This is the common semantic shape behind slider/value screens while remaining valid when mod
 * members are obfuscated or renamed.</p>
 */
public final class ValueBoardBytecodeScanner {

    private static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    private static final String PLAYER = "net/minecraft/world/entity/player/Player";
    private static final String BLOCK_HIT = "net/minecraft/world/phys/BlockHitResult";
    private static final String COMPONENT = "net/minecraft/network/chat/Component";
    private static final String LIST = "java/util/List";

    private ValueBoardBytecodeScanner() {}

    public static final class Contract {
        public String targetType;
        public String behaviourField;
        public String boardFactory;
        public String settingsSetter;
        public String settingsGetter;
        public String settingsType;
        public final List<String> settingsNumericAccessors = new ArrayList<>();
        public String boardTitle;
        public String boardRows;
        public final List<String> boardNumericAccessors = new ArrayList<>();
        public String boardFormatter;
    }

    private record OwnedMethod(String owner, MethodNode method) {}
    private record BoardShape(String title, String rows, List<String> numeric, String formatter) {}
    private record BehaviorShape(OwnedMethod factory, OwnedMethod setter, OwnedMethod getter,
                                 String settingsType, BoardShape board) {}

    public static List<Contract> scan(Map<String, ClassNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        ArrayList<Contract> out = new ArrayList<>();
        for (ClassNode owner : nodes.values()) {
            if (owner == null || owner.name == null || owner.fields == null) continue;
            for (FieldNode field : owner.fields) {
                Type fieldType = safeType(field.desc);
                if (fieldType == null || fieldType.getSort() != Type.OBJECT) continue;
                BehaviorShape shape = behaviorShape(fieldType.getInternalName(), nodes);
                if (shape == null) continue;

                // A mod jar frequently omits the library jar that owns the target's BlockEntity
                // superclass (CBC -> Create is one example), so archive-local inheritance alone
                // cannot always prove BlockEntity here. Emitting the structural holder contract is
                // safe: runtime resolution is only ever entered with an actual BlockEntity and an
                // exact matching runtime type hierarchy.

                Contract contract = new Contract();
                List<String> settingsAccessors = numericAccessors(shape.settingsType, nodes);
                contract.targetType = owner.name;
                contract.behaviourField = fieldSpec(owner.name, field);
                contract.boardFactory = methodSpec(shape.factory.owner, shape.factory.method);
                contract.settingsSetter = methodSpec(shape.setter.owner, shape.setter.method);
                contract.settingsGetter = shape.getter == null ? null
                        : methodSpec(shape.getter.owner, shape.getter.method);
                contract.settingsType = shape.settingsType;
                contract.settingsNumericAccessors.addAll(settingsAccessors);
                if (shape.board != null) {
                    contract.boardTitle = shape.board.title;
                    contract.boardRows = shape.board.rows;
                    contract.boardNumericAccessors.addAll(shape.board.numeric);
                    contract.boardFormatter = shape.board.formatter;
                }
                out.add(contract);
            }
        }
        return out.stream().sorted(Comparator.comparing(contract -> contract.targetType + '\n'
                + contract.behaviourField)).toList();
    }

    public static List<Contract> contractsForTarget(Collection<ModScanner.Profile> profiles,
                                                    Collection<String> runtimeTypes) {
        if (profiles == null || runtimeTypes == null || runtimeTypes.isEmpty()) return List.of();
        ArrayList<Contract> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String type : runtimeTypes) {
            if (type == null) continue;
            for (ModScanner.Profile profile : profiles) collect(profile, type, out, seen);
        }
        return List.copyOf(out);
    }

    private static void collect(ModScanner.Profile profile, String type, List<Contract> out,
                                Set<String> seen) {
        if (profile == null) return;
        if (profile.valueBoardContracts != null) {
            for (Contract contract : profile.valueBoardContracts) {
                if (contract == null || !type.equals(contract.targetType)) continue;
                String key = contract.targetType + '\n' + contract.behaviourField;
                if (seen.add(key)) out.add(contract);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collect(child, type, out, seen);
        }
    }

    private static BehaviorShape behaviorShape(String type, Map<String, ClassNode> nodes) {
        List<OwnedMethod> methods = methods(type, nodes);
        if (methods.isEmpty()) return null;
        for (OwnedMethod factory : methods) {
            Type[] factoryArgs = Type.getArgumentTypes(factory.method.desc);
            Type boardType = Type.getReturnType(factory.method.desc);
            if (factoryArgs.length != 2 || !object(factoryArgs[0], PLAYER)
                    || !object(factoryArgs[1], BLOCK_HIT) || boardType.getSort() != Type.OBJECT) continue;
            BoardShape board = boardShape(boardType.getInternalName(), nodes);

            for (OwnedMethod setter : methods) {
                Type[] setterArgs = Type.getArgumentTypes(setter.method.desc);
                Type setterResult = Type.getReturnType(setter.method.desc);
                if (setterArgs.length != 3 || !object(setterArgs[0], PLAYER)
                        || setterArgs[1].getSort() != Type.OBJECT
                        || setterArgs[2].getSort() != Type.BOOLEAN
                        || setterResult.getSort() != Type.VOID && setterResult.getSort() != Type.BOOLEAN) {
                    continue;
                }
                String settings = setterArgs[1].getInternalName();
                OwnedMethod matchingGetter = null;
                for (OwnedMethod getter : methods) {
                    if (Type.getArgumentTypes(getter.method.desc).length != 0) continue;
                    Type returned = Type.getReturnType(getter.method.desc);
                    if (returned.getSort() != Type.OBJECT || !settings.equals(returned.getInternalName())) continue;
                    matchingGetter = getter;
                    break;
                }
                // Dependencies are scanned archive-by-archive. An addon can override the board
                // factory/setter while inheriting the getter and DTO classes from another mod jar.
                // Preserve that exact local evidence as a partial contract; the runtime resolver
                // completes only verifier-obvious accessor shapes on the already loaded classes.
                return new BehaviorShape(factory, setter, matchingGetter, settings, board);
            }
        }
        return null;
    }

    private static BoardShape boardShape(String type, Map<String, ClassNode> nodes) {
        ClassNode node = nodes.get(type);
        if (node == null || node.methods == null) return null;
        String title = null;
        String rows = null;
        String formatter = null;
        java.util.LinkedHashMap<String, String> numericByField = new java.util.LinkedHashMap<>();
        for (MethodNode method : node.methods) {
            FieldInsnNode field = simpleGetter(node.name, method);
            if (field == null) continue;
            Type returned = Type.getReturnType(method.desc);
            if (returned.getSort() == Type.OBJECT && COMPONENT.equals(returned.getInternalName())) {
                if (title == null) title = methodSpec(node.name, method);
            } else if (returned.getSort() == Type.OBJECT && LIST.equals(returned.getInternalName())) {
                if (rows == null) rows = methodSpec(node.name, method);
            } else if (numeric(returned)) {
                numericByField.put(field.name + '\n' + field.desc, methodSpec(node.name, method));
            } else if (returned.getSort() == Type.OBJECT
                    && !"java/lang/String".equals(returned.getInternalName())
                    && !returned.getInternalName().equals(node.name)) {
                if (formatter == null) formatter = methodSpec(node.name, method);
            }
        }
        if (title == null || rows == null || numericByField.size() < 2) return null;
        List<String> numeric = orderedNumericConstructorAccessors(node, numericByField);
        return new BoardShape(title, rows, numeric, formatter);
    }

    private static List<String> numericAccessors(String type, Map<String, ClassNode> nodes) {
        ClassNode node = nodes.get(type);
        if (node == null || node.methods == null) return List.of();
        java.util.LinkedHashMap<String, String> byField = new java.util.LinkedHashMap<>();
        for (MethodNode method : node.methods) {
            if (!numeric(Type.getReturnType(method.desc))) continue;
            FieldInsnNode field = simpleGetter(node.name, method);
            if (field == null) continue;
            byField.put(field.name + '\n' + field.desc, methodSpec(node.name, method));
        }
        if (byField.size() < 2) return List.copyOf(byField.values());
        List<String> constructorOrdered = orderedNumericConstructorAccessors(node, byField);
        if (constructorOrdered.size() >= 2) return constructorOrdered;

        // Settings objects are constructed from (row, value). Recover that semantic order from
        // constructor stores rather than trusting method declaration order, which is not stable
        // across compilers/obfuscators. This only accepts direct verifier-obvious argument stores;
        // unusual constructors keep the deterministic getter order as a conservative fallback.
        for (MethodNode constructor : node.methods) {
            if (!"<init>".equals(constructor.name)) continue;
            Type[] args = Type.getArgumentTypes(constructor.desc);
            if (args.length != 2 || !numeric(args[0]) || !numeric(args[1])) continue;
            int[] locals = new int[]{1, 1 + args[0].getSize()};
            String[] ordered = new String[2];
            for (AbstractInsnNode instruction : constructor.instructions) {
                if (!(instruction instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.PUTFIELD || !node.name.equals(field.owner)) continue;
                String accessor = byField.get(field.name + '\n' + field.desc);
                if (accessor == null) continue;
                AbstractInsnNode previous = previousMeaningful(instruction);
                if (!(previous instanceof VarInsnNode load)) continue;
                for (int argument = 0; argument < locals.length; argument++) {
                    if (load.var == locals[argument]) ordered[argument] = accessor;
                }
            }
            if (ordered[0] != null && ordered[1] != null && !ordered[0].equals(ordered[1])) {
                return List.of(ordered[0], ordered[1]);
            }
        }
        return List.copyOf(byField.values());
    }

    /** Preserve semantic numeric order from constructor argument -> field stores. Records and most
     * immutable board/settings DTOs compile to this direct pattern even after obfuscation. */
    private static List<String> orderedNumericConstructorAccessors(
            ClassNode node, Map<String, String> byField) {
        if (node == null || node.methods == null || byField == null || byField.isEmpty()) return List.of();
        for (MethodNode constructor : node.methods) {
            if (!"<init>".equals(constructor.name)) continue;
            Type[] args = Type.getArgumentTypes(constructor.desc);
            int local = 1;
            java.util.LinkedHashMap<Integer, Integer> argumentByLocal = new java.util.LinkedHashMap<>();
            for (int argument = 0; argument < args.length; argument++) {
                argumentByLocal.put(local, argument);
                local += args[argument].getSize();
            }
            java.util.TreeMap<Integer, String> ordered = new java.util.TreeMap<>();
            for (AbstractInsnNode instruction : constructor.instructions) {
                if (!(instruction instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.PUTFIELD || !node.name.equals(field.owner)) continue;
                String accessor = byField.get(field.name + '\n' + field.desc);
                if (accessor == null) continue;
                AbstractInsnNode previous = previousMeaningful(instruction);
                if (!(previous instanceof VarInsnNode load)) continue;
                Integer argument = argumentByLocal.get(load.var);
                if (argument != null) ordered.put(argument, accessor);
            }
            if (ordered.size() == byField.size()) return List.copyOf(ordered.values());
        }
        return List.copyOf(byField.values());
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode instruction) {
        for (AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
             current != null; current = current.getPrevious()) {
            if (current.getOpcode() >= 0) return current;
        }
        return null;
    }

    /** Only accept accessors that read one backing field from this and immediately return it. */
    private static FieldInsnNode simpleGetter(String owner, MethodNode method) {
        if (method == null || (method.access & Opcodes.ACC_STATIC) != 0
                || Type.getArgumentTypes(method.desc).length != 0) return null;
        FieldInsnNode read = null;
        int meaningful = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode < 0) continue;
            if (opcode == Opcodes.ALOAD) {
                meaningful++;
                continue;
            }
            if (instruction instanceof FieldInsnNode field && opcode == Opcodes.GETFIELD
                    && owner.equals(field.owner)) {
                if (read != null) return null;
                read = field;
                meaningful++;
                continue;
            }
            if (opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN || opcode == Opcodes.FRETURN
                    || opcode == Opcodes.DRETURN || opcode == Opcodes.ARETURN) {
                meaningful++;
                continue;
            }
            return null;
        }
        return read != null && meaningful == 3 ? read : null;
    }

    private static List<OwnedMethod> methods(String root, Map<String, ClassNode> nodes) {
        ArrayList<OwnedMethod> out = new ArrayList<>();
        LinkedHashSet<String> seenTypes = new LinkedHashSet<>();
        LinkedHashSet<String> seenMethods = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            String type = queue.removeFirst();
            if (!seenTypes.add(type)) continue;
            ClassNode node = nodes.get(type);
            if (node == null) continue;
            if (node.methods != null) {
                for (MethodNode method : node.methods) {
                    String key = method.name + method.desc;
                    if (seenMethods.add(key)) out.add(new OwnedMethod(node.name, method));
                }
            }
            if (node.superName != null && !"java/lang/Object".equals(node.superName)) {
                queue.addLast(node.superName);
            }
            if (node.interfaces != null) queue.addAll(node.interfaces);
        }
        return out;
    }

    private static boolean isBlockEntityType(String type, Map<String, ClassNode> nodes) {
        if (type == null) return false;
        if (BLOCK_ENTITY.equals(type)) return true;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
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

    private static boolean object(Type type, String internalName) {
        return type != null && type.getSort() == Type.OBJECT && internalName.equals(type.getInternalName());
    }

    private static boolean numeric(Type type) {
        if (type == null) return false;
        int sort = type.getSort();
        return sort == Type.BYTE || sort == Type.SHORT || sort == Type.INT || sort == Type.LONG
                || sort == Type.FLOAT || sort == Type.DOUBLE;
    }

    private static Type safeType(String descriptor) {
        try {
            return Type.getType(descriptor);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String fieldSpec(String owner, FieldNode field) {
        return "F#" + owner + '#' + field.name + '#' + field.desc;
    }

    private static String methodSpec(String owner, MethodNode method) {
        return method == null ? null : "M#" + owner + '#' + method.name + '#' + method.desc;
    }
}
