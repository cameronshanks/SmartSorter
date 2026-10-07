package net.shaddii.smartsorter.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.util.FuelFilterMode;
import net.shaddii.smartsorter.util.ProcessProbeConfig;
import net.shaddii.smartsorter.util.RecipeFilterMode;

public record ProbeConfigUpdatePayload(
        BlockPos position,
        String customName,
        boolean enabled,
        RecipeFilterMode recipeFilter,
        FuelFilterMode fuelFilter
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ProbeConfigUpdatePayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SmartSorter.MOD_ID, "probe_config_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProbeConfigUpdatePayload> CODEC =
            StreamCodec.ofMember(
                    (value, buf) -> write(buf, value),
                    buf -> read(buf)
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public static void write(RegistryFriendlyByteBuf buf, ProbeConfigUpdatePayload payload) {
        buf.writeLong(payload.position.asLong());
        buf.writeBoolean(payload.customName != null);
        if (payload.customName != null) {
            buf.writeUtf(payload.customName);
        }
        buf.writeBoolean(payload.enabled);
        buf.writeUtf(payload.recipeFilter.asString());
        buf.writeUtf(payload.fuelFilter.asString());
    }

    public static ProbeConfigUpdatePayload read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        String customName = buf.readBoolean() ? buf.readUtf() : null;
        boolean enabled = buf.readBoolean();
        RecipeFilterMode recipeFilter = RecipeFilterMode.fromString(buf.readUtf());
        FuelFilterMode fuelFilter = FuelFilterMode.fromString(buf.readUtf());

        return new ProbeConfigUpdatePayload(pos, customName, enabled, recipeFilter, fuelFilter);
    }
}