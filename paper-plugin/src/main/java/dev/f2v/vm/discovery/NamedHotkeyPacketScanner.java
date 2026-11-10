package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recovers a narrow but common dynamic-hotkey shape without loading client code.
 *
 * <p>Some mods register server actions by a short string id, manufacture the visible key id as
 * {@code prefix + actionId + suffix}, wrap that id together with a KeyMapping, and later send a
 * packet whose one String payload is the original action id. This scanner proves all of those
 * links: dynamic StringConcatFactory key construction, String forwarding through registration
 * helpers/interfaces, the key wrapper's click path, and a packet constructor that delegates the
 * wrapper's stored id to an exact {@code (String)} constructor.</p>
 */
public final class NamedHotkeyPacketScanner {

    private static final String STRING_DESC = "Ljava/lang/String;";

    private NamedHotkeyPacketScanner() {}

    public static List<ClientSurfaceScanner.ClassSurface> scan(Map<String, ClassNode> nodes,
                                                               Collection<String> translationKeys) {
        if (nodes == null || nodes.isEmpty() || translationKeys == null || translationKeys.isEmpty()) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String key : translationKeys) {
            if (key != null && !key.isBlank()) keys.add(key);
        }
        if (keys.isEmpty()) return List.of();

        List<Template> templates = new ArrayList<>();
        for (ClassNode node : ordered(nodes)) {
            if (node == null || node.methods == null) continue;
            for (MethodNode method : node.methods) {
                Template template = dynamicKeyTemplate(node, method, nodes);
                if (template != null) templates.add(template);
            }
        }
        if (templates.isEmpty()) return List.of();

        List<ClientSurfaceScanner.ClassSurface> out = new ArrayList<>();
        for (Template template : templates) {
            PacketBridge packet = packetBridge(template.wrapper(), nodes);
            if (packet == null) continue;
            Set<String> names = registrationNames(template.factory(), nodes);
            if (names.isEmpty()) continue;

            ClientSurfaceScanner.ClassSurface surface = new ClientSurfaceScanner.ClassSurface();
            surface.clazz = template.factory().owner();
            ClassNode owner = nodes.get(template.factory().owner());
            surface.superClass = owner == null ? null : owner.superName;
            surface.clientOnlyLikely = true;
            surface.directExecutionAllowed = false;
            surface.clientScore = 10;
            surface.kinds.add(ClientSurfaceScanner.Kind.KEYBIND);
            surface.kinds.add(ClientSurfaceScanner.Kind.INPUT);
            surface.kinds.add(ClientSurfaceScanner.Kind.NETWORK);
            surface.translations.add(ClientSurfaceScanner.Translation.INPUT_BRIDGE);

            for (String name : names.stream().sorted().toList()) {
                String key = template.prefix() + name + template.suffix();
                if (!keys.contains(key)) continue;
                ClientSurfaceScanner.Action action = new ClientSurfaceScanner.Action();
                action.type = ClientSurfaceScanner.ActionType.SEND_PACKET;
                action.triggerType = ClientSurfaceScanner.TriggerType.KEYBIND;
                action.keybind = key;
                action.method = packet.wrapperMethod();
                action.target = packet.packetClass();
                action.constructorDesc = "(Ljava/lang/String;)V";
                action.stringArgument = name;
                action.producerOwner = packet.packetClass();
                action.producerMethod = "<init>";
                action.producerDesc = "(Ljava/lang/String;)V";
                action.confidence = 99;
                surface.keybinds.add(key);
                surface.actions.add(action);
            }
            if (!surface.actions.isEmpty()) out.add(surface);
        }
        return List.copyOf(out);
    }

    private record MethodRef(String owner, String name, String desc) {}
    private record Template(MethodRef factory, String wrapper, String prefix, String suffix) {}
    private record PacketBridge(String packetClass, String wrapperMethod) {}

    private static Template dynamicKeyTemplate(ClassNode owner, MethodNode method,
                                               Map<String, ClassNode> nodes) {
        if (owner == null || method == null || method.instructions == null) return null;
        int stringLocal = soleStringParameterLocal(method);
        if (stringLocal < 0) return null;
        String returned = objectReturn(method.desc);
        if (returned == null || !nodes.containsKey(returned)) return null;

        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof InvokeDynamicInsnNode indy)) continue;
            String[] template = concatTemplate(indy);
            if (template == null) continue;
            AbstractInsnNode source = previousReal(indy);
            if (!(source instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
                    || load.var != stringLocal) continue;

            MethodInsnNode keyConstructor = null;
            int remaining = 18;
            for (AbstractInsnNode next = instruction.getNext(); next != null && remaining-- > 0;
                 next = next.getNext()) {
                if (next instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && "<init>".equals(call.name) && isKeyMapping(call.owner)) {
                    keyConstructor = call;
                    break;
                }
            }
            if (keyConstructor == null) continue;

            MethodInsnNode wrapperConstructor = null;
            remaining = 18;
            for (AbstractInsnNode next = keyConstructor.getNext(); next != null && remaining-- > 0;
                 next = next.getNext()) {
                if (next instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && "<init>".equals(call.name) && returned.equals(call.owner)
                        && wrapperConstructorShape(call.desc)) {
                    wrapperConstructor = call;
                    break;
                }
            }
            if (wrapperConstructor == null) continue;
            String rawField = rawStringField(nodes.get(returned), wrapperConstructor.desc);
            if (rawField == null) continue;
            return new Template(new MethodRef(owner.name, method.name, method.desc), returned,
                    template[0], template[1]);
        }
        return null;
    }

    private static String[] concatTemplate(InvokeDynamicInsnNode indy) {
        if (indy == null || indy.bsm == null || indy.desc == null
                || !"java/lang/invoke/StringConcatFactory".equals(indy.bsm.getOwner())
                || !indy.bsm.getName().contains("makeConcat")) return null;
        Type[] args;
        Type result;
        try {
            args = Type.getArgumentTypes(indy.desc);
            result = Type.getReturnType(indy.desc);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        if (args.length != 1 || args[0].getSort() != Type.OBJECT
                || !"java/lang/String".equals(args[0].getInternalName())
                || result.getSort() != Type.OBJECT
                || !"java/lang/String".equals(result.getInternalName())) return null;
        for (Object arg : indy.bsmArgs) {
            if (!(arg instanceof String recipe)) continue;
            int marker = recipe.indexOf('\u0001');
            if (marker < 0 || recipe.indexOf('\u0001', marker + 1) >= 0) continue;
            return new String[]{recipe.substring(0, marker), recipe.substring(marker + 1)};
        }
        return null;
    }

    private static boolean wrapperConstructorShape(String desc) {
        try {
            Type[] args = Type.getArgumentTypes(desc);
            return args.length == 2 && isString(args[0])
                    && args[1].getSort() == Type.OBJECT && isKeyMapping(args[1].getInternalName());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String rawStringField(ClassNode wrapper, String constructorDesc) {
        if (wrapper == null || wrapper.methods == null) return null;
        MethodNode constructor = findMethod(wrapper, "<init>", constructorDesc);
        if (constructor == null) return null;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (!(instruction instanceof FieldInsnNode store) || store.getOpcode() != Opcodes.PUTFIELD
                    || !wrapper.name.equals(store.owner) || !STRING_DESC.equals(store.desc)) continue;
            AbstractInsnNode value = previousReal(store);
            AbstractInsnNode receiver = previousReal(value);
            if (value instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 1
                    && receiver instanceof VarInsnNode self && self.getOpcode() == Opcodes.ALOAD
                    && self.var == 0) return store.name;
        }
        return null;
    }

    private static PacketBridge packetBridge(String wrapperName, Map<String, ClassNode> nodes) {
        ClassNode wrapper = nodes.get(wrapperName);
        if (wrapper == null || wrapper.methods == null) return null;
        String rawField = wrapperRawField(wrapper);
        if (rawField == null) return null;
        List<PacketBridge> found = new ArrayList<>();
        for (MethodNode method : wrapper.methods) {
            if (method == null || method.instructions == null || !consumesKeyMapping(method)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode ctor)
                        || ctor.getOpcode() != Opcodes.INVOKESPECIAL || !"<init>".equals(ctor.name)
                        || !("(L" + wrapperName + ";)V").equals(ctor.desc)) continue;
                if (!sentSoonAfter(ctor)) continue;
                ClassNode packet = nodes.get(ctor.owner);
                if (packet == null || !packetDelegatesStoredString(packet, wrapper, rawField)) continue;
                found.add(new PacketBridge(packet.name, method.name + method.desc));
            }
        }
        found.sort(Comparator.comparing(PacketBridge::packetClass).thenComparing(PacketBridge::wrapperMethod));
        return found.isEmpty() ? null : found.get(0);
    }

    private static String wrapperRawField(ClassNode wrapper) {
        if (wrapper == null || wrapper.methods == null) return null;
        for (MethodNode method : wrapper.methods) {
            if (!"<init>".equals(method.name) || !wrapperConstructorShape(method.desc)) continue;
            String field = rawStringField(wrapper, method.desc);
            if (field != null) return field;
        }
        return null;
    }

    private static boolean consumesKeyMapping(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || !isKeyMapping(call.owner)) continue;
            String name = call.name == null ? "" : call.name.toLowerCase(java.util.Locale.ROOT);
            if ((name.contains("consumeclick") || name.equals("isdown"))
                    && "()Z".equals(call.desc)) return true;
        }
        return false;
    }

    private static boolean sentSoonAfter(MethodInsnNode constructor) {
        int remaining = 24;
        for (AbstractInsnNode next = constructor.getNext(); next != null && remaining-- > 0;
             next = next.getNext()) {
            if (next instanceof MethodInsnNode call && isServerSend(call)) return true;
        }
        return false;
    }

    private static boolean isServerSend(MethodInsnNode call) {
        if (call == null || call.owner == null || call.name == null) return false;
        String owner = call.owner.toLowerCase(java.util.Locale.ROOT);
        String name = call.name.toLowerCase(java.util.Locale.ROOT);
        if (name.contains("sendtoserver")) return true;
        if (owner.contains("clientplaynetworking") && name.contains("send")) return true;
        if (owner.contains("simplechannel") && name.contains("send")) return true;
        return owner.contains("packetdistributor") && name.contains("send");
    }

    private static boolean packetDelegatesStoredString(ClassNode packet, ClassNode wrapper,
                                                       String rawField) {
        if (packet == null || packet.methods == null || wrapper == null || rawField == null) return false;
        if (findMethod(packet, "<init>", "(Ljava/lang/String;)V") == null) return false;
        MethodNode bridge = findMethod(packet, "<init>", "(L" + wrapper.name + ";)V");
        if (bridge == null) return false;
        boolean accessor = false;
        for (AbstractInsnNode instruction : bridge.instructions) {
            if (instruction instanceof MethodInsnNode call && wrapper.name.equals(call.owner)
                    && "()Ljava/lang/String;".equals(call.desc)
                    && accessorReturnsField(wrapper, call.name, call.desc, rawField)) {
                accessor = true;
            } else if (accessor && instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL && packet.name.equals(call.owner)
                    && "<init>".equals(call.name) && "(Ljava/lang/String;)V".equals(call.desc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean accessorReturnsField(ClassNode wrapper, String name, String desc,
                                                String fieldName) {
        MethodNode method = findMethod(wrapper, name, desc);
        if (method == null) return false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && wrapper.name.equals(field.owner) && fieldName.equals(field.name)
                    && STRING_DESC.equals(field.desc)) return true;
        }
        return false;
    }

    private static Set<String> registrationNames(MethodRef factory, Map<String, ClassNode> nodes) {
        LinkedHashSet<MethodRef> known = new LinkedHashSet<>();
        known.add(factory);
        boolean changed;
        do {
            Set<MethodRef> aliases = aliases(known, nodes);
            changed = false;
            for (ClassNode owner : ordered(nodes)) {
                if (owner == null || owner.methods == null) continue;
                for (MethodNode method : owner.methods) {
                    int stringLocal = soleStringParameterLocal(method);
                    if (stringLocal < 0) continue;
                    for (AbstractInsnNode instruction : method.instructions) {
                        if (!(instruction instanceof MethodInsnNode call)
                                || !matches(aliases, call) || !lastArgumentString(call.desc)) continue;
                        AbstractInsnNode source = previousReal(call);
                        if (source instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                                && load.var == stringLocal) {
                            changed |= known.add(new MethodRef(owner.name, method.name, method.desc));
                        }
                    }
                }
            }
        } while (changed && known.size() < 256);

        Set<MethodRef> aliases = aliases(known, nodes);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (ClassNode owner : ordered(nodes)) {
            if (owner == null || owner.methods == null) continue;
            for (MethodNode method : owner.methods) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode call)
                            || !matches(aliases, call) || !lastArgumentString(call.desc)) continue;
                    String constant = precedingStringConstant(method, call);
                    if (constant != null && !constant.isBlank()) names.add(constant);
                }
            }
        }
        return names;
    }

    private static Set<MethodRef> aliases(Set<MethodRef> methods, Map<String, ClassNode> nodes) {
        LinkedHashSet<MethodRef> out = new LinkedHashSet<>(methods);
        for (MethodRef method : methods) {
            MethodNode declared = findMethod(nodes.get(method.owner()), method.name(), method.desc());
            if (declared == null || (declared.access & Opcodes.ACC_STATIC) != 0) continue;
            ArrayDeque<String> pending = new ArrayDeque<>();
            ClassNode owner = nodes.get(method.owner());
            if (owner != null) {
                if (owner.superName != null) pending.add(owner.superName);
                if (owner.interfaces != null) pending.addAll(owner.interfaces);
            }
            HashSet<String> seen = new HashSet<>();
            while (!pending.isEmpty()) {
                String parentName = pending.removeFirst();
                if (parentName == null || !seen.add(parentName)) continue;
                ClassNode parent = nodes.get(parentName);
                if (parent == null) continue;
                if (findMethod(parent, method.name(), method.desc()) != null) {
                    out.add(new MethodRef(parent.name, method.name(), method.desc()));
                }
                if (parent.superName != null) pending.add(parent.superName);
                if (parent.interfaces != null) pending.addAll(parent.interfaces);
            }
        }
        return out;
    }

    private static boolean matches(Set<MethodRef> refs, MethodInsnNode call) {
        return refs.contains(new MethodRef(call.owner, call.name, call.desc));
    }

    private static String precedingStringConstant(MethodNode method, MethodInsnNode call) {
        AbstractInsnNode source = previousReal(call);
        if (source instanceof LdcInsnNode ldc && ldc.cst instanceof String text) return text;
        if (!(source instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) return null;
        for (AbstractInsnNode previous = source.getPrevious(); previous != null; previous = previous.getPrevious()) {
            if (!(previous instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE
                    || store.var != load.var) continue;
            AbstractInsnNode value = previousReal(store);
            return value instanceof LdcInsnNode ldc && ldc.cst instanceof String text ? text : null;
        }
        return null;
    }

    private static int soleStringParameterLocal(MethodNode method) {
        if (method == null || method.desc == null) return -1;
        Type[] args;
        try {
            args = Type.getArgumentTypes(method.desc);
        } catch (IllegalArgumentException ignored) {
            return -1;
        }
        int local = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        int found = -1;
        for (Type arg : args) {
            if (isString(arg)) {
                if (found >= 0) return -1;
                found = local;
            }
            local += arg.getSize();
        }
        return found;
    }

    private static boolean lastArgumentString(String desc) {
        try {
            Type[] args = Type.getArgumentTypes(desc);
            return args.length > 0 && isString(args[args.length - 1]);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isString(Type type) {
        return type != null && type.getSort() == Type.OBJECT
                && "java/lang/String".equals(type.getInternalName());
    }

    private static String objectReturn(String desc) {
        try {
            Type type = Type.getReturnType(desc);
            return type.getSort() == Type.OBJECT ? type.getInternalName() : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isKeyMapping(String type) {
        if (type == null) return false;
        String lower = type.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith("/keymapping") || lower.endsWith("/keybinding")
                || lower.contains("client/keymapping") || lower.contains("client/option/keybinding");
    }

    private static MethodNode findMethod(ClassNode owner, String name, String desc) {
        if (owner == null || owner.methods == null) return null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) return method;
        }
        return null;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static List<ClassNode> ordered(Map<String, ClassNode> nodes) {
        return nodes.values().stream().filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(node -> node.name == null ? "" : node.name)).toList();
    }
}
