package dev.cim.testmod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * First-party test mod for c-imagine.
 *
 * Exercises, with deterministic log markers the CI matrix greps for:
 * - registration: block, item, block entity, entity type
 * - lifecycle: mod construction, common setup, server start, player login
 *
 * This mod must behave identically on a real NeoForge 1.21.1 server (ground
 * truth) and inside the c-imagine layer — that parity is the acceptance
 * criterion of M2.
 */
@Mod(CimTestMod.MOD_ID)
public final class CimTestMod {

    public static final String MOD_ID = "cimtest";
    public static final Logger LOGGER = LoggerFactory.getLogger("cimtest");

    public CimTestMod(IEventBus modBus) {
        CimBlocks.BLOCKS.register(modBus);
        CimItems.ITEMS.register(modBus);
        CimBlockEntities.BLOCK_ENTITIES.register(modBus);
        CimEntities.ENTITY_TYPES.register(modBus);
        modBus.addListener(this::commonSetup);
        LOGGER.info("[cimtest] mod constructed (registration queues armed)");
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("[cimtest] common setup ok (block={}, item={}, be={}, entity={})",
                CimBlocks.TEST_BLOCK.getId(),
                CimItems.TEST_BLOCK_ITEM.getId(),
                CimBlockEntities.TEST_BLOCK_ENTITY.getId(),
                CimEntities.TEST_ENTITY.getId());
    }
}
