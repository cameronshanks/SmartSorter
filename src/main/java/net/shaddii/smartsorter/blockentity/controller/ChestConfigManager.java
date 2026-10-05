package net.shaddii.smartsorter.blockentity.controller;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.network.ChestPriorityBatchPayload;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.util.ChestPriorityManager;

//? if >=1.21.8 {
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import net.minecraft.nbt.NbtOps;
import net.minecraft.storage.NbtReadView;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;
import net.minecraft.util.ErrorReporter;
//?} else {
/*import net.minecraft.text.Text;
 *///?}

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
    public void onChestDetected(World world, BlockPos chestPos, OutputProbeBlockEntity probe) {
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

    public void onAllChestsDetected(World world, List<BlockPos> linkedProbes) {
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
    public void onChestRemoved(World world, BlockPos chestPos, List<BlockPos> remainingProbes) {
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
    public void updateChestConfig(World world, BlockPos position, ChestConfig config,
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
            // SimplePriority dropdown changed to a non-null value (e.g., HIGHEST, HIGH, etc.)
            Map<BlockPos, Integer> newPriorities = priorityManager.updateChestPriority(
                    position, config.simplePrioritySelection, chestConfigs
            );
            applyPriorityUpdates(world, newPriorities);

        } else if (config.simplePrioritySelection == null &&
                config.priority != oldConfig.priority) {
            // Manual numeric priority change (SimplePriority is null, user typed a number)
            Map<BlockPos, Integer> newPriorities = priorityManager.setManualPriority(
                    position, config.priority, chestConfigs
            );
            applyPriorityUpdates(world, newPriorities);

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
    public void removeChestConfig(World world, BlockPos chestPos,
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
    public Map<BlockPos, ChestConfig> getChestConfigs(World world, List<BlockPos> linkedProbes) {
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
    public boolean isChestLinked(World world, BlockPos chestPos, List<BlockPos> linkedProbes) {
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
    public void clearAllChestNames(World world) {
        for (BlockPos chestPos : chestConfigs.keySet()) {
            clearNameFromChest(world, chestPos);
        }
    }

    /**
     * Writes chest configs to NBT.
     */
    public NbtList writeToNbt() {
        NbtList list = new NbtList();

        for (ChestConfig config : chestConfigs.values()) {
            NbtCompound nbt = new NbtCompound();
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
    public void readFromNbt(NbtList list) {
        chestConfigs.clear();

        for (int i = 0; i < list.size(); i++) {
            //? if >=1.21.8 {
            // Use Optional API for 1.21.8+ NbtList
            list.getCompound(i).ifPresent(nbt -> {
                // NbtCompound methods return Optional in 1.21.8+
                nbt.getLong("Pos").ifPresent(posLong -> {
                    BlockPos pos = BlockPos.fromLong(posLong);
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
            //?} else {
            /*// Direct access for older versions
            NbtCompound nbt = list.getCompound(i);
            if (!nbt.contains("Pos")) continue;

            BlockPos pos = BlockPos.fromLong(nbt.getLong("Pos"));
            String name = nbt.getString("Name");
            String categoryStr = nbt.getString("Category");
            int priority = nbt.getInt("Priority");
            String modeStr = nbt.getString("Mode");
            boolean autoFrame = nbt.getBoolean("AutoFrame");

            net.shaddii.smartsorter.util.Category category =
                net.shaddii.smartsorter.util.CategoryManager.getInstance().getCategory(categoryStr);
            ChestConfig.FilterMode mode = ChestConfig.FilterMode.valueOf(modeStr);

            ChestConfig config = new ChestConfig(pos, name, category, priority, mode, autoFrame);

            if (nbt.contains("SimplePriority")) {
                try {
                    config.simplePrioritySelection =
                        ChestConfig.SimplePriority.valueOf(nbt.getString("SimplePriority"));
                } catch (Exception e) {
                    config.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
                }
            } else {
                config.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
            }

            chestConfigs.put(pos, config);
            *///?}
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

    private void applyPriorityUpdates(World world, Map<BlockPos, Integer> newPriorities) {
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

    private void syncConfigToProbe(World world, ChestConfig config) {
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

    private void sendPriorityUpdatesToClients(World world) {
        if (!(world instanceof net.minecraft.server.world.ServerWorld serverWorld)) return;

        // Recalculate fullness for all configs before sending
        List<BlockPos> linkedProbes = probeRegistry.getLinkedProbes();
        for (ChestConfig config : chestConfigs.values()) {
            config.cachedFullness = calculateChestFullness(world, config.position, linkedProbes);
        }

        ChestPriorityBatchPayload payload = ChestPriorityBatchPayload.fromConfigs(chestConfigs);

        for (ServerPlayerEntity player : serverWorld.getPlayers()) {
            if (player.currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    public int calculateChestFullness(World world, BlockPos chestPos, List<BlockPos> linkedProbes) {
        if (world == null) return -1;

        // OPTIMIZATION: Use index to find probe directly (O(1) instead of O(n))
        BlockPos probePos = probeRegistry.getProbeForChest(world, chestPos);
        if (probePos == null) return -1;

        BlockEntity be = world.getBlockEntity(probePos);
        if (!(be instanceof OutputProbeBlockEntity probe)) return -1;

        Inventory inv = probe.getTargetInventory();
        if (inv == null) return -1;

        int totalSlots = inv.size();
        int occupiedSlots = 0;

        for (int i = 0; i < totalSlots; i++) {
            if (!inv.getStack(i).isEmpty()) {
                occupiedSlots++;
            }
        }

        return totalSlots > 0 ? (occupiedSlots * 100 / totalSlots) : 0;
    }

    private List<ItemStack> getChestPreviewItems(World world, BlockPos chestPos, List<BlockPos> linkedProbes) {
        List<ItemStack> items = new ArrayList<>();
        if (world == null) return items;

        // OPTIMIZATION: Use index to find probe directly (O(1) instead of O(n))
        BlockPos probePos = probeRegistry.getProbeForChest(world, chestPos);
        if (probePos == null) return items;

        BlockEntity be = world.getBlockEntity(probePos);
        if (!(be instanceof OutputProbeBlockEntity probe)) return items;

        Inventory inv = probe.getTargetInventory();
        if (inv == null) return items;

        for (int i = 0; i < inv.size() && items.size() < 8; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                items.add(stack.copy());
            }
        }

        return items;
    }

    //? if >=1.21.8 {
    private void writeNameToChest(World world, BlockPos chestPos, String customName) {
        if (world == null) return;

        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (blockEntity == null) return;

        NbtCompound nbt = blockEntity.createNbt(world.getRegistryManager());

        if (customName == null || customName.isEmpty()) {
            nbt.remove("CustomName");
        } else {
            Text textComponent = Text.literal(customName);
            DataResult<NbtElement> result = TextCodecs.CODEC.encodeStart(
                    world.getRegistryManager().getOps(NbtOps.INSTANCE),
                    textComponent
            );
            result.result().ifPresent(nbtElement -> {
                nbt.put("CustomName", nbtElement);
            });
        }

        try (ErrorReporter.Logging logging = new ErrorReporter.Logging(blockEntity.getReporterContext(), LogUtils.getLogger())) {
            blockEntity.read(NbtReadView.create(logging, world.getRegistryManager(), nbt));
        }
        blockEntity.markDirty();

        BlockState state = world.getBlockState(chestPos);
        world.updateListeners(chestPos, state, state, 3);
    }

    private String readNameFromChest(World world, BlockPos chestPos) {
        if (world == null) return "";

        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (blockEntity == null) return "";

        NbtCompound nbt = blockEntity.createNbt(world.getRegistryManager());

        if (nbt.contains("CustomName")) {
            try {
                DataResult<Text> result = TextCodecs.CODEC.parse(
                        world.getRegistryManager().getOps(NbtOps.INSTANCE),
                        nbt.get("CustomName")
                );
                return result.result()
                        .map(Text::getString)
                        .orElse("");
            } catch (Exception e) {
                return "";
            }
        }

        return "";
    }

    private void clearNameFromChest(World world, BlockPos chestPos) {
        if (world == null) return;

        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (blockEntity == null) return;

        NbtCompound nbt = blockEntity.createNbt(world.getRegistryManager());

        if (nbt.contains("CustomName")) {
            nbt.remove("CustomName");

            try (ErrorReporter.Logging logging = new ErrorReporter.Logging(blockEntity.getReporterContext(), LogUtils.getLogger())) {
                blockEntity.read(NbtReadView.create(logging, world.getRegistryManager(), nbt));
            }
            blockEntity.markDirty();

            BlockState state = world.getBlockState(chestPos);
            world.updateListeners(chestPos, state, state, 3);
        }
    }
    //?} else {
    /*private void writeNameToChest(World world, BlockPos chestPos, String customName) {
        if (world == null) return;
        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (blockEntity == null) return;

        NbtCompound nbt = blockEntity.createNbt(world.getRegistryManager());

        if (customName == null || customName.isEmpty()) {
            nbt.remove("CustomName");
        } else {
            Text textComponent = Text.literal(customName);
            nbt.putString("CustomName", Text.Serialization.toJsonString(textComponent, world.getRegistryManager()));
        }

        blockEntity.read(nbt, world.getRegistryManager());
        blockEntity.markDirty();

        BlockState state = world.getBlockState(chestPos);
        world.updateListeners(chestPos, state, state, 3);
    }

    private String readNameFromChest(World world, BlockPos chestPos) {
        if (world == null) return "";

        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (blockEntity == null) return "";

        NbtCompound nbt = blockEntity.createNbt(world.getRegistryManager());

        if (nbt.contains("CustomName")) {
            try {
                Text customName = Text.Serialization.fromJson(nbt.getString("CustomName"), world.getRegistryManager());
                return customName != null ? customName.getString() : "";
            } catch (Exception e) {
                return "";
            }
        }
        return "";
    }

    private void clearNameFromChest(World world, BlockPos chestPos) {
        if (world == null) return;

        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (blockEntity == null) return;

        NbtCompound nbt = blockEntity.createNbt(world.getRegistryManager());

        if (nbt.contains("CustomName")) {
            nbt.remove("CustomName");
            blockEntity.read(nbt, world.getRegistryManager());
            blockEntity.markDirty();

            BlockState state = world.getBlockState(chestPos);
            world.updateListeners(chestPos, state, state, 3);
        }
    }
    *///?}
}