package net.shaddii.smartsorter.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.util.Category;
import net.shaddii.smartsorter.util.CategoryManager;
import net.shaddii.smartsorter.util.ChestConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record ChestConfigBatchPayload(Map<BlockPos, ChestConfig> configs) implements CustomPacketPayload {

    public static final Type<ChestConfigBatchPayload> ID =
            new Type<>(Identifier.fromNamespaceAndPath(SmartSorter.MOD_ID, "chest_config_batch"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChestConfigBatchPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public ChestConfigBatchPayload decode(RegistryFriendlyByteBuf buf) {
                    return ChestConfigBatchPayload.read(buf);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ChestConfigBatchPayload payload) {
                    ChestConfigBatchPayload.write(buf, payload);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public static void write(RegistryFriendlyByteBuf buf, ChestConfigBatchPayload payload) {
        buf.writeVarInt(payload.configs.size());

        for (Map.Entry<BlockPos, ChestConfig> entry : payload.configs.entrySet()) {
            ChestConfig config = entry.getValue();

            // Write position
            buf.writeBlockPos(config.position);

            // Write custom name
            buf.writeUtf(config.customName != null ? config.customName : "");

            // Write filter category
            buf.writeUtf(config.filterCategory.asString());

            // Write priority
            buf.writeVarInt(config.priority);

            // Write filter mode
            buf.writeUtf(config.filterMode.name());

            // Write auto item frame
            buf.writeBoolean(config.autoItemFrame);

            // Match NBT
            buf.writeBoolean(config.strictNBTMatch);

            // Calculate Fullness
            buf.writeVarInt(config.cachedFullness);

            // ✅ FIX: Write SimplePriority
            if (config.simplePrioritySelection != null) {
                buf.writeBoolean(true); // Has SimplePriority
                buf.writeUtf(config.simplePrioritySelection.name());
            } else {
                buf.writeBoolean(false); // No SimplePriority
            }

            // Preview Items
            buf.writeVarInt(config.previewItems.size());
            for (ItemStack stack : config.previewItems) {
                ItemStack.STREAM_CODEC.encode(buf, stack);
            }
        }
    }

    public static ChestConfigBatchPayload read(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<BlockPos, ChestConfig> configs = new HashMap<>(size);

        for (int i = 0; i < size; i++) {
            // Read position
            BlockPos position = buf.readBlockPos();

            // Read custom name
            String customName = buf.readUtf();

            // Read filter category
            String categoryStr = buf.readUtf();
            Category category = CategoryManager.getInstance().getCategory(categoryStr);

            // Read priority
            int priority = buf.readVarInt();

            // Read filter mode
            String modeStr = buf.readUtf();
            ChestConfig.FilterMode mode = ChestConfig.FilterMode.valueOf(modeStr);

            // Read auto item frame
            boolean autoItemFrame = buf.readBoolean();

            // Match NBT
            boolean strictNBTMatch = buf.readBoolean();

            // Calculate Fullness
            int cachedFullness = buf.readVarInt();

            ChestConfig.SimplePriority simplePriority = null;
            boolean hasSimplePriority = buf.readBoolean();
            if (hasSimplePriority) {
                String simplePriorityStr = buf.readUtf();
                try {
                    simplePriority = ChestConfig.SimplePriority.valueOf(simplePriorityStr);
                } catch (IllegalArgumentException e) {
                    simplePriority = ChestConfig.SimplePriority.MEDIUM;
                }
            } else {
                simplePriority = null; // ChestConfig.SimplePriority.MEDIUM;
            }

            // Preview Items
            int previewSize = buf.readVarInt();
            List<ItemStack> previewItems = new ArrayList<>();
            for (int j = 0; j < previewSize; j++) {
                previewItems.add(ItemStack.STREAM_CODEC.decode(buf));
            }

            ChestConfig config = new ChestConfig(position, customName, category, priority, mode, autoItemFrame);
            config.strictNBTMatch = strictNBTMatch;
            config.cachedFullness = cachedFullness;
            config.simplePrioritySelection = simplePriority;
            config.previewItems = previewItems;
            configs.put(position, config);
        }

        return new ChestConfigBatchPayload(configs);
    }
}