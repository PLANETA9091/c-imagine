package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Operand provenance for discovery, without loading or executing the inspected classes.
 * ASM supplies the control-flow fixed point, including exception edges and local-variable
 * merges. Copies and casts retain their original producers. Constructor recipes are recovered
 * from the receiver of invokespecial, never from instruction proximity or packet class names.
 *
 * <p>Factory calls are followed only when dispatch is statically known. All reachable returns
 * must agree; unknown branches, mutable fields, recursion and analysis limits lose constructor
 * certainty. This is deliberately a bounded abstract interpretation, not a Java runtime.</p>
 */
final class BytecodeValueFlow {
    private static final int MAX_DEPTH = 24;
    private static final int MAX_ANALYSES = 64;
    private static final int MAX_INSTRUCTIONS = 24_000;
    private static final long MAX_FRAME_CELLS = 1_000_000;

    record Value(String type, String constructorDesc, Boolean booleanArgument, Integer integer) {
        static Value typed(String type) { return new Value(type, null, null, null); }
        static Value integer(int value) { return new Value(null, null, null, value); }
    }

    record Target(ClassNode owner, MethodNode method) {}

    private final Map<String, ClassNode> nodes;
    private final Map<MethodNode, Analysis> analyses = new IdentityHashMap<>();
    private final Set<MethodNode> failed = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> warnings = new java.util.LinkedHashSet<>();
    private int instructions;

    BytecodeValueFlow(Map<String, ClassNode> nodes) {
        this.nodes = nodes == null ? Map.of() : nodes;
    }

    Set<String> warnings() { return Set.copyOf(warnings); }

    List<Value> argumentValues(ClassNode owner, MethodNode method, MethodInsnNode call, int argument) {
        Analysis analysis = analyze(owner, method);
        if (analysis == null) return List.of();
        Frame<SourceValue> frame = analysis.frame(call);
        if (frame == null) return List.of();
        Type[] arguments = Type.getArgumentTypes(call.desc);
        if (argument < 0 || argument >= arguments.length || frame.getStackSize() < arguments.length) {
            return List.of();
        }
        SourceValue source = frame.getStack(frame.getStackSize() - arguments.length + argument);
        if (arguments[argument].getSort() == Type.ARRAY) return arrayElements(analysis, source, call);
        Value value = resolve(analysis, source, Map.of(), new HashSet<>(), 0);
        return value == null ? List.of() : List.of(value);
    }

    /**
     * Exact producer instructions for one call argument after verifier-level local/cast/copy
     * propagation. This is intentionally lower-level than {@link #argumentValues}: callers such as
     * the client-action scanner already have a richer domain for fields, UUID accessors and virtual
     * screen state, and only need ASM to tell them where a value actually came from.
     */
    Set<AbstractInsnNode> argumentSources(ClassNode owner, MethodNode method,
                                          MethodInsnNode call, int argument) {
        Analysis analysis = analyze(owner, method);
        if (analysis == null) return Set.of();
        Frame<SourceValue> frame = analysis.frame(call);
        if (frame == null) return Set.of();
        Type[] arguments = Type.getArgumentTypes(call.desc);
        if (argument < 0 || argument >= arguments.length || frame.getStackSize() < arguments.length) {
            return Set.of();
        }
        SourceValue source = frame.getStack(frame.getStackSize() - arguments.length + argument);
        return source == null || source.insns == null || source.insns.isEmpty()
                ? Set.of() : Set.copyOf(source.insns);
    }

    /** Exact producer sets for the two operand-stack values consumed by a binary instruction.
     * Copies/casts/locals have already been collapsed by the provenance interpreter, so callers
     * can recursively decode small arithmetic expressions without instruction-adjacency guesses. */
    List<Set<AbstractInsnNode>> binaryOperandSources(ClassNode owner, MethodNode method,
                                                    AbstractInsnNode instruction) {
        Analysis analysis = analyze(owner, method);
        if (analysis == null || instruction == null) return List.of();
        Frame<SourceValue> frame = analysis.frame(instruction);
        if (frame == null || frame.getStackSize() < 2) return List.of();
        SourceValue left = frame.getStack(frame.getStackSize() - 2);
        SourceValue right = frame.getStack(frame.getStackSize() - 1);
        if (left == null || right == null || left.insns == null || right.insns == null
                || left.insns.isEmpty() || right.insns.isEmpty()) return List.of();
        return List.of(Set.copyOf(left.insns), Set.copyOf(right.insns));
    }

    /** Exact producer set for the single operand consumed by a unary conversion/negation. */
    Set<AbstractInsnNode> unaryOperandSources(ClassNode owner, MethodNode method,
                                             AbstractInsnNode instruction) {
        Analysis analysis = analyze(owner, method);
        if (analysis == null || instruction == null) return Set.of();
        Frame<SourceValue> frame = analysis.frame(instruction);
        if (frame == null || frame.getStackSize() < 1) return Set.of();
        SourceValue source = frame.getStack(frame.getStackSize() - 1);
        return source == null || source.insns == null || source.insns.isEmpty()
                ? Set.of() : Set.copyOf(source.insns);
    }

    /**
     * Returns one exact zero-argument instance-accessor chain rooted in the requested method
     * parameter local. The terminal call is included. Ambiguous receivers, arguments, static calls
     * and non-call receiver producers fail closed.
     */
    List<String> zeroArgAccessorPathFromParameter(ClassNode owner, MethodNode method,
                                                  MethodInsnNode terminal, int parameterLocal) {
        Analysis analysis = analyze(owner, method);
        if (analysis == null || terminal == null || parameterLocal < 0
                || terminal.getOpcode() == Opcodes.INVOKESTATIC
                || Type.getArgumentTypes(terminal.desc).length != 0) return List.of();
        ArrayList<String> path = accessorReceiverPath(analysis, terminal, parameterLocal,
                java.util.Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        if (path == null) return List.of();
        path.add("M#" + terminal.owner + '#' + terminal.name + '#' + terminal.desc);
        return List.copyOf(path);
    }

    private ArrayList<String> accessorReceiverPath(Analysis analysis, MethodInsnNode call,
                                                   int parameterLocal,
                                                   Set<AbstractInsnNode> visiting, int depth) {
        if (analysis == null || call == null || depth > 8) return null;
        Frame<SourceValue> frame = analysis.frame(call);
        if (frame == null) return null;
        int receiver = frame.getStackSize() - Type.getArgumentTypes(call.desc).length - 1;
        if (receiver < 0) return null;
        SourceValue source = frame.getStack(receiver);
        Integer direct = parameterLocal(analysis, source);
        if (Integer.valueOf(parameterLocal).equals(direct)) return new ArrayList<>();
        if (source == null || source.insns == null || source.insns.size() != 1) return null;
        AbstractInsnNode producer = source.insns.iterator().next();
        if (!visiting.add(producer)) return null;
        try {
            if (!(producer instanceof MethodInsnNode nested)
                    || nested.getOpcode() == Opcodes.INVOKESTATIC
                    || Type.getArgumentTypes(nested.desc).length != 0) return null;
            ArrayList<String> path = accessorReceiverPath(
                    analysis, nested, parameterLocal, visiting, depth + 1);
            if (path == null) return null;
            path.add("M#" + nested.owner + '#' + nested.name + '#' + nested.desc);
            return path;
        } finally {
            visiting.remove(producer);
        }
    }

    /** Local slot of the method parameter that supplies one call argument unchanged. Returns null
     * for computed values, fields, merges or any provenance that is not exactly one parameter. */
    Integer argumentParameterLocal(ClassNode owner, MethodNode method,
                                   MethodInsnNode call, int argument) {
        Analysis analysis = analyze(owner, method);
        if (analysis == null || call == null) return null;
        Frame<SourceValue> frame = analysis.frame(call);
        if (frame == null) return null;
        Type[] arguments = Type.getArgumentTypes(call.desc);
        if (argument < 0 || argument >= arguments.length || frame.getStackSize() < arguments.length) {
            return null;
        }
        return parameterLocal(analysis,
                frame.getStack(frame.getStackSize() - arguments.length + argument));
    }

    /** Exact producer instructions for an instance-call receiver after local/cast propagation. */
    Set<AbstractInsnNode> receiverSources(ClassNode owner, MethodNode method, MethodInsnNode call) {
        if (call == null || call.getOpcode() == Opcodes.INVOKESTATIC) return Set.of();
        Analysis analysis = analyze(owner, method);
        if (analysis == null) return Set.of();
        Frame<SourceValue> frame = analysis.frame(call);
        if (frame == null) return Set.of();
        Type[] arguments = Type.getArgumentTypes(call.desc);
        int receiver = frame.getStackSize() - arguments.length - 1;
        if (receiver < 0) return Set.of();
        SourceValue source = frame.getStack(receiver);
        return source == null || source.insns == null || source.insns.isEmpty()
                ? Set.of() : Set.copyOf(source.insns);
    }

    /** Local slot of the method parameter (or local 0 / this) supplying an instance-call receiver. */
    Integer receiverParameterLocal(ClassNode owner, MethodNode method, MethodInsnNode call) {
        if (call == null || call.getOpcode() == Opcodes.INVOKESTATIC) return null;
        Analysis analysis = analyze(owner, method);
        if (analysis == null) return null;
        Frame<SourceValue> frame = analysis.frame(call);
        if (frame == null) return null;
        Type[] arguments = Type.getArgumentTypes(call.desc);
        int receiver = frame.getStackSize() - arguments.length - 1;
        return receiver < 0 ? null : parameterLocal(analysis, frame.getStack(receiver));
    }

    /** Local slot supplying the receiver of one GETFIELD. Useful for proving `payload.field` or
     * `this.field` without depending on instruction adjacency after compiler temporaries/casts. */
    Integer fieldReceiverParameterLocal(ClassNode owner, MethodNode method, FieldInsnNode field) {
        if (field == null || field.getOpcode() != Opcodes.GETFIELD) return null;
        Analysis analysis = analyze(owner, method);
        if (analysis == null) return null;
        Frame<SourceValue> frame = analysis.frame(field);
        if (frame == null || frame.getStackSize() < 1) return null;
        return parameterLocal(analysis, frame.getStack(frame.getStackSize() - 1));
    }

    /** Local slot supplying the value written by one PUTFIELD. The receiver itself is intentionally
     * irrelevant here; callers independently require the field owner they expect. */
    Integer putFieldValueParameterLocal(ClassNode owner, MethodNode method, FieldInsnNode field) {
        if (field == null || field.getOpcode() != Opcodes.PUTFIELD) return null;
        Analysis analysis = analyze(owner, method);
        if (analysis == null) return null;
        Frame<SourceValue> frame = analysis.frame(field);
        if (frame == null || frame.getStackSize() < 2) return null;
        return parameterLocal(analysis, frame.getStack(frame.getStackSize() - 1));
    }

    /** Exact producer instructions for the value written by one PUTFIELD after local/cast/copy
     * propagation. This is useful for proving that a freshly allocated collection is the exact
     * object later stored in a client-state field without depending on compiler local layout. */
    Set<AbstractInsnNode> putFieldValueSources(ClassNode owner, MethodNode method, FieldInsnNode field) {
        if (field == null || field.getOpcode() != Opcodes.PUTFIELD) return Set.of();
        Analysis analysis = analyze(owner, method);
        if (analysis == null) return Set.of();
        Frame<SourceValue> frame = analysis.frame(field);
        if (frame == null || frame.getStackSize() < 2) return Set.of();
        SourceValue source = frame.getStack(frame.getStackSize() - 1);
        return source == null || source.insns == null || source.insns.isEmpty()
                ? Set.of() : Set.copyOf(source.insns);
    }

    /** Local slot supplying the receiver of one PUTFIELD. */
    Integer putFieldReceiverParameterLocal(ClassNode owner, MethodNode method, FieldInsnNode field) {
        if (field == null || field.getOpcode() != Opcodes.PUTFIELD) return null;
        Analysis analysis = analyze(owner, method);
        if (analysis == null) return null;
        Frame<SourceValue> frame = analysis.frame(field);
        if (frame == null || frame.getStackSize() < 2) return null;
        return parameterLocal(analysis, frame.getStack(frame.getStackSize() - 2));
    }

    private static Integer parameterLocal(Analysis analysis, SourceValue source) {
        if (analysis == null || source == null || source.insns == null || source.insns.isEmpty()) {
            return null;
        }
        Integer local = null;
        for (AbstractInsnNode producer : source.insns) {
            Integer candidate = analysis.parameters.get(producer);
            if (candidate == null) return null;
            if (local == null) local = candidate;
            else if (!local.equals(candidate)) return null;
        }
        return local;
    }

    /** Exact producer instructions for an object/scalar return value after local/cast propagation. */
    Set<AbstractInsnNode> returnSources(ClassNode owner, MethodNode method, AbstractInsnNode returnInsn) {
        if (returnInsn == null || returnInsn.getOpcode() < Opcodes.IRETURN
                || returnInsn.getOpcode() > Opcodes.ARETURN) return Set.of();
        Analysis analysis = analyze(owner, method);
        if (analysis == null) return Set.of();
        Frame<SourceValue> frame = analysis.frame(returnInsn);
        if (frame == null || frame.getStackSize() < 1) return Set.of();
        SourceValue source = frame.getStack(frame.getStackSize() - 1);
        return source == null || source.insns == null || source.insns.isEmpty()
                ? Set.of() : Set.copyOf(source.insns);
    }

    /** Fresh, straight-line varargs initializers. Escaped arrays and conditional stores need a
     * heap analysis and are intentionally left unknown. Locals, DUP and overwritten slots work
     * because both the array receiver and each stored element come from analyzed stack frames. */
    private List<Value> arrayElements(Analysis analysis, SourceValue source, MethodInsnNode send) {
        if (source.insns.size() != 1) return List.of();
        AbstractInsnNode allocation = source.insns.iterator().next();
        if (allocation.getOpcode() != Opcodes.ANEWARRAY) return List.of();
        Frame<SourceValue> allocated = analysis.frame(allocation);
        if (allocated == null) return List.of();
        Value length = resolve(analysis, allocated.getStack(allocated.getStackSize() - 1),
                Map.of(), new HashSet<>(), 0);
        if (length == null || length.integer == null || length.integer < 0 || length.integer > 64) return List.of();
        Map<Integer, Value> elements = new HashMap<>();
        boolean reachedSend = false;
        for (AbstractInsnNode instruction = allocation.getNext(); instruction != null; instruction = instruction.getNext()) {
            if (instruction == send) { reachedSend = true; break; }
            if (instruction instanceof JumpInsnNode || instruction instanceof TableSwitchInsnNode
                    || instruction instanceof LookupSwitchInsnNode || analysis.branchTargets.contains(instruction)) {
                return List.of();
            }
            Frame<SourceValue> frame = analysis.frame(instruction);
            if (frame == null) continue;
            int size = frame.getStackSize();
            if (instruction.getOpcode() == Opcodes.AASTORE && size >= 3) {
                if (frame.getStack(size - 1).insns.contains(allocation)) return List.of();
                SourceValue array = frame.getStack(size - 3);
                if (!array.insns.contains(allocation)) continue;
                if (array.insns.size() != 1) return List.of();
                Value index = resolve(analysis, frame.getStack(size - 2), Map.of(), new HashSet<>(), 0);
                if (index == null || index.integer == null || index.integer < 0 || index.integer >= length.integer) {
                    return List.of();
                }
                Value element = resolve(analysis, frame.getStack(size - 1), Map.of(), new HashSet<>(), 0);
                elements.put(index.integer, element == null ? Value.typed(null) : element);
            } else if (instruction instanceof MethodInsnNode call) {
                int consumed = Type.getArgumentTypes(call.desc).length
                        + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
                for (int i = size - consumed; i < size; i++) {
                    if (i >= 0 && frame.getStack(i).insns.contains(allocation)) return List.of();
                }
            } else if (instruction instanceof InvokeDynamicInsnNode call) {
                for (int i = size - Type.getArgumentTypes(call.desc).length; i < size; i++) {
                    if (i >= 0 && frame.getStack(i).insns.contains(allocation)) return List.of();
                }
            } else if ((instruction.getOpcode() == Opcodes.PUTSTATIC || instruction.getOpcode() == Opcodes.PUTFIELD
                    || instruction.getOpcode() == Opcodes.ARETURN) && size > 0
                    && frame.getStack(size - 1).insns.contains(allocation)) {
                return List.of();
            }
        }
        if (!reachedSend || elements.size() != length.integer) return List.of();
        List<Value> result = new ArrayList<>();
        for (int i = 0; i < length.integer; i++) result.add(elements.get(i));
        return result;
    }

    private Analysis analyze(ClassNode owner, MethodNode method) {
        if (analyses.containsKey(method)) return analyses.get(method);
        if (failed.contains(method)) return null;
        int size = method.instructions.size();
        if (analyses.size() + failed.size() >= MAX_ANALYSES
                || instructions + size > MAX_INSTRUCTIONS
                || (long) size * (method.maxLocals + (long) method.maxStack) > MAX_FRAME_CELLS) {
            failed.add(method);
            warnings.add("value-flow budget exceeded");
            return null;
        }
        instructions += size;
        ProvenanceInterpreter interpreter = new ProvenanceInterpreter();
        try {
            Frame<SourceValue>[] frames = new Analyzer<>(interpreter).analyze(owner.name, method);
            Analysis result = new Analysis(owner, method, frames, interpreter.parameters);
            analyses.put(method, result);
            return result;
        } catch (AnalyzerException | RuntimeException failure) {
            failed.add(method);
            warnings.add("invalid bytecode: " + owner.name + '.' + method.name + method.desc);
            return null;
        }
    }

    private Value resolve(Analysis analysis, SourceValue source, Map<Integer, Value> bindings,
                          Set<AbstractInsnNode> visiting, int depth) {
        if (source == null || source.insns.isEmpty()) return null;
        if (depth > MAX_DEPTH) {
            warnings.add("value-flow depth exceeded");
            return null;
        }
        Value result = null;
        boolean first = true;
        for (AbstractInsnNode producer : source.insns) {
            if (!visiting.add(producer)) return null;
            Value next;
            try {
                next = producer(analysis, producer, bindings, visiting, depth + 1);
            } finally {
                visiting.remove(producer);
            }
            // A missing origin is an unknown alternative, not permission to select another one.
            if (next == null) return null;
            result = first ? next : merge(result, next);
            first = false;
        }
        return result;
    }

    private Value producer(Analysis analysis, AbstractInsnNode producer, Map<Integer, Value> bindings,
                           Set<AbstractInsnNode> visiting, int depth) {
        Integer parameter = analysis.parameters.get(producer);
        if (parameter != null) return bindings.get(parameter);
        int opcode = producer.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return Value.integer(opcode - Opcodes.ICONST_0);
        }
        if (producer instanceof IntInsnNode constant
                && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) {
            return Value.integer(constant.operand);
        }
        if (producer instanceof LdcInsnNode constant && constant.cst instanceof Integer number) {
            return Value.integer(number);
        }
        if (producer instanceof TypeInsnNode allocation && opcode == Opcodes.NEW) {
            List<MethodInsnNode> constructors = analysis.constructors.get(allocation);
            if (constructors == null || constructors.size() != 1) return Value.typed(allocation.desc);
            MethodInsnNode constructor = constructors.get(0);
            if (!allocation.desc.equals(constructor.owner)) return Value.typed(allocation.desc);
            Type[] arguments = Type.getArgumentTypes(constructor.desc);
            Frame<SourceValue> frame = analysis.frame(constructor);
            Boolean bool = null;
            int booleanCount = 0;
            for (int i = 0; i < arguments.length; i++) {
                if (arguments[i].getSort() != Type.BOOLEAN) continue;
                booleanCount++;
                Value argument = resolve(analysis,
                        frame.getStack(frame.getStackSize() - arguments.length + i),
                        bindings, visiting, depth);
                bool = argument != null && Integer.valueOf(0).equals(argument.integer) ? Boolean.FALSE
                        : argument != null && Integer.valueOf(1).equals(argument.integer) ? Boolean.TRUE : null;
            }
            return new Value(allocation.desc, constructor.desc, booleanCount == 1 ? bool : null, null);
        }
        if (producer instanceof FieldInsnNode field) return Value.typed(objectType(field.desc));
        if (producer instanceof MethodInsnNode call) {
            Value fallback = Value.typed(objectType(Type.getReturnType(call.desc).getDescriptor()));
            Target target = exactTarget(nodes, call.owner, call.name, call.desc, call.getOpcode());
            if (target == null) return fallback;
            Analysis child = analyze(target.owner, target.method);
            if (child == null) return fallback;
            Frame<SourceValue> frame = analysis.frame(call);
            if (frame == null) return fallback;
            Type[] arguments = Type.getArgumentTypes(call.desc);
            int local = call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
            Map<Integer, Value> childBindings = new HashMap<>();
            for (int i = 0; i < arguments.length; i++) {
                Value argument = resolve(analysis, frame.getStack(frame.getStackSize() - arguments.length + i),
                        bindings, visiting, depth);
                if (argument != null) childBindings.put(local, argument);
                local += arguments[i].getSize();
            }
            Value result = null;
            boolean first = true;
            for (AbstractInsnNode instruction : target.method.instructions) {
                if (instruction.getOpcode() < Opcodes.IRETURN || instruction.getOpcode() > Opcodes.ARETURN) continue;
                Frame<SourceValue> returned = child.frame(instruction);
                if (returned == null) continue;
                Value value = resolve(child, returned.getStack(returned.getStackSize() - 1),
                        childBindings, visiting, depth);
                if (value == null) return fallback;
                result = first ? value : merge(result, value);
                first = false;
            }
            return first ? fallback : result;
        }
        return null;
    }

    private static Value merge(Value left, Value right) {
        if (Objects.equals(left, right)) return left;
        if (left == null || right == null) return null;
        String type = Objects.equals(left.type, right.type) ? left.type : null;
        String constructor = type != null && Objects.equals(left.constructorDesc, right.constructorDesc)
                ? left.constructorDesc : null;
        return new Value(type, constructor,
                constructor != null && Objects.equals(left.booleanArgument, right.booleanArgument)
                        ? left.booleanArgument : null,
                Objects.equals(left.integer, right.integer) ? left.integer : null);
    }

    private static String objectType(String descriptor) {
        Type type = Type.getType(descriptor);
        return type.getSort() == Type.OBJECT ? type.getInternalName() : null;
    }

    /** Resolves inherited implementations, but never guesses a virtual/interface receiver. */
    static Target exactTarget(Map<String, ClassNode> nodes, String owner, String name,
                              String descriptor, int opcode) {
        if (nodes == null) return null;
        ClassNode declared = nodes.get(owner);
        boolean finalOwner = declared != null && (declared.access & Opcodes.ACC_FINAL) != 0;
        Set<String> visited = new HashSet<>();
        ClassNode current = declared;
        while (current != null && visited.add(current.name)) {
            for (MethodNode method : current.methods) {
                if (!name.equals(method.name) || !descriptor.equals(method.desc)) continue;
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return null;
                boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
                if ((opcode == Opcodes.INVOKESTATIC) != isStatic) return null;
                if (opcode != Opcodes.INVOKESTATIC && opcode != Opcodes.INVOKESPECIAL
                        && !finalOwner && (method.access & (Opcodes.ACC_FINAL | Opcodes.ACC_PRIVATE)) == 0) {
                    return null;
                }
                return new Target(current, method);
            }
            if ("<init>".equals(name)) return null;
            current = current.superName == null ? null : nodes.get(current.superName);
        }
        return null;
    }

    private static final class ProvenanceInterpreter extends SourceInterpreter {
        private final Map<AbstractInsnNode, Integer> parameters = new IdentityHashMap<>();

        ProvenanceInterpreter() { super(Opcodes.ASM9); }

        @Override
        public SourceValue newParameterValue(boolean instance, int local, Type type) {
            AbstractInsnNode marker = new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local);
            parameters.put(marker, local);
            return new SourceValue(type.getSize(), marker);
        }

        @Override
        public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) { return value; }

        @Override
        public SourceValue unaryOperation(AbstractInsnNode instruction, SourceValue value) {
            return instruction.getOpcode() == Opcodes.CHECKCAST ? value : super.unaryOperation(instruction, value);
        }
    }

    private static final class Analysis {
        final ClassNode owner;
        final MethodNode method;
        final Frame<SourceValue>[] frames;
        final Map<AbstractInsnNode, Integer> parameters;
        final Map<AbstractInsnNode, Integer> indices = new IdentityHashMap<>();
        final Map<AbstractInsnNode, List<MethodInsnNode>> constructors = new IdentityHashMap<>();
        final Set<AbstractInsnNode> branchTargets = new HashSet<>();

        Analysis(ClassNode owner, MethodNode method, Frame<SourceValue>[] frames,
                 Map<AbstractInsnNode, Integer> parameters) {
            this.owner = owner;
            this.method = method;
            this.frames = frames;
            this.parameters = parameters;
            for (TryCatchBlockNode handler : method.tryCatchBlocks) branchTargets.add(handler.handler);
            int index = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof JumpInsnNode jump) branchTargets.add(jump.label);
                if (instruction instanceof TableSwitchInsnNode table) {
                    branchTargets.add(table.dflt);
                    branchTargets.addAll(table.labels);
                }
                if (instruction instanceof LookupSwitchInsnNode table) {
                    branchTargets.add(table.dflt);
                    branchTargets.addAll(table.labels);
                }
                indices.put(instruction, index);
                Frame<SourceValue> frame = frames[index++];
                if (frame == null || !(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESPECIAL || !"<init>".equals(call.name)) continue;
                int receiver = frame.getStackSize() - Type.getArgumentTypes(call.desc).length - 1;
                if (receiver < 0) continue;
                for (AbstractInsnNode source : frame.getStack(receiver).insns) {
                    if (source.getOpcode() == Opcodes.NEW) {
                        constructors.computeIfAbsent(source, ignored -> new ArrayList<>()).add(call);
                    }
                }
            }
        }

        Frame<SourceValue> frame(AbstractInsnNode instruction) {
            Integer index = indices.get(instruction);
            return index == null ? null : frames[index];
        }
    }
}
