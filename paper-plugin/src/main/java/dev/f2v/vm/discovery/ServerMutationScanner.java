package dev.f2v.vm.discovery;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.Type;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Describes the authoritative server calls made by a mod's client-to-server packet handlers.
 *
 * <p>This is intentionally descriptive rather than executable. The scanner never loads packet or
 * client classes; it records calls from server-handler-shaped methods so runtime bridges can prefer
 * a proven mod-owned mutation path and fall back to generic reflection only when no recipe exists.</p>
 */
public final class ServerMutationScanner {

    private static final int MAX_CALLS = 96;

    private ServerMutationScanner() {}

    public static final class Recipe {
        public String packetClass;
        /** Class that actually owns handlerMethod. Usually packetClass, but modern payload
         * registrars often bind a record payload to a separate singleton/static handler class. */
        public String handlerOwner;
        public String handlerMethod;
        /** Concrete packet constructor descriptors declared by this class. */
        public final List<String> constructors = new ArrayList<>();
        /** Direct constructor argument -> packet field assignments. This intentionally records
         * only bytecode-proven ALOAD/ILOAD/etc -> PUTFIELD shapes; transformed arguments remain
         * unknown rather than being guessed. */
        public final List<ConstructorFieldBinding> constructorFields = new ArrayList<>();
        public final List<Invocation> calls = new ArrayList<>();
    }

    public static final class ConstructorFieldBinding {
        public String constructorDesc;
        public int argumentIndex;
        public String fieldName;
        public String fieldDesc;

        public ConstructorFieldBinding() {}

        ConstructorFieldBinding(String constructorDesc, int argumentIndex,
                                String fieldName, String fieldDesc) {
            this.constructorDesc = constructorDesc;
            this.argumentIndex = argumentIndex;
            this.fieldName = fieldName;
            this.fieldDesc = fieldDesc;
        }
    }

    public static final class Invocation {
        public String owner;
        public String name;
        public String desc;
        public boolean interfaceCall;

        public Invocation() {}

        Invocation(String owner, String name, String desc, boolean interfaceCall) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.interfaceCall = interfaceCall;
        }

        @Override
        public String toString() {
            return owner + '.' + name + desc;
        }
    }

    /** One exact packet -> authoritative target call edge. Keeping the packet owner attached lets
     * runtime bridges require a matching client constructor recipe before replaying argument-rich
     * mutations such as vehicle controls. */
    public record PacketInvocation(String packetClass, Invocation call) {}

    /** Exact handler owner + method identity. Keeping the owner separate prevents runtime code
     * that only knows how to invoke packet-local handlers from accidentally treating an external
     * registrar-bound handler as a method on the payload class. */
    public record HandlerRef(String owner, String method) {}

    /** Returns all server-handler recipes exposed by this packet/message-like class. */
    static List<Recipe> inspect(ClassNode node) {
        return inspect(node, false);
    }

    /** A concrete send operand establishes packet identity even when its class has no descriptive
     * name. Keep the handler signature requirement: being constructed on the client is not enough. */
    static void discoverServerbound(java.util.Map<String, ClassNode> nodes,
                                   List<BehaviorBytecodeScanner.BehaviorSurface> behaviors,
                                   List<Recipe> recipes) {
        Set<String> packets = new LinkedHashSet<>();
        Set<String> existing = new LinkedHashSet<>();
        for (Recipe recipe : recipes) existing.add(recipeKey(recipe));
        for (var surface : behaviors) {
            for (var method : surface.methods) {
                for (var call : method.calls) {
                    if (call.effect == BehaviorBytecodeScanner.Effect.SEND_SERVER_PACKET && call.target != null) {
                        packets.add(call.target);
                    }
                }
            }
        }
        for (String packet : packets) {
            ClassNode node = nodes.get(packet);
            if (node == null) continue;
            for (Recipe recipe : inspect(node, true)) {
                if (existing.add(recipeKey(recipe))) recipes.add(recipe);
            }
        }
    }

    /** Promote exact client-surface SEND_PACKET operands into the same proven-serverbound handler
     * inspection used by behavior scanning. Some frameworks use neutral record names such as
     * {@code SyncHolderChange}; requiring "packet/message/payload/network" in the class name makes
     * those real C2S payloads invisible even though ClientSurfaceScanner already proved that the
     * object is passed to a game-network send call. This join consumes only concrete discovered
     * SEND_PACKET targets and never guesses from a namespace or class-name token. */
    static void discoverClientServerbound(java.util.Map<String, ClassNode> nodes,
                                          List<ClientSurfaceScanner.ClassSurface> clientSurfaces,
                                          List<Recipe> recipes) {
        if (nodes == null || clientSurfaces == null || recipes == null) return;
        Set<String> packets = new LinkedHashSet<>();
        Set<String> existing = new LinkedHashSet<>();
        for (Recipe recipe : recipes) {
            if (recipe != null && recipe.packetClass != null && recipe.handlerMethod != null) {
                existing.add(recipeKey(recipe));
            }
        }
        for (ClientSurfaceScanner.ClassSurface surface : clientSurfaces) {
            if (surface == null || surface.actions == null) continue;
            for (ClientSurfaceScanner.Action action : surface.actions) {
                if (action == null || action.type != ClientSurfaceScanner.ActionType.SEND_PACKET
                        || action.target == null || action.target.isBlank()) continue;
                packets.add(action.target.replace('.', '/'));
            }
        }
        for (String packet : packets) {
            ClassNode node = nodes.get(packet);
            if (node == null) continue;
            for (Recipe recipe : inspect(node, true)) {
                if (existing.add(recipeKey(recipe))) recipes.add(recipe);
            }
        }
        discoverExternalHandlers(nodes, packets, recipes, existing);
    }

    private static List<Recipe> inspect(ClassNode node, boolean provenServerbound) {
        if (node == null || node.name == null || node.methods == null) return List.of();
        String className = node.name.toLowerCase(Locale.ROOT);
        boolean packetLike = className.contains("packet") || className.contains("message")
                || className.contains("payload") || className.contains("network");
        if (!packetLike && !provenServerbound) return List.of();

        List<Recipe> recipes = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!serverHandler(method) && !(provenServerbound && contextHandler(method))) continue;
            Recipe recipe = new Recipe();
            recipe.packetClass = node.name;
            recipe.handlerOwner = node.name;
            recipe.handlerMethod = method.name + method.desc;
            addPacketConstructors(recipe, node);
            Set<String> seen = new LinkedHashSet<>();
            collectHandlerCalls(node, method, recipe, seen, new LinkedHashSet<>(), 0);
            if (!recipe.calls.isEmpty()) recipes.add(recipe);
        }
        return List.copyOf(recipes);
    }

    /** Modern NeoForge/Fabric-style registration frequently keeps payload data and handler code in
     * different classes. Once a concrete payload has already been proven by a client C2S send, an
     * exact method whose descriptor contains that payload plus a server/network context is equally
     * strong handler evidence. This is structural only: no registrar class names or mod ids are
     * required, and no class is promoted unless the payload itself was already a SEND_PACKET target. */
    private static void discoverExternalHandlers(java.util.Map<String, ClassNode> nodes,
                                                 Set<String> packets,
                                                 List<Recipe> recipes,
                                                 Set<String> existing) {
        if (nodes == null || packets == null || packets.isEmpty()) return;
        for (ClassNode owner : nodes.values()) {
            if (owner == null || owner.name == null || owner.methods == null) continue;
            for (MethodNode method : owner.methods) {
                String packet = externalHandlerPacket(method, packets);
                if (packet == null || packet.equals(owner.name)) continue;
                ClassNode packetNode = nodes.get(packet);
                if (packetNode == null) continue;
                Recipe recipe = new Recipe();
                recipe.packetClass = packet;
                recipe.handlerOwner = owner.name;
                recipe.handlerMethod = method.name + method.desc;
                addPacketConstructors(recipe, packetNode);
                collectHandlerCalls(owner, method, recipe, new LinkedHashSet<>(),
                        new LinkedHashSet<>(), 0);
                if (recipe.calls.isEmpty() || !existing.add(recipeKey(recipe))) continue;
                recipes.add(recipe);
            }
        }
    }

    private static String externalHandlerPacket(MethodNode method, Set<String> packets) {
        if (method == null || method.desc == null || method.name == null
                || method.name.startsWith("<") || !(serverHandler(method) || contextHandler(method))) {
            return null;
        }
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(method.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        String packet = null;
        boolean serverContext = false;
        for (Type argument : arguments) {
            if (argument.getSort() != Type.OBJECT) continue;
            String type = argument.getInternalName();
            if (packets.contains(type)) {
                if (packet != null && !packet.equals(type)) return null;
                packet = type;
            } else if (serverContextType(type)) {
                serverContext = true;
            }
        }
        return packet != null && serverContext ? packet : null;
    }

    private static boolean serverContextType(String type) {
        if (type == null) return false;
        String lower = type.toLowerCase(Locale.ROOT);
        return type.equals("net/minecraft/server/level/ServerPlayer")
                || type.equals("net/minecraft/world/entity/player/Player")
                || lower.contains("ipayloadcontext") || lower.contains("serverpacketcontext")
                || lower.contains("networkevent$context")
                || lower.contains("serverplaynetworking$context");
    }

    private static void addPacketConstructors(Recipe recipe, ClassNode packetNode) {
        if (recipe == null || packetNode == null || packetNode.methods == null) return;
        for (MethodNode candidate : packetNode.methods) {
            if (candidate != null && "<init>".equals(candidate.name)
                    && candidate.desc != null && !recipe.constructors.contains(candidate.desc)) {
                recipe.constructors.add(candidate.desc);
                recipe.constructorFields.addAll(constructorFieldBindings(packetNode, candidate));
            }
        }
    }

    private static String recipeKey(Recipe recipe) {
        if (recipe == null) return "";
        String owner = recipe.handlerOwner == null ? recipe.packetClass : recipe.handlerOwner;
        return String.valueOf(recipe.packetClass) + '\n' + String.valueOf(owner) + '\n'
                + String.valueOf(recipe.handlerMethod);
    }

    private static List<ConstructorFieldBinding> constructorFieldBindings(ClassNode owner,
                                                                           MethodNode constructor) {
        if (owner == null || constructor == null || constructor.desc == null) return List.of();
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(constructor.desc);
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
        java.util.Map<Integer, Integer> localToArgument = new java.util.HashMap<>();
        int local = 1; // local 0 is this for an instance constructor
        for (int index = 0; index < arguments.length; index++) {
            localToArgument.put(local, index);
            local += arguments[index].getSize();
        }

        ArrayList<ConstructorFieldBinding> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (AbstractInsnNode instruction = constructor.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (!(instruction instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.PUTFIELD
                    || !owner.name.equals(field.owner)) continue;
            AbstractInsnNode value = previousReal(instruction.getPrevious());
            AbstractInsnNode receiver = previousReal(value == null ? null : value.getPrevious());
            if (!(value instanceof VarInsnNode load)
                    || !(receiver instanceof VarInsnNode thisLoad)
                    || thisLoad.getOpcode() != Opcodes.ALOAD || thisLoad.var != 0) continue;
            Integer argumentIndex = localToArgument.get(load.var);
            if (argumentIndex == null) continue;
            Type argument = arguments[argumentIndex];
            if (!field.desc.equals(argument.getDescriptor())
                    || !loadMatches(argument, load.getOpcode())) continue;
            String id = constructor.desc + '\n' + argumentIndex + '\n' + field.name + ':' + field.desc;
            if (seen.add(id)) {
                out.add(new ConstructorFieldBinding(constructor.desc, argumentIndex,
                        field.name, field.desc));
            }
        }
        return List.copyOf(out);
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction;
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static boolean loadMatches(Type type, int opcode) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> opcode == Opcodes.ILOAD;
            case Type.FLOAT -> opcode == Opcodes.FLOAD;
            case Type.LONG -> opcode == Opcodes.LLOAD;
            case Type.DOUBLE -> opcode == Opcodes.DLOAD;
            case Type.ARRAY, Type.OBJECT -> opcode == Opcodes.ALOAD;
            default -> false;
        };
    }

    /** Forge handlers very commonly put the authoritative work in an enqueueWork lambda. Follow
     * same-class helper/lambda edges while staying inside the packet class, so those mutations are
     * attributed to the real outer packet handler without treating arbitrary called classes as
     * executable. */
    private static void collectHandlerCalls(ClassNode owner, MethodNode method, Recipe recipe,
                                            Set<String> seenCalls, Set<String> seenMethods,
                                            int depth) {
        if (owner == null || method == null || recipe == null || depth > 6
                || recipe.calls.size() >= MAX_CALLS) return;
        String methodId = method.name + method.desc;
        if (!seenMethods.add(methodId)) return;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call) {
                if (!"<init>".equals(call.name) && modOwnedCall(call.owner)) {
                    String id = call.owner + '.' + call.name + call.desc;
                    if (seenCalls.add(id)) {
                        recipe.calls.add(new Invocation(call.owner, call.name, call.desc, call.itf));
                        if (recipe.calls.size() >= MAX_CALLS) return;
                    }
                }
                if (owner.name.equals(call.owner)) {
                    MethodNode helper = findMethod(owner, call.name, call.desc);
                    if (helper != null) {
                        collectHandlerCalls(owner, helper, recipe, seenCalls, seenMethods, depth + 1);
                    }
                }
            } else if (instruction instanceof InvokeDynamicInsnNode indy) {
                for (Object argument : indy.bsmArgs) {
                    if (!(argument instanceof Handle handle) || !owner.name.equals(handle.getOwner())) continue;
                    MethodNode helper = findMethod(owner, handle.getName(), handle.getDesc());
                    if (helper != null) {
                        // A method reference can be the packet's real public command helper rather
                        // than a compiler-generated lambda. Keep that exact edge so runtime replay
                        // can invoke the mod's compound validation/rollback method instead of
                        // approximating it from the helper's low-level target calls.
                        if ((helper.access & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0
                                && !"<init>".equals(helper.name)) {
                            String id = owner.name + '.' + helper.name + helper.desc;
                            if (seenCalls.add(id)) {
                                recipe.calls.add(new Invocation(owner.name, helper.name,
                                        helper.desc, false));
                                if (recipe.calls.size() >= MAX_CALLS) return;
                            }
                        }
                        collectHandlerCalls(owner, helper, recipe, seenCalls, seenMethods, depth + 1);
                    }
                }
            }
        }
    }

    private static MethodNode findMethod(ClassNode owner, String name, String desc) {
        if (owner == null || owner.methods == null || name == null || desc == null) return null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) return method;
        }
        return null;
    }

    /** Exact calls traced for one packet class across top-level and nested mod profiles. */
    public static List<Invocation> callsForPacket(Collection<ModScanner.Profile> profiles,
                                                   String packetClass) {
        if (profiles == null || packetClass == null) return List.of();
        List<Invocation> out = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectPacket(profile, packetClass, out);
        return List.copyOf(out);
    }

    /** Exact server-handler methods discovered on one packet class. Runtime may use these only
     * after separately proving a safe way to construct that packet; exposing the method identity
     * avoids guessing an apply/handle method by name or reflection shape. */
    public static List<String> handlersForPacket(Collection<ModScanner.Profile> profiles,
                                                 String packetClass) {
        if (profiles == null || packetClass == null || packetClass.isBlank()) return List.of();
        String wanted = packetClass.replace('.', '/');
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collectHandlers(profile, wanted, out);
        return List.copyOf(out);
    }

    private static void collectHandlers(ModScanner.Profile profile, String packetClass,
                                        Set<String> out) {
        if (profile == null) return;
        if (profile.serverMutations != null) {
            for (Recipe recipe : profile.serverMutations) {
                if (recipe == null || recipe.packetClass == null || recipe.handlerMethod == null) continue;
                String owner = recipe.handlerOwner == null ? recipe.packetClass : recipe.handlerOwner;
                if (packetClass.equals(recipe.packetClass.replace('.', '/'))
                        && packetClass.equals(owner.replace('.', '/'))) out.add(recipe.handlerMethod);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collectHandlers(child, packetClass, out);
        }
    }

    /** All exact handler identities for a packet, including separate registrar-bound handlers. */
    public static List<HandlerRef> handlerRefsForPacket(Collection<ModScanner.Profile> profiles,
                                                        String packetClass) {
        if (profiles == null || packetClass == null || packetClass.isBlank()) return List.of();
        String wanted = packetClass.replace('.', '/');
        ArrayList<HandlerRef> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collectHandlerRefs(profile, wanted, out, seen);
        return List.copyOf(out);
    }

    private static void collectHandlerRefs(ModScanner.Profile profile, String packetClass,
                                           List<HandlerRef> out, Set<String> seen) {
        if (profile == null) return;
        if (profile.serverMutations != null) {
            for (Recipe recipe : profile.serverMutations) {
                if (recipe == null || recipe.packetClass == null || recipe.handlerMethod == null
                        || !packetClass.equals(recipe.packetClass.replace('.', '/'))) continue;
                String owner = recipe.handlerOwner == null ? recipe.packetClass : recipe.handlerOwner;
                HandlerRef ref = new HandlerRef(owner, recipe.handlerMethod);
                if (seen.add(ref.owner() + '\n' + ref.method())) out.add(ref);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                collectHandlerRefs(child, packetClass, out, seen);
            }
        }
    }

    /** Calls whose owner is one of the supplied runtime target types and whose method matches the
     * requested semantic. Used by the safe runtime replay bridge without loading packet classes. */
    public static List<Invocation> callsForTarget(Collection<ModScanner.Profile> profiles,
                                                   Set<String> targetTypes, String semantic) {
        if (profiles == null || targetTypes == null || targetTypes.isEmpty()) return List.of();
        String wanted = semantic == null ? "" : semantic.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "");
        List<Invocation> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) {
            collectTarget(profile, targetTypes, wanted, out, seen);
        }
        // Packet handlers often contain both a low-level validator (tryEnterAssemblyMode) and
        // the complete authoritative transition (enterAssemblyMode). Bytecode order is not a
        // semantic priority: prefer the exact requested action so replay cannot stop after merely
        // validating it.
        out.sort(Comparator.comparingInt(call -> semanticRank(call.name, wanted)));
        return List.copyOf(out);
    }

    /** Exact packet calls narrowed to a live target hierarchy and optional producer semantic. */
    public static List<Invocation> callsForPacketTarget(Collection<ModScanner.Profile> profiles,
                                                        String packetClass,
                                                        Set<String> targetTypes,
                                                        String semantic) {
        if (profiles == null || packetClass == null || targetTypes == null || targetTypes.isEmpty()) {
            return List.of();
        }
        String wanted = semantic == null ? "" : semantic.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "");
        ArrayList<Invocation> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Invocation call : callsForPacket(profiles, packetClass)) {
            if (call == null || !targetTypes.contains(call.owner)) continue;
            String normalized = call.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
            if (!wanted.isEmpty() && !semanticMatch(normalized, wanted)) continue;
            String id = call.owner + '.' + call.name + call.desc;
            if (seen.add(id)) out.add(call);
        }
        out.sort(Comparator.comparingInt(call -> semanticRank(call.name, wanted)));
        return List.copyOf(out);
    }

    /** Exact packet/call pairs narrowed to a live target hierarchy. No semantic/name matching is
     * performed here; callers must additionally prove the argument transport they intend to
     * reconstruct. */
    public static List<PacketInvocation> packetCallsForTarget(Collection<ModScanner.Profile> profiles,
                                                              Set<String> targetTypes) {
        if (profiles == null || targetTypes == null || targetTypes.isEmpty()) return List.of();
        ArrayList<PacketInvocation> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) {
            collectPacketTarget(profile, targetTypes, out, seen);
        }
        return List.copyOf(out);
    }

    /** True only when the concrete packet class itself declares this exact constructor shape. */
    public static boolean packetHasConstructor(Collection<ModScanner.Profile> profiles,
                                               String packetClass, String descriptor) {
        if (profiles == null || packetClass == null || descriptor == null) return false;
        String wanted = packetClass.replace('.', '/');
        for (ModScanner.Profile profile : profiles) {
            if (profilePacketHasConstructor(profile, wanted, descriptor)) return true;
        }
        return false;
    }

    /** Exact direct packet-field binding for one constructor argument, or null when bytecode does
     * not prove a unique mapping. This is useful for stateful UI replay where field semantics must
     * be known before reconstructing a multi-argument packet. */
    public static String packetConstructorFieldName(Collection<ModScanner.Profile> profiles,
                                                    String packetClass, String descriptor,
                                                    int argumentIndex) {
        if (profiles == null || packetClass == null || descriptor == null || argumentIndex < 0) {
            return null;
        }
        String wanted = packetClass.replace('.', '/');
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) {
            collectConstructorFieldNames(profile, wanted, descriptor, argumentIndex, names);
        }
        return names.size() == 1 ? names.iterator().next() : null;
    }

    private static void collectConstructorFieldNames(ModScanner.Profile profile, String packetClass,
                                                     String descriptor, int argumentIndex,
                                                     Set<String> out) {
        if (profile == null) return;
        if (profile.serverMutations != null) {
            for (Recipe recipe : profile.serverMutations) {
                if (recipe == null || recipe.packetClass == null
                        || !packetClass.equals(recipe.packetClass.replace('.', '/'))
                        || recipe.constructorFields == null) continue;
                for (ConstructorFieldBinding binding : recipe.constructorFields) {
                    if (binding != null && descriptor.equals(binding.constructorDesc)
                            && argumentIndex == binding.argumentIndex && binding.fieldName != null) {
                        out.add(binding.fieldName);
                    }
                }
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                collectConstructorFieldNames(child, packetClass, descriptor, argumentIndex, out);
            }
        }
    }

    private static boolean profilePacketHasConstructor(ModScanner.Profile profile, String packetClass,
                                                       String descriptor) {
        if (profile == null) return false;
        if (profile.serverMutations != null) {
            for (Recipe recipe : profile.serverMutations) {
                if (recipe != null && recipe.packetClass != null
                        && packetClass.equals(recipe.packetClass.replace('.', '/'))
                        && recipe.constructors != null && recipe.constructors.contains(descriptor)) {
                    return true;
                }
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                if (profilePacketHasConstructor(child, packetClass, descriptor)) return true;
            }
        }
        return false;
    }

    private static void collectPacketTarget(ModScanner.Profile profile, Set<String> targetTypes,
                                            List<PacketInvocation> out, Set<String> seen) {
        if (profile == null) return;
        if (profile.serverMutations != null) {
            for (Recipe recipe : profile.serverMutations) {
                if (recipe == null || recipe.packetClass == null) continue;
                for (Invocation call : recipe.calls) {
                    if (call == null || !targetTypes.contains(call.owner)) continue;
                    String id = recipe.packetClass + '\n' + call.owner + '.' + call.name + call.desc;
                    if (seen.add(id)) out.add(new PacketInvocation(recipe.packetClass, call));
                }
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                collectPacketTarget(child, targetTypes, out, seen);
            }
        }
    }

    private static void collectPacket(ModScanner.Profile profile, String packetClass,
                                      List<Invocation> out) {
        if (profile == null) return;
        if (profile.serverMutations != null) {
            for (Recipe recipe : profile.serverMutations) {
                if (recipe != null && packetClass.equals(recipe.packetClass)) out.addAll(recipe.calls);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collectPacket(child, packetClass, out);
        }
    }

    private static void collectTarget(ModScanner.Profile profile, Set<String> targetTypes,
                                      String wanted, List<Invocation> out, Set<String> seen) {
        if (profile == null) return;
        if (profile.serverMutations != null) {
            for (Recipe recipe : profile.serverMutations) {
                if (recipe == null) continue;
                for (Invocation call : recipe.calls) {
                    if (call == null || !targetTypes.contains(call.owner)) continue;
                    String normalized = call.name.toLowerCase(Locale.ROOT)
                            .replaceAll("[^a-z0-9]+", "");
                    if (!wanted.isEmpty() && !semanticMatch(normalized, wanted)) continue;
                    String id = call.owner + '.' + call.name + call.desc;
                    if (seen.add(id)) out.add(call);
                }
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                collectTarget(child, targetTypes, wanted, out, seen);
            }
        }
    }

    private static boolean serverHandler(MethodNode method) {
        if (method == null || method.desc == null) return false;
        if (method.name.startsWith("<")) return false;
        String name = method.name.toLowerCase(Locale.ROOT);
        if (method.desc.contains("Lnet/minecraft/server/level/ServerPlayer;")) return true;
        return name.contains("applysettings") || name.equals("handle") || name.startsWith("handle")
                || name.contains("server") && (name.contains("handle") || name.contains("process"))
                || name.contains("execute") || name.contains("processpacket");
    }

    private static boolean contextHandler(MethodNode method) {
        if (method == null || method.desc == null || method.name.startsWith("<")) return false;
        String descriptor = method.desc.toLowerCase(Locale.ROOT);
        String signature = method.signature == null ? "" : method.signature.toLowerCase(Locale.ROOT);
        return descriptor.contains("serverpacketcontext;") || descriptor.contains("ipayloadcontext;")
                || descriptor.contains("networkevent$context;")
                || descriptor.contains("serverplaynetworking$context;")
                || signature.contains("networkevent$context;");
    }

    private static boolean semanticMatch(String method, String wanted) {
        if (method.equals(wanted)) return true;
        for (String prefix : new String[]{"try", "set", "apply", "update", "refresh"}) {
            if (method.equals(prefix + wanted)) return true;
            if (wanted.equals(prefix + method)) return true;
        }
        return false;
    }

    private static int semanticRank(String name, String wanted) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "");
        return normalized.equals(wanted) ? 0 : 1;
    }

    private static boolean modOwnedCall(String owner) {
        if (owner == null) return false;
        return !(owner.startsWith("java/") || owner.startsWith("javax/")
                || owner.startsWith("jdk/") || owner.startsWith("sun/")
                || owner.startsWith("net/minecraft/") || owner.startsWith("com/mojang/")
                || owner.startsWith("com/google/") || owner.startsWith("org/")
                // io.* is a normal reverse-domain namespace for mods too (Wispforest is a real
                // firstbatch example). Only exclude the infrastructure family we actually mean.
                || owner.startsWith("io/netty/") || owner.startsWith("it/unimi/"));
    }
}
