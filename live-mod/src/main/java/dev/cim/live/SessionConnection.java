package dev.cim.live;

import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * The session player's network side. A mod's menu code talks to its player through
 * {@code player.connection.send(...)}: a returned stack, a slot update, its own payload.
 * Nothing here goes on a wire; each packet is kept, so the session can hand Paper what
 * the mod's code said to the client. Allocated like the player, with no server behind it.
 */
public final class SessionConnection extends ServerGamePacketListenerImpl {

    private List<Packet<?>> sent;

    private SessionConnection() {
        super(null, null, null, null);
        throw new AssertionError();
    }

    static SessionConnection create(SessionServerPlayer player) {
        SessionConnection self = SessionServerPlayer.allocate(SessionConnection.class);
        self.player = player;
        self.sent = new ArrayList<>();
        return self;
    }

    @Override
    public void send(Packet<?> packet) {
        sent.add(packet);
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener) {
        sent.add(packet);
    }

    /** What the mod's code sent since the last call, oldest first. */
    public List<Packet<?>> drain() {
        List<Packet<?>> out = sent;
        sent = new ArrayList<>();
        return out;
    }
}
