package net.shaddii.smartsorter.chunk;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.shaddii.smartsorter.block.IntakeBlock;
import net.shaddii.smartsorter.block.OutputProbeBlock;
import net.shaddii.smartsorter.blockentity.IntakeBlockEntity;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;

import net.shaddii.smartsorter.config.SmartSorterConfig;

/**
 * Optional "keep my sorting system loaded" support (config keepChunksLoaded,
 * default off).
 *
 * Each registered block (controller, intake, output probe) owns a small set of
 * chunks: its own, plus its target/source chest's (and a double chest's other
 * half, added when the probe first finds it). Chunks are reference-counted
 * across owners and hold one ticket while the count is above zero, so a chunk
 * is released only when the last block that needed it is removed.
 *
 * Only the normal ticket API is used (ServerChunkManager.addTicket /
 * removeTicket with a custom non-serialized ticket type), never direct chunk
 * loads, so C2ME and similar mods schedule the loading as usual. All ticket
 * changes happen on the server thread at the end of a server tick: block
 * entity load events only enqueue, which keeps ticket changes out of chunk
 * loading callbacks.
 *
 * Restoring: owners are saved per dimension in KeepLoadedState. On server
 * start every saved owner's chunks get their tickets back; when those chunks
 * load, the block entities re-register (a no-op if nothing changed).
 *
 * maxForcedChunks caps the number of ticketed chunks across all dimensions;
 * chunks past the cap are counted but not ticketed, with one warning each time
 * the cap is reached.
 *
 * With the option off, nothing is queued or registered at all and saved state
 * is cleared on start, so the feature costs nothing.
 */
public final class ChunkKeeper {
    private static final Logger LOGGER = LoggerFactory.getLogger("smartsorter");

    /** Same level as /forceload (entity ticking), radius 2 = level 31. */
    private static final int TICKET_RADIUS = 2;

    public static final ChunkTicketType TICKET_TYPE = new ChunkTicketType(ChunkTicketType.NO_EXPIRATION,
            ChunkTicketType.FOR_LOADING | ChunkTicketType.FOR_SIMULATION | ChunkTicketType.RESETS_IDLE_TIMEOUT);

    private static final ConcurrentLinkedQueue<Op> PENDING = new ConcurrentLinkedQueue<>();
    private static final Map<RegistryKey<World>, WorldKeeper> WORLDS = new HashMap<>();
    private static int ticketedTotal = 0;
    private static boolean capWarned = false;

    private ChunkKeeper() {
    }

    /** Called from SmartSorter.onInitialize(). */
    public static void init() {
        Registry.register(Registries.TICKET_TYPE, Identifier.of("smartsorter", "keep_loaded"), TICKET_TYPE);
        ServerLifecycleEvents.SERVER_STARTED.register(ChunkKeeper::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(ChunkKeeper::onServerStopped);
        if (SmartSorterConfig.keepChunksLoaded) {
            // Only hooked up when the option is on, so it costs nothing otherwise.
            ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register(ChunkKeeper::onBlockEntityLoad);
            ServerTickEvents.END_SERVER_TICK.register(ChunkKeeper::processPending);
        }
    }

    // ---- entry points (cheap; never touch tickets directly) ----

    /** Block entity loaded (placed or its chunk loaded). */
    public static void onBlockEntityLoad(BlockEntity be, ServerWorld world) {
        if (!SmartSorterConfig.keepChunksLoaded) {
            return;
        }
        if (be instanceof StorageControllerBlockEntity) {
            enqueue(world, be.getPos(), new long[] {ChunkPos.toLong(be.getPos())});
        } else if (be instanceof IntakeBlockEntity) {
            enqueue(world, be.getPos(), ownAndFacing(be.getPos(), be.getCachedState(), true));
        } else if (be instanceof OutputProbeBlockEntity) {
            enqueue(world, be.getPos(), ownAndFacing(be.getPos(), be.getCachedState(), false));
        }
    }

    /** A probe found its chest needs another chunk (target in a neighbor chunk, double chest's other half). */
    public static void requestExtraChunk(ServerWorld world, BlockPos owner, BlockPos chunkOf) {
        enqueue(world, owner, new long[] {ChunkPos.toLong(chunkOf)});
    }

    /** Block removed: release everything it held. Always safe to call. */
    public static void unregister(ServerWorld world, BlockPos owner) {
        if (!SmartSorterConfig.keepChunksLoaded) {
            return;
        }
        PENDING.add(new Op(world.getRegistryKey(), owner.asLong(), null));
    }

    /**
     * Registrations only ever ADD chunks to an owner (its set grows until the
     * block is removed), so a chunk reload re-registering "own + facing" never
     * drops a double-chest half the probe added later.
     */
    private static void enqueue(ServerWorld world, BlockPos owner, long[] chunks) {
        PENDING.add(new Op(world.getRegistryKey(), owner.asLong(), chunks));
    }

    private static long[] ownAndFacing(BlockPos pos, BlockState state, boolean intake) {
        long own = ChunkPos.toLong(pos);
        Direction facing = null;
        if (intake && state.contains(IntakeBlock.FACING)) {
            facing = state.get(IntakeBlock.FACING);
        } else if (!intake && state.contains(OutputProbeBlock.FACING)) {
            facing = state.get(OutputProbeBlock.FACING);
        }
        if (facing == null) {
            return new long[] {own};
        }
        long other = ChunkPos.toLong(pos.offset(facing));
        return other == own ? new long[] {own} : new long[] {own, other};
    }

    // ---- server lifecycle ----

    public static void onServerStarted(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            if (!SmartSorterConfig.keepChunksLoaded) {
                // Option off: forget anything saved while it was on (get(), not
                // getOrCreate(), so no empty file is written for every world).
                KeepLoadedState saved = world.getPersistentStateManager().get(KeepLoadedState.TYPE);
                if (saved != null && !saved.owners.isEmpty()) {
                    saved.owners.clear();
                    saved.markDirty();
                }
                continue;
            }
            KeepLoadedState state = world.getPersistentStateManager().getOrCreate(KeepLoadedState.TYPE);
            WorldKeeper keeper = keeper(world);
            for (Long2ObjectMap.Entry<long[]> entry : state.owners.long2ObjectEntrySet()) {
                keeper.owners.put(entry.getLongKey(), entry.getValue());
                for (long chunk : entry.getValue()) {
                    keeper.retain(world, chunk);
                }
            }
            if (!state.owners.isEmpty()) {
                LOGGER.info("[Smart Sorter] Restored {} kept-loaded chunk(s) for {} block(s) in {}",
                        keeper.refs.size(), state.owners.size(), world.getRegistryKey().getValue());
            }
        }
    }

    public static void onServerStopped(MinecraftServer server) {
        PENDING.clear();
        WORLDS.clear();
        ticketedTotal = 0;
        capWarned = false;
    }

    /** End of every server tick; returns immediately when nothing is queued. */
    public static void processPending(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        Op op;
        while ((op = PENDING.poll()) != null) {
            ServerWorld world = server.getWorld(op.world);
            if (world != null) {
                apply(world, op);
            }
        }
    }

    private static void apply(ServerWorld world, Op op) {
        WorldKeeper keeper = keeper(world);
        KeepLoadedState state = world.getPersistentStateManager().getOrCreate(KeepLoadedState.TYPE);
        long[] old = keeper.owners.get(op.owner);

        long[] updated;
        if (op.chunks == null) {
            updated = null;
        } else if (old != null) {
            updated = union(old, op.chunks);
        } else {
            updated = op.chunks;
        }
        if (sameChunks(old, updated)) {
            return;
        }

        // Retain new chunks before releasing old ones so a chunk shared by
        // both never drops to zero in between.
        if (updated != null) {
            for (long chunk : updated) {
                keeper.retain(world, chunk);
            }
            keeper.owners.put(op.owner, updated);
            state.owners.put(op.owner, updated);
        } else {
            keeper.owners.remove(op.owner);
            state.owners.remove(op.owner);
        }
        if (old != null) {
            for (long chunk : old) {
                keeper.release(world, chunk);
            }
        }
        state.markDirty();
    }

    private static long[] union(long[] a, long[] b) {
        LongOpenHashSet set = new LongOpenHashSet(a);
        for (long chunk : b) {
            set.add(chunk);
        }
        return set.toLongArray();
    }

    private static boolean sameChunks(long[] a, long[] b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.length != b.length) {
            return false;
        }
        LongOpenHashSet set = new LongOpenHashSet(a);
        for (long chunk : b) {
            if (!set.contains(chunk)) {
                return false;
            }
        }
        return true;
    }

    private static WorldKeeper keeper(ServerWorld world) {
        return WORLDS.computeIfAbsent(world.getRegistryKey(), key -> new WorldKeeper());
    }

    /** chunks == null means unregister. */
    private record Op(RegistryKey<World> world, long owner, long[] chunks) {
    }

    private static final class WorldKeeper {
        final Long2ObjectOpenHashMap<long[]> owners = new Long2ObjectOpenHashMap<>();
        final Long2IntOpenHashMap refs = new Long2IntOpenHashMap();
        final LongOpenHashSet ticketed = new LongOpenHashSet();

        void retain(ServerWorld world, long chunk) {
            int count = this.refs.addTo(chunk, 1);
            if (count != 0) {
                return; // already counted (and ticketed, unless it was over the cap)
            }
            if (ticketedTotal >= SmartSorterConfig.maxForcedChunks) {
                if (!capWarned) {
                    capWarned = true;
                    LOGGER.warn("[Smart Sorter] maxForcedChunks ({}) reached; chunk {} in {} and any further chunks will not be kept loaded",
                            SmartSorterConfig.maxForcedChunks, new ChunkPos(chunk), world.getRegistryKey().getValue());
                }
                return;
            }
            world.getChunkManager().addTicket(TICKET_TYPE, new ChunkPos(chunk), TICKET_RADIUS);
            this.ticketed.add(chunk);
            ticketedTotal++;
        }

        void release(ServerWorld world, long chunk) {
            int count = this.refs.addTo(chunk, -1);
            if (count > 1) {
                return;
            }
            this.refs.remove(chunk);
            if (this.ticketed.remove(chunk)) {
                world.getChunkManager().removeTicket(TICKET_TYPE, new ChunkPos(chunk), TICKET_RADIUS);
                ticketedTotal--;
                if (ticketedTotal < SmartSorterConfig.maxForcedChunks) {
                    capWarned = false;
                }
            }
        }
    }
}
