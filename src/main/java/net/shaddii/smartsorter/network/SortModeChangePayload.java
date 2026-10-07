package net.shaddii.smartsorter.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.util.SortMode;

/**
 * Client -> Server packet to change sort mode in Storage Controller.
 */
public record SortModeChangePayload(String sortMode) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SortModeChangePayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SmartSorter.MOD_ID, "sort_mode_change"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SortModeChangePayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, SortModeChangePayload::sortMode,
                    SortModeChangePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public SortMode getSortMode() {
        return SortMode.fromString(sortMode);
    }
}