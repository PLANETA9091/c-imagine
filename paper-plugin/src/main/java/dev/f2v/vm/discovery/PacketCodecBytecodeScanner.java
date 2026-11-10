package dev.f2v.vm.discovery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers mod packet codecs without loading packet classes.
 *
 * <p>The scanner records executable recipes, not decoded values. Runtime code can call a mod's
 * own read/write function when that class exists in an appropriate runtime (dedicated server for
 * common packets, capture client for client-only packets). This avoids reimplementing custom wire
 * formats and also detects codecs composed from {@code StreamCodec} on newer versions.</p>
 */
public final class PacketCodecBytecodeScanner {

    public enum Style {
        FRIENDLY_BYTE_BUF,
        STREAM_CODEC
    }

    public enum OperationDirection {
        READ,
        WRITE,
        UNKNOWN
    }

    /** One byte-buffer operation in source order. field is best-effort evidence only. */
    public record Operation(OperationDirection direction, String owner, String method,
                            String descriptor, String field) {}

    /** Exact bytecode entrypoints for one packet's encoder/decoder. */
    public static final class Codec {
        public String packetClass;
        public Style style;
        public String readMethod;
        public String readDescriptor;
        public boolean readStatic;
        public String bufferConstructorDescriptor;
        public String writeMethod;
        public String writeDescriptor;
        public boolean writeStatic;
        public String streamCodecField;
        public final List<Operation> readOperations = new ArrayList<>();
        public final List<Operation> writeOperations = new ArrayList<>();

        public boolean hasDecoder() {
            return readMethod != null || bufferConstructorDescriptor != null || streamCodecField != null;
        }

        public boolean hasEncoder() {
            return writeMethod != null || streamCodecField != null;
        }

        @Override
        public String toString() {
            return packetClass + '[' + style + ", read=" + readMethod + readDescriptor
                    + ", ctor=" + bufferConstructorDescriptor + ", write=" + writeMethod
                    + writeDescriptor + ", codec=" + streamCodecField + ']';
        }
    }

    private PacketCodecBytecodeScanner() {}

    public static List<Codec> scan(Map<String, ClassNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        ArrayList<Codec> out = new ArrayList<>();
        for (ClassNode node : nodes.values()) {
            Codec codec = inspect(node, nodes);
            if (codec != null) out.add(codec);
        }
        out.sort(Comparator.comparing(codec -> codec.packetClass));
        return List.copyOf(out);
    }

    static Codec inspect(ClassNode node) {
        return inspect(node, node == null ? Map.of() : Map.of(node.name, node));
    }

    private static Codec inspect(ClassNode node, Map<String, ClassNode> nodes) {
        if (node == null || node.name == null || node.methods == null) return null;
        Codec codec = new Codec();
        codec.packetClass = node.name;

        if (node.fields != null) {
            for (FieldNode field : node.fields) {
                if (isStreamCodec(field.desc)) {
                    codec.style = Style.STREAM_CODEC;
                    if (codec.streamCodecField == null || preferredCodecField(field.name)) {
                        codec.streamCodecField = field.name;
                    }
                }
            }
        }

        for (MethodNode method : node.methods) {
            if ("<init>".equals(method.name) && singleBufferArgument(method.desc)) {
                codec.bufferConstructorDescriptor = method.desc;
                if (codec.style == null) codec.style = Style.FRIENDLY_BYTE_BUF;
                codec.readOperations.clear();
                codec.readOperations.addAll(operations(node, method, OperationDirection.READ, nodes));
                continue;
            }
            MethodShape shape = shape(node, method);
            if (shape == null) continue;
            if (shape.read && betterRead(codec, method)) {
                codec.readMethod = method.name;
                codec.readDescriptor = method.desc;
                codec.readStatic = (method.access & Opcodes.ACC_STATIC) != 0;
                codec.readOperations.clear();
                codec.readOperations.addAll(operations(node, method, OperationDirection.READ, nodes));
            }
            if (shape.write && betterWrite(codec, method)) {
                codec.writeMethod = method.name;
                codec.writeDescriptor = method.desc;
                codec.writeStatic = (method.access & Opcodes.ACC_STATIC) != 0;
                codec.writeOperations.clear();
                codec.writeOperations.addAll(operations(node, method, OperationDirection.WRITE, nodes));
            }
            if (codec.style == null && (shape.read || shape.write)) {
                codec.style = Style.FRIENDLY_BYTE_BUF;
            }
        }

        if (codec.style == Style.STREAM_CODEC) {
            for (MethodNode method : node.methods) {
                if (!"<clinit>".equals(method.name)) continue;
                List<Operation> composed = operations(node, method, OperationDirection.UNKNOWN, nodes);
                if (codec.readOperations.isEmpty()) codec.readOperations.addAll(composed);
                if (codec.writeOperations.isEmpty()) codec.writeOperations.addAll(composed);
            }
        }

        if (codec.style == null || (!codec.hasDecoder() && !codec.hasEncoder())) return null;
        return codec;
    }

    /** Returns all codecs in profiles, including nested jars, without namespace guessing. */
    public static List<Codec> all(Collection<ModScanner.Profile> profiles) {
        if (profiles == null) return List.of();
        ArrayList<Codec> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ModScanner.Profile profile : profiles) collect(profile, out, seen);
        out.sort(Comparator.comparing(codec -> codec.packetClass));
        return List.copyOf(out);
    }

    public static Codec forPacket(Collection<ModScanner.Profile> profiles, String packetClass) {
        if (profiles == null || packetClass == null) return null;
        String wanted = packetClass.replace('.', '/');
        for (Codec codec : all(profiles)) {
            if (wanted.equals(codec.packetClass.replace('.', '/'))) return codec;
        }
        return null;
    }

    private static void collect(ModScanner.Profile profile, List<Codec> out, Set<String> seen) {
        if (profile == null) return;
        if (profile.packetCodecs != null) {
            for (Codec codec : profile.packetCodecs) {
                if (codec != null && codec.packetClass != null && seen.add(codec.packetClass)) out.add(codec);
            }
        }
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) collect(child, out, seen);
        }
    }

    private static boolean preferredCodecField(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("stream_codec") || lower.equals("codec") || lower.contains("streamcodec");
    }

    private static boolean betterRead(Codec codec, MethodNode method) {
        if (codec.readMethod == null) return true;
        String lower = method.name.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("read") || lower.equals("decode") || lower.contains("fromnetwork");
    }

    private static boolean betterWrite(Codec codec, MethodNode method) {
        if (codec.writeMethod == null) return true;
        String lower = method.name.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("write") || lower.equals("encode") || lower.contains("tonetwork");
    }

    private record MethodShape(boolean read, boolean write) {}

    private static MethodShape shape(ClassNode owner, MethodNode method) {
        Type[] args;
        Type result;
        try {
            args = Type.getArgumentTypes(method.desc);
            result = Type.getReturnType(method.desc);
        } catch (Exception ignored) {
            return null;
        }
        boolean hasBuf = false;
        for (Type arg : args) {
            if (isBuffer(arg.getDescriptor())) {
                hasBuf = true;
                break;
            }
        }
        if (!hasBuf) return null;

        boolean staticMethod = (method.access & Opcodes.ACC_STATIC) != 0;
        boolean returnsPacket = result.getSort() == Type.OBJECT
                && owner.name.equals(result.getInternalName());
        boolean read = returnsPacket && staticMethod;

        boolean returnsVoid = result.getSort() == Type.VOID;
        boolean receiverPacket = !staticMethod;
        boolean staticPacketArg = false;
        if (staticMethod) {
            for (Type arg : args) {
                if (arg.getSort() == Type.OBJECT && owner.name.equals(arg.getInternalName())) {
                    staticPacketArg = true;
                    break;
                }
            }
        }
        boolean write = returnsVoid && (receiverPacket || staticPacketArg);

        String lower = method.name.toLowerCase(java.util.Locale.ROOT);
        if (!read && staticMethod && (lower.equals("read") || lower.equals("decode")
                || lower.contains("fromnetwork")) && returnsPacket) read = true;
        if (!write && returnsVoid && (lower.equals("write") || lower.equals("encode")
                || lower.contains("tonetwork"))) write = true;
        return read || write ? new MethodShape(read, write) : null;
    }

    private static List<Operation> operations(ClassNode packet, MethodNode method,
                                              OperationDirection expected,
                                              Map<String, ClassNode> nodes) {
        ArrayList<Operation> out = new ArrayList<>();
        collectOperations(packet, method, expected, nodes, out, new LinkedHashSet<>(), 0);
        return List.copyOf(out);
    }

    private static void collectOperations(ClassNode packet, MethodNode method,
                                          OperationDirection expected,
                                          Map<String, ClassNode> nodes,
                                          List<Operation> out, Set<String> visited, int depth) {
        if (method == null || method.instructions == null || depth > 7) return;
        String visit = (packet == null ? "?" : packet.name) + '#' + method.name + method.desc;
        if (!visited.add(visit)) return;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof InvokeDynamicInsnNode dynamic && dynamic.bsmArgs != null) {
                for (Object argument : dynamic.bsmArgs) {
                    if (!(argument instanceof Handle handle)) continue;
                    ClassNode lambdaOwner = nodes.get(handle.getOwner());
                    MethodNode lambda = findMethod(lambdaOwner, handle.getName(), handle.getDesc());
                    if (lambda != null) collectOperations(packet, lambda, expected, nodes,
                            out, visited, depth + 1);
                }
                continue;
            }
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (isBufferOwner(call.owner) || isCodecOwner(call.owner, call.desc)) {
                OperationDirection direction = direction(call.name);
                if (direction == OperationDirection.UNKNOWN) direction = expected;
                String field = nearbyPacketField(packet.name, insn,
                        direction == OperationDirection.WRITE);
                Operation operation = new Operation(direction, call.owner, call.name,
                        call.desc, field);
                if (!out.contains(operation)) out.add(operation);
            }
            if (!codecHelper(call, nodes)) continue;
            MethodNode callee = findMethod(nodes.get(call.owner), call.name, call.desc);
            if (callee != null) collectOperations(packet, callee, expected, nodes,
                    out, visited, depth + 1);
        }
    }

    private static MethodNode findMethod(ClassNode owner, String name, String descriptor) {
        if (owner == null || owner.methods == null) return null;
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
        }
        return null;
    }

    private static boolean codecHelper(MethodInsnNode call, Map<String, ClassNode> nodes) {
        if (call == null || nodes == null || !nodes.containsKey(call.owner)) return false;
        String descriptor = call.desc == null ? "" : call.desc;
        String name = call.name == null ? "" : call.name.toLowerCase(java.util.Locale.ROOT);
        return isBuffer(descriptor) || isStreamCodec(descriptor)
                || name.contains("encode") || name.contains("decode")
                || name.startsWith("read") || name.startsWith("write")
                || name.contains("codec");
    }

    private static boolean isCodecOwner(String owner, String descriptor) {
        if (owner == null) return false;
        String lower = owner.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("network/codec/") || lower.endsWith("streamcodec")
                || lower.endsWith("bytebufcodecs") || isStreamCodec(descriptor);
    }

    private static String nearbyPacketField(String packetOwner, AbstractInsnNode anchor, boolean backwards) {
        AbstractInsnNode cursor = anchor;
        for (int i = 0; i < 10; i++) {
            cursor = backwards ? cursor.getPrevious() : cursor.getNext();
            if (cursor == null) break;
            if (cursor instanceof FieldInsnNode field && field.owner.equals(packetOwner)
                    && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.PUTFIELD)) {
                return field.name;
            }
        }
        return null;
    }

    private static OperationDirection direction(String method) {
        if (method == null) return OperationDirection.UNKNOWN;
        String lower = method.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("read")) return OperationDirection.READ;
        if (lower.startsWith("write")) return OperationDirection.WRITE;
        return OperationDirection.UNKNOWN;
    }

    private static boolean singleBufferArgument(String descriptor) {
        try {
            Type[] args = Type.getArgumentTypes(descriptor);
            return args.length == 1 && isBuffer(args[0].getDescriptor());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isBufferOwner(String owner) {
        if (owner == null) return false;
        return owner.equals("net/minecraft/network/FriendlyByteBuf")
                || owner.equals("net/minecraft/network/RegistryFriendlyByteBuf")
                || owner.endsWith("/FriendlyByteBuf") || owner.endsWith("/RegistryFriendlyByteBuf");
    }

    private static boolean isBuffer(String descriptor) {
        if (descriptor == null) return false;
        return descriptor.contains("Lnet/minecraft/network/FriendlyByteBuf;")
                || descriptor.contains("Lnet/minecraft/network/RegistryFriendlyByteBuf;");
    }

    private static boolean isStreamCodec(String descriptor) {
        return descriptor != null && descriptor.contains("Lnet/minecraft/network/codec/StreamCodec;");
    }
}
