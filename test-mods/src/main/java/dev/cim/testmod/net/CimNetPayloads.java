package dev.cim.testmod.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.minecraft.server.level.ServerPlayer;

/**
 * The cimtest-net payloads (NeoForge 1.21.1 CustomPacketPayload/StreamCodec
 * API) and their registration.
 *
 * <p>Both directions on ONE channel namespace ({@code cimtest-net}):
 * <ul>
 *   <li>{@code cimtest-net:hello} — S2C, server → client on player login
 *       (handled client-side, which echoes it back);</li>
 *   <li>{@code cimtest-net:echo} — C2S, client → server (the round-trip
 *       closer, handled server-side).</li>
 * </ul>
 *
 * <p>The payload bodies are a single VAR_INT token — deterministic, so the
 * CI marker pairs are byte-stable across boots and stacks.
 */
public final class CimNetPayloads {

    private CimNetPayloads() {
    }

    /** S2C: the server's hello, sent on {@code PlayerLoggedInEvent}. */
    public record HelloPayload(int token) implements CustomPacketPayload {

        public static final Type<HelloPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CimTestNetMod.MOD_ID, "hello"));

        public static final StreamCodec<ByteBuf, HelloPayload> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, HelloPayload::token, HelloPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: the client's echo, closing the round trip server-side. */
    public record EchoPayload(int token) implements CustomPacketPayload {

        public static final Type<EchoPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CimTestNetMod.MOD_ID, "echo"));

        public static final StreamCodec<ByteBuf, EchoPayload> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, EchoPayload::token, EchoPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Registration (mod bus, {@code RegisterPayloadHandlersEvent}): both
     * directions through the 1.21.1 PayloadRegistrar. Fires on real stacks;
     * the c-imagine layer's FmlHost does not run NetworkRegistry.setup yet,
     * so the registration marker documents that gap honestly (see
     * docs/MODPACK.md).
     */
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(HelloPayload.TYPE, HelloPayload.STREAM_CODEC,
                CimNetPayloads::handleHelloOnClient);
        registrar.playToServer(EchoPayload.TYPE, EchoPayload.STREAM_CODEC,
                CimNetPayloads::handleEchoOnServer);
        CimTestNetMod.LOGGER.info(
                "[cimtest-net] payloads registered (c2s=cimtest-net:echo s2c=cimtest-net:hello)");
    }

    /**
     * Client-side hop: the hello crossed the net pipeline inbound — log the
     * token and echo it straight back C2S. Common NeoForge API only (no
     * client classes); never runs on a dedicated server.
     */
    private static void handleHelloOnClient(HelloPayload payload, IPayloadContext context) {
        CimTestNetMod.LOGGER.info("[cimtest-net] s2c hello received token={} (client echo follows)",
                payload.token());
        PacketDistributor.sendToServer(new EchoPayload(payload.token()));
    }

    /** Server-side round-trip closer: the echo came back with the same token. */
    private static void handleEchoOnServer(EchoPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            CimTestNetMod.LOGGER.info(
                    "[cimtest-net] c2s echo received player={} token={} (round-trip ok)",
                    player.getName().getString(), payload.token());
        } else {
            CimTestNetMod.LOGGER.info(
                    "[cimtest-net] c2s echo received token={} (round-trip ok)", payload.token());
        }
    }
}
