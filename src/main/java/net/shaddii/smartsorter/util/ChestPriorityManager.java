package net.shaddii.smartsorter.util;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;

public class ChestPriorityManager {
    private static final int CUSTOM_PRIORITY = 0;
    private final Map<BlockPos, Integer> priorityCache = new ConcurrentHashMap<>();
    private boolean isDirty = true;

    /**
     * Add a new chest to the system
     */
    public Map<BlockPos, Integer> addChest(BlockPos pos, ChestConfig config, Map<BlockPos, ChestConfig> allConfigs) {
        // Ensure SimplePriority is set
        if (config.simplePrioritySelection == null) {
            config.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
        }

        // Handle special cases
        if (config.filterMode == ChestConfig.FilterMode.OVERFLOW) {
            config.simplePrioritySelection = ChestConfig.SimplePriority.LOWEST;
        }

        allConfigs.put(pos, config);
        return reorderAllChests(allConfigs);
    }

    /**
     * Remove a chest from the system
     */
    public Map<BlockPos, Integer> removeChest(BlockPos pos, Map<BlockPos, ChestConfig> allConfigs) {
        allConfigs.remove(pos);
        return reorderAllChests(allConfigs);
    }

    /**
     * Update a chest's SimplePriority
     */
    public Map<BlockPos, Integer> updateChestPriority(BlockPos pos, ChestConfig.SimplePriority newPriority,
                                                      Map<BlockPos, ChestConfig> allConfigs) {
        ChestConfig config = allConfigs.get(pos);
        if (config == null) return new HashMap<>(priorityCache);

        config.simplePrioritySelection = newPriority;
        return reorderAllChests(allConfigs);
    }

    public Map<BlockPos, Integer> setManualPriority(BlockPos pos, int targetPriority,
                                                    Map<BlockPos, ChestConfig> allConfigs) {
        ChestConfig config = allConfigs.get(pos);
        if (config == null) return new HashMap<>(priorityCache);

        int oldPriority = config.priority;

        // Count total regular chests for SimplePriority derivation
        int totalRegularChests = getRegularChestCount(allConfigs);

        // Update the config
        config.priority = targetPriority;

        // FIXED: Derive SimplePriority from numeric priority instead of setting to null
        // This ensures probe can save/load the correct priority tier
        config.simplePrioritySelection = ChestConfig.SimplePriority.fromNumeric(
                targetPriority,
                totalRegularChests
        );

        allConfigs.put(pos, config);

        // Shift other chests to make room
        for (Map.Entry<BlockPos, ChestConfig> entry : allConfigs.entrySet()) {
            if (entry.getValue().filterMode == ChestConfig.FilterMode.CUSTOM) continue;
            if (entry.getKey().equals(pos)) continue;

            ChestConfig other = entry.getValue();

            if (targetPriority < oldPriority) {
                // Moving up in priority (e.g., 3 → 1)
                if (other.priority >= targetPriority && other.priority < oldPriority) {
                    other.priority++;

                    // FIXED: Also update SimplePriority for shifted chests
                    other.simplePrioritySelection = ChestConfig.SimplePriority.fromNumeric(
                            other.priority,
                            totalRegularChests
                    );

                    other.updateHiddenPriority();
                }
            } else if (targetPriority > oldPriority) {
                // Moving down in priority (e.g., 1 → 3)
                if (other.priority > oldPriority && other.priority <= targetPriority) {
                    other.priority--;

                    // FIXED: Also update SimplePriority for shifted chests
                    other.simplePrioritySelection = ChestConfig.SimplePriority.fromNumeric(
                            other.priority,
                            totalRegularChests
                    );

                    other.updateHiddenPriority();
                }
            }
        }

        config.updateHiddenPriority();

        // Rebuild cache
        Map<BlockPos, Integer> result = new HashMap<>();
        for (Map.Entry<BlockPos, ChestConfig> entry : allConfigs.entrySet()) {
            result.put(entry.getKey(), entry.getValue().priority);
            priorityCache.put(entry.getKey(), entry.getValue().priority);
        }

        isDirty = false;
        return result;
    }

    public Map<BlockPos, Integer> validatePriorities(Map<BlockPos, ChestConfig> allConfigs) {
        Map<BlockPos, Integer> result = new HashMap<>();

        // Separate CUSTOM chests (always priority 0)
        for (Map.Entry<BlockPos, ChestConfig> entry : allConfigs.entrySet()) {
            if (entry.getValue().filterMode == ChestConfig.FilterMode.CUSTOM) {
                entry.getValue().priority = CUSTOM_PRIORITY;
                entry.getValue().updateHiddenPriority();
                result.put(entry.getKey(), CUSTOM_PRIORITY);
                priorityCache.put(entry.getKey(), CUSTOM_PRIORITY);
            }
        }

        // Collect regular chests with their saved priorities
        List<ChestEntry> regularChests = new ArrayList<>();
        for (Map.Entry<BlockPos, ChestConfig> entry : allConfigs.entrySet()) {
            if (entry.getValue().filterMode != ChestConfig.FilterMode.CUSTOM) {
                regularChests.add(new ChestEntry(entry.getKey(), entry.getValue()));
            }
        }

        if (regularChests.isEmpty()) {
            isDirty = false;
            return result;
        }

        // Sort by saved priority (preserves probe's saved order)
        regularChests.sort((a, b) -> Integer.compare(a.config.priority, b.config.priority));

        // Detect and fix duplicates/gaps
        Set<Integer> usedPriorities = new HashSet<>();
        int nextAvailable = 1;

        for (ChestEntry chest : regularChests) {
            int savedPriority = chest.config.priority;

            // If priority is already used or invalid, assign next available
            if (savedPriority < 1 || usedPriorities.contains(savedPriority)) {
                // Find next available priority
                while (usedPriorities.contains(nextAvailable)) {
                    nextAvailable++;
                }
                chest.config.priority = nextAvailable;
                usedPriorities.add(nextAvailable);
                nextAvailable++;
            } else {
                // Priority is valid and unique, keep it
                chest.config.priority = savedPriority;
                usedPriorities.add(savedPriority);
            }

            chest.config.updateHiddenPriority();
            allConfigs.put(chest.pos, chest.config);
            result.put(chest.pos, chest.config.priority);
            priorityCache.put(chest.pos, chest.config.priority);
        }

        isDirty = false;
        return result;
    }

    /**
     * Recalculate all priorities (e.g., after loading from disk)
     */
    public Map<BlockPos, Integer> recalculateAll(Map<BlockPos, ChestConfig> allConfigs) {
        priorityCache.clear();
        return reorderAllChests(allConfigs);
    }

    public Map<BlockPos, Integer> recalculatePreservingManual(Map<BlockPos, ChestConfig> allConfigs,
                                                              List<BlockPos> manualPriorityChests) {
        priorityCache.clear();

        // Separate manual and SimplePriority-based chests
        Map<BlockPos, ChestConfig> manualChests = new HashMap<>();
        Map<BlockPos, ChestConfig> simpleChests = new HashMap<>();

        for (Map.Entry<BlockPos, ChestConfig> entry : allConfigs.entrySet()) {
            if (entry.getValue().filterMode == ChestConfig.FilterMode.CUSTOM) {
                // CUSTOM chests always priority 0
                entry.getValue().priority = CUSTOM_PRIORITY;
                entry.getValue().updateHiddenPriority();
                priorityCache.put(entry.getKey(), CUSTOM_PRIORITY);
                continue;
            }

            if (manualPriorityChests.contains(entry.getKey())) {
                manualChests.put(entry.getKey(), entry.getValue());
            } else {
                simpleChests.put(entry.getKey(), entry.getValue());
            }
        }

        // Sort SimplePriority chests by group, then by their current priority
        List<ChestEntry> simpleList = new ArrayList<>();
        for (Map.Entry<BlockPos, ChestConfig> entry : simpleChests.entrySet()) {
            simpleList.add(new ChestEntry(entry.getKey(), entry.getValue()));
        }

        simpleList.sort((a, b) -> {
            int aOrder = getSimplePriorityOrder(a.config.simplePrioritySelection);
            int bOrder = getSimplePriorityOrder(b.config.simplePrioritySelection);

            if (aOrder != bOrder) {
                return Integer.compare(aOrder, bOrder);
            }

            return Integer.compare(a.config.priority, b.config.priority);
        });

        // Collect all manual priorities to avoid conflicts
        Set<Integer> usedPriorities = new HashSet<>();
        for (ChestConfig config : manualChests.values()) {
            usedPriorities.add(config.priority);
            priorityCache.put(config.position, config.priority);
        }

        // Assign sequential priorities to SimplePriority chests, skipping manual priorities
        int priority = 1;
        for (ChestEntry entry : simpleList) {
            // Skip priorities used by manual chests
            while (usedPriorities.contains(priority)) {
                priority++;
            }

            entry.config.priority = priority;
            entry.config.updateHiddenPriority();
            allConfigs.put(entry.pos, entry.config);
            priorityCache.put(entry.pos, priority);
            priority++;
        }

        isDirty = false;
        return new HashMap<>(priorityCache);
    }

    /**
     * THE SINGLE SOURCE OF TRUTH - All priority assignment happens here
     */
    private Map<BlockPos, Integer> reorderAllChests(Map<BlockPos, ChestConfig> allConfigs) {
        Map<BlockPos, Integer> result = new HashMap<>();

        // Step 1: Handle CUSTOM chests (always priority 0)
        for (Map.Entry<BlockPos, ChestConfig> entry : allConfigs.entrySet()) {
            if (entry.getValue().filterMode == ChestConfig.FilterMode.CUSTOM) {
                ChestConfig config = entry.getValue();
                config.priority = CUSTOM_PRIORITY;
                config.updateHiddenPriority();
                priorityCache.put(entry.getKey(), CUSTOM_PRIORITY);
                result.put(entry.getKey(), CUSTOM_PRIORITY);
            }
        }

        // Step 2: Collect regular chests
        List<ChestEntry> regularChests = new ArrayList<>();
        for (Map.Entry<BlockPos, ChestConfig> entry : allConfigs.entrySet()) {
            if (entry.getValue().filterMode != ChestConfig.FilterMode.CUSTOM) {
                regularChests.add(new ChestEntry(entry.getKey(), entry.getValue()));
            }
        }

        // Step 3: Sort by SimplePriority groups, then by current priority within groups
        regularChests.sort((a, b) -> {
            int aOrder = getSimplePriorityOrder(a.config.simplePrioritySelection);
            int bOrder = getSimplePriorityOrder(b.config.simplePrioritySelection);

            if (aOrder != bOrder) {
                return Integer.compare(aOrder, bOrder);
            }

            // Within same SimplePriority, maintain relative order
            return Integer.compare(a.config.priority, b.config.priority);
        });

        // Step 4: Assign sequential priorities
        int priority = 1;
        for (ChestEntry chest : regularChests) {
            chest.config.priority = priority;
            chest.config.updateHiddenPriority();

            // Update the config in the map
            allConfigs.put(chest.pos, chest.config);

            // Update caches
            priorityCache.put(chest.pos, priority);
            result.put(chest.pos, priority);

            priority++;
        }

        isDirty = false;
        return result;
    }

    private int getSimplePriorityOrder(ChestConfig.SimplePriority priority) {
        if (priority == null) return 3; // Default to MEDIUM

        return switch (priority) {
            case HIGHEST -> 1;
            case HIGH -> 2;
            case MEDIUM -> 3;
            case LOW -> 4;
            case LOWEST -> 5;
        };
    }

    // Utility methods
    public boolean isDirty() { return isDirty; }
    public void markDirty() { isDirty = true; }
    public int getCachedPriority(BlockPos pos) { return priorityCache.getOrDefault(pos, -1); }
    public Map<BlockPos, Integer> getPriorityCache() { return new HashMap<>(priorityCache); }

    public int getRegularChestCount(Map<BlockPos, ChestConfig> allConfigs) {
        return (int) allConfigs.values().stream()
                .filter(config -> config.filterMode != ChestConfig.FilterMode.CUSTOM)
                .count();
    }

    private static class ChestEntry {
        final BlockPos pos;
        final ChestConfig config;

        ChestEntry(BlockPos pos, ChestConfig config) {
            this.pos = pos;
            this.config = config;
        }
    }
}