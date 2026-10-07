package net.shaddii.smartsorter.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.util.Category;
import net.shaddii.smartsorter.util.CategoryManager;
import net.shaddii.smartsorter.util.ChestConfig;

public record ChestConfigUpdatePayload(ChestConfig config) implements CustomPacketPayload {

    public static final Type<ChestConfigUpdatePayload> ID =
            new Type<>(Identifier.fromNamespaceAndPath(SmartSorter.MOD_ID, "chest_config_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChestConfigUpdatePayload> CODEC =
            new StreamCodec<>() {
                @Override
                public ChestConfigUpdatePayload decode(RegistryFriendlyByteBuf buf) {
                    return ChestConfigUpdatePayload.read(buf);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ChestConfigUpdatePayload payload) {
                    ChestConfigUpdatePayload.write(buf, payload);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public static void write(RegistryFriendlyByteBuf buf, ChestConfigUpdatePayload payload) {
        ChestConfig config = payload.config;

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
    }

    public static ChestConfigUpdatePayload read(RegistryFriendlyByteBuf buf) {
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

        ChestConfig config = new ChestConfig(position, customName, category, priority, mode, autoItemFrame);
        config.strictNBTMatch = strictNBTMatch;
        config.cachedFullness = cachedFullness;

        // Read SimplePriority
        boolean hasSimplePriority = buf.readBoolean();
        if (hasSimplePriority) {
            String simplePriorityStr = buf.readUtf();
            try {
                config.simplePrioritySelection = ChestConfig.SimplePriority.valueOf(simplePriorityStr);
            } catch (IllegalArgumentException e) {
                config.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
            }
        } else {
            config.simplePrioritySelection = null; // ChestConfig.SimplePriority.MEDIUM;
        }

        return new ChestConfigUpdatePayload(config);
    }
}