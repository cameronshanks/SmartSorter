package net.shaddii.smartsorter.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.shaddii.smartsorter.SmartSorter;

public record CollectXpPayload() implements CustomPacketPayload {
    public static final Type<CollectXpPayload> ID =
            new Type<>(Identifier.fromNamespaceAndPath(SmartSorter.MOD_ID, "collect_xp"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CollectXpPayload> CODEC =
            StreamCodec.ofMember((buf, payload) -> {}, buf -> new CollectXpPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}