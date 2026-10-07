package net.shaddii.smartsorter.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.shaddii.smartsorter.SmartSorter;

public record SortProgressPayload(
        int current,
        int total,
        boolean isComplete
) implements CustomPacketPayload {

    public static final Type<SortProgressPayload> ID =
            new Type<>(Identifier.fromNamespaceAndPath(SmartSorter.MOD_ID, "sort_progress"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SortProgressPayload> CODEC = StreamCodec.ofMember(
            (payload, buf) -> {
                buf.writeInt(payload.current);
                buf.writeInt(payload.total);
                buf.writeBoolean(payload.isComplete);
            },
            buf -> {
                int current = buf.readInt();
                int total = buf.readInt();
                boolean isComplete = buf.readBoolean();
                return new SortProgressPayload(current, total, isComplete);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}