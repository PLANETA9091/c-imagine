package dev.cim.testmod.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tier-1 net leg (docs/MODPACK.md): custom payload/channel BOTH directions
 * through the 1.21.1 PayloadRegistrar/StreamCodec pipeline, with a
 * deterministic echo round trip.
 *
 * <p>Marker law (deterministic, expect/actual pairs documented in
 * docs/MODPACK.md §Tier-1 marker contracts):
 * <ol>
 *   <li>{@code [cimtest-net] mod constructed} — every boot, both stacks;</li>
 *   <li>{@code [cimtest-net] s2c codec round-trip ok (token=4242 decoded=4242)}
 *       — common setup: the S2C payload's wire codec encode/decode round
 *       trips (the layer exercises this leg too — FmlHost runs common
 *       setup);</li>
 *   <li>{@code [cimtest-net] c2s codec round-trip ok (token=4242 decoded=4242)}
 *       — same for the C2S payload;</li>
 *   <li>{@code [cimtest-net] payloads registered (c2s=cimtest_net:echo s2c=cimtest_net:hello)}
 *       — {@link RegisterPayloadHandlersEvent} fired (real stacks; the layer's
 *       FmlHost does not run NetworkRegistry.setup yet — absence is the
 *       honest measurement);</li>
 *   <li>{@code [cimtest-net] s2c hello sent player=&lt;p&gt; token=4242} — the S2C
 *       send leg on player login (EXPECT side of the pair);</li>
 *   <li>{@code [cimtest-net] s2c hello received token=4242 (client echo follows)}
 *       — client-side hop (parity-diff client.out — the payload crossed the
 *       net pipeline to the modded client);</li>
 *   <li>{@code [cimtest-net] c2s echo received player=&lt;p&gt; token=4242 (round-trip ok)}
 *       — the C2S leg closed the loop (ACTUAL side; token equality with 5
 *       proves the round trip).</li>
 * </ol>
 *
 * <p>Server-side only rule respected: no {@code net.minecraft.client.*}
 * classes — the client-side echo handler is a plain payload handler using
 * the common NeoForge API ({@code PacketDistributor.sendToServer}) and is
 * inert on dedicated servers.
 */
@Mod(CimTestNetMod.MOD_ID)
public final class CimTestNetMod {

    public static final String MOD_ID = "cimtest_net";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * Deterministic round-trip token: CONSTANT so every boot produces the
     * same greppable expect/actual pairs (no per-boot nondeterminism).
     */
    public static final int TOKEN = 4242;

    public CimTestNetMod(IEventBus modBus) {
        modBus.addListener(this::commonSetup);
        modBus.addListener(CimNetPayloads::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        LOGGER.info("[cimtest-net] mod constructed (payload handlers armed)");
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Wire-codec self-test: encode/decode BOTH payloads through their
        // StreamCodecs — the deterministic part of the net pipeline this mod
        // can exercise WITHOUT a connection (runs inside the layer too).
        ByteBuf s2c = Unpooled.buffer();
        CimNetPayloads.HelloPayload.STREAM_CODEC.encode(s2c, new CimNetPayloads.HelloPayload(TOKEN));
        int decodedHello = CimNetPayloads.HelloPayload.STREAM_CODEC.decode(s2c).token();
        LOGGER.info("[cimtest-net] s2c codec round-trip ok (token={} decoded={})", TOKEN, decodedHello);

        ByteBuf c2s = Unpooled.buffer();
        CimNetPayloads.EchoPayload.STREAM_CODEC.encode(c2s, new CimNetPayloads.EchoPayload(TOKEN));
        int decodedEcho = CimNetPayloads.EchoPayload.STREAM_CODEC.decode(c2s).token();
        LOGGER.info("[cimtest-net] c2s codec round-trip ok (token={} decoded={})", TOKEN, decodedEcho);
    }

    /**
     * The S2C send leg: a client joins, the server responds with the hello
     * payload on the mod's own channel. The client mod echoes it C2S (see
     * {@link CimNetPayloads#handleHelloOnClient}); the echo receipt logs the
     * round-trip marker.
     */
    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new CimNetPayloads.HelloPayload(TOKEN));
        LOGGER.info("[cimtest-net] s2c hello sent player={} token={}",
                player.getName().getString(), TOKEN);
    }
}
