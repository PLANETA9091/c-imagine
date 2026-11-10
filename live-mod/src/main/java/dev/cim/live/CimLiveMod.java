package dev.cim.live;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/** Entry point; the sessions themselves are driven from Paper through {@link LiveMenus}. */
@Mod("cimlive")
public final class CimLiveMod {

    public CimLiveMod(IEventBus modBus) {
        modBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Paper drives sessions through this from now on; registries are complete here.
        LiveProtocol.publish();
        String item = System.getenv("CIM_LIVE_SELFTEST");
        if (item != null && !item.isBlank()) {
            // Registries are complete here on both stacks, and the menu types with them.
            LiveMenus.selfTest(item.trim());
        }
    }
}
