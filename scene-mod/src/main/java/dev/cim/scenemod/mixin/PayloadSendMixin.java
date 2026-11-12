package dev.cim.scenemod.mixin;

import dev.cim.scenemod.ScreenArt;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What a button press sent to the server: the payload the mod's own client code built. The
 * crawl records it with the press, so the c-imagine side presses the same button by what it
 * does, not by where it sits.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public class PayloadSendMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void cimscene$sent(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ServerboundCustomPayloadPacket custom) {
            ScreenArt.payloadSent(custom.payload());
        }
    }
}
