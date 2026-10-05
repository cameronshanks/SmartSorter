package net.shaddii.smartsorter.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

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
public record WhitelistRequestPayload(BlockPos position) implements CustomPayload {

    public static final CustomPayload.Id<WhitelistRequestPayload> ID =
            new CustomPayload.Id<>(Identifier.of("smartsorter", "whitelist_request"));

    public static final PacketCodec<RegistryByteBuf, WhitelistRequestPayload> CODEC = new PacketCodec<>() {
        @Override
        public WhitelistRequestPayload decode(RegistryByteBuf buf) {
            return new WhitelistRequestPayload(buf.readBlockPos());
        }

        @Override
        public void encode(RegistryByteBuf buf, WhitelistRequestPayload payload) {
            buf.writeBlockPos(payload.position);
        }
    };

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /** C2S only - the server never sends this one. */
    public static void registerCodec() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
    }
}
