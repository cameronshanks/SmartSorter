package net.shaddii.smartsorter.network;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.screen.OutputProbeScreenHandler;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.util.RoutingIndexEpoch;

/**
 * Whitelist payload registration and server-side handlers. Common code: it
 * must never reference ClientPlayNetworking or other client-only classes
 * (the client receiver lives in SmartSorterClient).
 */
public final class WhitelistNetworking {

    private WhitelistNetworking() {
    }

    public static void register() {
        WhitelistUpdatePayload.registerCodec();
        WhitelistRequestPayload.registerCodec();
        WhitelistToggleEnabledPayload.registerCodec();
        WhitelistToggleEditModePayload.registerCodec();

        // The Output Probe screen asking for the current state.
        ServerPlayNetworking.registerGlobalReceiver(WhitelistRequestPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayer player = context.player();
            ChestConfig config = configFor(player, payload.position());
            if (config != null) {
                ServerPlayNetworking.send(player, snapshotOf(payload.position(), config));
            }
        }));

        // "Sorting: ON/OFF"
        ServerPlayNetworking.registerGlobalReceiver(WhitelistToggleEnabledPayload.ID, (payload, context) -> context.server().execute(() -> {
            StorageControllerBlockEntity controller = controllerOf(context.player());
            ChestConfig config = configFor(context.player(), payload.position());
            if (controller == null || config == null) {
                return;
            }
            config.whitelistEnabled = !config.whitelistEnabled;
            controller.setChanged();
            RoutingIndexEpoch.bump(); // filter edit
            WhitelistBroadcast.toViewers(context.server(), controller, snapshotOf(payload.position(), config));
        }));

        // "Editing: ON/OFF"
        ServerPlayNetworking.registerGlobalReceiver(WhitelistToggleEditModePayload.ID, (payload, context) -> context.server().execute(() -> {
            StorageControllerBlockEntity controller = controllerOf(context.player());
            ChestConfig config = configFor(context.player(), payload.position());
            if (controller == null || config == null) {
                return;
            }
            config.whitelistEditMode = !config.whitelistEditMode;
            controller.setChanged();
            WhitelistBroadcast.toViewers(context.server(), controller, snapshotOf(payload.position(), config));
        }));
    }

    public static WhitelistUpdatePayload snapshotOf(BlockPos position, ChestConfig config) {
        List<Item> items = new ArrayList<>(config.getWhitelist());
        return new WhitelistUpdatePayload(position, config.whitelistEnabled, config.whitelistEditMode, items);
    }

    /** The controller behind the player's open screen - same lookup as ChestConfigUpdatePayload's handler. */
    private static StorageControllerBlockEntity controllerOf(ServerPlayer player) {
        if (player.containerMenu instanceof StorageControllerScreenHandler mainHandler) {
            return mainHandler.controller;
        }
        if (player.containerMenu instanceof OutputProbeScreenHandler probeHandler) {
            return probeHandler.controller;
        }
        return null;
    }

    private static ChestConfig configFor(ServerPlayer player, BlockPos chestPos) {
        StorageControllerBlockEntity controller = controllerOf(player);
        return controller == null ? null : controller.getChestConfig(chestPos);
    }
}
