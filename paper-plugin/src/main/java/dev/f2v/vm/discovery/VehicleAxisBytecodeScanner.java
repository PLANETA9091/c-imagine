package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Correlates the common analog passenger-control transport where a modded client copies vanilla
 * rider strafe/forward axes into a two-float C2S packet and a registered packet-type handler applies
 * that packet to the authoritative vehicle.
 *
 * <p>The runtime consumes only fully joined surfaces. Discovery must prove the exact vanilla input
 * fields and constructor order, the packet-type object registered by the packet class, and a unique
 * two-float mutation reached by that handler. The bridge can therefore invoke the mod's own handler
 * instead of bypassing packet-side clamping, validation, or other compound behavior.</p>
 */
public final class VehicleAxisBytecodeScanner {

    public static final int SCHEMA = 1;
    private static final String AXIS_CONSTRUCTOR = "(FF)V";
    private static final String STRAFE_FIELD = "net/minecraft/world/entity/player/Player.xxa:F";
    private static final String FORWARD_FIELD = "net/minecraft/world/entity/player/Player.zza:F";

    public record Surface(String packetClass,
                          String senderClass,
                          String senderMethod,
                          String handlerOwner,
                          String handlerMethod,
                          String handlerDescriptor,
                          String handlerFieldOwner,
                          String handlerFieldName,
                          String handlerFieldDescriptor,
                          String targetOwner,
                          String targetMethod,
                          String targetDescriptor,
                          int confidence) {}

    private VehicleAxisBytecodeScanner() {}

    public static List<Surface> discover(Map<String, ClassNode> nodes,
                                         List<BehaviorBytecodeScanner.BehaviorSurface> behaviors,
                                         List<ServerMutationScanner.Recipe> mutations) {
        if (nodes == null || nodes.isEmpty() || behaviors == null || mutations == null) return List.of();

        LinkedHashMap<String, Sender> senders = new LinkedHashMap<>();
        for (BehaviorBytecodeScanner.BehaviorSurface surface : behaviors) {
            if (surface == null || surface.clazz == null || surface.methods == null) continue;
            ClassNode owner = nodes.get(normalize(surface.clazz));
            if (owner == null) continue;
            for (BehaviorBytecodeScanner.MethodBehavior method : surface.methods) {
                if (method == null || method.method == null || method.descriptor == null) continue;
                MethodNode bytecode = findMethod(owner, method.method, method.descriptor);
                if (bytecode == null) continue;
                for (BehaviorBytecodeScanner.CallEdge call : method.calls) {
                    if (call == null || call.effect != BehaviorBytecodeScanner.Effect.SEND_SERVER_PACKET
                            || call.target == null || !AXIS_CONSTRUCTOR.equals(call.constructorDesc)) continue;
                    String packet = normalize(call.target);
                    if (!nodes.containsKey(packet) || !copiesVanillaAxes(owner, bytecode, packet)) continue;
                    String key = packet + '\n' + owner.name + '#' + method.method + method.descriptor;
                    senders.putIfAbsent(key, new Sender(packet, owner.name,
                            method.method + method.descriptor));
                }
            }
        }
        if (senders.isEmpty()) return List.of();

        ArrayList<Surface> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Sender sender : senders.values()) {
            ClassNode packet = nodes.get(sender.packetClass);
            if (packet == null || findMethod(packet, "<init>", AXIS_CONSTRUCTOR) == null) continue;
            for (ServerMutationScanner.Recipe recipe : mutations) {
                HandlerShape handler = handlerShape(recipe, sender.packetClass, nodes);
                if (handler == null) continue;
                StaticHandlerField registered = registeredHandlerField(packet, handler.owner, nodes);
                if (registered == null) continue;

                LinkedHashMap<String, ServerMutationScanner.Invocation> targetCalls = new LinkedHashMap<>();
                for (ServerMutationScanner.Invocation call : recipe.calls) {
                    if (call == null || !AXIS_CONSTRUCTOR.equals(call.desc)
                            || call.owner == null || call.name == null) continue;
                    String targetOwner = normalize(call.owner);
                    if (targetOwner.equals(sender.packetClass) || targetOwner.equals(handler.owner)) continue;
                    String key = targetOwner + '.' + call.name + call.desc;
                    targetCalls.putIfAbsent(key, call);
                }
                if (targetCalls.size() != 1) continue;
                ServerMutationScanner.Invocation target = targetCalls.values().iterator().next();
                String id = sender.packetClass + '\n' + normalize(target.owner) + '.' + target.name + target.desc;
                if (!seen.add(id)) continue;
                out.add(new Surface(sender.packetClass, sender.senderClass, sender.senderMethod,
                        handler.owner, handler.method, handler.descriptor,
                        registered.owner, registered.name, registered.descriptor,
                        normalize(target.owner), target.name, target.desc, 100));
            }
        }
        out.sort(Comparator.comparing(Surface::targetOwner)
                .thenComparing(Surface::packetClass)
                .thenComparing(Surface::targetMethod));
        return List.copyOf(out);
    }

    /** Exact target-hierarchy lookup used by the server runtime. Duplicate copies of the same
     * discovered surface collapse, while genuinely ambiguous control transports remain visible. */
    public static List<Surface> forTarget(Collection<ModScanner.Profile> profiles,
                                          Collection<String> runtimeTypes) {
        if (profiles == null || runtimeTypes == null || runtimeTypes.isEmpty()) return List.of();
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String type : runtimeTypes) wanted.add(normalize(type));
        LinkedHashMap<String, Surface> out = new LinkedHashMap<>();
        for (ModScanner.Profile profile : profiles) collect(profile, wanted, out);
        return List.copyOf(out.values());
    }

    private static void collect(ModScanner.Profile profile, Set<String> wanted,
                                Map<String, Surface> out) {
        if (profile == null) return;
        if (profile.vehicleAxisControls != null) {
            for (Surface surface : profile.vehicleAxisControls) {
                if (surface == null || !wanted.contains(normalize(surface.targetOwner))) continue;
                String key = normalize(surface.packetClass) + '\n' + normalize(surface.targetOwner)
                        + '.' + surface.targetMethod + surface.targetDescriptor;
                out.putIfAbsent(key, surface);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collect(child, wanted, out);
        }
    }

    private static boolean copiesVanillaAxes(ClassNode owner, MethodNode method, String packetClass) {
        ArrayList<MethodInsnNode> constructors = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && "<init>".equals(call.name)
                    && packetClass.equals(normalize(call.owner))
                    && AXIS_CONSTRUCTOR.equals(call.desc)) {
                constructors.add(call);
            }
        }
        if (constructors.size() != 1) return false;
        MethodInsnNode constructor = constructors.get(0);
        try {
            Frame<SourceValue>[] frames = new Analyzer<>(new SourceInterpreter()).analyze(owner.name, method);
            int index = instructionIndex(method, constructor);
            if (index < 0 || index >= frames.length || frames[index] == null) return false;
            Frame<SourceValue> frame = frames[index];
            Type[] args = Type.getArgumentTypes(constructor.desc);
            if (args.length != 2 || frame.getStackSize() < 2) return false;
            int first = frame.getStackSize() - args.length;
            String lateral = directField(frame.getStack(first));
            String forward = directField(frame.getStack(first + 1));
            return STRAFE_FIELD.equals(lateral) && FORWARD_FIELD.equals(forward);
        } catch (AnalyzerException | RuntimeException ignored) {
            return false;
        }
    }

    private static String directField(SourceValue source) {
        if (source == null || source.insns == null || source.insns.size() != 1) return null;
        AbstractInsnNode producer = source.insns.iterator().next();
        if (!(producer instanceof FieldInsnNode field)
                || (field.getOpcode() != Opcodes.GETFIELD && field.getOpcode() != Opcodes.GETSTATIC)) {
            return null;
        }
        return normalize(field.owner) + '.' + field.name + ':' + field.desc;
    }

    private static HandlerShape handlerShape(ServerMutationScanner.Recipe recipe, String packetClass,
                                             Map<String, ClassNode> nodes) {
        if (recipe == null || recipe.packetClass == null || recipe.handlerMethod == null) return null;
        int descriptorAt = recipe.handlerMethod.indexOf('(');
        if (descriptorAt <= 0) return null;
        String method = recipe.handlerMethod.substring(0, descriptorAt);
        String descriptor = recipe.handlerMethod.substring(descriptorAt);
        try {
            Type[] args = Type.getArgumentTypes(descriptor);
            Type returned = Type.getReturnType(descriptor);
            if (args.length != 1 || args[0].getSort() != Type.OBJECT
                    || !packetClass.equals(normalize(args[0].getInternalName()))) return null;
            if (returned.getSort() != Type.OBJECT
                    || !"java/util/function/Consumer".equals(normalize(returned.getInternalName()))) return null;
            String owner = normalize(recipe.packetClass);
            MethodNode implementation = findMethod(nodes.get(owner), method, descriptor);
            if (implementation == null || (implementation.access & Opcodes.ACC_PUBLIC) == 0
                    || (implementation.access & Opcodes.ACC_STATIC) != 0) return null;
            return new HandlerShape(owner, method, descriptor);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** Finds the exact static field whose class initializer constructs the discovered handler
     * implementation. The field's declared type may be an interface/base packet type. */
    private static StaticHandlerField registeredHandlerField(ClassNode packet, String handlerOwner,
                                                             Map<String, ClassNode> nodes) {
        MethodNode clinit = findMethod(packet, "<clinit>", "()V");
        if (clinit == null) return null;
        ArrayList<StaticHandlerField> matches = new ArrayList<>();
        for (AbstractInsnNode instruction = clinit.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (!(instruction instanceof TypeInsnNode allocation)
                    || allocation.getOpcode() != Opcodes.NEW
                    || !handlerOwner.equals(normalize(allocation.desc))) continue;
            int remaining = 16;
            for (AbstractInsnNode cursor = instruction.getNext(); cursor != null && remaining-- > 0;
                 cursor = cursor.getNext()) {
                if (cursor.getOpcode() < 0) continue;
                if (cursor instanceof TypeInsnNode other && other.getOpcode() == Opcodes.NEW) break;
                if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
                        && packet.name.equals(field.owner) && objectDescriptor(field.desc)) {
                    matches.add(new StaticHandlerField(normalize(field.owner), field.name, field.desc));
                    break;
                }
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static boolean objectDescriptor(String descriptor) {
        if (descriptor == null) return false;
        try {
            return Type.getType(descriptor).getSort() == Type.OBJECT;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static int instructionIndex(MethodNode method, AbstractInsnNode target) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction == target) return index;
            index++;
        }
        return -1;
    }

    private static MethodNode findMethod(ClassNode node, String name, String descriptor) {
        if (node == null || node.methods == null) return null;
        for (MethodNode method : node.methods) {
            if (method != null && name.equals(method.name) && descriptor.equals(method.desc)) return method;
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('.', '/');
    }

    private record Sender(String packetClass, String senderClass, String senderMethod) {}
    private record HandlerShape(String owner, String method, String descriptor) {}
    private record StaticHandlerField(String owner, String name, String descriptor) {}
}
