package net.shaddii.smartsorter.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -> server only. Sent when the player opens the whitelist editor for
 * a chest, so the panel always shows the server's authoritative whitelist
 * rather than whatever the client's local ChestConfig copy happens to still
 * hold (Smart Sorter's own sync packets don't carry our fields at all, so a
 * stale/empty local copy is the common case, not the exception).
 *
 * The server answers with a WhitelistUpdatePayload sent directly back to the
 * requesting player (see WhitelistNetworking).
 */
public record WhitelistRequestPayload(BlockPos position) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<WhitelistRequestPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("smartsorter", "whitelist_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistRequestPayload> CODEC = new StreamCodec<>() {
        @Override
        public WhitelistRequestPayload decode(RegistryFriendlyByteBuf buf) {
            return new WhitelistRequestPayload(buf.readBlockPos());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, WhitelistRequestPayload payload) {
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
