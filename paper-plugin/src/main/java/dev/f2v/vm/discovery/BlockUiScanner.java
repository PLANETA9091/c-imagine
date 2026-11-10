package dev.f2v.vm.discovery;

import org.objectweb.asm.Type;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers client-only block UI routes without loading client classes.
 *
 * <p>A route is structural: a method receives a BlockEntity subtype, constructs one or more
 * Screen subclasses, and passes a Screen to a client screen-opener method. Runtime bridges can
 * then select captured UI artwork by the authoritative block-entity type rather than by mod id,
 * registry name, or hand-written screen names.</p>
 */
public final class BlockUiScanner {

    private static final int MAX_HELPER_DEPTH = 6;

    private static final Set<String> BLOCK_ENTITY_ROOTS = Set.of(
            "net/minecraft/world/level/block/entity/BlockEntity");
    private static final Set<String> SCREEN_ROOTS = Set.of(
            "net/minecraft/client/gui/screens/Screen",
            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen");
    private static final String CUSTOM_PAYLOAD =
            "net/minecraft/network/protocol/common/custom/CustomPacketPayload";

    public static final class Route {
        public String owner;
        public String method;
        public String targetType;
        public final Set<String> screens = new LinkedHashSet<>();
        /** Client-side hold/overlay controllers reached from this block interaction. */
        public final Set<String> interactionControllers = new LinkedHashSet<>();
        /** C2S payloads constructed by those controllers, used only to select scanner-proven
         * server mutations; the payload classes themselves are never loaded at runtime. */
        public final Set<String> interactionPackets = new LinkedHashSet<>();
        /** Ordered authored GUI sprite fields rendered by the hold controller. */
        public final Set<String> interactionSprites = new LinkedHashSet<>();
        /** Logical height assigned by the controller to its rendered interaction strip. */
        public int interactionHeight = -1;
        /** Exact crop used for the movable handle when the controller blits a sub-region of one
         * of its declared GUI sprites. Keeping this separate from the track prevents pack baking
         * from guessing that every uncovered pixel in the atlas belongs to the handle. */
        public String interactionHandleSprite;
        public int interactionHandleU = -1;
        public int interactionHandleV = -1;
        public int interactionHandleWidth = -1;
        public int interactionHandleHeight = -1;
        public int confidence;
    }

    private BlockUiScanner() {}

    static List<Route> inspect(ClassNode node, Map<String, ClassNode> nodes) {
        if (node == null || node.methods == null || nodes == null) return List.of();
        ArrayList<Route> out = new ArrayList<>();
        for (MethodNode method : node.methods) {
            LinkedHashSet<String> targetTypes = blockEntityTargets(method, nodes);
            if (targetTypes.isEmpty()) continue;
            if (targetTypes.size() == 1) {
                UiTrace trace = traceUi(node.name, method, nodes, new HashSet<>(), 0);
                addRoute(out, node.name, method, targetTypes.iterator().next(), trace);
                continue;
            }
            // A dispatcher may service several block entities. Whole-method unioning attaches
            // every screen to every cast target; instead retain only actions whose local branch
            // proves a target through a cast/instanceof/typed argument load.
            for (String targetType : targetTypes) {
                UiTrace trace = traceUiForTarget(node.name, method, nodes, targetTypes, targetType);
                addRoute(out, node.name, method, targetType, trace);
            }
        }
        enrichInteractionLayout(out, nodes);
        return List.copyOf(out);
    }

    private static void enrichInteractionLayout(List<Route> routes, Map<String, ClassNode> nodes) {
        if (routes == null || routes.isEmpty() || nodes == null || nodes.isEmpty()) return;
        Map<String, GuiSpriteBytecodeScanner.Sprite> sprites = new java.util.LinkedHashMap<>();
        GuiSpriteBytecodeScanner.Result scan = GuiSpriteBytecodeScanner.scan(nodes);
        for (GuiSpriteBytecodeScanner.Sprite sprite : scan.sprites) {
            if (sprite != null && sprite.owner != null && sprite.field != null) {
                sprites.put(sprite.owner + '.' + sprite.field, sprite);
            }
        }
        for (Route route : routes) {
            if (route == null || route.interactionControllers == null) continue;
            for (String controller : route.interactionControllers) {
                BlitCrop handle = interactionHandle(nodes.get(controller), sprites);
                if (handle == null) continue;
                route.interactionHandleSprite = handle.spriteRef;
                route.interactionHandleU = handle.u;
                route.interactionHandleV = handle.v;
                route.interactionHandleWidth = handle.width;
                route.interactionHandleHeight = handle.height;
                break;
            }
        }
    }

    private record BlitCrop(String spriteRef, int u, int v, int width, int height) {}

    /** Finds a deliberately cropped blit from a declared GUI sprite. Track pieces normally blit
     * their declared rectangle verbatim; a handle/knob is commonly stored in the unused half of
     * the same texture and therefore uses different u/v/size arguments. */
    private static BlitCrop interactionHandle(ClassNode controller,
                                              Map<String, GuiSpriteBytecodeScanner.Sprite> sprites) {
        if (controller == null || controller.methods == null || sprites == null || sprites.isEmpty()) {
            return null;
        }
        BlitCrop best = null;
        int bestArea = -1;
        for (MethodNode method : controller.methods) {
            if (method.desc == null || !method.desc.contains("Lnet/minecraft/client/gui/GuiGraphics;")) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || !isGuiBlit(call)) continue;
                BlitCrop crop = blitCrop(call);
                if (crop == null) continue;
                GuiSpriteBytecodeScanner.Sprite declared = sprites.get(crop.spriteRef);
                if (declared == null) continue;
                boolean same = crop.u == declared.u && crop.v == declared.v
                        && crop.width == declared.width && crop.height == declared.height;
                if (same) continue;
                int area = crop.width * crop.height;
                if (area > bestArea) {
                    best = crop;
                    bestArea = area;
                }
            }
        }
        return best;
    }

    private static boolean isGuiBlit(MethodInsnNode call) {
        if (call == null || call.owner == null || call.desc == null) return false;
        String owner = call.owner.toLowerCase(java.util.Locale.ROOT);
        String name = call.name == null ? "" : call.name.toLowerCase(java.util.Locale.ROOT);
        return owner.contains("guigraphics") && (name.contains("blit") || name.contains("draw"))
                && call.desc.contains("Lnet/minecraft/resources/ResourceLocation;");
    }

    private static BlitCrop blitCrop(MethodInsnNode call) {
        AbstractInsnNode location = call.getPrevious();
        int remaining = 32;
        while (location != null && remaining-- > 0) {
            if (location instanceof FieldInsnNode field
                    && field.getOpcode() == org.objectweb.asm.Opcodes.GETFIELD) break;
            location = location.getPrevious();
        }
        if (!(location instanceof FieldInsnNode)) return null;
        AbstractInsnNode spriteInsn = location.getPrevious();
        while (spriteInsn != null && spriteInsn.getOpcode() < 0) spriteInsn = spriteInsn.getPrevious();
        if (!(spriteInsn instanceof FieldInsnNode sprite)
                || sprite.getOpcode() != org.objectweb.asm.Opcodes.GETSTATIC) return null;

        ArrayList<Number> arguments = new ArrayList<>();
        for (AbstractInsnNode cursor = location.getNext(); cursor != null && cursor != call;
             cursor = cursor.getNext()) {
            Number value = numericConstant(cursor);
            if (value != null) arguments.add(value);
        }
        // ResourceLocation, x, y, u, v, width, height, textureWidth, textureHeight.
        if (arguments.size() < 8) return null;
        int offset = arguments.size() - 8;
        int u = Math.round(arguments.get(offset + 2).floatValue());
        int v = Math.round(arguments.get(offset + 3).floatValue());
        int width = arguments.get(offset + 4).intValue();
        int height = arguments.get(offset + 5).intValue();
        if (u < 0 || v < 0 || width <= 0 || height <= 0 || width > 1024 || height > 1024) return null;
        return new BlitCrop(sprite.owner + '.' + sprite.name, u, v, width, height);
    }

    private static Number numericConstant(AbstractInsnNode instruction) {
        if (instruction instanceof IntInsnNode value) return value.operand;
        if (instruction instanceof LdcInsnNode value && value.cst instanceof Number number) return number;
        int opcode = instruction == null ? -1 : instruction.getOpcode();
        if (opcode >= org.objectweb.asm.Opcodes.ICONST_M1
                && opcode <= org.objectweb.asm.Opcodes.ICONST_5) {
            return opcode - org.objectweb.asm.Opcodes.ICONST_0;
        }
        if (opcode >= org.objectweb.asm.Opcodes.FCONST_0
                && opcode <= org.objectweb.asm.Opcodes.FCONST_2) {
            return (float) (opcode - org.objectweb.asm.Opcodes.FCONST_0);
        }
        if (opcode == org.objectweb.asm.Opcodes.DCONST_0) return 0d;
        if (opcode == org.objectweb.asm.Opcodes.DCONST_1) return 1d;
        return null;
    }

    private static void addRoute(List<Route> out, String owner, MethodNode method,
                                 String targetType, UiTrace trace) {
        if (trace == null) return;
        boolean screenRoute = trace.opensScreen && !trace.screens.isEmpty();
        boolean holdRoute = !trace.interactionControllers.isEmpty()
                && !trace.interactionPackets.isEmpty();
        if (!screenRoute && !holdRoute) return;
        Route route = new Route();
        route.owner = owner;
        route.method = method.name + method.desc;
        route.targetType = targetType;
        route.screens.addAll(trace.screens);
        route.interactionControllers.addAll(trace.interactionControllers);
        route.interactionPackets.addAll(trace.interactionPackets);
        route.interactionSprites.addAll(trace.interactionSprites);
        route.interactionHeight = trace.interactionHeight;
        route.confidence = trace.depth == 0 ? 98 : Math.max(86, 98 - trace.depth * 2);
        out.add(route);
    }

    /** Concrete screen classes discovered for any runtime type in the supplied hierarchy. */
    public static Set<String> screensForTarget(Collection<ModScanner.Profile> profiles,
                                               Collection<String> runtimeTypes) {
        if (profiles == null || runtimeTypes == null || runtimeTypes.isEmpty()) return Set.of();
        java.util.LinkedHashMap<String, Integer> ranks = new java.util.LinkedHashMap<>();
        int rank = 0;
        for (String type : runtimeTypes) {
            if (type != null && !type.isBlank()) ranks.putIfAbsent(type.replace('.', '/'), rank++);
        }
        if (ranks.isEmpty()) return Set.of();
        ArrayList<Route> routes = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectRoutes(profile, routes);
        int best = Integer.MAX_VALUE;
        for (Route route : routes) {
            if (route == null || route.targetType == null || route.screens == null
                    || route.screens.isEmpty()) continue;
            Integer candidate = ranks.get(route.targetType.replace('.', '/'));
            if (candidate != null) best = Math.min(best, candidate);
        }
        if (best == Integer.MAX_VALUE) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Route route : routes) {
            if (route == null || route.targetType == null || route.screens == null) continue;
            Integer candidate = ranks.get(route.targetType.replace('.', '/'));
            if (candidate != null && candidate == best) out.addAll(route.screens);
        }
        return Set.copyOf(out);
    }

    /** Scanner-proven hold/HUD interaction routes for a live block-entity hierarchy. */
    public static List<Route> interactionsForTarget(Collection<ModScanner.Profile> profiles,
                                                    Collection<String> runtimeTypes) {
        if (profiles == null || runtimeTypes == null || runtimeTypes.isEmpty()) return List.of();
        java.util.LinkedHashMap<String, Integer> ranks = new java.util.LinkedHashMap<>();
        int rank = 0;
        for (String type : runtimeTypes) {
            if (type != null && !type.isBlank()) ranks.putIfAbsent(type.replace('.', '/'), rank++);
        }
        if (ranks.isEmpty()) return List.of();
        ArrayList<Route> routes = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectRoutes(profile, routes);
        int best = Integer.MAX_VALUE;
        for (Route route : routes) {
            if (!interactionRoute(route)) continue;
            Integer candidate = ranks.get(route.targetType.replace('.', '/'));
            if (candidate != null) best = Math.min(best, candidate);
        }
        if (best == Integer.MAX_VALUE) return List.of();
        ArrayList<Route> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Route route : routes) {
            if (!interactionRoute(route)) continue;
            Integer candidate = ranks.get(route.targetType.replace('.', '/'));
            if (candidate == null || candidate != best) continue;
            String id = route.targetType + '\n' + route.owner + '\n' + route.method + '\n'
                    + route.interactionControllers + '\n' + route.interactionPackets;
            if (seen.add(id)) out.add(route);
        }
        return List.copyOf(out);
    }

    private static boolean interactionRoute(Route route) {
        return route != null && route.targetType != null
                && route.interactionControllers != null && !route.interactionControllers.isEmpty()
                && route.interactionPackets != null && !route.interactionPackets.isEmpty();
    }

    private static void collectRoutes(ModScanner.Profile profile, List<Route> out) {
        if (profile == null) return;
        if (profile.blockUiRoutes != null) out.addAll(profile.blockUiRoutes);
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collectRoutes(child, out);
        }
    }

    private static void collect(ModScanner.Profile profile, Set<String> runtimeTypes,
                                Set<String> out) {
        if (profile == null) return;
        if (profile.blockUiRoutes != null) {
            for (Route route : profile.blockUiRoutes) {
                if (route == null || route.targetType == null || route.screens == null) continue;
                if (runtimeTypes.contains(route.targetType.replace('.', '/'))) out.addAll(route.screens);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collect(child, runtimeTypes, out);
        }
    }

    private static void collectInteractions(ModScanner.Profile profile, Set<String> runtimeTypes,
                                            List<Route> out, Set<String> seen) {
        if (profile == null) return;
        if (profile.blockUiRoutes != null) {
            for (Route route : profile.blockUiRoutes) {
                if (route == null || route.targetType == null
                        || route.interactionControllers == null || route.interactionControllers.isEmpty()
                        || route.interactionPackets == null || route.interactionPackets.isEmpty()
                        || !runtimeTypes.contains(route.targetType.replace('.', '/'))) continue;
                String id = route.targetType + '\n' + route.owner + '\n' + route.method + '\n'
                        + route.interactionControllers + '\n' + route.interactionPackets;
                if (seen.add(id)) out.add(route);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                collectInteractions(child, runtimeTypes, out, seen);
            }
        }
    }

    private static LinkedHashSet<String> blockEntityTargets(MethodNode method,
                                                            Map<String, ClassNode> nodes) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            for (Type type : Type.getArgumentTypes(method.desc)) {
                if (type.getSort() != Type.OBJECT) continue;
                String name = type.getInternalName();
                if (isBlockEntityType(name, nodes)) out.add(name);
            }
        } catch (IllegalArgumentException ignored) {
        }
        // UI entrypoints frequently receive Level + BlockPos and obtain the concrete target with
        // getBlockEntity followed by CHECKCAST. The cast is stronger type evidence than a method
        // or class name and works for arbitrary mods and obfuscated entrypoints.
        if (method != null && method.instructions != null) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode cast
                        && (cast.getOpcode() == org.objectweb.asm.Opcodes.CHECKCAST
                        || cast.getOpcode() == org.objectweb.asm.Opcodes.INSTANCEOF)
                        && isBlockEntityType(cast.desc, nodes)) out.add(cast.desc);
            }
        }
        return out;
    }

    private static boolean isBlockEntityType(String type, Map<String, ClassNode> nodes) {
        if (inheritsAny(type, BLOCK_ENTITY_ROOTS, nodes)) return true;
        ClassNode node = nodes.get(type);
        if (node == null || node.methods == null) return false;
        // A nested mod jar can omit the library superclass that ultimately extends BlockEntity.
        // The vanilla BlockEntity constructor shape remains strong structural evidence without
        // relying on a mod id, class name, or an externally resolvable inheritance chain.
        for (MethodNode method : node.methods) {
            if (!"<init>".equals(method.name) || method.desc == null) continue;
            if (method.desc.startsWith("(Lnet/minecraft/world/level/block/entity/BlockEntityType;"
                    + "Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;")) return true;
        }
        return false;
    }

    private record UiTrace(boolean opensScreen, LinkedHashSet<String> screens,
                           LinkedHashSet<String> interactionControllers,
                           LinkedHashSet<String> interactionPackets,
                           LinkedHashSet<String> interactionSprites,
                           int interactionHeight, int depth) {}

    /** Branch-aware trace for methods that mention more than one block-entity type. */
    private static UiTrace traceUiForTarget(String owner, MethodNode method,
                                            Map<String, ClassNode> nodes,
                                            Set<String> targetTypes, String wantedTarget) {
        LinkedHashSet<String> screens = new LinkedHashSet<>();
        LinkedHashSet<String> controllers = new LinkedHashSet<>();
        LinkedHashSet<String> packets = new LinkedHashSet<>();
        LinkedHashSet<String> sprites = new LinkedHashSet<>();
        int interactionHeight = -1;
        int deepest = 0;
        boolean opens = false;
        if (method == null || method.instructions == null) {
            return new UiTrace(false, screens, controllers, packets, sprites, -1, 0);
        }
        Map<Integer, String> argumentTargets = argumentTargetLocals(method, targetTypes);
        for (AbstractInsnNode instruction : method.instructions) {
            String localTarget = targetNear(instruction, method, targetTypes, argumentTargets);
            boolean owned = wantedTarget.equals(localTarget);
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == org.objectweb.asm.Opcodes.NEW
                    && isScreen(type.desc, nodes) && owned) {
                screens.add(type.desc);
            }
            if (instruction instanceof MethodInsnNode call) {
                String descriptorTarget = uniqueTargetInDescriptor(call.desc, targetTypes);
                if (descriptorTarget != null) owned = wantedTarget.equals(descriptorTarget);
                if (screenOpener(call) && owned) opens = true;
                String returned = objectReturn(call.desc);
                if (isScreen(returned, nodes) && owned) screens.add(returned);
                if (shouldTraceHelper(owner, call, returned, nodes)) {
                    MethodNode helper = findMethod(nodes.get(call.owner), call.name, call.desc);
                    UiTrace nested = traceUi(call.owner, helper, nodes, new HashSet<>(), 1);
                    if (owned && hasUiEvidence(nested)) {
                        opens |= nested.opensScreen;
                        screens.addAll(nested.screens);
                        controllers.addAll(nested.interactionControllers);
                        packets.addAll(nested.interactionPackets);
                        sprites.addAll(nested.interactionSprites);
                        interactionHeight = Math.max(interactionHeight, nested.interactionHeight);
                        deepest = Math.max(deepest, nested.depth);
                    }
                }
            } else if (instruction instanceof InvokeDynamicInsnNode dynamic
                    && dynamic.bsmArgs != null && owned) {
                for (Object argument : dynamic.bsmArgs) {
                    if (!(argument instanceof Handle handle)) continue;
                    MethodNode helper = findMethod(nodes.get(handle.getOwner()),
                            handle.getName(), handle.getDesc());
                    UiTrace nested = traceUi(handle.getOwner(), helper, nodes, new HashSet<>(), 1);
                    if (!hasUiEvidence(nested)) continue;
                    opens |= nested.opensScreen;
                    screens.addAll(nested.screens);
                    controllers.addAll(nested.interactionControllers);
                    packets.addAll(nested.interactionPackets);
                    sprites.addAll(nested.interactionSprites);
                    interactionHeight = Math.max(interactionHeight, nested.interactionHeight);
                    deepest = Math.max(deepest, nested.depth);
                }
            }
        }
        return new UiTrace(opens, screens, controllers, packets, sprites,
                interactionHeight, deepest);
    }

    private static boolean hasUiEvidence(UiTrace trace) {
        return trace != null && (trace.opensScreen || !trace.screens.isEmpty()
                || !trace.interactionControllers.isEmpty() || !trace.interactionPackets.isEmpty());
    }

    private static Map<Integer, String> argumentTargetLocals(MethodNode method,
                                                             Set<String> targetTypes) {
        java.util.LinkedHashMap<Integer, String> out = new java.util.LinkedHashMap<>();
        if (method == null || method.desc == null) return out;
        int local = (method.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        try {
            for (Type type : Type.getArgumentTypes(method.desc)) {
                if (type.getSort() == Type.OBJECT && targetTypes.contains(type.getInternalName())) {
                    out.put(local, type.getInternalName());
                }
                local += type.getSize();
            }
        } catch (IllegalArgumentException ignored) {
        }
        return out;
    }

    private static String uniqueTargetInDescriptor(String descriptor, Set<String> targetTypes) {
        if (descriptor == null || targetTypes == null || targetTypes.isEmpty()) return null;
        String found = null;
        try {
            for (Type type : Type.getArgumentTypes(descriptor)) {
                if (type.getSort() != Type.OBJECT || !targetTypes.contains(type.getInternalName())) continue;
                if (found != null && !found.equals(type.getInternalName())) return null;
                found = type.getInternalName();
            }
        } catch (IllegalArgumentException ignored) {
        }
        return found;
    }

    private static String targetNear(AbstractInsnNode instruction, MethodNode method,
                                     Set<String> targetTypes,
                                     Map<Integer, String> argumentTargets) {
        int remaining = 96;
        for (AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
             previous != null && remaining > 0; previous = previous.getPrevious()) {
            if (previous.getOpcode() >= 0) remaining--;
            if (previous instanceof TypeInsnNode type
                    && (type.getOpcode() == org.objectweb.asm.Opcodes.CHECKCAST
                    || type.getOpcode() == org.objectweb.asm.Opcodes.INSTANCEOF)
                    && targetTypes.contains(type.desc)) return type.desc;
            if (previous instanceof VarInsnNode variable
                    && variable.getOpcode() == org.objectweb.asm.Opcodes.ALOAD) {
                String target = argumentTargets.get(variable.var);
                if (target != null) return target;
            }
            int opcode = previous.getOpcode();
            if (opcode == org.objectweb.asm.Opcodes.GOTO
                    || opcode == org.objectweb.asm.Opcodes.TABLESWITCH
                    || opcode == org.objectweb.asm.Opcodes.LOOKUPSWITCH
                    || opcode == org.objectweb.asm.Opcodes.RETURN
                    || opcode == org.objectweb.asm.Opcodes.ARETURN
                    || opcode == org.objectweb.asm.Opcodes.IRETURN
                    || opcode == org.objectweb.asm.Opcodes.LRETURN
                    || opcode == org.objectweb.asm.Opcodes.FRETURN
                    || opcode == org.objectweb.asm.Opcodes.DRETURN
                    || opcode == org.objectweb.asm.Opcodes.ATHROW) break;
        }
        return null;
    }

    /** Trace screen construction/opening through archive-local helpers and lambda bodies. */
    private static UiTrace traceUi(String owner, MethodNode method, Map<String, ClassNode> nodes,
                                   Set<String> visited, int depth) {
        LinkedHashSet<String> screens = new LinkedHashSet<>();
        LinkedHashSet<String> controllers = new LinkedHashSet<>();
        LinkedHashSet<String> packets = new LinkedHashSet<>();
        LinkedHashSet<String> sprites = new LinkedHashSet<>();
        int interactionHeight = -1;
        if (method == null || method.instructions == null || depth > MAX_HELPER_DEPTH) {
            return new UiTrace(false, screens, controllers, packets, sprites,
                    interactionHeight, depth);
        }
        String identity = owner + '#' + method.name + method.desc;
        if (!visited.add(identity)) return new UiTrace(false, screens, controllers, packets,
                sprites, interactionHeight, depth);
        boolean opens = false;
        int deepest = depth;
        ClassNode ownerNode = nodes.get(owner);
        if (interactiveOverlayController(ownerNode)) {
            controllers.add(owner);
            packets.addAll(interactionPackets(ownerNode, nodes));
            sprites.addAll(interactionSprites(ownerNode, nodes));
            interactionHeight = interactionHeight(ownerNode);
        }
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == org.objectweb.asm.Opcodes.NEW
                    && isScreen(type.desc, nodes)) screens.add(type.desc);
            if (instruction instanceof MethodInsnNode call) {
                if (screenOpener(call)) opens = true;
                String returned = objectReturn(call.desc);
                if (isScreen(returned, nodes)) screens.add(returned);
                if (depth < MAX_HELPER_DEPTH && shouldTraceHelper(owner, call, returned, nodes)) {
                    MethodNode helper = findMethod(nodes.get(call.owner), call.name, call.desc);
                    UiTrace nested = traceUi(call.owner, helper, nodes, visited, depth + 1);
                    opens |= nested.opensScreen;
                    screens.addAll(nested.screens);
                    controllers.addAll(nested.interactionControllers);
                    packets.addAll(nested.interactionPackets);
                    sprites.addAll(nested.interactionSprites);
                    interactionHeight = Math.max(interactionHeight, nested.interactionHeight);
                    deepest = Math.max(deepest, nested.depth);
                }
            } else if (instruction instanceof InvokeDynamicInsnNode dynamic
                    && depth < MAX_HELPER_DEPTH && dynamic.bsmArgs != null) {
                for (Object argument : dynamic.bsmArgs) {
                    if (!(argument instanceof Handle handle)) continue;
                    MethodNode helper = findMethod(nodes.get(handle.getOwner()),
                            handle.getName(), handle.getDesc());
                    UiTrace nested = traceUi(handle.getOwner(), helper, nodes, visited, depth + 1);
                    opens |= nested.opensScreen;
                    screens.addAll(nested.screens);
                    controllers.addAll(nested.interactionControllers);
                    packets.addAll(nested.interactionPackets);
                    sprites.addAll(nested.interactionSprites);
                    interactionHeight = Math.max(interactionHeight, nested.interactionHeight);
                    deepest = Math.max(deepest, nested.depth);
                }
            }
        }
        return new UiTrace(opens, screens, controllers, packets, sprites,
                interactionHeight, deepest);
    }

    /**
     * Detects the client pattern used by press/hold controls without relying on class names: the
     * controller renders directly into GuiGraphics and also consumes pointer/use input. These
     * classes are not Screen subclasses, so ordinary block-screen discovery cannot see them.
     */
    private static boolean interactiveOverlayController(ClassNode node) {
        if (node == null || node.methods == null) return false;
        boolean rendersHud = false;
        boolean consumesInput = false;
        for (MethodNode method : node.methods) {
            String desc = method.desc == null ? "" : method.desc;
            String name = method.name == null ? "" : method.name.toLowerCase(java.util.Locale.ROOT);
            if (desc.contains("Lnet/minecraft/client/gui/GuiGraphics;")
                    && (name.contains("render") || name.contains("draw"))) rendersHud = true;
            if ((desc.startsWith("(DD)") || desc.contains("Lnet/minecraft/client/KeyMapping;"))
                    && (name.contains("mouse") || name.contains("use") || name.contains("attack")
                    || name.contains("input") || name.contains("active"))) consumesInput = true;
            if (name.equals("release") || name.equals("start") || name.equals("stop")) {
                consumesInput = true;
            }
        }
        return rendersHud && consumesInput;
    }

    private static Set<String> interactionPackets(ClassNode controller, Map<String, ClassNode> nodes) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (controller == null || controller.methods == null) return out;
        for (MethodNode method : controller.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode type
                        && type.getOpcode() == org.objectweb.asm.Opcodes.NEW
                        && isCustomPayload(type.desc, nodes)) out.add(type.desc);
                // Some factories return a payload without a visible NEW at the call site.
                if (instruction instanceof MethodInsnNode call) {
                    String returned = objectReturn(call.desc);
                    if (isCustomPayload(returned, nodes)) out.add(returned);
                }
            }
        }
        return out;
    }

    private static Set<String> interactionSprites(ClassNode controller,
                                                  Map<String, ClassNode> nodes) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (controller == null || controller.methods == null) return out;
        for (MethodNode method : controller.methods) {
            if (method.desc == null
                    || !method.desc.contains("Lnet/minecraft/client/gui/GuiGraphics;")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field)
                        || field.getOpcode() != org.objectweb.asm.Opcodes.GETSTATIC) continue;
                String fieldType;
                try {
                    Type type = Type.getType(field.desc);
                    fieldType = type.getSort() == Type.OBJECT ? type.getInternalName() : null;
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (guiSpriteType(nodes.get(fieldType))) out.add(field.owner + '.' + field.name);
            }
        }
        return out;
    }

    private static boolean guiSpriteType(ClassNode node) {
        if (node == null || node.methods == null) return false;
        for (MethodNode method : node.methods) {
            if (method.desc == null
                    || !method.desc.contains("Lnet/minecraft/client/gui/GuiGraphics;")) continue;
            int integers = 0;
            try {
                for (Type argument : Type.getArgumentTypes(method.desc)) {
                    if (argument.getSort() == Type.INT) integers++;
                }
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (integers >= 2) return true;
        }
        return false;
    }

    private static int interactionHeight(ClassNode controller) {
        int best = -1;
        if (controller == null || controller.methods == null) return best;
        for (MethodNode method : controller.methods) {
            if (method.desc == null
                    || !method.desc.contains("Lnet/minecraft/client/gui/GuiGraphics;")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                Integer value = integerConstant(instruction);
                if (value == null || value < 16 || value > 256) continue;
                AbstractInsnNode next = instruction.getNext();
                while (next != null && next.getOpcode() < 0) next = next.getNext();
                if (next instanceof VarInsnNode variable
                        && variable.getOpcode() == org.objectweb.asm.Opcodes.ISTORE) {
                    best = Math.max(best, value);
                }
            }
        }
        return best;
    }

    private static Integer integerConstant(AbstractInsnNode instruction) {
        if (instruction instanceof IntInsnNode value) return value.operand;
        if (instruction instanceof LdcInsnNode value && value.cst instanceof Integer integer) {
            return integer;
        }
        int opcode = instruction == null ? -1 : instruction.getOpcode();
        if (opcode >= org.objectweb.asm.Opcodes.ICONST_M1
                && opcode <= org.objectweb.asm.Opcodes.ICONST_5) {
            return opcode - org.objectweb.asm.Opcodes.ICONST_0;
        }
        return null;
    }

    private static boolean isCustomPayload(String type, Map<String, ClassNode> nodes) {
        if (type == null) return false;
        HashSet<String> seen = new HashSet<>();
        ArrayList<String> pending = new ArrayList<>();
        pending.add(type);
        while (!pending.isEmpty()) {
            String current = pending.remove(pending.size() - 1);
            if (!seen.add(current)) continue;
            if (CUSTOM_PAYLOAD.equals(current)) return true;
            ClassNode node = nodes.get(current);
            if (node == null) continue;
            if (node.superName != null) pending.add(node.superName);
            if (node.interfaces != null) pending.addAll(node.interfaces);
        }
        return false;
    }

    private static boolean screenOpener(MethodInsnNode call) {
        if (call == null || call.desc == null) return false;
        String name = call.name == null ? "" : call.name.toLowerCase(java.util.Locale.ROOT);
        boolean acceptsScreen = call.desc.contains("Lnet/minecraft/client/gui/screens/Screen;");
        if (!acceptsScreen || !call.desc.endsWith(")V")) return false;
        String owner = call.owner == null ? "" : call.owner.toLowerCase(java.util.Locale.ROOT);
        return owner.equals("net/minecraft/client/minecraft")
                || name.contains("open") || name.contains("screen") || name.startsWith("set");
    }

    private static boolean shouldTraceHelper(String currentOwner, MethodInsnNode call,
                                             String returned, Map<String, ClassNode> nodes) {
        if (call == null || call.owner == null || !nodes.containsKey(call.owner)
                || "<init>".equals(call.name)) return false;
        if (call.owner.equals(currentOwner) || isScreen(returned, nodes)) return true;
        String semantic = (call.owner + '/' + call.name).toLowerCase(java.util.Locale.ROOT);
        return semantic.contains("screen") || semantic.contains("gui")
                || semantic.contains("menu") || semantic.contains("open");
    }

    private static MethodNode findMethod(ClassNode owner, String name, String descriptor) {
        if (owner == null || owner.methods == null) return null;
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
        }
        return null;
    }

    private static String objectReturn(String descriptor) {
        if (descriptor == null) return null;
        try {
            Type type = Type.getReturnType(descriptor);
            return type.getSort() == Type.OBJECT ? type.getInternalName() : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isScreen(String type, Map<String, ClassNode> nodes) {
        return type != null && (inheritsAny(type, SCREEN_ROOTS, nodes)
                || screenNamedType(type, nodes));
    }

    /**
     * Embedded/client libraries are often separate jars, so the archive-local hierarchy can stop
     * at an unresolved Abstract*Screen base. The containing method has already been proven to pass
     * a Screen to a typed opener, making a screen-named construction a safe route candidate.
     */
    private static boolean screenNamedType(String type, Map<String, ClassNode> nodes) {
        if (type == null) return false;
        HashSet<String> seen = new HashSet<>();
        String current = type;
        while (current != null && seen.add(current)) {
            String simple = current.substring(current.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
            if (simple.endsWith("screen") || simple.contains("screen$")) return true;
            ClassNode node = nodes.get(current);
            if (node == null) return false;
            current = node.superName;
        }
        return false;
    }

    private static boolean inheritsAny(String type, Set<String> roots,
                                       Map<String, ClassNode> nodes) {
        if (type == null) return false;
        HashSet<String> seen = new HashSet<>();
        String current = type;
        while (current != null && seen.add(current)) {
            if (roots.contains(current)) return true;
            ClassNode node = nodes.get(current);
            if (node == null) return false;
            current = node.superName;
        }
        return false;
    }
}
