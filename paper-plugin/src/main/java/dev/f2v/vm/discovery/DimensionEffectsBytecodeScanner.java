package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Recovers client-only DimensionSpecialEffects geometry without loading client classes. */
public final class DimensionEffectsBytecodeScanner {
    private static final String ROOT = "net/minecraft/client/renderer/DimensionSpecialEffects";

    public enum TextureRole { CLOUD, CELESTIAL, BACKDROP }

    private DimensionEffectsBytecodeScanner() {}

    public static final class Surface {
        public String clazz;
        public float cloudHeight = Float.NaN;
        public String skyType;
        /** True when the effect overrides/delegates custom sky rendering. */
        public boolean customSky;
        /** True when the effect overrides/delegates custom cloud rendering. */
        public boolean customClouds;
        /** Static sky/celestial textures recovered from the effect and renderer helpers. */
        public final List<String> skyTextures = new ArrayList<>();
        /** Custom render code was found but no static texture could represent it exactly. */
        public boolean proceduralSky;
    }

    public static List<Surface> scan(Map<String, ClassNode> nodes) {
        List<Surface> out = new ArrayList<>();
        if (nodes == null || nodes.isEmpty()) return out;
        for (ClassNode node : nodes.values()) {
            if (node == null || node.name == null || !inherits(node, nodes, ROOT)) continue;
            Surface surface = inspect(node, nodes);
            if (surface != null) out.add(surface);
        }
        return out;
    }

    private static Surface inspect(ClassNode node, Map<String, ClassNode> nodes) {
        Surface surface = new Surface();
        surface.clazz = node.name;
        for (MethodNode method : node.methods) {
            if ("<init>".equals(method.name)) {
                Float lastFloat = null;
                String lastSkyType = null;
                for (AbstractInsnNode instruction : method.instructions) {
                    Float numeric = floatConstant(instruction);
                    if (numeric != null) lastFloat = numeric;
                    if (instruction instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.GETSTATIC
                            && field.owner.endsWith("DimensionSpecialEffects$SkyType")) {
                        lastSkyType = field.name;
                    }
                    if (instruction instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKESPECIAL
                            && "<init>".equals(call.name) && ROOT.equals(call.owner)) {
                        surface.cloudHeight = lastFloat == null ? Float.NaN : lastFloat;
                        surface.skyType = lastSkyType;
                    }
                }
            }
            String lower = method.name == null ? "" : method.name.toLowerCase(Locale.ROOT);
            if (lower.contains("rendersky") || lower.equals("sky")) surface.customSky = true;
            if (lower.contains("rendercloud")) surface.customClouds = true;
        }
        LinkedHashSet<String> textures = new LinkedHashSet<>();
        collectRendererTextures(node, nodes, textures, new HashSet<>(), 0);
        surface.skyTextures.addAll(textures);
        surface.proceduralSky = surface.customSky && surface.skyTextures.isEmpty();
        return Float.isFinite(surface.cloudHeight) || surface.skyType != null || surface.customSky
                || surface.customClouds || !surface.skyTextures.isEmpty() ? surface : null;
    }

    private static void collectRendererTextures(ClassNode node, Map<String, ClassNode> nodes,
                                                LinkedHashSet<String> textures,
                                                Set<String> seen, int depth) {
        if (node == null || node.name == null || depth > 3 || !seen.add(node.name)) return;
        for (org.objectweb.asm.tree.FieldNode field : node.fields) {
            if (field.value instanceof String value) addTexture(value, textures);
        }
        LinkedHashSet<String> referenced = new LinkedHashSet<>();
        for (MethodNode method : node.methods) {
            String methodName = method.name == null ? "" : method.name.toLowerCase(Locale.ROOT);
            boolean skyMethod = depth > 0 || "<clinit>".equals(method.name) || "<init>".equals(method.name)
                    || methodName.contains("sky") || methodName.contains("cloud")
                    || methodName.contains("sun") || methodName.contains("moon")
                    || methodName.contains("star") || methodName.contains("celestial");
            if (!skyMethod) continue;
            String previousString = null;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                    addTexture(value, textures);
                    previousString = value;
                    continue;
                }
                if (instruction instanceof MethodInsnNode call) {
                    if (call.owner != null) referenced.add(call.owner);
                    // ResourceLocation(namespace, path) / helper factories often leave the path
                    // as the closest preceding LDC even when the namespace is another LDC.
                    if (previousString != null && (call.owner.endsWith("ResourceLocation")
                            || call.name.toLowerCase(Locale.ROOT).contains("resource"))) {
                        addTexture(previousString, textures);
                    }
                } else if (instruction instanceof FieldInsnNode field) {
                    if (field.owner != null) referenced.add(field.owner);
                    addObjectType(field.desc, referenced);
                } else if (instruction instanceof TypeInsnNode type) {
                    if (type.desc != null) referenced.add(type.desc);
                }
            }
        }
        for (String owner : referenced) {
            if (owner == null || owner.startsWith("java/") || owner.startsWith("net/minecraft/")) continue;
            ClassNode helper = nodes.get(owner);
            if (helper != null) collectRendererTextures(helper, nodes, textures, seen, depth + 1);
        }
    }

    private static void addObjectType(String descriptor, Set<String> out) {
        if (descriptor == null || out == null) return;
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            out.add(descriptor.substring(1, descriptor.length() - 1));
        }
    }

    private static void addTexture(String raw, LinkedHashSet<String> out) {
        if (raw == null || out == null) return;
        String value = raw.trim().replace('\\', '/');
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("assets/")) {
            String rest = value.substring("assets/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) value = rest.substring(0, slash) + ':' + rest.substring(slash + 1);
            lower = value.toLowerCase(Locale.ROOT);
        }
        int colon = value.indexOf(':');
        String path = colon >= 0 ? value.substring(colon + 1) : value;
        String pathLower = path.toLowerCase(Locale.ROOT);
        if (!pathLower.endsWith(".png")) return;
        if (!(pathLower.contains("textures/") || pathLower.contains("environment/")
                || pathLower.contains("sky/") || pathLower.contains("celestial/"))) return;
        out.add(value);
    }

    private static Float floatConstant(AbstractInsnNode instruction) {
        if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Number number) {
            return number.floatValue();
        }
        return switch (instruction.getOpcode()) {
            case Opcodes.FCONST_0 -> 0f;
            case Opcodes.FCONST_1 -> 1f;
            case Opcodes.FCONST_2 -> 2f;
            default -> null;
        };
    }

    private static boolean inherits(ClassNode node, Map<String, ClassNode> nodes, String target) {
        String current = node.superName;
        Set<String> seen = new HashSet<>();
        while (current != null && seen.add(current)) {
            if (target.equals(current)) return true;
            ClassNode parent = nodes.get(current);
            current = parent == null ? null : parent.superName;
        }
        return false;
    }

    /** Selects the closest client effect for a dimension effect id. */
    public static Surface bestForEffect(Collection<ModScanner.Profile> profiles,
                                        String namespace, String effectPath) {
        List<Surface> candidates = new ArrayList<>();
        collect(profiles, namespace, candidates);
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);
        String wanted = normalize(effectPath).replaceFirst("^the", "");
        Surface best = null;
        int bestScore = 0;
        for (Surface surface : candidates) {
            String leaf = surface.clazz == null ? "" : surface.clazz.substring(surface.clazz.lastIndexOf('/') + 1);
            String candidate = normalize(leaf)
                    .replace("dimensionspecialeffects", "")
                    .replace("specialeffects", "")
                    .replace("skyrendereffects", "")
                    .replace("rendereffects", "")
                    .replace("effects", "")
                    .replace("sky", "");
            int score = candidate.equals(wanted) ? 100
                    : (!candidate.isBlank() && (candidate.contains(wanted) || wanted.contains(candidate))) ? 60 : 0;
            if (score > bestScore) {
                best = surface;
                bestScore = score;
            }
        }
        return best;
    }

    private static void collect(Collection<ModScanner.Profile> profiles, String namespace,
                                List<Surface> out) {
        if (profiles == null) return;
        for (ModScanner.Profile profile : profiles) {
            if (profile == null) continue;
            if (namespace == null || namespace.equals(profile.modId)) out.addAll(profile.dimensionEffects);
            collect(profile.nested, namespace, out);
        }
    }

    /** All static atmosphere textures in stable build/runtime order, normalized to namespace:path. */
    public static List<String> allSkyTextures(Collection<ModScanner.Profile> profiles) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        collectSkyTextures(profiles, out);
        return out.stream().sorted().toList();
    }

    private static void collectSkyTextures(Collection<ModScanner.Profile> profiles,
                                           LinkedHashSet<String> out) {
        if (profiles == null) return;
        for (ModScanner.Profile profile : profiles) {
            if (profile == null) continue;
            String namespace = profile.modId == null || profile.modId.isBlank() || "?".equals(profile.modId)
                    ? "minecraft" : profile.modId;
            for (Surface surface : profile.dimensionEffects) {
                if (surface == null) continue;
                for (String raw : surface.skyTextures) {
                    String normalized = normalizeTexture(namespace, raw);
                    if (normalized != null) out.add(normalized);
                }
            }
            collectSkyTextures(profile.nested, out);
        }
    }

    public static List<String> texturesForEffect(Surface surface, String namespace) {
        if (surface == null || surface.skyTextures.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : surface.skyTextures) {
            String normalized = normalizeTexture(namespace, raw);
            if (normalized != null) out.add(normalized);
        }
        return List.copyOf(out);
    }

    /** Stable global texture index shared by pack generation and runtime CMD lookup. */
    public static int textureIndex(Collection<ModScanner.Profile> profiles, String normalizedTexture) {
        if (normalizedTexture == null) return -1;
        List<String> all = allSkyTextures(profiles);
        int index = java.util.Collections.binarySearch(all, normalizedTexture);
        return index >= 0 ? index : -1;
    }

    /** Structural rendering role inferred from the renderer-owned resource path. */
    public static TextureRole textureRole(String normalizedTexture) {
        if (normalizedTexture == null) return TextureRole.CELESTIAL;
        String lower = normalizedTexture.toLowerCase(Locale.ROOT);
        String path = lower.substring(Math.max(0, lower.indexOf(':') + 1));
        if (path.contains("cloud")) return TextureRole.CLOUD;
        if (path.contains("skybox") || path.contains("starfield") || path.contains("stars")
                || path.contains("background") || path.contains("backlight")
                || path.contains("milky_way") || path.contains("nebula")) {
            return TextureRole.BACKDROP;
        }
        // Renderer-owned environment textures are overwhelmingly celestial bodies (sun/moon/
        // planets). Unknown static sky textures are safer as a billboard than as a world-filling
        // backdrop because a wrong backdrop can obscure the whole dimension.
        return TextureRole.CELESTIAL;
    }

    private static String normalizeTexture(String namespace, String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().replace('\\', '/');
        if (value.startsWith("assets/")) {
            String rest = value.substring("assets/".length());
            int slash = rest.indexOf('/');
            if (slash <= 0) return null;
            value = rest.substring(0, slash) + ':' + rest.substring(slash + 1);
        }
        if (value.indexOf(':') < 0) value = namespace + ':' + value;
        int colon = value.indexOf(':');
        String ns = value.substring(0, colon);
        String path = value.substring(colon + 1);
        if (path.startsWith("textures/")) path = path.substring("textures/".length());
        if (!path.toLowerCase(Locale.ROOT).endsWith(".png")) return null;
        path = path.substring(0, path.length() - 4);
        return ns + ':' + path;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
