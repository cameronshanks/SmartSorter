package net.shaddii.smartsorter.network;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/**
 * Server -> client only: reports a chest's whitelist state - whether sorting
 * is enabled, whether edit mode is on, and the current item list - either as
 * the reply to a WhitelistRequestPayload, or as the echo sent to every
 * viewer after a WhitelistToggleEnabledPayload/WhitelistToggleEditModePayload
 * or a sneak... actually plain-click add/remove on the Output Probe (see
 * OutputProbeBlock.onUse) changes something.
 *
 * There is deliberately no client -> server direction anymore: the only
 * thing that ever sent one client-side was the Storage Controller's
 * grid-based whitelist editor, which got removed (it crashed with larger
 * whitelists and was redundant with editing via the Output Probe). Editing
 * now only ever happens through the two toggle payloads and the probe's own
 * block-click gesture, all of which mutate server-side state directly and
 * broadcast the result - nothing needs to send a client-built item list
 * anymore.
 */
public record WhitelistUpdatePayload(BlockPos position, boolean enabled, boolean editMode, List<Item> items) implements CustomPacketPayload {

    // A hard ceiling on the wire so a malformed/hostile packet can't make
    // either side allocate an unbounded list.
    public static final int MAX_ITEMS = 64;

    public static final CustomPacketPayload.Type<WhitelistUpdatePayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("smartsorter", "whitelist_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistUpdatePayload> CODEC = new StreamCodec<>() {
        @Override
        public WhitelistUpdatePayload decode(RegistryFriendlyByteBuf buf) {
            return WhitelistUpdatePayload.read(buf);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, WhitelistUpdatePayload payload) {
            WhitelistUpdatePayload.write(buf, payload);
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    private static void write(RegistryFriendlyByteBuf buf, WhitelistUpdatePayload payload) {
        buf.writeBlockPos(payload.position);
        buf.writeBoolean(payload.enabled);
        buf.writeBoolean(payload.editMode);
        buf.writeVarInt(payload.items.size());
        for (Item item : payload.items) {
            buf.writeIdentifier(BuiltInRegistries.ITEM.getKey(item));
        }
    }

    private static WhitelistUpdatePayload read(RegistryFriendlyByteBuf buf) {
        BlockPos position = buf.readBlockPos();
        boolean enabled = buf.readBoolean();
        boolean editMode = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ITEMS) {
            throw new IllegalArgumentException("WhitelistUpdatePayload item count out of range: " + count);
        }
        List<Item> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(BuiltInRegistries.ITEM.getValue(buf.readIdentifier()));
        }
        return new WhitelistUpdatePayload(position, enabled, editMode, items);
    }

    /** Called once from the common entrypoint - registering a payload type is
     *  symmetric and must happen identically on both sides before either
     *  side is allowed to send it, even though only the server ever does. */
    public static void registerCodec() {
        PayloadTypeRegistry.clientboundPlay().register(ID, CODEC);
    }
}
