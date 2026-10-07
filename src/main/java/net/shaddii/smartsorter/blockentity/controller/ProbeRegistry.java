package net.shaddii.smartsorter.blockentity.controller;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.util.RoutingIndexEpoch;

import java.util.*;

/**
 * OPTIMIZED FOR 1000+ CHESTS
 * - Caches BlockEntity references
 * - Caches hasItems status
 * - Lazy sorting
 * - Minimal world lookups
 */
public class ProbeRegistry {
    private final List<BlockPos> linkedProbes = new ArrayList<>();

    private final Map<BlockPos, BlockPos> chestToProbe = new HashMap<>();
    private boolean indexDirty = true;

    // Heavy caching for large networks
    private final Map<BlockPos, OutputProbeBlockEntity> probeCache = new HashMap<>();
    private final Map<BlockPos, Boolean> hasItemsCache = new HashMap<>();

    private List<BlockPos> sortedProbesCache;
    private boolean sortCacheDirty = true;
    private long lastCacheUpdate = 0;
    private static final long CACHE_LIFETIME = 100; // 5 seconds
    private long seenEpoch = Long.MIN_VALUE;

    public boolean addProbe(BlockPos probePos) {
        if (linkedProbes.contains(probePos)) {
            return false;
        }

        linkedProbes.add(probePos);
        invalidateCache();
        return true;
    }

    public boolean removeProbe(BlockPos probePos) {
        boolean removed = linkedProbes.remove(probePos);
        if (removed) {
            probeCache.remove(probePos);
            hasItemsCache.remove(probePos);
            invalidateCache();
        }
        return removed;
    }

    /**
     * OPTIMIZED: Aggressively cached, minimal world lookups
     */
    public List<BlockPos> getSortedProbes(Level world) {
        long currentTime = world != null ? world.getGameTime() : 0;

        // A probe loaded with its chunk, or a probe config changed outside
        // the controller: re-sort now instead of at the next 100-tick refresh.
        long epoch = RoutingIndexEpoch.get();
        if (epoch != seenEpoch) {
            seenEpoch = epoch;
            sortCacheDirty = true;
        }

        // Return cached if still valid
        if (!sortCacheDirty && sortedProbesCache != null &&
                (currentTime - lastCacheUpdate) < CACHE_LIFETIME) {
            return sortedProbesCache;
        }

        // Rebuild cache
        rebuildSortedCache(world, currentTime);
        return sortedProbesCache;
    }

    public BlockPos getProbeForChest(Level world, BlockPos chestPos) {
        if (world == null || chestPos == null) return null;

        // Rebuild index if dirty
        if (indexDirty) {
            rebuildChestIndex(world);
        }

        return chestToProbe.get(chestPos);
    }

    private void rebuildChestIndex(Level world) {
        chestToProbe.clear();

        for (BlockPos probePos : linkedProbes) {
            OutputProbeBlockEntity probe = getCachedProbe(world, probePos);
            if (probe == null) continue;

            BlockPos targetPos = probe.getTargetPos();
            if (targetPos != null) {
                chestToProbe.put(targetPos, probePos);
            }
        }

        indexDirty = false;
    }

    private void rebuildSortedCache(Level world, long currentTime) {
        List<ProbeEntry> entries = new ArrayList<>(linkedProbes.size());

        for (BlockPos probePos : linkedProbes) {
            OutputProbeBlockEntity probe = getCachedProbe(world, probePos);
            if (probe == null) continue;

            ChestConfig config = probe.getChestConfig();

            entries.add(new ProbeEntry(
                    probePos,
                    config,
                    getCachedHasItems(world, probePos, probe, currentTime)
            ));
        }

        // Multi-tier sorting
        entries.sort((a, b) -> {
            // 1. Priority (higher first)
            int aPri = a.config != null ? a.config.hiddenPriority : 0;
            int bPri = b.config != null ? b.config.hiddenPriority : 0;
            if (aPri != bPri) return Integer.compare(bPri, aPri);

            // 2. Has items (occupied chests first)
            return Boolean.compare(b.hasItems, a.hasItems);
        });

        sortedProbesCache = new ArrayList<>(entries.size());
        for (ProbeEntry entry : entries) {
            sortedProbesCache.add(entry.pos);
        }

        sortCacheDirty = false;
        lastCacheUpdate = currentTime;
    }

    /**
     * CRITICAL OPTIMIZATION: Cache BlockEntity lookups
     */
    private OutputProbeBlockEntity getCachedProbe(Level world, BlockPos pos) {
        OutputProbeBlockEntity cached = probeCache.get(pos);

        if (cached != null) {
            // Validate cache - ensure block entity still exists
            if (!cached.isRemoved()) {
                return cached;
            }
            // Cache invalid, remove it
            probeCache.remove(pos);
        }

        // Never look up a probe in an unloaded chunk (forces a synchronous
        // load); it's just left out of this pass.
        if (world == null || !world.hasChunkAt(pos)) {
            return null;
        }

        // Lookup and cache
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof OutputProbeBlockEntity probe) {
            probeCache.put(pos, probe);
            return probe;
        }

        return null;
    }

    /**
     * CRITICAL OPTIMIZATION: Cache hasItems status (expensive to check)
     */
    private boolean getCachedHasItems(Level world, BlockPos probePos,
                                      OutputProbeBlockEntity probe, long currentTime) {
        Boolean cached = hasItemsCache.get(probePos);

        // Use cached value if recent enough (within 1 second)
        if (cached != null && (currentTime - lastCacheUpdate) < 20) {
            return cached;
        }

        // Expensive check - only do when necessary
        boolean hasItems = checkHasItemsFast(probe);
        hasItemsCache.put(probePos, hasItems);
        return hasItems;
    }

    /** Answered from the probe's chest-contents snapshot, no slot scan. */
    private boolean checkHasItemsFast(OutputProbeBlockEntity probe) {
        return probe.targetHasItems();
    }

    public List<BlockPos> getLinkedProbes() {
        return new ArrayList<>(linkedProbes);
    }

    public int getProbeCount() {
        return linkedProbes.size();
    }

    public void invalidateCache() {
        sortCacheDirty = true;
        hasItemsCache.clear();
        indexDirty = true; // Also invalidate chest→probe index
    }

    /**
     * Validates all probes, removing invalid ones.
     */
    public void validate(Level world) {
        linkedProbes.removeIf(probePos -> {
            // Probes in unloaded chunks stay linked instead of being
            // force-loaded to check (or dropped as missing).
            if (world == null || !world.hasChunkAt(probePos)) {
                return false;
            }
            OutputProbeBlockEntity probe = getCachedProbe(world, probePos);
            if (probe == null || probe.isRemoved()) {
                probeCache.remove(probePos);
                hasItemsCache.remove(probePos);
                return true;
            }
            return false;
        });
    }

    private record ProbeEntry(
            BlockPos pos,
            ChestConfig config,
            boolean hasItems
    ) {}
}