package net.shaddii.smartsorter.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.shaddii.smartsorter.SmartSorter;

public record ProbeStatsSyncPayload(
        BlockPos position,
        int itemsProcessed
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ProbeStatsSyncPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SmartSorter.MOD_ID, "probe_stats_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProbeStatsSyncPayload> CODEC =
            StreamCodec.ofMember(
                    (value, buf) -> write(buf, value),
                    buf -> read(buf)
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public static void write(RegistryFriendlyByteBuf buf, ProbeStatsSyncPayload payload) {
        buf.writeLong(payload.position.asLong());
        buf.writeInt(payload.itemsProcessed);
    }

    public static ProbeStatsSyncPayload read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        int itemsProcessed = buf.readInt();
        return new ProbeStatsSyncPayload(pos, itemsProcessed);
    }
}