package net.shaddii.smartsorter.network;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.item.Item;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

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
public record WhitelistUpdatePayload(BlockPos position, boolean enabled, boolean editMode, List<Item> items) implements CustomPayload {

    // A hard ceiling on the wire so a malformed/hostile packet can't make
    // either side allocate an unbounded list.
    public static final int MAX_ITEMS = 64;

    public static final CustomPayload.Id<WhitelistUpdatePayload> ID =
            new CustomPayload.Id<>(Identifier.of("smartsorter", "whitelist_update"));

    public static final PacketCodec<RegistryByteBuf, WhitelistUpdatePayload> CODEC = new PacketCodec<>() {
        @Override
        public WhitelistUpdatePayload decode(RegistryByteBuf buf) {
            return WhitelistUpdatePayload.read(buf);
        }

        @Override
        public void encode(RegistryByteBuf buf, WhitelistUpdatePayload payload) {
            WhitelistUpdatePayload.write(buf, payload);
        }
    };

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    private static void write(RegistryByteBuf buf, WhitelistUpdatePayload payload) {
        buf.writeBlockPos(payload.position);
        buf.writeBoolean(payload.enabled);
        buf.writeBoolean(payload.editMode);
        buf.writeVarInt(payload.items.size());
        for (Item item : payload.items) {
            buf.writeIdentifier(Registries.ITEM.getId(item));
        }
    }

    private static WhitelistUpdatePayload read(RegistryByteBuf buf) {
        BlockPos position = buf.readBlockPos();
        boolean enabled = buf.readBoolean();
        boolean editMode = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ITEMS) {
            throw new IllegalArgumentException("WhitelistUpdatePayload item count out of range: " + count);
        }
        List<Item> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(Registries.ITEM.get(buf.readIdentifier()));
        }
        return new WhitelistUpdatePayload(position, enabled, editMode, items);
    }

    /** Called once from the common entrypoint - registering a payload type is
     *  symmetric and must happen identically on both sides before either
     *  side is allowed to send it, even though only the server ever does. */
    public static void registerCodec() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }
}
