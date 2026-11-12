package dev.cim.scenemod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SpecializedOpeners {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpecializedOpeners.class);

    public static final Set<String> MENUS = Set.of(
            // Mekanism robits
            "mekanism:crafting_robit",
            "mekanism:inventory_robit",
            "mekanism:repair_robit",
            "mekanism:smelting_robit",
            // Mekanism tile buttons
            "mekanism:digital_miner_config",
            "mekanism:boiler_stats",
            // Mekanism multiblocks
            "mekanism:thermoelectric_boiler",
            "mekanism:thermal_evaporation_controller",
            "mekanism:sps_casing",
            // Mekanism items
            "mekanism:seismic_reader",
            "mekanism:qio_frequency_select_item",
            // Sophisticated Backpacks
            "sophisticatedbackpacks:settings",
            // Create
            "create:stock_keeper_category",
            "create:stock_keeper_request",
            "create:factory_panel_set_item",
            // AE2
            "ae2:basic_cell_chest",
            "ae2:craftamount",
            "ae2:craftconfirm",
            "ae2:craftingcpu",
            "ae2:networkstatus",
            "ae2:set_stock_amount"
    );

    /**
     * The last placement said as a plain opener ({@code place+item:ID#components}) the layer
     * can replay with the mod's own code, or null.
     */
    public static String replay;

    private SpecializedOpeners() {
    }

    public static boolean isSpecialized(String menuId) {
        return MENUS.contains(menuId);
    }

    public static void place(ServerLevel level, ServerPlayer player, BlockPos rig, String menuId,
                             java.util.function.Consumer<Entity> onEntitySpawned) {
        ClassLoader cl = Block.class.getClassLoader();
        try {
            switch (menuId) {
                case "mekanism:crafting_robit", "mekanism:inventory_robit",
                     "mekanism:repair_robit", "mekanism:smelting_robit" -> {
                    Entity robit = CimSceneMod.placeRobit(level, player, rig.below());
                    if (robit != null && onEntitySpawned != null) {
                        onEntitySpawned.accept(robit);
                    }
                }
                case "mekanism:digital_miner_config" ->
                    setBlock(level, rig, "mekanism:digital_miner");
                case "mekanism:boiler_stats", "mekanism:thermoelectric_boiler" -> {
                    setBlock(level, rig, "mekanism:boiler_casing");
                    BlockEntity be = level.getBlockEntity(rig);
                    if (be != null) {
                        try {
                            Method getMultiblock = be.getClass().getMethod("getMultiblock");
                            Object mb = getMultiblock.invoke(be);
                            mb.getClass().getMethod("setFormedForce", boolean.class).invoke(mb, true);
                            try {
                                mb.getClass().getField("renderLocation").set(mb, rig);
                            } catch (Throwable ignored) {}
                            try {
                                mb.getClass().getField("upperRenderLocation").set(mb, rig);
                            } catch (Throwable ignored) {}
                        } catch (Throwable ignored) {}
                    }
                }
                case "mekanism:thermal_evaporation_controller" -> {
                    setBlock(level, rig, "mekanism:thermal_evaporation_controller");
                    BlockEntity be = level.getBlockEntity(rig);
                    if (be != null) {
                        try {
                            Method getMultiblock = be.getClass().getMethod("getMultiblock");
                            Object mb = getMultiblock.invoke(be);
                            mb.getClass().getMethod("setFormedForce", boolean.class).invoke(mb, true);
                            try {
                                mb.getClass().getField("renderLocation").set(mb, rig);
                            } catch (Throwable ignored) {}
                        } catch (Throwable ignored) {}
                    }
                }
                case "mekanism:sps_casing" -> {
                    setBlock(level, rig, "mekanism:sps_casing");
                    BlockEntity be = level.getBlockEntity(rig);
                    if (be != null) {
                        try {
                            Method getMultiblock = be.getClass().getMethod("getMultiblock");
                            Object mb = getMultiblock.invoke(be);
                            mb.getClass().getMethod("setFormedForce", boolean.class).invoke(mb, true);
                            try {
                                mb.getClass().getField("renderLocation").set(mb, rig);
                            } catch (Throwable ignored) {}
                        } catch (Throwable ignored) {}
                    }
                }
                case "mekanism:seismic_reader" -> {
                    player.setItemInHand(InteractionHand.MAIN_HAND,
                            new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:seismic_reader"))));
                    player.inventoryMenu.broadcastChanges();
                }
                case "mekanism:qio_frequency_select_item" -> {
                    player.setItemInHand(InteractionHand.MAIN_HAND,
                            new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:portable_qio_dashboard"))));
                    player.inventoryMenu.broadcastChanges();
                }
                case "sophisticatedbackpacks:settings" ->
                    setBlock(level, rig, "sophisticatedbackpacks:backpack");
                case "create:stock_keeper_category" ->
                    setBlock(level, rig, "create:stock_ticker");
                case "create:stock_keeper_request" -> {
                    // A stock keeper as a player builds one: a mob seated on a seat beside a
                    // stock ticker (Create's StockTickerInteractionHandler finds the ticker
                    // from the seated mob). The seat is Create's own sitDown.
                    setBlock(level, rig, "create:stock_ticker");
                    BlockPos seat = rig.north();
                    setBlock(level, seat, "create:white_seat");
                    Entity keeper = EntityType.VILLAGER.create(level);
                    if (keeper != null) {
                        keeper.moveTo(seat.getX() + 0.5, seat.getY(), seat.getZ() + 0.5, 180f, 0f);
                        if (keeper instanceof net.minecraft.world.entity.Mob mob) {
                            mob.setNoAi(true);
                        }
                        level.addFreshEntity(keeper);
                        Class.forName("com.simibubi.create.content.contraptions.actors.seat.SeatBlock", true, cl)
                                .getMethod("sitDown", net.minecraft.world.level.Level.class, BlockPos.class, Entity.class)
                                .invoke(null, level, seat, keeper);
                        if (onEntitySpawned != null) {
                            onEntitySpawned.accept(keeper);
                        }
                    }
                }
                case "create:factory_panel_set_item" -> {
                    // A factory gauge as a player places one: tuned to a logistics network
                    // first (Create refuses an untuned gauge), then used on a block's top.
                    setBlock(level, rig.below(), "minecraft:stone");
                    ItemStack gauge = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:factory_gauge")));
                    Class.forName("com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem", true, cl)
                            .getMethod("assignFrequency", ItemStack.class, Player.class, java.util.UUID.class)
                            .invoke(null, gauge, player, java.util.UUID.nameUUIDFromBytes("cimscene".getBytes()));
                    ItemStack held = player.getMainHandItem();
                    player.setItemInHand(InteractionHand.MAIN_HAND, gauge);
                    try {
                        gauge.useOn(new net.minecraft.world.item.context.UseOnContext(player, InteractionHand.MAIN_HAND,
                                new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(
                                        rig.getX() + 0.25, rig.getY(), rig.getZ() + 0.25),
                                        net.minecraft.core.Direction.UP, rig.below(), false)));
                    } finally {
                        player.setItemInHand(InteractionHand.MAIN_HAND, held);
                    }
                    LOGGER.info("[cimscene] factory gauge placed: {}", level.getBlockState(rig));
                    // What a player holds, said plainly, so another server can do the same: the
                    // tuned item placed, then what it placed right-clicked.
                    replay = "place+item:" + BuiltInRegistries.ITEM.getKey(gauge.getItem()) + "#"
                            + net.minecraft.core.component.DataComponentPatch.CODEC.encodeStart(
                                    level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),
                                    gauge.getComponentsPatch()).getOrThrow();
                }
                case "ae2:basic_cell_chest", "ae2:craftamount", "ae2:craftconfirm" ->
                    setBlock(level, rig, "ae2:chest");
                case "ae2:craftingcpu" ->
                    setBlock(level, rig, "ae2:crafting_unit");
                case "ae2:networkstatus" -> {
                    // A grid block for the network tool to read.
                    setBlock(level, rig, "ae2:controller");
                    player.setItemInHand(InteractionHand.MAIN_HAND,
                            new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("ae2:network_tool"))));
                    player.inventoryMenu.broadcastChanges();
                }
                case "ae2:set_stock_amount" ->
                    setBlock(level, rig, "ae2:interface");
                default -> { }
            }
        } catch (Throwable t) {
            LOGGER.info("[cimscene] specialized place failed for {}: {}", menuId, t.toString());
        }
    }

    private static void setBlock(ServerLevel level, BlockPos pos, String blockId) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockId));
        level.setBlock(pos, block.defaultBlockState(), 3);
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            be.onLoad();
        }
    }

    /**
     * Forces the vanilla entity-tracker visibility decision for one (entity, player)
     * pair and reports whether the player sees the entity afterwards. The tracker's
     * spawn packet normally rides its own flush, which the menu open payload does not
     * wait for; {@code TrackedEntity.updatePlayer} (public in 1.21.1, javap-verified
     * against the srg server jar) makes the same decision synchronously and sends the
     * spawn packet immediately when the entity is visible.
     */
    private static boolean forceTracked(ServerLevel level, Entity target, ServerPlayer player) {
        try {
            Object cache = level.getChunkSource();
            Object chunkMap = cache.getClass().getField("chunkMap").get(cache);
            Field entityMapField = chunkMap.getClass().getDeclaredField("entityMap");
            entityMapField.setAccessible(true);
            Object tracked = ((java.util.Map<?, ?>) entityMapField.get(chunkMap)).get(target.getId());
            if (tracked == null) {
                return false;
            }
            tracked.getClass().getMethod("updatePlayer", ServerPlayer.class).invoke(tracked, player);
            Field seenByField = tracked.getClass().getDeclaredField("seenBy");
            seenByField.setAccessible(true);
            for (Object connection : (Set<?>) seenByField.get(tracked)) {
                if (((net.minecraft.server.network.ServerPlayerConnection) connection).getPlayer() == player) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            LOGGER.info("[cimscene] robit opener tracking probe threw: {}", t.toString());
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean act(ServerLevel level, ServerPlayer player, BlockPos rig, String menuId, Entity entity) {
        ClassLoader cl = Block.class.getClassLoader();
        try {
            switch (menuId) {
                case "mekanism:crafting_robit", "mekanism:inventory_robit",
                     "mekanism:repair_robit", "mekanism:smelting_robit" -> {
                    Entity target = entity;
                    if (target == null) {
                        List<Entity> near = level.getEntities((Entity) null,
                                new net.minecraft.world.phys.AABB(rig).inflate(3),
                                e -> e.isAlive() && BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals("mekanism:robit"));
                        if (!near.isEmpty()) {
                            target = near.get(0);
                        }
                    }
                    if (target == null) {
                        return false;
                    }
                    // THE CLIENT-KILL GUARD (measured, gallery seed c769369d): the open
                    // payload's extra data carries the robit's entity id and the CLIENT
                    // resolves it in MekanismContainerType.getEntityFromBuf — an entity it
                    // has not tracked yet (the specialized fallback spawns and opens in the
                    // same tick, and the spawn packet normally rides the LATER tracker
                    // flush) throws IllegalStateException and NeoForge DISCONNECTS the
                    // client, killing the whole dump step. The open must reference an
                    // entity that is alive AND tracked by this player: force the native
                    // visibility decision now (updatePlayer sends the spawn immediately
                    // when visible), then refuse the open if the player still does not
                    // see it — a named honest row instead of a dead rig.
                    if (!target.isAlive()) {
                        LOGGER.info("[cimscene] robit opener: the nearby robit is not alive, refusing the open payload");
                        return false;
                    }
                    if (!forceTracked(level, target, player)) {
                        LOGGER.info("[cimscene] robit opener: entity {} is not tracked by the viewer yet — the open payload would disconnect the client, the page records honestly",
                                target.getId());
                        return false;
                    }
                    String btnName = switch (menuId) {
                        case "mekanism:crafting_robit" -> "ROBIT_CRAFTING";
                        case "mekanism:inventory_robit" -> "ROBIT_INVENTORY";
                        case "mekanism:repair_robit" -> "ROBIT_REPAIR";
                        case "mekanism:smelting_robit" -> "ROBIT_SMELTING";
                        default -> "";
                    };
                    Class<?> entityBtnClass = Class.forName(
                            "mekanism.common.network.to_server.button.PacketEntityButtonPress$ClickedEntityButton", true, cl);
                    Object btn = Enum.valueOf((Class<Enum>) entityBtnClass.asSubclass(Enum.class), btnName);
                    Method getProvider = entityBtnClass.getMethod("getProvider", Entity.class);
                    MenuProvider provider = (MenuProvider) getProvider.invoke(btn, target);
                    int entityId = target.getId();
                    player.openMenu(provider, buf -> buf.writeVarInt(entityId));
                    return true;
                }
                case "mekanism:digital_miner_config" -> {
                    Class<?> tileBtnClass = Class.forName(
                            "mekanism.common.network.to_server.button.PacketTileButtonPress$ClickedTileButton", true, cl);
                    Object btn = Enum.valueOf((Class<Enum>) tileBtnClass.asSubclass(Enum.class), "DIGITAL_MINER_CONFIG");
                    BlockEntity be = level.getBlockEntity(rig);
                    Method getProvider = tileBtnClass.getMethod("getProvider",
                            Class.forName("mekanism.common.tile.base.TileEntityMekanism", true, cl));
                    MenuProvider provider = (MenuProvider) getProvider.invoke(btn, be);
                    player.openMenu(provider, buf -> buf.writeBlockPos(rig));
                    return true;
                }
                case "mekanism:boiler_stats" -> {
                    BlockEntity be = level.getBlockEntity(rig);
                    Method getMultiblock = be.getClass().getMethod("getMultiblock");
                    Object mb = getMultiblock.invoke(be);
                    mb.getClass().getMethod("setFormedForce", boolean.class).invoke(mb, true);
                    try {
                        mb.getClass().getField("renderLocation").set(mb, rig);
                    } catch (Throwable ignored) {
                    }
                    try {
                        mb.getClass().getField("upperRenderLocation").set(mb, rig);
                    } catch (Throwable ignored) {
                    }

                    Class<?> tileBtnClass = Class.forName(
                            "mekanism.common.network.to_server.button.PacketTileButtonPress$ClickedTileButton", true, cl);
                    Object btn = Enum.valueOf((Class<Enum>) tileBtnClass.asSubclass(Enum.class), "TAB_STATS");
                    Method getProvider = tileBtnClass.getMethod("getProvider",
                            Class.forName("mekanism.common.tile.base.TileEntityMekanism", true, cl));
                    MenuProvider provider = (MenuProvider) getProvider.invoke(btn, be);
                    player.openMenu(provider, buf -> {
                        buf.writeBlockPos(rig);
                        try {
                            be.getClass().getMethod("encodeExtraContainerData",
                                    net.minecraft.network.RegistryFriendlyByteBuf.class).invoke(be, buf);
                        } catch (Throwable ignored) {}
                    });
                    return true;
                }
                case "mekanism:thermoelectric_boiler", "mekanism:thermal_evaporation_controller", "mekanism:sps_casing" -> {
                    BlockEntity be = level.getBlockEntity(rig);
                    Method getMultiblock = be.getClass().getMethod("getMultiblock");
                    Object mb = getMultiblock.invoke(be);
                    mb.getClass().getMethod("setFormedForce", boolean.class).invoke(mb, true);
                    try {
                        mb.getClass().getField("renderLocation").set(mb, rig);
                    } catch (Throwable ignored) {
                    }
                    try {
                        mb.getClass().getField("upperRenderLocation").set(mb, rig);
                    } catch (Throwable ignored) {
                    }
                    Method openGui = be.getClass().getMethod("openGui", Player.class);
                    openGui.invoke(be, player);
                    return true;
                }
                case "mekanism:seismic_reader" -> {
                    ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
                    if (stack.isEmpty()) {
                        stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:seismic_reader")));
                        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    }
                    player.inventoryMenu.broadcastChanges();
                    Class<?> reg = Class.forName("mekanism.common.registries.MekanismContainerTypes", true, cl);
                    Object ro = reg.getField("SEISMIC_READER").get(null);
                    ro.getClass().getMethod("tryOpenGui", ServerPlayer.class, InteractionHand.class, ItemStack.class)
                            .invoke(ro, player, InteractionHand.MAIN_HAND, stack);
                    return true;
                }
                case "mekanism:qio_frequency_select_item" -> {
                    ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
                    if (stack.isEmpty()) {
                        stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:portable_qio_dashboard")));
                        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    }
                    final ItemStack finalStack = stack;
                    Class<?> itemBtnClass = Class.forName(
                            "mekanism.common.network.to_server.button.PacketItemButtonPress$ClickedItemButton", true, cl);
                    Object btn = Enum.valueOf((Class<Enum>) itemBtnClass.asSubclass(Enum.class), "QIO_FREQUENCY_SELECT");
                    Method getProvider = itemBtnClass.getMethod("getProvider", ItemStack.class, InteractionHand.class);
                    MenuProvider provider = (MenuProvider) getProvider.invoke(btn, finalStack, InteractionHand.MAIN_HAND);
                    player.openMenu(provider, buf -> {
                        buf.writeEnum(InteractionHand.MAIN_HAND);
                        ItemStack.STREAM_CODEC.encode(buf, finalStack);
                    });
                    return true;
                }
                case "sophisticatedbackpacks:settings" -> {
                    Class<?> blockCtxClass = Class.forName(
                            "net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContext$Block", true, cl);
                    Object ctx = blockCtxClass.getConstructor(BlockPos.class).newInstance(rig);
                    Class<?> ctxClass = Class.forName(
                            "net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContext", true, cl);
                    Class<?> menuClass = Class.forName(
                            "net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackSettingsContainerMenu", true, cl);
                    Constructor<?> ctor = menuClass.getDeclaredConstructor(int.class, Player.class, ctxClass);
                    ctor.setAccessible(true);
                    MenuProvider provider = new SimpleMenuProvider(
                            (id, inv, p) -> {
                                try {
                                    return (AbstractContainerMenu) ctor.newInstance(id, p, ctx);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            },
                            Component.literal("Settings")
                    );
                    Method toBuf = ctxClass.getMethod("toBuffer", FriendlyByteBuf.class, Player.class);
                    player.openMenu(provider, buf -> {
                        try {
                            toBuf.invoke(ctx, buf, player);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                    return true;
                }
                case "create:stock_keeper_category" -> {
                    BlockEntity be = level.getBlockEntity(rig);
                    Class<?> provClass = Class.forName(
                            "com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity$CategoryMenuProvider", true, cl);
                    MenuProvider prov = (MenuProvider) provClass.getConstructor(be.getClass()).newInstance(be);
                    player.openMenu(prov, buf -> buf.writeBlockPos(rig));
                    return true;
                }
                case "create:stock_keeper_request" -> {
                    // The player right-clicks the seated keeper: the same events the server's
                    // packet handler fires (interact-at first, then interact).
                    Entity keeper = entity;
                    if (keeper == null || !keeper.isAlive()) {
                        List<Entity> near = level.getEntities((Entity) null,
                                new net.minecraft.world.phys.AABB(rig).inflate(3),
                                e -> e.isAlive() && e instanceof net.minecraft.world.entity.LivingEntity
                                        && !(e instanceof Player) && e.isPassenger());
                        keeper = near.isEmpty() ? null : near.get(0);
                    }
                    if (keeper == null) {
                        LOGGER.info("[cimscene] stock keeper opener: no seated keeper near the ticker");
                        return false;
                    }
                    forceTracked(level, keeper.getRootVehicle(), player);
                    forceTracked(level, keeper, player);
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(0, keeper.getBbHeight() / 2, 0);
                    net.minecraft.world.InteractionResult result =
                            net.neoforged.neoforge.common.CommonHooks.onInteractEntityAt(player, keeper, at, InteractionHand.MAIN_HAND);
                    if (result == null) {
                        result = keeper.interactAt(player, at, InteractionHand.MAIN_HAND);
                    }
                    if (!result.consumesAction()) {
                        result = player.interactOn(keeper, InteractionHand.MAIN_HAND);
                    }
                    LOGGER.info("[cimscene] stock keeper opener: interacted with {} on {} -> {}",
                            keeper.getType(), keeper.getRootVehicle().getType(), result);
                    return result.consumesAction();
                }
                case "create:factory_panel_set_item" -> {
                    BlockEntity be = level.getBlockEntity(rig);
                    if (be == null) {
                        place(level, player, rig, menuId, null);
                        be = level.getBlockEntity(rig);
                    }
                    if (be == null) {
                        LOGGER.info("[cimscene] factory panel opener: no gauge at the rig ({})", level.getBlockState(rig));
                        return false;
                    }
                    // The gauge the placement switched on: a right-click with an empty hand on a
                    // gauge with no item set opens the set-item menu (the panel's own
                    // onShortInteract, as Create's value-box input handler calls it).
                    Map<?, ?> panels = (Map<?, ?>) be.getClass().getField("panels").get(be);
                    Object beh = null;
                    for (Object each : panels.values()) {
                        if ((boolean) each.getClass().getMethod("isActive").invoke(each)) {
                            beh = each;
                            break;
                        }
                    }
                    if (beh == null) {
                        LOGGER.info("[cimscene] factory panel opener: no active gauge among {}", panels.keySet());
                        return false;
                    }
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    beh.getClass().getMethod("onShortInteract", Player.class, InteractionHand.class,
                                    net.minecraft.core.Direction.class, net.minecraft.world.phys.BlockHitResult.class)
                            .invoke(beh, player, InteractionHand.MAIN_HAND, net.minecraft.core.Direction.UP,
                                    new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(rig),
                                            net.minecraft.core.Direction.UP, rig, false));
                    return true;
                }
                case "ae2:basic_cell_chest", "ae2:craftamount", "ae2:craftconfirm",
                     "ae2:craftingcpu", "ae2:networkstatus", "ae2:set_stock_amount" -> {
                    Class<?> menuOpenerClass = Class.forName("appeng.menu.MenuOpener", true, cl);
                    Class<?> locatorClass = Class.forName("appeng.menu.locator.MenuHostLocator", true, cl);
                    Class<?> locatorsClass = Class.forName("appeng.menu.locator.MenuLocators", true, cl);
                    Method openMethod = menuOpenerClass.getMethod("open", MenuType.class, Player.class, locatorClass);
                    MenuType<?> type = BuiltInRegistries.MENU.get(ResourceLocation.parse(menuId));

                    if (menuId.equals("ae2:networkstatus")) {
                        // The network tool used on a grid block, the way the server handles a
                        // player's click (its onItemUseFirst opens the status of that node).
                        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
                        if (!stack.is(BuiltInRegistries.ITEM.get(ResourceLocation.parse("ae2:network_tool")))) {
                            stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("ae2:network_tool")));
                            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                        }
                        player.inventoryMenu.broadcastChanges();
                        if (level.getBlockEntity(rig) == null) {
                            setBlock(level, rig, "ae2:controller");
                        }
                        net.minecraft.world.InteractionResult used = player.gameMode.useItemOn(player, level, stack,
                                InteractionHand.MAIN_HAND, new net.minecraft.world.phys.BlockHitResult(
                                        new net.minecraft.world.phys.Vec3(rig.getX() + 0.5, rig.getY() + 1, rig.getZ() + 0.5),
                                        net.minecraft.core.Direction.UP, rig, false));
                        LOGGER.info("[cimscene] network tool on {} -> {}", level.getBlockState(rig), used);
                        return used.consumesAction();
                    }
                    BlockEntity be = level.getBlockEntity(rig);
                    if (menuId.equals("ae2:basic_cell_chest") && be != null) {
                        try {
                            Method getInv = be.getClass().getMethod("getCellInventory");
                            Object inv = getInv.invoke(be);
                            Method setItem = inv.getClass().getMethod("setItem", int.class, ItemStack.class);
                            setItem.invoke(inv, 0, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("ae2:item_storage_cell_1k"))));
                        } catch (Throwable t) {
                            LOGGER.info("[cimscene] basic_cell_chest cell insert note: {}", t.toString());
                        }
                    }
                    if (be != null) {
                        Object locator = locatorsClass.getMethod("forBlockEntity", BlockEntity.class).invoke(null, be);
                        openMethod.invoke(null, type, player, locator);
                        return true;
                    }
                    return false;
                }
                default -> {
                    return false;
                }
            }
        } catch (Throwable t) {
            LOGGER.info("[cimscene] specialized act threw for {}: {}", menuId, t.toString());
            return false;
        }
    }
}
