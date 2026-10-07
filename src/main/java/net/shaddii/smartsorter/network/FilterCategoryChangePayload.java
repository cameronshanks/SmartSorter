package net.shaddii.smartsorter.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.util.Category;
import net.shaddii.smartsorter.util.CategoryManager;

public record FilterCategoryChangePayload(String categoryId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<FilterCategoryChangePayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SmartSorter.MOD_ID, "filter_category_change"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FilterCategoryChangePayload> CODEC =
            StreamCodec.ofMember(
                    (value, buf) -> buf.writeUtf(value.categoryId()),
                    buf -> new FilterCategoryChangePayload(buf.readUtf())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public Category getCategory() {
        return CategoryManager.getInstance().getCategory(categoryId);
    }
}