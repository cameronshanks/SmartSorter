package net.shaddii.smartsorter.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -> server only. Flips a chest's whitelist ENABLED flag (whether the
 * whitelist actually filters items) in place, without touching its item
 * list or its edit-mode flag - one of two independent toggles the Output
 * Probe screen exposes, alongside WhitelistToggleEditModePayload. Neither
 * button ever holds a client-side copy of the current item list, so both
 * flip their one flag server-side rather than sending a full
 * WhitelistUpdatePayload that could overwrite the list with a stale or
 * empty copy.
 */
public record WhitelistToggleEnabledPayload(BlockPos position) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<WhitelistToggleEnabledPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("smartsorter", "whitelist_toggle_enabled"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistToggleEnabledPayload> CODEC = new StreamCodec<>() {
        @Override
        public WhitelistToggleEnabledPayload decode(RegistryFriendlyByteBuf buf) {
            return new WhitelistToggleEnabledPayload(buf.readBlockPos());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, WhitelistToggleEnabledPayload payload) {
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
