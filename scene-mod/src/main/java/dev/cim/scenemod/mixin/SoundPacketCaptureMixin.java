package dev.cim.scenemod.mixin;

import dev.cim.scenemod.CimSceneMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * THE SOUND+PARTICLE-CAPTURE PROBE (the SOUND and PARTICLE-STREAM gallery
 * classes, 2026-11-04/05 — DIAGNOSTIC first): while a capture window is
 * armed ({@link CimSceneMod.SoundCapture} / {@link CimSceneMod.ParticleCapture}),
 * record the resource location of EVERY outgoing clientbound SOUND or
 * PARTICLE packet. The truth leg's sound gallery plays each registered
 * SoundEvent (the particle gallery spawns each registered ParticleType) in
 * a deterministic order and writes the ordered event-id stream next to the
 * page manifest; the diff joins that stream against the ours leg's replay
 * log — the census-style channel proof for the SOUND / PARTICLE surfaces.
 *
 * <p>Injection point: the three-arg {@code send} every overload funnels into,
 * so each packet is recorded once. Two hooks on the outer overloads would count
 * the one-arg path twice. The PARTICLE branch rides the SAME proven
 * injection point (this descriptor is the one measured live — a separate
 * mixin would re-pin a new descriptor for zero benefit and the full FATAL
 * risk of a wrong one).
 *
 * {@code require = 0} ON PURPOSE: a descriptor that
 * does not match this exact 1.21.1 mojmap build must degrade the capture to
 * zero rows (which the diff NAMES as missing rows), never crash the boot of
 * a parity harness — the classes are DIAGNOSTIC until their channels are
 * measured stable, and the first CI run measures whether the hook sees the
 * broadcast traffic (an undercount is visible in the join, not silent).
 */
@Mixin(Connection.class)
public abstract class SoundPacketCaptureMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;"
            + "Lnet/minecraft/network/PacketSendListener;Z)V",
            at = @At("HEAD"), require = 0)
    private void cimscene$captureSound(Packet<?> packet, PacketSendListener listener,
                                       boolean flush, CallbackInfo ci) {
        cimscene$record(packet);
    }

    /** The packet's event id, or nothing when both captures are disarmed. */
    private static void cimscene$record(Packet<?> packet) {
        ResourceLocation id = null;
        if (packet instanceof ClientboundSoundPacket p) {
            id = BuiltInRegistries.SOUND_EVENT.getKey(p.getSound().value());
        } else if (packet instanceof ClientboundSoundEntityPacket p) {
            id = BuiltInRegistries.SOUND_EVENT.getKey(p.getSound().value());
        } else if (packet instanceof ClientboundLevelParticlesPacket p) {
            id = BuiltInRegistries.PARTICLE_TYPE.getKey(p.getParticle().getType());
        }
        if (id != null) {
            if (packet instanceof ClientboundLevelParticlesPacket) {
                CimSceneMod.ParticleCapture.record(id.toString());
            } else {
                CimSceneMod.SoundCapture.record(id.toString());
            }
        }
    }
}
