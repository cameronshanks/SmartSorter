package net.shaddii.smartsorter.blockentity.controller;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;

import java.util.*;

/**
 * Manages network-wide item inventory tracking and caching.
 * Optimized with delta tracking and lazy copy-on-write.
 */
public class NetworkInventoryManager {
    private static final int INITIAL_CAPACITY = 256;
    private static final float LOAD_FACTOR = 0.75f;
    private int samplingOffset = 0;
    private static final int SAMPLE_SIZE = 300;
    private Map<ItemVariant, Long> samplingAccumulator = new HashMap<>();
    private boolean samplingInProgress = false;

    private final Map<ItemVariant, Long> networkItems;
    private final Map<ItemVariant, Long> deltaItems;
    private final Map<ItemVariant, List<BlockPos>> itemLocationIndex;

    private Map<ItemVariant, Long> networkItemsSnapshot;
    private boolean snapshotDirty;

    public NetworkInventoryManager() {
        this.networkItems = new LinkedHashMap<>(INITIAL_CAPACITY, LOAD_FACTOR);
        this.deltaItems = new HashMap<>(64, LOAD_FACTOR);
        this.itemLocationIndex = new HashMap<>(INITIAL_CAPACITY, LOAD_FACTOR);
        this.snapshotDirty = true;
    }

    /**
     * Updates network cache by scanning all probes.
     * Optimized: Reuses maps, batch operations, minimal allocations.
     */
    public void updateCache(Level world, List<BlockPos> probes) {
        if (world == null) return;

        // OPTIMIZATION: For very large networks (1000+ chests), use sampling
        if (probes.size() > 1000) {
            updateCacheSampled(world, probes);
            return;
        }

        // Normal update for smaller networks
        updateCacheFull(world, probes);
    }

    public void updateCacheForceFull(Level world, List<BlockPos> probes) {
        if (world == null) return;
        updateCacheFull(world, probes);
    }

    private void updateCacheFull(Level world, List<BlockPos> probes) {
        Map<ItemVariant, Long> newItems = new HashMap<>(networkItems.size());
        itemLocationIndex.clear();

        for (BlockPos probePos : probes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            var storage = probe.getTargetStorage();
            if (storage == null) continue;

            for (var view : storage) {
                if (view.getAmount() == 0) continue;

                ItemVariant variant = view.getResource();
                if (variant.isBlank()) continue;

                newItems.merge(variant, view.getAmount(), Long::sum);
                itemLocationIndex.computeIfAbsent(variant, k -> new ArrayList<>(4))
                        .add(probePos);
            }
        }

        trackDeltas(newItems);
        networkItems.clear();
        networkItems.putAll(newItems);
        snapshotDirty = true;
        samplingOffset = 0;
        samplingAccumulator.clear();
        samplingInProgress = false;
        deltaItems.clear();
    }

    private void updateCacheSampled(Level world, List<BlockPos> probes) {
        int totalProbes = probes.size();
        int endIndex = Math.min(samplingOffset + SAMPLE_SIZE, totalProbes);

        // Start new cycle
        if (samplingOffset == 0) {
            samplingAccumulator.clear();
            itemLocationIndex.clear();
            samplingInProgress = true;
        }

        // Scan this batch
        for (int i = samplingOffset; i < endIndex; i++) {
            BlockPos probePos = probes.get(i);
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            var storage = probe.getTargetStorage();
            if (storage == null) continue;

            for (var view : storage) {
                if (view.getAmount() == 0) continue;

                ItemVariant variant = view.getResource();
                if (variant.isBlank()) continue;

                samplingAccumulator.merge(variant, view.getAmount(), Long::sum);
                itemLocationIndex.computeIfAbsent(variant, k -> new ArrayList<>(4))
                        .add(probePos);
            }
        }

        samplingOffset = endIndex;

        // Completed full rotation
        if (samplingOffset >= totalProbes) {
            trackDeltas(samplingAccumulator);
            networkItems.clear();
            networkItems.putAll(samplingAccumulator);
            samplingOffset = 0;
            samplingInProgress = false;
        }

        snapshotDirty = true;
    }

    /**
     * Tracks changes between old and new inventory states.
     */
    private void trackDeltas(Map<ItemVariant, Long> newItems) {
        // Check for removed/changed items
        for (Map.Entry<ItemVariant, Long> entry : networkItems.entrySet()) {
            long newAmount = newItems.getOrDefault(entry.getKey(), 0L);
            if (newAmount != entry.getValue()) {
                deltaItems.put(entry.getKey(), newAmount);
            }
        }

        // Check for new items
        for (Map.Entry<ItemVariant, Long> entry : newItems.entrySet()) {
            if (!networkItems.containsKey(entry.getKey())) {
                deltaItems.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Gets immutable snapshot of network items (copy-on-write).
     */
    public Map<ItemVariant, Long> getNetworkItems() {
        if (snapshotDirty || networkItemsSnapshot == null) {
            networkItemsSnapshot = new HashMap<>(networkItems);
            snapshotDirty = false;
        }
        return networkItemsSnapshot;
    }

    /**
     * Gets and clears deltas since last sync.
     */
    public Map<ItemVariant, Long> consumeDeltas() {
        if (deltaItems.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<ItemVariant, Long> deltas = new HashMap<>(deltaItems);
        deltaItems.clear();
        return deltas;
    }

    /**
     * Gets probes that contain a specific item.
     */
    public List<BlockPos> getProbesWithItem(ItemVariant variant) {
        return itemLocationIndex.getOrDefault(variant, Collections.emptyList());
    }

    /**
     * Gets current amount of an item in the network.
     */
    public long getItemAmount(ItemVariant variant) {
        return networkItems.getOrDefault(variant, 0L);
    }

    public boolean hasDeltas() {
        return !deltaItems.isEmpty();
    }

    public void markDirty() {
        snapshotDirty = true;
    }

    /**
     * Applies delta updates to the network items (for client-side sync).
     */
    public void applyDeltas(Map<ItemVariant, Long> deltas) {
        for (Map.Entry<ItemVariant, Long> entry : deltas.entrySet()) {
            if (entry.getValue() <= 0) {
                networkItems.remove(entry.getKey());
            } else {
                networkItems.put(entry.getKey(), entry.getValue());
            }
        }
        snapshotDirty = true; // Invalidate snapshot
    }

    public void adjustItemCount(ItemVariant variant, long delta) {
        if (variant == null || variant.isBlank()) return;

        long currentAmount = networkItems.getOrDefault(variant, 0L);
        long newAmount = currentAmount + delta;

        if (newAmount <= 0) {
            networkItems.remove(variant);
            deltaItems.put(variant, 0L); // Signal removal
        } else {
            networkItems.put(variant, newAmount);
            deltaItems.put(variant, newAmount); // Signal change
        }

        snapshotDirty = true;
    }
}