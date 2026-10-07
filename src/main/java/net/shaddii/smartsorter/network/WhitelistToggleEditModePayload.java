package net.shaddii.smartsorter.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -> server only. Flips a chest's whitelist EDIT MODE flag (whether
 * plain-click-with-item on the linked Output Probe adds/removes items,
 * instead of just testing them - see OutputProbeBlock.onUse) in place,
 * without touching its item list or its enabled/sorting flag. The other
 * independent toggle is WhitelistToggleEnabledPayload - see its class
 * comment for why both flip their one flag server-side.
 */
public record WhitelistToggleEditModePayload(BlockPos position) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<WhitelistToggleEditModePayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("smartsorter", "whitelist_toggle_edit_mode"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistToggleEditModePayload> CODEC = new StreamCodec<>() {
        @Override
        public WhitelistToggleEditModePayload decode(RegistryFriendlyByteBuf buf) {
            return new WhitelistToggleEditModePayload(buf.readBlockPos());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, WhitelistToggleEditModePayload payload) {
            buf.writeBlockPos(payload.position);
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    /** C2S only - the server never sends this one. */
    public static void registerCodec() {
        PayloadTypeRegistry.serverboundPlay().register(ID, CODEC);
    }
}
