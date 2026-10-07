package net.shaddii.smartsorter.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.shaddii.smartsorter.SmartSorter;

import java.util.ArrayList;
import java.util.List;

/**
 * A single payload to request sorting of one or more inventories.
 * The inventories are sorted by the server in the order they appear in the list.
 */
public record SortChestsPayload(List<BlockPos> sortedPositions) implements CustomPacketPayload {

    public static final Type<SortChestsPayload> ID =
            new Type<>(Identifier.fromNamespaceAndPath(SmartSorter.MOD_ID, "sort_chests"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SortChestsPayload> CODEC = StreamCodec.ofMember(
            // Encoder: Writes the list of BlockPos to the buffer
            (payload, buf) -> buf.writeCollection(payload.sortedPositions, BlockPos.STREAM_CODEC),
            // Decoder: Reads the list of BlockPos from the buffer
            buf -> new SortChestsPayload(buf.readCollection(ArrayList::new, BlockPos.STREAM_CODEC))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}