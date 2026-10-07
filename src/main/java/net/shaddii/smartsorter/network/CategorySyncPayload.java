package net.shaddii.smartsorter.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.shaddii.smartsorter.SmartSorter;

import java.util.ArrayList;
import java.util.List;

public record CategorySyncPayload(List<CategoryData> categories) implements CustomPacketPayload {
    public static final Type<CategorySyncPayload> ID =
            new Type<>(Identifier.fromNamespaceAndPath(SmartSorter.MOD_ID, "category_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CategorySyncPayload> CODEC =
            StreamCodec.ofMember(CategorySyncPayload::write, CategorySyncPayload::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(categories.size());
        for (CategoryData cat : categories) {
            buf.writeUtf(cat.id());
            buf.writeUtf(cat.displayName());
            buf.writeUtf(cat.shortName());
            buf.writeVarInt(cat.order());

            // Write item patterns
            buf.writeVarInt(cat.items().size());
            for (String item : cat.items()) {
                buf.writeUtf(item);
            }
        }
    }

    private static CategorySyncPayload read(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<CategoryData> categories = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = buf.readUtf();
            String displayName = buf.readUtf();
            String shortName = buf.readUtf();
            int order = buf.readVarInt();

            // Read item patterns
            int itemCount = buf.readVarInt();
            List<String> items = new ArrayList<>();
            for (int j = 0; j < itemCount; j++) {
                items.add(buf.readUtf());
            }

            categories.add(new CategoryData(id, displayName, shortName, order, items));
        }
        return new CategorySyncPayload(categories);
    }

    public record CategoryData(String id, String displayName, String shortName, int order, List<String> items) {}
}