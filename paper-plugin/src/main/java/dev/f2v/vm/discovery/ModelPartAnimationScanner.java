package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.util.*;

/** Read-only interpretation of vanilla EntityModel's five numeric animation inputs. Field aliases
 * come from constructor getChild calls, not limb names. Unknown calls/branches are not executed. */
public final class ModelPartAnimationScanner {
    private static final String PART = "net/minecraft/client/model/geom/ModelPart";
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
    public static final class Expression {
        public String op;
        public double value;
        public int input;
        public String source;
        public List<Expression> args = new ArrayList<>();
        public double evaluate(double[] inputs, Map<String, Double> pose) {
            return switch (op) {
                case "constant" -> value;
                case "input" -> input >= 0 && input < inputs.length ? inputs[input] : Double.NaN;
                case "read" -> pose.getOrDefault(source, Double.NaN);
                case "add" -> args.get(0).evaluate(inputs, pose) + args.get(1).evaluate(inputs, pose);
                case "sub" -> args.get(0).evaluate(inputs, pose) - args.get(1).evaluate(inputs, pose);
                case "mul" -> args.get(0).evaluate(inputs, pose) * args.get(1).evaluate(inputs, pose);
                case "div" -> args.get(0).evaluate(inputs, pose) / args.get(1).evaluate(inputs, pose);
                case "neg" -> -args.get(0).evaluate(inputs, pose);
                case "sin" -> Math.sin(args.get(0).evaluate(inputs, pose));
                case "cos" -> Math.cos(args.get(0).evaluate(inputs, pose));
                case "float" -> (float) args.get(0).evaluate(inputs, pose);
                default -> Double.NaN;
            };
        }
    }
    public static final class Write {
        public String part, channel;
        public Expression expression;
    }
    public static final class Program {
        public String modelClass;
        public final List<Write> writes = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();

        /** Values are radians and ModelPart pixels, exactly as in the source setupAnim method. */
        public Map<String, Double> evaluate(double[] inputs, Map<String, Double> restPose) {
            Map<String, Double> result = new LinkedHashMap<>(restPose);
            for (Write write : writes) {
                double value = write.expression.evaluate(inputs, result);
                if (Double.isFinite(value)) result.put(write.part + ':' + write.channel, value);
            }
            return result;
        }
    }

    private record Symbol(Expression number, String part, String text) {}
    private ModelPartAnimationScanner() {}

    static Program scan(ClassNode model) {
        return scan(model, null);
    }

    /**
     * @param knownParts part names the layer parser actually recovered, or {@code null} when they
     *                   are unknown. Synthesised vanilla writes are filtered against this set so a
     *                   model that renamed its limbs never produces writes for parts that do not
     *                   exist - {@code EntityModelBuilder} discards the whole program in that case.
     */
    static Program scan(ClassNode model, Set<String> knownParts) {
        Program program = new Program(); program.modelClass = model.name;
        String vanillaBase = null;
        Map<String, String> aliases = new HashMap<>(); Set<String> ambiguous = new HashSet<>();
        for (MethodNode method : model.methods) {
            if (!"<init>".equals(method.name)) continue;
            int roots = 0;
            for (Type arg : Type.getArgumentTypes(method.desc)) if (("L" + PART + ";").equals(arg.getDescriptor())) roots++;
            if (roots != 1) continue;
            Flow flow = Flow.create(model, method, aliases, true);
            if (flow == null) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                        || !field.owner.equals(model.name) || !("L" + PART + ";").equals(field.desc)) continue;
                Frame<SourceValue> frame = flow.frame(field);
                if (frame == null || frame.getStackSize() < 2 || !flow.isThis(frame.getStack(frame.getStackSize() - 2))) continue;
                Symbol value = flow.resolve(frame.getStack(frame.getStackSize() - 1), new HashSet<>(), 0);
                if (value == null || value.part == null) continue;
                String key = field.owner + '.' + field.name;
                String old = aliases.putIfAbsent(key, value.part);
                if (old != null && !old.equals(value.part)) ambiguous.add(key);
            }
        }
        for (String key : ambiguous) aliases.remove(key);
        for (MethodNode method : model.methods) {
            Type[] args = Type.getArgumentTypes(method.desc);
            if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_BRIDGE)) != 0 || args.length != 6
                    || args[0].getSort() != Type.OBJECT || !method.desc.endsWith("FFFFF)V")) continue;
            Flow flow = Flow.create(model, method, aliases, false);
            if (flow == null) { program.warnings.add("animation bytecode exceeds limits or cannot be analyzed"); continue; }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && (call.owner.equals(model.name) || call.desc.endsWith(")V")
                        || call.desc.contains("L" + PART + ";"))
                        && !(PART.equals(call.owner) && ("getChild".equals(call.name) || "m_171324_".equals(call.name)))) {
                    // A mod model whose setupAnim is nothing but `super.setupAnim(...)` (very common:
                    // Recruits' RecruitVillagerModel is exactly that) used to be reported as an
                    // uninterpreted helper, which made EntityModelBuilder throw the whole program
                    // away and ship a rigid, never-walking entity. The vanilla base classes have
                    // fixed, well-known limb math, so replay it instead of giving up.
                    String base = vanillaSetupAnimTarget(call);
                    if (base != null) {
                        vanillaBase = base;
                        continue;
                    }
                    program.warnings.add("uninterpreted animation helper: " + call.owner + '.' + call.name);
                }
                if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                        || !PART.equals(field.owner) || !"F".equals(field.desc)) continue;
                String channel = channel(field.name);
                if (channel == null) continue;
                Frame<SourceValue> frame = flow.frame(field);
                if (frame == null || frame.getStackSize() < 2) continue;
                Symbol receiver = flow.resolve(frame.getStack(frame.getStackSize() - 2), new HashSet<>(), 0);
                Symbol value = flow.resolve(frame.getStack(frame.getStackSize() - 1), new HashSet<>(), 0);
                var guard = ClientSurfaceScanner.screenDrawPredicate(model, method, field, Map.of(model.name, model));
                if (receiver == null || receiver.part == null || value == null || value.number == null
                        || guard == null || guard.op != RendererBytecodeScanner.BooleanOp.CONSTANT || !guard.constant) {
                    program.warnings.add("unsupported animated write: " + field.name + " in " + method.name);
                    continue;
                }
                Write write = new Write(); write.part = receiver.part; write.channel = channel; write.expression = value.number;
                program.writes.add(write);
            }
        }
        if (program.writes.isEmpty() && program.warnings.isEmpty() && vanillaBase != null) {
            appendVanillaBaseAnimation(program, vanillaBase, knownParts);
        }
        return program;
    }

    /** Recognises `super.setupAnim(entity, limbSwing, amount, age, headYaw, headPitch)`. */
    private static String vanillaSetupAnimTarget(MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKESPECIAL && call.getOpcode() != Opcodes.INVOKEVIRTUAL) return null;
        if (!("setupAnim".equals(call.name) || "m_6973_".equals(call.name))) return null;
        if (!call.desc.endsWith("FFFFF)V")) return null;
        return switch (call.owner) {
            case "net/minecraft/client/model/HumanoidModel",
                 "net/minecraft/client/model/PlayerModel",
                 "net/minecraft/client/model/ZombieModel",
                 "net/minecraft/client/model/AbstractZombieModel",
                 "net/minecraft/client/model/SkeletonModel",
                 "net/minecraft/client/model/IllagerModel" -> "humanoid";
            case "net/minecraft/client/model/VillagerModel" -> "villager";
            case "net/minecraft/client/model/QuadrupedModel",
                 "net/minecraft/client/model/HorseModel",
                 "net/minecraft/client/model/ChickenModel",
                 "net/minecraft/client/model/OcelotModel" -> "quadruped";
            default -> null;
        };
    }

    // Animation inputs, in the order EntityModelBuilder feeds them:
    // 0 limbSwing, 1 limbSwingAmount, 2 ageInTicks, 3 netHeadYaw, 4 headPitch.
    private static final int LIMB_SWING = 0;
    private static final int LIMB_SWING_AMOUNT = 1;
    private static final int AGE_IN_TICKS = 2;
    private static final int HEAD_YAW = 3;
    private static final int HEAD_PITCH = 4;

    private static Expression constant(double value) {
        Expression e = new Expression(); e.op = "constant"; e.value = value; return e;
    }

    private static Expression input(int index) {
        Expression e = new Expression(); e.op = "input"; e.input = index; return e;
    }

    private static Expression read(String part, String channel) {
        Expression e = new Expression(); e.op = "read"; e.source = part + ':' + channel; return e;
    }

    private static Expression binary(String op, Expression left, Expression right) {
        Expression e = new Expression(); e.op = op; e.args.add(left); e.args.add(right); return e;
    }

    private static Expression unary(String op, Expression argument) {
        Expression e = new Expression(); e.op = op; e.args.add(argument); return e;
    }

    /** cos(limbSwing * 0.6662 + phase) * factor * limbSwingAmount, i.e. the vanilla limb swing. */
    private static Expression swing(double phase, double factor) {
        Expression angle = binary("add",
                binary("mul", input(LIMB_SWING), constant(0.6662d)), constant(phase));
        return binary("mul", binary("mul", unary("cos", angle), constant(factor)),
                input(LIMB_SWING_AMOUNT));
    }

    private static void write(Program program, Set<String> knownParts, String part, String channel,
                              Expression expression) {
        if (knownParts != null && !knownParts.contains(part)) return;
        Write w = new Write(); w.part = part; w.channel = channel; w.expression = expression;
        program.writes.add(w);
    }

    /**
     * Replays the limb math of the vanilla base model the mod delegates to.
     *
     * <p>Only the unconditional parts of {@code setupAnim} are reproduced. State-dependent poses
     * (riding, swimming, crossbow hold, attack swing) are deliberately skipped: the generated
     * resource pack only carries idle/walk/run gait frames anyway.
     */
    private static void appendVanillaBaseAnimation(Program program, String base, Set<String> knownParts) {
        double degrees = Math.PI / 180d;
        if ("humanoid".equals(base)) {
            write(program, knownParts, "head", "yRot",
                    binary("mul", input(HEAD_YAW), constant(degrees)));
            write(program, knownParts, "head", "xRot",
                    binary("mul", input(HEAD_PITCH), constant(degrees)));
            write(program, knownParts, "right_arm", "xRot", swing(Math.PI, 2.0d * 0.5d));
            write(program, knownParts, "left_arm", "xRot", swing(0d, 2.0d * 0.5d));
            write(program, knownParts, "right_arm", "yRot", constant(0d));
            write(program, knownParts, "left_arm", "yRot", constant(0d));
            write(program, knownParts, "right_arm", "zRot", constant(0d));
            write(program, knownParts, "left_arm", "zRot", constant(0d));
            write(program, knownParts, "right_leg", "xRot", swing(0d, 1.4d));
            write(program, knownParts, "left_leg", "xRot", swing(Math.PI, 1.4d));
            write(program, knownParts, "right_leg", "yRot", constant(0.005d));
            write(program, knownParts, "left_leg", "yRot", constant(-0.005d));
            write(program, knownParts, "right_leg", "zRot", constant(0.005d));
            write(program, knownParts, "left_leg", "zRot", constant(-0.005d));
            // HumanoidModel.bobArms: the slow idle sway that keeps a standing mob from looking dead.
            Expression sway = binary("add",
                    binary("mul", unary("cos", binary("mul", input(AGE_IN_TICKS), constant(0.09d))),
                            constant(0.05d)),
                    constant(0.05d));
            Expression bob = binary("mul",
                    unary("sin", binary("mul", input(AGE_IN_TICKS), constant(0.067d))), constant(0.05d));
            write(program, knownParts, "right_arm", "zRot",
                    binary("add", read("right_arm", "zRot"), sway));
            write(program, knownParts, "left_arm", "zRot",
                    binary("sub", read("left_arm", "zRot"), sway));
            write(program, knownParts, "right_arm", "xRot",
                    binary("add", read("right_arm", "xRot"), bob));
            write(program, knownParts, "left_arm", "xRot",
                    binary("sub", read("left_arm", "xRot"), bob));
            return;
        }
        if ("villager".equals(base)) {
            write(program, knownParts, "head", "yRot",
                    binary("mul", input(HEAD_YAW), constant(degrees)));
            write(program, knownParts, "head", "xRot",
                    binary("mul", input(HEAD_PITCH), constant(degrees)));
            write(program, knownParts, "head", "zRot", constant(0d));
            write(program, knownParts, "right_leg", "xRot", swing(0d, .7d));
            write(program, knownParts, "left_leg", "xRot", swing(Math.PI, .7d));
            write(program, knownParts, "right_leg", "yRot", constant(0d));
            write(program, knownParts, "left_leg", "yRot", constant(0d));
            return;
        }
        if ("quadruped".equals(base)) {
            write(program, knownParts, "head", "xRot",
                    binary("mul", input(HEAD_PITCH), constant(degrees)));
            write(program, knownParts, "head", "yRot",
                    binary("mul", input(HEAD_YAW), constant(degrees)));
            write(program, knownParts, "right_hind_leg", "xRot", swing(0d, 1.4d));
            write(program, knownParts, "left_hind_leg", "xRot", swing(Math.PI, 1.4d));
            write(program, knownParts, "right_front_leg", "xRot", swing(Math.PI, 1.4d));
            write(program, knownParts, "left_front_leg", "xRot", swing(0d, 1.4d));
        }
    }

    private static String channel(String name) {
        return switch (name) {
            case "x", "f_104200_" -> "x";
            case "y", "f_104201_" -> "y";
            case "z", "f_104202_" -> "z";
            case "xRot", "f_104203_" -> "xRot";
            case "yRot", "f_104204_" -> "yRot";
            case "zRot", "f_104205_" -> "zRot";
            default -> null;
        };
    }

    private static Expression expression(String op, Expression... args) {
        for (Expression arg : args) if (arg == null) return null;
        Expression result = new Expression(); result.op = op; result.args = List.of(args); return result;
    }
    private static Symbol number(Expression value) { return value == null ? null : new Symbol(value, null, null); }

    private static final class Sources extends SourceInterpreter {
        final Map<AbstractInsnNode, Integer> parameters = new IdentityHashMap<>();
        final Map<Integer, Type> types = new HashMap<>();
        Sources() { super(Opcodes.ASM9); }
        @Override public SourceValue newParameterValue(boolean instance, int local, Type type) {
            LabelNode parameter = new LabelNode(); parameters.put(parameter, local); types.put(local, type);
            return new SourceValue(type.getSize(), parameter);
        }
        @Override public SourceValue copyOperation(AbstractInsnNode insn, SourceValue value) { return value; }
        @Override public SourceValue unaryOperation(AbstractInsnNode insn, SourceValue value) {
            return insn.getOpcode() == Opcodes.CHECKCAST ? value : super.unaryOperation(insn, value);
        }
    }

    private static final class Flow {
        final MethodNode method;
        final Frame<SourceValue>[] frames;
        final Sources sources;
        final Map<String, String> aliases;
        final boolean constructor;
        int remaining = 16_000;
        Flow(MethodNode method, Frame<SourceValue>[] frames, Sources sources, Map<String, String> aliases, boolean constructor) {
            this.method = method; this.frames = frames; this.sources = sources; this.aliases = aliases; this.constructor = constructor;
        }
        static Flow create(ClassNode owner, MethodNode method, Map<String, String> aliases, boolean constructor) {
            if (method.instructions.size() > 4096 || (long) method.instructions.size()
                    * (method.maxLocals + method.maxStack) > 500_000) return null;
            try {
                Sources interpreter = new Sources();
                return new Flow(method, new Analyzer<>(interpreter).analyze(owner.name, method), interpreter, aliases, constructor);
            } catch (AnalyzerException | RuntimeException failure) { return null; }
        }
        Frame<SourceValue> frame(AbstractInsnNode instruction) {
            int index = method.instructions.indexOf(instruction);
            return index >= 0 ? frames[index] : null;
        }
        boolean isThis(SourceValue value) {
            return value != null && value.insns.size() == 1
                    && Integer.valueOf(0).equals(sources.parameters.get(value.insns.iterator().next()));
        }
        Symbol resolve(SourceValue value, Set<AbstractInsnNode> visiting, int depth) {
            if (value == null || value.insns.isEmpty() || depth > 32 || remaining-- <= 0) return null;
            Symbol result = null;
            for (AbstractInsnNode source : value.insns) {
                if (!visiting.add(source)) return null;
                Symbol current;
                try { current = producer(source, visiting, depth + 1); }
                finally { visiting.remove(source); }
                if (current == null) return null;
                if (result != null && !GSON.toJson(result).equals(GSON.toJson(current))) return null;
                result = current;
            }
            return result;
        }
        Symbol producer(AbstractInsnNode source, Set<AbstractInsnNode> visiting, int depth) {
            Integer parameter = sources.parameters.get(source);
            if (parameter != null) {
                Type type = sources.types.get(parameter);
                if (constructor && ("L" + PART + ";").equals(type.getDescriptor())) return new Symbol(null, "", null);
                if (!constructor && parameter >= 2 && parameter <= 6 && type == Type.FLOAT_TYPE) {
                    Expression value = expression("input"); value.input = parameter - 2; return number(value);
                }
                return null;
            }
            Object constant = source instanceof LdcInsnNode ldc ? ldc.cst : source instanceof IntInsnNode integer
                    && integer.getOpcode() != Opcodes.NEWARRAY ? integer.operand : null;
            int op = source.getOpcode();
            if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) constant = op - Opcodes.ICONST_0;
            if (op >= Opcodes.FCONST_0 && op <= Opcodes.FCONST_2) constant = op - Opcodes.FCONST_0;
            if (op >= Opcodes.DCONST_0 && op <= Opcodes.DCONST_1) constant = op - Opcodes.DCONST_0;
            if (constant instanceof Number n) {
                if (!Double.isFinite(n.doubleValue())) return null;
                Expression value = expression("constant"); value.value = n.doubleValue(); return number(value);
            }
            if (constant instanceof String text) return new Symbol(null, null, text);
            Frame<SourceValue> frame = frame(source); if (frame == null) return null;
            int count = frame.getStackSize();
            if (source instanceof FieldInsnNode field && op == Opcodes.GETFIELD && count > 0) {
                if (aliases.containsKey(field.owner + '.' + field.name) && isThis(frame.getStack(count - 1))) {
                    return new Symbol(null, aliases.get(field.owner + '.' + field.name), null);
                }
                Symbol receiver = resolve(frame.getStack(count - 1), visiting, depth);
                String channel = channel(field.name);
                if (PART.equals(field.owner) && receiver != null && receiver.part != null && channel != null) {
                    Expression value = expression("read"); value.source = receiver.part + ':' + channel; return number(value);
                }
            }
            if (source instanceof MethodInsnNode call) {
                int args = Type.getArgumentTypes(call.desc).length;
                if (PART.equals(call.owner) && ("getChild".equals(call.name) || "m_171324_".equals(call.name)) && count >= 2) {
                    Symbol parent = resolve(frame.getStack(count - 2), visiting, depth);
                    Symbol name = resolve(frame.getStack(count - 1), visiting, depth);
                    if (parent != null && parent.part != null && name != null && name.text != null) {
                        return new Symbol(null, name.text, null); // LayerModel uses authored child names.
                    }
                }
                if (call.getOpcode() == Opcodes.INVOKESTATIC && args == 1 && count > 0
                        && Set.of("java/lang/Math", "java/lang/StrictMath", "net/minecraft/util/Mth").contains(call.owner)) {
                    String operation = switch (call.name) {
                        case "sin", "m_14031_" -> "sin"; case "cos", "m_14089_" -> "cos"; default -> null;
                    };
                    Symbol value = resolve(frame.getStack(count - 1), visiting, depth);
                    if (operation != null && value != null) return number(expression(operation, value.number));
                }
            }
            String operation = switch (op) {
                case Opcodes.FADD, Opcodes.DADD, Opcodes.IADD -> "add";
                case Opcodes.FSUB, Opcodes.DSUB, Opcodes.ISUB -> "sub";
                case Opcodes.FMUL, Opcodes.DMUL, Opcodes.IMUL -> "mul";
                case Opcodes.FDIV, Opcodes.DDIV -> "div";
                default -> null;
            };
            if (operation != null && count >= 2) {
                Symbol left = resolve(frame.getStack(count - 2), visiting, depth), right = resolve(frame.getStack(count - 1), visiting, depth);
                return left == null || right == null ? null : number(expression(operation, left.number, right.number));
            }
            if (count > 0 && (op == Opcodes.FNEG || op == Opcodes.DNEG || op == Opcodes.F2D
                    || op == Opcodes.D2F || op == Opcodes.I2F || op == Opcodes.I2D)) {
                Symbol value = resolve(frame.getStack(count - 1), visiting, depth);
                if (value == null) return null;
                if (op == Opcodes.FNEG || op == Opcodes.DNEG) return number(expression("neg", value.number));
                return op == Opcodes.D2F ? number(expression("float", value.number)) : value;
            }
            return null;
        }
    }
}
