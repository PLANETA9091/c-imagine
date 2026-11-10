package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recovers atlas-backed GUI sprites and the screens that select them.
 *
 * <p>Many UI libraries expose enum/static objects constructed as
 * {@code (texture, u, v, width, height)} and screens assign one of those objects to a background
 * field. Reading that bytecode is enough to crop the actual authored screen from its source PNG;
 * no mod id, screen name, framebuffer capture or client class loading is required.</p>
 */
public final class GuiSpriteBytecodeScanner {

    public static final class Sprite {
        public String owner;
        public String field;
        public String texture;
        public int u;
        public int v;
        public int width;
        public int height;
        /** Optional pre-baked runtime crop. Unlike an ordinary static sprite field, this crop is
         * selected by a live object's atlas id plus an integer variant at render time. */
        public String runtimeKey;
        public String runtimeId;
        public int runtimeOffset = Integer.MIN_VALUE;
        public String runtimeOffsetMethod;
        public String runtimeWidthMethod;

        @Override public String toString() {
            return owner + '.' + field + " -> " + texture + '[' + u + ',' + v + ' '
                    + width + 'x' + height + ']';
        }
    }

    public static final class ScreenBackground {
        public String screenClass;
        public String spriteOwner;
        public String spriteField;
    }

    /** One explicit sprite draw performed by a screen render method. Coordinates are relative to
     * the screen's gui-left/gui-top origin. Keeping these operations separate from the base crop is
     * important for screens that compose their authored panel from several atlas regions. */
    public static final class ScreenLayer {
        public String screenClass;
        public String spriteOwner;
        public String spriteField;
        /** Registry-backed item/block drawn directly by GuiGraphics instead of from an atlas. */
        public String itemId;
        public String itemRegistryKind;
        public int x;
        public int y;
        public int width;
        public int height;
        public boolean stretched;
        /** Direct ResourceLocation blits which are known to be only a fragment of a dynamic
         * screen must never be promoted to the base background merely because they are the largest
         * fixed draw discovered for that screen. */
        public boolean partialOnly;
        /** True unless normal control flow proves this draw occurs unconditionally. */
        public boolean conditional;
        public boolean predicateAnalyzed;
        public RendererBytecodeScanner.BooleanPredicate visiblePredicate;
    }

    public static final class Result {
        public final List<Sprite> sprites = new ArrayList<>();
        public final List<ScreenBackground> backgrounds = new ArrayList<>();
        public final List<ScreenLayer> layers = new ArrayList<>();
    }

    private GuiSpriteBytecodeScanner() {}

    public static Result scan(Map<String, ClassNode> nodes) {
        return scan(nodes, null);
    }

    public static Result scan(Map<String, ClassNode> nodes, String namespace) {
        Result result = new Result();
        if (nodes == null || nodes.isEmpty()) return result;
        Map<String, Sprite> sprites = new LinkedHashMap<>();
        for (ClassNode node : nodes.values()) collectSprites(node, sprites);
        for (ClassNode node : nodes.values()) collectRuntimeAtlasSegments(node, sprites);
        for (ClassNode node : nodes.values()) collectBackgrounds(node, sprites, result.backgrounds, nodes);
        for (ClassNode node : nodes.values()) collectLayers(node, sprites, result.layers, nodes);
        Map<String, String> directResources = directResourceFields(nodes, namespace);
        collectDirectResourceLayers(nodes, directResources, sprites, result.layers, result.backgrounds);
        collectConstructorResourceBackgrounds(nodes, directResources, sprites,
                result.layers, result.backgrounds);
        inheritScreenComposition(nodes, result.layers, result.backgrounds);
        collectRegistryItemLayers(nodes, namespace, result.layers);
        result.sprites.addAll(sprites.values());
        inferRenderedBackgrounds(result, sprites);
        inferLowLevelBlitBackgrounds(nodes, result, sprites);
        return result;
    }

    /** Finds a scanner-proven atlas crop for one live icon/segment object. The object class and
     * registry id make the lookup exact; offset/width are obtained from the same live helper
     * methods that its native renderer calls, so mods can add new spacing values without F2V
     * knowing their semantic names. */
    public static Sprite runtimeAtlasSegment(Collection<ModScanner.Profile> profiles,
                                             String owner, String runtimeId,
                                             int offset, int width) {
        if (profiles == null || owner == null || runtimeId == null || width <= 0) return null;
        String wantedOwner = normalize(owner);
        String wantedId = resourcePath(runtimeId);
        for (ModScanner.Profile profile : profiles) {
            Sprite found = runtimeAtlasSegment(profile, wantedOwner, wantedId, offset, width);
            if (found != null) return found;
        }
        return null;
    }

    public static Sprite runtimeAtlasFamily(Collection<ModScanner.Profile> profiles,
                                            String owner, String runtimeId) {
        if (profiles == null || owner == null || runtimeId == null) return null;
        String wantedOwner = normalize(owner);
        String wantedId = resourcePath(runtimeId);
        for (ModScanner.Profile profile : profiles) {
            Sprite found = runtimeAtlasFamily(profile, wantedOwner, wantedId);
            if (found != null) return found;
        }
        return null;
    }

    private static Sprite runtimeAtlasFamily(ModScanner.Profile profile, String owner, String runtimeId) {
        if (profile == null) return null;
        if (profile.guiSprites != null) {
            for (Sprite sprite : profile.guiSprites) {
                if (sprite == null || sprite.runtimeKey == null || sprite.runtimeId == null) continue;
                if (owner.equals(normalize(sprite.owner))
                        && runtimeId.equals(resourcePath(sprite.runtimeId))) return sprite;
            }
        }
        if (profile.nested != null) for (ModScanner.Profile child : profile.nested) {
            Sprite found = runtimeAtlasFamily(child, owner, runtimeId);
            if (found != null) return found;
        }
        return null;
    }

    private static Sprite runtimeAtlasSegment(ModScanner.Profile profile, String owner,
                                              String runtimeId, int offset, int width) {
        if (profile == null) return null;
        if (profile.guiSprites != null) {
            for (Sprite sprite : profile.guiSprites) {
                if (sprite == null || sprite.runtimeKey == null || sprite.runtimeId == null) continue;
                if (owner.equals(normalize(sprite.owner))
                        && runtimeId.equals(resourcePath(sprite.runtimeId))
                        && sprite.runtimeOffset == offset && sprite.width == width) return sprite;
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                Sprite found = runtimeAtlasSegment(child, owner, runtimeId, offset, width);
                if (found != null) return found;
            }
        }
        return null;
    }

    public static String runtimeAtlasAssetKey(String owner, String runtimeId,
                                              int offset, int width, int height) {
        String id = resourcePath(runtimeId).replaceAll("[^a-zA-Z0-9_./-]", "_");
        return normalize(owner) + "#atlas:" + id + '@' + offset + ':' + width + 'x' + height;
    }

    public static Sprite background(Collection<ModScanner.Profile> profiles, String screenClass) {
        if (profiles == null || screenClass == null) return null;
        String wanted = screenClass.replace('.', '/');
        for (ModScanner.Profile profile : profiles) {
            Sprite found = background(profile, wanted);
            if (found != null) return found;
        }
        return null;
    }

    /** Exact sprite draws owned by one screen, including nested sidecar profiles. */
    public static List<ScreenLayer> layersForScreen(Collection<ModScanner.Profile> profiles,
                                                    String screenClass) {
        if (profiles == null || screenClass == null) return List.of();
        String wanted = normalize(screenClass);
        ArrayList<ScreenLayer> out = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collectLayers(profile, wanted, out);
        return out;
    }

    private static void collectLayers(ModScanner.Profile profile, String screenClass,
                                      List<ScreenLayer> out) {
        if (profile == null) return;
        if (profile.guiLayers != null) {
            for (ScreenLayer layer : profile.guiLayers) {
                if (layer != null && screenClass.equals(normalize(layer.screenClass))) out.add(layer);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collectLayers(child, screenClass, out);
        }
    }

    /** Stable TileAssets key for one separately baked runtime layer. */
    public static String layerAssetKey(ScreenLayer layer) {
        if (layer == null) return "";
        if (layer.itemId != null) {
            return normalize(layer.screenClass) + "#item:" + layer.itemId + '@'
                    + layer.x + ',' + layer.y + ':' + layer.width + 'x' + layer.height;
        }
        return normalize(layer.screenClass) + "#layer:"
                + normalize(layer.spriteOwner) + '.' + String.valueOf(layer.spriteField)
                + '@' + layer.x + ',' + layer.y + ':' + layer.width + 'x' + layer.height;
    }

    private static Sprite background(ModScanner.Profile profile, String screenClass) {
        if (profile == null) return null;
        Sprite best = null;
        long bestArea = -1;
        if (profile.guiBackgrounds != null && profile.guiSprites != null) {
            for (ScreenBackground background : profile.guiBackgrounds) {
                if (background == null || !screenClass.equals(normalize(background.screenClass))) continue;
                for (Sprite sprite : profile.guiSprites) {
                    if (sprite != null && normalize(sprite.owner).equals(normalize(background.spriteOwner))
                            && java.util.Objects.equals(sprite.field, background.spriteField)) {
                        // A screen can keep several static aliases (base panel, paper/tab overlays,
                        // decorative sheets). The base surface is the largest authored footprint;
                        // returning whichever HashMap/class traversal happened to encounter first
                        // made the result nondeterministic and could select a small overlay.
                        long area = (long) sprite.width * sprite.height;
                        if (area > bestArea) {
                            best = sprite;
                            bestArea = area;
                        }
                    }
                }
            }
        }
        if (best != null) return best;
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                Sprite found = background(child, screenClass);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void collectSprites(ClassNode node, Map<String, Sprite> output) {
        if (node == null || node.methods == null) return;
        MethodNode clinit = null;
        for (MethodNode method : node.methods) if ("<clinit>".equals(method.name)) clinit = method;
        if (clinit == null) return;
        MethodInsnNode lastConstructor = null;
        for (AbstractInsnNode instruction : clinit.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && "<init>".equals(call.name) && call.owner.equals(node.name)) {
                lastConstructor = call;
                continue;
            }
            if (!(instruction instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.PUTSTATIC || !field.owner.equals(node.name)
                    || lastConstructor == null) continue;
            Sprite sprite = sprite(node.name, field.name, lastConstructor);
            if (sprite != null) output.put(node.name + '.' + field.name, sprite);
            lastConstructor = null;
        }
        collectGridAtlasSprites(node, clinit, output);
    }

    /**
     * Recovers runtime-selected atlas slices. The accepted bytecode shape is deliberately
     * structural: an instance render method receives an integer variant and GuiGraphics, asks two
     * same-owner {@code (I)I} helpers for offset/width, and blits a fixed-height rectangle from a
     * ResourceLocation instance field. Static initialization registers instances with
     * {@code (id, sheet, baseX, baseY)}. Create's train silhouettes use this pattern, but nothing
     * here references train/create names and other mods using the same atlas idiom get the same
     * exact-crop support.
     */
    private static void collectRuntimeAtlasSegments(ClassNode node, Map<String, Sprite> output) {
        if (node == null || node.methods == null || output == null) return;
        MethodNode render = null;
        MethodNode offsetMethod = null;
        MethodNode widthMethod = null;
        int height = -1;
        for (MethodNode method : node.methods) {
            if (method == null || method.instructions == null || method.desc == null) continue;
            Type[] args;
            Type returned;
            try {
                args = Type.getArgumentTypes(method.desc);
                returned = Type.getReturnType(method.desc);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (returned.getSort() != Type.INT || args.length < 2 || args[0].getSort() != Type.INT) continue;
            boolean graphics = false;
            for (Type arg : args) {
                if (arg.getSort() == Type.OBJECT
                        && "net/minecraft/client/gui/GuiGraphics".equals(arg.getInternalName())) graphics = true;
            }
            if (!graphics) continue;
            int widthLocal = returnedLocal(method);
            if (widthLocal < 0) continue;
            MethodInsnNode widthCall = helperStoredTo(method, node.name, widthLocal);
            if (widthCall == null) continue;
            MethodInsnNode other = null;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || call == widthCall
                        || !node.name.equals(call.owner) || !"(I)I".equals(call.desc)) continue;
                other = call;
                break;
            }
            if (other == null || !hasRuntimeAtlasBlit(method)) continue;
            int inferredHeight = fixedHeightAfterWidthLoad(method, widthLocal);
            if (inferredHeight <= 0 || inferredHeight > 256) continue;
            render = method;
            widthMethod = findMethod(node, widthCall.name, widthCall.desc);
            offsetMethod = findMethod(node, other.name, other.desc);
            height = inferredHeight;
            break;
        }
        if (render == null || widthMethod == null || offsetMethod == null || height <= 0) return;

        MethodNode clinit = findMethod(node, "<clinit>");
        if (clinit == null || clinit.instructions == null) return;
        MethodInsnNode registration = runtimeAtlasRegistrationCall(node, clinit);
        if (registration == null) return;
        Map<Integer, String> resourceLocals = resourceLocationLocals(clinit);
        java.util.LinkedHashSet<Integer> samples = intMethodSamples(offsetMethod, widthMethod);
        if (samples.isEmpty()) return;
        for (AbstractInsnNode instruction : clinit.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !node.name.equals(call.owner)
                    || !registration.name.equals(call.name) || !registration.desc.equals(call.desc)) continue;
            RuntimeAtlasRegistration args = runtimeAtlasRegistrationArgs(
                    clinit, call, registration, resourceLocals);
            if (args == null) continue;
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            for (int variant : samples) {
                Integer offset = evaluateIntHelper(offsetMethod, variant);
                Integer width = evaluateIntHelper(widthMethod, variant);
                if (offset == null || width == null || offset < 0 || width <= 0 || width > 512) continue;
                String geometry = offset + ":" + width;
                if (!seen.add(geometry)) continue;
                Sprite sprite = new Sprite();
                sprite.owner = node.name;
                sprite.field = "$runtime_atlas$" + resourcePath(args.id()) + '$' + offset + '$' + width;
                sprite.texture = args.sheet();
                sprite.u = args.baseX() + offset;
                sprite.v = args.baseY();
                sprite.width = width;
                sprite.height = height;
                sprite.runtimeId = resourcePath(args.id());
                sprite.runtimeOffset = offset;
                sprite.runtimeOffsetMethod = offsetMethod.name;
                sprite.runtimeWidthMethod = widthMethod.name;
                sprite.runtimeKey = runtimeAtlasAssetKey(node.name, sprite.runtimeId,
                        offset, width, height);
                output.putIfAbsent(node.name + '.' + sprite.field, sprite);
            }
        }
    }

    private record RuntimeAtlasRegistration(String id, String sheet, int baseX, int baseY) {}

    private static MethodInsnNode runtimeAtlasRegistrationCall(ClassNode node, MethodNode clinit) {
        for (AbstractInsnNode instruction : clinit.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC || !node.name.equals(call.owner)) continue;
            try {
                Type[] args = Type.getArgumentTypes(call.desc);
                if (args.length != 4 || Type.getReturnType(call.desc).getSort() != Type.VOID
                        || args[0].getSort() != Type.OBJECT || args[1].getSort() != Type.OBJECT
                        || !"net/minecraft/resources/ResourceLocation".equals(args[0].getInternalName())
                        || !"net/minecraft/resources/ResourceLocation".equals(args[1].getInternalName())
                        || args[2].getSort() != Type.INT || args[3].getSort() != Type.INT) continue;
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            return call;
        }
        return null;
    }

    private static Map<Integer, String> resourceLocationLocals(MethodNode clinit) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (AbstractInsnNode instruction : clinit.instructions) {
            if (!(instruction instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String value)) continue;
            AbstractInsnNode callInsn = nextReal(instruction);
            if (!(callInsn instanceof MethodInsnNode call) || !objectReturn(call.desc,
                    "net/minecraft/resources/ResourceLocation")) continue;
            AbstractInsnNode storeInsn = nextReal(callInsn);
            if (storeInsn instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE) {
                out.put(store.var, value);
            }
        }
        return out;
    }

    private static RuntimeAtlasRegistration runtimeAtlasRegistrationArgs(
            MethodNode method, MethodInsnNode call, MethodInsnNode registration,
            Map<Integer, String> resourceLocals) {
        Integer y = null, x = null;
        String sheet = null, id = null;
        int remaining = 36;
        for (AbstractInsnNode cursor = call.getPrevious(); cursor != null && remaining-- > 0;
             cursor = cursor.getPrevious()) {
            if (cursor.getOpcode() < 0) continue;
            if (cursor instanceof MethodInsnNode previous && previous != call
                    && previous.getOpcode() == Opcodes.INVOKESTATIC
                    && previous.owner.equals(registration.owner)
                    && previous.name.equals(registration.name) && previous.desc.equals(registration.desc)) break;
            Integer constant = integer(cursor);
            if (constant != null) {
                if (y == null) y = constant;
                else if (x == null) x = constant;
            }
            if (sheet == null && cursor instanceof VarInsnNode load
                    && load.getOpcode() == Opcodes.ALOAD) sheet = resourceLocals.get(load.var);
            if (id == null && cursor instanceof LdcInsnNode ldc && ldc.cst instanceof String value
                    && !value.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) id = value;
            if (x != null && y != null && sheet != null && id != null) break;
        }
        return x == null || y == null || sheet == null || id == null
                ? null : new RuntimeAtlasRegistration(id, sheet, x, y);
    }

    private static int returnedLocal(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getLast(); instruction != null;
             instruction = instruction.getPrevious()) {
            if (instruction.getOpcode() != Opcodes.IRETURN) continue;
            AbstractInsnNode previous = previousReal(instruction, 1);
            if (previous instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD) return load.var;
        }
        return -1;
    }

    private static MethodInsnNode helperStoredTo(MethodNode method, String owner, int local) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || !owner.equals(call.owner)
                    || !"(I)I".equals(call.desc)) continue;
            AbstractInsnNode next = nextReal(call);
            if (next instanceof VarInsnNode store && store.getOpcode() == Opcodes.ISTORE
                    && store.var == local) return call;
        }
        return null;
    }

    private static int fixedHeightAfterWidthLoad(MethodNode method, int widthLocal) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ILOAD
                    || load.var != widthLocal) continue;
            Integer value = integer(nextReal(instruction));
            if (value != null && value > 0) return value;
        }
        return -1;
    }

    private static boolean hasRuntimeAtlasBlit(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !"net/minecraft/client/gui/GuiGraphics".equals(call.owner)) continue;
            try {
                Type[] args = Type.getArgumentTypes(call.desc);
                boolean resource = false;
                int integers = 0;
                for (Type arg : args) {
                    if (arg.getSort() == Type.INT) integers++;
                    if (arg.getSort() == Type.OBJECT
                            && "net/minecraft/resources/ResourceLocation".equals(arg.getInternalName())) {
                        resource = true;
                    }
                }
                if (resource && integers >= 6) return true;
            } catch (IllegalArgumentException ignored) {
            }
        }
        return false;
    }

    private static java.util.LinkedHashSet<Integer> intMethodSamples(MethodNode... methods) {
        java.util.LinkedHashSet<Integer> out = new java.util.LinkedHashSet<>();
        out.add(-2); out.add(-1); out.add(0); out.add(1);
        for (MethodNode method : methods) {
            if (method == null || method.instructions == null) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                Integer value = integer(instruction);
                if (value == null || value < -128 || value > 512) continue;
                for (int delta = -1; delta <= 1; delta++) out.add(value + delta);
            }
        }
        return out;
    }

    /** Tiny verifier-safe interpreter for integer branch ladders such as atlas offset/width
     * selectors. Unsupported bytecode returns null rather than guessing. */
    private static Integer evaluateIntHelper(MethodNode method, int argument) {
        if (method == null || method.instructions == null) return null;
        Deque<Integer> stack = new ArrayDeque<>();
        int ip = 0;
        int steps = 0;
        while (ip >= 0 && ip < method.instructions.size() && steps++ < 256) {
            AbstractInsnNode instruction = method.instructions.get(ip);
            int opcode = instruction.getOpcode();
            if (opcode < 0) { ip++; continue; }
            Integer constant = integer(instruction);
            if (constant != null) { stack.push(constant); ip++; continue; }
            if (instruction instanceof VarInsnNode var && opcode == Opcodes.ILOAD) {
                stack.push(var.var == 1 ? argument : 0);
                ip++;
                continue;
            }
            if (opcode == Opcodes.IRETURN) return stack.isEmpty() ? null : stack.pop();
            if (instruction instanceof JumpInsnNode jump) {
                boolean branch;
                if (opcode == Opcodes.GOTO) branch = true;
                else if (opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ICMPLE) {
                    if (stack.size() < 2) return null;
                    int right = stack.pop(), left = stack.pop();
                    branch = switch (opcode) {
                        case Opcodes.IF_ICMPEQ -> left == right;
                        case Opcodes.IF_ICMPNE -> left != right;
                        case Opcodes.IF_ICMPLT -> left < right;
                        case Opcodes.IF_ICMPGE -> left >= right;
                        case Opcodes.IF_ICMPGT -> left > right;
                        case Opcodes.IF_ICMPLE -> left <= right;
                        default -> false;
                    };
                } else if (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IFLE) {
                    if (stack.isEmpty()) return null;
                    int value = stack.pop();
                    branch = switch (opcode) {
                        case Opcodes.IFEQ -> value == 0;
                        case Opcodes.IFNE -> value != 0;
                        case Opcodes.IFLT -> value < 0;
                        case Opcodes.IFGE -> value >= 0;
                        case Opcodes.IFGT -> value > 0;
                        case Opcodes.IFLE -> value <= 0;
                        default -> false;
                    };
                } else return null;
                ip = branch ? method.instructions.indexOf(jump.label) : ip + 1;
                continue;
            }
            return null;
        }
        return null;
    }

    private static boolean objectReturn(String descriptor, String internalName) {
        try {
            Type returned = Type.getReturnType(descriptor);
            return returned.getSort() == Type.OBJECT && internalName.equals(returned.getInternalName());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static MethodNode findMethod(ClassNode owner, String name, String descriptor) {
        if (owner == null || owner.methods == null || name == null || descriptor == null) return null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        }
        return null;
    }

    private static String resourcePath(String value) {
        if (value == null) return "";
        String normalized = value.replace('\\', '/');
        int colon = normalized.indexOf(':');
        return colon >= 0 ? normalized.substring(colon + 1) : normalized;
    }

    /**
     * Recovers icon atlases whose static fields are created through tiny cursor factories instead
     * of direct {@code new Sprite(texture,u,v,w,h)} calls. A common library shape keeps static
     * integer x/y cursors, exposes {@code next()/newRow()} helpers returning an object constructed
     * from those cursors, and multiplies the constructor coordinates by a fixed cell size. The
     * renderer then blits a fixed cell from one atlas ResourceLocation.
     *
     * <p>This is intentionally a small integer abstract interpreter rather than a class/name
     * heuristic. It only accepts a two-int constructor with fixed pixel multipliers, one authored
     * PNG atlas path, and same-owner static factories whose bytecode can be evaluated using integer
     * constants/arithmetic/static fields. Unknown instructions lose the value instead of guessing.</p>
     */
    private static void collectGridAtlasSprites(ClassNode node, MethodNode clinit,
                                                Map<String, Sprite> output) {
        if (node == null || clinit == null || output == null) return;
        int[] cell = gridCellSize(node);
        String texture = gridAtlasTexture(clinit);
        if (cell == null || texture == null) return;

        Map<String, Integer> staticInts = new LinkedHashMap<>();
        Map<String, MethodNode> factories = new LinkedHashMap<>();
        for (MethodNode method : node.methods) {
            if (method == null || method.desc == null || "<init>".equals(method.name)
                    || "<clinit>".equals(method.name)) continue;
            try {
                Type returned = Type.getReturnType(method.desc);
                if ((method.access & Opcodes.ACC_STATIC) != 0
                        && returned.getSort() == Type.OBJECT
                        && node.name.equals(returned.getInternalName())) {
                    factories.put(method.name + method.desc, method);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (factories.isEmpty()) return;

        Deque<GridValue> stack = new ArrayDeque<>();
        for (AbstractInsnNode instruction : clinit.instructions) {
            if (instruction.getOpcode() < 0) continue;
            if (pushGridConstant(instruction, stack)) continue;
            int opcode = instruction.getOpcode();
            if (instruction instanceof FieldInsnNode field) {
                if (opcode == Opcodes.GETSTATIC && "I".equals(field.desc)) {
                    stack.push(GridValue.integer(staticInts.get(field.owner + '.' + field.name)));
                    continue;
                }
                if (opcode == Opcodes.PUTSTATIC) {
                    GridValue value = popGrid(stack);
                    if ("I".equals(field.desc) && field.owner.equals(node.name)) {
                        if (value.integer != null) staticInts.put(field.owner + '.' + field.name, value.integer);
                        continue;
                    }
                    if (field.owner.equals(node.name) && ("L" + node.name + ";").equals(field.desc)
                            && value.coords != null && value.coords.length >= 2) {
                        Sprite sprite = new Sprite();
                        sprite.owner = node.name;
                        sprite.field = field.name;
                        sprite.texture = texture;
                        sprite.u = value.coords[0] * cell[0];
                        sprite.v = value.coords[1] * cell[1];
                        sprite.width = cell[0];
                        sprite.height = cell[1];
                        if (sprite.u >= 0 && sprite.v >= 0) {
                            output.putIfAbsent(node.name + '.' + field.name, sprite);
                        }
                    }
                    continue;
                }
            }
            if (instruction instanceof MethodInsnNode call && opcode == Opcodes.INVOKESTATIC
                    && call.owner.equals(node.name)) {
                MethodNode factory = factories.get(call.name + call.desc);
                if (factory != null) {
                    stack.push(evaluateGridFactory(node, factory, staticInts));
                    continue;
                }
            }
            applyGridStackInstruction(node, instruction, stack, staticInts, null);
        }
    }

    private record GridValue(Integer integer, int[] coords, boolean object) {
        static GridValue integer(Integer value) { return new GridValue(value, null, false); }
        static GridValue object(int[] coords) { return new GridValue(null, coords, true); }
        static GridValue unknown() { return new GridValue(null, null, false); }
    }

    private static GridValue evaluateGridFactory(ClassNode owner, MethodNode method,
                                                 Map<String, Integer> staticInts) {
        Deque<GridValue> stack = new ArrayDeque<>();
        int[][] constructed = new int[1][];
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() < 0) continue;
            if (pushGridConstant(instruction, stack)) continue;
            if (instruction.getOpcode() == Opcodes.ARETURN) return popGrid(stack);
            applyGridStackInstruction(owner, instruction, stack, staticInts, constructed);
        }
        return constructed[0] == null ? GridValue.unknown() : GridValue.object(constructed[0]);
    }

    private static void applyGridStackInstruction(ClassNode owner, AbstractInsnNode instruction,
                                                  Deque<GridValue> stack,
                                                  Map<String, Integer> staticInts,
                                                  int[][] constructed) {
        int opcode = instruction.getOpcode();
        if (opcode == Opcodes.DUP) {
            stack.push(stack.isEmpty() ? GridValue.unknown() : stack.peek());
            return;
        }
        if (opcode == Opcodes.POP) {
            popGrid(stack);
            return;
        }
        if (opcode == Opcodes.IADD || opcode == Opcodes.ISUB || opcode == Opcodes.IMUL) {
            GridValue right = popGrid(stack);
            GridValue left = popGrid(stack);
            Integer value = left.integer == null || right.integer == null ? null
                    : opcode == Opcodes.IADD ? left.integer + right.integer
                    : opcode == Opcodes.ISUB ? left.integer - right.integer
                    : left.integer * right.integer;
            stack.push(GridValue.integer(value));
            return;
        }
        if (instruction instanceof FieldInsnNode field) {
            if (opcode == Opcodes.GETSTATIC && "I".equals(field.desc)) {
                stack.push(GridValue.integer(staticInts.get(field.owner + '.' + field.name)));
                return;
            }
            if (opcode == Opcodes.PUTSTATIC && "I".equals(field.desc)) {
                GridValue value = popGrid(stack);
                if (field.owner.equals(owner.name) && value.integer != null) {
                    staticInts.put(field.owner + '.' + field.name, value.integer);
                }
                return;
            }
        }
        if (instruction instanceof TypeInsnNode type && opcode == Opcodes.NEW
                && type.desc.equals(owner.name)) {
            stack.push(GridValue.object(null));
            return;
        }
        if (instruction instanceof MethodInsnNode call) {
            Type[] args;
            Type returned;
            try {
                args = Type.getArgumentTypes(call.desc);
                returned = Type.getReturnType(call.desc);
            } catch (IllegalArgumentException ignored) {
                stack.clear();
                return;
            }
            if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(call.name)
                    && call.owner.equals(owner.name) && args.length == 2
                    && args[0].getSort() == Type.INT && args[1].getSort() == Type.INT) {
                GridValue y = popGrid(stack);
                GridValue x = popGrid(stack);
                popGrid(stack); // constructor receiver; a DUPed receiver remains below it
                int[] coords = x.integer == null || y.integer == null ? null
                        : new int[]{x.integer, y.integer};
                if (constructed != null) constructed[0] = coords;
                if (!stack.isEmpty() && stack.peek().object) {
                    stack.pop();
                    stack.push(GridValue.object(coords));
                }
                return;
            }
            int consumed = args.length + (opcode == Opcodes.INVOKESTATIC ? 0 : 1);
            for (int i = 0; i < consumed; i++) popGrid(stack);
            if (returned.getSort() != Type.VOID) stack.push(GridValue.unknown());
        }
    }

    private static boolean pushGridConstant(AbstractInsnNode instruction, Deque<GridValue> stack) {
        Integer value = integerConstant(instruction);
        if (value == null) return false;
        stack.push(GridValue.integer(value));
        return true;
    }

    private static GridValue popGrid(Deque<GridValue> stack) {
        return stack.isEmpty() ? GridValue.unknown() : stack.pop();
    }

    private static int[] gridCellSize(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!"<init>".equals(method.name) || !"(II)V".equals(method.desc)) continue;
            Integer first = null, second = null;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.PUTFIELD || !"I".equals(field.desc)) continue;
                AbstractInsnNode mul = previousReal(instruction, 1);
                AbstractInsnNode constant = previousReal(instruction, 2);
                AbstractInsnNode load = previousReal(instruction, 3);
                if (mul == null || mul.getOpcode() != Opcodes.IMUL
                        || !(load instanceof VarInsnNode var) || var.getOpcode() != Opcodes.ILOAD) continue;
                Integer size = integerConstant(constant);
                if (size == null || size <= 0 || size > 1024) continue;
                if (var.var == 1) first = size;
                else if (var.var == 2) second = size;
            }
            if (first != null && second != null) return new int[]{first, second};
        }
        return null;
    }

    private static String gridAtlasTexture(MethodNode clinit) {
        String candidate = null;
        for (AbstractInsnNode instruction : clinit.instructions) {
            if (!(instruction instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String value)) continue;
            String normalized = value.replace('\\', '/');
            if (!normalized.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) continue;
            if (candidate != null && !candidate.equals(normalized)) return null;
            candidate = normalized;
        }
        return candidate;
    }

    private static Integer integerConstant(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        int opcode = instruction.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return opcode - Opcodes.ICONST_0;
        }
        if (instruction instanceof IntInsnNode integer
                && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) return integer.operand;
        if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) return value;
        return null;
    }

    private static Sprite sprite(String owner, String field, MethodInsnNode constructor) {
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(constructor.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        int integerArguments = 0;
        boolean hasString = false;
        for (Type argument : arguments) {
            if (argument.getSort() == Type.INT) integerArguments++;
            if (argument.getSort() == Type.OBJECT
                    && "java/lang/String".equals(argument.getInternalName())) hasString = true;
        }
        if (!hasString || integerArguments < 2) return null;

        ArrayList<Object> constants = new ArrayList<>();
        for (AbstractInsnNode cursor = constructor.getPrevious(); cursor != null;
             cursor = cursor.getPrevious()) {
            if (cursor instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals(owner)) break;
            Object value = constant(cursor);
            if (value != null) constants.add(0, value);
        }
        ArrayList<Integer> integers = new ArrayList<>();
        String texture = null;
        for (Object value : constants) {
            if (value instanceof String string && !string.isBlank()) {
                // Enum sprite constructors commonly begin with the synthetic enum name/ordinal,
                // followed by the actual texture id and then (u,v,w,h[,atlasW,atlasH]).  Keeping
                // integers collected before the final string made the enum ordinal participate in
                // the crop, while taking the last four integers made atlas dimensions replace the
                // authored crop.  The last string is the texture argument; only integer constants
                // after that point belong to its geometry.
                texture = string;
                integers.clear();
            } else if (value instanceof Integer integer) {
                integers.add(integer);
            }
        }
        if (texture == null || integers.size() < 2) return null;
        Sprite sprite = new Sprite();
        sprite.owner = owner;
        sprite.field = field;
        sprite.texture = texture;
        if (integers.size() >= 4) {
            sprite.u = integers.get(0);
            sprite.v = integers.get(1);
            sprite.width = integers.get(2);
            sprite.height = integers.get(3);
        } else {
            sprite.u = 0;
            sprite.v = 0;
            sprite.width = integers.get(integers.size() - 2);
            sprite.height = integers.get(integers.size() - 1);
        }
        if (sprite.u < 0 || sprite.v < 0 || sprite.width <= 0 || sprite.height <= 0
                || sprite.width > 8192 || sprite.height > 8192) return null;
        return sprite;
    }

    private static void collectBackgrounds(ClassNode node, Map<String, Sprite> sprites,
                                           List<ScreenBackground> output,
                                           Map<String, ClassNode> nodes) {
        if (node == null || node.methods == null) return;
        collectStaticSpriteAliases(node, sprites, output);
        for (MethodNode method : node.methods) {
            if (!"<init>".equals(method.name)) continue;
            FieldInsnNode selected = null;
            int remaining = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETSTATIC
                        && sprites.containsKey(field.owner + '.' + field.name)) {
                    selected = field;
                    remaining = 8;
                    continue;
                }
                if (selected == null || remaining-- <= 0) {
                    selected = null;
                    continue;
                }
                if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTFIELD
                        && java.util.Objects.equals(field.desc, selected.desc)) {
                    ScreenBackground background = new ScreenBackground();
                    background.screenClass = node.name;
                    background.spriteOwner = selected.owner;
                    background.spriteField = selected.name;
                    output.add(background);
                    selected = null;
                }
            }
            collectConstructorPassedBackground(node, method, sprites, output, nodes);
        }
    }

    /**
     * Reusable screen bases frequently own the background field while concrete screens merely pass
     * an authored sprite to {@code super(..., sprite)}. Following that exact constructor operand is
     * stronger than guessing from either class or texture names and lets every subclass retain the
     * sprite it actually selected (filters, zappers and similar UI families).
     */
    private static void collectConstructorPassedBackground(ClassNode node, MethodNode constructor,
                                                           Map<String, Sprite> sprites,
                                                           List<ScreenBackground> output,
                                                           Map<String, ClassNode> nodes) {
        if (node == null || constructor == null || sprites == null || sprites.isEmpty()) return;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (!(instruction instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETSTATIC
                    || !sprites.containsKey(field.owner + '.' + field.name)) continue;
            int remaining = 20;
            for (AbstractInsnNode cursor = instruction.getNext(); cursor != null && remaining-- > 0;
                 cursor = cursor.getNext()) {
                if (!(cursor instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || !"<init>".equals(call.name)) continue;
                if (!isAncestor(node, call.owner, nodes)) break;
                boolean acceptsSprite = false;
                try {
                    for (Type argument : Type.getArgumentTypes(call.desc)) {
                        if (argument.getSort() == Type.OBJECT
                                && field.desc.equals(argument.getDescriptor())) {
                            acceptsSprite = true;
                            break;
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                }
                if (!acceptsSprite) break;
                addBackground(output, node.name, field.owner, field.name);
                break;
            }
        }
    }

    private static boolean isAncestor(ClassNode node, String owner, Map<String, ClassNode> nodes) {
        if (node == null || owner == null) return false;
        String parent = node.superName;
        int depth = 0;
        while (parent != null && depth++ < 16) {
            if (owner.equals(parent)) return true;
            ClassNode parentNode = nodes == null ? null : nodes.get(parent);
            if (parentNode == null) break;
            parent = parentNode.superName;
        }
        return false;
    }

    private static void addBackground(List<ScreenBackground> output, String screen,
                                      String owner, String field) {
        for (ScreenBackground existing : output) {
            if (existing != null && java.util.Objects.equals(screen, existing.screenClass)
                    && java.util.Objects.equals(owner, existing.spriteOwner)
                    && java.util.Objects.equals(field, existing.spriteField)) return;
        }
        ScreenBackground background = new ScreenBackground();
        background.screenClass = screen;
        background.spriteOwner = owner;
        background.spriteField = field;
        output.add(background);
    }

    /**
     * A large class of screens renders its base sprite directly instead of storing it in a field.
     * Once the draw-site scanner has proven the sprite and its screen-relative placement, use the
     * largest unconditional authored draw as that screen's base surface. This removes the old
     * filename fallback while still recovering direct-render screens such as schedules/clipboards.
     */
    private static void inferRenderedBackgrounds(Result result, Map<String, Sprite> sprites) {
        if (result == null || sprites == null || sprites.isEmpty()) return;
        java.util.Set<String> already = new java.util.HashSet<>();
        for (ScreenBackground background : result.backgrounds) {
            if (background != null) already.add(normalize(background.screenClass));
        }
        Map<String, ScreenLayer> best = new LinkedHashMap<>();
        Map<String, Long> bestArea = new LinkedHashMap<>();
        for (ScreenLayer layer : result.layers) {
            if (layer == null || layer.itemId != null || layer.spriteOwner == null || layer.partialOnly
                    || layer.conditional || layer.width <= 0 || layer.height <= 0) continue;
            String screen = normalize(layer.screenClass);
            if (already.contains(screen)) continue;
            long area = (long) layer.width * layer.height;
            // Base panels normally begin at the GUI origin. Small negative offsets are legitimate
            // window offsets (clipboard-style) and still describe the full authored surface.
            int originPenalty = Math.abs(layer.x) + Math.abs(layer.y);
            long score = area * 4096L - originPenalty;
            if (score > bestArea.getOrDefault(screen, Long.MIN_VALUE)) {
                best.put(screen, layer);
                bestArea.put(screen, score);
            }
        }
        for (ScreenLayer layer : best.values()) {
            addBackground(result.backgrounds, layer.screenClass, layer.spriteOwner, layer.spriteField);
        }
    }

    /**
     * Recovers direct {@link net.minecraft.resources.ResourceLocation}-backed GUI blits. A number
     * of GUI libraries never wrap their textures in a sprite object: screens keep a static
     * ResourceLocation and call GuiGraphics.blit(texture, guiLeft, guiTop, ...). Treating those as
     * mere filename references loses both the exact crop and foreground layers.
     *
     * <p>The implementation uses ASM operand provenance for the actual blit arguments. It accepts
     * only a static ResourceLocation whose initializer is proven in bytecode, x/y expressions
     * rooted at the screen's GUI origin, and constant/canonical panel dimensions. Unknown dynamic
     * expressions are skipped rather than guessed.</p>
     */
    private static void collectDirectResourceLayers(Map<String, ClassNode> nodes,
            Map<String, String> resources, Map<String, Sprite> sprites, List<ScreenLayer> output,
            List<ScreenBackground> backgrounds) {
        if (nodes == null || sprites == null || output == null) return;
        if (resources.isEmpty()) return;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (ClassNode node : nodes.values()) {
            if (node == null || node.methods == null) continue;
            int[] size = fixedScreenSize(node, nodes);
            for (MethodNode method : node.methods) {
                if (method == null || method.instructions == null || method.desc == null
                        || !method.desc.contains("Lnet/minecraft/client/gui/GuiGraphics;")) continue;
                RawBlitFlow flow = RawBlitFlow.analyze(node, method, size);
                if (flow == null) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode call)
                            || !"net/minecraft/client/gui/GuiGraphics".equals(call.owner)) continue;
                    RawBlit blit = flow.blit(call, resources);
                    if (blit == null || blit.width <= 0 || blit.height <= 0
                            || blit.width > 4096 || blit.height > 4096) continue;
                    String key = node.name + '|' + blit.resourceField + '@' + blit.x + ',' + blit.y
                            + '[' + blit.u + ',' + blit.v + ':' + blit.width + 'x' + blit.height + ']';
                    if (!seen.add(key)) continue;
                    String pseudoField = "$direct$" + Integer.toUnsignedString(key.hashCode(), 36);
                    Sprite sprite = new Sprite();
                    sprite.owner = node.name;
                    sprite.field = pseudoField;
                    sprite.texture = blit.texture;
                    sprite.u = blit.u;
                    sprite.v = blit.v;
                    sprite.width = blit.width;
                    sprite.height = blit.height;
                    sprites.putIfAbsent(sprite.owner + '.' + sprite.field, sprite);

                    var predicate = ClientSurfaceScanner.screenDrawPredicate(node, method, call, nodes);
                    if (predicate != null && predicate.op == RendererBytecodeScanner.BooleanOp.CONSTANT
                            && !predicate.constant) continue;
                    ScreenLayer layer = new ScreenLayer();
                    layer.screenClass = node.name;
                    layer.spriteOwner = sprite.owner;
                    layer.spriteField = sprite.field;
                    layer.x = blit.x;
                    layer.y = blit.y;
                    layer.width = blit.width;
                    layer.height = blit.height;
                    layer.predicateAnalyzed = true;
                    layer.visiblePredicate = predicate;
                    layer.conditional = predicate == null
                            || predicate.op != RendererBytecodeScanner.BooleanOp.CONSTANT || !predicate.constant;
                    boolean exactPanel = size[0] > 0 && size[1] > 0 && blit.x == 0 && blit.y == 0
                            && blit.u == 0 && blit.v == 0
                            && blit.width == size[0] && blit.height == size[1];
                    layer.partialOnly = !exactPanel;
                    output.add(layer);
                    if (exactPanel && !layer.conditional) {
                        addBackground(backgrounds, node.name, sprite.owner, sprite.field);
                    }
                }
            }
        }
    }

    /** A concrete container screen can pass a static ResourceLocation to a reusable screen base,
     * whose instance field performs the actual GuiGraphics blit. Join that constructor operand to
     * the proven inherited draw so the concrete screen owns the exact crop rather than falling back
     * to filename order. */
    private static void collectConstructorResourceBackgrounds(Map<String, ClassNode> nodes,
            Map<String, String> resources, Map<String, Sprite> sprites, List<ScreenLayer> output,
            List<ScreenBackground> backgrounds) {
        if (nodes == null || resources == null || resources.isEmpty()) return;
        java.util.Set<String> inheritedBlitters = new java.util.HashSet<>();
        for (ClassNode node : nodes.values()) {
            if (node != null && instanceResourceBlit(node)) inheritedBlitters.add(node.name);
        }
        for (ClassNode node : nodes.values()) {
            if (node == null || node.methods == null) continue;
            int[] size = fixedScreenSize(node, nodes);
            if (size[0] <= 0 || size[1] <= 0) continue;
            for (MethodNode constructor : node.methods) {
                if (!"<init>".equals(constructor.name) || constructor.instructions == null) continue;
                for (AbstractInsnNode instruction : constructor.instructions) {
                    if (!(instruction instanceof FieldInsnNode field)
                            || field.getOpcode() != Opcodes.GETSTATIC
                            || !resources.containsKey(field.owner + '.' + field.name)) continue;
                    MethodInsnNode superCall = null;
                    int remaining = 28;
                    for (AbstractInsnNode cursor = instruction.getNext(); cursor != null && remaining-- > 0;
                         cursor = cursor.getNext()) {
                        if (!(cursor instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                                || !"<init>".equals(call.name)) continue;
                        boolean acceptsResource = false;
                        try {
                            for (Type argument : Type.getArgumentTypes(call.desc)) {
                                if (argument.getSort() == Type.OBJECT
                                        && "net/minecraft/resources/ResourceLocation".equals(
                                        argument.getInternalName())) {
                                    acceptsResource = true;
                                    break;
                                }
                            }
                        } catch (IllegalArgumentException ignored) {
                        }
                        if (acceptsResource && inheritedResourceBlit(call.owner, nodes,
                                inheritedBlitters, new java.util.HashSet<>(), 0)) superCall = call;
                        break;
                    }
                    if (superCall == null) continue;
                    String key = node.name + '|' + field.owner + '.' + field.name + "#ctor:"
                            + size[0] + 'x' + size[1];
                    String pseudoField = "$inherited$" + Integer.toUnsignedString(key.hashCode(), 36);
                    Sprite sprite = new Sprite();
                    sprite.owner = node.name;
                    sprite.field = pseudoField;
                    sprite.texture = resources.get(field.owner + '.' + field.name);
                    sprite.u = 0; sprite.v = 0;
                    sprite.width = size[0]; sprite.height = size[1];
                    sprites.putIfAbsent(sprite.owner + '.' + sprite.field, sprite);
                    addBackground(backgrounds, node.name, sprite.owner, sprite.field);
                    boolean duplicate = output.stream().anyMatch(layer -> layer != null
                            && node.name.equals(layer.screenClass) && layer.x == 0 && layer.y == 0
                            && layer.width == size[0] && layer.height == size[1]
                            && java.util.Objects.equals(layer.spriteOwner, sprite.owner)
                            && java.util.Objects.equals(layer.spriteField, sprite.field));
                    if (!duplicate) {
                        ScreenLayer layer = new ScreenLayer();
                        layer.screenClass = node.name;
                        layer.spriteOwner = sprite.owner;
                        layer.spriteField = sprite.field;
                        layer.x = 0; layer.y = 0;
                        layer.width = size[0]; layer.height = size[1];
                        layer.predicateAnalyzed = true;
                        RendererBytecodeScanner.BooleanPredicate visible =
                                new RendererBytecodeScanner.BooleanPredicate();
                        visible.op = RendererBytecodeScanner.BooleanOp.CONSTANT;
                        visible.constant = true;
                        layer.visiblePredicate = visible;
                        layer.conditional = false;
                        output.add(layer);
                    }
                    break;
                }
            }
        }
    }

    private static boolean instanceResourceBlit(ClassNode node) {
        if (node == null || node.methods == null) return false;
        for (MethodNode method : node.methods) {
            if (method == null || method.instructions == null || method.desc == null
                    || !method.desc.contains("Lnet/minecraft/client/gui/GuiGraphics;")) continue;
            boolean resourceField = false;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                        && "Lnet/minecraft/resources/ResourceLocation;".equals(field.desc)) {
                    resourceField = true;
                }
                if (resourceField && instruction instanceof MethodInsnNode call
                        && "net/minecraft/client/gui/GuiGraphics".equals(call.owner)
                        && call.desc.contains("Lnet/minecraft/resources/ResourceLocation;")) return true;
            }
        }
        return false;
    }

    private static boolean inheritedResourceBlit(String owner, Map<String, ClassNode> nodes,
            java.util.Set<String> direct, java.util.Set<String> visiting, int depth) {
        if (owner == null || depth > 12 || !visiting.add(owner)) return false;
        if (direct.contains(owner)) return true;
        ClassNode node = nodes.get(owner);
        return node != null && inheritedResourceBlit(node.superName, nodes, direct, visiting, depth + 1);
    }

    /** Static ResourceLocation fields initialized from an authored texture path. */
    private static Map<String, String> directResourceFields(Map<String, ClassNode> nodes,
                                                             String defaultNamespace) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ClassNode node : nodes.values()) {
            if (node == null || node.methods == null) continue;
            MethodNode clinit = findMethod(node, "<clinit>");
            if (clinit == null || clinit.instructions == null) continue;
            for (AbstractInsnNode instruction : clinit.instructions) {
                if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                        || !"Lnet/minecraft/resources/ResourceLocation;".equals(field.desc)) continue;
                String path = null;
                String namespace = null;
                int remaining = 24;
                for (AbstractInsnNode cursor = instruction.getPrevious(); cursor != null && remaining-- > 0;
                     cursor = cursor.getPrevious()) {
                    if (cursor instanceof FieldInsnNode previous && previous.getOpcode() == Opcodes.PUTSTATIC) break;
                    if (!(cursor instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String value)) continue;
                    String normalized = value.replace('\\', '/');
                    if (path == null && (normalized.contains("textures/") || normalized.endsWith(".png")
                            || normalized.indexOf(':') > 0 && normalized.contains("/"))) {
                        path = normalized;
                        continue;
                    }
                    if (path != null && namespace == null && normalized.matches("[a-z0-9_.-]+")) {
                        namespace = normalized;
                    }
                }
                if (path == null) continue;
                String texture;
                if (path.indexOf(':') > 0) texture = path;
                else {
                    String ns = namespace == null || namespace.isBlank() ? defaultNamespace : namespace;
                    if (ns == null || ns.isBlank()) continue;
                    texture = ns + ':' + path;
                }
                out.put(field.owner + '.' + field.name, texture);
            }
        }
        return out;
    }

    /** Fixed logical panel size for screens whose layout is constant. Dynamic zero-valued axes
     * remain unknown; that prevents a fixed header strip from masquerading as a complete screen. */
    private static int[] fixedScreenSize(ClassNode node, Map<String, ClassNode> nodes) {
        int width = -1, height = -1;
        if (node == null) return new int[]{width, height};
        if (node.fields != null) for (org.objectweb.asm.tree.FieldNode field : node.fields) {
            if (!(field.value instanceof Integer value) || value <= 0) continue;
            String name = field.name == null ? "" : field.name.toLowerCase(java.util.Locale.ROOT);
            if (name.equals("f_97726_") || name.contains("imagewidth") || name.equals("xsize")) width = value;
            if (name.equals("f_97727_") || name.contains("imageheight") || name.equals("ysize")) height = value;
        }
        if (node.methods != null) for (MethodNode method : node.methods) {
            if (!"<init>".equals(method.name) || method.instructions == null) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD) {
                    Integer value = integer(previousReal(instruction, 1));
                    if (value == null || value <= 0 || value > 4096) continue;
                    String name = field.name == null ? "" : field.name.toLowerCase(java.util.Locale.ROOT);
                    if (name.equals("f_97726_") || name.contains("imagewidth") || name.equals("xsize")) width = value;
                    if (name.equals("f_97727_") || name.contains("imageheight") || name.equals("ysize")) height = value;
                }
                if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || !"<init>".equals(call.name) || !java.util.Objects.equals(node.superName, call.owner)) continue;
                Type[] args;
                try { args = Type.getArgumentTypes(call.desc); }
                catch (IllegalArgumentException ignored) { continue; }
                if (args.length < 2 || args[args.length - 1].getSort() != Type.INT
                        || args[args.length - 2].getSort() != Type.INT) continue;
                java.util.ArrayList<Integer> values = new java.util.ArrayList<>(2);
                int remaining = 24;
                for (AbstractInsnNode cursor = call.getPrevious(); cursor != null && remaining-- > 0
                        && values.size() < 2; cursor = cursor.getPrevious()) {
                    Integer value = integer(cursor);
                    if (value != null) values.add(value);
                }
                if (values.size() == 2) {
                    if (width <= 0 && values.get(1) > 1) width = values.get(1);
                    if (height <= 0 && values.get(0) > 1) height = values.get(0);
                }
            }
        }
        // AbstractContainerScreen initializes these dimensions to vanilla's canonical chest-style
        // panel size. Concrete screens only overwrite them when they need a different crop, so an
        // absent assignment is positive layout evidence rather than an unknown dimension. Keeping
        // this at the vanilla base-class boundary avoids texture-size guesses for GUI libraries
        // whose subclasses merely pass a ResourceLocation to a shared renderer.
        if (isAncestor(node, "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen", nodes)) {
            if (width <= 0) width = 176;
            if (height <= 0) height = 166;
        }
        if (height <= 0) height = minimumDynamicPanelHeight(node, nodes);
        return new int[]{width, height};
    }

    /** Shared size hint used by loader-side screen metadata as well as the GUI sprite pass. */
    static int[] screenSizeHint(ClassNode node, Map<String, ClassNode> nodes) {
        return fixedScreenSize(node, nodes);
    }

    private record LinearField(String field, int coefficient, int constant) {
        static LinearField constant(int value) { return new LinearField(null, 0, value); }
    }

    /**
     * Recovers the minimum height of a vertically tiled panel without executing its screen class.
     * The accepted form is a conventional imageHeight/ySize assignment which is affine in one
     * integer field (for example {@code header + units * rowHeight + footer}), plus a scanner-proven
     * positive lower bound for that field (typically {@code Math.max(minRows, viewportRows)}).
     * Unknown/multi-field arithmetic is rejected.
     */
    private static int minimumDynamicPanelHeight(ClassNode node, Map<String, ClassNode> nodes) {
        if (node == null || node.methods == null) return -1;
        for (MethodNode method : node.methods) {
            NumericFlow flow = NumericFlow.analyze(node, method);
            if (flow == null) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                        || !"I".equals(field.desc)
                        || !(node.name.equals(field.owner) || isAncestor(node, field.owner, nodes))) continue;
                String name = field.name == null ? "" : field.name.toLowerCase(java.util.Locale.ROOT);
                if (!(name.equals("f_97727_") || name.contains("imageheight") || name.equals("ysize"))) {
                    continue;
                }
                var frame = flow.frame(instruction);
                if (frame == null || frame.getStackSize() < 2) continue;
                LinearField expression = flow.linear(frame.getStack(frame.getStackSize() - 1),
                        new java.util.HashSet<>(), 0);
                if (expression == null || expression.field == null || expression.coefficient <= 0) continue;
                int minimum = flow.minimumAssigned(expression.field);
                if (minimum <= 0) continue;
                long height = (long) expression.constant + (long) expression.coefficient * minimum;
                if (height > 1 && height <= 4096) return (int) height;
            }
        }
        return -1;
    }

    /** Small source-flow evaluator for affine panel dimensions and proven row-count lower bounds. */
    private static final class NumericFlow {
        final ClassNode owner;
        final MethodNode method;
        final org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames;
        final java.util.IdentityHashMap<AbstractInsnNode, Integer> indices = new java.util.IdentityHashMap<>();

        private NumericFlow(ClassNode owner, MethodNode method,
                org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames) {
            this.owner = owner;
            this.method = method;
            this.frames = frames;
            int index = 0;
            for (AbstractInsnNode instruction : method.instructions) indices.put(instruction, index++);
        }

        static NumericFlow analyze(ClassNode owner, MethodNode method) {
            if (owner == null || method == null || method.instructions == null
                    || method.instructions.size() > 20_000) return null;
            try {
                var interpreter = new org.objectweb.asm.tree.analysis.SourceInterpreter(Opcodes.ASM9) {
                    @Override public org.objectweb.asm.tree.analysis.SourceValue copyOperation(
                            AbstractInsnNode instruction, org.objectweb.asm.tree.analysis.SourceValue value) {
                        return value;
                    }
                };
                return new NumericFlow(owner, method,
                        new org.objectweb.asm.tree.analysis.Analyzer<>(interpreter).analyze(owner.name, method));
            } catch (org.objectweb.asm.tree.analysis.AnalyzerException | RuntimeException ignored) {
                return null;
            }
        }

        org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue> frame(
                AbstractInsnNode instruction) {
            Integer index = indices.get(instruction);
            return index == null || index < 0 || index >= frames.length ? null : frames[index];
        }

        LinearField linear(org.objectweb.asm.tree.analysis.SourceValue source,
                           java.util.Set<AbstractInsnNode> visiting, int depth) {
            if (source == null || source.insns.isEmpty() || depth > 20) return null;
            LinearField found = null;
            for (AbstractInsnNode producer : source.insns) {
                if (!visiting.add(producer)) return null;
                LinearField value;
                try { value = linearProducer(producer, visiting, depth + 1); }
                finally { visiting.remove(producer); }
                if (value == null || found != null && !found.equals(value)) return null;
                found = value;
            }
            return found;
        }

        private LinearField linearProducer(AbstractInsnNode producer,
                                           java.util.Set<AbstractInsnNode> visiting, int depth) {
            Integer constant = GuiSpriteBytecodeScanner.integer(producer);
            if (constant != null) return LinearField.constant(constant);
            if (producer instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && owner.name.equals(field.owner) && "I".equals(field.desc)) {
                return new LinearField(field.owner + '.' + field.name, 1, 0);
            }
            int opcode = producer.getOpcode();
            if (opcode != Opcodes.IADD && opcode != Opcodes.ISUB && opcode != Opcodes.IMUL) return null;
            var frame = frame(producer);
            if (frame == null || frame.getStackSize() < 2) return null;
            LinearField left = linear(frame.getStack(frame.getStackSize() - 2), visiting, depth + 1);
            LinearField right = linear(frame.getStack(frame.getStackSize() - 1), visiting, depth + 1);
            if (left == null || right == null) return null;
            if (opcode == Opcodes.IADD || opcode == Opcodes.ISUB) {
                int sign = opcode == Opcodes.IADD ? 1 : -1;
                if (left.field != null && right.field != null
                        && !java.util.Objects.equals(left.field, right.field)) return null;
                String field = left.field != null ? left.field : right.field;
                int coefficient = left.coefficient + sign * right.coefficient;
                int value = left.constant + sign * right.constant;
                return new LinearField(field, coefficient, value);
            }
            if (left.field == null) {
                return new LinearField(right.field, right.coefficient * left.constant,
                        right.constant * left.constant);
            }
            if (right.field == null) {
                return new LinearField(left.field, left.coefficient * right.constant,
                        left.constant * right.constant);
            }
            return null;
        }

        int minimumAssigned(String fieldKey) {
            if (fieldKey == null) return -1;
            int best = -1;
            for (MethodNode candidate : owner.methods) {
                NumericFlow flow = candidate == method ? this : analyze(owner, candidate);
                if (flow == null) continue;
                for (AbstractInsnNode instruction : candidate.instructions) {
                    if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                            || !(field.owner + '.' + field.name).equals(fieldKey)) continue;
                    var frame = flow.frame(instruction);
                    if (frame == null || frame.getStackSize() < 2) continue;
                    Integer value = flow.lowerBound(frame.getStack(frame.getStackSize() - 1),
                            new java.util.HashSet<>(), 0);
                    if (value != null && value > 0) best = Math.max(best, value);
                }
            }
            return best;
        }

        private Integer lowerBound(org.objectweb.asm.tree.analysis.SourceValue source,
                                   java.util.Set<AbstractInsnNode> visiting, int depth) {
            if (source == null || source.insns.isEmpty() || depth > 20) return null;
            Integer found = null;
            for (AbstractInsnNode producer : source.insns) {
                if (!visiting.add(producer)) return null;
                Integer value;
                try { value = lowerBoundProducer(producer, visiting, depth + 1); }
                finally { visiting.remove(producer); }
                if (value == null || found != null && !found.equals(value)) return null;
                found = value;
            }
            return found;
        }

        private Integer lowerBoundProducer(AbstractInsnNode producer,
                                           java.util.Set<AbstractInsnNode> visiting, int depth) {
            Integer direct = GuiSpriteBytecodeScanner.integer(producer);
            if (direct != null) return direct;
            if (!(producer instanceof MethodInsnNode call)) return null;
            var frame = frame(call);
            if (frame == null) return null;
            Type[] args;
            try { args = Type.getArgumentTypes(call.desc); }
            catch (IllegalArgumentException ignored) { return null; }
            if ("java/lang/Math".equals(call.owner) && "max".equals(call.name)
                    && "(II)I".equals(call.desc) && frame.getStackSize() >= 2) {
                Integer left = lowerBound(frame.getStack(frame.getStackSize() - 2), visiting, depth + 1);
                Integer right = lowerBound(frame.getStack(frame.getStackSize() - 1), visiting, depth + 1);
                if (left != null && right != null) return Math.max(left, right);
                return left != null ? left : right;
            }
            if (args.length == 1 && Type.getReturnType(call.desc).getSort() == Type.INT
                    && frame.getStackSize() >= 1) {
                Number number = numericConstant(frame.getStack(frame.getStackSize() - 1),
                        new java.util.HashSet<>(), 0);
                if (number != null) return (int) Math.ceil(number.doubleValue());
            }
            return null;
        }

        private Number numericConstant(org.objectweb.asm.tree.analysis.SourceValue source,
                                       java.util.Set<AbstractInsnNode> visiting, int depth) {
            if (source == null || source.insns.isEmpty() || depth > 12) return null;
            Number found = null;
            for (AbstractInsnNode producer : source.insns) {
                Number value = null;
                if (producer instanceof LdcInsnNode ldc && ldc.cst instanceof Number number) value = number;
                else {
                    Integer integer = GuiSpriteBytecodeScanner.integer(producer);
                    if (integer != null) value = integer;
                    else value = switch (producer.getOpcode()) {
                        case Opcodes.FCONST_0 -> 0.0f;
                        case Opcodes.FCONST_1 -> 1.0f;
                        case Opcodes.FCONST_2 -> 2.0f;
                        case Opcodes.DCONST_0 -> 0.0d;
                        case Opcodes.DCONST_1 -> 1.0d;
                        default -> null;
                    };
                }
                if (value == null || found != null
                        && Double.compare(found.doubleValue(), value.doubleValue()) != 0) return null;
                found = value;
            }
            return found;
        }
    }

    /**
     * A concrete screen can specialize interaction/state while inheriting every render method from
     * another concrete screen. Bytecode scanners naturally attach the parent's blits to the class
     * that declares those methods, but the child is the surface the user actually opens. When the
     * child declares no GuiGraphics-consuming method of its own, replaying the parent's proven
     * composition is exact Java dispatch semantics, not a name or texture heuristic.
     */
    private static void inheritScreenComposition(Map<String, ClassNode> nodes,
            List<ScreenLayer> layers, List<ScreenBackground> backgrounds) {
        if (nodes == null || nodes.isEmpty()) return;
        for (int pass = 0; pass < 16; pass++) {
            boolean changed = false;
            Map<String, List<ScreenLayer>> layersByScreen = new LinkedHashMap<>();
            for (ScreenLayer layer : layers) if (layer != null && layer.screenClass != null) {
                layersByScreen.computeIfAbsent(normalize(layer.screenClass), ignored -> new ArrayList<>())
                        .add(layer);
            }
            Map<String, List<ScreenBackground>> backgroundsByScreen = new LinkedHashMap<>();
            for (ScreenBackground background : backgrounds) {
                if (background != null && background.screenClass != null) {
                    backgroundsByScreen.computeIfAbsent(normalize(background.screenClass),
                            ignored -> new ArrayList<>()).add(background);
                }
            }
            for (ClassNode node : nodes.values()) {
                if (node == null || node.name == null || node.superName == null
                        || declaresGuiGraphicsMethod(node)) continue;
                String child = normalize(node.name);
                String parent = normalize(node.superName);
                List<ScreenLayer> parentLayers = layersByScreen.getOrDefault(parent, List.of());
                List<ScreenBackground> parentBackgrounds = backgroundsByScreen.getOrDefault(parent, List.of());
                if (parentLayers.isEmpty() && parentBackgrounds.isEmpty()) continue;

                for (ScreenLayer source : parentLayers) {
                    boolean exists = layers.stream().anyMatch(existing -> existing != null
                            && child.equals(normalize(existing.screenClass))
                            && java.util.Objects.equals(source.spriteOwner, existing.spriteOwner)
                            && java.util.Objects.equals(source.spriteField, existing.spriteField)
                            && java.util.Objects.equals(source.itemId, existing.itemId)
                            && source.x == existing.x && source.y == existing.y
                            && source.width == existing.width && source.height == existing.height);
                    if (exists) continue;
                    ScreenLayer copy = new ScreenLayer();
                    copy.screenClass = node.name;
                    copy.spriteOwner = source.spriteOwner;
                    copy.spriteField = source.spriteField;
                    copy.itemId = source.itemId;
                    copy.itemRegistryKind = source.itemRegistryKind;
                    copy.x = source.x; copy.y = source.y;
                    copy.width = source.width; copy.height = source.height;
                    copy.stretched = source.stretched;
                    copy.partialOnly = source.partialOnly;
                    copy.conditional = source.conditional;
                    copy.predicateAnalyzed = source.predicateAnalyzed;
                    copy.visiblePredicate = source.visiblePredicate;
                    layers.add(copy);
                    changed = true;
                }
                for (ScreenBackground source : parentBackgrounds) {
                    boolean exists = backgrounds.stream().anyMatch(existing -> existing != null
                            && child.equals(normalize(existing.screenClass))
                            && java.util.Objects.equals(source.spriteOwner, existing.spriteOwner)
                            && java.util.Objects.equals(source.spriteField, existing.spriteField));
                    if (exists) continue;
                    ScreenBackground copy = new ScreenBackground();
                    copy.screenClass = node.name;
                    copy.spriteOwner = source.spriteOwner;
                    copy.spriteField = source.spriteField;
                    backgrounds.add(copy);
                    changed = true;
                }
            }
            if (!changed) return;
        }
    }

    private static boolean declaresGuiGraphicsMethod(ClassNode node) {
        if (node == null || node.methods == null) return false;
        for (MethodNode method : node.methods) {
            if (method != null && method.desc != null
                    && method.desc.contains("Lnet/minecraft/client/gui/GuiGraphics;")) return true;
        }
        return false;
    }

    private record RawBlit(String resourceField, String texture, int x, int y,
                           int u, int v, int width, int height) {}

    private record AffineInt(char base, int value) {
        static final char NONE = 'n', X = 'x', Y = 'y', SCREEN_X = 'w', SCREEN_Y = 'h', UNKNOWN = '?';
        static AffineInt constant(int value) { return new AffineInt(NONE, value); }
        boolean numeric() { return base == NONE; }
    }

    /** Small provenance evaluator for GuiGraphics blit operands. */
    private static final class RawBlitFlow {
        final ClassNode owner;
        final MethodNode method;
        final int[] panelSize;
        final org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames;
        final java.util.IdentityHashMap<AbstractInsnNode, Integer> indices = new java.util.IdentityHashMap<>();

        private RawBlitFlow(ClassNode owner, MethodNode method, int[] panelSize,
                org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames) {
            this.owner = owner; this.method = method; this.panelSize = panelSize; this.frames = frames;
            int i = 0;
            for (AbstractInsnNode instruction : method.instructions) indices.put(instruction, i++);
        }

        static RawBlitFlow analyze(ClassNode owner, MethodNode method, int[] panelSize) {
            if (method.instructions.size() > 20_000) return null;
            try {
                var interpreter = new org.objectweb.asm.tree.analysis.SourceInterpreter(Opcodes.ASM9) {
                    @Override public org.objectweb.asm.tree.analysis.SourceValue copyOperation(
                            AbstractInsnNode instruction, org.objectweb.asm.tree.analysis.SourceValue value) {
                        return value;
                    }
                };
                var frames = new org.objectweb.asm.tree.analysis.Analyzer<>(interpreter)
                        .analyze(owner.name, method);
                return new RawBlitFlow(owner, method, panelSize, frames);
            } catch (org.objectweb.asm.tree.analysis.AnalyzerException | RuntimeException ignored) {
                return null;
            }
        }

        RawBlit blit(MethodInsnNode call, Map<String, String> resources) {
            Type[] args;
            try { args = Type.getArgumentTypes(call.desc); }
            catch (IllegalArgumentException ignored) { return null; }
            int resourceArg = -1;
            for (int i = 0; i < args.length; i++) {
                if (args[i].getSort() == Type.OBJECT
                        && "net/minecraft/resources/ResourceLocation".equals(args[i].getInternalName())) {
                    resourceArg = i; break;
                }
            }
            if (resourceArg < 0 || resourceArg + 6 >= args.length) return null;
            var frame = frame(call);
            if (frame == null || frame.getStackSize() < args.length) return null;
            int first = frame.getStackSize() - args.length;
            String field = resourceField(frame.getStack(first + resourceArg), resources,
                    new java.util.HashSet<>(), 0);
            if (field == null) return null;
            AffineInt x = integer(frame.getStack(first + resourceArg + 1), new java.util.HashSet<>(), 0);
            AffineInt y = integer(frame.getStack(first + resourceArg + 2), new java.util.HashSet<>(), 0);
            int xValue = x != null && x.base == AffineInt.X ? x.value : -1;
            int yValue = y != null && y.base == AffineInt.Y ? y.value : -1;
            // Preserve exact atlas geometry even when one destination coordinate depends on a live
            // viewport/list expression. Discarding the whole draw loses row/footer art for dynamic
            // list screens and later forces the pack builder back to the raw atlas sheet. Runtime
            // translators may place one-axis-live layers once the corresponding layout value is
            // available; until then their pixels still remain scanner-owned evidence.
            if (xValue < 0 && yValue < 0) return null;

            // Two vanilla blit families are structurally relevant here:
            // (RL,x,y,u,v,w,h) and (RL,x,y,u(float),v(float),w,h,textureW,textureH).
            int widthArg;
            int heightArg;
            Number uNumber;
            Number vNumber;
            if (args[resourceArg + 3].getSort() == Type.INT
                    && args[resourceArg + 4].getSort() == Type.INT) {
                widthArg = resourceArg + 5;
                heightArg = resourceArg + 6;
                uNumber = number(frame.getStack(first + resourceArg + 3), new java.util.HashSet<>(), 0);
                vNumber = number(frame.getStack(first + resourceArg + 4), new java.util.HashSet<>(), 0);
            } else if (args[resourceArg + 3].getSort() == Type.FLOAT
                    && args[resourceArg + 4].getSort() == Type.FLOAT && resourceArg + 6 < args.length) {
                widthArg = resourceArg + 5;
                heightArg = resourceArg + 6;
                uNumber = number(frame.getStack(first + resourceArg + 3), new java.util.HashSet<>(), 0);
                vNumber = number(frame.getStack(first + resourceArg + 4), new java.util.HashSet<>(), 0);
            } else return null;
            AffineInt width = integer(frame.getStack(first + widthArg), new java.util.HashSet<>(), 0);
            AffineInt height = integer(frame.getStack(first + heightArg), new java.util.HashSet<>(), 0);
            if (uNumber == null || vNumber == null || width == null || height == null
                    || !width.numeric() || !height.numeric()) return null;
            return new RawBlit(field, resources.get(field), xValue, yValue,
                    Math.round(uNumber.floatValue()), Math.round(vNumber.floatValue()),
                    width.value, height.value);
        }

        private org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue> frame(
                AbstractInsnNode instruction) {
            Integer index = indices.get(instruction);
            return index == null || index < 0 || index >= frames.length ? null : frames[index];
        }

        private String resourceField(org.objectweb.asm.tree.analysis.SourceValue source,
                Map<String, String> resources, java.util.Set<AbstractInsnNode> visiting, int depth) {
            if (source == null || source.insns.isEmpty() || depth > 16) return null;
            String found = null;
            for (AbstractInsnNode producer : source.insns) {
                if (!visiting.add(producer)) return null;
                String value = null;
                try {
                    if (producer instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                            && resources.containsKey(field.owner + '.' + field.name)) {
                        value = field.owner + '.' + field.name;
                    } else {
                        var frame = frame(producer);
                        if (frame != null && frame.getStackSize() > 0) {
                            value = resourceField(frame.getStack(frame.getStackSize() - 1), resources,
                                    visiting, depth + 1);
                        }
                    }
                } finally { visiting.remove(producer); }
                if (value == null || found != null && !found.equals(value)) return null;
                found = value;
            }
            return found;
        }

        private AffineInt integer(org.objectweb.asm.tree.analysis.SourceValue source,
                                  java.util.Set<AbstractInsnNode> visiting, int depth) {
            if (source == null || source.insns.isEmpty() || depth > 20) return null;
            AffineInt found = null;
            for (AbstractInsnNode producer : source.insns) {
                if (!visiting.add(producer)) return null;
                AffineInt value;
                try { value = integerProducer(producer, visiting, depth + 1); }
                finally { visiting.remove(producer); }
                if (value == null || found != null && !found.equals(value)) return null;
                found = value;
            }
            return found;
        }

        private AffineInt integerProducer(AbstractInsnNode producer,
                java.util.Set<AbstractInsnNode> visiting, int depth) {
            Integer constant = GuiSpriteBytecodeScanner.integer(producer);
            if (constant != null) return AffineInt.constant(constant);
            if (producer instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) {
                String name = field.name == null ? "" : field.name.toLowerCase(java.util.Locale.ROOT);
                if (name.contains("guileft") || name.contains("leftpos") || name.equals("f_97735_")) {
                    return new AffineInt(AffineInt.X, 0);
                }
                if (name.contains("guitop") || name.contains("toppos") || name.equals("f_97736_")) {
                    return new AffineInt(AffineInt.Y, 0);
                }
                if (name.contains("imagewidth") || name.equals("xsize") || name.equals("f_97726_")) {
                    return panelSize[0] > 0 ? AffineInt.constant(panelSize[0]) : null;
                }
                if (name.contains("imageheight") || name.equals("ysize") || name.equals("f_97727_")) {
                    return panelSize[1] > 0 ? AffineInt.constant(panelSize[1]) : null;
                }
                if (name.equals("width") || name.equals("f_96543_")) {
                    return new AffineInt(AffineInt.SCREEN_X, 0);
                }
                if (name.equals("height") || name.equals("f_96544_")) {
                    return new AffineInt(AffineInt.SCREEN_Y, 0);
                }
                return null;
            }
            if (producer instanceof MethodInsnNode call && "()I".equals(call.desc)) {
                String name = call.name == null ? "" : call.name.toLowerCase(java.util.Locale.ROOT);
                if (name.contains("guileft") || name.contains("leftpos") || name.equals("getguileft")) {
                    return new AffineInt(AffineInt.X, 0);
                }
                if (name.contains("guitop") || name.contains("toppos") || name.equals("getguitop")) {
                    return new AffineInt(AffineInt.Y, 0);
                }
                if (name.contains("xsize") || name.contains("imagewidth")) {
                    return panelSize[0] > 0 ? AffineInt.constant(panelSize[0]) : null;
                }
                if (name.contains("ysize") || name.contains("imageheight")) {
                    return panelSize[1] > 0 ? AffineInt.constant(panelSize[1]) : null;
                }
            }
            int opcode = producer.getOpcode();
            if (opcode == Opcodes.IADD || opcode == Opcodes.ISUB || opcode == Opcodes.IMUL
                    || opcode == Opcodes.IDIV) {
                var frame = frame(producer);
                if (frame == null || frame.getStackSize() < 2) return null;
                AffineInt left = integer(frame.getStack(frame.getStackSize() - 2), visiting, depth + 1);
                AffineInt right = integer(frame.getStack(frame.getStackSize() - 1), visiting, depth + 1);
                if (left == null || right == null) return null;
                if (opcode == Opcodes.IADD || opcode == Opcodes.ISUB) {
                    int sign = opcode == Opcodes.IADD ? 1 : -1;
                    if (left.base == right.base && left.base != AffineInt.NONE) return null;
                    if (left.base != AffineInt.NONE && right.base == AffineInt.NONE) {
                        return new AffineInt(left.base, left.value + sign * right.value);
                    }
                    if (left.base == AffineInt.NONE && right.base == AffineInt.NONE) {
                        return AffineInt.constant(left.value + sign * right.value);
                    }
                    return null;
                }
                if (opcode == Opcodes.IDIV && right.numeric() && right.value == 2
                        && (left.base == AffineInt.SCREEN_X || left.base == AffineInt.SCREEN_Y)) {
                    int axis = left.base == AffineInt.SCREEN_X ? 0 : 1;
                    char resolved = axis == 0 ? AffineInt.X : AffineInt.Y;
                    int panel = panelSize[axis];
                    // Canonical Screen centering: (viewport - panelSize) / 2. Plain Screen
                    // implementations often keep panelSize only in a render-local constant, so
                    // this expression is also authoritative evidence for the missing dimension.
                    if (left.value < 0) {
                        int inferred = -left.value;
                        if (inferred > 0 && inferred <= 4096 && (panel <= 0 || panel == inferred)) {
                            if (panel <= 0) panelSize[axis] = inferred;
                            return new AffineInt(resolved, 0);
                        }
                    }
                    // screenWidth/2 (or height/2) is equally common for icons/text placed relative
                    // to a centered panel. Once its dimension is known, convert that midpoint back
                    // into the same guiLeft/guiTop coordinate system as every other layer.
                    if (left.value == 0 && panel > 0) {
                        return new AffineInt(resolved, panel / 2);
                    }
                    return null;
                }
                if (!left.numeric() || !right.numeric() || right.value == 0 && opcode == Opcodes.IDIV) return null;
                return AffineInt.constant(opcode == Opcodes.IMUL
                        ? left.value * right.value : left.value / right.value);
            }
            return null;
        }

        private Number number(org.objectweb.asm.tree.analysis.SourceValue source,
                              java.util.Set<AbstractInsnNode> visiting, int depth) {
            if (source == null || source.insns.isEmpty() || depth > 12) return null;
            Number found = null;
            for (AbstractInsnNode producer : source.insns) {
                Number value = null;
                if (producer instanceof LdcInsnNode ldc && ldc.cst instanceof Number number) value = number;
                else {
                    Integer integer = GuiSpriteBytecodeScanner.integer(producer);
                    if (integer != null) value = integer;
                }
                if (value == null) {
                    AffineInt integer = integer(source, visiting, depth + 1);
                    return integer != null && integer.numeric() ? integer.value : null;
                }
                if (found != null && Double.compare(found.doubleValue(), value.doubleValue()) != 0) return null;
                found = value;
            }
            return found;
        }
    }

    /** Direct registry-backed item rendering is part of authored GUI composition too. Recover the
     * exact registry id and screen-relative coordinates from the same bytecode graph rather than
     * substituting a semantic icon. This handles blocks exposed through Registrate/DeferredRegister
     * because BehaviorBytecodeScanner already resolves those static entry fields generically. */
    private static void collectRegistryItemLayers(Map<String, ClassNode> nodes, String namespace,
                                                  List<ScreenLayer> output) {
        if (nodes == null || output == null) return;
        Map<String, BehaviorBytecodeScanner.RegistryRef> registry = new LinkedHashMap<>(
                BehaviorBytecodeScanner.discoverRegistryFields(nodes, namespace));
        discoverFluentRegistryFields(nodes, namespace, registry);
        if (registry.isEmpty()) return;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (ClassNode node : nodes.values()) {
            if (node == null || node.methods == null) continue;
            for (MethodNode method : node.methods) {
                if (method == null || method.instructions == null || method.desc == null
                        || !method.desc.contains("Lnet/minecraft/client/gui/GuiGraphics;")) continue;
                BaseLocals bases = guiBaseLocals(method);
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode call)
                            || !"net/minecraft/client/gui/GuiGraphics".equals(call.owner)) continue;
                    Type[] arguments;
                    try { arguments = Type.getArgumentTypes(call.desc); }
                    catch (IllegalArgumentException ignored) { continue; }
                    boolean itemStack = false;
                    int integers = 0;
                    for (Type argument : arguments) {
                        if (argument.getSort() == Type.INT) integers++;
                        if (argument.getSort() == Type.OBJECT
                                && "net/minecraft/world/item/ItemStack".equals(argument.getInternalName())) {
                            itemStack = true;
                        }
                    }
                    if (!itemStack || integers < 2) continue;
                    FieldInsnNode registryField = registryFieldBefore(call, registry, 24);
                    if (registryField == null) continue;
                    BehaviorBytecodeScanner.RegistryRef ref = registry.get(
                            registryField.owner + '.' + registryField.name);
                    if (ref == null || ref.id == null) continue;
                    OffsetExpr x = relativeOffset(registryField.getNext(), call, bases, true);
                    OffsetExpr y = relativeOffset(registryField.getNext(), call, bases, false);
                    if (x == null || y == null) continue;
                    var predicate = ClientSurfaceScanner.screenDrawPredicate(node, method, call, nodes);
                    if (predicate != null && predicate.op == RendererBytecodeScanner.BooleanOp.CONSTANT
                            && !predicate.constant) continue;
                    String key = node.name + '|' + ref.id + '@' + x.value() + ',' + y.value();
                    if (!seen.add(key)) continue;
                    ScreenLayer layer = new ScreenLayer();
                    layer.screenClass = node.name;
                    layer.itemId = ref.id;
                    layer.itemRegistryKind = ref.registryKind;
                    layer.x = x.value();
                    layer.y = y.value();
                    layer.width = 16;
                    layer.height = 16;
                    layer.predicateAnalyzed = true;
                    layer.visiblePredicate = predicate;
                    layer.conditional = predicate == null
                            || predicate.op != RendererBytecodeScanner.BooleanOp.CONSTANT || !predicate.constant;
                    output.add(layer);
                }
            }
        }
    }

    /** Registrate-style fluent builders end in {@code builder.register(); PUTSTATIC entry}. They
     * do not use DeferredRegister's {@code register(String,Supplier)} shape, so recover the path
     * from the beginning of that same fluent chain. Stopping at the previous static store keeps
     * unrelated strings from neighbouring registrations out of the result. */
    private static void discoverFluentRegistryFields(Map<String, ClassNode> nodes, String namespace,
            Map<String, BehaviorBytecodeScanner.RegistryRef> output) {
        for (ClassNode node : nodes.values()) {
            if (node == null || node.methods == null) continue;
            MethodNode clinit = findMethod(node, "<clinit>");
            if (clinit == null || clinit.instructions == null) continue;
            for (AbstractInsnNode instruction : clinit.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || !"register".equals(call.name)) continue;
                try {
                    if (Type.getArgumentTypes(call.desc).length != 0
                            || Type.getReturnType(call.desc).getSort() != Type.OBJECT) continue;
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                AbstractInsnNode next = nextReal(call);
                if (!(next instanceof FieldInsnNode store) || store.getOpcode() != Opcodes.PUTSTATIC) continue;
                String path = null;
                int remaining = 48;
                for (AbstractInsnNode cursor = call.getPrevious(); cursor != null && remaining-- > 0;
                     cursor = cursor.getPrevious()) {
                    if (cursor.getOpcode() < 0) continue;
                    if (cursor instanceof FieldInsnNode previous && previous.getOpcode() == Opcodes.PUTSTATIC) break;
                    if (cursor instanceof LdcInsnNode ldc && ldc.cst instanceof String value
                            && simpleRegistryPath(value)) path = value;
                }
                if (path == null) continue;
                BehaviorBytecodeScanner.RegistryRef ref = new BehaviorBytecodeScanner.RegistryRef();
                ref.field = store.owner + '.' + store.name;
                String lower = store.desc == null ? "" : store.desc.toLowerCase(java.util.Locale.ROOT);
                ref.registryKind = lower.contains("block") ? "block"
                        : lower.contains("item") ? "item" : "registry";
                ref.id = path.indexOf(':') >= 0 ? path
                        : namespace == null || namespace.isBlank() || "?".equals(namespace)
                        ? path : namespace + ':' + path;
                output.putIfAbsent(ref.field, ref);
            }
        }
    }

    private static boolean simpleRegistryPath(String value) {
        if (value == null || value.isBlank() || value.length() > 160 || value.indexOf(' ') >= 0) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9')
                    && c != '_' && c != '-' && c != '/' && c != '.' && c != ':') return false;
        }
        return true;
    }

    private static FieldInsnNode registryFieldBefore(MethodInsnNode call,
            Map<String, BehaviorBytecodeScanner.RegistryRef> registry, int limit) {
        for (AbstractInsnNode cursor = call == null ? null : call.getPrevious();
             cursor != null && limit-- > 0; cursor = cursor.getPrevious()) {
            if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && registry.containsKey(field.owner + '.' + field.name)) return field;
            if (cursor instanceof MethodInsnNode previous
                    && "net/minecraft/client/gui/GuiGraphics".equals(previous.owner)) break;
        }
        return null;
    }

    /**
     * Some GUI libraries expose the sprite object but let a screen feed its decoded texture/UV
     * fields into GuiGraphics.blit directly instead of calling sprite.render().  That is still an
     * exact authored draw contract. For screens that have no stronger background evidence, retain
     * the largest decoded sprite whose use flows into a ResourceLocation-backed GuiGraphics blit.
     */
    private static void inferLowLevelBlitBackgrounds(Map<String, ClassNode> nodes, Result result,
                                                     Map<String, Sprite> sprites) {
        if (nodes == null || result == null || sprites == null || sprites.isEmpty()) return;
        java.util.Set<String> already = new java.util.HashSet<>();
        for (ScreenBackground background : result.backgrounds) {
            if (background != null) already.add(normalize(background.screenClass));
        }
        for (ClassNode node : nodes.values()) {
            if (node == null || already.contains(normalize(node.name)) || node.methods == null) continue;
            FieldInsnNode best = null;
            long bestArea = -1;
            for (MethodNode method : node.methods) {
                if (method == null || method.instructions == null || method.desc == null
                        || !method.desc.contains("Lnet/minecraft/client/gui/GuiGraphics;")) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof FieldInsnNode field)
                            || field.getOpcode() != Opcodes.GETSTATIC) continue;
                    Sprite sprite = sprites.get(field.owner + '.' + field.name);
                    if (sprite == null || !flowsToGuiBlit(instruction, 72)) continue;
                    long area = (long) sprite.width * sprite.height;
                    if (area > bestArea) {
                        best = field;
                        bestArea = area;
                    }
                }
            }
            if (best != null) {
                addBackground(result.backgrounds, node.name, best.owner, best.name);
                already.add(normalize(node.name));
            }
        }
    }

    private static boolean flowsToGuiBlit(AbstractInsnNode spriteLoad, int steps) {
        int remaining = steps;
        boolean textureEvidence = false;
        for (AbstractInsnNode cursor = spriteLoad == null ? null : spriteLoad.getNext();
             cursor != null && remaining-- > 0; cursor = cursor.getNext()) {
            if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && "Lnet/minecraft/resources/ResourceLocation;".equals(field.desc)) {
                textureEvidence = true;
            }
            if (!(cursor instanceof MethodInsnNode call)
                    || !"net/minecraft/client/gui/GuiGraphics".equals(call.owner)) continue;
            Type[] arguments;
            try { arguments = Type.getArgumentTypes(call.desc); }
            catch (IllegalArgumentException ignored) { continue; }
            int integers = 0;
            boolean resource = false;
            for (Type argument : arguments) {
                if (argument.getSort() == Type.INT) integers++;
                if (argument.getSort() == Type.OBJECT
                        && "net/minecraft/resources/ResourceLocation".equals(argument.getInternalName())) {
                    resource = true;
                }
            }
            if (textureEvidence && resource && integers >= 4) return true;
        }
        return false;
    }

    /**
     * Some screens do not store their background sprite on the instance. Instead they expose a
     * static alias such as {@code SCREEN_TEXTURE = GuiTextures.SCREEN} and render that alias from
     * the screen's draw method. Treating only constructor PUTFIELD assignments as backgrounds makes
     * the server fallback miss those screens and fall back to the entire source atlas.
     *
     * <p>Keep this bytecode-owned: an alias is accepted only when the class initializer copies an
     * already decoded sprite into a static field <em>and</em> a GuiGraphics method subsequently
     * invokes a sprite-style render call from that exact alias. No screen/mod/field names are used.</p>
     */
    private static void collectStaticSpriteAliases(ClassNode node, Map<String, Sprite> sprites,
                                                   List<ScreenBackground> output) {
        MethodNode clinit = findMethod(node, "<clinit>");
        if (clinit == null || clinit.instructions == null) return;
        Map<String, FieldInsnNode> aliases = new LinkedHashMap<>();
        FieldInsnNode selected = null;
        int remaining = 0;
        for (AbstractInsnNode instruction : clinit.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && sprites.containsKey(field.owner + '.' + field.name)) {
                selected = field;
                remaining = 8;
                continue;
            }
            if (selected == null || remaining-- <= 0) {
                selected = null;
                continue;
            }
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTSTATIC
                    && node.name.equals(field.owner)) {
                aliases.put(field.name, selected);
                selected = null;
            }
        }
        for (Map.Entry<String, FieldInsnNode> alias : aliases.entrySet()) {
            if (!renderedStaticAlias(node, alias.getKey())) continue;
            FieldInsnNode sprite = alias.getValue();
            ScreenBackground background = new ScreenBackground();
            background.screenClass = node.name;
            background.spriteOwner = sprite.owner;
            background.spriteField = sprite.name;
            output.add(background);
        }
    }

    private static boolean renderedStaticAlias(ClassNode node, String fieldName) {
        if (node == null || fieldName == null || node.methods == null) return false;
        for (MethodNode method : node.methods) {
            if (method == null || method.instructions == null || method.desc == null
                    || !method.desc.contains("Lnet/minecraft/client/gui/GuiGraphics;")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.GETSTATIC
                        || !node.name.equals(field.owner) || !fieldName.equals(field.name)) continue;
                int remaining = 32;
                for (AbstractInsnNode cursor = instruction.getNext(); cursor != null && remaining-- > 0;
                     cursor = cursor.getNext()) {
                    if (cursor instanceof MethodInsnNode call && spriteRenderCall(call)) return true;
                    // Do not let one alias borrow a later unrelated render call.
                    if (cursor instanceof FieldInsnNode next && next.getOpcode() == Opcodes.GETSTATIC
                            && node.name.equals(next.owner) && fieldName.equals(next.name)) break;
                }
            }
        }
        return false;
    }

    private static MethodNode findMethod(ClassNode node, String name) {
        if (node == null || node.methods == null) return null;
        for (MethodNode method : node.methods) if (name.equals(method.name)) return method;
        return null;
    }

    private record OffsetExpr(int value, AbstractInsnNode end) {}

    /**
     * Decode the common screen-space draw idioms without executing client classes. This is a small
     * symbolic integer evaluator rather than a name-based GUI guess: it follows locals loaded from
     * guiLeft/guiTop, constant +/- offsets, the exact sprite field, and optional stretched width /
     * height arguments. Dynamic expressions are deliberately skipped instead of being guessed.
     */
    private static void collectLayers(ClassNode node, Map<String, Sprite> sprites,
                                      List<ScreenLayer> output, Map<String, ClassNode> nodes) {
        if (node == null || node.methods == null || sprites == null || sprites.isEmpty()) return;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (MethodNode method : node.methods) {
            if (method.instructions == null || method.desc == null
                    || !method.desc.contains("Lnet/minecraft/client/gui/GuiGraphics;")) continue;
            BaseLocals bases = guiBaseLocals(method);
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                boolean direct = spriteRenderCall(call);
                boolean stretched = stretchedSpriteCall(call);
                if (!direct && !stretched) continue;
                FieldInsnNode field = spriteBefore(call, sprites, stretched ? 42 : 24);
                if (field == null) continue;
                AbstractInsnNode floor = stretched ? previousReal(call, 42) : field.getNext();
                OffsetExpr x = relativeOffset(floor, call, bases, true);
                OffsetExpr y = relativeOffset(floor, call, bases, false);
                // A draw can be anchored by one live expression while the other axis remains a
                // simple screen-relative constant. Keep that authored sprite in the IR with -1
                // for the unresolved coordinate instead of dropping it entirely. Runtime content
                // translators can then place text-following decorations (edit pencils are a common
                // example) from the same live value that drives the text itself.
                if (x == null && y == null) continue;
                int xValue = x == null ? -1 : x.value();
                int yValue = y == null ? -1 : y.value();
                Sprite sprite = sprites.get(field.owner + '.' + field.name);
                if (sprite == null) continue;
                int width = sprite.width;
                int height = sprite.height;
                if (stretched) {
                    int[] size = stretchedSize(y.end(), field);
                    if (size == null) continue;
                    width = size[0];
                    height = size[1];
                }
                if (width <= 0 || height <= 0 || width > 2048 || height > 2048) continue;
                var predicate = ClientSurfaceScanner.screenDrawPredicate(node, method, call, nodes);
                if (predicate != null && predicate.op == RendererBytecodeScanner.BooleanOp.CONSTANT
                        && !predicate.constant) continue;
                String key = node.name + '|' + field.owner + '.' + field.name + '@'
                        + xValue + ',' + yValue + ':' + width + 'x' + height + ':' + stretched
                        + ':' + predicate;
                if (!seen.add(key)) continue;
                ScreenLayer layer = new ScreenLayer();
                layer.screenClass = node.name;
                layer.spriteOwner = field.owner;
                layer.spriteField = field.name;
                layer.x = xValue;
                layer.y = yValue;
                layer.width = width;
                layer.height = height;
                layer.stretched = stretched;
                layer.predicateAnalyzed = true;
                layer.visiblePredicate = predicate;
                layer.conditional = predicate == null
                        || predicate.op != RendererBytecodeScanner.BooleanOp.CONSTANT || !predicate.constant;
                output.add(layer);
            }
        }
    }

    private static boolean spriteRenderCall(MethodInsnNode call) {
        if (call == null || call.desc == null || !call.desc.endsWith(")V")) return false;
        Type[] args;
        try {
            args = Type.getArgumentTypes(call.desc);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        return args.length == 3
                && args[0].getSort() == Type.OBJECT
                && "net/minecraft/client/gui/GuiGraphics".equals(args[0].getInternalName())
                && args[1].getSort() == Type.INT && args[2].getSort() == Type.INT;
    }

    private static boolean stretchedSpriteCall(MethodInsnNode call) {
        if (call == null || call.desc == null) return false;
        String name = call.name == null ? "" : call.name.toLowerCase(java.util.Locale.ROOT);
        if (!name.contains("stretch")) return false;
        Type[] args;
        try {
            args = Type.getArgumentTypes(call.desc);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        int integers = 0;
        boolean graphics = false;
        for (Type arg : args) {
            if (arg.getSort() == Type.INT) integers++;
            if (arg.getSort() == Type.OBJECT
                    && "net/minecraft/client/gui/GuiGraphics".equals(arg.getInternalName())) {
                graphics = true;
            }
        }
        return graphics && integers >= 4;
    }

    private static FieldInsnNode spriteBefore(AbstractInsnNode call, Map<String, Sprite> sprites,
                                              int steps) {
        int remaining = steps;
        for (AbstractInsnNode previous = call == null ? null : call.getPrevious();
             previous != null && remaining-- > 0; previous = previous.getPrevious()) {
            if (previous instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && sprites.containsKey(field.owner + '.' + field.name)) return field;
        }
        return null;
    }

    private record BaseLocals(int x, int y, int xOffset, int yOffset) {}

    private static BaseLocals guiBaseLocals(MethodNode method) {
        int x = -1, y = -1, xOffset = 0, yOffset = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            String name;
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) {
                name = field.name == null ? "" : field.name.toLowerCase(java.util.Locale.ROOT);
            } else if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() != Opcodes.INVOKESTATIC
                    && "()I".equals(call.desc)) {
                // Screen bases commonly expose accessor methods rather than fields. The method is
                // still bytecode-owned evidence: only the conventional GUI-origin accessors are
                // accepted, never an arbitrary integer getter near a render call.
                name = call.name == null ? "" : call.name.toLowerCase(java.util.Locale.ROOT);
            } else {
                continue;
            }
            boolean left = name.contains("guileft") || name.contains("leftpos")
                    || name.equals("getguileft") || name.equals("f_97735_");
            boolean top = name.contains("guitop") || name.contains("toppos")
                    || name.equals("getguitop") || name.equals("f_97736_");
            if (!left && !top) continue;
            AbstractInsnNode cursor = nextReal(instruction);
            int offset = 0;
            int remaining = 5;
            while (cursor != null && remaining-- > 0) {
                if (cursor instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ISTORE) {
                    if (left) { x = variable.var; xOffset = offset; }
                    if (top) { y = variable.var; yOffset = offset; }
                    break;
                }
                Integer amount = integer(cursor);
                if (amount != null) {
                    AbstractInsnNode op = nextReal(cursor);
                    if (op != null && op.getOpcode() == Opcodes.IADD) offset += amount;
                    else if (op != null && op.getOpcode() == Opcodes.ISUB) offset -= amount;
                }
                cursor = nextReal(cursor);
            }
        }
        return new BaseLocals(x, y, xOffset, yOffset);
    }

    private static OffsetExpr relativeOffset(AbstractInsnNode start, AbstractInsnNode end,
                                             BaseLocals bases, boolean xAxis) {
        OffsetExpr found = null;
        int baseLocal = xAxis ? bases.x : bases.y;
        int inheritedOffset = xAxis ? bases.xOffset : bases.yOffset;
        for (AbstractInsnNode cursor = start; cursor != null && cursor != end; cursor = cursor.getNext()) {
            AbstractInsnNode anchor = null;
            int baseOffset = 0;
            if (cursor instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ILOAD
                    && baseLocal >= 0 && variable.var == baseLocal) {
                anchor = cursor;
                baseOffset = inheritedOffset;
            } else if (cursor instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ALOAD
                    && variable.var == 0) {
                AbstractInsnNode next = nextReal(cursor);
                if (next instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) {
                    String name = field.name == null ? "" : field.name.toLowerCase(java.util.Locale.ROOT);
                    boolean matches = xAxis ? name.contains("guileft") || name.contains("leftpos")
                            || name.equals("f_97735_")
                            : name.contains("guitop") || name.contains("toppos") || name.equals("f_97736_");
                    if (matches) anchor = next;
                }
            }
            if (anchor == null) continue;
            AbstractInsnNode next = nextReal(anchor);
            Integer value = integer(next);
            if (value == null) {
                found = new OffsetExpr(baseOffset, anchor);
                continue;
            }
            AbstractInsnNode operation = nextReal(next);
            if (operation == null) continue;
            if (operation.getOpcode() == Opcodes.IADD) found = new OffsetExpr(baseOffset + value, operation);
            if (operation.getOpcode() == Opcodes.ISUB) found = new OffsetExpr(baseOffset - value, operation);
        }
        return found;
    }

    private static int[] stretchedSize(AbstractInsnNode afterY, AbstractInsnNode spriteField) {
        java.util.ArrayList<Integer> values = new java.util.ArrayList<>();
        for (AbstractInsnNode cursor = afterY == null ? null : afterY.getNext();
             cursor != null && cursor != spriteField; cursor = cursor.getNext()) {
            Integer value = integer(cursor);
            if (value != null && value > 0) values.add(value);
            if (values.size() >= 2) return new int[]{values.get(0), values.get(1)};
        }
        return null;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction, int steps) {
        AbstractInsnNode cursor = instruction;
        for (int i = 0; cursor != null && i < steps; i++) cursor = cursor.getPrevious();
        return cursor;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction == null ? null : instruction.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    private static Integer integer(AbstractInsnNode instruction) {
        Object value = constant(instruction);
        return value instanceof Integer integer ? integer : null;
    }

    private static Object constant(AbstractInsnNode instruction) {
        if (instruction instanceof LdcInsnNode ldc
                && (ldc.cst instanceof String || ldc.cst instanceof Integer)) return ldc.cst;
        if (instruction instanceof IntInsnNode integer) return integer.operand;
        int opcode = instruction.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return opcode - Opcodes.ICONST_0;
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('.', '/');
    }
}
