package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Archive-wide correlation for moving-structure controls whose normal input loop and HUD live on
 * the modded client.
 *
 * <p>These controls are split across several classes: a moving interaction starts ownership on a
 * server entity, a client controller samples ordinary movement keys and sends a packet, the
 * entity consumes the same indexed collection server-side, and a HUD/overlay reads that controller
 * while clientbound state updates keep it current. Per-class scanners can see every individual
 * piece but cannot tell that they form one control mode. This pass joins those already-declarative
 * bytecode surfaces without loading or executing any mod class.</p>
 */
public final class MovingControlBytecodeScanner {

    public static final int SCHEMA = 1;
    private static final int MAX_LOCAL_DEPTH = 4;

    /** A fully correlated client-control surface for one concrete server entity implementation. */
    public record ControlSurface(String entityClass,
                                 String controlMethod,
                                 String interactionClass,
                                 String clientControllerClass,
                                 String inputPacketClass,
                                 String hudClass,
                                 String hudUpdatePacketClass,
                                 List<String> inputFields,
                                 int confidence) {}

    private record ControllerEvidence(String controllerClass, String inputPacketClass) {}

    private MovingControlBytecodeScanner() {}

    public static List<ControlSurface> discover(Collection<ModScanner.Profile> profiles) {
        if (profiles == null || profiles.isEmpty()) return List.of();
        LinkedHashMap<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors = new LinkedHashMap<>();
        LinkedHashMap<String, ClientSurfaceScanner.ClassSurface> clients = new LinkedHashMap<>();
        for (ModScanner.Profile profile : profiles) collect(profile, behaviors, clients);
        if (behaviors.isEmpty()) return List.of();

        ArrayList<ControlSurface> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (BehaviorBytecodeScanner.BehaviorSurface entity : behaviors.values()) {
            if (entity == null || entity.clazz == null) continue;
            for (BehaviorBytecodeScanner.MethodBehavior control : entity.methods) {
                if (!controlShape(control.descriptor)) continue;
                LinkedHashSet<String> hierarchy = hierarchy(entity.clazz, behaviors);
                ControllerEvidence controllerEvidence = findController(
                        hierarchy, control.method + control.descriptor, behaviors);
                if (controllerEvidence == null) continue;
                BehaviorBytecodeScanner.BehaviorSurface controller =
                        behaviors.get(normalize(controllerEvidence.controllerClass));
                if (controller == null) continue;

                List<String> inputFields = inputFields(controller, behaviors);
                String hud = findHud(controllerEvidence.controllerClass, hierarchy, behaviors, clients);
                String hudUpdate = firstPacket(control, BehaviorBytecodeScanner.Effect.SEND_CLIENT_PACKET);
                String interaction = findInteractionClass(hierarchy, controllerEvidence.controllerClass, behaviors);

                int confidence = 82;
                if (!inputFields.isEmpty()) confidence += 6;
                if (hud != null) confidence += 6;
                if (hudUpdate != null) confidence += 6;
                confidence = Math.min(100, confidence);

                String methodKey = control.method + control.descriptor;
                String token = normalize(entity.clazz) + '\n' + methodKey + '\n'
                        + normalize(controllerEvidence.controllerClass) + '\n'
                        + normalize(controllerEvidence.inputPacketClass);
                if (!seen.add(token)) continue;
                result.add(new ControlSurface(normalize(entity.clazz), methodKey,
                        normalize(interaction), normalize(controllerEvidence.controllerClass),
                        normalize(controllerEvidence.inputPacketClass), normalize(hud), normalize(hudUpdate),
                        List.copyOf(inputFields), confidence));
            }
        }
        result.sort(Comparator.comparing(ControlSurface::entityClass)
                .thenComparing(ControlSurface::controlMethod));
        return List.copyOf(result);
    }

    /** Most-specific-first lookup for a live entity hierarchy supplied by the runtime. */
    public static ControlSurface forTarget(Collection<ModScanner.Profile> profiles,
                                           Collection<String> runtimeTypes) {
        if (profiles == null || runtimeTypes == null || runtimeTypes.isEmpty()) return null;
        List<ControlSurface> surfaces = discover(profiles);
        for (String runtimeType : runtimeTypes) {
            String wanted = normalize(runtimeType);
            ControlSurface best = surfaces.stream()
                    .filter(surface -> wanted.equals(normalize(surface.entityClass())))
                    .max(Comparator.comparingInt(ControlSurface::confidence))
                    .orElse(null);
            if (best != null) return best;
        }
        return null;
    }

    private static void collect(ModScanner.Profile profile,
                                Map<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors,
                                Map<String, ClientSurfaceScanner.ClassSurface> clients) {
        if (profile == null) return;
        if (profile.behaviors != null) {
            for (BehaviorBytecodeScanner.BehaviorSurface surface : profile.behaviors) {
                if (surface != null && surface.clazz != null) {
                    behaviors.putIfAbsent(normalize(surface.clazz), surface);
                }
            }
        }
        if (profile.clientSurfaces != null) {
            for (ClientSurfaceScanner.ClassSurface surface : profile.clientSurfaces) {
                if (surface != null && surface.clazz != null) {
                    clients.putIfAbsent(normalize(surface.clazz), surface);
                }
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collect(child, behaviors, clients);
        }
    }

    private static boolean controlShape(String descriptor) {
        if (descriptor == null) return false;
        try {
            Type[] args = Type.getArgumentTypes(descriptor);
            Type returned = Type.getReturnType(descriptor);
            if (args.length != 3 || !object(args[0], "net/minecraft/core/BlockPos")
                    || !object(args[1], "java/util/Collection")
                    || !object(args[2], "net/minecraft/world/entity/player/Player")) return false;
            return returned.getSort() == Type.BOOLEAN || returned.getSort() == Type.VOID
                    || object(returned, "java/lang/Boolean");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean startShape(String descriptor) {
        if (descriptor == null) return false;
        try {
            Type[] args = Type.getArgumentTypes(descriptor);
            Type returned = Type.getReturnType(descriptor);
            return args.length == 2 && object(args[0], "net/minecraft/core/BlockPos")
                    && object(args[1], "net/minecraft/world/entity/player/Player")
                    && (returned.getSort() == Type.BOOLEAN || object(returned, "java/lang/Boolean"));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * Finds the client controller by following its concrete serverbound packet back through the
     * packet/server-handler call graph until that graph invokes the candidate entity's exact
     * control method. This edge survives versions where the moving-interaction implementation is
     * intentionally omitted from the general behavior inventory.
     */
    private static ControllerEvidence findController(
            Set<String> entityHierarchy, String controlMethod,
            Map<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors) {
        for (BehaviorBytecodeScanner.BehaviorSurface surface : behaviors.values()) {
            if (surface == null || !looksLikeInputController(surface)) continue;
            for (BehaviorBytecodeScanner.MethodBehavior method : surface.methods) {
                for (BehaviorBytecodeScanner.CallEdge packet : method.calls) {
                    if (packet == null || packet.effect != BehaviorBytecodeScanner.Effect.SEND_SERVER_PACKET
                            || packet.target == null) continue;
                    String packetClass = normalize(packet.target);
                    BehaviorBytecodeScanner.BehaviorSurface packetSurface = behaviors.get(packetClass);
                    if (packetSurface == null) continue;
                    if (reachesControl(packetSurface, entityHierarchy, controlMethod, behaviors)) {
                        return new ControllerEvidence(surface.clazz, packetClass);
                    }
                }
            }
        }
        return null;
    }

    /** The sender must itself observe client input, not merely inherit SEND_SERVER_PACKET through
     * a helper call. This rejects packet wrappers/forwarders that happen to reach the same input
     * packet while retaining remapping independence: KeyMapping/InputConstants types remain in
     * descriptors even when the surrounding mod member names change. */
    private static boolean looksLikeInputController(BehaviorBytecodeScanner.BehaviorSurface surface) {
        if (surface == null) return false;
        for (BehaviorBytecodeScanner.MethodBehavior method : surface.methods) {
            if (firstPacket(method, BehaviorBytecodeScanner.Effect.SEND_SERVER_PACKET) == null) continue;
            for (BehaviorBytecodeScanner.InvocationEdge call : method.invocations) {
                if (call == null) continue;
                String owner = normalize(call.owner);
                String desc = call.descriptor == null ? "" : call.descriptor;
                if (owner.startsWith("net/minecraft/client/")
                        || owner.startsWith("com/mojang/blaze3d/platform/InputConstants")
                        || desc.contains("Lnet/minecraft/client/KeyMapping;")) return true;
            }
        }
        return false;
    }

    private static boolean reachesControl(
            BehaviorBytecodeScanner.BehaviorSurface packet,
            Set<String> entityHierarchy, String controlMethod,
            Map<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors) {
        ArrayList<MethodRef> queue = new ArrayList<>();
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        for (BehaviorBytecodeScanner.MethodBehavior method : packet.methods) {
            queue.add(new MethodRef(packet.clazz, method.method + method.descriptor, 0));
        }
        for (int cursor = 0; cursor < queue.size(); cursor++) {
            MethodRef ref = queue.get(cursor);
            if (ref.depth > MAX_LOCAL_DEPTH || !visited.add(ref.owner + '#' + ref.method)) continue;
            BehaviorBytecodeScanner.BehaviorSurface surface = behaviors.get(normalize(ref.owner));
            BehaviorBytecodeScanner.MethodBehavior method = method(surface, ref.method);
            if (method == null) continue;
            if (sameOrStateLinkedControl(surface, method, entityHierarchy, controlMethod)) return true;
            for (BehaviorBytecodeScanner.InvocationEdge call : method.invocations) {
                if (call == null || call.owner == null || call.method == null || call.descriptor == null) continue;
                String owner = normalize(call.owner);
                if (entityHierarchy.contains(owner)
                        && (call.method + call.descriptor).equals(controlMethod)) return true;
                BehaviorBytecodeScanner.BehaviorSurface next = behaviors.get(owner);
                if (next != null && method(next, call.method + call.descriptor) != null) {
                    queue.add(new MethodRef(owner, call.method + call.descriptor, ref.depth + 1));
                }
            }
        }
        return false;
    }

    /**
     * Network handlers often only update a persistent input table; a server tick on the same
     * bridge class later drains that table into the entity control method. Correlate those two
     * methods only when their bytecode touches at least one identical field, preventing an
     * unrelated periodic method on the same class from being joined to the packet path.
     */
    private static boolean sameOrStateLinkedControl(
            BehaviorBytecodeScanner.BehaviorSurface surface,
            BehaviorBytecodeScanner.MethodBehavior packetEntry,
            Set<String> entityHierarchy, String controlMethod) {
        if (surface == null || packetEntry == null) return false;
        for (BehaviorBytecodeScanner.MethodBehavior candidate : surface.methods) {
            if (!invokesControl(candidate, entityHierarchy, controlMethod)) continue;
            if (candidate == packetEntry || sharesState(packetEntry, candidate)) return true;
        }
        return false;
    }

    private static boolean invokesControl(BehaviorBytecodeScanner.MethodBehavior method,
                                          Set<String> entityHierarchy, String controlMethod) {
        if (method == null) return false;
        for (BehaviorBytecodeScanner.InvocationEdge call : method.invocations) {
            if (call != null && entityHierarchy.contains(normalize(call.owner))
                    && (call.method + call.descriptor).equals(controlMethod)) return true;
        }
        return false;
    }

    private static boolean sharesState(BehaviorBytecodeScanner.MethodBehavior a,
                                       BehaviorBytecodeScanner.MethodBehavior b) {
        if (a == null || b == null) return false;
        LinkedHashSet<String> left = new LinkedHashSet<>(a.fieldReads);
        left.addAll(a.fieldWrites);
        if (left.isEmpty()) return false;
        for (String field : b.fieldReads) if (left.contains(field)) return true;
        for (String field : b.fieldWrites) if (left.contains(field)) return true;
        return false;
    }

    private record MethodRef(String owner, String method, int depth) {}

    /** Optional lifecycle evidence: same method starts server ownership and invokes the discovered
     * client controller with (entity, BlockPos). It improves diagnostics but is not required for
     * the stronger packet -> server-control correlation above. */
    private static String findInteractionClass(
            Set<String> entityHierarchy, String controllerClass,
            Map<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors) {
        String controller = normalize(controllerClass);
        for (BehaviorBytecodeScanner.BehaviorSurface surface : behaviors.values()) {
            if (surface == null) continue;
            for (BehaviorBytecodeScanner.MethodBehavior method : surface.methods) {
                boolean starts = method.invocations.stream().anyMatch(call -> call != null
                        && entityHierarchy.contains(normalize(call.owner)) && startShape(call.descriptor));
                if (!starts) continue;
                for (BehaviorBytecodeScanner.InvocationEdge call : method.invocations) {
                    if (call == null || call.opcode != Opcodes.INVOKESTATIC
                            || !controller.equals(normalize(call.owner)) || call.descriptor == null) continue;
                    if (clientStartShape(call.descriptor, entityHierarchy)) return surface.clazz;
                }
            }
        }
        return null;
    }

    private static boolean clientStartShape(String descriptor, Set<String> entityHierarchy) {
        try {
            Type[] args = Type.getArgumentTypes(descriptor);
            if (args.length != 2 || Type.getReturnType(descriptor).getSort() != Type.VOID
                    || !object(args[1], "net/minecraft/core/BlockPos")
                    || args[0].getSort() != Type.OBJECT) return false;
            return entityHierarchy.contains(normalize(args[0].getInternalName()));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String findHud(String controllerClass, Set<String> entityHierarchy,
                                  Map<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors,
                                  Map<String, ClientSurfaceScanner.ClassSurface> clients) {
        String controller = normalize(controllerClass);
        for (Map.Entry<String, ClientSurfaceScanner.ClassSurface> entry : clients.entrySet()) {
            ClientSurfaceScanner.ClassSurface client = entry.getValue();
            if (client == null || !(client.kinds.contains(ClientSurfaceScanner.Kind.HUD)
                    || client.kinds.contains(ClientSurfaceScanner.Kind.OVERLAY))) continue;
            BehaviorBytecodeScanner.BehaviorSurface behavior = behaviors.get(entry.getKey());
            if (behavior == null) continue;
            for (BehaviorBytecodeScanner.MethodBehavior method : behavior.methods) {
                for (BehaviorBytecodeScanner.InvocationEdge call : method.invocations) {
                    if (call == null || !controller.equals(normalize(call.owner)) || call.descriptor == null) continue;
                    try {
                        Type returned = Type.getReturnType(call.descriptor);
                        if (returned.getSort() == Type.OBJECT
                                && entityHierarchy.contains(normalize(returned.getInternalName()))) {
                            return behavior.clazz;
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static String firstPacket(BehaviorBytecodeScanner.BehaviorSurface surface,
                                      BehaviorBytecodeScanner.Effect effect) {
        if (surface == null) return null;
        for (BehaviorBytecodeScanner.MethodBehavior method : surface.methods) {
            String packet = firstPacket(method, effect);
            if (packet != null) return packet;
        }
        return null;
    }

    private static String firstPacket(BehaviorBytecodeScanner.MethodBehavior method,
                                      BehaviorBytecodeScanner.Effect effect) {
        if (method == null) return null;
        for (BehaviorBytecodeScanner.CallEdge call : method.calls) {
            if (call != null && call.effect == effect && call.target != null && !call.target.isBlank()) {
                return call.target;
            }
        }
        return null;
    }

    /** Ordered vanilla key fields reached from the client controller's input loop. */
    private static List<String> inputFields(
            BehaviorBytecodeScanner.BehaviorSurface controller,
            Map<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (controller == null) return List.of();
        for (BehaviorBytecodeScanner.MethodBehavior method : controller.methods) {
            if (firstPacket(method, BehaviorBytecodeScanner.Effect.SEND_SERVER_PACKET) == null) continue;
            collectInputFields(controller.clazz, method, behaviors, fields, new LinkedHashSet<>(), 0);
        }
        return List.copyOf(fields);
    }

    private static void collectInputFields(
            String owner, BehaviorBytecodeScanner.MethodBehavior method,
            Map<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors,
            Set<String> output, Set<String> visiting, int depth) {
        if (method == null || depth > MAX_LOCAL_DEPTH) return;
        String token = normalize(owner) + '#' + method.method + method.descriptor;
        if (!visiting.add(token)) return;
        for (String field : method.fieldReads) {
            if (field == null) continue;
            String normalized = field.replace('.', '/');
            if (normalized.startsWith("net/minecraft/client/Options/")
                    && normalized.contains("Lnet/minecraft/client/KeyMapping;")) {
                output.add(fieldName(field));
            }
        }
        for (BehaviorBytecodeScanner.InvocationEdge call : method.invocations) {
            if (call == null || call.owner == null || call.method == null || call.descriptor == null) continue;
            BehaviorBytecodeScanner.BehaviorSurface called = behaviors.get(normalize(call.owner));
            if (called == null) continue;
            BehaviorBytecodeScanner.MethodBehavior next = method(called, call.method + call.descriptor);
            if (next != null) collectInputFields(called.clazz, next, behaviors, output, visiting, depth + 1);
        }
        visiting.remove(token);
    }

    private static String fieldName(String field) {
        int colon = field.indexOf(':');
        String left = colon >= 0 ? field.substring(0, colon) : field;
        int dot = Math.max(left.lastIndexOf('.'), left.lastIndexOf('/'));
        return dot >= 0 ? left.substring(dot + 1) : left;
    }

    private static BehaviorBytecodeScanner.MethodBehavior method(
            BehaviorBytecodeScanner.BehaviorSurface surface, String key) {
        if (surface == null || key == null) return null;
        for (BehaviorBytecodeScanner.MethodBehavior method : surface.methods) {
            if ((method.method + method.descriptor).equals(key)) return method;
        }
        return null;
    }

    private static LinkedHashSet<String> hierarchy(
            String clazz, Map<String, BehaviorBytecodeScanner.BehaviorSurface> behaviors) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String current = normalize(clazz);
        while (!current.isBlank() && result.add(current)) {
            BehaviorBytecodeScanner.BehaviorSurface surface = behaviors.get(current);
            if (surface == null || surface.superClass == null) break;
            current = normalize(surface.superClass);
        }
        return result;
    }

    private static boolean object(Type type, String internalName) {
        return type != null && type.getSort() == Type.OBJECT
                && normalize(internalName).equals(normalize(type.getInternalName()));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('.', '/');
    }
}
