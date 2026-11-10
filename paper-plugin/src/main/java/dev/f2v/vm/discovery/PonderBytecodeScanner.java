package dev.f2v.vm.discovery;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

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
 * Declarative Ponder/story-board discovery.  This scanner never loads Ponder or mod classes.
 * It reconstructs component -> structure -> story method edges from registration bytecode and a
 * small timeline IR from the story-board method itself.  Runtime compatibility code consumes only
 * that IR, so a dedicated server does not need the client Ponder library on its class path.
 */
public final class PonderBytecodeScanner {
    public static final int SCHEMA = 1;
    private static final int MAX_SCENES = 1024;
    private static final int MAX_STEPS = 384;

    public enum Op {
        SHOW_BASE_PLATE,
        SHOW_SECTION,
        HIDE_SECTION,
        MOVE_SECTION,
        SET_KINETIC_SPEED,
        TEXT,
        INPUT,
        EFFECT,
        KEYFRAME
    }

    /** Inclusive integer selection in Ponder structure coordinates. */
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    /** One scanner-recovered timeline instruction. Fields are nullable when the bytecode is dynamic. */
    public static final class Step {
        public int tick;
        public int duration;
        public Op op;
        public Bounds bounds;
        public String text;
        public String hint;
        public Double x;
        public Double y;
        public Double z;
        public Float value;
    }

    public static final class Scene {
        /** Direct jar path or compound outer.jar!/nested.jar reference from ModScanner. */
        public String sourceJar;
        public String namespace;
        public String structure;
        public String structureAsset;
        public String storyOwner;
        public String storyMethod;
        public String storyDescriptor;
        public String titleKey;
        public String title;
        public int totalTicks;
        public final Set<String> subjects = new LinkedHashSet<>();
        public final List<Step> steps = new ArrayList<>();
    }

    private PonderBytecodeScanner() {}

    /** Archive-wide pass. Registration helpers and story methods are commonly different classes. */
    public static List<Scene> scan(Map<String, ClassNode> nodes, String modId,
                                   Map<String, BehaviorBytecodeScanner.RegistryRef> knownRegistryFields) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        String namespace = usableNamespace(modId) ? modId : "minecraft";
        Map<String, String> fields = discoverComponentFields(nodes, namespace, knownRegistryFields);
        ArrayList<Scene> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ClassNode node : nodes.values()) {
            if (node == null || node.methods == null) continue;
            for (MethodNode method : node.methods) {
                if (result.size() >= MAX_SCENES) break;
                if (!mayRegisterPonder(method)) continue;
                scanRegistrationMethod(node, method, nodes, fields, namespace, result, seen);
            }
        }
        result.sort(Comparator.comparing((Scene scene) -> safe(scene.subjects.stream().findFirst().orElse("")))
                .thenComparing(scene -> safe(scene.structure)));
        return List.copyOf(result);
    }

    /** Exact runtime query. Nested profiles are traversed, but namespace-wide guessing is not used. */
    public static List<Scene> scenesForItem(Collection<ModScanner.Profile> profiles, String itemId) {
        if (profiles == null || itemId == null || itemId.isBlank()) return List.of();
        ArrayList<Scene> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collectScenes(profile, itemId, out, seen);
        out.sort(Comparator.comparing(scene -> safe(scene.structure)));
        return List.copyOf(out);
    }

    private static void collectScenes(ModScanner.Profile profile, String itemId,
                                      List<Scene> out, Set<String> seen) {
        if (profile == null) return;
        if (profile.ponderScenes != null) {
            for (Scene scene : profile.ponderScenes) {
                if (scene == null || !scene.subjects.contains(itemId)) continue;
                String key = safe(scene.storyOwner) + '#' + safe(scene.storyMethod) + safe(scene.storyDescriptor)
                        + '\n' + safe(scene.structure);
                if (seen.add(key)) out.add(scene);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collectScenes(child, itemId, out, seen);
        }
    }

    /** Recover registry-entry fields without executing Registrate/DeferredRegister builders. */
    static Map<String, String> discoverComponentFields(Map<String, ClassNode> nodes, String namespace,
                                                       Map<String, BehaviorBytecodeScanner.RegistryRef> known) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (known != null) {
            for (BehaviorBytecodeScanner.RegistryRef ref : known.values()) {
                if (ref != null && ref.field != null && ref.id != null
                        && ("item".equals(ref.registryKind) || "block".equals(ref.registryKind)
                        || "registry".equals(ref.registryKind))) out.put(ref.field, ref.id);
            }
        }
        for (ClassNode node : nodes.values()) {
            if (node == null || node.methods == null) continue;
            for (MethodNode method : node.methods) {
                if (!"<clinit>".equals(method.name)) continue;
                String pending = null;
                int age = 0;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (pending != null && ++age > 220) pending = null;
                    if (instruction instanceof MethodInsnNode call && componentBuilderCall(call)) {
                        String path = nearestString(instruction, 18);
                        if (simplePath(path)) {
                            pending = path.indexOf(':') >= 0 ? path : namespace + ':' + path;
                            age = 0;
                        }
                        continue;
                    }
                    if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
                            && pending != null && entryDescriptor(field.desc)) {
                        out.putIfAbsent(field.owner + '.' + field.name, pending);
                        pending = null;
                    }
                }
            }
        }
        return out;
    }

    private static boolean componentBuilderCall(MethodInsnNode call) {
        if (call == null || call.desc == null || call.name == null) return false;
        String name = call.name.toLowerCase(Locale.ROOT);
        if (!(name.equals("item") || name.equals("block") || name.equals("register"))) return false;
        if (!call.desc.contains("Ljava/lang/String;")) return false;
        String ret;
        try { ret = Type.getReturnType(call.desc).getInternalName(); }
        catch (Exception ignored) { return false; }
        String lower = ret == null ? "" : ret.toLowerCase(Locale.ROOT);
        String owner = call.owner == null ? "" : call.owner.toLowerCase(Locale.ROOT);
        return lower.contains("builder") || lower.contains("entry") || owner.contains("registrate")
                || owner.contains("deferredregister");
    }

    private static boolean entryDescriptor(String desc) {
        if (desc == null) return false;
        String lower = desc.toLowerCase(Locale.ROOT);
        return lower.contains("itementry") || lower.contains("blockentry")
                || lower.contains("itemproviderentry") || lower.contains("registryobject")
                || lower.contains("deferred") && lower.contains("holder");
    }

    private static boolean mayRegisterPonder(MethodNode method) {
        if (method == null || method.instructions == null) return false;
        String descriptor = safe(method.desc).toLowerCase(Locale.ROOT);
        if (descriptor.contains("pondersceneregistrationhelper") || descriptor.contains("multiscenebuilder")) {
            return true;
        }
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                String owner = safe(call.owner).toLowerCase(Locale.ROOT);
                if ((owner.contains("ponder") || safe(call.desc).toLowerCase(Locale.ROOT).contains("ponder"))
                        && (safe(call.name).equals("addStoryBoard") || safe(call.name).equals("forComponents"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void scanRegistrationMethod(ClassNode owner, MethodNode method,
                                               Map<String, ClassNode> nodes, Map<String, String> fields,
                                               String namespace, List<Scene> result, Set<String> seen) {
        LinkedHashSet<String> activeSubjects = new LinkedHashSet<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            String name = safe(call.name);
            if (name.equals("forComponents")) {
                LinkedHashSet<String> found = componentFieldsBefore(instruction, fields, 96);
                if (!found.isEmpty()) {
                    activeSubjects.clear();
                    activeSubjects.addAll(found);
                }
                continue;
            }
            if (!name.equals("addStoryBoard")) continue;
            String path = nearestString(instruction, 42);
            Handle story = nearestLambdaHandle(instruction, 34);
            if (!simpleScenePath(path) || story == null) continue;
            LinkedHashSet<String> subjects = new LinkedHashSet<>(activeSubjects);
            // Direct helper.addStoryBoard(component, path, program, ...) does not establish an
            // active MultiSceneBuilder, so recover the concrete component immediately before path.
            if (safe(call.owner).toLowerCase(Locale.ROOT).contains("pondersceneregistrationhelper")) {
                LinkedHashSet<String> direct = componentFieldsBefore(instruction, fields, 48);
                if (!direct.isEmpty()) subjects = direct;
            }
            if (subjects.isEmpty()) continue;
            String key = subjects + "\n" + path + "\n" + story.getOwner() + '#'
                    + story.getName() + story.getDesc();
            if (!seen.add(key)) continue;
            Scene scene = new Scene();
            scene.namespace = namespace;
            scene.structure = namespace + ':' + path;
            scene.structureAsset = "assets/" + namespace + "/ponder/" + path + ".nbt";
            scene.storyOwner = story.getOwner();
            scene.storyMethod = story.getName();
            scene.storyDescriptor = story.getDesc();
            scene.subjects.addAll(subjects);
            MethodNode storyMethod = findMethod(nodes.get(story.getOwner()), story.getName(), story.getDesc());
            if (storyMethod != null) scanTimeline(scene, storyMethod);
            result.add(scene);
            if (result.size() >= MAX_SCENES) return;
        }
    }

    private static LinkedHashSet<String> componentFieldsBefore(AbstractInsnNode point,
                                                               Map<String, String> fields, int limit) {
        LinkedHashSet<String> reverse = new LinkedHashSet<>();
        for (AbstractInsnNode p = point == null ? null : point.getPrevious(); p != null && limit-- > 0;
             p = p.getPrevious()) {
            if (p instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) {
                String id = fields.get(field.owner + '.' + field.name);
                if (id != null) reverse.add(id);
            }
            if (p instanceof MethodInsnNode call && (safe(call.name).equals("forComponents")
                    || safe(call.name).equals("addStoryBoard"))) break;
        }
        ArrayList<String> ordered = new ArrayList<>(reverse);
        java.util.Collections.reverse(ordered);
        return new LinkedHashSet<>(ordered);
    }

    private static Handle nearestLambdaHandle(AbstractInsnNode point, int limit) {
        for (AbstractInsnNode p = point == null ? null : point.getPrevious(); p != null && limit-- > 0;
             p = p.getPrevious()) {
            if (p instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
                for (Object argument : indy.bsmArgs) {
                    if (argument instanceof Handle handle && !"<init>".equals(handle.getName())) return handle;
                }
            }
            if (p instanceof MethodInsnNode call && safe(call.name).equals("addStoryBoard")) break;
        }
        return null;
    }

    private static void scanTimeline(Scene scene, MethodNode method) {
        int tick = 0;
        int pendingTextDuration = 0;
        Bounds lastBounds = null;
        double[] lastVector = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            String name = safe(call.name);
            String lowerOwner = safe(call.owner).toLowerCase(Locale.ROOT);

            if (name.equals("title") && call.desc.contains("Ljava/lang/String;Ljava/lang/String;")) {
                List<String> values = nearestStrings(instruction, 20, 2);
                if (values.size() >= 2) {
                    scene.titleKey = values.get(values.size() - 2);
                    scene.title = values.get(values.size() - 1);
                }
            } else if (name.equals("idle") || name.equals("idleSeconds")) {
                Integer value = nearestInt(instruction, 8);
                if (value != null) tick += name.equals("idleSeconds") ? value * 20 : Math.max(0, value);
            } else if (name.equals("fromTo") && call.desc.startsWith("(IIIIII)")) {
                List<Integer> values = nearestInts(instruction, 24, 6);
                if (values.size() == 6) lastBounds = bounds(values);
            } else if ((name.equals("position") || name.equals("at")) && call.desc.startsWith("(III)")) {
                List<Integer> values = nearestInts(instruction, 12, 3);
                if (values.size() == 3) {
                    lastBounds = new Bounds(values.get(0), values.get(1), values.get(2),
                            values.get(0), values.get(1), values.get(2));
                }
            } else if ((name.equals("of") || name.equals("centerOf") || name.equals("topOf"))
                    && (lowerOwner.contains("vectorutil") || call.desc.endsWith("Vec3;"))) {
                List<Double> values = nearestNumbers(instruction, 18, 3);
                if (values.size() == 3) lastVector = new double[]{values.get(0), values.get(1), values.get(2)};
            } else if (name.equals("showBasePlate")) {
                add(scene, step(tick, 0, Op.SHOW_BASE_PLATE, null, null, null, null));
            } else if (name.equals("showSection")) {
                add(scene, step(tick, 0, Op.SHOW_SECTION, lastBounds, null, null, null));
            } else if (name.equals("hideSection")) {
                add(scene, step(tick, 0, Op.HIDE_SECTION, lastBounds, null, null, null));
            } else if (name.equals("moveSection")) {
                Integer duration = nearestInt(instruction, 10);
                Step step = step(tick, duration == null ? 0 : Math.max(0, duration), Op.MOVE_SECTION,
                        lastBounds, null, null, lastVector);
                add(scene, step);
            } else if (name.equals("setKineticSpeed") || name.equals("modifyKineticSpeed")) {
                Step step = step(tick, 0, Op.SET_KINETIC_SPEED, lastBounds, null, name, null);
                Float speed = nearestFloat(instruction, 12);
                step.value = speed;
                add(scene, step);
            } else if (name.equals("showText")) {
                Integer duration = nearestInt(instruction, 8);
                pendingTextDuration = duration == null ? 40 : Math.max(1, duration);
            } else if (name.equals("text") && call.desc.contains("Ljava/lang/String;")) {
                String text = nearestString(instruction, 12);
                if (text != null) add(scene, step(tick, pendingTextDuration <= 0 ? 40 : pendingTextDuration,
                        Op.TEXT, null, text, null, null));
                pendingTextDuration = 0;
            } else if (name.equals("showControls")) {
                Integer duration = nearestInt(instruction, 10);
                add(scene, step(tick, duration == null ? 30 : Math.max(1, duration),
                        Op.INPUT, null, null, "controls", lastVector));
            } else if (name.equals("rightClick") || name.equals("leftClick") || name.equals("whileSneaking")
                    || name.equals("withItem")) {
                Step last = lastStep(scene, Op.INPUT, tick);
                if (last != null) last.hint = last.hint == null ? name : last.hint + '+' + name;
            } else if (name.startsWith("indicate") || name.contains("particle") || name.contains("effect")) {
                add(scene, step(tick, 12, Op.EFFECT, lastBounds, null, name, lastVector));
            } else if (name.toLowerCase(Locale.ROOT).contains("keyframe")) {
                add(scene, step(tick, 0, Op.KEYFRAME, null, null, name, null));
            }
        }
        int end = tick;
        for (Step step : scene.steps) end = Math.max(end, step.tick + Math.max(0, step.duration));
        scene.totalTicks = Math.max(20, end);
    }

    private static Step lastStep(Scene scene, Op op, int tick) {
        for (int i = scene.steps.size() - 1; i >= 0; i--) {
            Step step = scene.steps.get(i);
            if (step.tick != tick) break;
            if (step.op == op) return step;
        }
        return null;
    }

    private static Step step(int tick, int duration, Op op, Bounds bounds, String text,
                             String hint, double[] vector) {
        Step step = new Step();
        step.tick = Math.max(0, tick);
        step.duration = Math.max(0, duration);
        step.op = op;
        step.bounds = bounds;
        step.text = text;
        step.hint = hint;
        if (vector != null) {
            step.x = vector[0];
            step.y = vector[1];
            step.z = vector[2];
        }
        return step;
    }

    private static void add(Scene scene, Step step) {
        if (scene.steps.size() < MAX_STEPS) scene.steps.add(step);
    }

    private static Bounds bounds(List<Integer> v) {
        return new Bounds(Math.min(v.get(0), v.get(3)), Math.min(v.get(1), v.get(4)),
                Math.min(v.get(2), v.get(5)), Math.max(v.get(0), v.get(3)),
                Math.max(v.get(1), v.get(4)), Math.max(v.get(2), v.get(5)));
    }

    private static MethodNode findMethod(ClassNode node, String name, String desc) {
        if (node == null || node.methods == null) return null;
        for (MethodNode method : node.methods) {
            if (safe(name).equals(method.name) && safe(desc).equals(method.desc)) return method;
        }
        return null;
    }

    private static String nearestString(AbstractInsnNode point, int limit) {
        List<String> values = nearestStrings(point, limit, 1);
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    private static List<String> nearestStrings(AbstractInsnNode point, int limit, int wanted) {
        ArrayList<String> reverse = new ArrayList<>();
        for (AbstractInsnNode p = point == null ? null : point.getPrevious(); p != null && limit-- > 0;
             p = p.getPrevious()) {
            if (p instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                reverse.add(value);
                if (reverse.size() >= wanted) break;
            }
        }
        java.util.Collections.reverse(reverse);
        return reverse;
    }

    private static Integer nearestInt(AbstractInsnNode point, int limit) {
        List<Integer> values = nearestInts(point, limit, 1);
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    private static List<Integer> nearestInts(AbstractInsnNode point, int limit, int wanted) {
        ArrayList<Integer> reverse = new ArrayList<>();
        for (AbstractInsnNode p = point == null ? null : point.getPrevious(); p != null && limit-- > 0;
             p = p.getPrevious()) {
            Integer value = integerConstant(p);
            if (value != null) {
                reverse.add(value);
                if (reverse.size() >= wanted) break;
            }
        }
        java.util.Collections.reverse(reverse);
        return reverse;
    }

    private static List<Double> nearestNumbers(AbstractInsnNode point, int limit, int wanted) {
        ArrayList<Double> reverse = new ArrayList<>();
        for (AbstractInsnNode p = point == null ? null : point.getPrevious(); p != null && limit-- > 0;
             p = p.getPrevious()) {
            Double value = numberConstant(p);
            if (value != null) {
                reverse.add(value);
                if (reverse.size() >= wanted) break;
            }
        }
        java.util.Collections.reverse(reverse);
        return reverse;
    }

    private static Float nearestFloat(AbstractInsnNode point, int limit) {
        for (AbstractInsnNode p = point == null ? null : point.getPrevious(); p != null && limit-- > 0;
             p = p.getPrevious()) {
            Double value = numberConstant(p);
            if (value != null) return value.floatValue();
        }
        return null;
    }

    private static Integer integerConstant(AbstractInsnNode n) {
        if (n == null) return null;
        int opcode = n.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
        if (n instanceof org.objectweb.asm.tree.IntInsnNode value) return value.operand;
        if (n instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) return value;
        return null;
    }

    private static Double numberConstant(AbstractInsnNode n) {
        Integer integer = integerConstant(n);
        if (integer != null) return integer.doubleValue();
        if (n == null) return null;
        return switch (n.getOpcode()) {
            case Opcodes.FCONST_0 -> 0.0;
            case Opcodes.FCONST_1 -> 1.0;
            case Opcodes.FCONST_2 -> 2.0;
            case Opcodes.DCONST_0 -> 0.0;
            case Opcodes.DCONST_1 -> 1.0;
            default -> n instanceof LdcInsnNode ldc && ldc.cst instanceof Number value
                    ? value.doubleValue() : null;
        };
    }

    private static boolean simplePath(String value) {
        return value != null && !value.isBlank() && value.length() <= 160
                && value.matches("[a-z0-9_.-]+(?::[a-z0-9_./-]+)?");
    }

    private static boolean simpleScenePath(String value) {
        return value != null && !value.isBlank() && value.length() <= 180
                && value.matches("[a-z0-9_./-]+");
    }

    private static boolean usableNamespace(String value) {
        return value != null && !value.isBlank() && !"?".equals(value)
                && value.matches("[a-z0-9_.-]+");
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
