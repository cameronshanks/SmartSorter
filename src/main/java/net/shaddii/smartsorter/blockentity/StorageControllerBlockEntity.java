package net.shaddii.smartsorter.blockentity;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.blockentity.controller.*;
import net.shaddii.smartsorter.chunk.ChunkKeeper;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.util.*;
import org.jetbrains.annotations.Nullable;
import java.util.*;

/**
 * Main storage controller - now delegates to specialized services.
 * OPTIMIZED: 300 lines vs 1000+, better separation of concerns.
 */
public class StorageControllerBlockEntity extends BlockEntity
        implements MenuProvider, Container {

    // ========================================
    // CONSTANTS
    // ========================================

    private static final long CACHE_DURATION = 100;
    private static final long SYNC_COOLDOWN = 5;
    private static final long VALIDATION_INTERVAL = 200L;

    private static final int LARGE_NETWORK_THRESHOLD = 500;
    private static final long LARGE_NETWORK_CACHE_DURATION = 200;

    // ========================================
    // SERVICE COMPONENTS
    // ========================================

    private final NetworkInventoryManager networkManager;
    private final ProbeRegistry probeRegistry;
    private final ItemRoutingService routingService;
    private final ChestSortingService sortingService;
    private final ProcessProbeManager processProbeManager;
    private final ChestConfigManager chestConfigManager;

    // ========================================
    // STATE
    // ========================================

    private final List<BlockPos> linkedIntakes = new ArrayList<>();

    private long lastCacheUpdate = 0;
    private long lastChangeStamp = Long.MIN_VALUE;
    private long lastSyncTime = 0;
    private boolean networkDirty = true;

    private int dirtyCounter = 0;
    private static final int DIRTY_THRESHOLD = 10;
    private boolean userOperationPending = false;
    private boolean firstTick = true;

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public StorageControllerBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.STORAGE_CONTROLLER_BE_TYPE, pos, state);

        // Initialize services
        this.networkManager = new NetworkInventoryManager();
        this.probeRegistry = new ProbeRegistry();
        this.routingService = new ItemRoutingService(probeRegistry);
        this.sortingService = new ChestSortingService(routingService, probeRegistry, networkManager);
        this.processProbeManager = new ProcessProbeManager();
        this.chestConfigManager = new ChestConfigManager(probeRegistry);
    }

    // ========================================
    // TICK LOGIC
    // ========================================

    public static void tick(Level world, BlockPos pos, BlockState state,
                            StorageControllerBlockEntity be) {
        if (world.isClientSide()) return;

        if (be.firstTick) {
            be.firstTick = false;
            be.detectAllChests();
        }

        long currentTime = world.getGameTime();

        // Periodic validation
        if (currentTime % VALIDATION_INTERVAL == 0) {
            be.validateLinks();
        }

        // OPTIMIZATION: Adaptive cache duration based on network size
        int probeCount = be.probeRegistry.getProbeCount();
        long cacheDuration = probeCount > LARGE_NETWORK_THRESHOLD
                ? 20
                : CACHE_DURATION;

        // Refresh at most once per cacheDuration, and only when something
        // changed: a network operation (networkDirty) or a linked chest edited
        // from outside the network (hand, hopper), seen via its change counter.
        boolean shouldUpdate = false;
        if (currentTime - be.lastCacheUpdate >= cacheDuration) {
            long stamp = be.linkedChestChangeStamp();
            shouldUpdate = be.networkDirty || stamp != be.lastChangeStamp;
            be.lastChangeStamp = stamp;
        }

        if (shouldUpdate) {
            be.updateNetworkCache();
            be.lastCacheUpdate = currentTime;
            be.syncToViewers();
            be.networkDirty = false;
            be.dirtyCounter = 0;
        }
    }

    private void detectAllChests() {
        if (level == null) return;

        // OPTIMIZATION: Use batch version to avoid 1680 full priority recalculations
        chestConfigManager.onAllChestsDetected(level, probeRegistry.getLinkedProbes());
    }

    private void validateLinks() {
        probeRegistry.validate(level);

        // Intakes in unloaded chunks stay linked: looking them up would
        // force a synchronous chunk load.
        linkedIntakes.removeIf(intakePos -> {
            if (!level.hasChunkAt(intakePos)) return false;
            BlockEntity be = level.getBlockEntity(intakePos);
            return !(be instanceof IntakeBlockEntity);
        });
    }

    // ========================================
    // NETWORK MANAGEMENT
    // ========================================

    public void updateNetworkCache() {
        if (level == null) return;

        // If user operation pending and large network, force full update
        if (userOperationPending && probeRegistry.getProbeCount() > LARGE_NETWORK_THRESHOLD) {
            networkManager.updateCacheForceFull(level, probeRegistry.getLinkedProbes());
            userOperationPending = false;
        } else {
            networkManager.updateCache(level, probeRegistry.getLinkedProbes());
        }
    }

    private void syncToViewers() {
        if (!(level instanceof ServerLevel serverWorld)) return;

        long currentTime = level.getGameTime();
        if (currentTime - lastSyncTime < SYNC_COOLDOWN) return;
        lastSyncTime = currentTime;

        if (!networkManager.hasDeltas()) return;

        Map<ItemVariant, Long> deltas = networkManager.consumeDeltas();

        for (ServerPlayer player : serverWorld.players()) {
            if (player.containerMenu instanceof StorageControllerScreenHandler handler
                    && handler.controller == this) {
                handler.sendNetworkUpdate(player, deltas);
            }
        }
    }

    /**
     * Sum of the linked chests' change counters: one field read per chest.
     * The counters only grow, so any content change moves the sum.
     */
    private long linkedChestChangeStamp() {
        long stamp = 0;
        for (BlockPos probePos : probeRegistry.getLinkedProbes()) {
            if (!level.hasChunkAt(probePos)) continue;
            if (level.getBlockEntity(probePos) instanceof OutputProbeBlockEntity probe) {
                BlockPos target = probe.getTargetPos();
                if (target != null && level.hasChunkAt(target)
                        && level.getBlockEntity(target) instanceof ChangeCounter counter) {
                    stamp += counter.smartsorter$getChangeCount();
                }
            }
        }
        return stamp;
    }

    public Map<ItemVariant, Long> getNetworkItems() {
        return networkManager.getNetworkItems();
    }

    public void onProbeInventoryChanged(OutputProbeBlockEntity probe) {
        dirtyCounter++;

        // OPTIMIZATION: Only set dirty if threshold reached
        if (dirtyCounter >= DIRTY_THRESHOLD) {
            networkDirty = true;
        }
    }

    public void forceUpdateCache() {
        if (level == null || level.isClientSide()) return;

        networkManager.updateCacheForceFull(level, probeRegistry.getLinkedProbes());

        lastCacheUpdate = level.getGameTime();

        int probeCount = probeRegistry.getProbeCount();
        if (probeCount <= LARGE_NETWORK_THRESHOLD) {
            networkDirty = false;
        }
        // For large networks: leave networkDirty = true, tick will handle it

        dirtyCounter = 0;
    }

    // ========================================
    // PROBE MANAGEMENT (Delegates to ProbeRegistry)
    // ========================================

    public boolean addProbe(BlockPos probePos) {
        boolean added = probeRegistry.addProbe(probePos);

        if (added) {
            networkDirty = true;
            setChanged();

            if (level != null) {
                // Detect and add chest config
                BlockEntity be = level.getBlockEntity(probePos);
                if (be instanceof OutputProbeBlockEntity probe) {
                    BlockPos targetPos = probe.getTargetPos();
                    if (targetPos != null) {
                        chestConfigManager.onChestDetected(level, targetPos, probe);
                    }
                }

                updateListeners();
            }
        }

        return added;
    }

    public boolean removeProbe(BlockPos probePos) {
        boolean removed = probeRegistry.removeProbe(probePos);

        if (removed) {
            networkDirty = true;
            setChanged();

            if (level != null) {
                BlockEntity be = level.getBlockEntity(probePos);
                if (be instanceof OutputProbeBlockEntity probe) {
                    BlockPos targetPos = probe.getTargetPos();
                    if (targetPos != null) {
                        chestConfigManager.onChestRemoved(level, targetPos, probeRegistry.getLinkedProbes());
                    }
                }

                updateListeners();
            }
        }

        return removed;
    }

    public List<BlockPos> getLinkedProbes() {
        return probeRegistry.getLinkedProbes();
    }

    public int getLinkedInventoryCount() {
        return probeRegistry.getProbeCount();
    }

    // ========================================
    // INTAKE MANAGEMENT
    // ========================================

    public boolean addIntake(BlockPos intakePos) {
        if (linkedIntakes.contains(intakePos)) return false;

        linkedIntakes.add(intakePos);
        setChanged();
        updateListeners();
        return true;
    }

    public boolean removeIntake(BlockPos intakePos) {
        boolean removed = linkedIntakes.remove(intakePos);
        if (removed) {
            setChanged();
            updateListeners();
        }
        return removed;
    }

    public List<BlockPos> getLinkedIntakes() {
        return new ArrayList<>(linkedIntakes);
    }

    // ========================================
    // ITEM OPERATIONS (Delegates to RoutingService)
    // ========================================

    public ItemRoutingService.InsertionResult insertItem(ItemStack stack) {
        ItemVariant variant = ItemVariant.of(stack);
        ItemRoutingService.InsertionResult result = routingService.insertItem(level, stack);

        // OPTIMIZATION: Incrementally update cache instead of full rescan
        if (result.amountInserted() > 0) {
            networkManager.adjustItemCount(variant, result.amountInserted());
            networkDirty = true; // Still mark dirty for eventual full verification
            userOperationPending = true;
        }
        return result;
    }

    public ItemStack extractItem(ItemVariant variant, int amount) {
        ItemStack result = routingService.extractItem(level, variant, amount, networkManager);

        // OPTIMIZATION: Incrementally update cache instead of full rescan
        if (!result.isEmpty()) {
            networkManager.adjustItemCount(variant, -result.getCount()); // Negative delta
            networkDirty = true; // Still mark dirty for eventual full verification
            userOperationPending = true;
        }
        return result;
    }

    public boolean canInsertItem(ItemVariant variant, int amount) {
        // Quick check using network data
        List<BlockPos> probesWithItem = networkManager.getProbesWithItem(variant);

        for (BlockPos probePos : probesWithItem) {
            BlockEntity be = level.getBlockEntity(probePos);
            if (be instanceof OutputProbeBlockEntity probe) {
                if (probe.hasSpace(variant, amount)) {
                    return true;
                }
            }
        }

        // Check all probes for empty space
        for (BlockPos probePos : probeRegistry.getLinkedProbes()) {
            BlockEntity be = level.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            if (!probe.accepts(variant)) continue;

            Container inv = probe.getTargetInventory();
            if (inv == null) continue;

            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i).isEmpty()) {
                    return true;
                }
            }
        }

        return false;
    }

    // ========================================
    // CHEST MANAGEMENT (Delegates to ChestConfigManager)
    // ========================================

    public Map<BlockPos, ChestConfig> getChestConfigs() {
        return chestConfigManager.getChestConfigs(level, probeRegistry.getLinkedProbes());
    }

    public ChestConfig getChestConfig(BlockPos position) {
        return chestConfigManager.getChestConfig(position);
    }

    public void updateChestConfig(BlockPos position, ChestConfig config) {
        chestConfigManager.updateChestConfig(level, position, config, this);
        probeRegistry.invalidateCache();

        // INVALIDATE ALL PROBE CACHES FOR THIS CHEST
        for (BlockPos probePos : probeRegistry.getLinkedProbes()) {
            BlockEntity be = level.getBlockEntity(probePos);
            if (be instanceof OutputProbeBlockEntity probe) {
                if (position.equals(probe.getTargetPos())) {
                    probe.invalidateConfigCache();
                }
            }
        }

        RoutingIndexEpoch.bump(); // filter / bulk edit
        networkDirty = true;
        setChanged();
        updateListeners();
    }

    public void removeChestConfig(BlockPos chestPos) {
        chestConfigManager.removeChestConfig(level, chestPos, this);
        probeRegistry.invalidateCache();
        RoutingIndexEpoch.bump();
        networkDirty = true;
        setChanged();
    }

    public boolean isChestLinked(BlockPos chestPos) {
        return chestConfigManager.isChestLinked(level, chestPos, probeRegistry.getLinkedProbes());
    }

    // ========================================
    // SORTING (Delegates to ChestSortingService)
    // ========================================

    public void sortChestsInOrder(List<BlockPos> positions, @Nullable ServerPlayer player) {
        updateNetworkCache();
        sortingService.sortChests(level, positions, player);
        setChanged();
        updateNetworkCache();
    }

    // ========================================
    // PROCESS PROBES (Delegates to ProcessProbeManager)
    // ========================================

    public boolean registerProcessProbe(BlockPos pos, String machineType) {
        boolean result = processProbeManager.registerProbe(level, pos, machineType);
        if (result) {
            setChanged();
            networkDirty = true;
        }
        return result;
    }

    public void unregisterProcessProbe(BlockPos pos) {
        processProbeManager.unregisterProbe(level, pos);
        setChanged();
        networkDirty = true;
    }

    public void updateProbeConfig(ProcessProbeConfig config) {
        processProbeManager.updateConfig(level, config, this);
        setChanged();
        networkDirty = true;
    }

    public Map<BlockPos, ProcessProbeConfig> getProcessProbeConfigs() {
        return processProbeManager.getConfigs();
    }

    public ProcessProbeConfig getProbeConfig(BlockPos pos) {
        return processProbeManager.getConfig(pos);
    }

    public void syncProbeStatsToClients(BlockPos probePos, int itemsProcessed) {
        processProbeManager.syncStatsToClients(level, probePos, itemsProcessed, this);
    }

    // ========================================
    // XP MANAGEMENT
    // ========================================

    private int storedExperience = 0;

    public void addExperience(int amount) {
        storedExperience += amount;
        setChanged();
    }

    public int getStoredExperience() {
        return storedExperience;
    }

    public int collectExperience() {
        int xp = storedExperience;
        storedExperience = 0;
        setChanged();
        return xp;
    }

    // ========================================
    // CAPACITY CALCULATION
    // ========================================

    public int calculateTotalFreeSlots() {
        if (level == null) return 0;

        int totalFree = 0;
        for (BlockPos probePos : probeRegistry.getLinkedProbes()) {
            BlockEntity be = level.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            Container inv = probe.getTargetInventory();
            if (inv == null) continue;

            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i).isEmpty()) {
                    totalFree++;
                }
            }
        }

        return totalFree;
    }

    public int calculateTotalCapacity() {
        if (level == null) return 0;

        int totalSlots = 0;
        for (BlockPos probePos : probeRegistry.getLinkedProbes()) {
            BlockEntity be = level.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            Container inv = probe.getTargetInventory();
            if (inv != null) {
                totalSlots += inv.getContainerSize();
            }
        }

        return totalSlots;
    }

    // ========================================
    // PUBLIC API (for external callers)
    // ========================================

    /**
     * Calculates fullness of a specific chest (public API).
     */
    public int calculateChestFullness(BlockPos chestPos) {
        return chestConfigManager.calculateChestFullness(level, chestPos, probeRegistry.getLinkedProbes());
    }

    /**
     * Sorts a single chest into network (public API for ChunkedSorter).
     */
    public void sortChestIntoNetwork(BlockPos chestPos,
                                     Map<ItemVariant, Long> overflowCounts,
                                     Map<ItemVariant, String> overflowDestinations) {
        sortingService.sortChest(level, chestPos, overflowCounts, overflowDestinations);
    }

    // ========================================
    // NBT SERIALIZATION (Using original pattern)
    // ========================================

    @Override
    public void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);

        // Probes
        List<BlockPos> probes = probeRegistry.getLinkedProbes();
        view.putInt("probe_count", probes.size());
        for (int i = 0; i < probes.size(); i++) {
            view.putLong("probe_" + i, probes.get(i).asLong());
        }

        // Intakes
        view.putInt("intake_count", linkedIntakes.size());
        for (int i = 0; i < linkedIntakes.size(); i++) {
            view.putLong("intake_" + i, linkedIntakes.get(i).asLong());
        }

        // Process probes
        view.putInt("processProbeCount", processProbeManager.getConfigs().size());
        int idx = 0;
        for (ProcessProbeConfig config : processProbeManager.getConfigs().values()) {
            view.putLong("pp_pos_" + idx, config.position.asLong());
            if (config.customName != null) {
                view.putString("pp_name_" + idx, config.customName);
            }
            view.putString("pp_type_" + idx, config.machineType);
            view.putBoolean("pp_enabled_" + idx, config.enabled);
            view.putString("pp_recipe_" + idx, config.recipeFilter.asString());
            view.putString("pp_fuel_" + idx, config.fuelFilter.asString());
            view.putInt("pp_processed_" + idx, config.itemsProcessed);
            view.putInt("pp_index_" + idx, config.index);
            idx++;
        }

        // XP
        view.putInt("storedXp", storedExperience);

        // Chest configs (WITHOUT live data - just saved config)
        Map<BlockPos, ChestConfig> allConfigs = chestConfigManager.getAllConfigs();
        view.putInt("chestConfigCount", allConfigs.size());
        idx = 0;
        for (ChestConfig config : allConfigs.values()) {
            view.putLong("chest_pos_" + idx, config.position.asLong());
            view.putString("chest_name_" + idx, config.customName != null ? config.customName : "");
            view.putString("chest_cat_" + idx, config.filterCategory.asString());
            view.putInt("chest_pri_" + idx, config.priority);
            view.putString("chest_mode_" + idx, config.filterMode.name());
            view.putBoolean("chest_frame_" + idx, config.autoItemFrame);
            if (config.simplePrioritySelection != null) {
                view.putString("chest_spri_" + idx, config.simplePrioritySelection.name());
            }
            idx++;
        }

        writeWhitelists(view, allConfigs);
    }

    /*
     * Whitelists use their own "ssbulk_wl_*" keys. The names come from the
     * Bulk Edit add-on this fork grew out of, so worlds that used the add-on
     * keep their whitelists.
     */
    private static void writeWhitelists(ValueOutput view, Map<BlockPos, ChestConfig> configs) {
        int index = 0;
        for (Map.Entry<BlockPos, ChestConfig> entry : configs.entrySet()) {
            ChestConfig config = entry.getValue();
            Set<net.minecraft.world.item.Item> items = config.getWhitelist();
            if (!config.whitelistEnabled && !config.whitelistEditMode && items.isEmpty()) {
                continue;
            }
            String prefix = "ssbulk_wl_" + index;
            view.putLong(prefix + "_pos", entry.getKey().asLong());
            view.putBoolean(prefix + "_enabled", config.whitelistEnabled);
            view.putBoolean(prefix + "_editMode", config.whitelistEditMode);
            view.putInt(prefix + "_count", items.size());
            int itemIndex = 0;
            for (net.minecraft.world.item.Item item : items) {
                view.putString(prefix + "_item_" + itemIndex, net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString());
                itemIndex++;
            }
            index++;
        }
        view.putInt("ssbulk_wl_chest_count", index);
    }

    private void readWhitelists(ValueInput view) {
        int chestCount = view.getIntOr("ssbulk_wl_chest_count", 0);
        for (int index = 0; index < chestCount; index++) {
            String prefix = "ssbulk_wl_" + index;
            Optional<Long> posLong = view.getLong(prefix + "_pos");
            if (posLong.isEmpty()) {
                continue;
            }
            ChestConfig config = chestConfigManager.getChestConfig(BlockPos.of(posLong.get()));
            if (config == null) {
                continue; // chest no longer linked
            }
            Set<net.minecraft.world.item.Item> items = new HashSet<>();
            int itemCount = view.getIntOr(prefix + "_count", 0);
            for (int itemIndex = 0; itemIndex < itemCount; itemIndex++) {
                view.getString(prefix + "_item_" + itemIndex).ifPresent(idStr -> {
                    net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(idStr);
                    if (id != null) {
                        items.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id));
                    }
                });
            }
            config.whitelistEnabled = view.getBooleanOr(prefix + "_enabled", false);
            config.whitelistEditMode = view.getBooleanOr(prefix + "_editMode", false);
            config.setWhitelist(items);
        }
    }

    @Override
    public void loadAdditional(ValueInput view) {
        super.loadAdditional(view);

        // Probes
        int probeCount = view.getIntOr("probe_count", 0);
        for (int i = 0; i < probeCount; i++) {
            view.getLong("probe_" + i).ifPresent(posLong ->
                    probeRegistry.addProbe(BlockPos.of(posLong))
            );
        }

        // Intakes
        linkedIntakes.clear();
        int intakeCount = view.getIntOr("intake_count", 0);
        for (int i = 0; i < intakeCount; i++) {
            view.getLong("intake_" + i).ifPresent(posLong ->
                    linkedIntakes.add(BlockPos.of(posLong))
            );
        }

        // Process probes
        ListTag ppList = new ListTag();
        int ppCount = view.getIntOr("processProbeCount", 0);
        for (int i = 0; i < ppCount; i++) {
            CompoundTag ppNbt = new CompoundTag();
            final int index = i;

            view.getLong("pp_pos_" + index).ifPresent(pos -> ppNbt.putLong("position", pos));

            String customName = view.getStringOr("pp_name_" + index, "");
            if (!customName.isEmpty()) {
                ppNbt.putString("customName", customName);
            }

            ppNbt.putString("machineType", view.getStringOr("pp_type_" + index, "Unknown"));
            ppNbt.putBoolean("enabled", view.getBooleanOr("pp_enabled_" + index, true));
            ppNbt.putString("recipeFilter", view.getStringOr("pp_recipe_" + index, "ORES_ONLY"));
            ppNbt.putString("fuelFilter", view.getStringOr("pp_fuel_" + index, "COAL_ONLY"));
            ppNbt.putInt("itemsProcessed", view.getIntOr("pp_processed_" + index, 0));
            ppNbt.putInt("index", view.getIntOr("pp_index_" + index, 0));

            ppList.add(ppNbt);
        }
        processProbeManager.readFromNbt(ppList);

        // XP
        storedExperience = view.getIntOr("storedXp", 0);

        // Chest configs
        ListTag chestList = new ListTag();
        int chestCount = view.getIntOr("chestConfigCount", 0);
        for (int i = 0; i < chestCount; i++) {
            final int index = i;
            CompoundTag chestNbt = new CompoundTag();

            view.getLong("chest_pos_" + index).ifPresent(pos -> chestNbt.putLong("Pos", pos));
            chestNbt.putString("Name", view.getStringOr("chest_name_" + index, ""));
            chestNbt.putString("Category", view.getStringOr("chest_cat_" + index, "smartsorter:all"));
            chestNbt.putInt("Priority", view.getIntOr("chest_pri_" + index, 1));
            chestNbt.putString("Mode", view.getStringOr("chest_mode_" + index, "NONE"));
            chestNbt.putBoolean("AutoFrame", view.getBooleanOr("chest_frame_" + index, false));

            String simplePri = view.getStringOr("chest_spri_" + index, "");
            if (!simplePri.isEmpty()) {
                chestNbt.putString("SimplePriority", simplePri);
            }

            chestList.add(chestNbt);
        }
        chestConfigManager.readFromNbt(chestList);
        readWhitelists(view);
    }

    // ========================================
    // INVENTORY INTERFACE (Empty - controller doesn't store items)
    // ========================================

    @Override public int getContainerSize() { return 0; }
    @Override public boolean isEmpty() { return true; }
    @Override public ItemStack getItem(int slot) { return ItemStack.EMPTY; }
    @Override public ItemStack removeItem(int slot, int amount) { return ItemStack.EMPTY; }
    @Override public ItemStack removeItemNoUpdate(int slot) { return ItemStack.EMPTY; }
    @Override public void setItem(int slot, ItemStack stack) {}
    @Override public void clearContent() {}

    @Override
    public boolean stillValid(Player player) {
        return worldPosition.closerThan(player.blockPosition(), 8.0);
    }

    // ========================================
    // SCREEN HANDLER
    // ========================================

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.smartsorter.storage_controller");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
        return new StorageControllerScreenHandler(syncId, playerInventory, this);
    }

    // ========================================
    // CLEANUP
    // ========================================

    public void onRemoved() {
        if (level instanceof ServerLevel serverWorld) {
            ChunkKeeper.unregister(serverWorld, worldPosition);
        }
        if (level != null && !level.isClientSide()) {
            // Drop XP as experience orbs
            if (storedExperience > 0) {
                // Spawn XP orbs
                net.minecraft.world.entity.ExperienceOrb.award(
                        (ServerLevel) level,
                        net.minecraft.world.phys.Vec3.atCenterOf(worldPosition),
                        storedExperience
                );

                // Notify nearby players
                for (net.minecraft.server.level.ServerPlayer player :
                        ((ServerLevel) level).players()) {
                    double distance = player.distanceToSqr(
                            worldPosition.getX() + 0.5,
                            worldPosition.getY() + 0.5,
                            worldPosition.getZ() + 0.5
                    );

                    if (distance < 256) { // 16 blocks
                        player.sendSystemMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "§a[Smart Sorter] §e" + storedExperience + " XP dropped from controller!"
                                ).withStyle(net.minecraft.ChatFormatting.YELLOW));
                    }
                }

                storedExperience = 0;
            }

            // Unlink all probes (ensure they keep their configs)
            for (BlockPos probePos : probeRegistry.getLinkedProbes()) {
                BlockEntity be = level.getBlockEntity(probePos);
                if (be instanceof OutputProbeBlockEntity probe) {
                    probe.removeController(this.worldPosition);
                }
            }

            // Unlink intakes
            for (BlockPos intakePos : linkedIntakes) {
                BlockEntity be = level.getBlockEntity(intakePos);
                if (be instanceof IntakeBlockEntity intake) {
                    intake.clearController();
                }
            }

            // Unlink process probes (they should keep configs)
            processProbeManager.unlinkAll(level);

            // Clear chest names
            chestConfigManager.clearAllChestNames(level);
        }
    }

    private void updateListeners() {
        if (level != null) {
            BlockState state = level.getBlockState(worldPosition);
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    /** Runs before the block entity is removed, for every kind of removal. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        onRemoved();
    }
}
