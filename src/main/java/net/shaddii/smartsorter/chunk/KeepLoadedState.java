package net.shaddii.smartsorter.chunk;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

/**
 * Per-dimension saved data for the chunk keeper: which blocks (by packed
 * BlockPos) asked to keep which chunks (packed ChunkPos) loaded. Saved with
 * the world, so tickets can be restored on server start before any of those
 * chunks (and therefore their block entities) have loaded.
 *
 * The tickets themselves are NOT serialized by vanilla (the ticket type has
 * no SERIALIZE flag), so turning the option off and restarting leaves nothing
 * behind: ChunkKeeper clears this state instead of restoring it.
 */
public final class KeepLoadedState extends PersistentState {

    /** Each entry is [ownerPos, chunk, chunk, ...]. */
    private static final Codec<KeepLoadedState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.listOf().listOf().fieldOf("owners").forGetter(KeepLoadedState::toLists)
    ).apply(instance, KeepLoadedState::fromLists));

    // DataFixTypes can't be null here (PersistentStateManager.readNbt calls
    // update() on it unconditionally); this type applies no fixes to custom data.
    public static final PersistentStateType<KeepLoadedState> TYPE = new PersistentStateType<>(
            "smartsorter_keep_loaded", KeepLoadedState::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    final Long2ObjectOpenHashMap<long[]> owners = new Long2ObjectOpenHashMap<>();

    public KeepLoadedState() {
    }

    private static KeepLoadedState fromLists(List<List<Long>> lists) {
        KeepLoadedState state = new KeepLoadedState();
        for (List<Long> entry : lists) {
            if (entry.size() < 2) {
                continue;
            }
            long[] chunks = new long[entry.size() - 1];
            for (int i = 1; i < entry.size(); i++) {
                chunks[i - 1] = entry.get(i);
            }
            state.owners.put(entry.get(0).longValue(), chunks);
        }
        return state;
    }

    private List<List<Long>> toLists() {
        List<List<Long>> lists = new ArrayList<>(this.owners.size());
        for (Long2ObjectMap.Entry<long[]> entry : this.owners.long2ObjectEntrySet()) {
            List<Long> list = new ArrayList<>(entry.getValue().length + 1);
            list.add(entry.getLongKey());
            for (long chunk : entry.getValue()) {
                list.add(chunk);
            }
            lists.add(list);
        }
        return lists;
    }
}
