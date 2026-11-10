package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds server-backed planar surfaces which exist as data rather than world blocks.
 *
 * <p>Teleport/warp surfaces are a common example: the server stores two spatial records in saved
 * data while the real mod client draws textured quads at their positions. A vanilla client gets
 * the teleport mechanics but never runs that renderer. This pass joins the data shape, client quad
 * renderer and server saved-data container by bytecode structure, without depending on mod names.</p>
 */
public final class SpatialSurfaceBytecodeScanner {
    public static final int SCHEMA = 5;

    public static final class SurfaceFamily {
        public String stateClass;
        public String linkClass;
        public String containerClass;
        public String containerGetter;
        public String containerGetterDesc;
        public String containerField;
        public final List<String> linkStateFields = new ArrayList<>();
        /** Optional ResourceLocation stored by the link and used by its client renderer to select
         * a visual family. Runtime code treats it as opaque data; pack discovery resolves matching
         * client assets structurally. */
        public String linkVariantField;
        /** Optional link-level readiness method. Two-ended surface renderers commonly draw only a
         * frame until both endpoints are live; preserving this state lets vanilla clients select
         * the matching closed/open visual without understanding the mod's mechanics. */
        public String linkOpenMethod;
        public String linkOpenMethodDesc;
        /** Server-side traversal method structurally proven to consume an Entity + Level and
         * mutate entity motion/position while consulting this two-ended link. This is stronger
         * evidence than a generic isOpen() flag and is what permits the vanilla collision bridge. */
        public String linkTraversalMethod;
        public String linkTraversalMethodDesc;
        /** Optional pure AABB factory/accessor used by the proven traversal routine immediately
         * before its entity-vs-surface intersection test. Runtime may invoke this helper to use the
         * mod's exact traversal volume without executing the mutating traversal routine itself. */
        public String traversalBoundsOwner;
        public String traversalBoundsMethod;
        public String traversalBoundsMethodDesc;
        public String positionField;
        public String dimensionField;
        /** Optional no-arg state accessor for the server-computed replacement collision shapes.
         * Portal-like mods often cache the exact VoxelShape cuts they apply around an aperture;
         * exposing this lets the runtime constrain its viewer-local air cells to the same set. */
        public String collisionShapesMethod;
        public String collisionShapesMethodDesc;
        public String xRotationField;
        public String yRotationField;
        public String visibleMethod;
        public String visibleMethodDesc;
        /** True when renderer bytecode feeds the serialized position directly into the
         * PoseStack's world translation. Retain this as renderer-contract evidence; runtime
         * placement still applies the same support-geometry clearance used for every planar
         * surface so an anchor inside a collidable cell cannot embed the compatibility carrier. */
        public boolean positionIsRenderAnchor;
        /** True when the state rotation floats are consumed by Axis.rotationDegrees-style
         * PoseStack rotations. Those values are already renderer-space degrees and therefore do
         * not use the opposite-sign blockstate JSON convention. */
        public boolean rendererPoseRotation;
        /** Full-open renderer-space size recovered from the quad emitter. These are world-unit
         * dimensions after constant PoseStack scales, not texture pixel dimensions. */
        public float renderWidth = 1.0f;
        public float renderHeight = 2.0f;
        /** Full-open local-space centre offset recovered from constant renderer translations.
         * Runtime rotates this vector with the surface, so wall/floor/ceiling surfaces all remain
         * slightly in front of the support geometry exactly as their client renderer authored. */
        public float renderOffsetX;
        public float renderOffsetY;
        public float renderOffsetZ;
        public int confidence;
    }

    private static final String VEC3 = "Lnet/minecraft/world/phys/Vec3;";
    private static final String RESOURCE_KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String SAVED_DATA = "net/minecraft/world/level/saveddata/SavedData";
    private static final String LEVEL = "Lnet/minecraft/world/level/Level;";
    private static final String SERVER = "Lnet/minecraft/server/MinecraftServer;";

    private SpatialSurfaceBytecodeScanner() {}

    public static List<SurfaceFamily> scan(Map<String, ClassNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        ArrayList<SurfaceFamily> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();

        for (ClassNode state : nodes.values()) {
            StateShape shape = stateShape(state);
            if (shape == null || !serialized(state)) continue;
            RenderEvidence render = renderEvidence(nodes.values(), state, shape);
            if (render == null) continue;

            LinkShape link = findLink(nodes.values(), state.name);
            if (link == null) continue;
            ContainerShape container = findContainer(nodes, link.clazz);
            if (container == null) continue;

            String token = state.name + '\n' + link.clazz + '\n' + container.clazz;
            if (!seen.add(token)) continue;
            SurfaceFamily family = new SurfaceFamily();
            family.stateClass = state.name;
            family.linkClass = link.clazz;
            family.containerClass = container.clazz;
            family.containerGetter = container.getter;
            family.containerGetterDesc = container.getterDesc;
            family.containerField = container.field;
            family.linkStateFields.addAll(link.stateFields);
            family.linkVariantField = link.variantField;
            family.linkOpenMethod = link.openMethod;
            family.linkOpenMethodDesc = link.openMethodDesc;
            family.linkTraversalMethod = link.traversalMethod;
            family.linkTraversalMethodDesc = link.traversalMethodDesc;
            family.traversalBoundsOwner = link.traversalBoundsOwner;
            family.traversalBoundsMethod = link.traversalBoundsMethod;
            family.traversalBoundsMethodDesc = link.traversalBoundsMethodDesc;
            family.positionField = render.positionField;
            family.dimensionField = shape.dimensionField;
            family.collisionShapesMethod = shape.collisionShapesMethod;
            family.collisionShapesMethodDesc = shape.collisionShapesMethodDesc;
            family.xRotationField = render.floatFields.get(0);
            family.yRotationField = render.floatFields.get(1);
            family.visibleMethod = render.visibleMethod;
            family.visibleMethodDesc = render.visibleDesc;
            family.positionIsRenderAnchor = render.positionIsRenderAnchor;
            family.rendererPoseRotation = render.rendererPoseRotation;
            family.renderWidth = render.geometry == null ? 1.0f : render.geometry.width;
            family.renderHeight = render.geometry == null ? 2.0f : render.geometry.height;
            family.renderOffsetX = render.geometry == null ? 0.0f : render.geometry.offsetX;
            family.renderOffsetY = render.geometry == null ? 0.0f : render.geometry.offsetY;
            family.renderOffsetZ = render.geometry == null ? 0.0f : render.geometry.offsetZ;
            family.confidence = 100;
            result.add(family);
        }
        result.sort(Comparator.comparing((SurfaceFamily f) -> f.stateClass)
                .thenComparing(f -> f.containerClass));
        return List.copyOf(result);
    }

    public static List<SurfaceFamily> all(Collection<ModScanner.Profile> profiles) {
        if (profiles == null) return List.of();
        ArrayList<SurfaceFamily> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collect(profile, result, seen);
        return List.copyOf(result);
    }

    private static void collect(ModScanner.Profile profile, List<SurfaceFamily> out, Set<String> seen) {
        if (profile == null) return;
        if (profile.spatialSurfaces != null) {
            for (SurfaceFamily family : profile.spatialSurfaces) {
                if (family == null || family.stateClass == null || family.containerClass == null) continue;
                String token = family.stateClass + '\n' + family.containerClass;
                if (seen.add(token)) out.add(family);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collect(child, out, seen);
        }
    }

    private static StateShape stateShape(ClassNode node) {
        if (node == null || node.fields == null || node.methods == null) return null;
        String position = null;
        String dimension = null;
        ArrayList<String> floats = new ArrayList<>();
        int directions = 0;
        for (FieldNode field : node.fields) {
            if ((field.access & Opcodes.ACC_STATIC) != 0) continue;
            if (VEC3.equals(field.desc) && position == null) position = field.name;
            else if (RESOURCE_KEY.equals(field.desc) && dimension == null) dimension = field.name;
            else if ("F".equals(field.desc)) floats.add(field.name);
            else if ("Lnet/minecraft/core/Direction;".equals(field.desc)) directions++;
        }
        if (position == null || dimension == null || floats.size() < 2 || directions == 0) return null;
        MethodNode collisionShapes = collisionShapesMethod(node);
        return new StateShape(position, dimension, List.copyOf(floats),
                collisionShapes == null ? null : collisionShapes.name,
                collisionShapes == null ? null : collisionShapes.desc);
    }

    /** Finds a state-owned List&lt;VoxelShape&gt; accessor without relying on field or method names. */
    private static MethodNode collisionShapesMethod(ClassNode state) {
        if (state == null || state.fields == null || state.methods == null) return null;
        LinkedHashSet<String> shapeFields = new LinkedHashSet<>();
        for (FieldNode field : state.fields) {
            if ((field.access & Opcodes.ACC_STATIC) != 0 || !"Ljava/util/List;".equals(field.desc)) continue;
            String signature = field.signature == null ? "" : field.signature;
            if (signature.contains("Lnet/minecraft/world/phys/shapes/VoxelShape;")) {
                shapeFields.add(field.name);
            }
        }
        if (shapeFields.isEmpty()) return null;
        for (MethodNode method : state.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0
                    || !"()Ljava/util/List;".equals(method.desc)) continue;
            String only = null;
            boolean ambiguous = false;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                        && field.owner.equals(state.name) && shapeFields.contains(field.name)) {
                    if (only == null) only = field.name;
                    else if (!only.equals(field.name)) ambiguous = true;
                }
            }
            if (only != null && !ambiguous) return method;
        }
        return null;
    }

    private static boolean serialized(ClassNode node) {
        for (MethodNode method : node.methods) {
            String desc = method.desc == null ? "" : method.desc;
            if (desc.contains("Lnet/minecraft/network/FriendlyByteBuf;")
                    || desc.contains("Lnet/minecraft/nbt/CompoundTag;")) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof FieldInsnNode field && field.owner.equals(node.name)) return true;
                }
            }
        }
        return false;
    }

    private static RenderEvidence renderEvidence(Collection<ClassNode> nodes, ClassNode state,
                                                  StateShape shape) {
        Map<String, String> getterFields = getterFields(state);
        RenderEvidence best = null;
        for (ClassNode renderer : nodes) {
            if (renderer == null || renderer.methods == null || renderer.name.equals(state.name)) continue;
            LinkedHashSet<String> floatFields = new LinkedHashSet<>();
            String positionField = null;
            String visibleMethod = null;
            String visibleDesc = null;
            boolean consumesState = false;
            boolean drawsQuad = false;
            boolean transforms = false;
            boolean positionIsRenderAnchor = false;
            boolean rendererPoseRotation = false;
            for (MethodNode method : renderer.methods) {
                boolean methodReadsPosition = false;
                boolean methodReadsRotation = false;
                boolean methodTranslatesPose = false;
                boolean methodBuildsAxisRotation = false;
                boolean methodAppliesQuaternion = false;
                for (AbstractInsnNode insn : method.instructions) {
                    if (!(insn instanceof MethodInsnNode call)) continue;
                    String owner = call.owner == null ? "" : call.owner;
                    if (owner.equals(state.name)) {
                        consumesState = true;
                        Type returnType;
                        try { returnType = Type.getReturnType(call.desc); }
                        catch (Exception ignored) { continue; }
                        String field = getterFields.get(call.name + call.desc);
                        if (returnType.getSort() == Type.OBJECT
                                && VEC3.equals(returnType.getDescriptor()) && field != null) {
                            positionField = field;
                            methodReadsPosition = true;
                        } else if (returnType.getSort() == Type.FLOAT && field != null) {
                            floatFields.add(field);
                            methodReadsRotation = true;
                        } else if (returnType.getSort() == Type.BOOLEAN
                                && Type.getArgumentTypes(call.desc).length == 0
                                && visibleMethod == null) {
                            visibleMethod = call.name;
                            visibleDesc = call.desc;
                        }
                    }
                    String lowerOwner = owner.toLowerCase(java.util.Locale.ROOT);
                    if (lowerOwner.contains("vertexconsumer") || lowerOwner.contains("bufferbuilder")
                            || lowerOwner.contains("multibuffersource")) drawsQuad = true;
                    if (owner.equals("com/mojang/blaze3d/vertex/PoseStack")) {
                        transforms = true;
                        if (isPoseTranslate(call)) methodTranslatesPose = true;
                        if ("(Lorg/joml/Quaternionf;)V".equals(call.desc)) {
                            methodAppliesQuaternion = true;
                        }
                    }
                    if (owner.equals("com/mojang/math/Axis")
                            && "(F)Lorg/joml/Quaternionf;".equals(call.desc)) {
                        methodBuildsAxisRotation = true;
                    }
                }
                positionIsRenderAnchor |= methodReadsPosition && methodTranslatesPose;
                rendererPoseRotation |= methodReadsRotation
                        && methodBuildsAxisRotation && methodAppliesQuaternion;
            }
            if (consumesState && drawsQuad && transforms && positionField != null
                    && floatFields.size() >= 2 && visibleMethod != null) {
                ArrayList<String> ordered = new ArrayList<>(floatFields);
                GeometryEvidence geometry = rendererGeometry(renderer, state.name);
                RenderEvidence candidate = new RenderEvidence(positionField, ordered, visibleMethod,
                        visibleDesc, geometry, positionIsRenderAnchor, rendererPoseRotation);
                // A direct quad emitter is more authoritative than an event/wrapper method that
                // merely delegates to one. Keep looking until we find the renderer that owns the
                // actual vertex coordinates and PoseStack scale/offset operations.
                if (geometry != null) return candidate;
                if (best == null) best = candidate;
            }
        }
        return best;
    }

    /**
     * Recovers a planar renderer's authored full-open geometry without executing client code.
     *
     * <p>This deliberately follows only constants immediately feeding PoseStack transforms and
     * vertex calls. Dynamic scales (opening animations, camera transforms, etc.) are ignored,
     * which leaves the stable full-size geometry. The rule is generic and version tolerant: it
     * understands mapped PoseStack names plus the vanilla 1.20 aliases used in production jars.</p>
     */
    private static GeometryEvidence rendererGeometry(ClassNode renderer, String stateClass) {
        GeometryEvidence best = null;
        for (MethodNode method : renderer.methods) {
            boolean consumesState = false;
            TransformState transform = new TransformState();
            ArrayList<TransformState> stack = new ArrayList<>();
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            int vertices = 0;
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                if (stateClass.equals(call.owner)) consumesState = true;

                if (isPosePush(call)) {
                    stack.add(new TransformState(transform));
                    continue;
                }
                if (isPosePop(call)) {
                    if (!stack.isEmpty()) transform = stack.remove(stack.size() - 1);
                    continue;
                }
                if (isPoseScale(call)) {
                    List<Double> values = immediateNumbers(call, 3);
                    if (values != null) {
                        transform.sx *= values.get(0).floatValue();
                        transform.sy *= values.get(1).floatValue();
                        transform.sz *= values.get(2).floatValue();
                    }
                    continue;
                }
                if (isPoseTranslate(call)) {
                    List<Double> values = immediateNumbers(call, 3);
                    if (values != null) {
                        // PoseStack post-multiplies: a translate following a scale is itself
                        // scaled. Preserve that matrix order in the recovered local transform.
                        transform.tx += values.get(0).floatValue() * transform.sx;
                        transform.ty += values.get(1).floatValue() * transform.sy;
                        transform.tz += values.get(2).floatValue() * transform.sz;
                    }
                    continue;
                }
                if (!isVertexPosition(call)) continue;
                List<Double> xyz = immediateNumbers(call, 3);
                if (xyz == null) continue;
                float x = xyz.get(0).floatValue() * transform.sx + transform.tx;
                float y = xyz.get(1).floatValue() * transform.sy + transform.ty;
                float z = xyz.get(2).floatValue() * transform.sz + transform.tz;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                vertices++;
            }
            if (!consumesState || vertices < 4) continue;
            float width = maxX - minX;
            float height = maxY - minY;
            if (!Float.isFinite(width) || !Float.isFinite(height)
                    || width < 0.05f || height < 0.05f || width > 64f || height > 64f) continue;
            GeometryEvidence candidate = new GeometryEvidence(width, height,
                    (minX + maxX) * .5f, (minY + maxY) * .5f, (minZ + maxZ) * .5f,
                    vertices);
            float area = candidate.width * candidate.height;
            float bestArea = best == null ? -1f : best.width * best.height;
            // Helper passes often draw a narrow depth/mask quad with more vertices than the
            // actual visible surface. Prefer the largest planar footprint; vertex count only
            // breaks ties between equally sized render passes.
            if (best == null || area > bestArea + 1.0e-4f
                    || Math.abs(area - bestArea) <= 1.0e-4f && candidate.vertices > best.vertices) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean isPosePush(MethodInsnNode call) {
        if (!"com/mojang/blaze3d/vertex/PoseStack".equals(call.owner) || !"()V".equals(call.desc)) return false;
        String name = call.name.toLowerCase(java.util.Locale.ROOT);
        return name.contains("push") || "m_85836_".equals(call.name);
    }

    private static boolean isPosePop(MethodInsnNode call) {
        if (!"com/mojang/blaze3d/vertex/PoseStack".equals(call.owner) || !"()V".equals(call.desc)) return false;
        String name = call.name.toLowerCase(java.util.Locale.ROOT);
        return name.contains("pop") || "m_85849_".equals(call.name);
    }

    private static boolean isPoseScale(MethodInsnNode call) {
        if (!"com/mojang/blaze3d/vertex/PoseStack".equals(call.owner) || !"(FFF)V".equals(call.desc)) return false;
        String name = call.name.toLowerCase(java.util.Locale.ROOT);
        return name.contains("scale") || "m_85841_".equals(call.name);
    }

    private static boolean isPoseTranslate(MethodInsnNode call) {
        if (!"com/mojang/blaze3d/vertex/PoseStack".equals(call.owner)) return false;
        if (!"(FFF)V".equals(call.desc) && !"(DDD)V".equals(call.desc)) return false;
        String name = call.name.toLowerCase(java.util.Locale.ROOT);
        return name.contains("translat") || "m_252880_".equals(call.name) || "m_85837_".equals(call.name);
    }

    private static boolean isVertexPosition(MethodInsnNode call) {
        if (call == null || call.desc == null) return false;
        String owner = call.owner == null ? "" : call.owner.toLowerCase(java.util.Locale.ROOT);
        if (!owner.contains("vertexconsumer") && !owner.contains("bufferbuilder")) return false;
        Type[] args;
        try { args = Type.getArgumentTypes(call.desc); }
        catch (Exception ignored) { return false; }
        if (args.length < 3) return false;
        int n = args.length;
        return args[n - 3].getSort() == Type.FLOAT
                && args[n - 2].getSort() == Type.FLOAT
                && args[n - 1].getSort() == Type.FLOAT;
    }

    /** Exactly the immediate constant producers for an invocation, ignoring labels/frames only. */
    private static List<Double> immediateNumbers(AbstractInsnNode point, int wanted) {
        ArrayList<Double> reverse = new ArrayList<>(wanted);
        for (AbstractInsnNode p = point == null ? null : point.getPrevious(); p != null && reverse.size() < wanted;
             p = p.getPrevious()) {
            if (p.getOpcode() < 0) continue;
            Double value = numberConstant(p);
            if (value == null) return null;
            reverse.add(value);
        }
        if (reverse.size() != wanted) return null;
        java.util.Collections.reverse(reverse);
        return reverse;
    }

    private static Double numberConstant(AbstractInsnNode node) {
        if (node == null) return null;
        int opcode = node.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return (double) (opcode - Opcodes.ICONST_0);
        }
        if (node instanceof IntInsnNode value) return (double) value.operand;
        return switch (opcode) {
            case Opcodes.FCONST_0 -> 0.0;
            case Opcodes.FCONST_1 -> 1.0;
            case Opcodes.FCONST_2 -> 2.0;
            case Opcodes.DCONST_0 -> 0.0;
            case Opcodes.DCONST_1 -> 1.0;
            default -> node instanceof LdcInsnNode ldc && ldc.cst instanceof Number value
                    ? value.doubleValue() : null;
        };
    }

    private static Map<String, String> getterFields(ClassNode state) {
        HashMap<String, String> result = new HashMap<>();
        for (MethodNode method : state.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0 || Type.getArgumentTypes(method.desc).length != 0) continue;
            FieldInsnNode read = null;
            boolean bad = false;
            for (AbstractInsnNode insn : method.instructions) {
                int opcode = insn.getOpcode();
                if (insn instanceof FieldInsnNode field && opcode == Opcodes.GETFIELD
                        && field.owner.equals(state.name)) {
                    if (read == null) read = field;
                    else if (!read.name.equals(field.name)) bad = true;
                }
            }
            if (!bad && read != null) result.put(method.name + method.desc, read.name);
        }
        return result;
    }

    private static LinkShape findLink(Collection<ClassNode> nodes, String stateClass) {
        String stateDesc = 'L' + stateClass + ';';
        for (ClassNode node : nodes) {
            if (node == null || node.fields == null || node.name.equals(stateClass)) continue;
            ArrayList<String> fields = new ArrayList<>();
            String variantField = null;
            boolean ambiguousVariant = false;
            for (FieldNode field : node.fields) {
                if ((field.access & Opcodes.ACC_STATIC) != 0) continue;
                if (stateDesc.equals(field.desc)) {
                    fields.add(field.name);
                } else if ("Lnet/minecraft/resources/ResourceLocation;".equals(field.desc)) {
                    if (variantField == null) variantField = field.name;
                    else ambiguousVariant = true;
                }
            }
            if (fields.size() >= 2) {
                MethodNode readiness = readinessMethod(node, stateClass, fields);
                MethodNode traversal = traversalMethod(node, stateClass, fields);
                BoundsHelper bounds = traversal == null ? null
                        : traversalBoundsHelper(traversal, stateClass);
                return new LinkShape(node.name, List.copyOf(fields),
                        ambiguousVariant ? null : variantField,
                        readiness == null ? null : readiness.name,
                        readiness == null ? null : readiness.desc,
                        traversal == null ? null : traversal.name,
                        traversal == null ? null : traversal.desc,
                        bounds == null ? null : bounds.owner,
                        bounds == null ? null : bounds.name,
                        bounds == null ? null : bounds.desc);
            }
        }
        return null;
    }

    /** A link readiness method is a no-arg boolean that consults at least two endpoint fields and
     * delegates boolean state to the endpoint type. Semantic names only break ties between equally
     * structural candidates (for example isOpen vs isInWorld). */
    private static MethodNode readinessMethod(ClassNode link, String stateClass,
                                              List<String> stateFields) {
        MethodNode best = null;
        int bestScore = Integer.MIN_VALUE;
        Set<String> wanted = Set.copyOf(stateFields);
        for (MethodNode method : link.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0 || !"()Z".equals(method.desc)) continue;
            LinkedHashSet<String> reads = new LinkedHashSet<>();
            boolean endpointBoolean = false;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                        && field.owner.equals(link.name) && wanted.contains(field.name)) {
                    reads.add(field.name);
                } else if (insn instanceof MethodInsnNode call && call.owner.equals(stateClass)
                        && "()Z".equals(call.desc)) {
                    endpointBoolean = true;
                }
            }
            if (reads.size() < 2 || !endpointBoolean) continue;
            String name = method.name.toLowerCase(java.util.Locale.ROOT);
            int score = reads.size() * 10;
            if (name.contains("open")) score += 100;
            else if (name.contains("active") || name.contains("ready")
                    || name.contains("connected") || name.contains("linked")) score += 80;
            else if (name.contains("world") || name.contains("valid")) score += 10;
            if (score > bestScore) {
                best = method;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Proves that a two-ended link owns server traversal rather than merely being a decorative
     * saved-data pair. The method must consume an Entity, a Level and a boolean side selector,
     * return a decision, consult endpoint state (directly or through a link helper), and issue an
     * entity-space mutation. Descriptors are used instead of method names so production-obfuscated
     * mods remain discoverable.
     */
    private static MethodNode traversalMethod(ClassNode link, String stateClass,
                                              List<String> stateFields) {
        Set<String> wanted = Set.copyOf(stateFields);
        MethodNode best = null;
        int bestScore = Integer.MIN_VALUE;
        for (MethodNode method : link.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0) continue;
            Type[] args;
            Type result;
            try {
                args = Type.getArgumentTypes(method.desc);
                result = Type.getReturnType(method.desc);
            } catch (Exception ignored) { continue; }
            if (result.getSort() != Type.BOOLEAN) continue;
            boolean entityArg = false, levelArg = false, sideArg = false;
            for (Type arg : args) {
                if (arg.getSort() == Type.BOOLEAN) sideArg = true;
                if (arg.getSort() != Type.OBJECT) continue;
                String name = arg.getInternalName();
                entityArg |= "net/minecraft/world/entity/Entity".equals(name);
                levelArg |= "net/minecraft/world/level/Level".equals(name)
                        || name.endsWith("/ServerLevel");
            }
            if (!entityArg || !levelArg || !sideArg) continue;

            int endpointReads = 0;
            boolean stateUse = false;
            boolean entityMutation = false;
            boolean linkHelper = false;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                        && field.owner.equals(link.name) && wanted.contains(field.name)) {
                    endpointReads++;
                } else if (insn instanceof MethodInsnNode call) {
                    if (call.owner.equals(stateClass)) stateUse = true;
                    if (call.owner.equals(link.name) && call.name != null
                            && !call.name.equals(method.name)) linkHelper = true;
                    String owner = call.owner == null ? "" : call.owner;
                    if ((owner.equals("net/minecraft/world/entity/Entity")
                            || owner.endsWith("/ServerPlayer") || owner.endsWith("/ServerPlayerGameMode"))
                            && entityMutationDescriptor(call.desc)) {
                        entityMutation = true;
                    }
                }
            }
            if (!entityMutation || endpointReads == 0 && !stateUse && !linkHelper) continue;
            int score = endpointReads * 12 + (stateUse ? 25 : 0) + (linkHelper ? 8 : 0);
            String name = method.name == null ? "" : method.name.toLowerCase(java.util.Locale.ROOT);
            if (name.contains("teleport") || name.contains("travel") || name.contains("travers")) score += 80;
            if (score > bestScore) {
                best = method;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Finds the non-mutating traversal-volume helper that feeds the proven entity crossing test.
     * A common server pattern is {@code surfaceBounds(position, pitch, yaw)} followed by
     * {@code entityBox.intersects(surfaceBounds)}. A state-owned no-arg AABB accessor is accepted as
     * the equivalent shape. Frequency and bytecode proximity to the first AABB intersection choose
     * between a traversal box and unrelated placement/render bounds without relying on mod names.
     */
    private static BoundsHelper traversalBoundsHelper(MethodNode traversal, String stateClass) {
        if (traversal == null || traversal.instructions == null) return null;
        final String aabb = "net/minecraft/world/phys/AABB";
        final String aabbDesc = "Lnet/minecraft/world/phys/AABB;";
        final String vecFloatFloat = "(Lnet/minecraft/world/phys/Vec3;FF)" + aabbDesc;
        final String noArg = "()" + aabbDesc;

        int firstIntersection = -1;
        int index = 0;
        for (AbstractInsnNode insn : traversal.instructions) {
            if (insn instanceof MethodInsnNode call && aabb.equals(call.owner)
                    && ("(" + aabbDesc + ")Z").equals(call.desc)) {
                firstIntersection = index;
                break;
            }
            index++;
        }
        if (firstIntersection < 0) return null;

        Map<String, BoundsCandidate> candidates = new HashMap<>();
        index = 0;
        for (AbstractInsnNode insn : traversal.instructions) {
            if (!(insn instanceof MethodInsnNode call)) {
                index++;
                continue;
            }
            boolean staticFactory = call.getOpcode() == Opcodes.INVOKESTATIC
                    && vecFloatFloat.equals(call.desc);
            boolean stateAccessor = call.getOpcode() != Opcodes.INVOKESTATIC
                    && stateClass.equals(call.owner) && noArg.equals(call.desc);
            if (!staticFactory && !stateAccessor) {
                index++;
                continue;
            }
            String key = call.owner + '\n' + call.name + '\n' + call.desc;
            BoundsCandidate candidate = candidates.computeIfAbsent(key,
                    ignored -> new BoundsCandidate(call.owner, call.name, call.desc));
            candidate.count++;
            if (index <= firstIntersection) {
                candidate.distanceToIntersection = Math.min(candidate.distanceToIntersection,
                        firstIntersection - index);
            }
            index++;
        }

        BoundsCandidate best = null;
        int bestScore = Integer.MIN_VALUE;
        for (BoundsCandidate candidate : candidates.values()) {
            int score = candidate.count * 20;
            if (candidate.distanceToIntersection != Integer.MAX_VALUE) {
                score += Math.max(0, 120 - candidate.distanceToIntersection);
            }
            String name = candidate.name.toLowerCase(java.util.Locale.ROOT);
            if (name.contains("teleport") || name.contains("travers") || name.contains("travel")) score += 20;
            else if (name.contains("collision") || name.contains("bound") || name.contains("box")) score += 5;
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best == null ? null : new BoundsHelper(best.owner, best.name, best.desc);
    }

    private static boolean entityMutationDescriptor(String descriptor) {
        if (descriptor == null) return false;
        // Common position/velocity/teleport shapes. Names are intentionally irrelevant.
        return "(DDD)V".equals(descriptor)
                || "(Lnet/minecraft/world/phys/Vec3;)V".equals(descriptor)
                || descriptor.contains("Lnet/minecraft/server/level/ServerLevel;")
                && descriptor.endsWith(")V");
    }

    private static ContainerShape findContainer(Map<String, ClassNode> nodes, String linkClass) {
        for (ClassNode node : nodes.values()) {
            if (node == null || !inherits(node, SAVED_DATA, nodes, new HashSet<>())) continue;
            String fieldName = null;
            for (FieldNode field : node.fields) {
                String signature = field.signature == null ? "" : field.signature;
                if (signature.contains('L' + linkClass + ';')) {
                    fieldName = field.name;
                    break;
                }
            }
            if (fieldName == null) continue;
            MethodNode serverFallback = null;
            for (MethodNode method : node.methods) {
                if ((method.access & Opcodes.ACC_STATIC) == 0) continue;
                Type returnType;
                Type[] args;
                try {
                    returnType = Type.getReturnType(method.desc);
                    args = Type.getArgumentTypes(method.desc);
                } catch (Exception ignored) { continue; }
                if (returnType.getSort() != Type.OBJECT || !node.name.equals(returnType.getInternalName())
                        || args.length != 1 || args[0].getSort() != Type.OBJECT) continue;
                String arg = args[0].getDescriptor();
                if (LEVEL.equals(arg)) return new ContainerShape(node.name, method.name, method.desc, fieldName);
                if (SERVER.equals(arg)) serverFallback = method;
            }
            if (serverFallback != null) {
                return new ContainerShape(node.name, serverFallback.name, serverFallback.desc, fieldName);
            }
        }
        return null;
    }

    private static boolean inherits(ClassNode node, String target, Map<String, ClassNode> nodes,
                                    Set<String> visiting) {
        if (node == null || !visiting.add(node.name)) return false;
        if (target.equals(node.name) || target.equals(node.superName)) return true;
        return node.superName != null && inherits(nodes.get(node.superName), target, nodes, visiting);
    }

    private record StateShape(String positionField, String dimensionField, List<String> floatFields,
                              String collisionShapesMethod, String collisionShapesMethodDesc) {}
    private static final class TransformState {
        float sx = 1f, sy = 1f, sz = 1f;
        float tx, ty, tz;
        TransformState() {}
        TransformState(TransformState other) {
            sx = other.sx; sy = other.sy; sz = other.sz;
            tx = other.tx; ty = other.ty; tz = other.tz;
        }
    }

    private record GeometryEvidence(float width, float height, float offsetX, float offsetY,
                                    float offsetZ, int vertices) {}
    private record RenderEvidence(String positionField, List<String> floatFields,
                                  String visibleMethod, String visibleDesc,
                                  GeometryEvidence geometry, boolean positionIsRenderAnchor,
                                  boolean rendererPoseRotation) {}
    private record LinkShape(String clazz, List<String> stateFields, String variantField,
                             String openMethod, String openMethodDesc,
                             String traversalMethod, String traversalMethodDesc,
                             String traversalBoundsOwner, String traversalBoundsMethod,
                             String traversalBoundsMethodDesc) {}
    private static final class BoundsCandidate {
        final String owner;
        final String name;
        final String desc;
        int count;
        int distanceToIntersection = Integer.MAX_VALUE;

        BoundsCandidate(String owner, String name, String desc) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
        }
    }
    private record BoundsHelper(String owner, String name, String desc) {}
    private record ContainerShape(String clazz, String getter, String getterDesc, String field) {}
}
