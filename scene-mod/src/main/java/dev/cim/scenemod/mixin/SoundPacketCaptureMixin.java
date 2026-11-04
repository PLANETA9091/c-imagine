package dev.cim.scenemod.mixin;

import dev.cim.scenemod.CimSceneMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * THE SOUND-CAPTURE PROBE (the SOUND gallery class, 2026-11-04 — DIAGNOSTIC
 * first): while a capture window is armed ({@link CimSceneMod.SoundCapture}),
 * record the resource location of EVERY outgoing clientbound SOUND packet.
 * The truth leg's sound gallery plays each registered SoundEvent in a
 * deterministic order and writes the ordered event-id stream next to the
 * page manifest; sound-diff.py joins that stream against the ours leg's
 * replay log — the census-style channel proof for the SOUND surface.
 *
 * <p>Injection point: BOTH public {@code send} overloads of
 * {@code Connection} — the vanilla server broadcast path
 * (ServerPlayer.connection.send -> ServerCommonPacketListenerImpl.send)
 * reaches Connection through the two-arg overload, so the single-arg hook
 * alone would undercount. {@code require = 0} ON PURPOSE: a descriptor that
 * does not match this exact 1.21.1 mojmap build must degrade the capture to
 * zero rows (which the diff NAMES as missing rows), never crash the boot of
 * a parity harness — the class is DIAGNOSTIC until the channel is measured
 * stable, and the first CI run measures whether the hook sees the
 * broadcast traffic (an undercount is visible in the join, not silent).
 */
@Mixin(Connection.class)
public abstract class SoundPacketCaptureMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), require = 0)
    private void cimscene$captureSound(Packet<?> packet, CallbackInfo ci) {
        cimscene$record(packet);
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;"
            + "Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"), require = 0)
    private void cimscene$captureSoundWithListener(Packet<?> packet,
                                                   CallbackInfo ci) {
        cimscene$record(packet);
    }

    /** The packet's sound id, or nothing when the capture is disarmed. */
    private static void cimscene$record(Packet<?> packet) {
        ResourceLocation id = null;
        if (packet instanceof ClientboundSoundPacket p) {
            id = BuiltInRegistries.SOUND_EVENT.getKey(p.getSound().value());
        } else if (packet instanceof ClientboundSoundEntityPacket p) {
            id = BuiltInRegistries.SOUND_EVENT.getKey(p.getSound().value());
        }
        if (id != null) {
            CimSceneMod.SoundCapture.record(id.toString());
        }
    }
}
