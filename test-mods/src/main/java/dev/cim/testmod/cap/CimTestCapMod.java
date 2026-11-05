package dev.cim.testmod.cap;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tier-1 cap leg (docs/MODPACK.md): capabilities/data components registered
 * and round-tripped server-side, through the layer on the legs the layer's
 * FML lifecycle actually runs.
 *
 * <p>Measured NeoForge 21.1 lifecycle facts this design encodes (sources of
 * neoforge-21.1.265): {@code FMLCommonSetupEvent} runs BEFORE the
 * registration-events stage (RegisterCapabilitiesEvent), which runs before
 * {@code FMLLoadCompleteEvent}; {@code AttachCapabilitiesEvent} no longer
 * exists in 1.21 — item capabilities attach via
 * {@link RegisterCapabilitiesEvent#registerItem}. The c-imagine layer's
 * FmlHost runs construction → registration → common setup, so:
 * <ul>
 *   <li>the DataComponent legs run EVERYWHERE (registration happened in the
 *       registry phase, common setup in the layer);</li>
 *   <li>the capability legs run on real stacks only (their absence in the
 *       layer log is the honest measurement — docs/MODPACK.md).</li>
 * </ul>
 *
 * <p>Marker law (deterministic, expect/actual pairs documented in
 * docs/MODPACK.md §Tier-1 marker contracts):
 * <ol>
 *   <li>{@code [cimtest-cap] mod constructed} — every boot, both stacks;</li>
 *   <li>{@code [cimtest-cap] data component registered (cimtest-cap:charge)}
 *       — the component type resolved from the registry (common setup);</li>
 *   <li>{@code [cimtest-cap] data component round-trip ok (item=cimtest:test_block set=7 got=7)}
 *       — server-side component write/read round trip (common setup);</li>
 *   <li>{@code [cimtest-cap] component codec round-trip ok (charge=7)} — the
 *       persistent codec encode/decode round trip (common setup);</li>
 *   <li>{@code [cimtest-cap] capabilities registered (item=cimtest:test_block cap=energy)}
 *       — RegisterCapabilitiesEvent (real stacks);</li>
 *   <li>{@code [cimtest-cap] capability round-trip ok (cap=energy item=cimtest:test_block set=42 got=42)}
 *       — capability resolved on the stack + write→read round trip
 *       (FMLLoadCompleteEvent, real stacks; a FAILED variant logs honestly
 *       if the capability does not resolve).</li>
 * </ol>
 */
@Mod(CimTestCapMod.MOD_ID)
public final class CimTestCapMod {

    public static final String MOD_ID = "cimtest-cap";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Deterministic values for the marker pairs (no per-boot nondeterminism). */
    static final int COMPONENT_VALUE = 7;
    static final int CAPABILITY_VALUE = 42;
    private static final int CAPACITY = 1000;

    public CimTestCapMod(IEventBus modBus) {
        CimCapComponents.DATA_COMPONENTS.register(modBus);
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::onRegisterCapabilities);
        modBus.addListener(this::onLoadComplete);
        LOGGER.info("[cimtest-cap] mod constructed (component/capability queues armed)");
    }

    /**
     * Common setup — runs in the c-imagine layer too (FmlHost): the component
     * registration proof + BOTH server-side component round trips.
     */
    private void commonSetup(FMLCommonSetupEvent event) {
        // 1) registration proof: the component type resolves from the registry
        ResourceLocation id =
                BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(CimCapComponents.CHARGE.get());
        LOGGER.info("[cimtest-cap] data component registered ({})", id);

        // 2) server-side write/read round trip through the component map
        ItemStack stack = new ItemStack(dev.cim.testmod.CimItems.TEST_BLOCK_ITEM.get());
        stack.set(CimCapComponents.CHARGE.get(), COMPONENT_VALUE);
        int got = stack.getOrDefault(CimCapComponents.CHARGE.get(), -1);
        LOGGER.info("[cimtest-cap] data component round-trip ok (item=cimtest:test_block set={} got={})",
                COMPONENT_VALUE, got);

        // 3) persistent codec round trip — the serialization the bridge
        //    marshalling rides (docs/ARCHITECTURE.md §4.4)
        DataComponentType<Integer> type = CimCapComponents.CHARGE.get();
        Tag encoded = type.codec().encodeStart(NbtOps.INSTANCE, COMPONENT_VALUE).getOrThrow();
        int decoded = type.codec().parse(NbtOps.INSTANCE, encoded).getOrThrow();
        LOGGER.info("[cimtest-cap] component codec round-trip ok (charge={})", decoded);
    }

    /**
     * Real-stacks-only: the item capability registration (21.1 API —
     * AttachCapabilitiesEvent is gone). The registration applies AFTER this
     * handler returns, so the round trip lives in the load-complete leg.
     */
    private void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerItem(Capabilities.EnergyStorage.ITEM,
                (stack, side) -> new EnergyStorage(CAPACITY),
                dev.cim.testmod.CimItems.TEST_BLOCK_ITEM.get());
        LOGGER.info("[cimtest-cap] capabilities registered (item=cimtest:test_block cap=energy)");
    }

    /**
     * Real-stacks-only (fires after the registration-events stage): resolve
     * the capability off a fresh stack and round-trip a write/read through it.
     */
    private void onLoadComplete(FMLLoadCompleteEvent event) {
        ItemStack stack = new ItemStack(dev.cim.testmod.CimItems.TEST_BLOCK_ITEM.get());
        IEnergyStorage energy = stack.getCapability(Capabilities.EnergyStorage.ITEM);
        if (energy == null) {
            LOGGER.error("[cimtest-cap] capability round-trip FAILED (cap=energy item=cimtest:test_block not resolved)");
            return;
        }
        int set = energy.receiveEnergy(CAPABILITY_VALUE, false);
        int got = energy.getEnergyStored();
        LOGGER.info("[cimtest-cap] capability round-trip ok (cap=energy item=cimtest:test_block set={} got={})",
                set, got);
    }
}
