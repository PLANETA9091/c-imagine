package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

/** Bounded evaluation of pure model factories and their mod-owned builder wrappers. No inspected
 * class is loaded or invoked: the only external operations are model builders, numbers and lists. */
final class LayerFactoryInterpreter {
    private final Map<String, ClassNode> classes;
    private int steps, allocations;

    private LayerFactoryInterpreter(Map<String, ClassNode> classes) { this.classes = classes; }

    static ArmorVisualBytecodeScanner.LayerModel read(ClassNode model, Map<String, ClassNode> classes) {
        for (MethodNode method : model.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0 || !method.desc.startsWith("()")
                    || !method.desc.endsWith("/LayerDefinition;")) continue;
            try {
                Object result = new LayerFactoryInterpreter(classes).run(model, method, null, new Object[0], 0);
                if (result instanceof ArmorVisualBytecodeScanner.LayerModel layer && !layer.cubes.isEmpty()) {
                    layer.animation = ModelPartAnimationScanner.scan(model, layer.parts.stream()
                            .map(part -> part.name).collect(java.util.stream.Collectors.toSet()));
                    return layer;
                }
            } catch (UnsupportedOperationException | ClassCastException | IndexOutOfBoundsException ignored) {
                // An unknown instruction invalidates the entire factory, never a partial mesh.
            }
        }
        return null;
    }

    private static final class ObjectValue {
        final String type;
        final Map<String, Object> fields = new HashMap<>();
        ObjectValue(String type) { this.type = type; }
    }

    private static final class Cubes {
        final List<ArmorVisualBytecodeScanner.Cube> cubes = new ArrayList<>();
        int u, v;
        boolean mirror;
    }

    private static final class Part {
        final String name;
        final List<ArmorVisualBytecodeScanner.Cube> cubes = new ArrayList<>();
        final Map<String, Part> children = new LinkedHashMap<>();
        float[] pose = new float[6];
        Part(String name) { this.name = name; }
    }

    private static final class Mesh { final Part root = new Part(""); }
    private static final class Deformation { float amount; }

    private Object allocate(String type) {
        if (++allocations > 4096) throw unsupported();
        if (type.endsWith("/CubeListBuilder")) return new Cubes();
        if (type.endsWith("/MeshDefinition")) return new Mesh();
        if (type.endsWith("/CubeDeformation")) return new Deformation();
        if (type.equals("java/util/ArrayList")) return new ArrayList<>();
        if (classes.containsKey(type)) return new ObjectValue(type);
        throw unsupported();
    }

    private Object run(ClassNode owner, MethodNode method, Object receiver, Object[] arguments, int depth) {
        if (depth > 32) throw unsupported();
        Map<Integer, Object> locals = new HashMap<>();
        int slot = 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0) locals.put(slot++, receiver);
        Type[] types = Type.getArgumentTypes(method.desc);
        for (int i = 0; i < arguments.length; i++) {
            locals.put(slot, arguments[i]); slot += types[i].getSize();
        }
        List<Object> stack = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (++steps > 100_000) throw unsupported();
            int opcode = instruction.getOpcode();
            if (opcode < 0 || opcode == Opcodes.NOP) continue;
            if (instruction instanceof VarInsnNode variable) {
                if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) stack.add(locals.get(variable.var));
                else if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) locals.put(variable.var, pop(stack));
                else throw unsupported();
            } else if (instruction instanceof LdcInsnNode constant) {
                if (!(constant.cst instanceof Number || constant.cst instanceof String)) throw unsupported();
                stack.add(constant.cst);
            } else if (instruction instanceof IntInsnNode constant) {
                if (opcode != Opcodes.BIPUSH && opcode != Opcodes.SIPUSH) throw unsupported();
                stack.add(constant.operand);
            } else if (instruction instanceof TypeInsnNode type) {
                if (opcode == Opcodes.NEW) stack.add(allocate(type.desc));
                else if (opcode != Opcodes.CHECKCAST) throw unsupported();
            } else if (instruction instanceof IincInsnNode increment) {
                locals.put(increment.var, number(locals.get(increment.var)).intValue() + increment.incr);
            } else if (instruction instanceof FieldInsnNode field) {
                if (opcode == Opcodes.PUTFIELD) {
                    Object value = pop(stack);
                    ((ObjectValue) pop(stack)).fields.put(field.name, value);
                } else if (opcode == Opcodes.GETFIELD) {
                    ObjectValue object = (ObjectValue) pop(stack);
                    stack.add(object.fields.getOrDefault(field.name,
                            Type.getType(field.desc).getSort() <= Type.DOUBLE ? 0 : null));
                } else if (opcode == Opcodes.GETSTATIC && field.owner.endsWith("/PartPose")) {
                    stack.add(new float[6]);
                } else if (opcode == Opcodes.GETSTATIC && field.owner.endsWith("/CubeDeformation")) {
                    stack.add(new Deformation());
                } else throw unsupported();
            } else if (instruction instanceof MethodInsnNode call) {
                Object[] args = new Object[Type.getArgumentTypes(call.desc).length];
                for (int i = args.length - 1; i >= 0; i--) args[i] = pop(stack);
                Object instance = opcode == Opcodes.INVOKESTATIC ? null : pop(stack);
                Object value = invoke(call, instance, args, depth + 1);
                if (Type.getReturnType(call.desc).getSort() != Type.VOID) stack.add(value);
            } else if (instruction instanceof InvokeDynamicInsnNode call) {
                if (!call.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")
                        || !call.bsm.getName().equals("makeConcatWithConstants")
                        || call.bsmArgs.length == 0 || !(call.bsmArgs[0] instanceof String recipe)) throw unsupported();
                Type[] inputs = Type.getArgumentTypes(call.desc);
                Object[] values = new Object[inputs.length];
                for (int i = inputs.length - 1; i >= 0; i--) {
                    Object value = pop(stack);
                    values[i] = inputs[i].getSort() == Type.INT ? number(value).intValue() : value;
                }
                StringBuilder value = new StringBuilder();
                int argument = 0, constant = 1;
                for (char c : recipe.toCharArray()) {
                    if (c == '\u0001') value.append(values[argument++]);
                    else if (c == '\u0002') value.append(call.bsmArgs[constant++]);
                    else value.append(c);
                }
                stack.add(value.toString());
            } else if (instruction instanceof JumpInsnNode branch) {
                boolean jump;
                if (opcode == Opcodes.GOTO) jump = true;
                else if (opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) {
                    jump = (pop(stack) == null) == (opcode == Opcodes.IFNULL);
                } else {
                    double right = opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ICMPLE
                            ? number(pop(stack)).doubleValue() : 0;
                    double left = number(pop(stack)).doubleValue();
                    jump = switch (opcode) {
                        case Opcodes.IFEQ, Opcodes.IF_ICMPEQ -> left == right;
                        case Opcodes.IFNE, Opcodes.IF_ICMPNE -> left != right;
                        case Opcodes.IFLT, Opcodes.IF_ICMPLT -> left < right;
                        case Opcodes.IFGE, Opcodes.IF_ICMPGE -> left >= right;
                        case Opcodes.IFGT, Opcodes.IF_ICMPGT -> left > right;
                        case Opcodes.IFLE, Opcodes.IF_ICMPLE -> left <= right;
                        default -> throw unsupported();
                    };
                }
                if (jump) instruction = branch.label;
            } else {
                switch (opcode) {
                    case Opcodes.ACONST_NULL -> stack.add(null);
                    case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                         Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5 -> stack.add(opcode - Opcodes.ICONST_0);
                    case Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> stack.add((float) (opcode - Opcodes.FCONST_0));
                    case Opcodes.DCONST_0, Opcodes.DCONST_1 -> stack.add((double) (opcode - Opcodes.DCONST_0));
                    case Opcodes.DUP -> stack.add(stack.get(stack.size() - 1));
                    case Opcodes.POP -> pop(stack);
                    case Opcodes.I2F, Opcodes.D2F -> stack.add(number(pop(stack)).floatValue());
                    case Opcodes.F2D, Opcodes.I2D -> stack.add(number(pop(stack)).doubleValue());
                    case Opcodes.F2I, Opcodes.D2I -> stack.add(number(pop(stack)).intValue());
                    case Opcodes.INEG -> stack.add(-number(pop(stack)).intValue());
                    case Opcodes.FNEG -> stack.add(-number(pop(stack)).floatValue());
                    case Opcodes.DNEG -> stack.add(-number(pop(stack)).doubleValue());
                    case Opcodes.IADD, Opcodes.FADD, Opcodes.DADD, Opcodes.ISUB, Opcodes.FSUB, Opcodes.DSUB,
                         Opcodes.IMUL, Opcodes.FMUL, Opcodes.DMUL, Opcodes.IDIV, Opcodes.FDIV, Opcodes.DDIV -> {
                        double b = number(pop(stack)).doubleValue(), a = number(pop(stack)).doubleValue();
                        double value = switch (opcode) {
                            case Opcodes.IADD, Opcodes.FADD, Opcodes.DADD -> a + b;
                            case Opcodes.ISUB, Opcodes.FSUB, Opcodes.DSUB -> a - b;
                            case Opcodes.IMUL, Opcodes.FMUL, Opcodes.DMUL -> a * b;
                            default -> a / b;
                        };
                        if (!Double.isFinite(value)) throw unsupported();
                        if (opcode == Opcodes.IADD || opcode == Opcodes.ISUB
                                || opcode == Opcodes.IMUL || opcode == Opcodes.IDIV) {
                            int left = (int) a, right = (int) b;
                            stack.add(switch (opcode) {
                                case Opcodes.IADD -> left + right;
                                case Opcodes.ISUB -> left - right;
                                case Opcodes.IMUL -> left * right;
                                default -> left / right;
                            });
                        } else if (opcode == Opcodes.FADD || opcode == Opcodes.FSUB
                                || opcode == Opcodes.FMUL || opcode == Opcodes.FDIV) stack.add((float) value);
                        else stack.add(value);
                    }
                    case Opcodes.ARETURN, Opcodes.FRETURN, Opcodes.IRETURN, Opcodes.DRETURN -> { return pop(stack); }
                    case Opcodes.RETURN -> { return null; }
                    default -> throw new UnsupportedOperationException("opcode " + opcode + " in " + owner.name + '.' + method.name);
                }
            }
        }
        throw unsupported();
    }

    private Object invoke(MethodInsnNode call, Object receiver, Object[] args, int depth) {
        String owner = call.owner;
        if (call.name.equals("<init>") && (owner.equals("java/lang/Object") || receiver instanceof Cubes
                || receiver instanceof Mesh || receiver instanceof List<?>)) return null;
        if (receiver instanceof Deformation deformation && call.name.equals("<init>")) {
            if (args.length != 1) throw unsupported();
            deformation.amount = number(args[0]).floatValue(); return null;
        }
        if (owner.endsWith("/CubeListBuilder")) {
            if (receiver == null && args.length == 0) return new Cubes();
            Cubes builder = (Cubes) receiver;
            Type[] types = Type.getArgumentTypes(call.desc);
            if (args.length == 2 && types[0].getSort() == Type.INT && types[1].getSort() == Type.INT) {
                builder.u = number(args[0]).intValue(); builder.v = number(args[1]).intValue();
            } else if (args.length <= 1) {
                builder.mirror = args.length == 0 || number(args[0]).intValue() != 0;
            } else if (args.length >= 6 && types[0].getSort() == Type.FLOAT) {
                var cube = new ArmorVisualBytecodeScanner.Cube();
                cube.x = f(args[0]); cube.y = f(args[1]); cube.z = f(args[2]);
                cube.dx = f(args[3]); cube.dy = f(args[4]); cube.dz = f(args[5]);
                cube.inflate = args.length > 6 && args[6] instanceof Deformation deformation ? deformation.amount : 0;
                cube.texU = builder.u; cube.texV = builder.v; cube.mirror = builder.mirror;
                builder.cubes.add(cube);
            } else throw unsupported();
            return builder;
        }
        if (receiver instanceof Mesh mesh && args.length == 0) return mesh.root;
        if (owner.endsWith("/PartPose")) {
            if (args.length != 3 && args.length != 6) throw unsupported();
            float[] pose = new float[6];
            int offset = call.name.equals("rotation") || call.name.equals("m_171419_") ? 3 : 0;
            for (int i = 0; i < args.length; i++) pose[offset + i] = f(args[i]);
            return pose;
        }
        if (receiver instanceof Part parent) {
            if (args.length == 1 && args[0] instanceof String name) {
                Part child = parent.children.get(name);
                if (child == null) throw unsupported();
                return child;
            }
            if (args.length == 3 && args[0] instanceof String name && args[1] instanceof Cubes builder
                    && args[2] instanceof float[] pose) {
                Part child = new Part(name); child.pose = pose; child.cubes.addAll(builder.cubes);
                Part previous = parent.children.put(name, child);
                if (previous != null) child.children.putAll(previous.children);
                return child;
            }
            throw unsupported();
        }
        if (owner.endsWith("/LayerDefinition") && args.length == 3 && args[0] instanceof Mesh mesh) {
            var layer = new ArmorVisualBytecodeScanner.LayerModel();
            layer.textureWidth = number(args[1]).intValue(); layer.textureHeight = number(args[2]).intValue();
            flatten(mesh.root, "", layer, 0);
            return layer;
        }
        if (receiver instanceof List<?> raw) {
            @SuppressWarnings("unchecked") List<Object> list = (List<Object>) raw;
            return switch (call.name) {
                case "add" -> { if (list.size() >= 4096) throw unsupported(); list.add(args[0]); yield 1; }
                case "iterator" -> list.iterator();
                default -> throw unsupported();
            };
        }
        if (receiver instanceof Iterator<?> iterator) {
            return switch (call.name) {
                case "hasNext" -> iterator.hasNext() ? 1 : 0;
                case "next" -> iterator.next();
                default -> throw unsupported();
            };
        }
        if (args.length == 1 && (owner.equals("net/minecraft/util/Mth") || owner.equals("java/lang/Math"))) {
            double value = number(args[0]).doubleValue();
            double result = switch (call.name) {
                case "sin", "m_14031_" -> Math.sin(value);
                case "cos", "m_14089_" -> Math.cos(value);
                case "sqrt", "m_14116_" -> Math.sqrt(value);
                default -> throw unsupported();
            };
            if (!Double.isFinite(result)) throw unsupported();
            if (Type.getReturnType(call.desc).getSort() == Type.FLOAT) return (float) result;
            return result;
        }
        for (ClassNode type = classes.get(owner); type != null; type = classes.get(type.superName)) {
            for (MethodNode method : type.methods) {
                if (method.name.equals(call.name) && method.desc.equals(call.desc)) {
                    try { return run(type, method, receiver, args, depth); }
                    catch (UnsupportedOperationException failure) {
                        throw new UnsupportedOperationException(type.name + '.' + method.name, failure);
                    }
                }
            }
        }
        throw new UnsupportedOperationException("call " + call.owner + '.' + call.name + call.desc);
    }

    private static void flatten(Part part, String parent, ArmorVisualBytecodeScanner.LayerModel layer, int depth) {
        if (depth > 32) throw unsupported();
        String name = parent.isEmpty() ? part.name : parent + '/' + part.name;
        if (!part.name.isBlank()) {
            var pose = new ArmorVisualBytecodeScanner.PartPose();
            pose.name = name; pose.parent = parent;
            pose.pivotX = part.pose[0]; pose.pivotY = part.pose[1]; pose.pivotZ = part.pose[2];
            pose.rotX = part.pose[3]; pose.rotY = part.pose[4]; pose.rotZ = part.pose[5];
            layer.parts.add(pose);
            for (var source : part.cubes) {
                var cube = new ArmorVisualBytecodeScanner.Cube();
                cube.part = name; cube.parent = parent;
                cube.x = source.x; cube.y = source.y; cube.z = source.z;
                cube.dx = source.dx; cube.dy = source.dy; cube.dz = source.dz;
                cube.inflate = source.inflate; cube.texU = source.texU; cube.texV = source.texV; cube.mirror = source.mirror;
                layer.cubes.add(cube);
            }
        }
        for (Part child : part.children.values()) flatten(child, name, layer, depth + 1);
    }

    private static Object pop(List<Object> stack) { return stack.remove(stack.size() - 1); }
    private static Number number(Object value) {
        if (value instanceof Number number) return number;
        throw unsupported();
    }
    private static float f(Object value) {
        float result = number(value).floatValue();
        if (!Float.isFinite(result)) throw unsupported();
        return result;
    }
    private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException(); }
}
