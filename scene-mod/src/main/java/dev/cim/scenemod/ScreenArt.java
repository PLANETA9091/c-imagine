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
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
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
    private static final long SETTLE_MILLIS = 1800;

    /** Read by {@code ScreenBackgroundMixin}: true while the capture pass draws. */
    public static boolean capturing;

    private static final Set<ResourceLocation> DONE = new HashSet<>();
    private static Object current;
    private static int frames;
    private static long openedAt;

    private ScreenArt() {
    }

    public static void init() {
        if (System.getProperty("cim.screenart") != null) {
            NeoForge.EVENT_BUS.addListener(ScreenArt::onRender);
            NeoForge.EVENT_BUS.addListener(ScreenArt::onTick);
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
            openedAt = System.currentTimeMillis();
        }
        // Mods animate a screen as it opens, so wait for that in time as well as frames.
        if (++frames < SETTLE_FRAMES || System.currentTimeMillis() - openedAt < SETTLE_MILLIS) {
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
                event.getGuiGraphics().flush();
                capture(screen, id);
            } catch (Throwable t) {
                LOGGER.warn("[cimscene] screen art failed for {}: {}", id, t.toString());
            }
        }
    }

    // --- the crawl: some menus only open from a button in another menu

    private static final int CRAWL_STEP_TICKS = 6;
    private static final int CRAWL_WAIT_TICKS = 70;
    private static final int CRAWL_MAX_WIDGETS = 18;

    /** Pressing through one open screen. */
    private static final class Crawl {
        final Screen parent;
        final List<int[]> targets = new ArrayList<>();
        int next;
        int wait;

        Crawl(AbstractContainerScreen<?> screen, int start) {
            parent = screen;
            next = start;
            for (GuiEventListener child : screen.children()) {
                if (child instanceof AbstractWidget w && w.visible && w.active && !(w instanceof EditBox)
                        && targets.size() < CRAWL_MAX_WIDGETS) {
                    targets.add(new int[]{w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2});
                }
            }
        }
    }

    private static Crawl crawl;
    private static int crawlTicks;

    private static void finishCrawl(int next) {
        crawl = null;
        try {
            Files.writeString(Path.of(System.getProperty("cim.screenart"), "crawl-done"), String.valueOf(next));
        } catch (IOException e) {
            LOGGER.warn("[cimscene] crawl marker failed: {}", e.toString());
        }
    }

    private static boolean newMenuUp(Minecraft mc) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            return false;
        }
        try {
            return !DONE.contains(BuiltInRegistries.MENU.getKey(screen.getMenu().getType()));
        } catch (UnsupportedOperationException noType) {
            return false;
        }
    }

    private static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (crawl == null) {
            Path go = Path.of(System.getProperty("cim.screenart"), "crawl-go");
            if (Files.exists(go) && mc.screen instanceof AbstractContainerScreen<?> screen) {
                try {
                    int start = Integer.parseInt(Files.readString(go).trim());
                    Files.delete(go);
                    crawl = new Crawl(screen, start);
                    crawlTicks = 0;
                } catch (IOException | NumberFormatException e) {
                    LOGGER.warn("[cimscene] crawl start failed: {}", e.toString());
                }
            }
            return;
        }
        if (++crawlTicks % CRAWL_STEP_TICKS != 0) {
            return;
        }
        if (mc.screen != crawl.parent) {
            if (newMenuUp(mc) && crawl.wait++ < CRAWL_WAIT_TICKS / CRAWL_STEP_TICKS) {
                return;   // onRender is capturing it
            }
            if (mc.screen instanceof AbstractContainerScreen<?> || mc.screen == null) {
                finishCrawl(crawl.next);   // the server has moved on, so the parent must be reopened
            } else {
                mc.setScreen(crawl.parent);   // a client-only page: step back out
            }
            return;
        }
        if (crawl.next >= crawl.targets.size()) {
            finishCrawl(-1);
            return;
        }
        int[] at = crawl.targets.get(crawl.next++);
        crawl.wait = 0;
        try {
            crawl.parent.mouseClicked(at[0], at[1], 0);
            crawl.parent.mouseReleased(at[0], at[1], 0);
        } catch (Throwable t) {
            LOGGER.info("[cimscene] crawl press threw {}", t.toString());
        }
    }

    private static void capture(AbstractContainerScreen<?> screen, ResourceLocation id) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        // The frame as a player sees it, for a menu no server command can reopen.
        Path frameDir = Path.of(System.getProperty("cim.screenart"));
        Files.createDirectories(frameDir);
        try (NativeImage frame = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            frame.writeToFile(frameDir.resolve(id.getNamespace() + "_" + id.getPath().replace('/', '_') + ".frame"));
        }
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
        save(target, w, h, dir.resolve(name + ".png"));
        try {
            captureIcons(mc, target, lifted, dir.resolve("icons"));
        } catch (Throwable t) {
            LOGGER.warn("[cimscene] icon capture failed: {}", t.toString());
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

    private static void save(TextureTarget target, int w, int h, Path file) throws IOException {
        try (NativeImage image = new NativeImage(w, h, false)) {
            RenderSystem.bindTexture(target.getColorTextureId());
            image.downloadTexture(0, false);
            image.flipY();
            image.writeToFile(file);
        }
    }

    /**
     * The real pixels of each carried item. A texture file is only one layer of what a
     * model draws: tints, overlays and 3D blocks exist only once it is rendered. Icons
     * land in the top left corner; the file name carries the GUI scale.
     */
    private static void captureIcons(Minecraft mc, TextureTarget target, List<ItemStack> stacks, Path dir)
            throws IOException {
        Files.createDirectories(dir);
        int scale = (int) mc.getWindow().getGuiScale();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            ResourceLocation item = BuiltInRegistries.ITEM.getKey(stack.getItem());
            Path file = dir.resolve(item.getNamespace() + "__" + item.getPath() + "_s" + scale + ".png");
            if (Files.exists(file)) {
                continue;
            }
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            ItemStack one = stack.copyWithCount(1);
            graphics.renderItem(one, 0, 0);
            graphics.renderItemDecorations(mc.font, one, 0, 0);
            graphics.flush();
            save(target, mc.getWindow().getWidth(), mc.getWindow().getHeight(), file);
        }
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
