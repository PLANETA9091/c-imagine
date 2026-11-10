package dev.f2v.vm.discovery;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Generic bytecode behavior inventory used by compatibility translators.
 *
 * <p>The scanner deliberately stops at describing behavior. It never marks arbitrary mod classes
 * safe to execute on a dedicated server. Runtime bridges consume narrow recipes such as
 * "mouse input on item X constructs serverbound packet Y(boolean)", while render/particle/sound
 * effects become evidence for the existing proxy/capture translators.</p>
 */
public final class BehaviorBytecodeScanner {

    public static final int SCHEMA = 6;
    private static final int MAX_CALLS = 64;
    private static final int MAX_RESOURCES = 64;

    public enum Trigger {
        ITEM_USE,
        ITEM_USE_ON,
        ITEM_INVENTORY_TICK,
        ITEM_FINISH_USE,
        ITEM_RELEASE_USE,
        ITEM_SWING,
        ITEM_ENTITY_UPDATE,
        ENTITY_TICK,
        ENTITY_INTERACT,
        ENTITY_HURT,
        ENTITY_COLLISION,
        PROJECTILE_TICK,
        PARTICLE_TICK,
        PARTICLE_RENDER,
        ENTITY_RENDER,
        BLOCK_ENTITY_RENDER,
        ITEM_RENDER,
        WORLD_RENDER,
        HUD_RENDER,
        CLIENT_TICK,
        SERVER_TICK,
        MOUSE_INPUT,
        KEY_INPUT,
        PACKET_HANDLE,
        EVENT_HANDLER,
        UNKNOWN
    }

    public enum Effect {
        SEND_SERVER_PACKET,
        SEND_CLIENT_PACKET,
        SPAWN_ENTITY,
        REMOVE_ENTITY,
        ADD_PARTICLE,
        PLAY_SOUND,
        STOP_SOUND,
        RAYCAST,
        TELEPORT_ENTITY,
        MOVE_ENTITY,
        SET_VELOCITY,
        DAMAGE_ENTITY,
        WRITE_ITEM_STATE,
        WRITE_ENTITY_STATE,
        READ_ENTITY_STATE,
        CAPABILITY_READ,
        CAPABILITY_WRITE,
        OPEN_SCREEN,
        CANCEL_EVENT,
        RENDER_GEOMETRY,
        RENDER_MODEL,
        TRIGGER_ANIMATION,
        WORLD_MUTATION,
        STATE_SYNC
    }

    public enum Translation {
        INPUT_PACKET_REPLAY,
        SERVER_HANDLER_REPLAY,
        ENTITY_PROXY,
        PARTICLE_BRIDGE,
        SOUND_BRIDGE,
        DISPLAY_PROXY,
        CAPTURE_RENDER,
        STATE_MIRROR,
        RAYCAST_BRIDGE,
        UNSUPPORTED
    }

    /** Static registry field recovered from DeferredRegister-style bytecode. */
    public static final class RegistryRef {
        public String field;
        public String registryKind;
        public String id;
        public String implementationClass;
    }

    /** One class with behavior useful to a compatibility translator. */
    public static final class BehaviorSurface {
        public String clazz;
        public String superClass;
        public boolean directExecutionAllowed;
        public final Set<String> subjects = new LinkedHashSet<>();
        public final Set<Effect> effects = new LinkedHashSet<>();
        public final Set<Translation> translations = new LinkedHashSet<>();
        /** Raw class fields are retained as declarative payload/state shape evidence. */
        public final List<DataField> dataFields = new ArrayList<>();
        public final List<MethodBehavior> methods = new ArrayList<>();
    }

    /** Field shape from the class file. The scanner never reads a live instance here. */
    public static final class DataField {
        public String name;
        public String descriptor;
        public boolean isStatic;
    }

    /** One method-level trigger -> effects graph node. */
    public static final class MethodBehavior {
        public String method;
        public String descriptor;
        public Trigger trigger = Trigger.UNKNOWN;
        public int confidence;
        public final Set<String> subjects = new LinkedHashSet<>();
        public final Set<String> resources = new LinkedHashSet<>();
        public final Set<Effect> effects = new LinkedHashSet<>();
        /** Raw field accesses retained for archive-wide state-flow correlation. */
        public final Set<String> fieldReads = new LinkedHashSet<>();
        public final Set<String> fieldWrites = new LinkedHashSet<>();
        public final List<CallEdge> calls = new ArrayList<>();
        /** Every concrete method invocation, including calls that are not themselves an effect. */
        public final List<InvocationEdge> invocations = new ArrayList<>();
        /** Exact `this.field = parameter` assignments. Used to correlate a screen constructor
         * parameter with the backing field that later drives translated client state. */
        public final List<FieldAssignment> parameterFieldAssignments = new ArrayList<>();
        /** Exact collection-size relations proven inside this constructor. These are deliberately
         * narrower than general collection analysis: a relation exists only when bytecode proves
         * one fresh empty collection receives exactly one add per 0..N-1 loop iteration and is
         * then stored into the named field. */
        public final List<CollectionCardinality> collectionCardinalities = new ArrayList<>();
        /** Analysis limitations are retained in discovery reports instead of implying certainty. */
        public final Set<String> analysisWarnings = new LinkedHashSet<>();
        transient final Set<String> localTargets = new LinkedHashSet<>();
    }

    /** Raw invocation edge used for archive-wide data-flow correlation. */
    public static final class InvocationEdge {
        public String owner;
        public String method;
        public String descriptor;
        public int opcode;
        /** Receiver provenance for instance calls. -1 means no ordinary method parameter; when
         * receiverIsThis is true, the receiver is local 0 of the current instance method. */
        public int receiverParameterIndex = -1;
        public boolean receiverIsThis;
        public final List<InvocationArgument> arguments = new ArrayList<>();
    }

    /** Exact provenance for one invocation argument. A value may be an unchanged method parameter
     * or a simple backing-field read from one method parameter / this. Rich expressions remain
     * intentionally blank. */
    public static final class InvocationArgument {
        public int index = -1;
        public String descriptor;
        public int parameterIndex = -1;
        public int fieldReceiverParameterIndex = -1;
        public boolean fieldReceiverIsThis;
        public String fieldOwner;
        public String fieldName;
        public String fieldDesc;
        /** Exact java.util.Map#get lookup whose result supplied this argument. The selected object
         * itself is never executed by F2V; only the key's packet-field provenance is retained so
         * archive-wide analysis can join prior authoritative clientbound state by identity. */
        public String lookupOwner;
        public String lookupMethod;
        public String lookupDesc;
        public int lookupKeyFieldReceiverParameterIndex = -1;
        public boolean lookupKeyFieldReceiverIsThis;
        public String lookupKeyFieldOwner;
        public String lookupKeyFieldName;
        public String lookupKeyFieldDesc;
    }

    /** Exact constructor/helper assignment from one method parameter into this instance. */
    public static final class FieldAssignment {
        public String fieldOwner;
        public String fieldName;
        public String fieldDesc;
        public int parameterIndex = -1;
    }

    /** Exact cardinality of one collection field as a constructor argument. */
    public static final class CollectionCardinality {
        public String fieldOwner;
        public String fieldName;
        public String fieldDesc;
        public String constructorDesc;
        public int constructorArgument = -1;
        public int confidence;
    }

    /** Concrete effect edge. target is a class/resource id when recoverable. */
    public static final class CallEdge {
        public Effect effect;
        public String owner;
        public String method;
        public String descriptor;
        public String target;
        public String constructorDesc;
        public Boolean booleanArgument;
        public int confidence;
    }

    /** Safe-to-consume packet recipe recovered from a subject-specific input path. */
    public record PacketRecipe(String packetClass, String constructorDesc, Boolean booleanArgument,
                               Trigger trigger, int confidence) {}

    public record Summary(int mods, int surfaces, int methods, int effects, int replayablePackets) {}

    private static final Set<String> ITEM_ROOTS = Set.of(
            "net/minecraft/world/item/Item",
            "net/minecraft/world/item/ArmorItem",
            "net/minecraft/world/item/ProjectileWeaponItem",
            "net/minecraft/world/item/BowItem",
            "net/minecraft/world/item/CrossbowItem");
    private static final Set<String> ENTITY_ROOTS = Set.of(
            "net/minecraft/world/entity/Entity",
            "net/minecraft/world/entity/LivingEntity",
            "net/minecraft/world/entity/Mob",
            "net/minecraft/world/entity/PathfinderMob");
    private static final Set<String> PROJECTILE_ROOTS = Set.of(
            "net/minecraft/world/entity/projectile/Projectile",
            "net/minecraft/world/entity/projectile/ThrowableProjectile",
            "net/minecraft/world/entity/projectile/AbstractArrow");
    private static final Set<String> PARTICLE_ROOTS = Set.of(
            "net/minecraft/client/particle/Particle",
            "net/minecraft/client/particle/TextureSheetParticle",
            "net/minecraft/client/particle/NoRenderParticle",
            "net/minecraft/client/particle/SingleQuadParticle",
            "net/minecraft/client/particle/SimpleAnimatedParticle",
            "net/minecraft/client/particle/RisingParticle",
            "net/minecraft/client/particle/SuspendedParticle",
            "net/minecraft/client/particle/TerrainParticle");
    private static final Set<String> ENTITY_RENDERER_ROOTS = Set.of(
            "net/minecraft/client/renderer/entity/EntityRenderer",
            "net/minecraft/client/renderer/entity/LivingEntityRenderer",
            "net/minecraft/client/renderer/entity/MobRenderer");
    private static final Set<String> BLOCK_ENTITY_RENDERER_ROOTS = Set.of(
            "net/minecraft/client/renderer/blockentity/BlockEntityRenderer");
    private static final Set<String> SCREEN_ROOTS = Set.of(
            "net/minecraft/client/gui/screens/Screen",
            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen");

    private BehaviorBytecodeScanner() {}

    /** Builds a single archive-wide registry-field index, reused for every class inspection. */
    public static Map<String, RegistryRef> discoverRegistryFields(Map<String, ClassNode> nodes,
                                                                   String modId) {
        LinkedHashMap<String, RegistryRef> out = new LinkedHashMap<>();
        if (nodes == null || nodes.isEmpty()) return out;
        String namespace = modId == null || modId.isBlank() || "?".equals(modId) ? null : modId;
        for (ClassNode node : nodes.values()) {
            if (node == null || node.methods == null) continue;
            for (MethodNode method : node.methods) {
                if (!"<clinit>".equals(method.name)) continue;
                List<AbstractInsnNode> insns = toList(method);
                for (int i = 0; i < insns.size(); i++) {
                    AbstractInsnNode instruction = insns.get(i);
                    if (!(instruction instanceof MethodInsnNode call) || !registrationCall(call)) continue;
                    String path = previousString(insns, i, 14);
                    FieldInsnNode store = nextStaticStore(insns, i, 10);
                    if (path == null || store == null || !simplePath(path)) continue;
                    RegistryRef ref = new RegistryRef();
                    ref.field = store.owner + "." + store.name;
                    ref.registryKind = registryKind(store.owner, store.desc);
                    ref.id = path.indexOf(':') >= 0 ? path
                            : namespace == null ? path : namespace + ":" + path;
                    InvokeDynamicInsnNode supplier = previousInvokeDynamic(insns, i, 12);
                    if (supplier != null) {
                        ref.implementationClass = implementationFromInvokeDynamic(supplier, nodes,
                                new HashSet<>(), 0);
                    }
                    out.putIfAbsent(ref.field, ref);
                }
            }
        }
        return out;
    }

    /** Analyze one already parsed mod class; no classloading or second jar traversal. */
    public static BehaviorSurface inspect(ClassNode node, Map<String, ClassNode> nodes,
                                          Map<String, RegistryRef> registryFields) {
        return inspect(node, nodes, registryFields, false);
    }

    static BehaviorSurface inspectGraphClass(ClassNode node, Map<String, ClassNode> nodes,
                                             Map<String, RegistryRef> registryFields) {
        return inspect(node, nodes, registryFields, true);
    }

    private static BehaviorSurface inspect(ClassNode node, Map<String, ClassNode> nodes,
                                            Map<String, RegistryRef> registryFields,
                                            boolean retainHelpers) {
        if (node == null || node.methods == null) return null;
        Map<String, RegistryRef> fields = registryFields == null ? Map.of() : registryFields;
        boolean itemClass = inheritsAny(node, ITEM_ROOTS, nodes);
        boolean entityClass = inheritsAny(node, ENTITY_ROOTS, nodes);
        boolean projectileClass = inheritsAny(node, PROJECTILE_ROOTS, nodes);
        boolean particleClass = inheritsAny(node, PARTICLE_ROOTS, nodes);

        BehaviorSurface surface = new BehaviorSurface();
        surface.clazz = node.name;
        surface.superClass = node.superName;
        surface.directExecutionAllowed = false;
        if (node.fields != null) {
            for (FieldNode field : node.fields) {
                if (field == null || surface.dataFields.size() >= MAX_RESOURCES) break;
                DataField data = new DataField();
                data.name = field.name;
                data.descriptor = field.desc;
                data.isStatic = (field.access & Opcodes.ACC_STATIC) != 0;
                surface.dataFields.add(data);
            }
        }
        for (RegistryRef ref : fields.values()) {
            if (node.name.equals(ref.implementationClass) && isSubjectKind(ref.registryKind)) {
                addBounded(surface.subjects, ref.id, MAX_RESOURCES);
            }
        }

        Map<String, MethodBehavior> byMethod = new LinkedHashMap<>();
        for (MethodNode method : node.methods) {
            MethodBehavior behavior = inspectMethod(node, method, nodes, fields, itemClass,
                    entityClass, projectileClass, particleClass);
            if (behavior == null) continue;
            behavior.subjects.addAll(surface.subjects);
            byMethod.put(methodKey(method.name, method.desc), behavior);
            surface.methods.add(behavior);
        }
        propagateLocalEffects(byMethod);
        for (MethodBehavior method : surface.methods) {
            surface.effects.addAll(method.effects);
            for (String subject : method.subjects) addBounded(surface.subjects, subject, MAX_RESOURCES);
        }
        chooseTranslations(surface);
        if (surface.methods.isEmpty() && surface.subjects.isEmpty()) return null;
        if (!retainHelpers && surface.effects.isEmpty()
                && surface.methods.stream().allMatch(m -> m.trigger == Trigger.UNKNOWN)) {
            return null;
        }
        return surface;
    }

    private static MethodBehavior inspectMethod(ClassNode owner, MethodNode method,
                                                Map<String, ClassNode> nodes,
                                                Map<String, RegistryRef> registryFields,
                                                boolean itemClass, boolean entityClass,
                                                boolean projectileClass, boolean particleClass) {
        Trigger trigger = classifyTrigger(owner, method, nodes, itemClass, entityClass,
                projectileClass, particleClass);
        MethodBehavior out = new MethodBehavior();
        out.method = method.name;
        out.descriptor = method.desc;
        out.trigger = trigger;
        out.confidence = trigger == Trigger.UNKNOWN ? 35 : 80;

        List<AbstractInsnNode> insns = toList(method);
        RecentConstruct recent = null;
        BytecodeValueFlow valueFlow = new BytecodeValueFlow(nodes);
        int ordinal = 0;
        for (int i = 0; i < insns.size(); i++) {
            AbstractInsnNode instruction = insns.get(i);
            if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                if (resourceLike(value)) addBounded(out.resources, value, MAX_RESOURCES);
            }
            if (instruction instanceof FieldInsnNode field) {
                String fieldKey = field.owner + '.' + field.name + ':' + field.desc;
                if (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC) {
                    addBounded(out.fieldReads, fieldKey, MAX_RESOURCES);
                } else if (field.getOpcode() == Opcodes.PUTFIELD || field.getOpcode() == Opcodes.PUTSTATIC) {
                    addBounded(out.fieldWrites, fieldKey, MAX_RESOURCES);
                    if (field.getOpcode() == Opcodes.PUTFIELD && owner.name.equals(field.owner)) {
                        addParameterFieldAssignment(out, owner, method, field, valueFlow);
                    }
                }
                RegistryRef ref = registryFields.get(field.owner + "." + field.name);
                if (ref != null) {
                    if (isSubjectKind(ref.registryKind)) addBounded(out.subjects, ref.id, MAX_RESOURCES);
                    else addBounded(out.resources, ref.id, MAX_RESOURCES);
                }
                if (field.getOpcode() == Opcodes.PUTFIELD && owner.name.equals(field.owner)
                        && entityClass) {
                    addEffect(out, Effect.WRITE_ENTITY_STATE, field.owner + "." + field.name,
                            field.owner, field.name, field.desc, null, null, 70);
                }
            }
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                recent = new RecentConstruct(type.desc, null, null, ordinal);
            }
            if (instruction instanceof MethodInsnNode call) {
                if (call.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(call.name)) {
                    Boolean bool = booleanArgument(insns, i);
                    recent = new RecentConstruct(call.owner, call.desc, bool, ordinal);
                }
                addInvocation(out, call, owner, method, valueFlow, nodes);
                List<BytecodeValueFlow.Value> packets = List.of();
                String lowerOwner = call.owner.toLowerCase(Locale.ROOT);
                String lowerName = call.name.toLowerCase(Locale.ROOT);
                if (serverSend(lowerOwner, lowerName, call.desc)
                        || clientSend(lowerOwner, lowerName, call.desc)) {
                    packets = new ArrayList<>();
                    for (int argument : packetArguments(call.desc)) {
                        packets.addAll(valueFlow.argumentValues(owner, method, call, argument));
                    }
                }
                classifyCall(out, call, recent, packets, ordinal);
                if (owner.name.equals(call.owner)) {
                    out.localTargets.add(methodKey(call.name, call.desc));
                }
            }
            if (instruction instanceof InvokeDynamicInsnNode indy) {
                if (!"java/lang/invoke/LambdaMetafactory".equals(indy.bsm.getOwner())) continue;
                for (Object arg : indy.bsmArgs) {
                    if (arg instanceof Handle handle) {
                        int opcode = switch (handle.getTag()) {
                            case Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC;
                            case Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL -> Opcodes.INVOKESPECIAL;
                            case Opcodes.H_INVOKEVIRTUAL -> Opcodes.INVOKEVIRTUAL;
                            case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
                            default -> -1;
                        };
                        if (opcode < 0) continue;
                        addInvocation(out, new MethodInsnNode(opcode, handle.getOwner(),
                                handle.getName(), handle.getDesc(), handle.isInterface()));
                        if (owner.name.equals(handle.getOwner())) {
                            out.localTargets.add(methodKey(handle.getName(), handle.getDesc()));
                        }
                    }
                }
            }
            ordinal++;
        }
        out.analysisWarnings.addAll(valueFlow.warnings());
        if ("<init>".equals(method.name)) {
            discoverCollectionCardinalities(out, owner, method, valueFlow);
        }

        if (renderDescriptor(method.desc)) {
            out.effects.add(Effect.RENDER_GEOMETRY);
            if (trigger == Trigger.UNKNOWN) {
                out.trigger = rendererTrigger(owner, method, nodes);
                out.confidence = out.trigger == Trigger.UNKNOWN ? 45 : 80;
            }
        }
        if (out.effects.isEmpty() && out.trigger == Trigger.UNKNOWN && out.subjects.isEmpty()
                && out.resources.isEmpty() && out.localTargets.isEmpty()
                && out.fieldReads.isEmpty() && out.fieldWrites.isEmpty()) {
            boolean dynamicFlowHelper = payloadClass(returnedObject(method.desc), nodes)
                    || !out.invocations.isEmpty();
            if (!dynamicFlowHelper) return null;
        }
        return out;
    }

    /**
     * Recognizes a canonical counted fill of a fresh {@link java.util.ArrayList}:
     * {@code for (int i = 0; i < count; i++) list.add(...); this.field = list}. Capacity alone is
     * never treated as size. The loop proof requires zero initialization, an exact constructor
     * parameter bound, one add on the same allocation per iteration, a +1 counter, and no other
     * mutating call on that allocation before the field store.
     */
    private static void discoverCollectionCardinalities(MethodBehavior out, ClassNode owner,
                                                        MethodNode method, BytecodeValueFlow flow) {
        if (out == null || owner == null || method == null || flow == null
                || method.instructions == null) return;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                    || !owner.name.equals(field.owner)) continue;
            Set<AbstractInsnNode> sources = flow.putFieldValueSources(owner, method, field);
            if (sources.size() != 1) continue;
            AbstractInsnNode source = sources.iterator().next();
            if (!(source instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.NEW
                    || !"java/util/ArrayList".equals(allocation.desc)) continue;
            Integer parameter = countedArrayListCardinalityParameter(owner, method, field,
                    allocation, flow);
            if (parameter == null || parameter < 0) continue;
            CollectionCardinality cardinality = new CollectionCardinality();
            cardinality.fieldOwner = field.owner;
            cardinality.fieldName = field.name;
            cardinality.fieldDesc = field.desc;
            cardinality.constructorDesc = method.desc;
            cardinality.constructorArgument = parameter;
            cardinality.confidence = 99;
            out.collectionCardinalities.add(cardinality);
        }
    }

    private static Integer countedArrayListCardinalityParameter(
            ClassNode owner, MethodNode method, FieldInsnNode field, TypeInsnNode allocation,
            BytecodeValueFlow flow) {
        ArrayList<AbstractInsnNode> instructions = new ArrayList<>();
        Map<AbstractInsnNode, Integer> index = new java.util.IdentityHashMap<>();
        int ordinal = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            instructions.add(instruction);
            index.put(instruction, ordinal++);
        }
        Integer allocationIndex = index.get(allocation);
        Integer fieldIndex = index.get(field);
        if (allocationIndex == null || fieldIndex == null || allocationIndex >= fieldIndex) return null;

        MethodInsnNode constructor = null;
        ArrayList<MethodInsnNode> adds = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            Set<AbstractInsnNode> receivers = flow.receiverSources(owner, method, call);
            if (receivers.size() != 1 || !receivers.contains(allocation)) continue;
            if (call.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(call.name)
                    && "java/util/ArrayList".equals(call.owner)
                    && ("()V".equals(call.desc) || "(I)V".equals(call.desc))) {
                if (constructor != null) return null;
                constructor = call;
                continue;
            }
            if ("add".equals(call.name) && "(Ljava/lang/Object;)Z".equals(call.desc)) {
                adds.add(call);
                continue;
            }
            // Any other mutator/escape before the field assignment makes size uncertain. Pure
            // Object methods are harmless, but collection reads are unnecessary in the canonical
            // fill shape and therefore intentionally not accepted here.
            Integer callIndex = index.get(call);
            if (callIndex != null && callIndex > allocationIndex && callIndex < fieldIndex) return null;
        }
        if (constructor == null || adds.size() != 1) return null;
        MethodInsnNode add = adds.get(0);
        Integer addIndex = index.get(add);
        if (addIndex == null || addIndex <= allocationIndex || addIndex >= fieldIndex) return null;

        JumpInsnNode back = null;
        int headerIndex = -1;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.GOTO) continue;
            Integer jumpIndex = index.get(jump);
            Integer targetIndex = index.get(jump.label);
            if (jumpIndex == null || targetIndex == null || jumpIndex <= addIndex
                    || jumpIndex >= fieldIndex || targetIndex >= addIndex) continue;
            if (back != null) return null;
            back = jump;
            headerIndex = targetIndex;
        }
        if (back == null || headerIndex < 0) return null;
        Integer backIndex = index.get(back);
        if (backIndex == null) return null;

        AbstractInsnNode first = nextReal(back.label);
        AbstractInsnNode second = nextReal(first);
        AbstractInsnNode third = nextReal(second);
        if (!(first instanceof VarInsnNode counterLoad) || counterLoad.getOpcode() != Opcodes.ILOAD
                || !(second instanceof VarInsnNode boundLoad) || boundLoad.getOpcode() != Opcodes.ILOAD
                || !(third instanceof JumpInsnNode exit) || exit.getOpcode() != Opcodes.IF_ICMPGE) {
            return null;
        }
        int parameter = methodParameterIndex(method, boundLoad.var);
        if (parameter < 0) return null;

        AbstractInsnNode beforeHeader = previousReal(back.label);
        AbstractInsnNode zero = previousReal(beforeHeader);
        if (!(beforeHeader instanceof VarInsnNode init) || init.getOpcode() != Opcodes.ISTORE
                || init.var != counterLoad.var || !Integer.valueOf(0).equals(integerConstant(zero))) {
            return null;
        }
        int increments = 0;
        for (AbstractInsnNode instruction = add.getNext(); instruction != null && instruction != back;
             instruction = instruction.getNext()) {
            if (instruction instanceof IincInsnNode iinc && iinc.var == counterLoad.var) {
                if (iinc.incr != 1) return null;
                increments++;
            }
        }
        if (increments != 1) return null;
        Integer exitIndex = index.get(exit.label);
        if (exitIndex == null || exitIndex <= backIndex || exitIndex > fieldIndex) return null;
        return parameter;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
        for (AbstractInsnNode next = instruction == null ? null : instruction.getNext();
             next != null; next = next.getNext()) {
            if (next.getOpcode() >= 0) return next;
        }
        return null;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        for (AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
             previous != null; previous = previous.getPrevious()) {
            if (previous.getOpcode() >= 0) return previous;
        }
        return null;
    }

    private static Integer integerConstant(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        return switch (instruction.getOpcode()) {
            case Opcodes.ICONST_M1 -> -1;
            case Opcodes.ICONST_0 -> 0;
            case Opcodes.ICONST_1 -> 1;
            case Opcodes.ICONST_2 -> 2;
            case Opcodes.ICONST_3 -> 3;
            case Opcodes.ICONST_4 -> 4;
            case Opcodes.ICONST_5 -> 5;
            case Opcodes.BIPUSH, Opcodes.SIPUSH -> instruction instanceof org.objectweb.asm.tree.IntInsnNode value
                    ? value.operand : null;
            default -> instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value
                    ? value : null;
        };
    }

    private static Trigger classifyTrigger(ClassNode owner, MethodNode method,
                                           Map<String, ClassNode> nodes, boolean itemClass,
                                           boolean entityClass, boolean projectileClass,
                                           boolean particleClass) {
        String desc = method.desc == null ? "" : method.desc;
        String lowerDesc = desc.toLowerCase(Locale.ROOT);
        String lowerName = method.name == null ? "" : method.name.toLowerCase(Locale.ROOT);

        if (lowerDesc.contains("inputevent$mousebutton") || lowerDesc.contains("mousebuttonevent")
                || lowerDesc.contains("mousebutton$pre") || lowerDesc.contains("mousebutton$event")) {
            return Trigger.MOUSE_INPUT;
        }
        if (lowerDesc.contains("inputevent$key") || lowerDesc.contains("keyinputevent")
                || lowerDesc.contains("keyevent")) return Trigger.KEY_INPUT;
        if (lowerDesc.contains("clienttickevent")) return Trigger.CLIENT_TICK;
        if (lowerDesc.contains("servertickevent")) return Trigger.SERVER_TICK;
        if (lowerDesc.contains("renderlevelstageevent") || lowerDesc.contains("renderworldlastevent")) {
            return Trigger.WORLD_RENDER;
        }
        if (lowerDesc.contains("renderguievent") || lowerDesc.contains("rendergameoverlayevent")
                || lowerDesc.contains("renderblockscreeneffectevent")) return Trigger.HUD_RENDER;
        if (inheritsAny(owner, SCREEN_ROOTS, nodes) || looksLikeScreenClass(owner.name)) {
            if (tickLike(lowerName) && desc.equals("()V")) return Trigger.CLIENT_TICK;
            if (renderDescriptor(desc) && (lowerName.contains("render") || lowerName.contains("draw"))) {
                return Trigger.HUD_RENDER;
            }
        }
        if (packetHandlerDescriptor(desc)
                || clientPacketHandlerDescriptor(method, nodes)
                || ((lowerName.equals("handle") || lowerName.equals("handler")
                || lowerName.equals("process") || lowerName.equals("receive"))
                && (lowerDesc.contains("java/util/function/supplier")
                || lowerDesc.contains("networkevent$context")
                || lowerDesc.contains("payloadcontext")
                || lowerDesc.contains("packetcontext")))) {
            return Trigger.PACKET_HANDLE;
        }

        if (tickLike(lowerName) && desc.endsWith(")V")
                && (lowerDesc.contains("net/minecraft/server/level/serverlevel")
                || lowerDesc.contains("net/minecraft/world/level/level"))) {
            return lowerDesc.contains("clientlevel") ? Trigger.CLIENT_TICK : Trigger.SERVER_TICK;
        }

        if (itemClass) {
            if (desc.contains("Lnet/minecraft/world/item/context/UseOnContext;")) return Trigger.ITEM_USE_ON;
            if (desc.contains("Lnet/minecraft/world/InteractionHand;")
                    && desc.contains("Lnet/minecraft/world/entity/player/Player;")
                    && (desc.contains("InteractionResult") || desc.contains("InteractionResultHolder"))) {
                return Trigger.ITEM_USE;
            }
            if (desc.contains("Lnet/minecraft/world/item/ItemStack;")
                    && (desc.contains("Lnet/minecraft/world/level/Level;")
                    || desc.contains("Lnet/minecraft/server/level/ServerLevel;"))
                    && desc.contains("Lnet/minecraft/world/entity/Entity;") && desc.endsWith(")V")) {
                return Trigger.ITEM_INVENTORY_TICK;
            }
            if (desc.contains("Lnet/minecraft/world/item/ItemStack;")
                    && desc.contains("Lnet/minecraft/world/entity/LivingEntity;")
                    && desc.endsWith(")Z")) return Trigger.ITEM_SWING;
            if (desc.contains("Lnet/minecraft/world/entity/item/ItemEntity;") && desc.endsWith(")Z")) {
                return Trigger.ITEM_ENTITY_UPDATE;
            }
            if (lowerName.contains("finishusing") || lowerName.contains("finishuse")) {
                return Trigger.ITEM_FINISH_USE;
            }
            if (lowerName.contains("releaseusing") || lowerName.contains("releaseuse")) {
                return Trigger.ITEM_RELEASE_USE;
            }
        }

        if (particleClass) {
            if (renderDescriptor(desc)) return Trigger.PARTICLE_RENDER;
            if (desc.equals("()V") && tickLike(lowerName)) return Trigger.PARTICLE_TICK;
        }
        if (entityClass) {
            if (desc.contains("Lnet/minecraft/world/entity/player/Player;")
                    && desc.contains("Lnet/minecraft/world/InteractionHand;")
                    && desc.contains("InteractionResult")) return Trigger.ENTITY_INTERACT;
            if (desc.contains("Lnet/minecraft/world/damagesource/DamageSource;")
                    && desc.contains("F") && desc.endsWith(")Z")) return Trigger.ENTITY_HURT;
            if (desc.contains("Lnet/minecraft/world/entity/Entity;") && desc.endsWith(")V")
                    && (lowerName.contains("collid") || lowerName.contains("push"))) {
                return Trigger.ENTITY_COLLISION;
            }
            if (desc.equals("()V") && tickLike(lowerName)) {
                return projectileClass ? Trigger.PROJECTILE_TICK : Trigger.ENTITY_TICK;
            }
        }

        Trigger renderer = rendererTrigger(owner, method, nodes);
        if (renderer != Trigger.UNKNOWN) return renderer;
        if (lowerDesc.contains("/event/") || lowerDesc.contains("event;")
                || lowerDesc.contains("event$")) return Trigger.EVENT_HANDLER;
        return Trigger.UNKNOWN;
    }

    private static Trigger rendererTrigger(ClassNode owner, MethodNode method,
                                           Map<String, ClassNode> nodes) {
        if (!renderDescriptor(method.desc)) return Trigger.UNKNOWN;
        String lower = (owner.name + " " + owner.superName + " " + method.desc).toLowerCase(Locale.ROOT);
        if (lower.contains("blockentityrenderer") || lower.contains("blockentityrender")
                || inheritsAny(owner, BLOCK_ENTITY_RENDERER_ROOTS, nodes)) {
            return Trigger.BLOCK_ENTITY_RENDER;
        }
        if (lower.contains("itemrenderer") || lower.contains("geoitemrenderer")
                || method.desc.contains("Lnet/minecraft/world/item/ItemStack;")) {
            return Trigger.ITEM_RENDER;
        }
        if (lower.contains("entityrenderer") || method.desc.contains("Lnet/minecraft/world/entity/Entity;")
                || inheritsAny(owner, ENTITY_RENDERER_ROOTS, nodes)) {
            return Trigger.ENTITY_RENDER;
        }
        if (lower.contains("particle") || inheritsAny(owner, PARTICLE_ROOTS, nodes)) {
            return Trigger.PARTICLE_RENDER;
        }
        return Trigger.UNKNOWN;
    }

    private static void classifyCall(MethodBehavior out, MethodInsnNode call, RecentConstruct recent,
                                     List<BytecodeValueFlow.Value> packets, int ordinal) {
        String owner = call.owner == null ? "" : call.owner;
        String name = call.name == null ? "" : call.name;
        String desc = call.desc == null ? "" : call.desc;
        String lowerOwner = owner.toLowerCase(Locale.ROOT);
        String lowerName = name.toLowerCase(Locale.ROOT);
        String lowerDesc = desc.toLowerCase(Locale.ROOT);

        boolean ambiguousPayloadSend = ambiguousPayloadSend(lowerOwner, lowerName, desc);
        if (serverSend(lowerOwner, lowerName, desc)
                || ambiguousPayloadSend && clientOrigin(out.trigger)) {
            addPacketEffects(out, Effect.SEND_SERVER_PACKET, call, packets);
        } else if (clientSend(lowerOwner, lowerName, desc)) {
            addPacketEffects(out, Effect.SEND_CLIENT_PACKET, call, packets);
        }

        if ((lowerName.contains("addfreshentity") || lowerName.contains("spawn")
                || "(Lnet/minecraft/world/entity/Entity;)Z".equals(desc))
                && (lowerOwner.contains("level") || lowerOwner.contains("world"))) {
            RecentConstruct entity = recentWithin(recent, ordinal, 36);
            addEffect(out, Effect.SPAWN_ENTITY, entity == null ? null : entity.type,
                    owner, name, desc, entity == null ? null : entity.ctorDesc, null,
                    entity == null ? 55 : 85);
        }
        if (lowerName.contains("discard") || lowerName.contains("removeentity")
                || lowerName.equals("kill")) {
            addEffect(out, Effect.REMOVE_ENTITY, owner, owner, name, desc, null, null, 65);
        }
        if (lowerDesc.contains("particleoptions") || lowerOwner.contains("particleengine")
                || lowerName.contains("addparticle") || lowerName.contains("sendparticles")) {
            addEffect(out, Effect.ADD_PARTICLE, firstInterestingSubject(out.resources, "particle"),
                    owner, name, desc, null, null, 80);
        }
        if (lowerDesc.contains("soundevent") || lowerName.contains("playsound")) {
            addEffect(out, Effect.PLAY_SOUND, firstInterestingSubject(out.resources, "sound"),
                    owner, name, desc, null, null, 80);
        }
        if (lowerName.contains("stopsound") || lowerOwner.contains("soundmanager")
                && lowerName.contains("stop")) {
            addEffect(out, Effect.STOP_SOUND, owner, owner, name, desc, null, null, 75);
        }
        if (lowerName.contains("raycast") || lowerName.contains("raytrace")
                || lowerOwner.contains("projectileutil")
                || lowerDesc.contains("clipcontext") && lowerDesc.contains("hitresult")) {
            addEffect(out, Effect.RAYCAST, owner + "." + name, owner, name, desc, null, null, 85);
        }
        if ((lowerName.contains("teleport") || lowerName.contains("changdimension"))
                && lowerOwner.contains("entity")) {
            addEffect(out, Effect.TELEPORT_ENTITY, owner, owner, name, desc, null, null, 75);
        }
        if ((lowerName.contains("setpos") || lowerName.contains("moveto")
                || lowerName.equals("move")) && lowerOwner.contains("entity")) {
            addEffect(out, Effect.MOVE_ENTITY, owner, owner, name, desc, null, null, 70);
        }
        if ((lowerName.contains("setdelta") || lowerName.contains("velocity"))
                && lowerOwner.contains("entity")) {
            addEffect(out, Effect.SET_VELOCITY, owner, owner, name, desc, null, null, 70);
        }
        if (lowerDesc.contains("damagesource") && lowerOwner.contains("entity")) {
            addEffect(out, Effect.DAMAGE_ENTITY, owner, owner, name, desc, null, null, 70);
        }
        if (lowerName.contains("getcapability") || lowerDesc.contains("lazyoptional")
                && lowerDesc.contains("capability")) {
            addEffect(out, Effect.CAPABILITY_READ, owner, owner, name, desc, null, null, 80);
        }
        if (lowerOwner.contains("lazyoptional") && (lowerName.contains("ifpresent")
                || lowerName.contains("resolve"))) {
            out.effects.add(Effect.CAPABILITY_READ);
        }
        if (lowerOwner.contains("itemstack") && (lowerName.startsWith("set")
                || lowerName.contains("tag") || lowerName.contains("component"))) {
            addEffect(out, Effect.WRITE_ITEM_STATE, owner + "." + name, owner, name, desc,
                    null, null, 55);
        }
        if (lowerOwner.contains("synchedentitydata") && lowerDesc.contains("entitydataaccessor")) {
            Type[] arguments;
            Type result;
            try {
                arguments = Type.getArgumentTypes(desc);
                result = Type.getReturnType(desc);
            } catch (IllegalArgumentException malformed) {
                arguments = new Type[0];
                result = Type.VOID_TYPE;
            }
            // Production/obfuscated jars rename get/set/define, but the accessor/value descriptor
            // survives mappings. Two arguments + void is set/define; one accessor + value return
            // is a synchronized read. This catches Kotlin mods such as Cobblemon without names.
            if (arguments.length >= 2 && result.getSort() == Type.VOID) {
                addEffect(out, Effect.WRITE_ENTITY_STATE, owner + "." + name, owner, name, desc,
                        null, null, 92);
                out.effects.add(Effect.STATE_SYNC);
            } else if (arguments.length == 1 && result.getSort() != Type.VOID) {
                addEffect(out, Effect.READ_ENTITY_STATE, owner + "." + name, owner, name, desc,
                        null, null, 90);
                out.effects.add(Effect.STATE_SYNC);
            }
        } else if (lowerOwner.contains("entitydataaccessor")
                && (lowerName.contains("set") || lowerName.contains("define"))) {
            addEffect(out, Effect.WRITE_ENTITY_STATE, owner + "." + name, owner, name, desc,
                    null, null, 75);
            out.effects.add(Effect.STATE_SYNC);
        }
        if (minecraftScreenSetter(owner, desc)
                || lowerOwner.contains("minecraft") && lowerDesc.contains("/screens/screen;")
                && (lowerName.contains("setscreen") || lowerName.contains("open"))) {
            RecentConstruct screen = recentWithin(recent, ordinal, 24);
            addEffect(out, Effect.OPEN_SCREEN, screen == null ? null : screen.type,
                    owner, name, desc, screen == null ? null : screen.ctorDesc, null, 85);
        }
        if (lowerName.equals("setcanceled") && "(Z)V".equals(desc)) {
            addEffect(out, Effect.CANCEL_EVENT, owner, owner, name, desc, null,
                    recent == null ? null : recent.booleanArgument, 90);
        }
        if (lowerOwner.contains("posestack") || lowerOwner.contains("vertexconsumer")
                || lowerOwner.contains("multibuffersource") || lowerOwner.contains("rendertype")) {
            out.effects.add(Effect.RENDER_GEOMETRY);
        }
        if (lowerOwner.contains("renderer") || lowerOwner.contains("model")
                || lowerName.contains("rendertobuffer")) {
            out.effects.add(Effect.RENDER_MODEL);
        }
        if (lowerName.contains("triggeranim") || lowerName.contains("setanimation")
                || lowerOwner.contains("geckolib") && lowerName.contains("anim")) {
            addEffect(out, Effect.TRIGGER_ANIMATION, owner + "." + name, owner, name, desc,
                    null, null, 85);
        }
        if ((lowerName.contains("setblock") || lowerName.contains("removeblock")
                || lowerName.contains("destroyblock") || lowerName.contains("explode"))
                && (lowerOwner.contains("level") || lowerOwner.contains("world"))) {
            addEffect(out, Effect.WORLD_MUTATION, owner + "." + name, owner, name, desc,
                    null, null, 80);
        }
    }

    private static void addPacketEffects(MethodBehavior out, Effect effect, MethodInsnNode call,
                                          List<BytecodeValueFlow.Value> packets) {
        if (packets.isEmpty()) {
            addEffect(out, effect, null, call.owner, call.name, call.desc, null, null, 55);
        }
        for (BytecodeValueFlow.Value packet : packets) {
            addEffect(out, effect, packet.type(), call.owner, call.name, call.desc,
                    packet.constructorDesc(), packet.booleanArgument(), packet.constructorDesc() == null ? 65 : 95);
        }
    }

    private static List<Integer> packetArguments(String descriptor) {
        Type[] arguments = Type.getArgumentTypes(descriptor);
        List<Integer> selected = new ArrayList<>();
        int objectArgument = -1;
        for (int i = 0; i < arguments.length; i++) {
            Type argument = arguments[i].getSort() == Type.ARRAY ? arguments[i].getElementType() : arguments[i];
            if (argument.getSort() != Type.OBJECT) continue;
            String type = argument.getInternalName().toLowerCase(Locale.ROOT);
            if (type.contains("packettarget") || type.contains("packetsink") || type.contains("packetcontext")) continue;
            if (type.contains("packet") || type.contains("payload")) {
                selected.add(i);
            } else if (type.equals("java/lang/object")) {
                objectArgument = objectArgument == -1 ? i : -2;
            }
        }
        if (selected.isEmpty() && objectArgument >= 0) selected.add(objectArgument);
        // A single reference argument is unambiguous even if the mod renamed every class.
        if (selected.isEmpty() && arguments.length == 1
                && (arguments[0].getSort() == Type.OBJECT || arguments[0].getSort() == Type.ARRAY)) selected.add(0);
        return selected;
    }

    private static boolean serverSend(String lowerOwner, String lowerName, String desc) {
        if (lowerName.contains("sendtoserver")) return true;
        if (lowerOwner.contains("packetdistributor") && lowerName.contains("sendtoserver")) return true;
        return lowerOwner.contains("networkmanager") && lowerName.contains("sendtoserver")
                || lowerOwner.contains("clientplaynetworkhandler") && lowerName.contains("sendpacket")
                || lowerOwner.contains("clientpacketlistener") && lowerName.equals("send");
    }

    private static boolean clientSend(String lowerOwner, String lowerName, String desc) {
        if (serverSend(lowerOwner, lowerName, desc)) return false;
        if (lowerOwner.contains("simplechannel") && lowerName.equals("send")) return true;
        if (lowerOwner.contains("packetdistributor") && lowerName.startsWith("send")) return true;
        if (lowerOwner.contains("serverplayer") && lowerName.contains("send")) return true;
        return lowerDescHasPacket(desc) && (lowerName.contains("send") || lowerName.contains("reply"));
    }

    /** Framework-neutral send APIs often expose one PacketSink for both directions. In client
     * tick/render/input code a payload send is necessarily serverbound; on server code the same
     * sink shape is clientbound. This avoids hard-coding any networking library or mod id. */
    private static boolean ambiguousPayloadSend(String lowerOwner, String lowerName, String desc) {
        if (!lowerDescHasPacket(desc) || !(lowerName.contains("send") || lowerName.contains("reply"))) {
            return false;
        }
        return lowerOwner.contains("sink") || lowerOwner.contains("sender")
                || lowerOwner.contains("network") || lowerOwner.contains("packet");
    }

    private static boolean clientOrigin(Trigger trigger) {
        return trigger == Trigger.CLIENT_TICK || trigger == Trigger.HUD_RENDER
                || trigger == Trigger.WORLD_RENDER || trigger == Trigger.MOUSE_INPUT
                || trigger == Trigger.KEY_INPUT || trigger == Trigger.PARTICLE_RENDER
                || trigger == Trigger.ENTITY_RENDER || trigger == Trigger.BLOCK_ENTITY_RENDER
                || trigger == Trigger.ITEM_RENDER;
    }

    private static boolean lowerDescHasPacket(String desc) {
        String lower = desc == null ? "" : desc.toLowerCase(Locale.ROOT);
        return lower.contains("packet") || lower.contains("payload") || lower.contains("object;");
    }

    private static void propagateLocalEffects(Map<String, MethodBehavior> methods) {
        Map<MethodBehavior, Set<MethodBehavior>> callers = new LinkedHashMap<>();
        for (MethodBehavior caller : methods.values()) {
            for (String local : caller.localTargets) {
                MethodBehavior child = methods.get(local);
                if (child != null && child != caller) {
                    callers.computeIfAbsent(child, ignored -> new LinkedHashSet<>()).add(caller);
                }
            }
        }
        propagateEffects(methods.values(), callers);
    }

    static void propagateEffects(Collection<MethodBehavior> methods,
                                  Map<MethodBehavior, Set<MethodBehavior>> callers) {
        java.util.ArrayDeque<MethodBehavior> queue = new java.util.ArrayDeque<>(methods);
        Set<MethodBehavior> queued = new HashSet<>(methods);
        while (!queue.isEmpty()) {
            MethodBehavior child = queue.removeFirst();
            queued.remove(child);
            for (MethodBehavior target : callers.getOrDefault(child, Set.of())) {
                boolean changed = target.effects.addAll(child.effects);
                int subjectsBefore = target.subjects.size();
                for (String value : child.subjects) addBounded(target.subjects, value, MAX_RESOURCES);
                changed |= subjectsBefore != target.subjects.size();
                int resourcesBefore = target.resources.size();
                for (String value : child.resources) addBounded(target.resources, value, MAX_RESOURCES);
                changed |= resourcesBefore != target.resources.size();
                for (CallEdge call : child.calls) {
                    if (containsCall(target.calls, call)) continue;
                    if (target.calls.size() >= MAX_CALLS) {
                        changed |= target.analysisWarnings.add("effect-call inventory truncated");
                        break;
                    }
                    target.calls.add(call);
                    changed = true;
                }
                changed |= target.analysisWarnings.addAll(child.analysisWarnings);
                if (changed && queued.add(target)) queue.addLast(target);
            }
        }
    }

    static void chooseTranslations(BehaviorSurface surface) {
        for (MethodBehavior method : surface.methods) {
            if (method.effects.contains(Effect.SEND_SERVER_PACKET)
                    && (method.trigger == Trigger.MOUSE_INPUT || method.trigger == Trigger.KEY_INPUT
                    || method.trigger == Trigger.CLIENT_TICK || method.trigger == Trigger.ITEM_USE
                    || method.trigger == Trigger.ITEM_SWING)) {
                surface.translations.add(Translation.INPUT_PACKET_REPLAY);
            }
            if (method.trigger == Trigger.PACKET_HANDLE) {
                surface.translations.add(Translation.SERVER_HANDLER_REPLAY);
            }
        }
        if (surface.effects.stream().anyMatch(effect -> effect == Effect.SPAWN_ENTITY
                || effect == Effect.MOVE_ENTITY || effect == Effect.TELEPORT_ENTITY
                || effect == Effect.WRITE_ENTITY_STATE)) surface.translations.add(Translation.ENTITY_PROXY);
        if (surface.effects.contains(Effect.ADD_PARTICLE)) surface.translations.add(Translation.PARTICLE_BRIDGE);
        if (surface.effects.contains(Effect.PLAY_SOUND) || surface.effects.contains(Effect.STOP_SOUND)) {
            surface.translations.add(Translation.SOUND_BRIDGE);
        }
        if (surface.effects.contains(Effect.RENDER_GEOMETRY) || surface.effects.contains(Effect.RENDER_MODEL)) {
            surface.translations.add(Translation.CAPTURE_RENDER);
            surface.translations.add(Translation.DISPLAY_PROXY);
        }
        if (surface.effects.stream().anyMatch(effect -> effect == Effect.WRITE_ITEM_STATE
                || effect == Effect.WRITE_ENTITY_STATE || effect == Effect.READ_ENTITY_STATE
                || effect == Effect.STATE_SYNC
                || effect == Effect.CAPABILITY_READ || effect == Effect.CAPABILITY_WRITE)) {
            surface.translations.add(Translation.STATE_MIRROR);
        }
        if (surface.effects.contains(Effect.RAYCAST)) surface.translations.add(Translation.RAYCAST_BRIDGE);
        if (surface.translations.isEmpty() && !surface.effects.isEmpty()) {
            surface.translations.add(Translation.UNSUPPORTED);
        }
    }

    /** Returns subject-specific input packet recipes; no namespace-wide guessing. */
    public static List<PacketRecipe> inputPacketsForSubject(Collection<ModScanner.Profile> profiles,
                                                            String subjectId) {
        if (profiles == null || subjectId == null) return List.of();
        ArrayList<PacketRecipe> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collectInputPackets(profile, subjectId, out, seen);
        out.sort(Comparator.comparingInt(PacketRecipe::confidence).reversed());
        return List.copyOf(out);
    }

    /**
     * Resolves constructor recipes for an exact set of packet classes already selected by another
     * scanner edge (for example {@code key -> SEND_PACKET}).  This intentionally does not perform
     * namespace matching: the caller must supply concrete bytecode targets.  Runtime replay uses
     * this as a second proof layer so merely mentioning a packet class is not enough to execute it.
     */
    public static List<PacketRecipe> packetRecipesForTargets(Collection<ModScanner.Profile> profiles,
                                                             Collection<String> packetClasses) {
        if (profiles == null || packetClasses == null || packetClasses.isEmpty()) return List.of();
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String packet : packetClasses) {
            if (packet != null && !packet.isBlank()) wanted.add(packet.replace('.', '/'));
        }
        if (wanted.isEmpty()) return List.of();
        ArrayList<PacketRecipe> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collectPacketRecipes(profile, wanted, out, seen);
        out.sort(Comparator.comparingInt(PacketRecipe::confidence).reversed());
        return List.copyOf(out);
    }

    /**
     * Exact mouse-wheel packet recipes. This is intentionally structural rather than name based:
     * the client method must consume a double scroll delta, return a consumed flag, read the
     * player's root vehicle, and construct an integer-valued serverbound packet on that path.
     * Vanilla hotbar scrolling gives the runtime the same signed +/-1 notch, so that integer can
     * be replayed without inventing client state.
     */
    public static List<PacketRecipe> vehicleScrollPackets(Collection<ModScanner.Profile> profiles) {
        if (profiles == null) return List.of();
        ArrayList<PacketRecipe> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collectVehicleScrollPackets(profile, out, seen);
        out.sort(Comparator.comparingInt(PacketRecipe::confidence).reversed());
        return List.copyOf(out);
    }

    private static void collectVehicleScrollPackets(ModScanner.Profile profile,
                                                     List<PacketRecipe> out, Set<String> seen) {
        if (profile == null) return;
        if (profile.behaviors != null) {
            for (BehaviorSurface surface : profile.behaviors) {
                if (surface == null) continue;
                for (MethodBehavior method : surface.methods) {
                    if (method == null || method.descriptor == null
                            || !method.descriptor.endsWith("D)Z")) continue;
                    boolean rootVehicle = method.invocations.stream().anyMatch(call -> call != null
                            && "net/minecraft/client/player/LocalPlayer".equals(call.owner)
                            && "getRootVehicle".equals(call.method)
                            && "()Lnet/minecraft/world/entity/Entity;".equals(call.descriptor));
                    if (!rootVehicle) continue;
                    for (CallEdge call : method.calls) {
                        if (call == null || call.effect != Effect.SEND_SERVER_PACKET
                                || call.target == null || !"(I)V".equals(call.constructorDesc)) continue;
                        String key = call.target.replace('.', '/') + "\n(I)V";
                        if (!seen.add(key)) continue;
                        out.add(new PacketRecipe(call.target.replace('.', '/'), "(I)V", null,
                                Trigger.MOUSE_INPUT,
                                Math.min(100, Math.max(method.confidence, call.confidence))));
                    }
                }
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collectVehicleScrollPackets(child, out, seen);
        }
    }

    /** True only when discovery saw the concrete packet's server-side handler bytecode. */
    public static boolean hasServerHandler(Collection<ModScanner.Profile> profiles, String packetClass) {
        if (profiles == null || packetClass == null || packetClass.isBlank()) return false;
        String wanted = packetClass.replace('.', '/');
        for (ModScanner.Profile profile : profiles) {
            if (profileHasServerHandler(profile, wanted)
                    || profileHasServerMutation(profile, wanted)) return true;
        }
        return false;
    }

    private static void collectPacketRecipes(ModScanner.Profile profile, Set<String> wanted,
                                             List<PacketRecipe> out, Set<String> seen) {
        if (profile == null) return;
        if (profile.behaviors != null) {
            for (BehaviorSurface surface : profile.behaviors) {
                if (surface == null) continue;
                for (MethodBehavior method : surface.methods) {
                    for (CallEdge call : method.calls) {
                        if (call.effect != Effect.SEND_SERVER_PACKET || call.target == null
                                || call.constructorDesc == null) continue;
                        String target = call.target.replace('.', '/');
                        if (!wanted.contains(target)) continue;
                        String key = target + "\n" + call.constructorDesc + "\n" + call.booleanArgument;
                        if (!seen.add(key)) continue;
                        out.add(new PacketRecipe(target, call.constructorDesc, call.booleanArgument,
                                method.trigger, Math.min(100, Math.max(method.confidence, call.confidence))));
                    }
                }
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collectPacketRecipes(child, wanted, out, seen);
        }
    }

    private static boolean profileHasServerHandler(ModScanner.Profile profile, String wanted) {
        if (profile == null) return false;
        if (profile.behaviors != null) {
            for (BehaviorSurface surface : profile.behaviors) {
                if (surface == null || surface.clazz == null
                        || !surface.clazz.replace('.', '/').equals(wanted)) continue;
                for (MethodBehavior method : surface.methods) {
                    if (method.trigger == Trigger.PACKET_HANDLE) return true;
                }
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                if (profileHasServerHandler(child, wanted)) return true;
            }
        }
        return false;
    }

    /** ServerMutationScanner recognises common cross-loader handlers whose signature carries a
     * Player/PacketListener/Executor tuple instead of the loader-specific payload context. That is
     * still exact server-handler bytecode evidence for the concrete packet class. */
    private static boolean profileHasServerMutation(ModScanner.Profile profile, String wanted) {
        if (profile == null) return false;
        if (profile.serverMutations != null) {
            for (ServerMutationScanner.Recipe recipe : profile.serverMutations) {
                if (recipe != null && recipe.packetClass != null
                        && wanted.equals(recipe.packetClass.replace('.', '/'))
                        && recipe.handlerMethod != null && !recipe.handlerMethod.isBlank()) {
                    return true;
                }
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                if (profileHasServerMutation(child, wanted)) return true;
            }
        }
        return false;
    }

    private static void collectInputPackets(ModScanner.Profile profile, String subjectId,
                                            List<PacketRecipe> out, Set<String> seen) {
        if (profile == null) return;
        if (profile.behaviors != null) {
            for (BehaviorSurface surface : profile.behaviors) {
                if (surface == null) continue;
                for (MethodBehavior method : surface.methods) {
                    if (!method.subjects.contains(subjectId)) continue;
                    if (!(method.trigger == Trigger.MOUSE_INPUT || method.trigger == Trigger.KEY_INPUT
                            || method.trigger == Trigger.CLIENT_TICK || method.trigger == Trigger.ITEM_USE
                            || method.trigger == Trigger.ITEM_SWING)) continue;
                    for (CallEdge call : method.calls) {
                        if (call.effect != Effect.SEND_SERVER_PACKET || call.target == null) continue;
                        String key = call.target + "\n" + call.constructorDesc + "\n" + call.booleanArgument;
                        if (!seen.add(key)) continue;
                        out.add(new PacketRecipe(call.target, call.constructorDesc, call.booleanArgument,
                                method.trigger, Math.min(100, Math.max(method.confidence, call.confidence))));
                    }
                }
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collectInputPackets(child, subjectId, out, seen);
        }
    }

    /** Machine-readable behavior inventory written alongside client_surfaces.json. */
    public static Summary writeReport(Collection<ModScanner.Profile> profiles, Path file) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        root.addProperty("directExecutionAllowed", false);
        root.addProperty("captureClientDirectExecutionAvailable", true);
        root.addProperty("purpose", "bytecode behavior translation inventory");
        root.add("packetCodecs", new GsonBuilder().create().toJsonTree(
                PacketCodecBytecodeScanner.all(profiles)));
        root.add("dynamicSurfaces", new GsonBuilder().create().toJsonTree(
                DynamicSurfaceBytecodeScanner.discover(profiles)));
        JsonArray mods = new JsonArray();
        int[] counts = new int[5];
        Set<String> seen = new HashSet<>();
        if (profiles != null) {
            for (ModScanner.Profile profile : profiles) appendProfile(profile, mods, counts, seen);
        }
        JsonObject summary = new JsonObject();
        summary.addProperty("mods", counts[0]);
        summary.addProperty("surfaces", counts[1]);
        summary.addProperty("methods", counts[2]);
        summary.addProperty("effects", counts[3]);
        summary.addProperty("replayablePackets", counts[4]);
        root.add("summary", summary);
        root.add("mods", mods);
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root),
                StandardCharsets.UTF_8);
        return new Summary(counts[0], counts[1], counts[2], counts[3], counts[4]);
    }

    private static void appendProfile(ModScanner.Profile profile, JsonArray mods, int[] counts,
                                      Set<String> seen) {
        if (profile == null) return;
        String identity = profile.jarFile + "\n" + profile.modId;
        if (!seen.add(identity)) return;
        if (profile.behaviors != null && !profile.behaviors.isEmpty()) {
            JsonObject mod = new JsonObject();
            mod.addProperty("modId", profile.modId);
            mod.addProperty("jar", profile.jarFile);
            mod.addProperty("version", profile.version);
            mod.add("behaviors", new GsonBuilder().create().toJsonTree(profile.behaviors));
            mods.add(mod);
            counts[0]++;
            counts[1] += profile.behaviors.size();
            for (BehaviorSurface surface : profile.behaviors) {
                counts[2] += surface.methods.size();
                for (MethodBehavior method : surface.methods) {
                    counts[3] += method.effects.size();
                    for (CallEdge call : method.calls) {
                        if (call.effect == Effect.SEND_SERVER_PACKET && call.constructorDesc != null) counts[4]++;
                    }
                }
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) appendProfile(child, mods, counts, seen);
        }
    }

    private static void addEffect(MethodBehavior out, Effect effect, String target, String owner,
                                  String method, String descriptor, String constructorDesc,
                                  Boolean booleanArgument, int confidence) {
        out.effects.add(effect);
        CallEdge edge = new CallEdge();
        edge.effect = effect;
        edge.owner = owner;
        edge.method = method;
        edge.descriptor = descriptor;
        edge.target = target;
        edge.constructorDesc = constructorDesc;
        edge.booleanArgument = booleanArgument;
        edge.confidence = confidence;
        if (!containsCall(out.calls, edge)) {
            if (out.calls.size() >= MAX_CALLS) out.analysisWarnings.add("effect-call inventory truncated");
            else out.calls.add(edge);
        }
    }

    private static boolean containsCall(List<CallEdge> calls, CallEdge wanted) {
        for (CallEdge call : calls) {
            if (call.effect == wanted.effect && eq(call.target, wanted.target)
                    && eq(call.constructorDesc, wanted.constructorDesc)
                    && eq(call.booleanArgument, wanted.booleanArgument)
                    && eq(call.owner, wanted.owner) && eq(call.method, wanted.method)
                    && eq(call.descriptor, wanted.descriptor)) return true;
        }
        return false;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean registrationCall(MethodInsnNode call) {
        if (call == null || call.desc == null) return false;
        String lowerOwner = call.owner.toLowerCase(Locale.ROOT);
        String lowerName = call.name.toLowerCase(Locale.ROOT);
        return lowerName.equals("register") && call.desc.contains("Ljava/lang/String;")
                && (call.desc.contains("Ljava/util/function/Supplier;")
                || lowerOwner.contains("deferredregister"));
    }

    private static String registryKind(String owner, String desc) {
        String lower = (owner + " " + desc).toLowerCase(Locale.ROOT);
        if (lower.contains("item")) return "item";
        if (lower.contains("entity")) return "entity";
        if (lower.contains("particle")) return "particle";
        if (lower.contains("sound")) return "sound";
        if (lower.contains("block")) return "block";
        if (lower.contains("fluid")) return "fluid";
        return "registry";
    }

    private static boolean isSubjectKind(String kind) {
        return "item".equals(kind) || "entity".equals(kind) || "block".equals(kind)
                || "registry".equals(kind);
    }

    private static String implementationFromInvokeDynamic(InvokeDynamicInsnNode indy,
                                                           Map<String, ClassNode> nodes,
                                                           Set<String> visiting, int depth) {
        if (indy == null || depth > 4) return null;
        for (Object arg : indy.bsmArgs) {
            if (!(arg instanceof Handle handle)) continue;
            String found = implementationFromHandle(handle, nodes, visiting, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static String implementationFromHandle(Handle handle, Map<String, ClassNode> nodes,
                                                   Set<String> visiting, int depth) {
        if (handle == null || depth > 4) return null;
        if ("<init>".equals(handle.getName()) && nodes.containsKey(handle.getOwner())) return handle.getOwner();
        String key = handle.getOwner() + "." + handle.getName() + handle.getDesc();
        if (!visiting.add(key)) return null;
        ClassNode node = nodes.get(handle.getOwner());
        if (node == null) return null;
        MethodNode method = findMethod(node, handle.getName(), handle.getDesc());
        if (method == null) return null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && nodes.containsKey(type.desc) && usefulImplementation(type.desc)) {
                return type.desc;
            }
            if (instruction instanceof InvokeDynamicInsnNode nested) {
                String found = implementationFromInvokeDynamic(nested, nodes, visiting, depth + 1);
                if (found != null) return found;
            }
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && "<init>".equals(call.name) && nodes.containsKey(call.owner)
                    && usefulImplementation(call.owner)) return call.owner;
        }
        return null;
    }

    private static boolean usefulImplementation(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.contains("/init/") && !lower.endsWith("init")
                && !lower.contains("builder") && !lower.contains("properties");
    }

    private static String previousString(List<AbstractInsnNode> insns, int from, int max) {
        for (int i = from - 1, seen = 0; i >= 0 && seen++ < max; i--) {
            AbstractInsnNode instruction = insns.get(i);
            if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String value) return value;
        }
        return null;
    }

    private static FieldInsnNode nextStaticStore(List<AbstractInsnNode> insns, int from, int max) {
        for (int i = from + 1, seen = 0; i < insns.size() && seen++ < max; i++) {
            AbstractInsnNode instruction = insns.get(i);
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC) return field;
            if (instruction instanceof MethodInsnNode call && !"<init>".equals(call.name)) break;
        }
        return null;
    }

    private static InvokeDynamicInsnNode previousInvokeDynamic(List<AbstractInsnNode> insns,
                                                                int from, int max) {
        for (int i = from - 1, seen = 0; i >= 0 && seen++ < max; i--) {
            if (insns.get(i) instanceof InvokeDynamicInsnNode indy) return indy;
        }
        return null;
    }

    private static Boolean booleanArgument(List<AbstractInsnNode> insns, int constructorIndex) {
        for (int i = constructorIndex - 1, seen = 0; i >= 0 && seen++ < 6; i--) {
            AbstractInsnNode instruction = insns.get(i);
            if (instruction.getOpcode() == Opcodes.ICONST_0) return Boolean.FALSE;
            if (instruction.getOpcode() == Opcodes.ICONST_1) return Boolean.TRUE;
            if (instruction instanceof MethodInsnNode || instruction instanceof FieldInsnNode
                    || instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.NEW) break;
        }
        return null;
    }

    private static boolean packetHandlerDescriptor(String desc) {
        if (desc == null) return false;
        String lower = desc.toLowerCase(Locale.ROOT);
        return lower.contains("networkevent$context") || lower.contains("payloadcontext")
                || lower.contains("packetcontext")
                || lower.contains("custompayload") && lower.contains("context");
    }

    /** Client packet frameworks often deliver an already-decoded semantic payload directly to
     * {@code handle(payload, Minecraft)} instead of exposing a loader Context parameter. Treat that
     * as a packet boundary only when the method name is handler-like, one argument is exactly the
     * Minecraft client, and another object argument is structurally a payload class. */
    private static boolean clientPacketHandlerDescriptor(MethodNode method,
                                                         Map<String, ClassNode> nodes) {
        if (method == null || method.desc == null || method.name == null) return false;
        String name = method.name.toLowerCase(Locale.ROOT);
        if (!(name.equals("handle") || name.equals("handler") || name.equals("process")
                || name.equals("receive"))) return false;
        Type[] args;
        try {
            args = Type.getArgumentTypes(method.desc);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        boolean minecraft = false;
        boolean payload = false;
        for (Type arg : args) {
            if (arg.getSort() != Type.OBJECT) continue;
            String type = arg.getInternalName();
            if ("net/minecraft/client/Minecraft".equals(type)) minecraft = true;
            else if (payloadClass(type, nodes)) payload = true;
        }
        return minecraft && payload;
    }

    /** Mojang names are unavailable in production jars, but Minecraft#setScreen keeps the exact
     * owner and one-Screen-argument void descriptor. That signature is narrow enough to recover the
     * navigation edge without relying on mappings or a mod-specific handler name. */
    private static boolean minecraftScreenSetter(String owner, String descriptor) {
        if (!"net/minecraft/client/Minecraft".equals(owner) || descriptor == null) return false;
        try {
            Type[] args = Type.getArgumentTypes(descriptor);
            return args.length == 1 && args[0].getSort() == Type.OBJECT
                    && "net/minecraft/client/gui/screens/Screen".equals(args[0].getInternalName())
                    && Type.getReturnType(descriptor).getSort() == Type.VOID;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean renderDescriptor(String desc) {
        if (desc == null) return false;
        String lower = desc.toLowerCase(Locale.ROOT);
        return lower.contains("posestack") || lower.contains("vertexconsumer")
                || lower.contains("multibuffersource") || lower.contains("guigraphics")
                || lower.contains("renderlevelstageevent") || lower.contains("renderguievent");
    }

    private static String returnedObject(String descriptor) {
        if (descriptor == null) return null;
        try {
            Type type = Type.getReturnType(descriptor);
            return type.getSort() == Type.OBJECT ? type.getInternalName() : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** Payload identification is bytecode-shape based first (implemented packet/payload interface)
     * with a class-name fallback for legacy SimpleChannel-style message classes. */
    private static boolean payloadClass(String type, Map<String, ClassNode> nodes) {
        if (type == null || type.isBlank()) return false;
        String lower = type.toLowerCase(Locale.ROOT);
        if (lower.startsWith("java/") || lower.startsWith("javax/")
                || lower.startsWith("net/minecraft/client/")) return false;
        String simple = lower.substring(lower.lastIndexOf('/') + 1);
        if (simple.contains("packetsink") || simple.contains("packetmanager")
                || simple.contains("packetcontext") || simple.contains("packethandler")
                || simple.contains("packetlistener") || simple.contains("packetchannel")) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        String current = type;
        while (current != null && seen.add(current)) {
            String currentLower = current.toLowerCase(Locale.ROOT);
            if (currentLower.contains("custompacketpayload")
                    || currentLower.endsWith("/packet") || currentLower.endsWith("packet")
                    || currentLower.endsWith("payload")) return true;
            ClassNode node = nodes == null ? null : nodes.get(current);
            if (node == null) break;
            if (node.interfaces != null) {
                for (String iface : node.interfaces) {
                    String ifaceLower = iface == null ? "" : iface.toLowerCase(Locale.ROOT);
                    if (ifaceLower.contains("custompacketpayload")
                            || ifaceLower.endsWith("/packet") || ifaceLower.endsWith("payload")) {
                        return true;
                    }
                }
            }
            current = node.superName;
        }
        return lower.contains("/network/")
                && (simple.endsWith("packet") || simple.endsWith("payload")
                || simple.startsWith("message") || simple.endsWith("message"));
    }

    private static boolean looksLikeScreenClass(String type) {
        if (type == null) return false;
        String lower = type.toLowerCase(Locale.ROOT);
        int slash = lower.lastIndexOf('/');
        String simple = slash < 0 ? lower : lower.substring(slash + 1);
        return (lower.contains("/screen/") || lower.contains("/gui/"))
                && (simple.endsWith("screen") || simple.contains("screen$")
                || simple.endsWith("gui"));
    }

    private static boolean tickLike(String lowerName) {
        return lowerName.equals("tick") || lowerName.equals("basetick") || lowerName.equals("aistep")
                || lowerName.equals("m_8119_") || lowerName.equals("m_8107_")
                || lowerName.contains("tick");
    }

    private static boolean inheritsAny(ClassNode node, Set<String> roots,
                                       Map<String, ClassNode> nodes) {
        if (node == null) return false;
        Set<String> seen = new HashSet<>();
        String current = node.superName;
        while (current != null && seen.add(current)) {
            if (roots.contains(current)) return true;
            ClassNode parent = nodes == null ? null : nodes.get(current);
            current = parent == null ? null : parent.superName;
        }
        if (node.interfaces != null) {
            for (String iface : node.interfaces) if (roots.contains(iface)) return true;
        }
        return false;
    }

    private static MethodNode findMethod(ClassNode node, String name, String desc) {
        if (node == null || node.methods == null) return null;
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }

    private static List<AbstractInsnNode> toList(MethodNode method) {
        ArrayList<AbstractInsnNode> out = new ArrayList<>();
        if (method != null && method.instructions != null) {
            for (AbstractInsnNode instruction : method.instructions) out.add(instruction);
        }
        return out;
    }

    private static String methodKey(String name, String desc) {
        return String.valueOf(name) + String.valueOf(desc);
    }

    private static boolean simplePath(String value) {
        if (value == null || value.isBlank() || value.length() > 160) return false;
        return value.matches("[a-z0-9_.-]+(?::[a-z0-9_./-]+)?");
    }

    private static boolean resourceLike(String value) {
        if (value == null || value.isBlank() || value.length() > 180) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || lower.contains("textures/") || lower.contains("models/")
                || lower.contains("particles/") || lower.endsWith(".png")
                || lower.endsWith(".ogg") || lower.endsWith(".json")
                || lower.startsWith("item.") || lower.startsWith("entity.");
    }

    private static String firstInterestingSubject(Set<String> resources, String token) {
        if (resources == null) return null;
        for (String value : resources) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(token)) return value;
        }
        return null;
    }

    private static void addBounded(Set<String> set, String value, int max) {
        if (value != null && !value.isBlank() && set.size() < max) set.add(value);
    }

    private static void addInvocation(MethodBehavior out, MethodInsnNode call) {
        addInvocation(out, call, null, null, null, Map.of());
    }

    private static void addInvocation(MethodBehavior out, MethodInsnNode call,
                                      ClassNode owner, MethodNode method,
                                      BytecodeValueFlow flow, Map<String, ClassNode> nodes) {
        if (out == null || call == null) return;
        InvocationEdge edge = new InvocationEdge();
        edge.owner = call.owner;
        edge.method = call.name;
        edge.descriptor = call.desc;
        edge.opcode = call.getOpcode();
        if (owner != null && method != null && flow != null) {
            Integer receiverLocal = flow.receiverParameterLocal(owner, method, call);
            if (receiverLocal != null) {
                if ((method.access & Opcodes.ACC_STATIC) == 0 && receiverLocal == 0) {
                    edge.receiverIsThis = true;
                } else {
                    edge.receiverParameterIndex = methodParameterIndex(method, receiverLocal);
                }
            }
            Type[] arguments;
            try {
                arguments = Type.getArgumentTypes(call.desc);
            } catch (IllegalArgumentException ignored) {
                arguments = new Type[0];
            }
            for (int index = 0; index < arguments.length; index++) {
                InvocationArgument argument = new InvocationArgument();
                argument.index = index;
                argument.descriptor = arguments[index].getDescriptor();
                Integer parameterLocal = flow.argumentParameterLocal(owner, method, call, index);
                int parameter = methodParameterIndex(method, parameterLocal);
                if (parameter >= 0) {
                    argument.parameterIndex = parameter;
                } else {
                    Set<AbstractInsnNode> sources = flow.argumentSources(owner, method, call, index);
                    if (sources.size() == 1) {
                        AbstractInsnNode source = sources.iterator().next();
                        FieldOrigin origin = invocationFieldOrigin(
                                argument.descriptor, source, owner, method, flow, nodes);
                        if (origin != null) {
                            argument.fieldOwner = origin.field().owner;
                            argument.fieldName = origin.field().name;
                            argument.fieldDesc = origin.field().desc;
                            if ((method.access & Opcodes.ACC_STATIC) == 0 && origin.receiverLocal() == 0) {
                                argument.fieldReceiverIsThis = true;
                            } else {
                                argument.fieldReceiverParameterIndex =
                                        methodParameterIndex(method, origin.receiverLocal());
                            }
                        } else if (source instanceof MethodInsnNode lookup && mapLookup(lookup)) {
                            Set<AbstractInsnNode> keys = flow.argumentSources(owner, method, lookup, 0);
                            if (keys.size() == 1) {
                                AbstractInsnNode keySource = keys.iterator().next();
                                String keyDesc = sourceDescriptor(keySource);
                                FieldOrigin key = invocationFieldOrigin(
                                        keyDesc, keySource, owner, method, flow, nodes);
                                if (key != null) {
                                    argument.lookupOwner = lookup.owner;
                                    argument.lookupMethod = lookup.name;
                                    argument.lookupDesc = lookup.desc;
                                    argument.lookupKeyFieldOwner = key.field().owner;
                                    argument.lookupKeyFieldName = key.field().name;
                                    argument.lookupKeyFieldDesc = key.field().desc;
                                    if ((method.access & Opcodes.ACC_STATIC) == 0 && key.receiverLocal() == 0) {
                                        argument.lookupKeyFieldReceiverIsThis = true;
                                    } else {
                                        argument.lookupKeyFieldReceiverParameterIndex =
                                                methodParameterIndex(method, key.receiverLocal());
                                    }
                                }
                            }
                        }
                    }
                }
                edge.arguments.add(argument);
            }
        }
        for (InvocationEdge existing : out.invocations) {
            if (sameInvocation(existing, edge)) return;
        }
        out.invocations.add(edge);
    }

    private record FieldOrigin(FieldInsnNode field, int receiverLocal) {}

    private static FieldOrigin invocationFieldOrigin(
            String descriptor, AbstractInsnNode source, ClassNode owner, MethodNode method,
            BytecodeValueFlow flow, Map<String, ClassNode> nodes) {
        if (descriptor == null || source == null || owner == null || method == null || flow == null) {
            return null;
        }
        FieldInsnNode backing = null;
        Integer receiverLocal = null;
        if (source instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                && descriptor.equals(field.desc)) {
            backing = field;
            receiverLocal = flow.fieldReceiverParameterLocal(owner, method, field);
        } else if (source instanceof MethodInsnNode getter
                && getter.getOpcode() != Opcodes.INVOKESTATIC
                && Type.getArgumentTypes(getter.desc).length == 0
                && descriptor.equals(Type.getReturnType(getter.desc).getDescriptor())) {
            MethodNode getterMethod = findMethod(nodes == null ? null : nodes.get(getter.owner),
                    getter.name, getter.desc);
            backing = simpleGetterField(getterMethod, descriptor);
            receiverLocal = backing == null ? null : flow.receiverParameterLocal(owner, method, getter);
        }
        return backing == null || receiverLocal == null ? null
                : new FieldOrigin(backing, receiverLocal);
    }

    private static boolean mapLookup(MethodInsnNode call) {
        if (call == null || call.getOpcode() == Opcodes.INVOKESTATIC
                || !"get".equals(call.name)
                || !"(Ljava/lang/Object;)Ljava/lang/Object;".equals(call.desc)) return false;
        return "java/util/Map".equals(call.owner)
                || call.owner.startsWith("java/util/") && call.owner.endsWith("Map");
    }

    private static String sourceDescriptor(AbstractInsnNode source) {
        if (source instanceof FieldInsnNode field
                && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC)) {
            return field.desc;
        }
        if (source instanceof MethodInsnNode call) {
            try {
                return Type.getReturnType(call.desc).getDescriptor();
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static void addParameterFieldAssignment(MethodBehavior out, ClassNode owner,
                                                    MethodNode method, FieldInsnNode field,
                                                    BytecodeValueFlow flow) {
        if (out == null || owner == null || method == null || field == null || flow == null
                || field.getOpcode() != Opcodes.PUTFIELD || !owner.name.equals(field.owner)) return;
        Integer receiverLocal = flow.putFieldReceiverParameterLocal(owner, method, field);
        Integer valueLocal = flow.putFieldValueParameterLocal(owner, method, field);
        if (receiverLocal == null || valueLocal == null
                || (method.access & Opcodes.ACC_STATIC) != 0 || receiverLocal != 0) return;
        int parameter = methodParameterIndex(method, valueLocal);
        if (parameter < 0) return;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(method.desc);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if (parameter >= arguments.length || !field.desc.equals(arguments[parameter].getDescriptor())) return;
        for (FieldAssignment existing : out.parameterFieldAssignments) {
            if (existing.parameterIndex == parameter && eq(existing.fieldOwner, field.owner)
                    && eq(existing.fieldName, field.name) && eq(existing.fieldDesc, field.desc)) return;
        }
        FieldAssignment assignment = new FieldAssignment();
        assignment.fieldOwner = field.owner;
        assignment.fieldName = field.name;
        assignment.fieldDesc = field.desc;
        assignment.parameterIndex = parameter;
        out.parameterFieldAssignments.add(assignment);
    }

    private static int methodParameterIndex(MethodNode method, Integer local) {
        if (method == null || local == null) return -1;
        Type[] arguments;
        try {
            arguments = Type.getArgumentTypes(method.desc);
        } catch (IllegalArgumentException ignored) {
            return -1;
        }
        int slot = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (int index = 0; index < arguments.length; index++) {
            if (slot == local) return index;
            slot += arguments[index].getSize();
        }
        return -1;
    }

    private static FieldInsnNode simpleGetterField(MethodNode method, String descriptor) {
        if (method == null || descriptor == null || method.instructions == null) return null;
        ArrayList<AbstractInsnNode> real = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) real.add(instruction);
        }
        if (real.size() != 3 || !(real.get(0) instanceof org.objectweb.asm.tree.VarInsnNode self)
                || self.getOpcode() != Opcodes.ALOAD || self.var != 0
                || !(real.get(1) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                || !descriptor.equals(field.desc)) return null;
        int expectedReturn = switch (Type.getType(descriptor).getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.IRETURN;
            case Type.LONG -> Opcodes.LRETURN;
            case Type.FLOAT -> Opcodes.FRETURN;
            case Type.DOUBLE -> Opcodes.DRETURN;
            case Type.OBJECT, Type.ARRAY -> Opcodes.ARETURN;
            default -> -1;
        };
        return real.get(2).getOpcode() == expectedReturn ? field : null;
    }

    private static boolean sameInvocation(InvocationEdge left, InvocationEdge right) {
        if (left == null || right == null || left.opcode != right.opcode
                || left.receiverParameterIndex != right.receiverParameterIndex
                || left.receiverIsThis != right.receiverIsThis
                || !eq(left.owner, right.owner) || !eq(left.method, right.method)
                || !eq(left.descriptor, right.descriptor)
                || left.arguments.size() != right.arguments.size()) return false;
        for (int i = 0; i < left.arguments.size(); i++) {
            InvocationArgument a = left.arguments.get(i);
            InvocationArgument b = right.arguments.get(i);
            if (a == b) continue;
            if (a == null || b == null || a.index != b.index
                    || a.parameterIndex != b.parameterIndex
                    || a.fieldReceiverParameterIndex != b.fieldReceiverParameterIndex
                    || a.fieldReceiverIsThis != b.fieldReceiverIsThis
                    || a.lookupKeyFieldReceiverParameterIndex != b.lookupKeyFieldReceiverParameterIndex
                    || a.lookupKeyFieldReceiverIsThis != b.lookupKeyFieldReceiverIsThis
                    || !eq(a.descriptor, b.descriptor) || !eq(a.fieldOwner, b.fieldOwner)
                    || !eq(a.fieldName, b.fieldName) || !eq(a.fieldDesc, b.fieldDesc)
                    || !eq(a.lookupOwner, b.lookupOwner) || !eq(a.lookupMethod, b.lookupMethod)
                    || !eq(a.lookupDesc, b.lookupDesc)
                    || !eq(a.lookupKeyFieldOwner, b.lookupKeyFieldOwner)
                    || !eq(a.lookupKeyFieldName, b.lookupKeyFieldName)
                    || !eq(a.lookupKeyFieldDesc, b.lookupKeyFieldDesc)) return false;
        }
        return true;
    }

    private static RecentConstruct recentWithin(RecentConstruct recent, int ordinal, int maxDistance) {
        return recent != null && ordinal - recent.ordinal <= maxDistance ? recent : null;
    }

    private record RecentConstruct(String type, String ctorDesc, Boolean booleanArgument, int ordinal) {}
}
