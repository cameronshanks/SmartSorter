package net.shaddii.smartsorter.network;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.shaddii.smartsorter.SmartSorter;

import java.util.HashMap;
import java.util.Map;

public record StorageDeltaSyncPayload(
        Map<ItemVariant, Long> changedItems
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StorageDeltaSyncPayload> ID = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SmartSorter.MOD_ID, "storage_delta_sync"));

    private static final StreamCodec<RegistryFriendlyByteBuf, Map<ItemVariant, Long>> MAP_CODEC =
            ByteBufCodecs.map(HashMap::new, ItemVariant.PACKET_CODEC, ByteBufCodecs.VAR_LONG);

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageDeltaSyncPayload> CODEC =
            MAP_CODEC.map(StorageDeltaSyncPayload::new, StorageDeltaSyncPayload::changedItems);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}