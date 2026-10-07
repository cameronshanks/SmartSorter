package net.shaddii.smartsorter.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.util.ChestConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight payload for bulk priority updates
 * Only sends position + priority + SimplePriority (not full config)
 */
public record ChestPriorityBatchPayload(Map<BlockPos, PriorityUpdate> updates) implements CustomPacketPayload {

    public static final Type<ChestPriorityBatchPayload> ID =
            new Type<>(Identifier.fromNamespaceAndPath(SmartSorter.MOD_ID, "chest_priority_batch"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChestPriorityBatchPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public ChestPriorityBatchPayload decode(RegistryFriendlyByteBuf buf) {
                    return ChestPriorityBatchPayload.read(buf);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ChestPriorityBatchPayload payload) {
                    ChestPriorityBatchPayload.write(buf, payload);
                }
            };

    public record PriorityUpdate(
            int priority,
            ChestConfig.SimplePriority simplePriority,
            int hiddenPriority,
            int cachedFullness
    ) {}

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public static void write(RegistryFriendlyByteBuf buf, ChestPriorityBatchPayload payload) {
        buf.writeVarInt(payload.updates.size());

        for (Map.Entry<BlockPos, PriorityUpdate> entry : payload.updates.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue().priority);

            // Handle null SimplePriority
            if (entry.getValue().simplePriority != null) {
                buf.writeBoolean(true);
                buf.writeEnum(entry.getValue().simplePriority);
            } else {
                buf.writeBoolean(false);
            }

            buf.writeVarInt(entry.getValue().hiddenPriority);
            buf.writeVarInt(entry.getValue().cachedFullness);
        }
    }

    public static ChestPriorityBatchPayload read(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<BlockPos, PriorityUpdate> updates = new HashMap<>();

        for (int i = 0; i < size; i++) {
            BlockPos pos = buf.readBlockPos();
            int priority = buf.readVarInt();

            // Handle null SimplePriority
            ChestConfig.SimplePriority simplePriority = null;
            if (buf.readBoolean()) {
                simplePriority = buf.readEnum(ChestConfig.SimplePriority.class);
            }

            int hiddenPriority = buf.readVarInt();
            int cachedFullness = buf.readVarInt();

            updates.put(pos, new PriorityUpdate(priority, simplePriority, hiddenPriority, cachedFullness));
        }

        return new ChestPriorityBatchPayload(updates);
    }

    /**
     * Create from chest configs (extracts only priority data)
     */
    public static ChestPriorityBatchPayload fromConfigs(Map<BlockPos, ChestConfig> configs) {
        Map<BlockPos, PriorityUpdate> updates = new HashMap<>();

        for (Map.Entry<BlockPos, ChestConfig> entry : configs.entrySet()) {
            ChestConfig config = entry.getValue();
            updates.put(
                    entry.getKey(),
                    new PriorityUpdate(
                            config.priority,
                            config.simplePrioritySelection,
                            config.hiddenPriority,
                            config.cachedFullness
                    )
            );
        }

        return new ChestPriorityBatchPayload(updates);
    }

}