package dev.cim.scenemod;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures the real pixels of every container screen, on the real modded client.
 *
 * <p>Some mods declare their art in bytecode, others in JSON or by drawing code, so
 * the one source that covers all of them is the mod's own render method. Each open
 * screen is drawn once more into a transparent target with its background switched
 * off and the player's items lifted out, which leaves only what the mod draws.
 *
 * <p>Enabled by {@code -Dcim.screenart=<dir>}. Writes {@code <ns>_<path>.png} and a
 * {@code .tsv} with the screen's bounds, in framebuffer pixels from the window centre.
 */
public final class ScreenArt {

    private static final Logger LOGGER = LoggerFactory.getLogger("cimscene");
    /** Software rendering in CI manages a few frames a second, so this has to stay small. */
    private static final int SETTLE_FRAMES = 6;

    /** Read by {@code ScreenBackgroundMixin}: true while the capture pass draws. */
    public static boolean capturing;

    private static final Set<ResourceLocation> DONE = new HashSet<>();
    private static Object current;
    private static int frames;

    private ScreenArt() {
    }

    public static void init() {
        if (System.getProperty("cim.screenart") != null) {
            NeoForge.EVENT_BUS.addListener(ScreenArt::onRender);
            LOGGER.info("[cimscene] screen art capture on: {}", System.getProperty("cim.screenart"));
        }
    }

    private static void onRender(ScreenEvent.Render.Post event) {
        if (capturing || !(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (current != screen) {
            current = screen;
            frames = 0;
        }
        if (++frames != SETTLE_FRAMES) {
            return;
        }
        ResourceLocation id;
        try {
            id = BuiltInRegistries.MENU.getKey(screen.getMenu().getType());
        } catch (UnsupportedOperationException noType) {
            return;
        }
        if (id != null && DONE.add(id)) {
            try {
                capture(screen, id);
            } catch (Throwable t) {
                LOGGER.warn("[cimscene] screen art failed for {}: {}", id, t.toString());
            }
        }
    }

    private static void capture(AbstractContainerScreen<?> screen, ResourceLocation id) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        TextureTarget target = new TextureTarget(w, h, true, Minecraft.ON_OSX);
        target.setClearColor(0f, 0f, 0f, 0f);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        List<ItemStack> lifted = lift(mc);
        capturing = true;
        try {
            GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            screen.render(graphics, -10000, -10000, 0f);
            graphics.flush();
        } finally {
            capturing = false;
            restore(mc, lifted);
        }
        Path dir = Path.of(System.getProperty("cim.screenart"));
        Files.createDirectories(dir);
        String name = id.getNamespace() + "_" + id.getPath().replace('/', '_');
        try (NativeImage image = new NativeImage(w, h, false)) {
            RenderSystem.bindTexture(target.getColorTextureId());
            image.downloadTexture(0, false);
            image.flipY();
            image.writeToFile(dir.resolve(name + ".png"));
        }
        mc.getMainRenderTarget().bindWrite(true);
        target.destroyBuffers();
        double scale = mc.getWindow().getGuiScale();
        StringBuilder tsv = new StringBuilder();
        tsv.append("menu\t").append(id).append('\t').append(screen.getMenu().getClass().getName()).append('\n');
        tsv.append("frame\t").append(w).append('\t').append(h).append('\t').append((int) scale).append('\n');
        tsv.append("bounds\t").append(screen.getGuiLeft()).append('\t').append(screen.getGuiTop()).append('\t')
                .append(screen.getXSize()).append('\t').append(screen.getYSize()).append('\n');
        for (Slot slot : screen.getMenu().slots) {
            // A disabled slot exists in the menu but not on the screen.
            if (!slot.isActive()) {
                continue;
            }
            tsv.append("slot\t").append(slot.x).append('\t').append(slot.y).append('\t')
                    .append(slot.container == mc.player.getInventory() ? "player" : "menu").append('\t')
                    .append(slot.getContainerSlot()).append('\n');
        }
        Files.writeString(dir.resolve(name + ".tsv"), tsv.toString(), StandardCharsets.UTF_8);
        LOGGER.info("[cimscene] screen art captured {}", id);
    }

    /** Empties the player's inventory for the pass, so slots draw bare. */
    private static List<ItemStack> lift(Minecraft mc) {
        List<ItemStack> items = new ArrayList<>(mc.player.getInventory().items);
        for (int i = 0; i < mc.player.getInventory().items.size(); i++) {
            mc.player.getInventory().items.set(i, ItemStack.EMPTY);
        }
        return items;
    }

    private static void restore(Minecraft mc, List<ItemStack> items) {
        for (int i = 0; i < items.size(); i++) {
            mc.player.getInventory().items.set(i, items.get(i));
        }
    }
}
