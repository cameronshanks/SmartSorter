package net.shaddii.smartsorter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.shaddii.smartsorter.block.*;
import net.shaddii.smartsorter.blockentity.*;
import net.shaddii.smartsorter.item.LinkingToolItem;
import net.shaddii.smartsorter.chunk.ChunkKeeper;
import net.shaddii.smartsorter.config.SmartSorterConfig;
import net.shaddii.smartsorter.network.*;
import net.shaddii.smartsorter.screen.OutputProbeScreenHandler;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.util.CategoryManager;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.util.ChunkedSorter;
import net.shaddii.smartsorter.util.ProcessProbeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

// import static com.mojang.text2speech.Narrator.LOGGER;

public class SmartSorter implements ModInitializer {
    public static final String MOD_ID = "smartsorter";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // === Blocks ===
    public static Block INTAKE_BLOCK;
    public static Block PROBE_BLOCK;
    public static Block STORAGE_CONTROLLER_BLOCK;
    public static Block PROCESS_PROBE_BLOCK;

    // === Items ===
    public static Item INTAKE_ITEM;
    public static Item PROBE_ITEM;
    public static Item STORAGE_CONTROLLER_ITEM;
    public static Item PROCESS_PROBE_ITEM;
    public static Item LINKING_TOOL;

    // === Block Entities ===
    public static BlockEntityType<IntakeBlockEntity> INTAKE_BE_TYPE;
    public static BlockEntityType<OutputProbeBlockEntity> PROBE_BE_TYPE;
    public static BlockEntityType<StorageControllerBlockEntity> STORAGE_CONTROLLER_BE_TYPE;
    public static BlockEntityType<ProcessProbeBlockEntity> PROCESS_PROBE_BE_TYPE;
    public static BlockEntityType<EnderChestProbeBlockEntity> ENDER_PROBE_BE_TYPE;

    // === Screen Handlers ===
    public static MenuType<StorageControllerScreenHandler> STORAGE_CONTROLLER_SCREEN_HANDLER;
    public static MenuType<OutputProbeScreenHandler> OUTPUT_PROBE_SCREEN_HANDLER;

    @Override
    public void onInitialize() {
        SmartSorterConfig.load();
        ChunkKeeper.init();

        registerBlocks();
        registerItems();
        registerBlockEntities();
        registerScreens();
        registerTools();
        registerCreativeTab();
        registerNetworkPayloads();
        registerNetworkHandlers();
        WhitelistNetworking.register();

        // Linking Tool selections are plain positions; drop them when they stop making sense
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                LinkingToolItem.clearSelection(handler.getPlayer().getUUID()));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) ->
                LinkingToolItem.clearSelection(player.getUUID()));
        registerEvents();
        ChunkedSorter.init();

        // Register the category manager
        ResourceLoader.get(PackType.SERVER_DATA)
                .registerReloadListener
                (
                        Identifier.fromNamespaceAndPath("smartsorter", "category_manager"),
                        CategoryManager.getInstance()
                );
    }

    private static void writeNameToChestDirect(net.minecraft.world.level.Level world, BlockPos chestPos, String customName) {
        if (world == null || world.isClientSide()) return;

        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (blockEntity == null) return;

        CompoundTag nbt = blockEntity.saveWithoutMetadata(world.registryAccess());

        if (customName == null || customName.isEmpty()) {
            nbt.remove("CustomName");
        } else {
            net.minecraft.network.chat.Component textComponent = net.minecraft.network.chat.Component.literal(customName);
            com.mojang.serialization.DataResult<net.minecraft.nbt.Tag> result =
                    net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(
                            world.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),
                            textComponent
                    );
            result.result().ifPresent(nbtElement -> {
                nbt.put("CustomName", nbtElement);
            });
        }

        try (net.minecraft.util.ProblemReporter.ScopedCollector logging =
                     new net.minecraft.util.ProblemReporter.ScopedCollector(
                             blockEntity.problemPath(),
                             com.mojang.logging.LogUtils.getLogger())) {
            blockEntity.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(logging, world.registryAccess(), nbt));
        }
        blockEntity.setChanged();

        net.minecraft.world.level.block.state.BlockState state = world.getBlockState(chestPos);
        world.sendBlockUpdated(chestPos, state, state, 3);
    }

    // ------------------------------------------------------
    // Blocks
    // ------------------------------------------------------
    
    private void registerBlocks() {
        INTAKE_BLOCK = registerBlock("intake",
                new IntakeBlock(BlockBehaviour.Properties.of()
                        .setId(ResourceKey.create(Registries.BLOCK, id("intake")))
                        .strength(0.6F).noOcclusion()));

        PROBE_BLOCK = registerBlock("output_probe",
                new OutputProbeBlock(BlockBehaviour.Properties.of()
                        .setId(ResourceKey.create(Registries.BLOCK, id("output_probe")))
                        .strength(0.6F).noOcclusion()));

        STORAGE_CONTROLLER_BLOCK = registerBlock("storage_controller",
                new StorageControllerBlock(BlockBehaviour.Properties.of()
                        .setId(ResourceKey.create(Registries.BLOCK, id("storage_controller")))
                        .strength(0.6F)));
        PROCESS_PROBE_BLOCK = registerBlock("process_probe",
                new ProcessProbeBlock(BlockBehaviour.Properties.of()
                        .setId(ResourceKey.create(Registries.BLOCK, id("process_probe")))
                        .strength(0.6F).noOcclusion()));
    }


    private Block registerBlock(String name, Block block) {
        return Registry.register(BuiltInRegistries.BLOCK, id(name), block);
    }

    // ------------------------------------------------------
    // Block Items
    // ------------------------------------------------------
    private void registerItems() {
        INTAKE_ITEM = registerBlockItem("intake", INTAKE_BLOCK);
        PROBE_ITEM = registerBlockItem("output_probe", PROBE_BLOCK);
        STORAGE_CONTROLLER_ITEM = registerBlockItem("storage_controller", STORAGE_CONTROLLER_BLOCK);
        PROCESS_PROBE_ITEM = registerBlockItem("process_probe", PROCESS_PROBE_BLOCK);
    }

    private Item registerBlockItem(String name, Block block) {
        
        Item.Properties settings = new Item.Properties()
                .setId(ResourceKey.create(Registries.ITEM, id(name)));
        // Create a custom BlockItem that uses the block's name
        return Registry.register(BuiltInRegistries.ITEM, id(name), new BlockItem(block, settings) {
            @Override
            public Component getName(ItemStack stack) {
                return block.getName(); // uses "block.smartsorter.intake" instead of "item.smartsorter.intake"
            }
        });
    }


    // ------------------------------------------------------
    // Block Entities
    // ------------------------------------------------------
    private void registerBlockEntities() {
        INTAKE_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE, id("intake"),
                FabricBlockEntityTypeBuilder.create(IntakeBlockEntity::new, INTAKE_BLOCK).build());
        PROBE_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE, id("output_probe"),
                FabricBlockEntityTypeBuilder.create(OutputProbeBlockEntity::new, PROBE_BLOCK).build());
        STORAGE_CONTROLLER_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE, id("storage_controller"),
                FabricBlockEntityTypeBuilder.create(StorageControllerBlockEntity::new, STORAGE_CONTROLLER_BLOCK).build());
        PROCESS_PROBE_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE, id("process_probe"),
                FabricBlockEntityTypeBuilder.create(ProcessProbeBlockEntity::new, PROCESS_PROBE_BLOCK).build());
        ENDER_PROBE_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE, id("ender_chest_probe"),
                FabricBlockEntityTypeBuilder.create(EnderChestProbeBlockEntity::new, PROBE_BLOCK).build());
    }

    // ------------------------------------------------------
    // Screens
    // ------------------------------------------------------
    private void registerScreens() {
        STORAGE_CONTROLLER_SCREEN_HANDLER = Registry.register(
                BuiltInRegistries.MENU, id("storage_controller"),
                new MenuType<>(StorageControllerScreenHandler::new, null));

        OUTPUT_PROBE_SCREEN_HANDLER = Registry.register(
                BuiltInRegistries.MENU, id("output_probe"),
                new ExtendedMenuType<>(OutputProbeScreenHandler::new, OutputProbeBlockEntity.ProbeData.CODEC));
    }

    // ------------------------------------------------------
    // Tools
    // ------------------------------------------------------
    private void registerTools() {
        
        // Create settings with registry key pre-set
        Item.Properties settings = new Item.Properties()
                .setId(ResourceKey.create(Registries.ITEM, id("linking_tool")))
                .stacksTo(1);

        LINKING_TOOL = Registry.register(
                BuiltInRegistries.ITEM, id("linking_tool"),
                new LinkingToolItem(settings));
    }

    // ------------------------------------------------------
    // Creative Tab
    // ------------------------------------------------------
    private void registerCreativeTab() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id("smartsorter_group"),
                FabricCreativeModeTab.builder()
                        .title(Component.translatable("itemGroup.smartsorter"))
                        .icon(() -> new ItemStack(STORAGE_CONTROLLER_ITEM))
                        .displayItems((ctx, entries) -> {
                            entries.accept(STORAGE_CONTROLLER_ITEM);
                            entries.accept(INTAKE_ITEM);
                            entries.accept(PROBE_ITEM);
                            entries.accept(PROCESS_PROBE_ITEM);
                            entries.accept(LINKING_TOOL);
                        })
                        .build());
    }

    // ------------------------------------------------------
    // Networking
    // ------------------------------------------------------
    private void registerNetworkPayloads() {
    // =====================================================
    // Server to Client (S2C) - Server sends to client
    // =====================================================
        PayloadTypeRegistry.clientboundPlay().register(
                StorageControllerSyncPacket.SyncPayload.ID_PAYLOAD,
                StorageControllerSyncPacket.SyncPayload.CODEC);

        PayloadTypeRegistry.clientboundPlay().register(
                ProbeStatsSyncPayload.ID,
                ProbeStatsSyncPayload.CODEC);

        PayloadTypeRegistry.clientboundPlay().register(
                ProbeConfigBatchPayload.ID,
                ProbeConfigBatchPayload.CODEC);

        PayloadTypeRegistry.clientboundPlay().register(
                ChestConfigBatchPayload.ID,
                ChestConfigBatchPayload.CODEC);

        PayloadTypeRegistry.clientboundPlay().register(
                ChestConfigUpdatePayload.ID,
                ChestConfigUpdatePayload.CODEC);

        PayloadTypeRegistry.clientboundPlay().register(
                OverflowNotificationPayload.ID,
                OverflowNotificationPayload.CODEC);

        PayloadTypeRegistry.clientboundPlay().register(
                StorageDeltaSyncPayload.ID,
                StorageDeltaSyncPayload.CODEC);

        PayloadTypeRegistry.clientboundPlay().register(
                SortProgressPayload.ID,
                SortProgressPayload.CODEC);

        PayloadTypeRegistry.clientboundPlay().register(
                ChestPriorityBatchPayload.ID,
                ChestPriorityBatchPayload.CODEC);

        PayloadTypeRegistry.clientboundPlay().register(
                CategorySyncPayload.ID,
                CategorySyncPayload.CODEC);

        // =====================================================
        // Client to Server (C2S) - Client sends to server
        // =====================================================
        PayloadTypeRegistry.serverboundPlay().register(
                StorageControllerScreenHandler.ExtractionRequestPayload.ID,
                StorageControllerScreenHandler.ExtractionRequestPayload.CODEC);

        PayloadTypeRegistry.serverboundPlay().register(
                StorageControllerScreenHandler.SyncRequestPayload.ID,
                StorageControllerScreenHandler.SyncRequestPayload.CODEC);

        PayloadTypeRegistry.serverboundPlay().register(
                StorageControllerScreenHandler.DepositRequestPayload.ID,
                StorageControllerScreenHandler.DepositRequestPayload.CODEC);

        PayloadTypeRegistry.serverboundPlay().register(
                SortModeChangePayload.ID,
                SortModeChangePayload.CODEC);

        PayloadTypeRegistry.serverboundPlay().register(
                FilterCategoryChangePayload.ID,
                FilterCategoryChangePayload.CODEC);

        PayloadTypeRegistry.serverboundPlay().register(
                CollectXpPayload.ID,
                CollectXpPayload.CODEC);

        PayloadTypeRegistry.serverboundPlay().register(
                ProbeConfigUpdatePayload.ID,
                ProbeConfigUpdatePayload.CODEC);

        PayloadTypeRegistry.serverboundPlay().register(
                ChestConfigUpdatePayload.ID,
                ChestConfigUpdatePayload.CODEC);

        PayloadTypeRegistry.serverboundPlay().register(
                SortChestsPayload.ID,
                SortChestsPayload.CODEC);
    }

    private void registerNetworkHandlers() {
        // Extraction - SINGLE HANDLER
        ServerPlayNetworking.registerGlobalReceiver(
                StorageControllerScreenHandler.ExtractionRequestPayload.ID,
                (payload, context) -> context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player.containerMenu instanceof StorageControllerScreenHandler handler) {
                        if (handler.controller != null) {
                            handler.extractItem(payload.variant(), payload.amount(), payload.toInventory(), player);
                            handler.requestImmediateSync(player); // Force sync
                        }
                    }
                }));

        // Deposit - SINGLE HANDLER
        ServerPlayNetworking.registerGlobalReceiver(
                StorageControllerScreenHandler.DepositRequestPayload.ID,
                (payload, context) -> context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player.containerMenu instanceof StorageControllerScreenHandler handler) {
                        if (handler.controller != null) {
                            ItemStack stack = payload.variant().toStack(payload.amount());
                            handler.depositItem(stack, payload.amount(), player);
                            handler.requestImmediateSync(player); // Force sync
                        }
                    }
                }));

        ServerPlayNetworking.registerGlobalReceiver(SortChestsPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player.containerMenu instanceof StorageControllerScreenHandler handler) {
                    StorageControllerBlockEntity controller = handler.controller;
                    if (controller != null) {
                        ChunkedSorter.startSorting(player, controller, payload.sortedPositions());
                    }
                }
            });
        });

        // Sync request
        ServerPlayNetworking.registerGlobalReceiver(
                StorageControllerScreenHandler.SyncRequestPayload.ID,
                (payload, context) -> context.server().execute(() -> {
                    if (context.player().containerMenu instanceof StorageControllerScreenHandler handler) {
                        // When a client manually requests a sync, it always needs the full data.
                        handler.sendNetworkUpdate(context.player());
                    }
                }));

        // Sort mode
        ServerPlayNetworking.registerGlobalReceiver(
                SortModeChangePayload.ID,
                (payload, context) -> context.server().execute(() -> {
                    if (context.player().containerMenu instanceof StorageControllerScreenHandler handler) {
                        handler.setSortMode(payload.getSortMode());
                        handler.sendNetworkUpdate(context.player());
                    }
                }));

        // Filter category
        ServerPlayNetworking.registerGlobalReceiver(
                FilterCategoryChangePayload.ID,
                (payload, context) -> context.server().execute(() -> {
                    if (context.player().containerMenu instanceof StorageControllerScreenHandler handler) {
                        handler.setFilterCategory(payload.getCategory());
                        handler.sendNetworkUpdate(context.player());
                    }
                }));

        // XP Collection
        ServerPlayNetworking.registerGlobalReceiver(CollectXpPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player.containerMenu instanceof StorageControllerScreenHandler handler) {
                    if (handler.controller != null) {
                        int xp = handler.controller.collectExperience();
                        if (xp > 0) {
                            // Add XP to player
                            player.giveExperiencePoints(xp);

                            // Visual feedback
                            player.sendOverlayMessage(Component.literal("§a+§e" + xp + " XP §acollected!"));

                            // Play sound
                            player.level().playSound(
                                    null,
                                    player.getX(),
                                    player.getY(),
                                    player.getZ(),
                                    SoundEvents.EXPERIENCE_ORB_PICKUP,
                                    SoundSource.PLAYERS,
                                    0.5f,
                                    1.0f
                            );

                            // Sync updated XP back to client
                            handler.sendNetworkUpdate(player);

                        }
                    }
                }
            });
        });

        // Probe config update
        ServerPlayNetworking.registerGlobalReceiver(ProbeConfigUpdatePayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();

                if (player.containerMenu instanceof StorageControllerScreenHandler handler) {
                    if (handler.controller != null) {
                        ProcessProbeConfig config = handler.controller.getProbeConfig(payload.position());
                        if (config != null) {

                            config.customName = payload.customName();
                            config.enabled = payload.enabled();
                            config.recipeFilter = payload.recipeFilter();
                            config.fuelFilter = payload.fuelFilter();

                            handler.controller.updateProbeConfig(config);
                            handler.controller.setChanged();
                            handler.sendNetworkUpdate(player);
                        }
                    }
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(
                ChestConfigUpdatePayload.ID,
                (payload, context) -> {
                    context.server().execute(() -> {
                        ServerPlayer player = context.player();
                        StorageControllerBlockEntity controller = null;
                        OutputProbeBlockEntity probe = null;

                        // Check if open in main controller GUI
                        if (player.containerMenu instanceof StorageControllerScreenHandler mainHandler) {
                            controller = mainHandler.controller;
                        }
                        // Check if open in probe GUI
                        else if (player.containerMenu instanceof OutputProbeScreenHandler probeHandler) {
                            controller = probeHandler.controller;
                            probe = probeHandler.probe;
                        }

                        // PRIORITY 1: Update the probe's local config (works standalone)
                        if (probe != null) {
                            probe.setChestConfig(payload.config());
                        }

                        // PRIORITY 2: Update controller if linked
                        if (controller != null) {
                            controller.updateChestConfig(payload.config().position, payload.config());

                            if (player.containerMenu instanceof StorageControllerScreenHandler mainHandler) {
                                mainHandler.markConfigsDirty();
                                mainHandler.sendNetworkUpdate(player);
                            } else if (player.containerMenu instanceof OutputProbeScreenHandler probeHandler) {
                                ChestConfig refreshedConfig = controller.getChestConfig(payload.config().position);
                                if (refreshedConfig != null) {
                                    probeHandler.setChestConfig(refreshedConfig);
                                    ServerPlayNetworking.send(player, new ChestConfigUpdatePayload(refreshedConfig));
                                }
                            }
                        }
                        // PRIORITY 3: If no controller, manually write the name to the chest
                        else if (probe != null && probe.getLevel() != null) {
                            BlockPos chestPos = payload.config().position;
                            if (chestPos != null) {
                                writeNameToChestDirect(probe.getLevel(), chestPos, payload.config().customName);
                            }
                        }
                    });
                }
        );

    }

    // ------------------------------------------------------
    // Events
    // ------------------------------------------------------
    private void registerEvents() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> InteractionResult.PASS);
    }
    // ------------------------------------------------------
    // Utility
    // ------------------------------------------------------
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);  // Using Identifier.of() instead of new Identifier()
    }
}