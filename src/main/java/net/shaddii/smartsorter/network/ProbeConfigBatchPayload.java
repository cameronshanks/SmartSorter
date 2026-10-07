package net.shaddii.smartsorter.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.util.ProcessProbeConfig;
import net.shaddii.smartsorter.util.RecipeFilterMode;
import net.shaddii.smartsorter.util.FuelFilterMode;

import java.util.HashMap;
import java.util.Map;

public record ProbeConfigBatchPayload(Map<BlockPos, ProcessProbeConfig> configs) implements CustomPacketPayload {
    public static final Type<ProbeConfigBatchPayload> ID =
            new Type<>(Identifier.fromNamespaceAndPath(SmartSorter.MOD_ID, "probe_config_batch"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProbeConfigBatchPayload> CODEC =
            StreamCodec.ofMember(
                    (value, buf) -> write(buf, value),  // Fixed: swapped parameter order
                    buf -> read(buf)
            );

    private static void write(RegistryFriendlyByteBuf buf, ProbeConfigBatchPayload payload) {
        buf.writeVarInt(payload.configs.size());

        for (Map.Entry<BlockPos, ProcessProbeConfig> entry : payload.configs.entrySet()) {
            buf.writeLong(entry.getKey().asLong());
            ProcessProbeConfig config = entry.getValue();

            buf.writeUtf(config.machineType);
            buf.writeBoolean(config.customName != null);
            if (config.customName != null) {
                buf.writeUtf(config.customName);
            }
            buf.writeBoolean(config.enabled);
            buf.writeUtf(config.recipeFilter.asString());
            buf.writeUtf(config.fuelFilter.asString());
            buf.writeVarInt(config.itemsProcessed);
            buf.writeVarInt(config.index);
        }
    }

    private static ProbeConfigBatchPayload read(RegistryFriendlyByteBuf buf) {
        Map<BlockPos, ProcessProbeConfig> configs = new HashMap<>();
        int count = buf.readVarInt();

        for (int i = 0; i < count; i++) {
            BlockPos pos = BlockPos.of(buf.readLong());
            ProcessProbeConfig config = new ProcessProbeConfig();
            config.position = pos;
            config.machineType = buf.readUtf();

            if (buf.readBoolean()) {
                config.customName = buf.readUtf();
            }

            config.enabled = buf.readBoolean();
            config.recipeFilter = RecipeFilterMode.fromString(buf.readUtf());
            config.fuelFilter = FuelFilterMode.fromString(buf.readUtf());
            config.itemsProcessed = buf.readVarInt();
            config.index = buf.readVarInt();

            configs.put(pos, config);
        }

        return new ProbeConfigBatchPayload(configs);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}