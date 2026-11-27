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
 * <p>The sender is a {@link SessionServerPlayer} facade: the effects land on the facade, not on
 * the Paper player the key came from. Until the real ServerPlayer can be passed in, the reply
 * always says {@code facade=true} and the Paper side keeps its own effects.
 */
final class RollDelivery {

    private RollDelivery() {
    }

    /** {@code vx, vy, vz}: the velocity the client's roll would send (the impulse). */
    static String deliver(SessionServerPlayer player, double vx, double vy, double vz) throws Exception {
        ClassLoader loader = RollDelivery.class.getClassLoader();
        Class<?> publish = LivePayloads.load("net/combat_roll/network/Packets$RollPublish", loader);
        Class<?> visuals = LivePayloads.load("net/combat_roll/client/RollEffect$Visuals", loader);
        Class<?> particles = LivePayloads.load("net/combat_roll/client/RollEffect$Particles", loader);

        Field puff = particles.getDeclaredField("PUFF");
        puff.setAccessible(true);
        Constructor<?> visualsCtor = visuals.getDeclaredConstructor(String.class, particles);
        visualsCtor.setAccessible(true);
        Object look = visualsCtor.newInstance("roll", puff.get(null));

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
        return "handled\troll\tfacade=true";
    }
}
