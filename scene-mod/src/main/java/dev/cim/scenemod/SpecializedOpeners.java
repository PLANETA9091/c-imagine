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
import java.lang.reflect.Method;
import java.util.List;
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

    private SpecializedOpeners() {
    }

    public static boolean isSpecialized(String menuId) {
        return MENUS.contains(menuId);
    }

    public static void place(ServerLevel level, ServerPlayer player, BlockPos rig, String menuId,
                             java.util.function.Consumer<Entity> onEntitySpawned) {
        try {
            switch (menuId) {
                case "mekanism:crafting_robit", "mekanism:inventory_robit",
                     "mekanism:repair_robit", "mekanism:smelting_robit" -> {
                    EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("mekanism:robit"));
                    Entity robit = type.create(level);
                    if (robit != null) {
                        robit.moveTo(rig.getX() + 0.5, rig.getY(), rig.getZ() + 0.5, 0f, 0f);
                        level.addFreshEntity(robit);
                        if (onEntitySpawned != null) {
                            onEntitySpawned.accept(robit);
                        }
                    }
                }
                case "mekanism:digital_miner_config" ->
                    setBlock(level, rig, "mekanism:digital_miner");
                case "mekanism:boiler_stats", "mekanism:thermoelectric_boiler" ->
                    setBlock(level, rig, "mekanism:boiler_casing");
                case "mekanism:thermal_evaporation_controller" ->
                    setBlock(level, rig, "mekanism:thermal_evaporation_controller");
                case "mekanism:sps_casing" ->
                    setBlock(level, rig, "mekanism:sps_casing");
                case "mekanism:seismic_reader" ->
                    player.setItemInHand(InteractionHand.MAIN_HAND,
                            new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:seismic_reader"))));
                case "mekanism:qio_frequency_select_item" ->
                    player.setItemInHand(InteractionHand.MAIN_HAND,
                            new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:portable_qio_dashboard"))));
                case "sophisticatedbackpacks:settings" ->
                    setBlock(level, rig, "sophisticatedbackpacks:backpack");
                case "create:stock_keeper_category", "create:stock_keeper_request" ->
                    setBlock(level, rig, "create:stock_ticker");
                case "create:factory_panel_set_item" ->
                    setBlock(level, rig, "create:factory_panel");
                case "ae2:basic_cell_chest", "ae2:craftamount", "ae2:craftconfirm" ->
                    setBlock(level, rig, "ae2:chest");
                case "ae2:craftingcpu" ->
                    setBlock(level, rig, "ae2:crafting_unit");
                case "ae2:networkstatus" ->
                    player.setItemInHand(InteractionHand.MAIN_HAND,
                            new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("ae2:network_tool"))));
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
                                e -> BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals("mekanism:robit"));
                        if (!near.isEmpty()) {
                            target = near.get(0);
                        }
                    }
                    if (target == null) {
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

                    Class<?> tileBtnClass = Class.forName(
                            "mekanism.common.network.to_server.button.PacketTileButtonPress$ClickedTileButton", true, cl);
                    Object btn = Enum.valueOf((Class<Enum>) tileBtnClass.asSubclass(Enum.class), "TAB_STATS");
                    Method getProvider = tileBtnClass.getMethod("getProvider",
                            Class.forName("mekanism.common.tile.base.TileEntityMekanism", true, cl));
                    MenuProvider provider = (MenuProvider) getProvider.invoke(btn, be);
                    player.openMenu(provider, buf -> buf.writeBlockPos(rig));
                    return true;
                }
                case "mekanism:thermoelectric_boiler", "mekanism:thermal_evaporation_controller", "mekanism:sps_casing" -> {
                    BlockEntity be = level.getBlockEntity(rig);
                    Method getMultiblock = be.getClass().getMethod("getMultiblock");
                    Object mb = getMultiblock.invoke(be);
                    mb.getClass().getMethod("setFormedForce", boolean.class).invoke(mb, true);
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
                    BlockEntity be = level.getBlockEntity(rig);
                    Class<?> provClass = Class.forName(
                            "com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity$RequestMenuProvider", true, cl);
                    MenuProvider prov = (MenuProvider) provClass.getConstructor(be.getClass()).newInstance(be);
                    player.openMenu(prov, buf -> buf.writeBlockPos(rig));
                    return true;
                }
                case "create:factory_panel_set_item" -> {
                    Class<?> slotClass = Class.forName(
                            "com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock$PanelSlot", true, cl);
                    Object panelSlot = slotClass.getEnumConstants()[0];
                    Class<?> posClass = Class.forName(
                            "com.simibubi.create.content.logistics.factoryBoard.FactoryPanelPosition", true, cl);
                    Object panelPos = posClass.getConstructor(BlockPos.class, slotClass).newInstance(rig, panelSlot);
                    Class<?> behClass = Class.forName(
                            "com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour", true, cl);
                    Object beh = behClass.getMethod("at", BlockAndTintGetter.class, posClass).invoke(null, level, panelPos);
                    Class<?> menuClass = Class.forName(
                            "com.simibubi.create.content.logistics.factoryBoard.FactoryPanelSetItemMenu", true, cl);
                    Method createMethod = menuClass.getMethod("create", int.class,
                            net.minecraft.world.entity.player.Inventory.class, behClass);
                    net.minecraft.network.codec.StreamCodec codec =
                            (net.minecraft.network.codec.StreamCodec) posClass.getField("STREAM_CODEC").get(null);

                    MenuProvider prov = new SimpleMenuProvider(
                            (id, inv, p) -> {
                                try {
                                    return (AbstractContainerMenu) createMethod.invoke(null, id, inv, beh);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            },
                            Component.literal("Factory Panel")
                    );
                    player.openMenu(prov, buf -> codec.encode(buf, panelPos));
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
                        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
                        if (stack.isEmpty()) {
                            stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("ae2:network_tool")));
                            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                        }
                        Object locator = locatorsClass.getMethod("forHand", Player.class, InteractionHand.class)
                                .invoke(null, player, InteractionHand.MAIN_HAND);
                        openMethod.invoke(null, type, player, locator);
                        return true;
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
