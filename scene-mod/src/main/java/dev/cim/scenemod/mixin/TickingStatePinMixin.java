package dev.cim.scenemod.mixin;

import dev.cim.scenemod.SceneTexturePin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 14-eb3 THE MCMETA FRAME-INDEX PIN. The 14-di freeze stops every clock
 * (the server's BE tickers, the client's textureManager.tick) but the
 * mcmeta frame INDEX at the freeze = f(client tick history since join) —
 * the two legs' capture clients freeze at DIFFERENT frame indices, the
 * frozen frames differ by the whole phase, and every animated-sprite cell
 * measures the phase delta as a fidelity red (the 14-di named residual:
 * the sa=[0,0] RED rows whose cross-leg delta is the frozen phase). The
 * pin: the moment the client learns the server froze (the same
 * ClientboundTickingStatePacket the 14-di verification rode), zero every
 * animated sprite's frame counters and re-upload frame 0 — both legs land
 * the SAME frame, the frozen phases collapse to a measured constant, and
 * the animated classes compare frame-0-vs-frame-0 (the pack's 14-dx bake
 * ships frame 0 exactly).
 */
@Mixin(ClientPacketListener.class)
public class TickingStatePinMixin {
    @Inject(method = "handleTickingState", at = @At("TAIL"))
    private void cimscene$pinFramesOnFreeze(ClientboundTickingStatePacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getTextureManager() == null) {
            return;
        }
        // the packet carries the frozen flag itself — the connection's
        // tickRateManager() accessor is NeoForge-patched and absent from the
        // offline mojmap layer (the CI gradle build stays the reference)
        if (packet.isFrozen()) {
            SceneTexturePin.pinAllAnimatedSprites(mc);
        }
    }
}
