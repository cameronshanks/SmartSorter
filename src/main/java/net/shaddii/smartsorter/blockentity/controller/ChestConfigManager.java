package net.shaddii.smartsorter.blockentity.controller;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.network.ChestPriorityBatchPayload;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.util.ChestPriorityManager;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import java.util.*;

/**
 * Manages chest configurations and naming synchronization.
 */
public class ChestConfigManager {
    private final Map<BlockPos, ChestConfig> chestConfigs = new LinkedHashMap<>();
    private final ChestPriorityManager priorityManager = new ChestPriorityManager();
    private final ProbeRegistry probeRegistry;

    public ChestConfigManager(ProbeRegistry probeRegistry) {
        this.probeRegistry = probeRegistry;
    }

    /**
     * Called when a new chest is detected by a probe.
     */
    public void onChestDetected(Level world, BlockPos chestPos, OutputProbeBlockEntity probe) {
        if (chestConfigs.containsKey(chestPos)) return;

        ChestConfig config = null;

        // Try to get config from probe
        ChestConfig probeConfig = probe.getChestConfig();
        if (probeConfig != null) {
            config = probeConfig.copy();
        }

        // Create default if needed
        if (config == null) {
            String existingName = readNameFromChest(world, chestPos);
            config = new ChestConfig(chestPos);
            if (existingName != null && !existingName.isEmpty()) {
                config.customName = existingName;
            }
            config.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
        }

        // Let priority manager handle priority assignment
        Map<BlockPos, Integer> newPriorities = priorityManager.addChest(chestPos, config, chestConfigs);
        applyPriorityUpdates(world, newPriorities);

        probeRegistry.invalidateCache();
    }

    public void onAllChestsDetected(Level world, List<BlockPos> linkedProbes) {
        if (world == null) return;

        // Collect all chest configs without triggering priority assignment
        Map<BlockPos, ChestConfig> newConfigs = new HashMap<>();

        for (BlockPos probePos : linkedProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            BlockPos targetPos = probe.getTargetPos();
            if (targetPos == null || chestConfigs.containsKey(targetPos)) continue;

            ChestConfig config = null;

            // Try to get config from probe
            ChestConfig probeConfig = probe.getChestConfig();
            if (probeConfig != null) {
                config = probeConfig.copy();
            }

            // Create default if needed
            if (config == null) {
                String existingName = readNameFromChest(world, targetPos);
                config = new ChestConfig(targetPos);
                if (existingName != null && !existingName.isEmpty()) {
                    config.customName = existingName;
                }
                config.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
            }

            // Ensure SimplePriority is set (convert null to MEDIUM for consistency)
            if (config.simplePrioritySelection == null) {
                // Manual priority was set - derive SimplePriority from numeric value
                config.simplePrioritySelection = ChestConfig.SimplePriority.fromNumeric(
                        config.priority,
                        linkedProbes.size()
                );
            }

            // Handle overflow default
            if (config.filterMode == ChestConfig.FilterMode.OVERFLOW) {
                config.simplePrioritySelection = ChestConfig.SimplePriority.LOWEST;
            }

            newConfigs.put(targetPos, config);
        }

        if (newConfigs.isEmpty()) return;

        // Add all configs to main map
        chestConfigs.putAll(newConfigs);

        // FIXED: Validate and deduplicate priorities WITHOUT reordering
        Map<BlockPos, Integer> validatedPriorities = priorityManager.validatePriorities(chestConfigs);
        applyPriorityUpdates(world, validatedPriorities);

        probeRegistry.invalidateCache();
    }

    /**
     * Called when a chest is removed.
     */
    public void onChestRemoved(Level world, BlockPos chestPos, List<BlockPos> remainingProbes) {
        // Check if any other probe still targets this chest
        boolean stillLinked = false;
        for (BlockPos probePos : remainingProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (be instanceof OutputProbeBlockEntity probe) {
                BlockPos targetPos = probe.getTargetPos();
                if (targetPos != null && targetPos.equals(chestPos)) {
                    stillLinked = true;
                    break;
                }
            }
        }

        if (!stillLinked) {
            ChestConfig removed = chestConfigs.remove(chestPos);
            if (removed != null) {
                clearNameFromChest(world, chestPos);
            }
        }
    }

    /**
     * Updates a chest configuration.
     */
    public void updateChestConfig(Level world, BlockPos position, ChestConfig config,
                                  StorageControllerBlockEntity controller) {
        ChestConfig oldConfig = chestConfigs.get(position);

        // The incoming config is usually a brand-new object (ChestConfigPanel's
        // priority field builds one with "new ChestConfig(...)", and network
        // payloads don't carry the whitelist), so carry the whitelist over.
        if (oldConfig != null && oldConfig != config) {
            config.copyWhitelistFrom(oldConfig);
        }

        if (oldConfig == null) {
            // New chest - assign priority
            Map<BlockPos, Integer> newPriorities = priorityManager.addChest(position, config, chestConfigs);
            applyPriorityUpdates(world, newPriorities);

        } else if (config.simplePrioritySelection != null &&
                oldConfig.simplePrioritySelection != config.simplePrioritySelection) {
            // SimplePriority dropdown changed to a non-null value (e.g., HIGHEST, HIGH, etc.).
            // Store the rest of the update first (switching to Overflow changes the mode
            // and the priority together), then re-rank from the old position.
            config.priority = oldConfig.priority;
            chestConfigs.put(position, config);
            Map<BlockPos, Integer> newPriorities = priorityManager.updateChestPriority(
                    position, config.simplePrioritySelection, chestConfigs
            );
            applyPriorityUpdates(world, newPriorities);
            syncConfigToProbe(world, config);

        } else if (config.simplePrioritySelection == null &&
                config.priority != oldConfig.priority) {
            // Manual numeric priority change (SimplePriority is null, user typed a number)
            int targetPriority = config.priority;
            config.priority = oldConfig.priority;
            chestConfigs.put(position, config);
            Map<BlockPos, Integer> newPriorities = priorityManager.setManualPriority(
                    position, targetPriority, chestConfigs
            );
            applyPriorityUpdates(world, newPriorities);
            syncConfigToProbe(world, config);

        } else {
            // Non-priority update (category, filter mode, NBT matching, etc.)
            // Keep old priority unchanged
            config.priority = oldConfig.priority;
            config.simplePrioritySelection = oldConfig.simplePrioritySelection; // Also preserve SimplePriority
            config.updateHiddenPriority();
            chestConfigs.put(position, config);

            // Sync to probe
            syncConfigToProbe(world, config);
        }

        writeNameToChest(world, position, config.customName);
        probeRegistry.invalidateCache();
    }


    /**
     * Removes a chest configuration.
     */
    public void removeChestConfig(Level world, BlockPos chestPos,
                                  StorageControllerBlockEntity controller) {
        if (chestConfigs.containsKey(chestPos)) {
            Map<BlockPos, Integer> newPriorities = priorityManager.removeChest(chestPos, chestConfigs);
            applyPriorityUpdates(world, newPriorities);

            clearNameFromChest(world, chestPos);
            probeRegistry.invalidateCache();
        }
    }

    /**
     * Gets all chest configs with live data.
     */
    public Map<BlockPos, ChestConfig> getChestConfigs(Level world, List<BlockPos> linkedProbes) {
        Map<BlockPos, ChestConfig> configs = new LinkedHashMap<>(chestConfigs);

        for (ChestConfig config : configs.values()) {
            config.cachedFullness = calculateChestFullness(world, config.position, linkedProbes);
            config.previewItems = getChestPreviewItems(world, config.position, linkedProbes);
        }

        return configs;
    }

    /**
     * Gets a specific chest config.
     */
    public ChestConfig getChestConfig(BlockPos position) {
        return chestConfigs.get(position);
    }

    /**
     * Checks if a chest is linked.
     */
    public boolean isChestLinked(Level world, BlockPos chestPos, List<BlockPos> linkedProbes) {
        if (world == null) return false;

        for (BlockPos probePos : linkedProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (be instanceof OutputProbeBlockEntity probe) {
                if (chestPos.equals(probe.getTargetPos())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Clears all chest names (cleanup).
     */
    public void clearAllChestNames(Level world) {
        for (BlockPos chestPos : chestConfigs.keySet()) {
            clearNameFromChest(world, chestPos);
        }
    }

    /**
     * Writes chest configs to NBT.
     */
    public ListTag writeToNbt() {
        ListTag list = new ListTag();

        for (ChestConfig config : chestConfigs.values()) {
            CompoundTag nbt = new CompoundTag();
            nbt.putLong("Pos", config.position.asLong());
            nbt.putString("Name", config.customName != null ? config.customName : "");
            nbt.putString("Category", config.filterCategory.asString());
            nbt.putInt("Priority", config.priority);
            nbt.putString("Mode", config.filterMode.name());
            nbt.putBoolean("AutoFrame", config.autoItemFrame);

            if (config.simplePrioritySelection != null) {
                nbt.putString("SimplePriority", config.simplePrioritySelection.name());
            }

            list.add(nbt);
        }

        return list;
    }

    /**
     * Reads chest configs from NBT.
     */
    public void readFromNbt(ListTag list) {
        chestConfigs.clear();

        for (int i = 0; i < list.size(); i++) {
            // Use Optional API for 1.21.8+ NbtList
            list.getCompound(i).ifPresent(nbt -> {
                // NbtCompound methods return Optional in 1.21.8+
                nbt.getLong("Pos").ifPresent(posLong -> {
                    BlockPos pos = BlockPos.of(posLong);
                    String name = nbt.getString("Name").orElse("");
                    String categoryStr = nbt.getString("Category").orElse("smartsorter:all");
                    int priority = nbt.getInt("Priority").orElse(1);
                    String modeStr = nbt.getString("Mode").orElse("NONE");
                    boolean autoFrame = nbt.getBoolean("AutoFrame").orElse(false);

                    net.shaddii.smartsorter.util.Category category =
                            net.shaddii.smartsorter.util.CategoryManager.getInstance().getCategory(categoryStr);
                    ChestConfig.FilterMode mode = ChestConfig.FilterMode.valueOf(modeStr);

                    ChestConfig config = new ChestConfig(pos, name, category, priority, mode, autoFrame);

                    // Load SimplePriority
                    nbt.getString("SimplePriority").ifPresent(str -> {
                        try {
                            config.simplePrioritySelection = ChestConfig.SimplePriority.valueOf(str);
                        } catch (Exception e) {
                            config.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
                        }
                    });

                    if (config.simplePrioritySelection == null) {
                        config.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
                    }

                    chestConfigs.put(pos, config);
                });
            });
        }

        // Recalculate all priorities
        if (!chestConfigs.isEmpty()) {
            Map<BlockPos, Integer> recalculated = priorityManager.recalculateAll(chestConfigs);
            for (Map.Entry<BlockPos, Integer> entry : recalculated.entrySet()) {
                ChestConfig config = chestConfigs.get(entry.getKey());
                if (config != null) {
                    config.priority = entry.getValue();
                    config.updateHiddenPriority();
                }
            }
        }
    }

    // ========================================
    // HELPERS
    // ========================================

    public Map<BlockPos, ChestConfig> getAllConfigs() {
        return new LinkedHashMap<>(chestConfigs);
    }

    private void applyPriorityUpdates(Level world, Map<BlockPos, Integer> newPriorities) {
        for (Map.Entry<BlockPos, Integer> entry : newPriorities.entrySet()) {
            ChestConfig config = chestConfigs.get(entry.getKey());
            if (config != null) {
                config.priority = entry.getValue();
                config.updateHiddenPriority();
                syncConfigToProbe(world, config);
            }
        }

        probeRegistry.invalidateCache();
        sendPriorityUpdatesToClients(world);
    }

    private void syncConfigToProbe(Level world, ChestConfig config) {
        if (world == null) return;

        // Find probe(s) targeting this chest
        for (BlockPos probePos : probeRegistry.getLinkedProbes()) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (be instanceof OutputProbeBlockEntity probe) {
                BlockPos targetPos = probe.getTargetPos();
                if (targetPos != null && targetPos.equals(config.position)) {
                    probe.updateLocalConfig(config.copy());
                }
            }
        }
    }

    private void sendPriorityUpdatesToClients(Level world) {
        if (!(world instanceof net.minecraft.server.level.ServerLevel serverWorld)) return;

        // Recalculate fullness for all configs before sending
        List<BlockPos> linkedProbes = probeRegistry.getLinkedProbes();
        for (ChestConfig config : chestConfigs.values()) {
            config.cachedFullness = calculateChestFullness(world, config.position, linkedProbes);
        }

        ChestPriorityBatchPayload payload = ChestPriorityBatchPayload.fromConfigs(chestConfigs);

        for (ServerPlayer player : serverWorld.players()) {
            if (player.containerMenu instanceof StorageControllerScreenHandler handler) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    public int calculateChestFullness(Level world, BlockPos chestPos, List<BlockPos> linkedProbes) {
        if (world == null) return -1;

        // OPTIMIZATION: Use index to find probe directly (O(1) instead of O(n))
        BlockPos probePos = probeRegistry.getProbeForChest(world, chestPos);
        if (probePos == null) return -1;

        BlockEntity be = world.getBlockEntity(probePos);
        if (!(be instanceof OutputProbeBlockEntity probe)) return -1;

        Container inv = probe.getTargetInventory();
        if (inv == null) return -1;

        int totalSlots = inv.getContainerSize();
        int occupiedSlots = 0;

        for (int i = 0; i < totalSlots; i++) {
            if (!inv.getItem(i).isEmpty()) {
                occupiedSlots++;
            }
        }

        return totalSlots > 0 ? (occupiedSlots * 100 / totalSlots) : 0;
    }

    private List<ItemStack> getChestPreviewItems(Level world, BlockPos chestPos, List<BlockPos> linkedProbes) {
        List<ItemStack> items = new ArrayList<>();
        if (world == null) return items;

        // OPTIMIZATION: Use index to find probe directly (O(1) instead of O(n))
        BlockPos probePos = probeRegistry.getProbeForChest(world, chestPos);
        if (probePos == null) return items;

        BlockEntity be = world.getBlockEntity(probePos);
        if (!(be instanceof OutputProbeBlockEntity probe)) return items;

        Container inv = probe.getTargetInventory();
        if (inv == null) return items;

        for (int i = 0; i < inv.getContainerSize() && items.size() < 8; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                items.add(stack.copy());
            }
        }

        return items;
    }

    private void writeNameToChest(Level world, BlockPos chestPos, String customName) {
        if (world == null) return;

        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (blockEntity == null) return;

        CompoundTag nbt = blockEntity.saveWithoutMetadata(world.registryAccess());

        if (customName == null || customName.isEmpty()) {
            nbt.remove("CustomName");
        } else {
            Component textComponent = Component.literal(customName);
            DataResult<Tag> result = ComponentSerialization.CODEC.encodeStart(
                    world.registryAccess().createSerializationContext(NbtOps.INSTANCE),
                    textComponent
            );
            result.result().ifPresent(nbtElement -> {
                nbt.put("CustomName", nbtElement);
            });
        }

        try (ProblemReporter.ScopedCollector logging = new ProblemReporter.ScopedCollector(blockEntity.problemPath(), LogUtils.getLogger())) {
            blockEntity.loadWithComponents(TagValueInput.create(logging, world.registryAccess(), nbt));
        }
        blockEntity.setChanged();

        BlockState state = world.getBlockState(chestPos);
        world.sendBlockUpdated(chestPos, state, state, 3);
    }

    private String readNameFromChest(Level world, BlockPos chestPos) {
        if (world == null) return "";

        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (blockEntity == null) return "";

        CompoundTag nbt = blockEntity.saveWithoutMetadata(world.registryAccess());

        if (nbt.contains("CustomName")) {
            try {
                DataResult<Component> result = ComponentSerialization.CODEC.parse(
                        world.registryAccess().createSerializationContext(NbtOps.INSTANCE),
                        nbt.get("CustomName")
                );
                return result.result()
                        .map(Component::getString)
                        .orElse("");
            } catch (Exception e) {
                return "";
            }
        }

        return "";
    }

    private void clearNameFromChest(Level world, BlockPos chestPos) {
        if (world == null) return;

        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (blockEntity == null) return;

        CompoundTag nbt = blockEntity.saveWithoutMetadata(world.registryAccess());

        if (nbt.contains("CustomName")) {
            nbt.remove("CustomName");

            try (ProblemReporter.ScopedCollector logging = new ProblemReporter.ScopedCollector(blockEntity.problemPath(), LogUtils.getLogger())) {
                blockEntity.loadWithComponents(TagValueInput.create(logging, world.registryAccess(), nbt));
            }
            blockEntity.setChanged();

            BlockState state = world.getBlockState(chestPos);
            world.sendBlockUpdated(chestPos, state, state, 3);
        }
    }
}