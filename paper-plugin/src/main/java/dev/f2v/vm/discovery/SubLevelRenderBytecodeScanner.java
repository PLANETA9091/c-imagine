package dev.f2v.vm.discovery;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Describes client screens that render a live moving sublevel as a 3D viewport.
 *
 * <p>This is deliberately separate from {@link DynamicSurfaceBytecodeScanner}: request/response
 * dataflow says where live labels/forces come from, while this scanner says the screen's central
 * visual is the sublevel itself. Runtime compatibility can therefore choose an orthographic
 * structure projection only when the mod bytecode proves that rendering contract.</p>
 */
public final class SubLevelRenderBytecodeScanner {

    public static final int SCHEMA = 2;

    public static final class Surface {
        public String screenClass;
        public String rootMethod;
        public boolean orthographic;
        /** Calls that receive the sublevel together with render matrices/buffers. */
        public final Set<String> renderCalls = new LinkedHashSet<>();
        /** Numeric orientation/config accessors reached by the render path. */
        public final Set<String> orientationAccessors = new LinkedHashSet<>();
        public final Set<String> subLevelTypes = new LinkedHashSet<>();
        /** Native pixel extent of the framebuffer/sprite surface the sublevel is composited into.
         * This is attached from the exact background sprite selected by the same screen bytecode;
         * zero means the extent could not be proven. */
        public int viewportWidth;
        public int viewportHeight;

        @Override public String toString() {
            return screenClass + '#' + rootMethod + " ortho=" + orthographic
                    + " render=" + renderCalls + " orientation=" + orientationAccessors
                    + " viewport=" + viewportWidth + 'x' + viewportHeight;
        }
    }

    private SubLevelRenderBytecodeScanner() {}

    public static List<Surface> scan(Map<String, ClassNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        ArrayList<Surface> out = new ArrayList<>();
        for (ClassNode node : nodes.values()) {
            if (node == null || node.methods == null) continue;
            for (MethodNode method : node.methods) {
                if (!subLevelRenderRoot(method)) continue;
                Surface surface = new Surface();
                surface.screenClass = node.name;
                surface.rootMethod = method.name + method.desc;
                collect(node.name, method, nodes, surface, new LinkedHashSet<>(), 0);
                if (!surface.renderCalls.isEmpty()) {
                    collectScreenOrientationAccessors(node, surface);
                    out.add(surface);
                }
            }
        }
        out.sort(Comparator.comparing((Surface value) -> value.screenClass == null ? "" : value.screenClass)
                .thenComparing(value -> value.rootMethod == null ? "" : value.rootMethod));
        return List.copyOf(out);
    }

    /**
     * Same render scan, enriched with the exact authored screen sprite extent. Screens that render
     * an FBO back across their native background commonly use that same width/height as the
     * orthographic aspect ratio. Keeping the join screen-class based avoids any mod/name rule.
     */
    public static List<Surface> scan(Map<String, ClassNode> nodes,
                                     GuiSpriteBytecodeScanner.Result guiSprites) {
        List<Surface> surfaces = new ArrayList<>(scan(nodes));
        if (guiSprites == null || guiSprites.backgrounds == null || guiSprites.sprites == null
                || surfaces.isEmpty()) return List.copyOf(surfaces);
        java.util.Map<String, GuiSpriteBytecodeScanner.Sprite> sprites = new java.util.HashMap<>();
        for (GuiSpriteBytecodeScanner.Sprite sprite : guiSprites.sprites) {
            if (sprite == null || sprite.owner == null || sprite.field == null) continue;
            sprites.put(normalize(sprite.owner) + '.' + sprite.field, sprite);
        }
        java.util.Map<String, GuiSpriteBytecodeScanner.Sprite> backgrounds = new java.util.HashMap<>();
        for (GuiSpriteBytecodeScanner.ScreenBackground background : guiSprites.backgrounds) {
            if (background == null || background.screenClass == null) continue;
            GuiSpriteBytecodeScanner.Sprite sprite = sprites.get(
                    normalize(background.spriteOwner) + '.' + background.spriteField);
            if (sprite != null && sprite.width > 0 && sprite.height > 0) {
                backgrounds.merge(normalize(background.screenClass), sprite,
                        (left, right) -> (long) right.width * right.height
                                > (long) left.width * left.height ? right : left);
            }
        }
        for (Surface surface : surfaces) {
            GuiSpriteBytecodeScanner.Sprite sprite = backgrounds.get(normalize(surface.screenClass));
            if (sprite == null) continue;
            surface.viewportWidth = sprite.width;
            surface.viewportHeight = sprite.height;
        }
        return List.copyOf(surfaces);
    }

    public static Surface forScreen(Collection<ModScanner.Profile> profiles, String screenClass) {
        if (profiles == null || screenClass == null) return null;
        String wanted = normalize(screenClass);
        for (ModScanner.Profile profile : profiles) {
            Surface found = forScreen(profile, wanted);
            if (found != null) return found;
        }
        return null;
    }

    private static Surface forScreen(ModScanner.Profile profile, String wanted) {
        if (profile == null) return null;
        Surface best = null;
        int bestScore = Integer.MIN_VALUE;
        if (profile.subLevelRenderSurfaces != null) {
            for (Surface surface : profile.subLevelRenderSurfaces) {
                if (surface == null || !wanted.equals(normalize(surface.screenClass))) continue;
                int score = (surface.orthographic ? 1000 : 0)
                        + surface.renderCalls.size() * 100
                        + surface.orientationAccessors.size() * 10
                        + surface.subLevelTypes.size();
                if (score > bestScore) {
                    best = surface;
                    bestScore = score;
                }
            }
        }
        if (best != null) return best;
        if (profile.nested != null) {
            for (ModScanner.Profile nested : profile.nested) {
                Surface found = forScreen(nested, wanted);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean subLevelRenderRoot(MethodNode method) {
        if (method == null || method.desc == null || method.instructions == null) return false;
        if (!method.desc.contains("SubLevel;")) return false;
        String name = method.name == null ? "" : method.name.toLowerCase(Locale.ROOT);
        if (!(name.contains("render") || name.contains("draw") || name.startsWith("m_"))) return false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && renderApi(call.desc)) return true;
        }
        return false;
    }

    private static void collect(String owner, MethodNode method, Map<String, ClassNode> nodes,
                                Surface surface, Set<String> visiting, int depth) {
        if (method == null || method.instructions == null || depth > 5) return;
        String key = owner + '#' + method.name + method.desc;
        if (!visiting.add(key)) return;
        try {
            collectSubLevelTypes(method.desc, surface.subLevelTypes);
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (orthographicCall(call)) surface.orthographic = true;
                if (subLevelRenderCall(call)) {
                    surface.renderCalls.add(call.owner + '#' + call.name + call.desc);
                    collectSubLevelTypes(call.desc, surface.subLevelTypes);
                }
                if (orientationAccessor(call)) {
                    surface.orientationAccessors.add(call.owner + '#' + call.name + call.desc);
                }
                if (nodes == null || !owner.equals(call.owner) || "<init>".equals(call.name)) continue;
                MethodNode local = findMethod(nodes.get(call.owner), call.name, call.desc);
                if (local != null) collect(call.owner, local, nodes, surface, visiting, depth + 1);
            }
        } finally {
            visiting.remove(key);
        }
    }

    private static boolean subLevelRenderCall(MethodInsnNode call) {
        if (call == null || call.desc == null || !call.desc.contains("SubLevel;")) return false;
        if (Type.getReturnType(call.desc).getSort() != Type.VOID) return false;
        return renderApi(call.desc);
    }

    private static boolean renderApi(String descriptor) {
        if (descriptor == null) return false;
        return descriptor.contains("Matrix4f") || descriptor.contains("PoseStack")
                || descriptor.contains("MultiBufferSource") || descriptor.contains("Framebuffer")
                || descriptor.contains("AdvancedFbo") || descriptor.contains("VertexConsumer");
    }

    private static boolean orthographicCall(MethodInsnNode call) {
        if (call == null) return false;
        String owner = call.owner == null ? "" : call.owner;
        String name = call.name == null ? "" : call.name.toLowerCase(Locale.ROOT);
        return owner.contains("Matrix4") && (name.equals("ortho") || name.contains("orthographic"));
    }

    private static boolean orientationAccessor(MethodInsnNode call) {
        if (call == null || call.desc == null) return false;
        String name = call.name == null ? "" : call.name.toLowerCase(Locale.ROOT);
        if (!(name.equals("yaw") || name.equals("pitch") || name.equals("roll")
                || name.contains("orientation") || name.contains("rotation"))) return false;
        Type returnType;
        try {
            returnType = Type.getReturnType(call.desc);
        } catch (Exception ignored) {
            return false;
        }
        return returnType.getSort() == Type.FLOAT || returnType.getSort() == Type.DOUBLE
                || returnType.getSort() == Type.OBJECT;
    }

    private static void collectScreenOrientationAccessors(ClassNode node, Surface surface) {
        if (node == null || node.methods == null || surface == null) return;
        for (MethodNode method : node.methods) {
            if (method == null || method.instructions == null) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && orientationAccessor(call)) {
                    surface.orientationAccessors.add(call.owner + '#' + call.name + call.desc);
                }
            }
        }
    }

    private static void collectSubLevelTypes(String descriptor, Set<String> out) {
        if (descriptor == null) return;
        try {
            for (Type type : Type.getArgumentTypes(descriptor)) {
                if (type.getSort() == Type.OBJECT && type.getInternalName() != null
                        && type.getInternalName().contains("SubLevel")) {
                    out.add(type.getInternalName());
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static MethodNode findMethod(ClassNode node, String name, String desc) {
        if (node == null || node.methods == null) return null;
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('.', '/');
    }
}
