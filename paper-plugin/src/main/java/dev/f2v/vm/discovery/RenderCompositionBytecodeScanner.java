package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconstructs how a client render surface is composed without executing client code.
 *
 * <p>The older scanners answered separate questions ("is this a map?", "which GUI sprite is the
 * background?", "does this screen render a sublevel?"). That still left runtime code guessing how
 * those facts fit together, which is exactly how a live viewport ended up as a generic square over
 * an otherwise correctly discovered screen. This scanner joins the existing evidence by concrete
 * class/method ownership and records a declarative composition plan.</p>
 *
 * <p>No mod ids or known class names are used. Screen/HUD ownership comes from the client-surface
 * scanner, authored dimensions come from the GUI-sprite scanner, dynamic 3-D ownership comes from
 * the sublevel scanner, and HUD layout bindings come from ASM source-dataflow: primitive fields of
 * a render-context object must actually reach a downstream GuiGraphics render call.</p>
 */
public final class RenderCompositionBytecodeScanner {

    public static final int SCHEMA = 3;

    public enum SurfaceKind { SCREEN, HUD }
    public enum DynamicKind { MAP, SUBLEVEL, FRAMEBUFFER, GENERIC }
    public enum BoundsMode { FULL_SURFACE, AUTHORED_SURFACE, CONTEXT_RECT, UNKNOWN }

    public static final class FieldBinding {
        public String role;
        public String owner;
        public String field;
        public String descriptor;

        @Override public String toString() {
            return role + '=' + owner + '.' + field + ':' + descriptor;
        }
    }

    public static final class Surface {
        public String ownerClass;
        public String rootMethod;
        public SurfaceKind surfaceKind;
        public DynamicKind dynamicKind;
        public BoundsMode boundsMode = BoundsMode.UNKNOWN;
        /** Native authored logical-pixel extent when bytecode proves one. */
        public int width;
        public int height;
        /** Symmetric content padding inside a runtime-sized HUD rectangle, when repeated bytecode
         * layout arithmetic proves one dominant small offset. Zero means unknown. */
        public int contentInset;
        public String backgroundSpriteOwner;
        public String backgroundSpriteField;
        public final Set<String> renderCalls = new LinkedHashSet<>();
        /** Exact render-context fields forwarded to a downstream draw call. Roles are structural:
         * first two context ints after GuiGraphics are origin x/y; the next two are surface w/h. */
        public final List<FieldBinding> contextBindings = new ArrayList<>();

        @Override public String toString() {
            return ownerClass + '#' + rootMethod + ' ' + surfaceKind + '/' + dynamicKind
                    + ' ' + boundsMode + ' ' + width + 'x' + height
                    + " inset=" + contentInset
                    + " bg=" + backgroundSpriteOwner + '.' + backgroundSpriteField
                    + " context=" + contextBindings;
        }
    }

    private record SpriteRef(String owner, String field, int width, int height) {}

    private RenderCompositionBytecodeScanner() {}

    public static List<Surface> scan(Map<String, ClassNode> nodes,
                                     Collection<ClientSurfaceScanner.ClassSurface> clientSurfaces,
                                     GuiSpriteBytecodeScanner.Result guiSprites,
                                     Collection<SubLevelRenderBytecodeScanner.Surface> subLevels) {
        if (nodes == null || nodes.isEmpty() || clientSurfaces == null) return List.of();
        Map<String, SpriteRef> backgrounds = backgrounds(guiSprites);
        Map<String, SubLevelRenderBytecodeScanner.Surface> subLevelByScreen = new HashMap<>();
        if (subLevels != null) for (var surface : subLevels) {
            if (surface != null && surface.screenClass != null) {
                subLevelByScreen.putIfAbsent(normalize(surface.screenClass), surface);
            }
        }

        ArrayList<Surface> out = new ArrayList<>();
        for (ClientSurfaceScanner.ClassSurface client : clientSurfaces) {
            if (client == null || client.clazz == null) continue;
            boolean screen = client.kinds.contains(ClientSurfaceScanner.Kind.SCREEN);
            boolean passiveHudHint = !screen && client.kinds.contains(ClientSurfaceScanner.Kind.HUD)
                    && client.kinds.contains(ClientSurfaceScanner.Kind.OVERLAY);
            boolean mapRenderCandidate = !screen && client.kinds.contains(ClientSurfaceScanner.Kind.MAP);
            if (!screen && !passiveHudHint && !mapRenderCandidate) continue;
            ClassNode node = nodes.get(normalize(client.clazz));
            if (node == null || node.methods == null) continue;

            SubLevelRenderBytecodeScanner.Surface subLevel = subLevelByScreen.get(normalize(client.clazz));
            SpriteRef background = backgrounds.get(normalize(client.clazz));
            for (MethodNode method : node.methods) {
                if (!renderMethod(method)) continue;
                DynamicKind dynamic = dynamicKind(node, client, method, subLevel);
                if (dynamic == null) continue;
                Surface surface = new Surface();
                surface.ownerClass = node.name;
                surface.rootMethod = method.name + method.desc;
                surface.dynamicKind = dynamic;
                collectRenderCalls(method, surface.renderCalls);

                if (background != null) {
                    surface.backgroundSpriteOwner = background.owner;
                    surface.backgroundSpriteField = background.field;
                    surface.width = background.width;
                    surface.height = background.height;
                    surface.boundsMode = BoundsMode.AUTHORED_SURFACE;
                } else if (screen && dynamic == DynamicKind.MAP) {
                    // A map Screen with no authored panel is structurally a canvas: its Screen
                    // dimensions are supplied by Minecraft at runtime, so no fixed compatibility
                    // panel should be invented around it.
                    surface.boundsMode = BoundsMode.FULL_SURFACE;
                }

                List<FieldBinding> context = contextBindingsDeep(nodes, node, method);
                if (!context.isEmpty()) {
                    surface.contextBindings.addAll(context);
                    if (!screen && hasContextRect(context)) {
                        surface.boundsMode = BoundsMode.CONTEXT_RECT;
                    }
                }
                if (!screen && dynamic == DynamicKind.MAP) {
                    surface.contentInset = dominantSmallLayoutOffset(nodes, node, method);
                }
                if (!screen && !passiveHudHint && surface.boundsMode != BoundsMode.CONTEXT_RECT) {
                    // MAP+RENDERER by itself can be a helper for a full-screen map. Concrete x/y /
                    // screen-w/h provenance is the proof that this method owns a persistent HUD
                    // rectangle rather than merely participating in map rendering.
                    continue;
                }
                surface.surfaceKind = screen ? SurfaceKind.SCREEN : SurfaceKind.HUD;
                if (surface.boundsMode == BoundsMode.UNKNOWN && screen) {
                    surface.boundsMode = BoundsMode.FULL_SURFACE;
                }
                out.add(surface);
            }
        }
        out.sort(Comparator.comparing((Surface value) -> value.ownerClass == null ? "" : value.ownerClass)
                .thenComparing(value -> value.rootMethod == null ? "" : value.rootMethod));
        return List.copyOf(out);
    }

    public static Surface forScreen(Collection<ModScanner.Profile> profiles, String screenClass) {
        if (profiles == null || screenClass == null || screenClass.isBlank()) return null;
        String wanted = normalize(screenClass);
        Surface best = null;
        int bestScore = Integer.MIN_VALUE;
        ArrayList<Surface> all = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collect(profile, all);
        for (Surface surface : all) {
            if (surface == null || surface.surfaceKind != SurfaceKind.SCREEN
                    || !wanted.equals(normalize(surface.ownerClass))) continue;
            int score = (surface.dynamicKind == DynamicKind.SUBLEVEL ? 1000 : 0)
                    + (surface.boundsMode == BoundsMode.AUTHORED_SURFACE ? 300 : 0)
                    + (surface.boundsMode == BoundsMode.FULL_SURFACE ? 200 : 0)
                    + surface.renderCalls.size();
            if (score > bestScore) {
                best = surface;
                bestScore = score;
            }
        }
        return best;
    }

    public static List<Surface> passiveMapHuds(Collection<ModScanner.Profile> profiles) {
        if (profiles == null) return List.of();
        // A MAP render helper is not sufficient evidence for an always-visible minimap. Full-screen
        // world maps often share HUD-ish rendering utilities; require the independent client-surface
        // proof that this mod actually owns a passive map HUD before publishing any composition.
        if (!ClientSurfaceScanner.hasPassiveMapHud(profiles)) return List.of();
        ArrayList<Surface> out = new ArrayList<>();
        for (ModScanner.Profile profile : profiles) collect(profile, out);
        return out.stream().filter(surface -> surface != null
                        && surface.surfaceKind == SurfaceKind.HUD
                        && surface.dynamicKind == DynamicKind.MAP)
                .sorted(Comparator.comparing(surface -> surface.ownerClass == null ? "" : surface.ownerClass))
                .toList();
    }

    private static void collect(ModScanner.Profile profile, List<Surface> out) {
        if (profile == null) return;
        if (profile.renderCompositions != null) out.addAll(profile.renderCompositions);
        if (profile.nested != null) for (ModScanner.Profile nested : profile.nested) collect(nested, out);
    }

    private static DynamicKind dynamicKind(ClassNode owner,
                                           ClientSurfaceScanner.ClassSurface client, MethodNode method,
                                           SubLevelRenderBytecodeScanner.Surface subLevel) {
        if (subLevel != null && subLevel.rootMethod != null
                && (subLevel.rootMethod.equals(method.name + method.desc)
                || reaches(owner, method, subLevel.rootMethod, new LinkedHashSet<>(), 0))) {
            return DynamicKind.SUBLEVEL;
        }
        if (client.kinds.contains(ClientSurfaceScanner.Kind.MAP)) return DynamicKind.MAP;
        if (framebufferCall(method)) return DynamicKind.FRAMEBUFFER;
        return null;
    }

    private static boolean reaches(ClassNode owner, MethodNode method, String target,
                                   Set<String> visiting, int depth) {
        if (owner == null || method == null || target == null || depth > 4) return false;
        String key = method.name + method.desc;
        if (!visiting.add(key)) return false;
        try {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || !owner.name.equals(call.owner)) continue;
                if ((call.name + call.desc).equals(target)) return true;
                MethodNode local = localMethod(owner, call.name, call.desc);
                if (local != null && reaches(owner, local, target, visiting, depth + 1)) return true;
            }
            return false;
        } finally {
            visiting.remove(key);
        }
    }

    private static MethodNode localMethod(ClassNode owner, String name, String descriptor) {
        if (owner == null || owner.methods == null) return null;
        for (MethodNode candidate : owner.methods) {
            if (name.equals(candidate.name) && descriptor.equals(candidate.desc)) return candidate;
        }
        return null;
    }

    private static boolean framebufferCall(MethodNode method) {
        if (method == null || method.instructions == null) return false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || call.desc == null) continue;
            String descriptor = call.desc;
            if (descriptor.contains("RenderTarget") || descriptor.contains("Framebuffer")
                    || descriptor.contains("AdvancedFbo")) return true;
        }
        return false;
    }

    private static boolean renderMethod(MethodNode method) {
        if (method == null || method.instructions == null || method.desc == null) return false;
        if (!method.desc.contains("Lnet/minecraft/client/gui/GuiGraphics;")) return false;
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;
        return true;
    }

    private static void collectRenderCalls(MethodNode method, Set<String> out) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || call.desc == null) continue;
            if (call.desc.contains("Lnet/minecraft/client/gui/GuiGraphics;")
                    || call.desc.contains("RenderTarget") || call.desc.contains("Framebuffer")
                    || call.desc.contains("AdvancedFbo") || call.desc.contains("SubLevel;")) {
                out.add(call.owner + '#' + call.name + call.desc);
            }
        }
    }

    /** Recover concrete context-field provenance for downstream GUI render arguments. */
    private static List<FieldBinding> contextBindings(ClassNode owner, MethodNode method) {
        ArrayList<FieldBinding> best = new ArrayList<>();
        try {
            Frame<SourceValue>[] frames = new Analyzer<>(new SourceInterpreter())
                    .analyze(owner.name, method);
            int instructionIndex = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.desc != null
                        && call.desc.contains("Lnet/minecraft/client/gui/GuiGraphics;")) {
                    List<FieldBinding> bindings = bindingsAt(frames[instructionIndex], call);
                    if (bindings.size() > best.size()) best = new ArrayList<>(bindings);
                }
                instructionIndex++;
            }
        } catch (AnalyzerException | RuntimeException ignored) {
        }
        return List.copyOf(best);
    }

    /**
     * HUD frameworks commonly split the public overlay callback from the module renderer that owns
     * its rectangle. Follow exact helper calls and interface implementations rather than requiring
     * x/y/w/h to appear in the entry method itself. This remains passive: only classfile methods
     * already present in the scanned archive are inspected.
     */
    private static List<FieldBinding> contextBindingsDeep(Map<String, ClassNode> nodes,
                                                          ClassNode owner, MethodNode root) {
        if (nodes == null || owner == null || root == null) return List.of();
        record Pending(ClassNode owner, MethodNode method, int depth) {}
        java.util.ArrayDeque<Pending> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        ArrayList<FieldBinding> best = new ArrayList<>();
        queue.add(new Pending(owner, root, 0));
        while (!queue.isEmpty() && seen.size() < 512) {
            Pending pending = queue.removeFirst();
            String key = pending.owner.name + '#' + pending.method.name + pending.method.desc;
            if (!seen.add(key)) continue;
            List<FieldBinding> direct = enrichContextBindings(nodes,
                    contextBindings(pending.owner, pending.method));
            if (direct.size() > best.size()) best = new ArrayList<>(direct);
            if (hasContextRect(best) || pending.depth >= 5) continue;
            for (AbstractInsnNode instruction : pending.method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || call.owner == null) continue;
                if (call.owner.startsWith("java/") || call.owner.startsWith("javax/")
                        || call.owner.startsWith("net/minecraft/") || call.owner.startsWith("com/mojang/")) {
                    continue;
                }
                ClassNode exactOwner = methodOwner(nodes, call.owner, call.name, call.desc);
                MethodNode exact = method(exactOwner, call.name, call.desc);
                if (exact != null) queue.addLast(new Pending(exactOwner, exact, pending.depth + 1));
                if (call.getOpcode() == Opcodes.INVOKEINTERFACE) {
                    for (ClassNode candidate : nodes.values()) {
                        if (candidate == null || candidate.name == null
                                || !implementsType(candidate, call.owner, nodes)) continue;
                        MethodNode implementation = method(candidate, call.name, call.desc);
                        if (implementation != null) {
                            queue.addLast(new Pending(candidate, implementation, pending.depth + 1));
                        }
                    }
                }
            }
        }
        return List.copyOf(best);
    }

    /**
     * Finds a renderer-owned symmetric padding constant without relying on mod names or field
     * names. Runtime-sized HUDs often receive only an outer module rectangle while their renderer
     * repeatedly positions terrain/chrome at {@code x + inset}/{@code y + inset}. A value is only
     * published when one small positive integer is strongly dominant across the reachable render
     * graph, avoiding guesses from isolated texture dimensions or one-off widget offsets.
     */
    private static int dominantSmallLayoutOffset(Map<String, ClassNode> nodes,
                                                 ClassNode owner, MethodNode root) {
        if (nodes == null || owner == null || root == null) return 0;
        record Pending(ClassNode owner, MethodNode method, int depth) {}
        java.util.ArrayDeque<Pending> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        java.util.HashMap<Integer, Integer> counts = new java.util.HashMap<>();
        queue.add(new Pending(owner, root, 0));
        while (!queue.isEmpty() && seen.size() < 96) {
            Pending pending = queue.removeFirst();
            String key = pending.owner.name + '#' + pending.method.name + pending.method.desc;
            if (!seen.add(key)) continue;
            for (AbstractInsnNode instruction : pending.method.instructions) {
                Integer constant = smallPositiveInt(instruction);
                AbstractInsnNode next = nextOpcode(instruction);
                if (constant != null && next != null && next.getOpcode() == Opcodes.IADD) {
                    counts.merge(constant, 1, Integer::sum);
                }
                if (!(instruction instanceof MethodInsnNode call) || call.owner == null
                        || pending.depth >= 12) continue;
                if (call.owner.startsWith("java/") || call.owner.startsWith("javax/")
                        || call.owner.startsWith("net/minecraft/") || call.owner.startsWith("com/mojang/")) {
                    continue;
                }
                ClassNode exactOwner = methodOwner(nodes, call.owner, call.name, call.desc);
                MethodNode exact = method(exactOwner, call.name, call.desc);
                if (exact != null) queue.addLast(new Pending(exactOwner, exact, pending.depth + 1));
                if (call.getOpcode() == Opcodes.INVOKEINTERFACE) {
                    for (ClassNode candidate : nodes.values()) {
                        if (candidate == null || candidate.name == null
                                || !implementsType(candidate, call.owner, nodes)) continue;
                        MethodNode implementation = method(candidate, call.name, call.desc);
                        if (implementation != null) {
                            queue.addLast(new Pending(candidate, implementation, pending.depth + 1));
                        }
                    }
                }
            }
        }
        int bestValue = 0, bestCount = 0, secondCount = 0;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            int count = entry.getValue();
            if (count > bestCount) {
                secondCount = bestCount;
                bestCount = count;
                bestValue = entry.getKey();
            } else if (count > secondCount) {
                secondCount = count;
            }
        }
        return bestCount >= 6 && bestCount >= secondCount * 2 ? bestValue : 0;
    }

    private static Integer smallPositiveInt(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        int opcode = instruction.getOpcode();
        int value;
        if (instruction instanceof IntInsnNode integer) {
            value = integer.operand;
        } else if (opcode >= Opcodes.ICONST_1 && opcode <= Opcodes.ICONST_5) {
            value = opcode - Opcodes.ICONST_0;
        } else {
            return null;
        }
        return value >= 1 && value <= 16 ? value : null;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction == null ? null : instruction.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    private static MethodNode method(ClassNode owner, String name, String desc) {
        if (owner == null || owner.methods == null) return null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)
                    && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) return method;
        }
        return null;
    }

    /** Resolve the class that actually declares an invoked method. Bytecode owners may be concrete
     * subclasses even when the implementation is inherited; stopping at the immediate owner loses
     * renderer/layout evidence behind ordinary virtual dispatch. */
    private static ClassNode methodOwner(Map<String, ClassNode> nodes, String ownerName,
                                         String name, String desc) {
        if (nodes == null || ownerName == null) return null;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        ClassNode current = nodes.get(ownerName);
        while (current != null && current.name != null && seen.add(current.name)) {
            if (method(current, name, desc) != null) return current;
            current = current.superName == null ? null : nodes.get(current.superName);
        }
        return null;
    }

    private static boolean implementsType(ClassNode node, String wanted, Map<String, ClassNode> nodes) {
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        queue.add(node.name);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!seen.add(current)) continue;
            if (wanted.equals(current)) return true;
            ClassNode type = nodes.get(current);
            if (type == null) continue;
            if (type.superName != null) queue.addLast(type.superName);
            if (type.interfaces != null) queue.addAll(type.interfaces);
        }
        return false;
    }

    private static List<FieldBinding> bindingsAt(Frame<SourceValue> frame, MethodInsnNode call) {
        if (frame == null) return List.of();
        Type[] args;
        try { args = Type.getArgumentTypes(call.desc); }
        catch (IllegalArgumentException ignored) { return List.of(); }
        int first = frame.getStackSize() - args.length;
        if (call.getOpcode() != Opcodes.INVOKESTATIC) first--;
        if (first < 0) return List.of();

        ArrayList<FieldBinding> primitive = new ArrayList<>();
        int guiIndex = -1;
        for (int i = 0; i < args.length; i++) {
            Type type = args[i];
            if (type.getSort() == Type.OBJECT
                    && "net/minecraft/client/gui/GuiGraphics".equals(type.getInternalName())) {
                guiIndex = i;
                continue;
            }
            if (guiIndex < 0 || i <= guiIndex || !primitive(type)) continue;
            SourceValue value = frame.getStack(first + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1) + i);
            FieldInsnNode field = uniqueFieldSource(value);
            if (field == null || field.getOpcode() != Opcodes.GETFIELD) continue;
            FieldBinding binding = new FieldBinding();
            binding.owner = field.owner;
            binding.field = field.name;
            binding.descriptor = field.desc;
            primitive.add(binding);
        }
        if (primitive.size() < 2) return List.of();

        // A render-context forwarding call conventionally starts x, y, screenWidth, screenHeight.
        // This is not based on field names: roles follow primitive argument position after the
        // concrete GuiGraphics argument and are only assigned when all values come from one owner.
        String contextOwner = primitive.get(0).owner;
        if (primitive.stream().filter(value -> contextOwner.equals(value.owner)).count() < 2) return List.of();
        ArrayList<FieldBinding> sameOwner = new ArrayList<>();
        for (FieldBinding binding : primitive) if (contextOwner.equals(binding.owner)) sameOwner.add(binding);
        // Prefer semantic context-field names when the framework preserves them. In particular,
        // screenWidth/screenHeight are viewport dimensions, not the module's own w/h. Treating the
        // viewport as the HUD rectangle made passive maps appear oversized. Keep the positional
        // fallback for remapped/obfuscated frameworks where these names are unavailable.
        String[] fallbackRoles = {"x", "y", "surfaceWidth", "surfaceHeight", "scale", "contentWidth", "contentHeight"};
        for (int i = 0; i < sameOwner.size(); i++) {
            FieldBinding binding = sameOwner.get(i);
            binding.role = semanticContextRole(binding.field);
            if (binding.role == null && i < fallbackRoles.length) binding.role = fallbackRoles[i];
        }
        return sameOwner;
    }

    private static String semanticContextRole(String field) {
        if (field == null || field.isBlank()) return null;
        String lower = field.toLowerCase(java.util.Locale.ROOT).replace("_", "");
        if ("x".equals(lower)) return "x";
        if ("y".equals(lower)) return "y";
        if (lower.contains("screenwidth") || lower.contains("viewportwidth")) return "screenWidth";
        if (lower.contains("screenheight") || lower.contains("viewportheight")) return "screenHeight";
        if (lower.contains("screenscale") || lower.equals("scale")) return "scale";
        if (lower.equals("w") || lower.equals("width") || lower.contains("surfacewidth")
                || lower.contains("modulewidth")) return "surfaceWidth";
        if (lower.equals("h") || lower.equals("height") || lower.contains("surfaceheight")
                || lower.contains("moduleheight")) return "surfaceHeight";
        if (lower.contains("contentwidth")) return "contentWidth";
        if (lower.contains("contentheight")) return "contentHeight";
        return null;
    }

    /**
     * Some square HUD renderers forward only their width because the downstream renderer already
     * knows height == width. If the context type itself exposes the matching semantic height field,
     * retain that binding as rectangle evidence rather than misusing screenHeight as the surface.
     */
    private static List<FieldBinding> enrichContextBindings(Map<String, ClassNode> nodes,
                                                            List<FieldBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) return List.of();
        ArrayList<FieldBinding> out = new ArrayList<>(bindings);
        boolean width = out.stream().anyMatch(binding -> "surfaceWidth".equals(binding.role));
        boolean height = out.stream().anyMatch(binding -> "surfaceHeight".equals(binding.role));
        if (!width || height) return List.copyOf(out);
        String owner = out.stream().filter(binding -> "surfaceWidth".equals(binding.role))
                .map(binding -> binding.owner).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        ClassNode context = owner == null ? null : nodes.get(owner);
        if (context == null || context.fields == null) return List.copyOf(out);
        for (org.objectweb.asm.tree.FieldNode field : context.fields) {
            if (!"surfaceHeight".equals(semanticContextRole(field.name))) continue;
            FieldBinding added = new FieldBinding();
            added.role = "surfaceHeight";
            added.owner = owner;
            added.field = field.name;
            added.descriptor = field.desc;
            out.add(added);
            break;
        }
        return List.copyOf(out);
    }

    private static boolean primitive(Type type) {
        return switch (type.getSort()) {
            case Type.BYTE, Type.SHORT, Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE, Type.BOOLEAN -> true;
            default -> false;
        };
    }

    private static FieldInsnNode uniqueFieldSource(SourceValue value) {
        if (value == null || value.insns == null || value.insns.isEmpty()) return null;
        FieldInsnNode found = null;
        for (AbstractInsnNode source : value.insns) {
            if (!(source instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD) continue;
            if (found != null && (!found.owner.equals(field.owner) || !found.name.equals(field.name))) {
                return null;
            }
            found = field;
        }
        return found;
    }

    private static Map<String, SpriteRef> backgrounds(GuiSpriteBytecodeScanner.Result result) {
        if (result == null) return Map.of();
        Map<String, GuiSpriteBytecodeScanner.Sprite> sprites = new LinkedHashMap<>();
        for (var sprite : result.sprites) {
            if (sprite == null || sprite.owner == null || sprite.field == null) continue;
            sprites.put(normalize(sprite.owner) + '.' + sprite.field, sprite);
        }
        Map<String, SpriteRef> out = new LinkedHashMap<>();
        for (var background : result.backgrounds) {
            if (background == null || background.screenClass == null) continue;
            var sprite = sprites.get(normalize(background.spriteOwner) + '.' + background.spriteField);
            if (sprite == null || sprite.width <= 0 || sprite.height <= 0) continue;
            SpriteRef candidate = new SpriteRef(sprite.owner, sprite.field, sprite.width, sprite.height);
            out.merge(normalize(background.screenClass), candidate,
                    (left, right) -> (long) right.width * right.height
                            > (long) left.width * left.height ? right : left);
        }
        return out;
    }

    private static boolean hasContextRect(List<FieldBinding> bindings) {
        boolean x = false, y = false, width = false, height = false;
        for (FieldBinding binding : bindings) {
            x |= "x".equals(binding.role);
            y |= "y".equals(binding.role);
            width |= "surfaceWidth".equals(binding.role);
            height |= "surfaceHeight".equals(binding.role);
        }
        return x && y && width && height;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('.', '/');
    }
}
