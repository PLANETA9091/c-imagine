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
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads constructor-authored box models without loading their client classes.
 *
 * <p>Older and lightweight renderer libraries often build a ModelPart-like tree directly in the
 * model constructor instead of exposing a vanilla {@code LayerDefinition}. The bytecode shape is
 * still highly regular: fresh part objects are stored in fields, a fluent two-int call selects UV
 * offsets, six float dimensions append boxes, one-part calls establish parent/child links, and
 * three-float mutators establish pivots/rotations. Interpreting those data-flow shapes keeps this
 * fallback library- and mod-agnostic while allowing the ordinary model conversion pipeline to
 * consume the result as a {@link ArmorVisualBytecodeScanner.LayerModel}.</p>
 */
final class ModelConstructorBytecodeInterpreter {
    private static final int MAX_INSTRUCTIONS = 48_000;

    private ModelConstructorBytecodeInterpreter() {}

    static ArmorVisualBytecodeScanner.LayerModel read(ClassNode model, Map<String, ClassNode> classes) {
        if (model == null || model.methods == null) return null;
        MethodNode constructor = null;
        for (MethodNode method : model.methods) {
            if ("<init>".equals(method.name) && "()V".equals(method.desc)) {
                constructor = method;
                break;
            }
        }
        if (constructor == null || constructor.instructions == null
                || constructor.instructions.size() > MAX_INSTRUCTIONS) return null;

        Map<AbstractInsnNode, Frame<SourceValue>> frames = analyze(model.name, constructor);
        if (frames.isEmpty()) return null;
        LinkedHashMap<String, String> partTypes = discoverPartFields(model, constructor);
        if (partTypes.isEmpty()) return null;

        LinkedHashMap<String, ArmorVisualBytecodeScanner.PartPose> poses = new LinkedHashMap<>();
        for (String name : partTypes.keySet()) {
            ArmorVisualBytecodeScanner.PartPose pose = new ArmorVisualBytecodeScanner.PartPose();
            pose.name = name;
            poses.put(name, pose);
        }
        LinkedHashMap<String, int[]> textureOffsets = new LinkedHashMap<>();
        LinkedHashSet<String> boxedParts = new LinkedHashSet<>();
        ArrayList<ArmorVisualBytecodeScanner.Cube> cubes = new ArrayList<>();

        int[] dimensions = textureDimensions(model, constructor, frames, partTypes.keySet());
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || "<init>".equals(call.name)) continue;
            Type[] args = Type.getArgumentTypes(call.desc);
            String receiver = receiverPart(model.name, call, frames, partTypes.keySet(), 0);

            if (receiver != null && args.length == 2
                    && args[0].getSort() == Type.INT && args[1].getSort() == Type.INT
                    && Type.getReturnType(call.desc).getSort() == Type.OBJECT) {
                Number u = argumentNumber(call, 0, frames);
                Number v = argumentNumber(call, 1, frames);
                if (u != null && v != null) textureOffsets.put(receiver,
                        new int[]{u.intValue(), v.intValue()});
                continue;
            }

            if (receiver != null && args.length == 3 && allFloat(args)
                    && Type.getReturnType(call.desc).getSort() == Type.VOID
                    && !boxedParts.contains(receiver)) {
                float[] values = floatArguments(call, 0, 3, frames);
                if (values != null) {
                    ArmorVisualBytecodeScanner.PartPose pose = poses.get(receiver);
                    pose.pivotX = values[0]; pose.pivotY = values[1]; pose.pivotZ = values[2];
                }
                continue;
            }

            if (receiver != null && args.length == 1 && args[0].getSort() == Type.OBJECT
                    && Type.getReturnType(call.desc).getSort() == Type.VOID) {
                String child = argumentPart(model.name, call, 0, frames, partTypes.keySet(), 0);
                if (child != null && !child.equals(receiver)) poses.get(child).parent = receiver;
                continue;
            }

            if (receiver != null && args.length >= 6 && firstSixFloat(args)) {
                float[] values = floatArguments(call, 0, 6, frames);
                if (values == null) continue;
                ArmorVisualBytecodeScanner.Cube cube = new ArmorVisualBytecodeScanner.Cube();
                cube.part = receiver;
                cube.x = values[0]; cube.y = values[1]; cube.z = values[2];
                cube.dx = values[3]; cube.dy = values[4]; cube.dz = values[5];
                int[] uv = textureOffsets.get(receiver);
                if (uv != null) { cube.texU = uv[0]; cube.texV = uv[1]; }
                if (args.length > 6 && args[6].getSort() == Type.FLOAT) {
                    Number inflate = argumentNumber(call, 6, frames);
                    if (inflate != null) cube.inflate = inflate.floatValue();
                }
                for (int i = 6; i < args.length; i++) {
                    if (args[i].getSort() != Type.BOOLEAN) continue;
                    Number mirror = argumentNumber(call, i, frames);
                    if (mirror != null) cube.mirror = mirror.intValue() != 0;
                }
                cubes.add(cube);
                boxedParts.add(receiver);
                continue;
            }

            // Model-owned helpers commonly implement setRotationAngle(part, x, y, z). Require the
            // helper body to prove that all three float parameters are written onto that part so a
            // random utility call with the same descriptor cannot be mistaken for a pose.
            if (args.length == 4 && args[0].getSort() == Type.OBJECT
                    && args[1].getSort() == Type.FLOAT && args[2].getSort() == Type.FLOAT
                    && args[3].getSort() == Type.FLOAT && Type.getReturnType(call.desc).getSort() == Type.VOID
                    && writesThreeFloatParameters(call, classes)) {
                String target = argumentPart(model.name, call, 0, frames, partTypes.keySet(), 0);
                float[] values = floatArguments(call, 1, 3, frames);
                if (target != null && values != null) {
                    ArmorVisualBytecodeScanner.PartPose pose = poses.get(target);
                    pose.rotX = values[0]; pose.rotY = values[1]; pose.rotZ = values[2];
                }
            }
        }
        if (cubes.isEmpty()) return null;

        for (ArmorVisualBytecodeScanner.Cube cube : cubes) {
            ArmorVisualBytecodeScanner.PartPose pose = poses.get(cube.part);
            if (pose != null) {
                cube.parent = pose.parent;
                cube.pivotX = pose.pivotX; cube.pivotY = pose.pivotY; cube.pivotZ = pose.pivotZ;
                cube.rotX = pose.rotX; cube.rotY = pose.rotY; cube.rotZ = pose.rotZ;
            }
        }
        ArmorVisualBytecodeScanner.LayerModel out = new ArmorVisualBytecodeScanner.LayerModel();
        out.cubes.addAll(cubes);
        out.parts.addAll(poses.values());
        out.textureWidth = dimensions[0];
        out.textureHeight = dimensions[1];
        return out;
    }

    private static Map<AbstractInsnNode, Frame<SourceValue>> analyze(String owner, MethodNode method) {
        IdentityHashMap<AbstractInsnNode, Frame<SourceValue>> out = new IdentityHashMap<>();
        try {
            Frame<SourceValue>[] analyzed = new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
            for (int i = 0; i < analyzed.length; i++) out.put(method.instructions.get(i), analyzed[i]);
        } catch (AnalyzerException | RuntimeException ignored) {
        }
        return out;
    }

    private static LinkedHashMap<String, String> discoverPartFields(ClassNode model, MethodNode constructor) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                    || !model.name.equals(field.owner)) continue;
            String type = objectType(field.desc);
            if (type == null || !hasMatchingAllocation(instruction, type)) continue;
            out.put(field.name, type);
        }
        return out;
    }

    private static boolean hasMatchingAllocation(AbstractInsnNode start, String type) {
        int code = 0;
        for (AbstractInsnNode cursor = start.getPrevious(); cursor != null && code < 16;
             cursor = cursor.getPrevious()) {
            if (cursor.getOpcode() >= 0) code++;
            if (cursor instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                    && type.equals(allocation.desc)) return true;
            if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD) break;
        }
        return false;
    }

    private static int[] textureDimensions(ClassNode model, MethodNode constructor,
                                           Map<AbstractInsnNode, Frame<SourceValue>> frames,
                                           Set<String> partFields) {
        int width = 64, height = 32;
        List<Integer> unnamed = new ArrayList<>(2);
        boolean sawPart = false;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD) continue;
            if (model.name.equals(field.owner) && partFields.contains(field.name)) {
                sawPart = true;
                continue;
            }
            if (sawPart || !"I".equals(field.desc)) continue;
            Frame<SourceValue> frame = frames.get(instruction);
            Number value = frame == null || frame.getStackSize() < 1
                    ? null : number(frame.getStack(frame.getStackSize() - 1), frames, 0);
            if (value == null || value.intValue() <= 0 || value.intValue() > 8192) continue;
            String name = field.name == null ? "" : field.name.toLowerCase(java.util.Locale.ROOT);
            if (name.contains("texwidth") || name.contains("texturewidth")) width = value.intValue();
            else if (name.contains("texheight") || name.contains("textureheight")) height = value.intValue();
            else if (unnamed.size() < 2) unnamed.add(value.intValue());
        }
        if (width == 64 && height == 32 && unnamed.size() >= 2) {
            width = unnamed.get(0); height = unnamed.get(1);
        }
        return new int[]{Math.max(1, width), Math.max(1, height)};
    }

    private static String receiverPart(String modelOwner, MethodInsnNode call,
                                       Map<AbstractInsnNode, Frame<SourceValue>> frames,
                                       Set<String> partFields, int depth) {
        if (call.getOpcode() == Opcodes.INVOKESTATIC) return null;
        Frame<SourceValue> frame = frames.get(call);
        int args = Type.getArgumentTypes(call.desc).length;
        if (frame == null || frame.getStackSize() <= args) return null;
        return partFromValue(modelOwner, frame.getStack(frame.getStackSize() - args - 1),
                frames, partFields, depth + 1);
    }

    private static String argumentPart(String modelOwner, MethodInsnNode call, int index,
                                       Map<AbstractInsnNode, Frame<SourceValue>> frames,
                                       Set<String> partFields, int depth) {
        SourceValue value = argument(call, index, frames);
        return partFromValue(modelOwner, value, frames, partFields, depth + 1);
    }

    private static String partFromValue(String modelOwner, SourceValue value,
                                        Map<AbstractInsnNode, Frame<SourceValue>> frames,
                                        Set<String> partFields, int depth) {
        if (value == null || depth > 6 || value.insns == null || value.insns.size() != 1) return null;
        AbstractInsnNode source = value.insns.iterator().next();
        if (source instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                && modelOwner.equals(field.owner) && partFields.contains(field.name)) return field.name;
        if (source instanceof MethodInsnNode call && call.getOpcode() != Opcodes.INVOKESTATIC
                && Type.getReturnType(call.desc).getSort() == Type.OBJECT) {
            return receiverPart(modelOwner, call, frames, partFields, depth + 1);
        }
        return null;
    }

    private static SourceValue argument(MethodInsnNode call, int index,
                                        Map<AbstractInsnNode, Frame<SourceValue>> frames) {
        Type[] args = Type.getArgumentTypes(call.desc);
        Frame<SourceValue> frame = frames.get(call);
        if (frame == null || index < 0 || index >= args.length || frame.getStackSize() < args.length) return null;
        return frame.getStack(frame.getStackSize() - args.length + index);
    }

    private static Number argumentNumber(MethodInsnNode call, int index,
                                         Map<AbstractInsnNode, Frame<SourceValue>> frames) {
        return number(argument(call, index, frames), frames, 0);
    }

    private static float[] floatArguments(MethodInsnNode call, int start, int count,
                                          Map<AbstractInsnNode, Frame<SourceValue>> frames) {
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            Number value = argumentNumber(call, start + i, frames);
            if (value == null) return null;
            out[i] = value.floatValue();
        }
        return out;
    }

    private static Number number(SourceValue value, Map<AbstractInsnNode, Frame<SourceValue>> frames, int depth) {
        if (value == null || depth > 6 || value.insns == null || value.insns.size() != 1) return null;
        AbstractInsnNode source = value.insns.iterator().next();
        Number direct = directNumber(source);
        if (direct != null) return direct;
        if (!(source instanceof InsnNode op)) return null;
        Frame<SourceValue> frame = frames.get(source);
        if (frame == null || frame.getStackSize() == 0) return null;
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

    private static boolean writesThreeFloatParameters(MethodInsnNode call, Map<String, ClassNode> classes) {
        if (classes == null) return false;
        ClassNode owner = classes.get(call.owner);
        if (owner == null || owner.methods == null) return false;
        MethodNode target = null;
        for (MethodNode method : owner.methods) {
            if (method.name.equals(call.name) && method.desc.equals(call.desc)) { target = method; break; }
        }
        if (target == null) return false;
        Set<Integer> loads = new LinkedHashSet<>();
        int writes = 0;
        for (AbstractInsnNode instruction : target.instructions) {
            if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                    || !"F".equals(field.desc)) continue;
            AbstractInsnNode value = previousReal(instruction);
            AbstractInsnNode receiver = previousReal(value);
            if (value instanceof org.objectweb.asm.tree.VarInsnNode load
                    && load.getOpcode() == Opcodes.FLOAD
                    && receiver instanceof org.objectweb.asm.tree.VarInsnNode object
                    && object.getOpcode() == Opcodes.ALOAD && object.var == 1) {
                loads.add(load.var);
                writes++;
            }
        }
        return writes >= 3 && loads.contains(2) && loads.contains(3) && loads.contains(4);
    }

    private static boolean allFloat(Type[] args) {
        for (Type arg : args) if (arg.getSort() != Type.FLOAT) return false;
        return true;
    }

    private static boolean firstSixFloat(Type[] args) {
        if (args.length < 6) return false;
        for (int i = 0; i < 6; i++) if (args[i].getSort() != Type.FLOAT) return false;
        return true;
    }

    private static String objectType(String descriptor) {
        try {
            Type type = Type.getType(descriptor);
            return type.getSort() == Type.OBJECT ? type.getInternalName() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getPrevious();
        return cursor;
    }
}
