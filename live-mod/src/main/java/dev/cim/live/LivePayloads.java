package dev.cim.live;

import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;

import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A mod screen's button, run on the server the way the mod runs it: the client would build
 * the mod's payload and send it; here the same payload is built from what F2V's
 * ClientSurfaceScanner proved about that button (payload class, constructor, every argument
 * as a constant, an enum constant or a member of the open menu), and handed to the handler
 * the mod registered for it, with the session player as the sender. Nothing is recorded.
 */
final class LivePayloads {

    private LivePayloads() {
    }

    /**
     * One proven argument, as the protocol carries it: {@code bool:true}, {@code int:3},
     * {@code long:..}, {@code double:..}, {@code string:..}, {@code uuid:..},
     * {@code static:owner/Name.FIELD}, {@code menu-field:owner/Menu.name},
     * {@code menu-method:owner/Menu.name}.
     */
    static Object argument(String spec, SessionServerPlayer player, AbstractContainerMenu menu, ClassLoader loader)
            throws Exception {
        switch (spec) {
            case "player" -> {
                return player;
            }
            case "player-id" -> {
                return player.getId();
            }
            case "player-uuid" -> {
                return player.getUUID();
            }
            default -> {
            }
        }
        int colon = spec.indexOf(':');
        String kind = spec.substring(0, colon);
        String value = spec.substring(colon + 1);
        return switch (kind) {
            case "bool" -> Boolean.parseBoolean(value);
            case "int" -> Integer.parseInt(value);
            case "long" -> Long.parseLong(value);
            case "double" -> Double.parseDouble(value);
            case "float" -> (float) Double.parseDouble(value);
            case "string" -> value;
            case "uuid" -> java.util.UUID.fromString(value);
            case "static" -> {
                int dot = value.lastIndexOf('.');
                Field f = Class.forName(value.substring(0, dot).replace('/', '.'), true, loader)
                        .getDeclaredField(value.substring(dot + 1));
                f.setAccessible(true);
                yield f.get(null);
            }
            case "menu-field", "menu-method" -> {
                if (menu == null) {
                    throw new IllegalStateException("no open menu for " + spec);
                }
                int dot = value.lastIndexOf('.');
                Class<?> owner = Class.forName(value.substring(0, dot).replace('/', '.'), true, loader);
                String name = value.substring(dot + 1);
                if (!owner.isInstance(menu)) {
                    throw new IllegalStateException("open menu " + menu.getClass().getName() + " is not " + owner.getName());
                }
                if (kind.equals("menu-field")) {
                    Field f = findField(owner, name);
                    f.setAccessible(true);
                    yield f.get(menu);
                }
                Method m = findMethod(owner, name);
                m.setAccessible(true);
                yield m.invoke(menu);
            }
            default -> throw new IllegalArgumentException("unknown argument kind " + kind);
        };
    }

    /** Builds the payload with its proven constructor and lets the mod's handler take it. */
    static String press(SessionServerPlayer player, String payloadClass, String constructorDesc, List<String> args)
            throws Exception {
        AbstractContainerMenu menu = player.opened();
        ClassLoader loader = menu != null ? menu.getClass().getClassLoader() : LivePayloads.class.getClassLoader();
        Class<?> type = Class.forName(payloadClass.replace('/', '.'), true, loader);
        Class<?>[] params = MethodType.fromMethodDescriptorString(constructorDesc, type.getClassLoader()).parameterArray();
        Constructor<?> ctor = type.getDeclaredConstructor(params);
        ctor.setAccessible(true);
        Object[] values = new Object[args.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = argument(args.get(i), player, menu, type.getClassLoader());
        }
        Object payload = ctor.newInstance(values);
        if (!(payload instanceof CustomPacketPayload custom)) {
            return "not a payload: " + type.getName();
        }
        return dispatch(player, custom);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static String dispatch(SessionServerPlayer player, CustomPacketPayload payload) throws Exception {
        NetworkRegistry.setup();
        Field f = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
        f.setAccessible(true);
        Map<?, Map<ResourceLocation, PayloadRegistration<?>>> all = (Map) f.get(null);
        ResourceLocation id = payload.type().id();
        PayloadRegistration registration = null;
        for (Map<ResourceLocation, PayloadRegistration<?>> byId : all.values()) {
            PayloadRegistration<?> r = byId.get(id);
            if (r != null && r.matchesFlow(PacketFlow.SERVERBOUND)) {
                registration = r;
                break;
            }
        }
        if (registration == null) {
            return "no serverbound handler for " + id;
        }
        registration.handler().handle(payload, new Context(player));
        return "handled " + id;
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // up the hierarchy
            }
        }
        throw new NoSuchFieldException(c.getName() + "." + name);
    }

    private static Method findMethod(Class<?> c, String name) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    return m;
                }
            }
        }
        throw new NoSuchMethodException(c.getName() + "." + name + "()");
    }

    /** The handler's view of the sender: the session player, its connection, work run now. */
    private record Context(SessionServerPlayer player) implements IPayloadContext {
        @Override
        public net.neoforged.neoforge.common.extensions.ICommonPacketListener listener() {
            return player.connection;
        }

        @Override
        public CompletableFuture<Void> enqueueWork(Runnable task) {
            task.run();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> CompletableFuture<T> enqueueWork(Supplier<T> task) {
            return CompletableFuture.completedFuture(task.get());
        }

        @Override
        public PacketFlow flow() {
            return PacketFlow.SERVERBOUND;
        }

        @Override
        public void handle(CustomPacketPayload payload) {
            // A reply to the client: kept with the rest of what the mod sent.
            player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(payload));
        }

        @Override
        public void finishCurrentTask(net.minecraft.server.network.ConfigurationTask.Type task) {
        }
    }

    /** For the self-test: every widget payload of every screen, as a spec list per argument. */
    static List<String> specs(String... raw) {
        return new ArrayList<>(List.of(raw));
    }
}
