package dev.cim.testmod.cap;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The cimtest-cap registered surfaces: a custom {@link DataComponentType}
 * ({@code cimtest_cap:charge}) — the data-components half of the Tier-1 cap
 * leg (docs/MODPACK.md).
 *
 * <p>The component's persistent codec is the serialization the state-bridge
 * marshalling rides (docs/ARCHITECTURE.md §4.4: capabilities/data components
 * are marshalled as typed frames), so its codec round trip is the
 * layer-exercisable part of the contract.
 */
public final class CimCapComponents {

    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE,
                    CimTestCapMod.MOD_ID);

    /** Integer "charge" component, settable on any stack; default written/read server-side. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> CHARGE =
            DATA_COMPONENTS.registerComponentType("charge", builder -> builder
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    private CimCapComponents() {
    }
}
