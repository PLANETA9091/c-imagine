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
            NeoForge.EVENT_BUS.addListener((ScreenEvent.Render.Pre e) -> {
                if (!capturing) {
                    DRAWN.clear();
                }
            });
            LOGGER.info("[cimscene] screen art capture on: {}", System.getProperty("cim.screenart"));
        }
    }

    private static void onRender(ScreenEvent.Render.Post event) {
        if (capturing || !(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (pendingState != null && crawl != null && screen == crawl.parent) {
            try {
                event.getGuiGraphics().flush();
                stateCapture(screen, pendingState);
            } catch (Throwable t) {
                LOGGER.warn("[cimscene] state capture failed: {}", t.toString());
            }
            pendingState = null;
            stateSaved = true;
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
    private static final int CRAWL_MAX_WIDGETS = 40;
    private static final int CRAWL_CLOSED_TICKS = 30;
    private static final int CRAWL_STATE_TIMEOUT_TICKS = 60;

    /** Pressing through one open screen. */
    private static final class Crawl {
        final Screen parent;
        final String name;
        final int left;
        final int top;
        final List<int[]> targets = new ArrayList<>();
        int next;
        int wait;
        /** Steps this press has shown no screen, waiting for a sub-menu the server opens. */
        int closed;
        /** The press waiting for its result to be captured, or -1. */
        int pressed = -1;
        int settleAt;
        boolean moveDone;

        void collect(List<? extends GuiEventListener> children, int sw, int sh, int depth) {
            collectInto(targets, children, sw, sh, depth);
        }

        Crawl(AbstractContainerScreen<?> screen, int start) {
            parent = screen;
            next = start;
            moveDone = start > 0;
            left = screen.getGuiLeft();
            top = screen.getGuiTop();
            String id;
            try {
                ResourceLocation key = BuiltInRegistries.MENU.getKey(screen.getMenu().getType());
                id = key.getNamespace() + "_" + key.getPath().replace('/', '_');
            } catch (UnsupportedOperationException noType) {
                id = "unknown";
            }
            name = id;
            collect(screen.children(), screen.width, screen.height, 0);
            if (start == 0) {
                StringBuilder at = new StringBuilder();
                for (int[] t : targets) {
                    at.append(' ').append(t[2] - left).append(',').append(t[3] - top).append(' ').append(t[4]).append('x').append(t[5]);
                }
                LOGGER.info("[cimscene] crawl {} targets {}:{}", name, targets.size(), at);
            }
        }
    }

    /**
     * Every pressable control on the screen: vanilla widgets, and a mod's own controls that
     * are not AbstractWidgets but have bounds (Sophisticated Backpacks' settings tab), also
     * inside a container element. A control as large as half the screen is a panel, not a
     * button.
     */
    private static void collectInto(List<int[]> targets, List<? extends GuiEventListener> children, int sw, int sh,
                                    int depth) {
        for (GuiEventListener child : children) {
            if (targets.size() >= CRAWL_MAX_WIDGETS) {
                return;
            }
            if (child instanceof AbstractWidget w) {
                if (w.visible && w.active && !(w instanceof EditBox)) {
                    targets.add(new int[]{w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2,
                            w.getX(), w.getY(), w.getWidth(), w.getHeight()});
                }
                continue;
            }
            boolean container = child instanceof net.minecraft.client.gui.components.events.ContainerEventHandler inner
                    && !inner.children().isEmpty();
            net.minecraft.client.gui.navigation.ScreenRectangle r = child.getRectangle();
            if (r.width() <= 0 || r.height() <= 0) {
                // A mod's own control that keeps its bounds outside the vanilla rectangle
                // (Sophisticated Core's tabs: getX/getY/getWidth/getHeight): a container's
                // own centre is one of its children, pressed on its own.
                r = container ? r : boundsOf(child);
            }
            if (r.width() > 0 && r.height() > 0 && r.width() < sw / 2 && r.height() < sh / 2) {
                targets.add(new int[]{r.left() + r.width() / 2, r.top() + r.height() / 2,
                        r.left(), r.top(), r.width(), r.height()});
            }
            if (depth < 3 && container) {
                collectInto(targets,
                        ((net.minecraft.client.gui.components.events.ContainerEventHandler) child).children(),
                        sw, sh, depth + 1);
            }
        }
    }

    /** The bounds a control answers through public getX/getY/getWidth/getHeight, or empty. */
    private static net.minecraft.client.gui.navigation.ScreenRectangle boundsOf(GuiEventListener child) {
        try {
            Class<?> c = child.getClass();
            int x = (int) c.getMethod("getX").invoke(child);
            int y = (int) c.getMethod("getY").invoke(child);
            int w = (int) c.getMethod("getWidth").invoke(child);
            int h = (int) c.getMethod("getHeight").invoke(child);
            return new net.minecraft.client.gui.navigation.ScreenRectangle(x, y, w, h);
        } catch (ReflectiveOperationException | ClassCastException | IllegalArgumentException e) {
            return net.minecraft.client.gui.navigation.ScreenRectangle.empty();
        }
    }

    /** Set by the crawl, taken by the next render of the parent: a state to save. */
    private static String pendingState;
    private static final int CRAWL_SETTLE_TICKS = 24;

    private static void record(Crawl c, String line) {
        try {
            Files.writeString(Path.of(System.getProperty("cim.screenart"), c.name + ".presses"), line + "\n",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("[cimscene] press record failed: {}", e.toString());
        }
    }

    /**
     * The real move a player makes first: a stack from the inventory into the menu's first
     * free slot. Indices count only the slots on screen, as the capture lists them.
     */
    private static boolean slotMove(Minecraft mc, AbstractContainerScreen<?> screen, Crawl c) {
        List<Slot> shown = screen.getMenu().slots.stream().filter(Slot::isActive).toList();
        int from = -1;
        int to = -1;
        for (int i = 0; i < shown.size() && from < 0; i++) {
            Slot slot = shown.get(i);
            if (slot.container == mc.player.getInventory() && slot.hasItem()) {
                from = i;
            }
        }
        // The slot a player would put it in: empty and accepting it. A slot that refuses it
        // (an upgrade or energy slot) leaves the stack on the cursor, drawn wherever the
        // pointer happens to be (measured: the digital miner's states).
        ItemStack moving = from < 0 ? ItemStack.EMPTY : shown.get(from).getItem();
        for (int i = 0; i < shown.size() && to < 0 && from >= 0; i++) {
            Slot slot = shown.get(i);
            // ...and drawn: a player cannot put anything into a slot the screen does not show.
            if (slot.container != mc.player.getInventory() && !slot.hasItem() && slot.mayPlace(moving)
                    && drawn(screen, slot)) {
                to = i;
            }
        }
        if (from < 0 || to < 0) {
            return false;
        }
        int id = screen.getMenu().containerId;
        mc.gameMode.handleInventoryMouseClick(id, shown.get(from).index, 0,
                net.minecraft.world.inventory.ClickType.PICKUP, mc.player);
        mc.gameMode.handleInventoryMouseClick(id, shown.get(to).index, 0,
                net.minecraft.world.inventory.ClickType.PICKUP, mc.player);
        // A slot that only copies what it is shown (Create's filter slots) leaves the stack on
        // the cursor; a player puts it back where it came from rather than carry it on.
        if (!screen.getMenu().getCarried().isEmpty()) {
            mc.gameMode.handleInventoryMouseClick(id, shown.get(from).index, 0,
                    net.minecraft.world.inventory.ClickType.PICKUP, mc.player);
            record(c, "move\t" + from + "\t" + to + "\t" + from);
        } else {
            record(c, "move\t" + from + "\t" + to);
        }
        return true;
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

    private static boolean captured(AbstractContainerScreen<?> screen) {
        try {
            ResourceLocation id = BuiltInRegistries.MENU.getKey(screen.getMenu().getType());
            return id == null || DONE.contains(id);
        } catch (UnsupportedOperationException noType) {
            return true;
        }
    }

    private static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (crawl == null) {
            Path go = Path.of(System.getProperty("cim.screenart"), "crawl-go");
            // Not before this screen's own capture: the art and the base frame are the menu as
            // it opened, and the crawl's first act is a slot move that changes what it shows.
            if (Files.exists(go) && mc.screen instanceof AbstractContainerScreen<?> screen && captured(screen)) {
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
            // A server that opens a sub-menu closes this one first: the client shows no screen
            // for a moment (measured: Mekanism's digital miner config, the robit's tabs).
            if (mc.screen == null && crawl.closed++ < CRAWL_CLOSED_TICKS / CRAWL_STEP_TICKS) {
                return;
            }
            if (crawl.wait == 0) {
                // What the press led to: the evidence for a sub-menu that is never captured.
                String type = "-";
                if (mc.screen instanceof AbstractContainerScreen<?> other) {
                    try {
                        type = String.valueOf(BuiltInRegistries.MENU.getKey(other.getMenu().getType()));
                    } catch (UnsupportedOperationException none) {
                        type = "untyped";
                    }
                }
                LOGGER.info("[cimscene] crawl {} press {} -> screen {} menu {}", crawl.name, crawl.next - 1,
                        mc.screen == null ? "none" : mc.screen.getClass().getName(), type);
            }
            if (newMenuUp(mc) && crawl.wait++ < CRAWL_WAIT_TICKS / CRAWL_STEP_TICKS) {
                return;   // onRender is capturing it
            }
            if (!(mc.screen instanceof AbstractContainerScreen<?>) && mc.screen != null) {
                // A client-only page (AE2's guide): showing the old screen object again would
                // bring back a menu its removal already emptied on the client (measured: the
                // pattern provider's return slot), which no player sees. Leave it, and let the
                // page be opened again, as after any press that left the screen.
                mc.setScreen(null);
            }
            finishCrawl(crawl.next);   // the parent must be reopened
            return;
        }
        // A press that kept this screen: wait for its effect, then save what it looks like.
        if (crawl.pressed >= 0) {
            if (crawlTicks < crawl.settleAt) {
                return;
            }
            if (pendingState == null) {
                if (stateSaved) {
                    stateSaved = false;
                    if (crawl.pressed < CRAWL_MOVE) {
                        int[] at = crawl.targets.get(crawl.pressed);
                        // Where the menu sits in this state, against where it opened: a press
                        // may move it (Farmer's Delight's recipe book pushes the pot right).
                        int dx = crawl.parent instanceof AbstractContainerScreen<?> s ? s.getGuiLeft() - crawl.left : 0;
                        int dy = crawl.parent instanceof AbstractContainerScreen<?> s ? s.getGuiTop() - crawl.top : 0;
                        record(crawl, "press\t" + crawl.pressed + "\t" + (at[2] - crawl.left) + "\t"
                                + (at[3] - crawl.top) + "\t" + at[4] + "\t" + at[5] + "\t" + dx + "\t" + dy
                                + "\t" + (sent == null ? "-" : sent));
                    }
                    crawl.pressed = -1;
                } else {
                    pendingState = crawl.name + (crawl.pressed == CRAWL_MOVE ? ".move" : ".w" + crawl.pressed);
                }
            } else if (crawlTicks > crawl.settleAt + CRAWL_STATE_TIMEOUT_TICKS) {
                // The screen never drew again to be captured (measured: the QIO dashboard after a
                // press held the crawl until the shard gave up on it): no state for this press.
                LOGGER.info("[cimscene] crawl {} press {} drew no state", crawl.name, crawl.pressed);
                pendingState = null;
                crawl.pressed = -1;
            }
            return;
        }
        if (!crawl.moveDone) {
            crawl.moveDone = true;
            if (crawl.parent instanceof AbstractContainerScreen<?> screen && slotMove(mc, screen, crawl)) {
                crawl.pressed = CRAWL_MOVE;
                crawl.settleAt = crawlTicks + CRAWL_SETTLE_TICKS;
                return;
            }
        }
        if (crawl.next >= crawl.targets.size()) {
            finishCrawl(-1);
            return;
        }
        int index = crawl.next++;
        int[] at = crawl.targets.get(index);
        crawl.wait = 0;
        crawl.closed = 0;
        sent = null;
        // Pressed before the click: the payload it sends is recorded as this press's.
        crawl.pressed = index;
        crawl.settleAt = crawlTicks + CRAWL_SETTLE_TICKS;
        try {
            boolean handled = crawl.parent.mouseClicked(at[0], at[1], 0);
            crawl.parent.mouseReleased(at[0], at[1], 0);
            // What took the click: the evidence when a press sends nothing it should.
            String target = crawl.parent.getChildAt(at[0], at[1]).map(c -> c.getClass().getName()).orElse("-");
            LOGGER.info("[cimscene] crawl {} press {} at {},{} on {} handled={} sent={}", crawl.name, index,
                    at[2] - crawl.left, at[3] - crawl.top, target, handled, sent);
        } catch (Throwable t) {
            LOGGER.info("[cimscene] crawl press threw {}", t.toString());
        }
    }

    private static final int CRAWL_MOVE = 1000;
    private static boolean stateSaved;

    /** One state of a screen, the same two pictures a first capture takes. */
    private static void stateCapture(AbstractContainerScreen<?> screen, String base) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        Path dir = Path.of(System.getProperty("cim.screenart"));
        try (NativeImage frame = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            frame.writeToFile(dir.resolve(base + ".frame"));
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
        save(target, w, h, dir.resolve(base + ".png"));
        saveAnimation(mc, screen, w, h, target, dir.resolve(base + ".anim.png"));
        mc.getMainRenderTarget().bindWrite(true);
        target.destroyBuffers();
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
        // Before the icons: they are drawn into this same target, over the art.
        saveAnimation(mc, screen, w, h, target, dir.resolve(name + ".anim.png"));
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
                    .append(slot.getContainerSlot()).append('\t').append(slot.index).append('\t')
                    .append(drawn(screen, slot) ? 1 : 0).append('\n');
        }
        Files.writeString(dir.resolve(name + ".tsv"), tsv.toString(), StandardCharsets.UTF_8);
        LOGGER.info("[cimscene] screen art captured {}", id);
    }

    /** Later moments the screen is drawn at, to find what moves with the clock alone. */
    // Spread over a scroll's whole cycle (Mekanism scrolls a long label over several seconds):
    // three moments left a few edge pixels of the label's travel unseen.
    private static final long[] ANIMATION_SHIFTS_MS = {
            170L, 410L, 640L, 930L, 1270L, 1730L, 2190L, 2910L, 3530L, 4370L, 5210L, 6110L, 7340L, 8650L,
            10070L, 11930L};

    /**
     * The pixels of the art that change with time alone: the screen drawn again with the
     * clock moved on (Mekanism scrolls a label too long for its box by the time the screen has
     * been open, so two captures of one state differ there by the moment). Written as a white
     * mask only when something moves; the verdict judges those pixels apart.
     */
    private static void saveAnimation(Minecraft mc, AbstractContainerScreen<?> screen, int w, int h,
                                      TextureTarget art, Path file) {
        net.minecraft.util.TimeSource.NanoTimeSource real = net.minecraft.Util.timeSource;
        NativeImage mask = null;
        try (NativeImage base = download(art, w, h)) {
            for (long shift : ANIMATION_SHIFTS_MS) {
                long add = shift * 1_000_000L;
                net.minecraft.Util.timeSource = () -> real.getAsLong() + add;
                TextureTarget later = new TextureTarget(w, h, true, Minecraft.ON_OSX);
                later.setClearColor(0f, 0f, 0f, 0f);
                later.clear(Minecraft.ON_OSX);
                later.bindWrite(true);
                List<ItemStack> lifted = lift(mc);
                capturing = true;
                try {
                    GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
                    screen.render(graphics, -10000, -10000, 0f);
                    graphics.flush();
                } finally {
                    capturing = false;
                    restore(mc, lifted);
                    net.minecraft.Util.timeSource = real;
                }
                try (NativeImage other = download(later, w, h)) {
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            if (other.getPixelRGBA(x, y) != base.getPixelRGBA(x, y)) {
                                if (mask == null) {
                                    mask = new NativeImage(w, h, true);
                                }
                                mask.setPixelRGBA(x, y, -1);
                            }
                        }
                    }
                } finally {
                    later.destroyBuffers();
                }
            }
            if (mask != null) {
                mask.writeToFile(file);
            }
        } catch (Throwable t) {
            LOGGER.warn("[cimscene] animation capture failed: {}", t.toString());
        } finally {
            net.minecraft.Util.timeSource = real;
            if (mask != null) {
                mask.close();
            }
        }
    }

    private static NativeImage download(TextureTarget target, int w, int h) {
        NativeImage image = new NativeImage(w, h, false);
        RenderSystem.bindTexture(target.getColorTextureId());
        image.downloadTexture(0, false);
        image.flipY();
        return image;
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

    /** The slots the screen drew in its last frame (a slot can be active and never drawn). */
    private static final java.util.Set<Slot> DRAWN =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** The first payload the current press sent, as "class" or "class#ENUM". */
    private static String sent;

    /** Called by the payload-send mixin for every custom payload the client sends. */
    public static void payloadSent(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (crawl == null || crawl.pressed < 0 || sent != null) {
            return;
        }
        StringBuilder out = new StringBuilder(payload.getClass().getName());
        for (java.lang.reflect.Field f : payload.getClass().getDeclaredFields()) {
            if (f.getType().isEnum() && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(payload);
                    if (v != null) {
                        out.append('#').append(((Enum<?>) v).name());
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // the class alone still names the button's action
                }
                break;
            }
        }
        sent = out.toString();
    }

    /**
     * Whether the screen drew this slot in its last frame. A screen that draws its slots its own
     * way (Sophisticated Backpacks overrides renderSlot without the vanilla body) records none:
     * then nothing is known and every active slot counts as drawn.
     */
    private static boolean drawn(AbstractContainerScreen<?> screen, Slot slot) {
        boolean known = false;
        for (Slot each : screen.getMenu().slots) {
            if (DRAWN.contains(each)) {
                known = true;
                break;
            }
        }
        return !known || DRAWN.contains(slot);
    }

    /** Called by the payload-send mixin for a vanilla menu packet a press sent. */
    public static void vanillaSent(String described) {
        if (crawl != null && crawl.pressed >= 0 && crawl.pressed != CRAWL_MOVE && sent == null) {
            sent = described;
        }
    }

    /** Called by the slot-render mixin for every slot a screen draws. */
    public static void slotDrawn(Slot slot) {
        if (!capturing) {
            DRAWN.add(slot);
        }
    }

    /** The menu slots emptied for the current pass, with what they held. */
    private static final java.util.Map<Slot, ItemStack> LIFTED_MENU = new java.util.LinkedHashMap<>();

    /**
     * Empties the player's inventory and the open menu's own slots for the pass, so every slot
     * draws bare: the art is the screen alone, and the HUD draws whatever the slots hold, live,
     * with the icons captured here (measured: an item baked into the art showed twice once the
     * live menu drew it again). The returned list holds every lifted stack, for the icons.
     */
    private static List<ItemStack> lift(Minecraft mc) {
        List<ItemStack> items = new ArrayList<>(mc.player.getInventory().items);
        for (int i = 0; i < mc.player.getInventory().items.size(); i++) {
            mc.player.getInventory().items.set(i, ItemStack.EMPTY);
        }
        LIFTED_MENU.clear();
        if (mc.screen instanceof AbstractContainerScreen<?> screen) {
            for (Slot slot : screen.getMenu().slots) {
                // A slot nobody takes from is the mod's display: its screen draws the item with
                // its own decorations (Create's attribute summary and its count), so it stays.
                if (slot.container != mc.player.getInventory() && slot.hasItem() && slot.mayPickup(mc.player)) {
                    LIFTED_MENU.put(slot, slot.getItem());
                    slot.set(ItemStack.EMPTY);
                }
            }
        }
        List<ItemStack> all = new ArrayList<>(items);
        all.addAll(LIFTED_MENU.values());
        return all;
    }

    private static void restore(Minecraft mc, List<ItemStack> items) {
        int n = mc.player.getInventory().items.size();
        for (int i = 0; i < n && i < items.size(); i++) {
            mc.player.getInventory().items.set(i, items.get(i));
        }
        for (java.util.Map.Entry<Slot, ItemStack> e : LIFTED_MENU.entrySet()) {
            e.getKey().set(e.getValue());
        }
        LIFTED_MENU.clear();
    }
}
