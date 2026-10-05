package net.shaddii.smartsorter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
//? if >=1.21.9 {
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
//?} else {
/*import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
 *///?}
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
//? if > 1.21.1 {
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
//?}
import net.minecraft.resource.ResourceType;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

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
    public static ScreenHandlerType<StorageControllerScreenHandler> STORAGE_CONTROLLER_SCREEN_HANDLER;
    public static ScreenHandlerType<OutputProbeScreenHandler> OUTPUT_PROBE_SCREEN_HANDLER;

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
        registerEvents();
        ChunkedSorter.init();

        // Register the category manager
        //? if >=1.21.9 {
        ResourceLoader.get(ResourceType.SERVER_DATA)
                .registerReloader
                (
                        Identifier.of("smartsorter", "category_manager"),
                        CategoryManager.getInstance()
                );
    //?} else {
        /*ResourceManagerHelper.get(ResourceType.SERVER_DATA)
                .registerReloadListener(CategoryManager.getInstance());
    *///?}
    }

    //? if >=1.21.8 {
    private static void writeNameToChestDirect(net.minecraft.world.World world, BlockPos chestPos, String customName) {
        if (world == null || world.isClient()) return;

        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (blockEntity == null) return;

        NbtCompound nbt = blockEntity.createNbt(world.getRegistryManager());

        if (customName == null || customName.isEmpty()) {
            nbt.remove("CustomName");
        } else {
            net.minecraft.text.Text textComponent = net.minecraft.text.Text.literal(customName);
            com.mojang.serialization.DataResult<net.minecraft.nbt.NbtElement> result =
                    net.minecraft.text.TextCodecs.CODEC.encodeStart(
                            world.getRegistryManager().getOps(net.minecraft.nbt.NbtOps.INSTANCE),
                            textComponent
                    );
            result.result().ifPresent(nbtElement -> {
                nbt.put("CustomName", nbtElement);
            });
        }

        try (net.minecraft.util.ErrorReporter.Logging logging =
                     new net.minecraft.util.ErrorReporter.Logging(
                             blockEntity.getReporterContext(),
                             com.mojang.logging.LogUtils.getLogger())) {
            blockEntity.read(net.minecraft.storage.NbtReadView.create(logging, world.getRegistryManager(), nbt));
        }
        blockEntity.markDirty();

        net.minecraft.block.BlockState state = world.getBlockState(chestPos);
        world.updateListeners(chestPos, state, state, 3);
    }
//?} else {
/*private static void writeNameToChestDirect(net.minecraft.world.World world, BlockPos chestPos, String customName) {
    if (world == null || world.isClient()) return;

    BlockEntity blockEntity = world.getBlockEntity(chestPos);
    if (blockEntity == null) return;

    NbtCompound nbt = blockEntity.createNbt(world.getRegistryManager());

    if (customName == null || customName.isEmpty()) {
        nbt.remove("CustomName");
    } else {
        net.minecraft.text.Text textComponent = net.minecraft.text.Text.literal(customName);
        nbt.putString("CustomName", net.minecraft.text.Text.Serialization.toJsonString(textComponent, world.getRegistryManager()));
    }

    blockEntity.read(nbt, world.getRegistryManager());
    blockEntity.markDirty();

    net.minecraft.block.BlockState state = world.getBlockState(chestPos);
    world.updateListeners(chestPos, state, state, 3);
}
*///?}

    // ------------------------------------------------------
    // Blocks
    // ------------------------------------------------------
    //? if > 1.21.1 {
    
    private void registerBlocks() {
        INTAKE_BLOCK = registerBlock("intake",
                new IntakeBlock(AbstractBlock.Settings.create()
                        .registryKey(RegistryKey.of(RegistryKeys.BLOCK, id("intake")))
                        .strength(0.6F).nonOpaque()));

        PROBE_BLOCK = registerBlock("output_probe",
                new OutputProbeBlock(AbstractBlock.Settings.create()
                        .registryKey(RegistryKey.of(RegistryKeys.BLOCK, id("output_probe")))
                        .strength(0.6F).nonOpaque()));

        STORAGE_CONTROLLER_BLOCK = registerBlock("storage_controller",
                new StorageControllerBlock(AbstractBlock.Settings.create()
                        .registryKey(RegistryKey.of(RegistryKeys.BLOCK, id("storage_controller")))
                        .strength(0.6F)));
        PROCESS_PROBE_BLOCK = registerBlock("process_probe",
                new ProcessProbeBlock(AbstractBlock.Settings.create()
                        .registryKey(RegistryKey.of(RegistryKeys.BLOCK, id("process_probe")))
                        .strength(0.6F).nonOpaque()));
    }
    //?} else {

    /*private void registerBlocks() {
        INTAKE_BLOCK = registerBlock("intake",
                new IntakeBlock(AbstractBlock.Settings.create()
                        .strength(0.6F).nonOpaque()));

        PROBE_BLOCK = registerBlock("output_probe",
                new OutputProbeBlock(AbstractBlock.Settings.create()
                        .strength(0.6F).nonOpaque()));

        STORAGE_CONTROLLER_BLOCK = registerBlock("storage_controller",
                new StorageControllerBlock(AbstractBlock.Settings.create()
                        .strength(0.6F)));

        PROCESS_PROBE_BLOCK = registerBlock("process_probe",
                new ProcessProbeBlock(AbstractBlock.Settings.create()
                        .strength(0.6F).nonOpaque()));
    }
    *///?}

    private Block registerBlock(String name, Block block) {
        return Registry.register(Registries.BLOCK, id(name), block);
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
        //? if > 1.21.1 {
        
        Item.Settings settings = new Item.Settings()
                .registryKey(RegistryKey.of(RegistryKeys.ITEM, id(name)));
        //?} else {
        /*Item.Settings settings = new Item.Settings();
        *///?}
        // Create a custom BlockItem that uses the block's name
        return Registry.register(Registries.ITEM, id(name), new BlockItem(block, settings) {
            @Override
            public Text getName(ItemStack stack) {
                return block.getName(); // uses "block.smartsorter.intake" instead of "item.smartsorter.intake"
            }
        });
    }


    // ------------------------------------------------------
    // Block Entities
    // ------------------------------------------------------
    private void registerBlockEntities() {
        INTAKE_BE_TYPE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE, id("intake"),
                FabricBlockEntityTypeBuilder.create(IntakeBlockEntity::new, INTAKE_BLOCK).build());
        PROBE_BE_TYPE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE, id("output_probe"),
                FabricBlockEntityTypeBuilder.create(OutputProbeBlockEntity::new, PROBE_BLOCK).build());
        STORAGE_CONTROLLER_BE_TYPE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE, id("storage_controller"),
                FabricBlockEntityTypeBuilder.create(StorageControllerBlockEntity::new, STORAGE_CONTROLLER_BLOCK).build());
        PROCESS_PROBE_BE_TYPE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE, id("process_probe"),
                FabricBlockEntityTypeBuilder.create(ProcessProbeBlockEntity::new, PROCESS_PROBE_BLOCK).build());
        ENDER_PROBE_BE_TYPE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE, id("ender_chest_probe"),
                FabricBlockEntityTypeBuilder.create(EnderChestProbeBlockEntity::new, PROBE_BLOCK).build());
    }

    // ------------------------------------------------------
    // Screens
    // ------------------------------------------------------
    private void registerScreens() {
        STORAGE_CONTROLLER_SCREEN_HANDLER = Registry.register(
                Registries.SCREEN_HANDLER, id("storage_controller"),
                new ScreenHandlerType<>(StorageControllerScreenHandler::new, null));

        OUTPUT_PROBE_SCREEN_HANDLER = Registry.register(
                Registries.SCREEN_HANDLER, id("output_probe"),
                new ExtendedScreenHandlerType<>(OutputProbeScreenHandler::new, OutputProbeBlockEntity.ProbeData.CODEC));
    }

    // ------------------------------------------------------
    // Tools
    // ------------------------------------------------------
    private void registerTools() {
        //? if > 1.21.1 {
        
        // Create settings with registry key pre-set
        Item.Settings settings = new Item.Settings()
                .registryKey(RegistryKey.of(RegistryKeys.ITEM, id("linking_tool")))
                .maxCount(1);
        //?} else {
        /*Item.Settings settings = new Item.Settings()
                .maxCount(1);
        *///?}

        LINKING_TOOL = Registry.register(
                Registries.ITEM, id("linking_tool"),
                new LinkingToolItem(settings));
    }

    // ------------------------------------------------------
    // Creative Tab
    // ------------------------------------------------------
    private void registerCreativeTab() {
        Registry.register(Registries.ITEM_GROUP, id("smartsorter_group"),
                FabricItemGroup.builder()
                        .displayName(Text.translatable("itemGroup.smartsorter"))
                        .icon(() -> new ItemStack(STORAGE_CONTROLLER_ITEM))
                        .entries((ctx, entries) -> {
                            entries.add(STORAGE_CONTROLLER_ITEM);
                            entries.add(INTAKE_ITEM);
                            entries.add(PROBE_ITEM);
                            entries.add(PROCESS_PROBE_ITEM);
                            entries.add(LINKING_TOOL);
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
        PayloadTypeRegistry.playS2C().register(
                StorageControllerSyncPacket.SyncPayload.ID_PAYLOAD,
                StorageControllerSyncPacket.SyncPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(
                ProbeStatsSyncPayload.ID,
                ProbeStatsSyncPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(
                ProbeConfigBatchPayload.ID,
                ProbeConfigBatchPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(
                ChestConfigBatchPayload.ID,
                ChestConfigBatchPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(
                ChestConfigUpdatePayload.ID,
                ChestConfigUpdatePayload.CODEC);

        PayloadTypeRegistry.playS2C().register(
                OverflowNotificationPayload.ID,
                OverflowNotificationPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(
                StorageDeltaSyncPayload.ID,
                StorageDeltaSyncPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(
                SortProgressPayload.ID,
                SortProgressPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(
                ChestPriorityBatchPayload.ID,
                ChestPriorityBatchPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(
                CategorySyncPayload.ID,
                CategorySyncPayload.CODEC);

        // =====================================================
        // Client to Server (C2S) - Client sends to server
        // =====================================================
        PayloadTypeRegistry.playC2S().register(
                StorageControllerScreenHandler.ExtractionRequestPayload.ID,
                StorageControllerScreenHandler.ExtractionRequestPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(
                StorageControllerScreenHandler.SyncRequestPayload.ID,
                StorageControllerScreenHandler.SyncRequestPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(
                StorageControllerScreenHandler.DepositRequestPayload.ID,
                StorageControllerScreenHandler.DepositRequestPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(
                SortModeChangePayload.ID,
                SortModeChangePayload.CODEC);

        PayloadTypeRegistry.playC2S().register(
                FilterCategoryChangePayload.ID,
                FilterCategoryChangePayload.CODEC);

        PayloadTypeRegistry.playC2S().register(
                CollectXpPayload.ID,
                CollectXpPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(
                ProbeConfigUpdatePayload.ID,
                ProbeConfigUpdatePayload.CODEC);

        PayloadTypeRegistry.playC2S().register(
                ChestConfigUpdatePayload.ID,
                ChestConfigUpdatePayload.CODEC);

        PayloadTypeRegistry.playC2S().register(
                SortChestsPayload.ID,
                SortChestsPayload.CODEC);
    }

    private void registerNetworkHandlers() {
        // Extraction - SINGLE HANDLER
        ServerPlayNetworking.registerGlobalReceiver(
                StorageControllerScreenHandler.ExtractionRequestPayload.ID,
                (payload, context) -> context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player.currentScreenHandler instanceof StorageControllerScreenHandler handler) {
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
                    ServerPlayerEntity player = context.player();
                    if (player.currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                        if (handler.controller != null) {
                            ItemStack stack = payload.variant().toStack(payload.amount());
                            handler.depositItem(stack, payload.amount(), player);
                            handler.requestImmediateSync(player); // Force sync
                        }
                    }
                }));

        ServerPlayNetworking.registerGlobalReceiver(SortChestsPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayerEntity player = context.player();
                if (player.currentScreenHandler instanceof StorageControllerScreenHandler handler) {
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
                    if (context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                        // When a client manually requests a sync, it always needs the full data.
                        handler.sendNetworkUpdate(context.player());
                    }
                }));

        // Sort mode
        ServerPlayNetworking.registerGlobalReceiver(
                SortModeChangePayload.ID,
                (payload, context) -> context.server().execute(() -> {
                    if (context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                        handler.setSortMode(payload.getSortMode());
                        handler.sendNetworkUpdate(context.player());
                    }
                }));

        // Filter category
        ServerPlayNetworking.registerGlobalReceiver(
                FilterCategoryChangePayload.ID,
                (payload, context) -> context.server().execute(() -> {
                    if (context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                        handler.setFilterCategory(payload.getCategory());
                        handler.sendNetworkUpdate(context.player());
                    }
                }));

        // XP Collection
        ServerPlayNetworking.registerGlobalReceiver(CollectXpPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayerEntity player = context.player();
                if (player.currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                    if (handler.controller != null) {
                        int xp = handler.controller.collectExperience();
                        if (xp > 0) {
                            // Add XP to player
                            player.addExperience(xp);

                            // Visual feedback
                            player.sendMessage(Text.literal("§a+§e" + xp + " XP §acollected!"), true);

                            // Play sound
                            //? if >=1.21.9 {
                            player.getEntityWorld().playSound(
                            //?} else {
                                    /*player.getWorld().playSound(
                            *///?}
                                    null,
                                    player.getX(),
                                    player.getY(),
                                    player.getZ(),
                                    SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                                    SoundCategory.PLAYERS,
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
                ServerPlayerEntity player = context.player();

                if (player.currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                    if (handler.controller != null) {
                        ProcessProbeConfig config = handler.controller.getProbeConfig(payload.position());
                        if (config != null) {

                            config.customName = payload.customName();
                            config.enabled = payload.enabled();
                            config.recipeFilter = payload.recipeFilter();
                            config.fuelFilter = payload.fuelFilter();

                            handler.controller.updateProbeConfig(config);
                            handler.controller.markDirty();
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
                        ServerPlayerEntity player = context.player();
                        StorageControllerBlockEntity controller = null;
                        OutputProbeBlockEntity probe = null;

                        // Check if open in main controller GUI
                        if (player.currentScreenHandler instanceof StorageControllerScreenHandler mainHandler) {
                            controller = mainHandler.controller;
                        }
                        // Check if open in probe GUI
                        else if (player.currentScreenHandler instanceof OutputProbeScreenHandler probeHandler) {
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

                            if (player.currentScreenHandler instanceof StorageControllerScreenHandler mainHandler) {
                                mainHandler.markConfigsDirty();
                                mainHandler.sendNetworkUpdate(player);
                            } else if (player.currentScreenHandler instanceof OutputProbeScreenHandler probeHandler) {
                                ChestConfig refreshedConfig = controller.getChestConfig(payload.config().position);
                                if (refreshedConfig != null) {
                                    probeHandler.setChestConfig(refreshedConfig);
                                    ServerPlayNetworking.send(player, new ChestConfigUpdatePayload(refreshedConfig));
                                }
                            }
                        }
                        // PRIORITY 3: If no controller, manually write the name to the chest
                        else if (probe != null && probe.getWorld() != null) {
                            BlockPos chestPos = payload.config().position;
                            if (chestPos != null) {
                                writeNameToChestDirect(probe.getWorld(), chestPos, payload.config().customName);
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
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> ActionResult.PASS);
    }
    // ------------------------------------------------------
    // Utility
    // ------------------------------------------------------
    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);  // Using Identifier.of() instead of new Identifier()
    }
}