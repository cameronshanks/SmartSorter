package net.shaddii.smartsorter.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.item.ItemStack;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.network.*;
import net.shaddii.smartsorter.screen.OutputProbeScreen;
import net.shaddii.smartsorter.screen.OutputProbeScreenHandler;
import net.shaddii.smartsorter.screen.StorageControllerScreen;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.util.CategoryManager;
import net.shaddii.smartsorter.util.ChestConfig;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

public class SmartSorterClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        registerBlockRenderLayers();
        CategoryManager.getInstance();

        // Register screens
        HandledScreens.register(SmartSorter.STORAGE_CONTROLLER_SCREEN_HANDLER, StorageControllerScreen::new);
        HandledScreens.register(SmartSorter.OUTPUT_PROBE_SCREEN_HANDLER, OutputProbeScreen::new);

        // Register HUD overlays
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (MinecraftClient.getInstance().currentScreen == null) {
                SortProgressOverlay.render(drawContext);
                OverflowNotificationOverlay.render(drawContext, 0f);
            }
        });

        // Register overflow input handlers
        OverflowInputHandler.register();
        registerOverflowInputHandlers();

        // Bulk mode editing on the Chests tab
        BulkEditController.register();

        // Whitelist state for an open Output Probe screen
        ClientPlayNetworking.registerGlobalReceiver(WhitelistUpdatePayload.ID,
                (payload, context) -> context.client().execute(() -> ProbeWhitelistPanel.onServerState(payload)));

        // ========================================
        // PACKET HANDLERS (NO DUPLICATES!)
        // ========================================

        // Main sync packet (items, XP, cursor)
        ClientPlayNetworking.registerGlobalReceiver(
                StorageControllerSyncPacket.SyncPayload.ID_PAYLOAD,
                (payload, context) -> context.client().execute(() -> {
                    if (context.player() != null &&
                            context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {

                        handler.updateNetworkItems(payload.items());
                        handler.updateStoredXp(payload.storedXp());

                        // CRITICAL FIX: Only update configs when provided (non-empty)
                        if (!payload.probeConfigs().isEmpty()) {
                            handler.clearProbeConfigs();
                            handler.updateProbeConfigs(payload.probeConfigs());
                        }
                        // DON'T clear chest configs - they're sent separately

                        handler.setCursorStack(payload.cursorStack());

                        if (context.client().currentScreen instanceof StorageControllerScreen screen) {
                            screen.markDirty();
                        }
                    }
                })
        );


        // Chest config batch
        ClientPlayNetworking.registerGlobalReceiver(
                ChestConfigBatchPayload.ID,
                (payload, context) -> context.client().execute(() -> {
                    if (context.player() != null &&
                            context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {

                        handler.updateChestConfigs(payload.configs());

                        if (context.client().currentScreen instanceof StorageControllerScreen screen) {
                            screen.markDirty();
                        }
                    }
                })
        );

        // Chest config single update
        ClientPlayNetworking.registerGlobalReceiver(
                ChestConfigUpdatePayload.ID,
                (payload, context) -> context.client().execute(() -> {

                    if (context.client().player != null) {
                        // Handle controller screen
                        if (context.client().player.currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                            Map<BlockPos, ChestConfig> singleUpdate = new HashMap<>();
                            singleUpdate.put(payload.config().position, payload.config());
                            handler.updateChestConfigs(singleUpdate);

                            if (context.client().currentScreen instanceof StorageControllerScreen screen) {
                                screen.markDirty();
                            }
                        }
                        // Handle probe screen
                        else if (context.client().player.currentScreenHandler instanceof OutputProbeScreenHandler handler) {
                            handler.setChestConfig(payload.config());

                            if (context.client().currentScreen instanceof OutputProbeScreen screen) {
                                screen.refreshConfig();
                            }
                        }
                    }
                })
        );

        // Probe stats sync
        ClientPlayNetworking.registerGlobalReceiver(
                ProbeStatsSyncPayload.ID,
                (payload, context) -> context.client().execute(() -> {
                    if (context.player() != null &&
                            context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {

                        handler.updateProbeStats(payload.position(), payload.itemsProcessed());

                        if (context.client().currentScreen instanceof StorageControllerScreen screen) {
                            screen.updateProbeStats(payload.position(), payload.itemsProcessed());
                            screen.markDirty();
                        }
                    }
                })
        );

        // Probe config batch
        ClientPlayNetworking.registerGlobalReceiver(
                ProbeConfigBatchPayload.ID,
                (payload, context) -> context.client().execute(() -> {
                    if (context.player() != null &&
                            context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {

                        handler.updateProbeConfigs(payload.configs());

                        if (context.client().currentScreen instanceof StorageControllerScreen screen) {
                            screen.markDirty();
                        }
                    }
                })
        );

        // Chest priority batch
        ClientPlayNetworking.registerGlobalReceiver(
                ChestPriorityBatchPayload.ID,
                (payload, context) -> context.client().execute(() -> {

                    if (context.client().player != null &&
                            context.client().player.currentScreenHandler instanceof StorageControllerScreenHandler handler) {

                        handler.applyPriorityUpdatesFromServer(payload.updates());

                        if (context.client().currentScreen instanceof StorageControllerScreen screen) {
                            screen.onPriorityUpdate();
                        }
                    }
                })
        );

        // Overflow notification
        ClientPlayNetworking.registerGlobalReceiver(
                OverflowNotificationPayload.ID,
                (payload, context) -> context.client().execute(() -> {
                    OverflowNotificationOverlay.show(
                            payload.overflowedItems(),
                            payload.overflowDestinations()
                    );
                })
        );

        // Delta sync
        ClientPlayNetworking.registerGlobalReceiver(
                StorageDeltaSyncPayload.ID,
                (payload, context) -> context.client().execute(() -> {
                    if (context.player() != null &&
                            context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {

                        // Create a mutable copy of current items
                        Map<ItemVariant, Long> updatedItems = new HashMap<>(handler.getNetworkItems());

                        // Apply deltas to the mutable copy
                        for (Map.Entry<ItemVariant, Long> entry : payload.changedItems().entrySet()) {
                            if (entry.getValue() > 0) {
                                updatedItems.put(entry.getKey(), entry.getValue());
                            } else {
                                updatedItems.remove(entry.getKey());
                            }
                        }

                        // Update handler with the modified map
                        handler.updateNetworkItems(updatedItems);

                        if (context.client().currentScreen instanceof StorageControllerScreen screen) {
                            screen.markDirty();
                        }
                    }
                })
        );

        // Sort progress
        ClientPlayNetworking.registerGlobalReceiver(
                SortProgressPayload.ID,
                (payload, context) -> context.client().execute(() -> {
                    SortProgressOverlay.updateProgress(
                            payload.current(),
                            payload.total(),
                            payload.isComplete()
                    );
                })
        );

        ClientPlayNetworking.registerGlobalReceiver(
                CategorySyncPayload.ID,
                (payload, context) -> context.client().execute(() -> {
                    CategoryManager.getInstance().updateFromServer(payload.categories());

                    // Refresh screen if open
                    if (context.client().currentScreen instanceof StorageControllerScreen screen) {
                        screen.markDirty();
                    }
                })
        );

    }

    private void registerBlockRenderLayers() {
        //? if <1.21.8 {
    /*// For 1.21.1 - Uses the old API
    net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap.INSTANCE.putBlock(
        SmartSorter.INTAKE_BLOCK, net.minecraft.client.render.RenderLayer.getSolid()
    );
    net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap.INSTANCE.putBlock(
        SmartSorter.PROBE_BLOCK, net.minecraft.client.render.RenderLayer.getSolid()
    );
    net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap.INSTANCE.putBlock(
        SmartSorter.PROCESS_PROBE_BLOCK, net.minecraft.client.render.RenderLayer.getSolid()
    );
    net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap.INSTANCE.putBlock(
        SmartSorter.STORAGE_CONTROLLER_BLOCK, net.minecraft.client.render.RenderLayer.getSolid()
    );
    *///?} else {
        // For 1.21.8+ - Uses BlockRenderLayer enum
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(
                SmartSorter.INTAKE_BLOCK, net.minecraft.client.render.BlockRenderLayer.SOLID
        );
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(
                SmartSorter.PROBE_BLOCK, net.minecraft.client.render.BlockRenderLayer.SOLID
        );
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(
                SmartSorter.PROCESS_PROBE_BLOCK, net.minecraft.client.render.BlockRenderLayer.SOLID
        );
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(
                SmartSorter.STORAGE_CONTROLLER_BLOCK, net.minecraft.client.render.BlockRenderLayer.SOLID
        );
        //?}
    }

    private void registerOverflowInputHandlers() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.currentScreen != null) return;

            long window = client.getWindow().getHandle();

            if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_X) == GLFW.GLFW_PRESS) {
                if (OverflowNotificationOverlay.isActive()) {
                    OverflowNotificationOverlay.dismiss();
                }
            }

            if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_UP) == GLFW.GLFW_PRESS) {
                OverflowNotificationOverlay.scroll(-1);
            }

            if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_DOWN) == GLFW.GLFW_PRESS) {
                OverflowNotificationOverlay.scroll(1);
            }
        });
    }
}