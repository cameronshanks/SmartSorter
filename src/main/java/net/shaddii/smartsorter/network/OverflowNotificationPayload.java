package net.shaddii.smartsorter.network;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.shaddii.smartsorter.SmartSorter;

import java.util.HashMap;
import java.util.Map;

public record OverflowNotificationPayload(
        Map<ItemVariant, Long> overflowedItems,
        Map<ItemVariant, String> overflowDestinations
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OverflowNotificationPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SmartSorter.MOD_ID, "overflow_notification"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OverflowNotificationPayload> CODEC = StreamCodec.ofMember(
            (payload, buf) -> {
                buf.writeInt(payload.overflowedItems.size());
                for (Map.Entry<ItemVariant, Long> entry : payload.overflowedItems.entrySet()) {
                    ItemStack stack = entry.getKey().toStack();
                    ItemStack.STREAM_CODEC.encode(buf, stack);
                    buf.writeLong(entry.getValue());
                    String destination = payload.overflowDestinations.getOrDefault(entry.getKey(), "Unknown");
                    buf.writeUtf(destination);
                }
            },
            buf -> {
                int size = buf.readInt();
                Map<ItemVariant, Long> items = new HashMap<>();
                Map<ItemVariant, String> destinations = new HashMap<>();

                for (int i = 0; i < size; i++) {
                    ItemStack stack = ItemStack.STREAM_CODEC.decode(buf);
                    ItemVariant variant = ItemVariant.of(stack);
                    long count = buf.readLong();
                    String destination = buf.readUtf();

                    items.put(variant, count);
                    destinations.put(variant, destination);
                }

                return new OverflowNotificationPayload(items, destinations);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}