package dev.cim.live;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Combat Roll's own server handler, fed the payload its client sends. The client decides the
 * roll (CombatRollAdapter on the Paper side mirrors that decision); the mod's
 * {@code ServerNetwork.handleRollPublish} then does the server effects: invulnerability ticks,
 * food exhaustion, the PlayerStartRolling event and the RollAnimation broadcast. The payload is
 * built reflectively from the mod's own classes, so nothing about the mod is re-implemented here.
 *
 * <p>The sender is the real ServerPlayer the Paper side hands over (its NMS handle, by key), when
 * it is an instance of the ServerPlayer class this code sees (a shared-classloader check). Only
 * then does the reply say {@code real=true}, and the Paper side skips its own effects. Otherwise
 * the handler runs on the {@link SessionServerPlayer} facade and the reply says {@code facade=true}.
 */
final class RollDelivery {

    private RollDelivery() {
    }

    /** {@code vx, vy, vz}: the velocity the client's roll would send (the impulse). */
    static String deliver(SessionServerPlayer facade, Object real, double vx, double vy, double vz) throws Exception {
        boolean realPlayer = real instanceof net.minecraft.server.level.ServerPlayer;
        net.minecraft.server.level.ServerPlayer player = realPlayer
                ? (net.minecraft.server.level.ServerPlayer) real : facade;
        ClassLoader loader = RollDelivery.class.getClassLoader();
        Class<?> publish = LivePayloads.load("net/combat_roll/network/Packets$RollPublish", loader);
        Class<?> visuals = LivePayloads.load("net/combat_roll/client/RollEffect$Visuals", loader);
        Class<?> particles = LivePayloads.load("net/combat_roll/client/RollEffect$Particles", loader);

        Field puff = particles.getDeclaredField("PUFF");
        puff.setAccessible(true);
        Constructor<?> visualsCtor = visuals.getDeclaredConstructor(String.class, particles);
        visualsCtor.setAccessible(true);
        // The client's own Visuals name (MinecraftClientMixin.tryRolling), the animation id.
        Object look = visualsCtor.newInstance("combat_roll:roll", puff.get(null));

        Constructor<?> ctor = LivePayloads.constructor(publish,
                "(ILnet/combat_roll/client/RollEffect$Visuals;Lnet/minecraft/world/phys/Vec3;)V");
        // The Vec3 class is the one the mod's constructor takes, whatever relocation the layer applied.
        Class<?> vec = ctor.getParameterTypes()[2];
        Object velocity = vec.getConstructor(double.class, double.class, double.class).newInstance(vx, vy, vz);
        Object payload = ctor.newInstance(player.getId(), look, velocity);

        Class<?> network = LivePayloads.load("net/combat_roll/network/ServerNetwork", loader);
        Method handle = null;
        for (Method m : network.getDeclaredMethods()) {
            if (m.getName().equals("handleRollPublish") && m.getParameterCount() == 3) {
                handle = m;
            }
        }
        if (handle == null) {
            return "error\tno handleRollPublish in ServerNetwork";
        }
        handle.setAccessible(true);
        handle.invoke(null, payload, player.getServer(), player);
        return "handled\troll\t" + (realPlayer ? "real=true" : "facade=true");
    }
}
