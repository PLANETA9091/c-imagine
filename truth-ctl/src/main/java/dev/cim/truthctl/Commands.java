package dev.cim.truthctl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Command table of the truth-ctl/1 protocol. {@link #dispatch} runs on the client main
 * thread and returns the reply future (completed for synchronous commands, driven by
 * {@link TruthCtl.Task} for long ones).
 */
final class Commands {
    private Commands() {}

    private static final InteractionHand HAND = InteractionHand.MAIN_HAND;
    private static final double REACH = 4.5;

    // ---------------------------------------------------------------- dispatch

    static double timeoutSeconds(String cmd, List<String> args, JsonObject opts) {
        if (opts.has("timeout")) {
            try {
                return Double.parseDouble(opts.get("timeout").getAsString());
            } catch (RuntimeException ignored) {
                // fall through to default
            }
        }
        switch (cmd) {
            case "wait-ready":
                return args.isEmpty() ? 60 : parseDoubleOr(args.get(0), 60);
            case "wait":
                return parseDoubleOr(args.isEmpty() ? "0" : args.get(0), 0) / 20.0 + 15;
            case "break":
                return 30;
            case "use-item": {
                int hold;
                try {
                    hold = holdTicks(args);
                } catch (TruthCtl.CmdError e) {
                    hold = 0; // dispatch reports the bad argument
                }
                return hold > 0 ? hold / 20.0 + 15 : 20;
            }
            default:
                return 20;
        }
    }

    static CompletableFuture<JsonObject> dispatch(String cmd, List<String> args, JsonObject opts) {
        try {
            return dispatch0(cmd, args, opts);
        } catch (TruthCtl.CmdError e) {
            return CompletableFuture.completedFuture(TruthCtl.err(e.getMessage(), e.kind));
        } catch (Throwable t) {
            TruthCtl.LOG.error(TruthCtl.TAG + "command {} failed", cmd, t);
            return CompletableFuture.completedFuture(TruthCtl.err(String.valueOf(t), "exception"));
        }
    }

    private static CompletableFuture<JsonObject> dispatch0(String cmd, List<String> args, JsonObject opts)
            throws TruthCtl.CmdError {
        Minecraft mc = Minecraft.getInstance();
        switch (cmd) {
            case "hello": {
                JsonObject o = TruthCtl.ok();
                o.addProperty("protocol", TruthCtl.PROTOCOL);
                o.addProperty("mc", "1.21.1");
                o.addProperty("loader", "neoforge");
                return done(o);
            }
            case "state":
                return done(stateJson(mc));
            case "wait-ready":
                return TruthCtl.install(new WaitReadyTask(ticks(timeoutSeconds(cmd, args, opts))));
            case "block": {
                BlockPos pos = pos(args, 0);
                ClientLevel lvl = level(mc);
                JsonObject o = TruthCtl.ok();
                BlockState s = lvl.getBlockState(pos);
                o.addProperty("block", blockId(s));
                o.addProperty("state", stateString(s));
                o.addProperty("loaded", lvl.hasChunkAt(pos));
                return done(o);
            }
            case "blocks": {
                BlockPos a = pos(args, 0);
                BlockPos b = pos(args, 3);
                long volume = (long) (Math.abs(a.getX() - b.getX()) + 1) * (Math.abs(a.getY() - b.getY()) + 1)
                        * (Math.abs(a.getZ() - b.getZ()) + 1);
                if (volume > 2_000_000L) {
                    throw new TruthCtl.CmdError("box too large (" + volume + " blocks)", "bad_request");
                }
                ClientLevel lvl = level(mc);
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (BlockPos p : BlockPos.betweenClosed(a, b)) {
                    if (!lvl.hasChunkAt(p)) {
                        continue;
                    }
                    counts.merge(blockId(lvl.getBlockState(p)), 1, Integer::sum);
                }
                JsonObject o = TruthCtl.ok();
                o.add("counts", countsJson(counts));
                return done(o);
            }
            case "inventory":
                return done(inventoryJson(player(mc)));
            case "look": {
                LocalPlayer p = player(mc);
                double yaw = dbl(args, 0);
                double pitch = dbl(args, 1);
                p.setYRot((float) yaw);
                p.setXRot((float) pitch);
                JsonObject o = TruthCtl.ok();
                o.addProperty("yaw", p.getYRot());
                o.addProperty("pitch", p.getXRot());
                return done(o);
            }
            case "look-at-block": {
                LocalPlayer p = player(mc);
                BlockPos pos = pos(args, 0);
                Direction face = faceArg(args, 3, p.getEyePosition(), pos);
                p.lookAt(EntityAnchorArgument.Anchor.EYES, faceCenter(pos, face));
                JsonObject o = TruthCtl.ok();
                o.addProperty("face", face.getName());
                o.addProperty("yaw", p.getYRot());
                o.addProperty("pitch", p.getXRot());
                return done(o);
            }
            case "select": {
                LocalPlayer p = player(mc);
                String a = arg(args, 0);
                int slot = -1;
                if (a.matches("[1-9]")) {
                    slot = Integer.parseInt(a) - 1;
                } else {
                    String key = normItem(a);
                    for (int i = 0; i < 9; i++) {
                        if (itemId(p.getInventory().items.get(i)).equals(key)) {
                            slot = i;
                            break;
                        }
                    }
                    if (slot < 0) {
                        throw new TruthCtl.CmdError(key + " is not in the hotbar", "not_found");
                    }
                }
                selectSlot(p, slot);
                JsonObject o = TruthCtl.ok();
                o.addProperty("selected", slot + 1);
                o.addProperty("item", itemId(p.getInventory().items.get(slot)));
                return done(o);
            }
            case "break": {
                BlockPos pos = pos(args, 0);
                if (mc.level == null || mc.player == null || mc.gameMode == null) {
                    throw new TruthCtl.CmdError("not in world", "no_world");
                }
                Direction face = faceArg(args, 3, mc.player.getEyePosition(), pos);
                return TruthCtl.install(new BreakTask(pos, face, ticks(timeoutSeconds(cmd, args, opts))));
            }
            case "place": {
                BlockPos pos = pos(args, 0);
                String item = args.size() > 3 ? normItem(args.get(3)) : null;
                return TruthCtl.install(new PlaceTask(pos, item));
            }
            case "interact": {
                BlockPos pos = pos(args, 0);
                LocalPlayer p = player(mc);
                Direction face = faceArg(args, 3, p.getEyePosition(), pos);
                return TruthCtl.install(new InteractTask(pos, face));
            }
            case "use-item":
                return TruthCtl.install(new UseItemTask(holdTicks(args)));
            case "key":
                return keyCmd(args, opts);
            case "use-at": {
                Vec3 point = new Vec3(dbl(args, 0), dbl(args, 1), dbl(args, 2));
                return TruthCtl.install(new UseAtTask(point));
            }
            case "respawn": {
                LocalPlayer p = mc.player;
                if (p == null || mc.level == null) {
                    throw new TruthCtl.CmdError("not in world", "no_world");
                }
                boolean dead = mc.screen instanceof DeathScreen || p.isDeadOrDying() || p.getHealth() <= 0;
                if (!dead) {
                    JsonObject o = TruthCtl.ok();
                    o.addProperty("respawned", false);
                    return done(o);
                }
                return TruthCtl.install(new RespawnTask());
            }
            case "attack": {
                LocalPlayer p = player(mc);
                ClientLevel lvl = level(mc);
                Entity target;
                if (!args.isEmpty()) {
                    target = lvl.getEntity(intArg(args, 0));
                    if (target == null) {
                        throw new TruthCtl.CmdError("no entity with that id", "not_found");
                    }
                } else if (mc.hitResult instanceof EntityHitResult ehr) {
                    target = ehr.getEntity();
                } else {
                    throw new TruthCtl.CmdError("no entity id given and crosshair is not on an entity", "not_found");
                }
                p.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
                mc.gameMode.attack(p, target);
                p.swing(HAND);
                JsonObject o = TruthCtl.ok();
                o.addProperty("entity", target.getId());
                o.addProperty("type", typeId(target));
                return done(o);
            }
            case "screen":
            case "menu":
                return done(screenJson(mc));
            case "click-slot":
                return TruthCtl.install(new ClickSlotTask(intArg(args, 0), arg(args, 1, "left")));
            case "open-inventory": {
                LocalPlayer p = player(mc);
                if (mc.gameMode.hasInfiniteItems()) {
                    mc.setScreen(new CreativeModeInventoryScreen(p, p.connection.enabledFeatures(),
                            mc.options.operatorItemsTab().get()));
                } else {
                    mc.setScreen(new InventoryScreen(p));
                }
                JsonObject o = TruthCtl.ok();
                o.addProperty("screen", screenName(mc.screen));
                return done(o);
            }
            case "close": {
                if (mc.screen != null) {
                    mc.screen.onClose();
                }
                JsonObject o = TruthCtl.ok();
                o.addProperty("screen", screenName(mc.screen));
                return done(o);
            }
            case "command": {
                LocalPlayer p = player(mc);
                String text = String.join(" ", args);
                if (text.startsWith("/")) {
                    text = text.substring(1);
                }
                p.connection.sendCommand(text);
                return done(TruthCtl.ok());
            }
            case "chat": {
                LocalPlayer p = player(mc);
                p.connection.sendChat(String.join(" ", args));
                return done(TruthCtl.ok());
            }
            case "messages": {
                int n = args.isEmpty() ? 50 : intArg(args, 0);
                List<String> all = new ArrayList<>(TruthCtl.CHAT);
                List<String> tail = all.subList(Math.max(0, all.size() - Math.max(0, n)), all.size());
                JsonArray lines = new JsonArray();
                for (String s : tail) {
                    lines.add(s);
                }
                JsonObject o = TruthCtl.ok();
                o.add("lines", lines);
                return done(o);
            }
            case "entities": {
                LocalPlayer p = player(mc);
                double r = args.isEmpty() ? 16 : dbl(args, 0);
                JsonArray arr = new JsonArray();
                for (Entity e : level(mc).getEntities(p, p.getBoundingBox().inflate(r))) {
                    if (e == p) {
                        continue;
                    }
                    JsonObject eo = new JsonObject();
                    eo.addProperty("id", e.getId());
                    eo.addProperty("type", typeId(e));
                    eo.add("pos", vec(e.position()));
                    if (e instanceof LivingEntity le) {
                        eo.addProperty("health", le.getHealth());
                    } else {
                        eo.add("health", JsonNull.INSTANCE);
                    }
                    arr.add(eo);
                }
                JsonObject o = TruthCtl.ok();
                o.add("entities", arr);
                return done(o);
            }
            case "screenshot": {
                String path = arg(args, 0);
                if (path.isEmpty()) {
                    throw new TruthCtl.CmdError("screenshot needs a path", "bad_request");
                }
                return TruthCtl.install(new ScreenshotTask(path));
            }
            case "wait":
                return TruthCtl.install(new WaitTask(intArg(args, 0)));
            default:
                throw new TruthCtl.CmdError("unknown command: " + cmd, "unknown");
        }
    }

    // ---------------------------------------------------------------- tasks

    /** Break: real MultiPlayerGameMode start/continue per tick, then 1 s drop sampling. */
    static final class BreakTask extends TruthCtl.Task {
        private final BlockPos pos;
        private final Direction face;
        private final int timeoutTicks;
        private BlockState beforeState;
        private Map<String, Integer> invBefore;
        private final Set<Integer> preExisting = new HashSet<>();
        private final Set<Integer> seen = new HashSet<>();
        private final Map<String, Integer> drops = new LinkedHashMap<>();
        private int mineTicks;
        private int afterTicks;
        private boolean broken;
        private BlockState afterState;
        private int breakTicks;

        BreakTask(BlockPos pos, Direction face, int timeoutTicks) {
            super("break", timeoutTicks + 60);
            this.pos = pos;
            this.face = face;
            this.timeoutTicks = timeoutTicks;
        }

        @Override
        void start(Minecraft mc) throws TruthCtl.CmdError {
            LocalPlayer p = player(mc);
            ClientLevel lvl = level(mc);
            p.lookAt(EntityAnchorArgument.Anchor.EYES, faceCenter(pos, face));
            beforeState = lvl.getBlockState(pos);
            invBefore = invCounts(p.getInventory());
            for (ItemEntity e : nearbyItems(lvl, pos)) {
                preExisting.add(e.getId());
            }
            mc.gameMode.startDestroyBlock(pos, face);
        }

        @Override
        boolean step(Minecraft mc) throws TruthCtl.CmdError {
            LocalPlayer p = player(mc);
            ClientLevel lvl = level(mc);
            if (!broken) {
                BlockState now = lvl.getBlockState(pos);
                if (!now.equals(beforeState)) {
                    broken = true;
                    afterState = now;
                    breakTicks = mineTicks;
                } else if (mineTicks >= timeoutTicks) {
                    mc.gameMode.stopDestroyBlock();
                    JsonObject o = TruthCtl.err("block not broken within timeout", "timeout");
                    o.addProperty("block_before", blockId(beforeState));
                    o.addProperty("block", blockId(now));
                    future.complete(o);
                    return true;
                } else {
                    p.swing(HAND);
                    mc.gameMode.continueDestroyBlock(pos, face);
                    mineTicks++;
                    return false;
                }
            }
            for (ItemEntity e : nearbyItems(lvl, pos)) {
                if (!preExisting.contains(e.getId()) && seen.add(e.getId())) {
                    drops.merge(itemId(e.getItem()), e.getItem().getCount(), Integer::sum);
                }
            }
            afterTicks++;
            if (afterTicks < 20) {
                return false;
            }
            mc.gameMode.stopDestroyBlock();
            Map<String, Integer> picked = positiveDelta(invBefore, invCounts(p.getInventory()));
            JsonObject o = TruthCtl.ok();
            o.addProperty("block_before", blockId(beforeState));
            o.addProperty("block", blockId(afterState));
            o.addProperty("ticks", breakTicks);
            o.add("drops", countsJson(drops));
            o.add("picked_up", countsJson(picked));
            future.complete(o);
            return true;
        }
    }

    /** Place: select the item, click the solid neighbour's face that faces the target. */
    static final class PlaceTask extends TruthCtl.Task {
        private final BlockPos target;
        private final String item;
        private String before;
        private Map<String, Integer> invBefore;
        private String result;

        PlaceTask(BlockPos target, String item) {
            super("place", 5 + 20);
            this.target = target;
            this.item = item;
        }

        @Override
        void start(Minecraft mc) throws TruthCtl.CmdError {
            LocalPlayer p = player(mc);
            ClientLevel lvl = level(mc);
            if (item != null) {
                selectByItem(p, item);
            }
            BlockHitResult hit = placementHit(p, lvl, target);
            p.lookAt(EntityAnchorArgument.Anchor.EYES, hit.getLocation());
            before = blockId(lvl.getBlockState(target));
            invBefore = invCounts(p.getInventory());
            InteractionResult r = mc.gameMode.useItemOn(p, HAND, hit);
            if (r.consumesAction()) {
                p.swing(HAND);
            }
            result = String.valueOf(r);
        }

        @Override
        boolean step(Minecraft mc) throws TruthCtl.CmdError {
            if (ticks < 5) {
                return false;
            }
            LocalPlayer p = player(mc);
            JsonObject o = TruthCtl.ok();
            o.addProperty("block_before", before);
            o.addProperty("block", blockId(level(mc).getBlockState(target)));
            o.add("inventory_change", countsJson(signedDelta(invBefore, invCounts(p.getInventory()))));
            o.addProperty("result", result);
            future.complete(o);
            return true;
        }
    }

    /** Interact: useItemOn on the block's face (block use first, then held item). */
    static final class InteractTask extends TruthCtl.Task {
        private final BlockPos pos;
        private final Direction face;
        private String result;

        InteractTask(BlockPos pos, Direction face) {
            super("interact", 5 + 20);
            this.pos = pos;
            this.face = face;
        }

        @Override
        void start(Minecraft mc) throws TruthCtl.CmdError {
            LocalPlayer p = player(mc);
            Vec3 point = faceCenter(pos, face);
            p.lookAt(EntityAnchorArgument.Anchor.EYES, point);
            BlockHitResult hit = new BlockHitResult(point, face, pos, false);
            InteractionResult r = mc.gameMode.useItemOn(p, HAND, hit);
            if (r.consumesAction()) {
                p.swing(HAND);
            }
            result = String.valueOf(r);
        }

        @Override
        boolean step(Minecraft mc) throws TruthCtl.CmdError {
            if (ticks < 5) {
                return false;
            }
            JsonObject o = TruthCtl.ok();
            o.addProperty("screen", screenName(mc.screen));
            o.addProperty("block", blockId(level(mc).getBlockState(pos)));
            o.addProperty("result", result);
            future.complete(o);
            return true;
        }
    }

    /**
     * key NAME [press|release|tap] [--ticks N] (default tap, 1 tick). The KeyMapping is found by
     * its name (the translation key, e.g. key.jump or keybinds.combat_roll.roll) and driven the
     * way vanilla input drives it: KeyMapping.set(key, down), plus KeyMapping.click(key) on press.
     */
    static CompletableFuture<JsonObject> keyCmd(List<String> args, JsonObject opts)
            throws TruthCtl.CmdError {
        String name = arg(args, 0);
        String mode = "tap";
        if (args.size() > 1 && !args.get(1).startsWith("--")) {
            mode = args.get(1);
        }
        if (!mode.equals("press") && !mode.equals("release") && !mode.equals("tap")) {
            throw new TruthCtl.CmdError("mode must be press, release or tap: " + mode, "bad_request");
        }
        int ticks = 1;
        int i = args.indexOf("--ticks");
        if (i >= 0) {
            ticks = tickCount(arg(args, i + 1));
        } else if (opts.has("ticks")) {
            ticks = tickCount(opts.get("ticks").getAsString());
        }
        KeyMapping km = keyMapping(Minecraft.getInstance(), name);
        JsonObject o = TruthCtl.ok();
        o.addProperty("key", name);
        o.addProperty("mode", mode);
        switch (mode) {
            case "press":
                pressKey(km);
                o.addProperty("ticks", 0);
                return done(o);
            case "release":
                releaseKey(km);
                o.addProperty("ticks", 0);
                return done(o);
            default:
                return TruthCtl.install(new KeyTask(km, name, ticks));
        }
    }

    private static int tickCount(String s) throws TruthCtl.CmdError {
        try {
            int n = Integer.parseInt(s);
            if (n < 1 || n > 1200) {
                throw new TruthCtl.CmdError("--ticks must be 1..1200", "bad_request");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new TruthCtl.CmdError("--ticks needs a tick count: " + s, "bad_request");
        }
    }

    /** Finds a registered KeyMapping by its name (translation key). Mod mappings are in options.keyMappings. */
    static KeyMapping keyMapping(Minecraft mc, String name) throws TruthCtl.CmdError {
        if (mc.options == null || mc.options.keyMappings == null) {
            throw new TruthCtl.CmdError("options not loaded", "no_world");
        }
        for (KeyMapping km : mc.options.keyMappings) {
            if (km.getName().equals(name)) {
                if (km.isUnbound()) {
                    throw new TruthCtl.CmdError(name + " is unbound", "bad_request");
                }
                return km;
            }
        }
        throw new TruthCtl.CmdError("no KeyMapping named " + name, "not_found");
    }

    static void pressKey(KeyMapping km) {
        KeyMapping.set(km.getKey(), true);
        KeyMapping.click(km.getKey());
    }

    static void releaseKey(KeyMapping km) {
        KeyMapping.set(km.getKey(), false);
    }

    /** Holds a KeyMapping down for a number of client ticks, then releases it. */
    static final class KeyTask extends TruthCtl.Task {
        private final KeyMapping km;
        private final String name;
        private final int hold;

        KeyTask(KeyMapping km, String name, int hold) {
            super("key", hold + 20);
            this.km = km;
            this.name = name;
            this.hold = hold;
        }

        @Override
        void start(Minecraft mc) {
            pressKey(km);
        }

        @Override
        boolean step(Minecraft mc) {
            if (ticks < hold) {
                return false;
            }
            releaseKey(km);
            JsonObject o = TruthCtl.ok();
            o.addProperty("key", name);
            o.addProperty("mode", "tap");
            o.addProperty("ticks", hold);
            future.complete(o);
            return true;
        }
    }

    /**
     * use-at X Y Z: aims the view at the block-space point, ray-traces the first block hit from
     * the eye through the point (Block.OUTLINE) and calls useItemOn with that real hit. Reply after
     * 5 ticks.
     */
    static final class UseAtTask extends TruthCtl.Task {
        private final Vec3 point;
        private Map<String, Integer> invBefore;
        private String blockBefore;
        private BlockHitResult hit;
        private String result;

        UseAtTask(Vec3 point) {
            super("use-at", 5 + 20);
            this.point = point;
        }

        @Override
        void start(Minecraft mc) throws TruthCtl.CmdError {
            LocalPlayer p = player(mc);
            ClientLevel lvl = level(mc);
            p.lookAt(EntityAnchorArgument.Anchor.EYES, point);
            Vec3 eye = p.getEyePosition();
            Vec3 dir = point.subtract(eye);
            double len = dir.length();
            if (len < 1e-6) {
                throw new TruthCtl.CmdError("point is at the eye", "bad_request");
            }
            // Extend one block past the point so a face lying exactly on the point is still hit.
            Vec3 end = eye.add(dir.scale((len + 1.0) / len));
            BlockHitResult h = lvl.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, p));
            if (h.getType() != HitResult.Type.BLOCK) {
                throw new TruthCtl.CmdError("no block along the ray from the eye to " + point, "not_found");
            }
            hit = h;
            blockBefore = blockId(lvl.getBlockState(h.getBlockPos()));
            invBefore = invCounts(p.getInventory());
            InteractionResult r = mc.gameMode.useItemOn(p, HAND, h);
            if (r.consumesAction()) {
                p.swing(HAND);
            }
            result = String.valueOf(r);
        }

        @Override
        boolean step(Minecraft mc) throws TruthCtl.CmdError {
            if (ticks < 5) {
                return false;
            }
            LocalPlayer p = player(mc);
            ClientLevel lvl = level(mc);
            BlockPos bp = hit.getBlockPos();
            JsonObject h = new JsonObject();
            JsonArray pos = new JsonArray();
            pos.add(bp.getX());
            pos.add(bp.getY());
            pos.add(bp.getZ());
            h.add("pos", pos);
            h.addProperty("face", hit.getDirection().getName());
            h.add("location", vec(hit.getLocation()));
            JsonObject o = TruthCtl.ok();
            o.add("hit", h);
            o.addProperty("block_before", blockBefore);
            o.addProperty("block", blockId(lvl.getBlockState(bp)));
            o.add("inventory_change", countsJson(signedDelta(invBefore, invCounts(p.getInventory()))));
            o.addProperty("result", result);
            future.complete(o);
            return true;
        }
    }

    /** Hold ticks from "--hold TICKS" in args (default 40 when the number is omitted), 0 = single click. */
    static int holdTicks(List<String> args) throws TruthCtl.CmdError {
        int i = args.indexOf("--hold");
        if (i < 0) {
            return 0;
        }
        if (i + 1 >= args.size()) {
            return 40;
        }
        try {
            int n = Integer.parseInt(args.get(i + 1));
            if (n < 1 || n > 72000) {
                throw new TruthCtl.CmdError("--hold must be 1..72000 ticks", "bad_request");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new TruthCtl.CmdError("--hold needs a tick count", "bad_request");
        }
    }

    /**
     * Use the held item (MultiPlayerGameMode.useItem). Without hold: one click, reply after 5
     * ticks. With hold: a held right-click. The first tick calls useItem, keyUse stays pressed
     * (vanilla re-starts the use each tick while it is not running), and the use is released
     * when it finishes or the hold ticks elapse, then 5 settle ticks before the reply.
     */
    static final class UseItemTask extends TruthCtl.Task {
        private final int hold;
        private Map<String, Integer> invBefore;
        private String result;
        private boolean seenUsing;
        private boolean released;
        private int releasedAt;
        private String endedBy;

        UseItemTask(int hold) {
            super("use-item", hold > 0 ? hold + 40 : 5 + 20);
            this.hold = hold;
        }

        @Override
        void start(Minecraft mc) throws TruthCtl.CmdError {
            LocalPlayer p = player(mc);
            invBefore = invCounts(p.getInventory());
            InteractionResult r = mc.gameMode.useItem(p, HAND);
            if (r.consumesAction()) {
                p.swing(HAND);
            }
            result = String.valueOf(r);
            if (hold > 0) {
                mc.options.keyUse.setDown(true);
            }
        }

        @Override
        boolean step(Minecraft mc) throws TruthCtl.CmdError {
            LocalPlayer p = player(mc);
            if (hold == 0) {
                return ticks >= 5 && reply(p);
            }
            if (!released) {
                if (p.isUsingItem()) {
                    seenUsing = true;
                }
                if (seenUsing && !p.isUsingItem()) {
                    endedBy = "finished";
                } else if (!seenUsing && ticks >= 3) {
                    endedBy = "not_usable";
                } else if (ticks >= hold) {
                    endedBy = "hold_elapsed";
                } else {
                    return false;
                }
                mc.options.keyUse.setDown(false);
                if (p.isUsingItem()) {
                    mc.gameMode.releaseUsingItem(p);
                }
                released = true;
                releasedAt = ticks;
            }
            return ticks - releasedAt >= 5 && reply(p);
        }

        private boolean reply(LocalPlayer p) {
            JsonObject o = TruthCtl.ok();
            o.add("inventory_change", countsJson(signedDelta(invBefore, invCounts(p.getInventory()))));
            o.addProperty("result", result);
            if (hold > 0) {
                o.addProperty("ended_by", endedBy);
                o.addProperty("held_ticks", ticks);
            }
            o.addProperty("food", p.getFoodData().getFoodLevel());
            o.addProperty("saturation", p.getFoodData().getSaturationLevel());
            o.addProperty("health", p.getHealth());
            future.complete(o);
            return true;
        }
    }

    /** Respawn like the DeathScreen button: LocalPlayer.respawn() then close the screen. */
    static final class RespawnTask extends TruthCtl.Task {
        private boolean requested;

        RespawnTask() {
            super("respawn", 10 * 20 + 20);
        }

        @Override
        void start(Minecraft mc) throws TruthCtl.CmdError {
            LocalPlayer p = player(mc);
            p.respawn();
            requested = true;
            if (mc.screen instanceof DeathScreen) {
                mc.setScreen(null);
            }
        }

        @Override
        boolean step(Minecraft mc) {
            LocalPlayer p = mc.player;
            if (!requested || ticks < 2 || p == null || mc.level == null) {
                return false;
            }
            if (mc.screen instanceof DeathScreen || !p.isAlive()) {
                if (mc.screen instanceof DeathScreen) {
                    mc.setScreen(null);
                }
                return false;
            }
            JsonObject o = TruthCtl.ok();
            o.addProperty("respawned", true);
            o.addProperty("screen", screenName(mc.screen));
            JsonObject po = new JsonObject();
            po.add("position", vec(p.position()));
            po.add("velocity", vec(p.getDeltaMovement()));
            po.addProperty("health", p.getHealth());
            o.add("player", po);
            o.addProperty("health", p.getHealth());
            future.complete(o);
            return true;
        }
    }

    /** Container click via MultiPlayerGameMode.handleInventoryMouseClick. */
    static final class ClickSlotTask extends TruthCtl.Task {
        private final int index;
        private final int button;
        private final ClickType type;
        private AbstractContainerMenu menu;
        private List<ItemStack> snapshot;

        ClickSlotTask(int index, String mode) throws TruthCtl.CmdError {
            super("click-slot", 2 + 20);
            this.index = index;
            switch (mode) {
                case "left" -> {
                    this.button = 0;
                    this.type = ClickType.PICKUP;
                }
                case "right" -> {
                    this.button = 1;
                    this.type = ClickType.PICKUP;
                }
                case "shift" -> {
                    this.button = 0;
                    this.type = ClickType.QUICK_MOVE;
                }
                default -> throw new TruthCtl.CmdError("mode must be left|right|shift", "bad_request");
            }
        }

        @Override
        void start(Minecraft mc) throws TruthCtl.CmdError {
            LocalPlayer p = player(mc);
            menu = p.containerMenu;
            if (index < 0 || index >= menu.slots.size()) {
                throw new TruthCtl.CmdError("slot index out of range (" + menu.slots.size() + " slots)", "bad_request");
            }
            snapshot = new ArrayList<>();
            for (Slot s : menu.slots) {
                snapshot.add(s.getItem().copy());
            }
            mc.gameMode.handleInventoryMouseClick(menu.containerId, index, button, type, p);
        }

        @Override
        boolean step(Minecraft mc) throws TruthCtl.CmdError {
            if (ticks < 2) {
                return false;
            }
            JsonArray changed = new JsonArray();
            for (int i = 0; i < menu.slots.size(); i++) {
                ItemStack now = menu.slots.get(i).getItem();
                ItemStack was = snapshot.get(i);
                if (!itemId(now).equals(itemId(was)) || now.getCount() != was.getCount()) {
                    changed.add(slotJson(i, now));
                }
            }
            JsonObject o = TruthCtl.ok();
            o.add("carried", stackJson(menu.getCarried()));
            o.add("changed", changed);
            future.complete(o);
            return true;
        }
    }

    /** Screenshot: grabs the main render target two client ticks after the request. */
    static final class ScreenshotTask extends TruthCtl.Task {
        private final String path;

        ScreenshotTask(String path) {
            super("screenshot", 2 + 40);
            this.path = path;
        }

        @Override
        boolean step(Minecraft mc) throws TruthCtl.CmdError {
            if (ticks < 2) {
                return false;
            }
            try {
                Path out = Paths.get(path).toAbsolutePath();
                if (out.getParent() != null) {
                    Files.createDirectories(out.getParent());
                }
                try (NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    img.writeToFile(out);
                    JsonObject o = TruthCtl.ok();
                    o.addProperty("path", out.toString());
                    o.addProperty("width", img.getWidth());
                    o.addProperty("height", img.getHeight());
                    future.complete(o);
                }
            } catch (java.io.IOException e) {
                throw new TruthCtl.CmdError("screenshot failed: " + e, "exception");
            }
            return true;
        }
    }

    static final class WaitTask extends TruthCtl.Task {
        private final int n;

        WaitTask(int n) {
            super("wait", Math.max(0, n) + 5);
            this.n = Math.max(0, n);
        }

        @Override
        boolean step(Minecraft mc) {
            if (ticks < n) {
                return false;
            }
            JsonObject o = TruthCtl.ok();
            o.addProperty("ticks", n);
            future.complete(o);
            return true;
        }
    }

    /** Ready = in world, not on the loading screen, 5x5 chunks around the player loaded, for 10 ticks. */
    static final class WaitReadyTask extends TruthCtl.Task {
        private int stable;

        WaitReadyTask(int timeoutTicks) {
            super("wait-ready", timeoutTicks);
        }

        @Override
        boolean step(Minecraft mc) {
            if (isReady(mc)) {
                stable++;
            } else {
                stable = 0;
            }
            if (stable < 10) {
                return false;
            }
            future.complete(stateJson(mc));
            return true;
        }

        private static boolean isReady(Minecraft mc) {
            LocalPlayer p = mc.player;
            ClientLevel lvl = mc.level;
            if (p == null || lvl == null || mc.screen instanceof TitleScreen || mc.screen instanceof ReceivingLevelScreen) {
                return false;
            }
            int cx = p.chunkPosition().x;
            int cz = p.chunkPosition().z;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (!lvl.hasChunk(cx + dx, cz + dz)) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static CompletableFuture<JsonObject> done(JsonObject o) {
        return CompletableFuture.completedFuture(o);
    }

    private static int ticks(double seconds) {
        return (int) Math.max(1, Math.round(seconds * 20));
    }

    private static double parseDoubleOr(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (RuntimeException e) {
            return def;
        }
    }

    static LocalPlayer player(Minecraft mc) throws TruthCtl.CmdError {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            throw new TruthCtl.CmdError("not in world", "no_world");
        }
        return p;
    }

    static ClientLevel level(Minecraft mc) throws TruthCtl.CmdError {
        ClientLevel lvl = mc.level;
        if (lvl == null || mc.player == null) {
            throw new TruthCtl.CmdError("not in world", "no_world");
        }
        return lvl;
    }

    private static String arg(List<String> a, int i) throws TruthCtl.CmdError {
        if (i >= a.size()) {
            throw new TruthCtl.CmdError("missing argument " + (i + 1), "bad_request");
        }
        return a.get(i);
    }

    private static String arg(List<String> a, int i, String def) {
        return i < a.size() ? a.get(i) : def;
    }

    private static int intArg(List<String> a, int i) throws TruthCtl.CmdError {
        String s = arg(a, i);
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new TruthCtl.CmdError("argument " + (i + 1) + " is not an integer: " + s, "bad_request");
        }
    }

    private static double dbl(List<String> a, int i) throws TruthCtl.CmdError {
        String s = arg(a, i);
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new TruthCtl.CmdError("argument " + (i + 1) + " is not a number: " + s, "bad_request");
        }
    }

    private static BlockPos pos(List<String> a, int i) throws TruthCtl.CmdError {
        return new BlockPos(intArg(a, i), intArg(a, i + 1), intArg(a, i + 2));
    }

    private static String normItem(String s) throws TruthCtl.CmdError {
        try {
            return ResourceLocation.parse(s).toString();
        } catch (RuntimeException e) {
            throw new TruthCtl.CmdError("bad item id: " + s, "bad_request");
        }
    }

    /** Face argument at index i, or the face nearest the eye when absent. */
    private static Direction faceArg(List<String> a, int i, Vec3 eye, BlockPos pos) throws TruthCtl.CmdError {
        if (i >= a.size()) {
            return visibleFace(eye, pos);
        }
        Direction d = Direction.byName(a.get(i));
        if (d == null) {
            throw new TruthCtl.CmdError("bad face: " + a.get(i), "bad_request");
        }
        return d;
    }

    static Direction visibleFace(Vec3 eye, BlockPos pos) {
        Vec3 c = Vec3.atCenterOf(pos);
        double dx = eye.x - c.x;
        double dy = eye.y - c.y;
        double dz = eye.z - c.z;
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ax >= ay && ax >= az) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (ay >= az) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** Point at the centre of the given face of the block. */
    static Vec3 faceCenter(BlockPos pos, Direction face) {
        return Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
    }

    /**
     * Solid neighbour of target whose face points at target and is visible from the eye,
     * nearest one within reach. The click is on that neighbour's face (vanilla placement).
     */
    static BlockHitResult placementHit(LocalPlayer p, ClientLevel lvl, BlockPos target) throws TruthCtl.CmdError {
        Vec3 eye = p.getEyePosition();
        BlockHitResult best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos n = target.relative(d);
            BlockState ns = lvl.getBlockState(n);
            if (ns.isAir() || ns.canBeReplaced()) {
                continue;
            }
            Direction face = d.getOpposite();
            Vec3 hit = faceCenter(n, face);
            Vec3 out = Vec3.atLowerCornerOf(face.getNormal());
            if (eye.subtract(hit).dot(out) <= 0) {
                continue;
            }
            double dist = eye.distanceTo(hit);
            if (dist > REACH) {
                continue;
            }
            if (dist < bestDist) {
                bestDist = dist;
                best = new BlockHitResult(hit, face, n, false);
            }
        }
        if (best == null) {
            throw new TruthCtl.CmdError("no reachable visible solid neighbour of " + target, "not_found");
        }
        return best;
    }

    private static void selectSlot(LocalPlayer p, int idx) {
        p.getInventory().selected = idx;
        p.connection.send(new ServerboundSetCarriedItemPacket(idx));
    }

    private static void selectByItem(LocalPlayer p, String key) throws TruthCtl.CmdError {
        for (int i = 0; i < 9; i++) {
            if (itemId(p.getInventory().items.get(i)).equals(key)) {
                selectSlot(p, i);
                return;
            }
        }
        throw new TruthCtl.CmdError(key + " is not in the hotbar", "not_found");
    }

    private static List<ItemEntity> nearbyItems(ClientLevel lvl, BlockPos pos) {
        Vec3 c = Vec3.atCenterOf(pos);
        List<ItemEntity> out = new ArrayList<>();
        for (ItemEntity e : lvl.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3.0))) {
            if (e.position().distanceTo(c) <= 3.0) {
                out.add(e);
            }
        }
        return out;
    }

    static String blockId(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
    }

    /** Vanilla-style state string: ns:id[prop=val,prop=val] (property order from the state). */
    static String stateString(BlockState s) {
        StringBuilder sb = new StringBuilder(blockId(s));
        if (!s.getValues().isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (Map.Entry<Property<?>, Comparable<?>> e : s.getValues().entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(e.getKey().getName()).append('=').append(propValue(e.getKey(), e.getValue()));
            }
            sb.append(']');
        }
        return sb.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String propValue(Property prop, Comparable value) {
        return prop.getName(value);
    }

    static String itemId(ItemStack st) {
        if (st.isEmpty()) {
            return "minecraft:air";
        }
        return BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
    }

    static String typeId(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    static Map<String, Integer> invCounts(Inventory inv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (ItemStack st : inv.items) {
            addCount(m, st);
        }
        for (ItemStack st : inv.armor) {
            addCount(m, st);
        }
        for (ItemStack st : inv.offhand) {
            addCount(m, st);
        }
        return m;
    }

    private static void addCount(Map<String, Integer> m, ItemStack st) {
        if (!st.isEmpty()) {
            m.merge(itemId(st), st.getCount(), Integer::sum);
        }
    }

    /** after - before, nonzero entries only. */
    static Map<String, Integer> signedDelta(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys) {
            int d = after.getOrDefault(k, 0) - before.getOrDefault(k, 0);
            if (d != 0) {
                out.put(k, d);
            }
        }
        return out;
    }

    static Map<String, Integer> positiveDelta(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : signedDelta(before, after).entrySet()) {
            if (e.getValue() > 0) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    static JsonObject countsJson(Map<String, Integer> m) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            o.addProperty(e.getKey(), e.getValue());
        }
        return o;
    }

    static JsonArray vec(Vec3 v) {
        JsonArray a = new JsonArray();
        a.add(v.x);
        a.add(v.y);
        a.add(v.z);
        return a;
    }

    static JsonElement stackJson(ItemStack st) {
        if (st.isEmpty()) {
            return JsonNull.INSTANCE;
        }
        JsonObject o = new JsonObject();
        o.addProperty("item", itemId(st));
        o.addProperty("count", st.getCount());
        return o;
    }

    static JsonObject slotJson(int index, ItemStack st) {
        JsonObject o = new JsonObject();
        o.addProperty("slot", index);
        o.addProperty("index", index);
        o.addProperty("item", itemId(st));
        o.addProperty("count", st.getCount());
        return o;
    }

    static JsonObject stateJson(Minecraft mc) {
        JsonObject o = TruthCtl.ok();
        o.addProperty("screen", screenName(mc.screen));
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        if (mc.gameMode != null && p != null) {
            o.addProperty("gamemode", mc.gameMode.getPlayerMode().getName());
        } else {
            o.add("gamemode", JsonNull.INSTANCE);
        }
        if (lvl != null) {
            o.addProperty("dimension", lvl.dimension().location().toString());
            o.addProperty("biome", lvl.getBiome(p != null ? p.blockPosition() : BlockPos.ZERO)
                    .unwrapKey().map(k -> k.location().toString()).orElse(null));
            JsonObject time = new JsonObject();
            time.addProperty("day_time", lvl.getDayTime());
            o.add("time", time);
        } else {
            o.add("dimension", JsonNull.INSTANCE);
            o.add("biome", JsonNull.INSTANCE);
            o.add("time", JsonNull.INSTANCE);
        }
        if (p != null) {
            o.addProperty("yaw", p.getYRot());
            o.addProperty("pitch", p.getXRot());
            JsonObject po = new JsonObject();
            po.add("position", vec(p.position()));
            po.add("velocity", vec(p.getDeltaMovement()));
            po.addProperty("health", p.getHealth());
            po.addProperty("food", p.getFoodData().getFoodLevel());
            po.addProperty("saturation", p.getFoodData().getSaturationLevel());
            po.addProperty("on_ground", p.onGround());
            o.add("player", po);
            ItemStack held = p.getMainHandItem();
            o.add("held", held.isEmpty() ? JsonNull.INSTANCE : stackJson(held));
        } else {
            o.add("player", JsonNull.INSTANCE);
            o.add("held", JsonNull.INSTANCE);
        }
        o.add("target", targetJson(mc));
        return o;
    }

    private static JsonElement targetJson(Minecraft mc) {
        if (mc.hitResult instanceof BlockHitResult bhr && mc.level != null) {
            JsonObject t = new JsonObject();
            BlockPos bp = bhr.getBlockPos();
            t.addProperty("kind", "block");
            JsonArray pa = new JsonArray();
            pa.add(bp.getX());
            pa.add(bp.getY());
            pa.add(bp.getZ());
            t.add("pos", pa);
            t.addProperty("state", stateString(mc.level.getBlockState(bp)));
            return t;
        }
        if (mc.hitResult instanceof EntityHitResult ehr) {
            JsonObject t = new JsonObject();
            t.addProperty("kind", "entity");
            t.addProperty("id", ehr.getEntity().getId());
            t.addProperty("type", typeId(ehr.getEntity()));
            return t;
        }
        return JsonNull.INSTANCE;
    }

    static JsonObject inventoryJson(LocalPlayer p) {
        Inventory inv = p.getInventory();
        JsonObject o = TruthCtl.ok();
        JsonArray hotbar = new JsonArray();
        for (int i = 0; i < 9; i++) {
            hotbar.add(slotJson(i, inv.items.get(i)));
        }
        JsonArray main = new JsonArray();
        for (int i = 9; i < inv.items.size(); i++) {
            main.add(slotJson(i, inv.items.get(i)));
        }
        JsonArray armor = new JsonArray();
        for (int i = 0; i < inv.armor.size(); i++) {
            armor.add(slotJson(i, inv.armor.get(i)));
        }
        o.add("hotbar", hotbar);
        o.add("main", main);
        o.add("armor", armor);
        JsonArray off = new JsonArray();
        off.add(slotJson(0, inv.offhand.get(0)));
        o.add("offhand", off);
        ItemStack carried = p.containerMenu.getCarried();
        o.add("carried", carried.isEmpty() ? JsonNull.INSTANCE : stackJson(carried));
        return o;
    }

    static JsonObject screenJson(Minecraft mc) {
        JsonObject o = TruthCtl.ok();
        Screen s = mc.screen;
        o.addProperty("screen", screenName(s));
        o.addProperty("title", s == null ? null : titleOf(s));
        LocalPlayer p = mc.player;
        if (p == null) {
            o.add("menu_type", JsonNull.INSTANCE);
            o.add("slots", new JsonArray());
            o.add("carried", JsonNull.INSTANCE);
            return o;
        }
        AbstractContainerMenu m = p.containerMenu;
        String menuType = null;
        try {
            var key = BuiltInRegistries.MENU.getKey(m.getType());
            menuType = key == null ? null : key.toString();
        } catch (RuntimeException ignored) {
            // InventoryMenu and some other menus have no registered MenuType
        }
        o.addProperty("menu_type", menuType);
        JsonArray slots = new JsonArray();
        for (int i = 0; i < m.slots.size(); i++) {
            slots.add(slotJson(i, m.slots.get(i).getItem()));
        }
        o.add("slots", slots);
        ItemStack carried = m.getCarried();
        o.add("carried", carried.isEmpty() ? JsonNull.INSTANCE : stackJson(carried));
        return o;
    }

    private static String titleOf(Screen s) {
        Component c = s.getTitle();
        return c == null ? null : c.getString();
    }

    static String screenName(Screen s) {
        if (s == null) {
            return "playing";
        }
        if (s instanceof TitleScreen) {
            return "title";
        }
        return s.getClass().getSimpleName();
    }
}
