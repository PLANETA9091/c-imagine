package dev.f2v.vm.discovery;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers entity-owned client-only screens without loading client classes on the server.
 *
 * <p>A route is emitted only when bytecode proves the complete chain:</p>
 * <ol>
 *     <li>an entity interaction constructs and sends a concrete clientbound packet,</li>
 *     <li>that packet resolves a {@code Screen} from the entity and passes it to Minecraft, and</li>
 *     <li>a concrete entity subclass implements that resolver by constructing a concrete screen.</li>
 * </ol>
 *
 * <p>This is intentionally archive-structural rather than namespace based. It covers mods that
 * open configuration screens with a custom client packet instead of a vanilla MenuType packet.</p>
 */
public final class EntityScreenBytecodeScanner {

    public static final int SCHEMA = 1;

    public static final class Route {
        public String entityClass;
        public String interactionOwner;
        public String clientPacketClass;
        public String resolverOwner;
        public String resolverMethod;
        public String resolverDescriptor;
        public String screenClass;
        public int confidence;

        @Override
        public String toString() {
            return entityClass + " -> " + screenClass + " via " + clientPacketClass;
        }
    }

    /**
     * How a clientbound packet reaches a concrete {@code Screen}.
     *
     * <p>Two proven shapes exist in the wild:</p>
     * <ul>
     *     <li><b>resolver</b> &mdash; the packet handler calls a method that <i>returns</i> a
     *     {@code Screen} (typically on the entity) and hands it to Minecraft. {@code directScreen}
     *     is {@code null} and the concrete screen is read from the entity override.</li>
     *     <li><b>direct</b> &mdash; the packet handler itself does
     *     {@code Minecraft.getInstance().setScreen(new SomeScreen(...))}. There is no resolver
     *     method at all, so {@code directScreen} carries the constructed screen class.
     *     Measured in {@code recruits-1.20.1-1.15.2.jar}:
     *     {@code MessageToClientOpenNobleTradeScreen} does exactly this with
     *     {@code com/talhanation/recruits/client/gui/NobleTradeScreen}.</li>
     * </ul>
     */
    private record Resolver(String owner, String method, String descriptor, String directScreen) {}

    private EntityScreenBytecodeScanner() {}

    public static List<Route> scan(Map<String, ClassNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return List.of();

        Map<String, Resolver> packetResolvers = packetResolvers(nodes);
        if (packetResolvers.isEmpty()) return List.of();

        ArrayList<Route> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ClassNode interactionOwner : nodes.values()) {
            if (interactionOwner == null || interactionOwner.name == null
                    || interactionOwner.methods == null) continue;
            for (MethodNode method : interactionOwner.methods) {
                if (!interactionShape(method)) continue;
                for (Sender sender : sendersReachableFrom(interactionOwner, method, nodes)) {
                    for (String packet : clientPacketsSentBy(sender.method(), packetResolvers.keySet())) {
                        Resolver resolver = packetResolvers.get(packet);
                        if (resolver == null) continue;
                        if (resolver.directScreen() != null) {
                            // The packet handler builds the screen itself, so there is no entity
                            // override to read. Attribute the screen to the class that actually
                            // sends the packet (and its subclasses) rather than to the whole
                            // interaction hierarchy - recruits sends the noble trade screen from
                            // VillagerNobleEntity.openTradeGUI while mobInteract lives on
                            // AbstractRecruitEntity, which every recruit type extends.
                            for (ClassNode entity : nodes.values()) {
                                if (entity == null || entity.name == null
                                        || !isSubclass(entity.name, sender.owner(), nodes)
                                        || !looksLikeEntity(entity, nodes)) continue;
                                if (!seen.add(entity.name + '\n' + resolver.directScreen() + '\n' + packet)) {
                                    continue;
                                }
                                out.add(route(entity.name, interactionOwner.name, packet,
                                        sender.owner(), sender.method().name, sender.method().desc,
                                        resolver.directScreen(), 95));
                            }
                            continue;
                        }
                        for (ClassNode entity : nodes.values()) {
                            if (entity == null || entity.name == null
                                    || !isSubclass(entity.name, resolver.owner(), nodes)) continue;
                            String screen = resolvedScreen(entity, resolver, nodes);
                            if (screen == null) continue;
                            if (!seen.add(entity.name + '\n' + screen + '\n' + packet)) continue;
                            out.add(route(entity.name, interactionOwner.name, packet,
                                    resolver.owner(), resolver.method(), resolver.descriptor(),
                                    screen, 99));
                        }
                    }
                }
            }
        }
        out.sort(Comparator.comparing((Route route) -> route.entityClass)
                .thenComparing(route -> route.screenClass)
                .thenComparing(route -> route.clientPacketClass));
        return List.copyOf(out);
    }

    private record Sender(String owner, MethodNode method) {}

    private static Route route(String entityClass, String interactionOwner, String packet,
                               String resolverOwner, String resolverMethod,
                               String resolverDescriptor, String screen, int confidence) {
        Route route = new Route();
        route.entityClass = entityClass;
        route.interactionOwner = interactionOwner;
        route.clientPacketClass = packet;
        route.resolverOwner = resolverOwner;
        route.resolverMethod = resolverMethod;
        route.resolverDescriptor = resolverDescriptor;
        route.screenClass = screen;
        route.confidence = confidence;
        return route;
    }

    /**
     * The interaction method itself plus one level of intra-hierarchy call indirection.
     *
     * <p>Mods rarely inline the packet send into {@code mobInteract}. Measured in
     * {@code recruits-1.20.1-1.15.2.jar}: {@code AbstractRecruitEntity.m_6071_} calls
     * {@code VillagerNobleEntity.openTradeGUI(Player)} and that helper performs the
     * {@code SimpleChannel.send}. Following exactly one hop - and only into classes that are
     * related to the interaction owner by inheritance - recovers those routes without turning
     * the scanner into a general call-graph walk.</p>
     */
    private static List<Sender> sendersReachableFrom(ClassNode interactionOwner, MethodNode method,
                                                     Map<String, ClassNode> nodes) {
        ArrayList<Sender> senders = new ArrayList<>();
        senders.add(new Sender(interactionOwner.name, method));
        if (method.instructions == null) return senders;
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || call.owner == null) continue;
            if (!related(call.owner, interactionOwner.name, nodes)) continue;
            if (!visited.add(call.owner + '#' + call.name + call.desc)) continue;
            ClassNode owner = nodes.get(call.owner);
            if (owner == null || owner.methods == null) continue;
            for (MethodNode candidate : owner.methods) {
                if (candidate != null && call.name.equals(candidate.name)
                        && call.desc.equals(candidate.desc) && candidate != method) {
                    senders.add(new Sender(owner.name, candidate));
                }
            }
        }
        return senders;
    }

    private static boolean related(String a, String b, Map<String, ClassNode> nodes) {
        return a.equals(b) || isSubclass(a, b, nodes) || isSubclass(b, a, nodes);
    }

    /** True when the supertype chain reaches vanilla {@code net/minecraft/world/entity/...}. */
    private static boolean looksLikeEntity(ClassNode node, Map<String, ClassNode> nodes) {
        String cursor = node == null ? null : node.name;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        while (cursor != null && seen.add(cursor)) {
            if (cursor.startsWith("net/minecraft/world/entity/")) return true;
            ClassNode current = nodes.get(cursor);
            if (current == null) return false;
            cursor = current.superName;
        }
        return false;
    }

    /** Exact screen classes for one live target hierarchy, including nested profiles. */
    public static List<String> screensForTarget(Collection<ModScanner.Profile> profiles,
                                                Collection<String> targetTypes) {
        if (profiles == null || targetTypes == null || targetTypes.isEmpty()) return List.of();
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String type : targetTypes) {
            if (type != null && !type.isBlank()) wanted.add(type.replace('.', '/'));
        }
        LinkedHashMap<String, Integer> screens = new LinkedHashMap<>();
        for (ModScanner.Profile profile : profiles) collect(profile, wanted, screens);
        return screens.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static void collect(ModScanner.Profile profile, Set<String> wanted,
                                Map<String, Integer> screens) {
        if (profile == null) return;
        if (profile.entityScreenRoutes != null) {
            for (Route route : profile.entityScreenRoutes) {
                if (route == null || route.entityClass == null || route.screenClass == null
                        || !wanted.contains(route.entityClass.replace('.', '/'))) continue;
                screens.merge(route.screenClass, route.confidence, Math::max);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile nested : profile.nested) collect(nested, wanted, screens);
        }
    }

    private static Map<String, Resolver> packetResolvers(Map<String, ClassNode> nodes) {
        LinkedHashMap<String, Resolver> result = new LinkedHashMap<>();
        for (ClassNode packet : nodes.values()) {
            if (packet == null || packet.name == null || packet.methods == null) continue;
            for (MethodNode method : packet.methods) {
                if (method == null || method.instructions == null) continue;
                Resolver resolver = null;
                String directScreen = null;
                boolean opensScreen = false;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof TypeInsnNode type
                            && type.getOpcode() == org.objectweb.asm.Opcodes.NEW
                            && directScreen == null && isScreen(type.desc, nodes)) {
                        directScreen = type.desc;
                    }
                    if (!(instruction instanceof MethodInsnNode call)) continue;
                    Type returned;
                    try {
                        returned = Type.getReturnType(call.desc);
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }
                    if (returned.getSort() == Type.OBJECT
                            && "net/minecraft/client/gui/screens/Screen".equals(returned.getInternalName())) {
                        resolver = new Resolver(call.owner, call.name, call.desc, null);
                    }
                    if (minecraftScreenSetter(call)) opensScreen = true;
                }
                if (!opensScreen) continue;
                if (resolver != null) {
                    result.putIfAbsent(packet.name, resolver);
                } else if (directScreen != null) {
                    result.putIfAbsent(packet.name,
                            new Resolver(packet.name, method.name, method.desc, directScreen));
                }
            }
        }
        return result;
    }

    private static boolean minecraftScreenSetter(MethodInsnNode call) {
        if (call == null || call.owner == null || call.desc == null) return false;
        if (!call.owner.equals("net/minecraft/client/Minecraft")) return false;
        try {
            for (Type argument : Type.getArgumentTypes(call.desc)) {
                if (argument.getSort() == Type.OBJECT
                        && "net/minecraft/client/gui/screens/Screen".equals(argument.getInternalName())) {
                    return true;
                }
            }
        } catch (IllegalArgumentException ignored) {
        }
        return false;
    }

    private static boolean interactionShape(MethodNode method) {
        if (method == null || method.desc == null || method.instructions == null) return false;
        String desc = method.desc;
        return desc.contains("Lnet/minecraft/world/entity/player/Player;")
                && desc.contains("Lnet/minecraft/world/InteractionHand;")
                && desc.contains("Lnet/minecraft/world/InteractionResult;");
    }

    private static Set<String> clientPacketsSentBy(MethodNode method, Set<String> knownPackets) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (method == null || method.instructions == null || knownPackets.isEmpty()) return result;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode send) || !clientSend(send)) continue;
            String packet = recentConstructedPacket(instruction, knownPackets, 48);
            if (packet != null) result.add(packet);
        }
        return result;
    }

    private static boolean clientSend(MethodInsnNode call) {
        if (call == null || call.owner == null || call.name == null || call.desc == null) return false;
        String owner = call.owner.toLowerCase(java.util.Locale.ROOT);
        String name = call.name.toLowerCase(java.util.Locale.ROOT);
        if (owner.contains("simplechannel") && name.equals("send")) return true;
        if (owner.contains("packetdistributor") && name.startsWith("send")) return true;
        return owner.contains("serverplayer") && name.contains("send")
                || name.contains("sendtoclient") || name.contains("sendtoplayer");
    }

    private static String recentConstructedPacket(AbstractInsnNode before, Set<String> knownPackets,
                                                  int maxInstructions) {
        int remaining = maxInstructions;
        for (AbstractInsnNode current = before == null ? null : before.getPrevious();
             current != null && remaining-- > 0; current = current.getPrevious()) {
            if (current instanceof MethodInsnNode constructor
                    && "<init>".equals(constructor.name)
                    && knownPackets.contains(constructor.owner)) return constructor.owner;
            if (current instanceof TypeInsnNode type && type.getOpcode() == org.objectweb.asm.Opcodes.NEW
                    && knownPackets.contains(type.desc)) return type.desc;
        }
        return null;
    }

    private static String resolvedScreen(ClassNode entity, Resolver resolver,
                                         Map<String, ClassNode> nodes) {
        if (entity == null || entity.methods == null || resolver == null) return null;
        for (MethodNode method : entity.methods) {
            if (!resolver.method.equals(method.name) || !resolver.descriptor.equals(method.desc)
                    || method.instructions == null) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof TypeInsnNode type)
                        || type.getOpcode() != org.objectweb.asm.Opcodes.NEW) continue;
                if (isScreen(type.desc, nodes)) return type.desc;
            }
        }
        return null;
    }

    private static boolean isScreen(String type, Map<String, ClassNode> nodes) {
        if (type == null) return false;
        if (type.endsWith("Screen")) return true;
        return isSubclass(type, "net/minecraft/client/gui/screens/Screen", nodes);
    }

    private static boolean isSubclass(String child, String parent, Map<String, ClassNode> nodes) {
        if (child == null || parent == null) return false;
        String cursor = child;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        while (cursor != null && seen.add(cursor)) {
            if (parent.equals(cursor)) return true;
            ClassNode node = nodes.get(cursor);
            if (node == null) return false;
            if (node.interfaces != null) {
                for (String iface : node.interfaces) {
                    if (parent.equals(iface) || isSubclass(iface, parent, nodes)) return true;
                }
            }
            cursor = node.superName;
        }
        return false;
    }
}
