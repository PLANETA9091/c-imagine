package dev.cim.live;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import java.util.function.Function;

/**
 * Paper's way in. The plugin and the layer share no class, so the session is driven with
 * text: one JVM-wide {@code Function<String, String>} under the system property key
 * {@value #KEY}, a command in and the menu's state out. Item stacks travel whole, as the
 * JSON of {@link ItemStack#OPTIONAL_CODEC}: a filter's settings and a backpack's contents
 * are the mod's own data components and come back to Paper unchanged.
 *
 * <pre>
 * in:   open-item  TAB uuid TAB name        then one line per inventory slot: inv TAB i TAB stack
 *       click      TAB uuid TAB slot TAB button TAB PICKUP|QUICK_MOVE|...
 *       close      TAB uuid
 * out:  menu TAB type TAB class   (or: none)
 *       slot TAB n TAB menuIndex TAB player|menu TAB containerSlot TAB stack   (active slots, in order)
 *       carried TAB stack
 *       inv TAB i TAB stack
 *       drop TAB stack            (what the mod's code dropped from the player)
 * </pre>
 */
public final class LiveProtocol {

    public static final String KEY = "cim.live";

    /** The block each session opened, to hand its block entity's data back on close. */
    private static final java.util.Map<UUID, net.minecraft.core.BlockPos> BLOCKS = new java.util.HashMap<>();

    private LiveProtocol() {
    }

    /** Publishes the entry point; called once the registries are complete. */
    static void publish() {
        Function<String, String> call = LiveProtocol::call;
        System.getProperties().put(KEY, call);
    }

    static synchronized String call(String request) {
        try {
            String[] lines = request.split("\n");
            String[] head = lines[0].split("\t");
            if (head[0].equals("tick")) {
                return tick();
            }
            // Placed mod blocks of loaded Paper chunks: no player, so no uuid.
            if (head[0].equals("adopt")) {
                return adopt(head, lines);
            }
            if (head[0].equals("release")) {
                return release(head);
            }
            if (head[0].equals("save")) {
                return save(head);
            }
            // Stateless answers about blocks and tools: no player, so no uuid.
            if (head[0].equals("blockinfo")) {
                return blockInfo(head);
            }
            if (head[0].equals("digspeed")) {
                return digSpeed(head);
            }
            UUID id = UUID.fromString(head[1]);
            return switch (head[0]) {
                case "open-item" -> {
                    // open-item TAB uuid TAB name [TAB sneaking 1|0]
                    SessionServerPlayer player = LiveMenus.session(id, head[2]);
                    player.closeContainer();
                    readInventory(player, lines);
                    player.setShiftKeyDown(head.length > 3 && head[3].equals("1"));
                    try {
                        yield state(player, LiveMenus.useHeld(player, player.getMainHandItem()));
                    } finally {
                        player.setShiftKeyDown(false);
                    }
                }
                case "open-block" -> {
                    // open-block TAB uuid TAB name TAB blockId TAB x TAB y TAB z, then inv lines and an
                    // optional "be TAB snbt" line: the block entity's data as Paper kept it.
                    SessionServerPlayer player = LiveMenus.session(id, head[2]);
                    player.closeContainer();
                    readInventory(player, lines);
                    net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                            Integer.parseInt(head[4]), Integer.parseInt(head[5]), Integer.parseInt(head[6]));
                    net.minecraft.world.level.block.state.BlockState wanted =
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                                    net.minecraft.resources.ResourceLocation.parse(head[3])).defaultBlockState();
                    FacadeServerLevel level = LiveMenus.level();
                    boolean fresh = false;
                    for (int i = 1; i < lines.length; i++) {
                        fresh |= lines[i].equals("fresh");
                    }
                    if (fresh) {
                        // As just placed: the old block entity goes with the old block.
                        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                    }
                    if (level.getBlockState(pos).getBlock() != wanted.getBlock()) {
                        level.setBlock(pos, wanted, 3);
                    }
                    for (int i = 1; i < lines.length; i++) {
                        if (lines[i].startsWith("be\t")) {
                            // As a chunk loads it: the data first, then the level and onLoad, which
                            // a mod builds its runtime state in from that data (measured: a placed
                            // Sophisticated Backpack, loaded after onLoad, kept an empty wrapper).
                            loadKept(level, pos, lines[i].substring(3));
                        }
                    }
                    BLOCKS.put(id, pos);
                    // Where a player stands to click it: the menu's own reach check holds.
                    player.setPos(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                    yield state(player, LiveMenus.clickBlock(player, pos));
                }
                case "break" -> {
                    // break TAB uuid TAB name TAB blockId TAB x TAB y TAB z, then inv lines (the held
                    // stack, the tool, in slot 0) and an optional "be TAB snbt" line: the player
                    // breaks a mod block of Paper's world, by the block's own code as a server's
                    // game mode does. What it spilled comes back as "spill TAB stack" lines.
                    SessionServerPlayer player = LiveMenus.session(id, head[2]);
                    player.closeContainer();
                    readInventory(player, lines);
                    net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                            Integer.parseInt(head[4]), Integer.parseInt(head[5]), Integer.parseInt(head[6]));
                    FacadeServerLevel level = LiveMenus.level();
                    level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(pos, net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                            net.minecraft.resources.ResourceLocation.parse(head[3])).defaultBlockState(), 3);
                    for (int i = 1; i < lines.length; i++) {
                        if (lines[i].startsWith("be\t")) {
                            loadKept(level, pos, lines[i].substring(3));
                        }
                    }
                    player.setPos(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 1.5);
                    FacadeServerLevel.takeSpilled();
                    net.minecraft.world.level.block.state.BlockState st = level.getBlockState(pos);
                    net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
                    net.minecraft.world.item.ItemStack tool = player.getMainHandItem();
                    net.minecraft.world.item.ItemStack used = tool.copy();
                    boolean canHarvest = st.canHarvestBlock(level, pos, player);
                    st = st.getBlock().playerWillDestroy(level, pos, st, player);
                    boolean removed = st.onDestroyedByPlayer(level, pos, player, canHarvest, level.getFluidState(pos));
                    if (removed) {
                        st.getBlock().destroy(level, pos, st);
                        tool.mineBlock(level, st, pos, player);
                        if (canHarvest) {
                            st.getBlock().playerDestroy(level, player, pos, st, be, used);
                        }
                    }
                    StringBuilder out = new StringBuilder(state(player, null));
                    out.append("broken\t").append(removed).append('\n');
                    for (net.minecraft.world.item.ItemStack spilled : FacadeServerLevel.takeSpilled()) {
                        out.append("spill\t").append(encode(spilled)).append('\n');
                    }
                    level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                    yield out.toString();
                }
                case "use-on" -> {
                    // use-on TAB uuid TAB name TAB x TAB y TAB z TAB face TAB clickedBlock TAB sneaking, then
                    // inv lines (the held stack in slot 0): a right click with a mod item on a block
                    // in Paper's world, done by the item's own code as a client's click is. The
                    // clicked block stands in the facade as itself when it is a mod block, as stone
                    // otherwise. A block the item placed is reported (id, position, its block
                    // entity's data) for Paper to put down; a menu it opened is the answer's menu.
                    SessionServerPlayer player = LiveMenus.session(id, head[2]);
                    player.closeContainer();
                    readInventory(player, lines);
                    net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                            Integer.parseInt(head[3]), Integer.parseInt(head[4]), Integer.parseInt(head[5]));
                    net.minecraft.core.Direction face = net.minecraft.core.Direction.byName(head[6]);
                    if (face == null) {
                        face = net.minecraft.core.Direction.UP;
                    }
                    FacadeServerLevel level = LiveMenus.level();
                    net.minecraft.world.level.block.state.BlockState stand = head[7].equals("-")
                            ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()
                            : net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                                    net.minecraft.resources.ResourceLocation.parse(head[7])).defaultBlockState();
                    net.minecraft.core.BlockPos into = pos.relative(face);
                    level.setBlock(pos, stand, 3);
                    level.setBlock(into, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                    // Out of the way of what it places, facing the clicked face.
                    net.minecraft.core.BlockPos stand_at = into.relative(face, 2);
                    player.setPos(stand_at.getX() + 0.5, stand_at.getY(), stand_at.getZ() + 0.5);
                    player.setShiftKeyDown(head.length > 8 && head[8].equals("1"));
                    BLOCKS.put(id, pos);
                    net.minecraft.world.item.ItemStack stack = player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND);
                    net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                            net.minecraft.world.phys.Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false);
                    net.minecraft.world.InteractionResult r = stack.useOn(new net.minecraft.world.item.context.UseOnContext(
                            player, net.minecraft.world.InteractionHand.MAIN_HAND, hit));
                    if (!r.consumesAction() && player.opened() == null) {
                        stack.use(level, player, net.minecraft.world.InteractionHand.MAIN_HAND);
                    }
                    player.setShiftKeyDown(false);
                    StringBuilder out = new StringBuilder(state(player, player.opened()));
                    for (net.minecraft.core.BlockPos p : new net.minecraft.core.BlockPos[]{into, pos}) {
                        net.minecraft.world.level.block.state.BlockState now = level.getBlockState(p);
                        boolean changed = p.equals(into) ? !now.isAir() : now != stand;
                        if (!changed || now.isAir()) {
                            continue;
                        }
                        out.append("placed\t").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(now.getBlock()))
                                .append('\t').append(p.getX()).append('\t').append(p.getY()).append('\t').append(p.getZ()).append('\n');
                        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(p);
                        if (be != null) {
                            out.append("placedbe\t").append(be.saveWithFullMetadata(level.registryAccess())).append('\n');
                        }
                        break;
                    }
                    if (player.opened() == null) {
                        // Paper's world keeps what was placed; the facade forgets it.
                        level.setBlock(into, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                        BLOCKS.remove(id);
                    }
                    yield out.toString();
                }
                case "payload" -> {
                    // payload TAB uuid TAB class TAB value;value...: a record payload a real
                    // client's press sent, rebuilt from its plain values, to the mod's handler.
                    SessionServerPlayer player = LiveMenus.session(id, "cimlive");
                    java.util.List<String> values = head.length > 3 && !head[3].isEmpty()
                            ? java.util.List.of(head[3].split(";")) : java.util.List.of();
                    String pressed = LivePayloads.record(player, head[2], values);
                    AbstractContainerMenu menu = player.opened();
                    if (menu != null) {
                        menu.broadcastChanges();
                    }
                    yield "pressed\t" + pressed + "\n" + state(player, menu);
                }
                case "vanilla" -> {
                    // vanilla TAB uuid TAB kind TAB n: a vanilla menu packet a screen's button sends
                    // (button: a menu button id; trade: a merchant offer), through the server's own
                    // packet handler, as the real client's press reaches it.
                    SessionServerPlayer player = LiveMenus.session(id, "cimlive");
                    AbstractContainerMenu menu = player.opened();
                    if (menu == null) {
                        yield "error\tno open menu";
                    }
                    int n = Integer.parseInt(head[3]);
                    if (head[2].equals("button")) {
                        player.connection.handleContainerButtonClick(
                                new net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket(menu.containerId, n));
                    } else if (head[2].equals("trade")) {
                        player.connection.handleSelectTrade(new net.minecraft.network.protocol.game.ServerboundSelectTradePacket(n));
                    } else {
                        yield "error\tunknown vanilla press " + head[2];
                    }
                    menu.broadcastChanges();
                    yield "pressed\tvanilla:" + head[2] + "#" + n + "\n" + state(player, player.opened());
                }
                case "state" -> {
                    // What the open menu holds now, after ticks moved it.
                    SessionServerPlayer player = LiveMenus.session(id, "cimlive");
                    yield state(player, player.opened());
                }
                case "widgets" -> {
                    // widgets TAB uuid, then one line per widget: ordinal TAB active-expr TAB visible-expr.
                    // Answers each widget's live state on the open menu: ordinal TAB active TAB visible.
                    SessionServerPlayer player = LiveMenus.session(id, "cimlive");
                    net.minecraft.world.inventory.AbstractContainerMenu menu = player.opened();
                    net.minecraft.core.BlockPos pos = BLOCKS.get(id);
                    Object be = pos == null ? null : LiveMenus.level().getBlockEntity(pos);
                    StringBuilder out = new StringBuilder();
                    for (int k = 1; k < lines.length && menu != null; k++) {
                        String[] w = lines[k].split("\t", -1);
                        if (w.length < 3) {
                            continue;
                        }
                        Boolean active = w[1].isEmpty() || w[1].equals("null") ? Boolean.TRUE : LivePredicates.eval(w[1], menu, be);
                        Boolean visible = w[2].isEmpty() || w[2].equals("null") ? Boolean.TRUE : LivePredicates.eval(w[2], menu, be);
                        out.append("widget\t").append(w[0]).append('\t').append(active).append('\t').append(visible).append('\n');
                    }
                    yield out.toString();
                }
                case "open-use" -> {
                    // open-use TAB uuid TAB name TAB opener TAB x TAB y TAB z: the walk's proven opener
                    // done in the facade, as the walk did it on the real server:
                    //   place+item:I        I used on a block beneath, then what it placed clicked;
                    //   use+item:I@B        B put down, then I used on it (settle+use the same).
                    SessionServerPlayer player = LiveMenus.session(id, head[2]);
                    player.closeContainer();
                    readInventory(player, lines);
                    String opener = head[3];
                    net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                            Integer.parseInt(head[4]), Integer.parseInt(head[5]), Integer.parseInt(head[6]));
                    FacadeServerLevel level = LiveMenus.level();
                    level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                    player.setPos(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 2.5);
                    BLOCKS.put(id, pos);
                    if (opener.startsWith("place+item:")) {
                        // place+item:I#C: the item with the components C the walk's player held
                        // (an item a mod wants tuned before it places, Create's factory gauge).
                        String itemSpec = opener.substring("place+item:".length());
                        String components = null;
                        if (itemSpec.contains("#")) {
                            components = itemSpec.substring(itemSpec.indexOf('#') + 1);
                            itemSpec = itemSpec.substring(0, itemSpec.indexOf('#'));
                        }
                        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                                        net.minecraft.resources.ResourceLocation.parse(itemSpec)));
                        if (components != null) {
                            try {
                                stack.applyComponents(net.minecraft.core.component.DataComponentPatch.CODEC.parse(
                                        level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),
                                        net.minecraft.nbt.TagParser.parseTag(components)).getOrThrow());
                            } catch (Exception e) {
                                BLOCKS.remove(id);
                                yield "error\tcomponents of " + itemSpec + ": " + e;
                            }
                        }
                        net.minecraft.core.BlockPos below = pos.below();
                        level.setBlock(below, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
                        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                        stack.useOn(new net.minecraft.world.item.context.UseOnContext(player,
                                net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.phys.BlockHitResult(
                                net.minecraft.world.phys.Vec3.atCenterOf(below).relative(net.minecraft.core.Direction.UP, 0.5),
                                net.minecraft.core.Direction.UP, below, false)));
                        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                                net.minecraft.world.item.ItemStack.EMPTY);
                        // What it placed is clicked next: an entity it hung (Create's blueprint)
                        // first, else the block.
                        for (net.minecraft.world.entity.Entity made : java.util.List.copyOf(level.store().placedEntities())) {
                            if (player.opened() != null) {
                                break;
                            }
                            if (!made.interactAt(player, net.minecraft.world.phys.Vec3.ZERO,
                                    net.minecraft.world.InteractionHand.MAIN_HAND).consumesAction()) {
                                made.interact(player, net.minecraft.world.InteractionHand.MAIN_HAND);
                            }
                        }
                        if (player.opened() == null) {
                            LiveMenus.clickBlock(player, pos);
                        }
                        if (player.opened() == null) {
                            // A click the mod takes in its own right-click handler (Create's value
                            // boxes on a factory gauge): the server's game mode, which posts the
                            // event first, over each quarter of the placed block's floor.
                            double[] at = {0.25, 0.75};
                            for (double dx : at) {
                                for (double dz : at) {
                                    if (player.opened() != null) {
                                        break;
                                    }
                                    player.gameMode.useItemOn(player, level, net.minecraft.world.item.ItemStack.EMPTY,
                                            net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.phys.BlockHitResult(
                                                    new net.minecraft.world.phys.Vec3(pos.getX() + dx, pos.getY() + 0.06, pos.getZ() + dz),
                                                    net.minecraft.core.Direction.UP, pos, false));
                                }
                            }
                        }
                        yield state(player, player.opened());
                    }
                    if (opener.startsWith("entity:")) {
                        // A mob the walk spawned and clicked (Supplementaries' red merchant): made
                        // in the facade beside the player, and clicked the way a client's click is.
                        net.minecraft.world.entity.EntityType<?> type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                .get(net.minecraft.resources.ResourceLocation.parse(opener.substring("entity:".length())));
                        net.minecraft.world.entity.Entity made = type.create(level);
                        if (made == null) {
                            BLOCKS.remove(id);
                            yield "error\tno entity " + opener;
                        }
                        // As the walk spawns it: placed, added, one tick of its own.
                        made.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0f, 0f);
                        level.addFreshEntity(made);
                        made.tick();
                        if (made.isRemoved()) {
                            BLOCKS.remove(id);
                            yield "error\tthe entity removed itself: " + opener;
                        }
                        if (!made.interactAt(player, net.minecraft.world.phys.Vec3.ZERO,
                                net.minecraft.world.InteractionHand.MAIN_HAND).consumesAction()) {
                            made.interact(player, net.minecraft.world.InteractionHand.MAIN_HAND);
                        }
                        yield state(player, player.opened());
                    }
                    String plain = opener.replace("settle+", "");
                    if (plain.startsWith("use+item:")) {
                        String[] pair = plain.substring("use+item:".length()).split("@", 2);
                        level.setBlock(pos, net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                                net.minecraft.resources.ResourceLocation.parse(pair[1])).defaultBlockState(), 3);
                        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                                        net.minecraft.resources.ResourceLocation.parse(pair[0])));
                        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                        net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                                net.minecraft.world.phys.Vec3.atCenterOf(pos).relative(net.minecraft.core.Direction.UP, 0.5),
                                net.minecraft.core.Direction.UP, pos, false);
                        net.minecraft.world.level.block.state.BlockState st = level.getBlockState(pos);
                        if (opener.startsWith("settle+")) {
                            // As the walk does it: the server's own right-click handling (NeoForge's
                            // onItemUseFirst first: AE2's network tool on a node).
                            player.gameMode.useItemOn(player, level, stack, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
                            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                                    net.minecraft.world.item.ItemStack.EMPTY);
                            yield state(player, player.opened());
                        }
                        net.minecraft.world.ItemInteractionResult r = st.useItemOn(stack, level, player,
                                net.minecraft.world.InteractionHand.MAIN_HAND, hit);
                        if (r == net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
                            if (!st.useWithoutItem(level, player, hit).consumesAction() && player.opened() == null) {
                                stack.useOn(new net.minecraft.world.item.context.UseOnContext(player,
                                        net.minecraft.world.InteractionHand.MAIN_HAND, hit));
                            }
                        } else if (!r.consumesAction()) {
                            stack.useOn(new net.minecraft.world.item.context.UseOnContext(player,
                                    net.minecraft.world.InteractionHand.MAIN_HAND, hit));
                        }
                        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                                net.minecraft.world.item.ItemStack.EMPTY);
                        // What the use put into the world is clicked next, as the walk's player
                        // clicks it (a robit set down on its chargepad opens its own menu).
                        for (net.minecraft.world.entity.Entity made : java.util.List.copyOf(level.store().placedEntities())) {
                            if (player.opened() != null) {
                                break;
                            }
                            if (!made.interactAt(player, net.minecraft.world.phys.Vec3.ZERO,
                                    net.minecraft.world.InteractionHand.MAIN_HAND).consumesAction()) {
                                made.interact(player, net.minecraft.world.InteractionHand.MAIN_HAND);
                            }
                        }
                        yield state(player, player.opened());
                    }
                    BLOCKS.remove(id);
                    yield "error\tunsupported opener " + opener;
                }
                case "click" -> {
                    SessionServerPlayer player = LiveMenus.session(id, "cimlive");
                    LiveMenus.click(player, Integer.parseInt(head[2]), Integer.parseInt(head[3]),
                            ClickType.valueOf(head[4]));
                    yield state(player, player.opened());
                }
                case "button" -> {
                    // button TAB uuid TAB payloadClass TAB constructorDesc TAB arg...
                    SessionServerPlayer player = LiveMenus.session(id, "cimlive");
                    java.util.List<String> args = new java.util.ArrayList<>(java.util.Arrays.asList(head).subList(4, head.length));
                    String pressed = LivePayloads.press(player, head[2], head[3], args);
                    yield "pressed\t" + pressed + "\n" + state(player, player.opened());
                }
                case "close" -> {
                    SessionServerPlayer player = LiveMenus.session(id, "cimlive");
                    player.closeContainer();
                    String out = state(player, null);
                    net.minecraft.core.BlockPos pos = BLOCKS.remove(id);
                    if (pos != null) {
                        // The block entity's data after the menu closed: Paper keeps it with the block.
                        net.minecraft.world.level.block.entity.BlockEntity be = LiveMenus.level().getBlockEntity(pos);
                        if (be != null) {
                            out += "be\t" + be.saveWithoutMetadata(LiveMenus.level().registryAccess()) + "\n";
                        }
                    }
                    LiveMenus.end(id);
                    yield out;
                }
                default -> "error\tunknown command " + head[0];
            };
        } catch (Throwable t) {
            return "error\t" + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /**
     * One server tick for the block entities of open menus, driven by Paper's own tick: a
     * machine works, a condenser destroys what was put into its trash slot, at the rate the
     * real server runs them. Nothing else in the facade ticks.
     */
    private static int ticks;

    /** Paper's ticks since the layer's live menus started: the facade server's tick count. */
    static int ticks() {
        return ticks;
    }

    /**
     * The placed mod block entities of Paper's loaded chunks: each ticks every server tick
     * while it is adopted, with or without a menu open. Keyed by position, valued by block id.
     * {@link #tickList} is the same set as an array, rebuilt on change, so the tick allocates
     * nothing for it.
     */
    private static final java.util.Map<net.minecraft.core.BlockPos, String> TICKING = new java.util.HashMap<>();
    private static net.minecraft.core.BlockPos[] tickList = new net.minecraft.core.BlockPos[0];

    private static void refreshTickList() {
        tickList = TICKING.keySet().toArray(new net.minecraft.core.BlockPos[0]);
    }

    private static net.minecraft.core.BlockPos pos(String[] head, int at) {
        return new net.minecraft.core.BlockPos(Integer.parseInt(head[at]), Integer.parseInt(head[at + 1]),
                Integer.parseInt(head[at + 2]));
    }

    /** adopt TAB x TAB y TAB z TAB blockId, then an optional "be TAB snbt" line: a placed block starts ticking. */
    private static String adopt(String[] head, String[] lines) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.core.BlockPos pos = pos(head, 1);
        FacadeServerLevel level = LiveMenus.level();
        net.minecraft.world.level.block.state.BlockState wanted =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                        net.minecraft.resources.ResourceLocation.parse(head[4])).defaultBlockState();
        // The registry answers air for an id it does not know: a block the layer lacks never ticks.
        if (wanted.isAir() && !head[4].equals("minecraft:air")) {
            return "error\tunknown block " + head[4];
        }
        if (level.getBlockState(pos).getBlock() != wanted.getBlock()) {
            level.setBlock(pos, wanted, 3);
        }
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].startsWith("be\t")) {
                loadKept(level, pos, lines[i].substring(3));
            }
        }
        TICKING.put(pos, head[4]);
        refreshTickList();
        return "adopted\t" + TICKING.size() + "\n";
    }

    /**
     * release TAB x TAB y TAB z: the block leaves the ticking set (its chunk unloaded, or it was
     * broken). Its data goes back as a "be" line, unless a menu holds it open.
     */
    private static String release(String[] head) {
        net.minecraft.core.BlockPos pos = pos(head, 1);
        FacadeServerLevel level = LiveMenus.level();
        StringBuilder out = new StringBuilder(save(head));
        if (TICKING.remove(pos) != null) {
            refreshTickList();
        }
        if (!BLOCKS.containsValue(pos)) {
            // removeBlockEntity only: no onRemove, so nothing a mod drops is spilled into the facade.
            level.removeBlockEntity(pos);
        }
        return out.append("released\t").append(TICKING.size()).append('\n').toString();
    }

    /** A block id's default state, or an error for an id the layer does not know (the registry would answer air). */
    private static net.minecraft.world.level.block.state.BlockState blockState(String id) {
        net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.parse(id);
        if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(rl)) {
            throw new IllegalArgumentException("unknown block " + id);
        }
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(rl).defaultBlockState();
    }

    /**
     * blockinfo TAB blockId: "block TAB hardness TAB requiresTool" (1 or 0). The hardness is the
     * block's destroy time as the game has it (-1 unbreakable, 0 instant); cached on Paper per id.
     */
    private static String blockInfo(String[] head) {
        net.minecraft.world.level.block.state.BlockState st = blockState(head[1]);
        return "block\t" + st.getDestroySpeed(LiveMenus.level(), net.minecraft.core.BlockPos.ZERO) + "\t"
                + (st.requiresCorrectToolForDrops() ? 1 : 0) + "\n";
    }

    /**
     * digspeed TAB modBlockId TAB refBlockId TAB heldStack: "digspeed TAB speed TAB correct TAB refSpeed",
     * the held stack's destroy speed on the mod block, whether it is a correct tool for the mod block's
     * drops, and its destroy speed on the reference block. The held stack is its JSON ("-" for none).
     */
    private static String digSpeed(String[] head) {
        ItemStack held = decode(head[3]);
        net.minecraft.world.level.block.state.BlockState mod = blockState(head[1]);
        net.minecraft.world.level.block.state.BlockState ref = blockState(head[2]);
        return "digspeed\t" + held.getDestroySpeed(mod) + "\t" + (held.isCorrectToolForDrops(mod) ? 1 : 0) + "\t"
                + held.getDestroySpeed(ref) + "\n";
    }

    /** save TAB x TAB y TAB z: the block entity's data as it is now, for Paper to keep. */
    private static String save(String[] head) {
        net.minecraft.world.level.block.entity.BlockEntity be = LiveMenus.level().getBlockEntity(pos(head, 1));
        return be == null ? "" : "be\t" + be.saveWithoutMetadata(LiveMenus.level().registryAccess()) + "\n";
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String tick() {
        ticks++;
        FacadeServerLevel level = LiveMenus.level();
        int ticked = 0;
        SPILLS.setLength(0);
        StringBuilder spills = SPILLS;
        // Menu-open block entities first, each once however many sessions hold it open; a placed one
        // that a menu also holds ticks once, here.
        int held = 0;
        for (net.minecraft.core.BlockPos pos : BLOCKS.values()) {
            if (!earlierHolder(pos, held++)) {
                if (tickAt(level, pos)) {
                    ticked++;
                }
                drainSpills(spills, pos);
            }
        }
        for (net.minecraft.core.BlockPos pos : tickList) {
            if (!BLOCKS.containsValue(pos)) {
                if (tickAt(level, pos)) {
                    ticked++;
                }
                drainSpills(spills, pos);
            }
        }
        for (SessionServerPlayer p : LiveMenus.openSessions()) {
            if (p.opened() != null) {
                p.opened().broadcastChanges();
            }
        }
        return "ticked\t" + ticked + spills;
    }

    /** What the tick spilled, reused across ticks: the common tick allocates no buffer. */
    private static final StringBuilder SPILLS = new StringBuilder();

    /** True when one of the first {@code n} menu-held positions is {@code pos}: it already ticked. */
    private static boolean earlierHolder(net.minecraft.core.BlockPos pos, int n) {
        int i = 0;
        for (net.minecraft.core.BlockPos earlier : BLOCKS.values()) {
            if (i++ >= n) {
                break;
            }
            if (earlier.equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /** What a tick spilled at {@code pos} goes out as "spill" lines, for Paper to drop there. */
    private static void drainSpills(StringBuilder out, net.minecraft.core.BlockPos pos) {
        if (!FacadeServerLevel.hasSpilled()) {
            return;
        }
        for (net.minecraft.world.item.ItemStack stack : FacadeServerLevel.takeSpilled()) {
            out.append("\nspill\t").append(pos.getX()).append('\t').append(pos.getY()).append('\t')
                    .append(pos.getZ()).append('\t').append(encode(stack));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean tickAt(FacadeServerLevel level, net.minecraft.core.BlockPos pos) {
        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return false;
        }
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        net.minecraft.world.level.block.entity.BlockEntityTicker ticker =
                state.getTicker(level, (net.minecraft.world.level.block.entity.BlockEntityType) be.getType());
        if (ticker == null) {
            return false;
        }
        try {
            ticker.tick(level, pos, state, be);
            return true;
        } catch (Throwable t) {
            // A machine that cannot run here stops ticking; its menu stays as it is.
            return false;
        }
    }

    private static void readInventory(SessionServerPlayer player, String[] lines) {
        player.getInventory().clearContent();
        for (int i = 1; i < lines.length; i++) {
            String[] p = lines[i].split("\t", 3);
            if (p.length == 3 && p[0].equals("inv")) {
                player.getInventory().setItem(Integer.parseInt(p[1]), decode(p[2]));
            }
        }
    }

    /**
     * A block entity's kept data loaded as a chunk loads it: the data first, then the level
     * and onLoad, which a mod builds its runtime state in from that data (a placed
     * Sophisticated Backpack, loaded after onLoad, kept an empty wrapper). Data saved without
     * its metadata names no type: the block's own block entity type is it.
     */
    private static void loadKept(FacadeServerLevel level, net.minecraft.core.BlockPos pos, String snbt)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.TagParser.parseTag(snbt);
        net.minecraft.world.level.block.entity.BlockEntity existing = level.getBlockEntity(pos);
        if (!tag.contains("id") && existing != null) {
            tag.putString("id", String.valueOf(net.minecraft.world.level.block.entity.BlockEntityType.getKey(existing.getType())));
        }
        net.minecraft.world.level.block.entity.BlockEntity loaded =
                net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos, level.getBlockState(pos), tag,
                        level.registryAccess());
        if (loaded != null) {
            level.removeBlockEntity(pos);
            level.setBlockEntity(loaded);
            loaded.onLoad();
        } else if (existing != null) {
            existing.loadWithComponents(tag, level.registryAccess());
        }
    }

    private static String state(SessionServerPlayer player, AbstractContainerMenu menu) {
        StringBuilder out = new StringBuilder();
        if (menu == null) {
            out.append("none\n");
        } else {
            String type;
            try {
                type = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(menu.getType()));
            } catch (UnsupportedOperationException none) {
                type = "null";
            }
            out.append("menu\t").append(type).append('\t').append(menu.getClass().getName()).append('\n');
            int n = 0;
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                if (!slot.isActive()) {
                    continue;   // the screen does not draw it, the capture does not list it
                }
                out.append("slot\t").append(n++).append('\t').append(i).append('\t')
                        .append(slot.container == player.getInventory() ? "player" : "menu").append('\t')
                        .append(slot.getContainerSlot()).append('\t').append(encode(slot.getItem()))
                        .append('\t').append(slot.x).append('\t').append(slot.y)
                        // A slot nobody takes from is the mod's display (Create's attribute summary).
                        .append('\t').append(slot.mayPickup(player) ? 1 : 0).append('\n');
            }
            out.append("carried\t").append(encode(menu.getCarried())).append('\n');
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                out.append("inv\t").append(i).append('\t').append(encode(stack)).append('\n');
            }
        }
        for (ItemStack stack : player.takeDropped()) {
            out.append("drop\t").append(encode(stack)).append('\n');
        }
        return out.toString();
    }

    static String encode(ItemStack stack) {
        if (stack.isEmpty()) {
            return "-";
        }
        RegistryOps<JsonElement> ops = LiveMenus.level().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        return ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).getOrThrow().toString();
    }

    static ItemStack decode(String json) {
        if (json.equals("-")) {
            return ItemStack.EMPTY;
        }
        RegistryOps<JsonElement> ops = LiveMenus.level().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        return ItemStack.OPTIONAL_CODEC.parse(ops, JsonParser.parseString(json)).getOrThrow();
    }
}
