package net.shaddii.smartsorter.network;

import java.util.List;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.screen.OutputProbeScreenHandler;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;

/**
 * Sends a WhitelistUpdatePayload to every player currently looking at the
 * chest it describes - either through the Storage Controller's Chests tab
 * (matched by controller identity) or an Output Probe pointed at that same
 * chest (matched by chestPos) - so every open view of a chest's whitelist
 * stays in sync regardless of which screen (or block interaction) changed
 * it. Used by WhitelistNetworking's C2S handlers and by
 * OutputProbeBlock's click-to-edit gesture.
 */
public final class WhitelistBroadcast {

    private WhitelistBroadcast() {
    }

    public static void toViewers(MinecraftServer server, StorageControllerBlockEntity controller, WhitelistUpdatePayload payload) {
        if (server == null) {
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer viewer : players) {
            if (viewer.containerMenu instanceof StorageControllerScreenHandler handler && handler.controller == controller) {
                ServerPlayNetworking.send(viewer, payload);
            } else if (viewer.containerMenu instanceof OutputProbeScreenHandler probeHandler
                    && payload.position().equals(probeHandler.chestPos)) {
                ServerPlayNetworking.send(viewer, payload);
            }
        }
    }
}
