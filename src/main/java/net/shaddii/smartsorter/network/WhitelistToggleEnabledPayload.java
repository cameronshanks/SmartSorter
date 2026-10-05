package net.shaddii.smartsorter.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

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
public record WhitelistToggleEnabledPayload(BlockPos position) implements CustomPayload {

    public static final CustomPayload.Id<WhitelistToggleEnabledPayload> ID =
            new CustomPayload.Id<>(Identifier.of("smartsorter", "whitelist_toggle_enabled"));

    public static final PacketCodec<RegistryByteBuf, WhitelistToggleEnabledPayload> CODEC = new PacketCodec<>() {
        @Override
        public WhitelistToggleEnabledPayload decode(RegistryByteBuf buf) {
            return new WhitelistToggleEnabledPayload(buf.readBlockPos());
        }

        @Override
        public void encode(RegistryByteBuf buf, WhitelistToggleEnabledPayload payload) {
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
