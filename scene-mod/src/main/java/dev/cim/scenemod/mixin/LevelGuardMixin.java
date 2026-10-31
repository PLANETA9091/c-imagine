package dev.cim.scenemod.mixin;

import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Consumer;

/**
 * THE ENTITY-TICK QUARANTINE (12-ar, measured gallery #27 run 41192176056).
 *
 * <p>A bare-spawned modded entity can pass placement (create:potato_projectile
 * spawned into its cell, the settle pass even wrote the done marker) and then
 * NPE on its first server tick ({@code PotatoProjectileEntity.tick:149} —
 * {@code this.type} is only set when the potato cannon FIRES the projectile).
 * Vanilla turns any entity-tick Throwable into a FATAL server crash: the
 * truth leg died between page 0's shot and the next page's RCON call, the
 * ours leg never ran, and the honest red never got to print.
 *
 * <p>This guard degrades that fatal into a NAMED quarantine: the subject is
 * discarded (its cell goes empty), the log names it, the settle pass reads it
 * as {@code dissolved} and the coverage gate turns that into the honest red
 * with the subject's name (12-ap chain). One bad subject can never kill the
 * server — the same contract as the crash-proof walk, one layer deeper.
 *
 * <p>Scope: the cimscene harness mod rides the TRUTH leg (and the layer's
 * census boot). A vanilla entity that genuinely crashes its tick would now
 * be quarantined instead of fatal — for a parity harness that is the right
 * trade: the run goes RED through the manifest (dissolved → unshown), not
 * through a dead server.
 */
@Mixin(net.minecraft.world.level.Level.class)
public abstract class LevelGuardMixin {

    private static final Logger CIMSCENE$LOGGER =
            LoggerFactory.getLogger("cimscene");

    @Redirect(
            method = "guardEntityTick(Ljava/util/function/Consumer;Lnet/minecraft/world/entity/Entity;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"
            )
    )
    private void cimscene$quarantineEntityTick(Consumer<Object> consumer,
                                               Object entityObj) {
        try {
            consumer.accept(entityObj);
        } catch (Throwable t) {
            if (entityObj instanceof Entity entity) {
                CIMSCENE$LOGGER.error(
                        "[cimscene] entity-tick quarantine entity={} err={}",
                        net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                .getKey(entity.getType()),
                        t.toString());
                try {
                    entity.discard();
                } catch (Throwable ignore) {
                    // the row is already named; a discard failure cannot
                    // matter more than the original throw
                }
            } else {
                // not an entity (never happens in guardEntityTick) — keep
                // vanilla's fatal semantics for anything unexpected
                throw t;
            }
        }
    }
}
