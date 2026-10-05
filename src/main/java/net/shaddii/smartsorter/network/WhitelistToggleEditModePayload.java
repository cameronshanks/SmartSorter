package net.shaddii.smartsorter.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Client -> server only. Flips a chest's whitelist EDIT MODE flag (whether
 * plain-click-with-item on the linked Output Probe adds/removes items,
 * instead of just testing them - see OutputProbeBlock.onUse) in place,
 * without touching its item list or its enabled/sorting flag. The other
 * independent toggle is WhitelistToggleEnabledPayload - see its class
 * comment for why both flip their one flag server-side.
 */
public record WhitelistToggleEditModePayload(BlockPos position) implements CustomPayload {

    public static final CustomPayload.Id<WhitelistToggleEditModePayload> ID =
            new CustomPayload.Id<>(Identifier.of("smartsorter", "whitelist_toggle_edit_mode"));

    public static final PacketCodec<RegistryByteBuf, WhitelistToggleEditModePayload> CODEC = new PacketCodec<>() {
        @Override
        public WhitelistToggleEditModePayload decode(RegistryByteBuf buf) {
            return new WhitelistToggleEditModePayload(buf.readBlockPos());
        }

        @Override
        public void encode(RegistryByteBuf buf, WhitelistToggleEditModePayload payload) {
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
