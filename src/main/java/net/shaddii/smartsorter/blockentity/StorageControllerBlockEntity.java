package net.shaddii.smartsorter.blockentity;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLong;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.blockentity.controller.*;
import net.shaddii.smartsorter.chunk.ChunkKeeper;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.util.*;
import org.jetbrains.annotations.Nullable;

//? if >=1.21.8 {
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
//?} else {
/*import net.minecraft.registry.RegistryWrapper;
 *///?}

import java.util.*;

/**
 * Main storage controller - now delegates to specialized services.
 * OPTIMIZED: 300 lines vs 1000+, better separation of concerns.
 */
public class StorageControllerBlockEntity extends BlockEntity
        implements NamedScreenHandlerFactory, Inventory {

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

    public static void tick(World world, BlockPos pos, BlockState state,
                            StorageControllerBlockEntity be) {
        if (world.isClient()) return;

        if (be.firstTick) {
            be.firstTick = false;
            be.detectAllChests();
        }

        long currentTime = world.getTime();

        // Periodic validation
        if (currentTime % VALIDATION_INTERVAL == 0) {
            be.validateLinks();
        }

        // OPTIMIZATION: Adaptive cache duration based on network size
        int probeCount = be.probeRegistry.getProbeCount();
        long cacheDuration = probeCount > LARGE_NETWORK_THRESHOLD
                ? 20
                : CACHE_DURATION;

        // OPTIMIZATION: Only update if significantly dirty
        boolean shouldUpdate = be.networkDirty &&
                (currentTime - be.lastCacheUpdate >= cacheDuration) &&
                (be.dirtyCounter >= DIRTY_THRESHOLD || probeCount > LARGE_NETWORK_THRESHOLD);

        if (shouldUpdate) {
            be.updateNetworkCache();
            be.lastCacheUpdate = currentTime;
            be.syncToViewers();
            be.networkDirty = false;
            be.dirtyCounter = 0;
        }
    }

    private void detectAllChests() {
        if (world == null) return;

        // OPTIMIZATION: Use batch version to avoid 1680 full priority recalculations
        chestConfigManager.onAllChestsDetected(world, probeRegistry.getLinkedProbes());
    }

    private void validateLinks() {
        probeRegistry.validate(world);

        // Intakes in unloaded chunks stay linked: looking them up would
        // force a synchronous chunk load.
        linkedIntakes.removeIf(intakePos -> {
            if (!world.isChunkLoaded(intakePos)) return false;
            BlockEntity be = world.getBlockEntity(intakePos);
            return !(be instanceof IntakeBlockEntity);
        });
    }

    // ========================================
    // NETWORK MANAGEMENT
    // ========================================

    public void updateNetworkCache() {
        if (world == null) return;

        // If user operation pending and large network, force full update
        if (userOperationPending && probeRegistry.getProbeCount() > LARGE_NETWORK_THRESHOLD) {
            networkManager.updateCacheForceFull(world, probeRegistry.getLinkedProbes());
            userOperationPending = false;
        } else {
            networkManager.updateCache(world, probeRegistry.getLinkedProbes());
        }
    }

    private void syncToViewers() {
        if (!(world instanceof ServerWorld serverWorld)) return;

        long currentTime = world.getTime();
        if (currentTime - lastSyncTime < SYNC_COOLDOWN) return;
        lastSyncTime = currentTime;

        if (!networkManager.hasDeltas()) return;

        Map<ItemVariant, Long> deltas = networkManager.consumeDeltas();

        for (ServerPlayerEntity player : serverWorld.getPlayers()) {
            if (player.currentScreenHandler instanceof StorageControllerScreenHandler handler
                    && handler.controller == this) {
                handler.sendNetworkUpdate(player, deltas);
            }
        }
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
        if (world == null || world.isClient()) return;

        networkManager.updateCacheForceFull(world, probeRegistry.getLinkedProbes());

        lastCacheUpdate = world.getTime();

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
            markDirty();

            if (world != null) {
                // Detect and add chest config
                BlockEntity be = world.getBlockEntity(probePos);
                if (be instanceof OutputProbeBlockEntity probe) {
                    BlockPos targetPos = probe.getTargetPos();
                    if (targetPos != null) {
                        chestConfigManager.onChestDetected(world, targetPos, probe);
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
            markDirty();

            if (world != null) {
                BlockEntity be = world.getBlockEntity(probePos);
                if (be instanceof OutputProbeBlockEntity probe) {
                    BlockPos targetPos = probe.getTargetPos();
                    if (targetPos != null) {
                        chestConfigManager.onChestRemoved(world, targetPos, probeRegistry.getLinkedProbes());
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
        markDirty();
        updateListeners();
        return true;
    }

    public boolean removeIntake(BlockPos intakePos) {
        boolean removed = linkedIntakes.remove(intakePos);
        if (removed) {
            markDirty();
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
        ItemRoutingService.InsertionResult result = routingService.insertItem(world, stack);

        // OPTIMIZATION: Incrementally update cache instead of full rescan
        if (result.amountInserted() > 0) {
            networkManager.adjustItemCount(variant, result.amountInserted());
            networkDirty = true; // Still mark dirty for eventual full verification
            userOperationPending = true;
        }
        return result;
    }

    public ItemStack extractItem(ItemVariant variant, int amount) {
        ItemStack result = routingService.extractItem(world, variant, amount, networkManager);

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
            BlockEntity be = world.getBlockEntity(probePos);
            if (be instanceof OutputProbeBlockEntity probe) {
                if (probe.hasSpace(variant, amount)) {
                    return true;
                }
            }
        }

        // Check all probes for empty space
        for (BlockPos probePos : probeRegistry.getLinkedProbes()) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            if (!probe.accepts(variant)) continue;

            Inventory inv = probe.getTargetInventory();
            if (inv == null) continue;

            for (int i = 0; i < inv.size(); i++) {
                if (inv.getStack(i).isEmpty()) {
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
        return chestConfigManager.getChestConfigs(world, probeRegistry.getLinkedProbes());
    }

    public ChestConfig getChestConfig(BlockPos position) {
        return chestConfigManager.getChestConfig(position);
    }

    public void updateChestConfig(BlockPos position, ChestConfig config) {
        chestConfigManager.updateChestConfig(world, position, config, this);
        probeRegistry.invalidateCache();

        // INVALIDATE ALL PROBE CACHES FOR THIS CHEST
        for (BlockPos probePos : probeRegistry.getLinkedProbes()) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (be instanceof OutputProbeBlockEntity probe) {
                if (position.equals(probe.getTargetPos())) {
                    probe.invalidateConfigCache();
                }
            }
        }

        RoutingIndexEpoch.bump(); // filter / bulk edit
        networkDirty = true;
        markDirty();
        updateListeners();
    }

    public void removeChestConfig(BlockPos chestPos) {
        chestConfigManager.removeChestConfig(world, chestPos, this);
        probeRegistry.invalidateCache();
        RoutingIndexEpoch.bump();
        networkDirty = true;
        markDirty();
    }

    public boolean isChestLinked(BlockPos chestPos) {
        return chestConfigManager.isChestLinked(world, chestPos, probeRegistry.getLinkedProbes());
    }

    // ========================================
    // SORTING (Delegates to ChestSortingService)
    // ========================================

    public void sortChestsInOrder(List<BlockPos> positions, @Nullable ServerPlayerEntity player) {
        updateNetworkCache();
        sortingService.sortChests(world, positions, player);
        markDirty();
        updateNetworkCache();
    }

    // ========================================
    // PROCESS PROBES (Delegates to ProcessProbeManager)
    // ========================================

    public boolean registerProcessProbe(BlockPos pos, String machineType) {
        boolean result = processProbeManager.registerProbe(world, pos, machineType);
        if (result) {
            markDirty();
            networkDirty = true;
        }
        return result;
    }

    public void unregisterProcessProbe(BlockPos pos) {
        processProbeManager.unregisterProbe(world, pos);
        markDirty();
        networkDirty = true;
    }

    public void updateProbeConfig(ProcessProbeConfig config) {
        processProbeManager.updateConfig(world, config, this);
        markDirty();
        networkDirty = true;
    }

    public Map<BlockPos, ProcessProbeConfig> getProcessProbeConfigs() {
        return processProbeManager.getConfigs();
    }

    public ProcessProbeConfig getProbeConfig(BlockPos pos) {
        return processProbeManager.getConfig(pos);
    }

    public void syncProbeStatsToClients(BlockPos probePos, int itemsProcessed) {
        processProbeManager.syncStatsToClients(world, probePos, itemsProcessed, this);
    }

    // ========================================
    // XP MANAGEMENT
    // ========================================

    private int storedExperience = 0;

    public void addExperience(int amount) {
        storedExperience += amount;
        markDirty();
    }

    public int getStoredExperience() {
        return storedExperience;
    }

    public int collectExperience() {
        int xp = storedExperience;
        storedExperience = 0;
        markDirty();
        return xp;
    }

    // ========================================
    // CAPACITY CALCULATION
    // ========================================

    public int calculateTotalFreeSlots() {
        if (world == null) return 0;

        int totalFree = 0;
        for (BlockPos probePos : probeRegistry.getLinkedProbes()) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            Inventory inv = probe.getTargetInventory();
            if (inv == null) continue;

            for (int i = 0; i < inv.size(); i++) {
                if (inv.getStack(i).isEmpty()) {
                    totalFree++;
                }
            }
        }

        return totalFree;
    }

    public int calculateTotalCapacity() {
        if (world == null) return 0;

        int totalSlots = 0;
        for (BlockPos probePos : probeRegistry.getLinkedProbes()) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            Inventory inv = probe.getTargetInventory();
            if (inv != null) {
                totalSlots += inv.size();
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
        return chestConfigManager.calculateChestFullness(world, chestPos, probeRegistry.getLinkedProbes());
    }

    /**
     * Sorts a single chest into network (public API for ChunkedSorter).
     */
    public void sortChestIntoNetwork(BlockPos chestPos,
                                     Map<ItemVariant, Long> overflowCounts,
                                     Map<ItemVariant, String> overflowDestinations) {
        sortingService.sortChest(world, chestPos, overflowCounts, overflowDestinations);
    }

    // ========================================
    // NBT SERIALIZATION (Using original pattern)
    // ========================================

    //? if >=1.21.8 {
    @Override
    public void writeData(WriteView view) {
        super.writeData(view);

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
    private static void writeWhitelists(WriteView view, Map<BlockPos, ChestConfig> configs) {
        int index = 0;
        for (Map.Entry<BlockPos, ChestConfig> entry : configs.entrySet()) {
            ChestConfig config = entry.getValue();
            Set<net.minecraft.item.Item> items = config.getWhitelist();
            if (!config.whitelistEnabled && !config.whitelistEditMode && items.isEmpty()) {
                continue;
            }
            String prefix = "ssbulk_wl_" + index;
            view.putLong(prefix + "_pos", entry.getKey().asLong());
            view.putBoolean(prefix + "_enabled", config.whitelistEnabled);
            view.putBoolean(prefix + "_editMode", config.whitelistEditMode);
            view.putInt(prefix + "_count", items.size());
            int itemIndex = 0;
            for (net.minecraft.item.Item item : items) {
                view.putString(prefix + "_item_" + itemIndex, net.minecraft.registry.Registries.ITEM.getId(item).toString());
                itemIndex++;
            }
            index++;
        }
        view.putInt("ssbulk_wl_chest_count", index);
    }

    private void readWhitelists(ReadView view) {
        int chestCount = view.getInt("ssbulk_wl_chest_count", 0);
        for (int index = 0; index < chestCount; index++) {
            String prefix = "ssbulk_wl_" + index;
            Optional<Long> posLong = view.getOptionalLong(prefix + "_pos");
            if (posLong.isEmpty()) {
                continue;
            }
            ChestConfig config = chestConfigManager.getChestConfig(BlockPos.fromLong(posLong.get()));
            if (config == null) {
                continue; // chest no longer linked
            }
            Set<net.minecraft.item.Item> items = new HashSet<>();
            int itemCount = view.getInt(prefix + "_count", 0);
            for (int itemIndex = 0; itemIndex < itemCount; itemIndex++) {
                view.getOptionalString(prefix + "_item_" + itemIndex).ifPresent(idStr -> {
                    net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(idStr);
                    if (id != null) {
                        items.add(net.minecraft.registry.Registries.ITEM.get(id));
                    }
                });
            }
            config.whitelistEnabled = view.getBoolean(prefix + "_enabled", false);
            config.whitelistEditMode = view.getBoolean(prefix + "_editMode", false);
            config.setWhitelist(items);
        }
    }

    @Override
    public void readData(ReadView view) {
        super.readData(view);

        // Probes
        int probeCount = view.getInt("probe_count", 0);
        for (int i = 0; i < probeCount; i++) {
            view.getOptionalLong("probe_" + i).ifPresent(posLong ->
                    probeRegistry.addProbe(BlockPos.fromLong(posLong))
            );
        }

        // Intakes
        linkedIntakes.clear();
        int intakeCount = view.getInt("intake_count", 0);
        for (int i = 0; i < intakeCount; i++) {
            view.getOptionalLong("intake_" + i).ifPresent(posLong ->
                    linkedIntakes.add(BlockPos.fromLong(posLong))
            );
        }

        // Process probes
        NbtList ppList = new NbtList();
        int ppCount = view.getInt("processProbeCount", 0);
        for (int i = 0; i < ppCount; i++) {
            NbtCompound ppNbt = new NbtCompound();
            final int index = i;

            view.getOptionalLong("pp_pos_" + index).ifPresent(pos -> ppNbt.putLong("position", pos));

            String customName = view.getString("pp_name_" + index, "");
            if (!customName.isEmpty()) {
                ppNbt.putString("customName", customName);
            }

            ppNbt.putString("machineType", view.getString("pp_type_" + index, "Unknown"));
            ppNbt.putBoolean("enabled", view.getBoolean("pp_enabled_" + index, true));
            ppNbt.putString("recipeFilter", view.getString("pp_recipe_" + index, "ORES_ONLY"));
            ppNbt.putString("fuelFilter", view.getString("pp_fuel_" + index, "COAL_ONLY"));
            ppNbt.putInt("itemsProcessed", view.getInt("pp_processed_" + index, 0));
            ppNbt.putInt("index", view.getInt("pp_index_" + index, 0));

            ppList.add(ppNbt);
        }
        processProbeManager.readFromNbt(ppList);

        // XP
        storedExperience = view.getInt("storedXp", 0);

        // Chest configs
        NbtList chestList = new NbtList();
        int chestCount = view.getInt("chestConfigCount", 0);
        for (int i = 0; i < chestCount; i++) {
            final int index = i;
            NbtCompound chestNbt = new NbtCompound();

            view.getOptionalLong("chest_pos_" + index).ifPresent(pos -> chestNbt.putLong("Pos", pos));
            chestNbt.putString("Name", view.getString("chest_name_" + index, ""));
            chestNbt.putString("Category", view.getString("chest_cat_" + index, "smartsorter:all"));
            chestNbt.putInt("Priority", view.getInt("chest_pri_" + index, 1));
            chestNbt.putString("Mode", view.getString("chest_mode_" + index, "NONE"));
            chestNbt.putBoolean("AutoFrame", view.getBoolean("chest_frame_" + index, false));

            String simplePri = view.getString("chest_spri_" + index, "");
            if (!simplePri.isEmpty()) {
                chestNbt.putString("SimplePriority", simplePri);
            }

            chestList.add(chestNbt);
        }
        chestConfigManager.readFromNbt(chestList);
        readWhitelists(view);
    }
    //?} else {
    /*@Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.writeNbt(nbt, registryLookup);

        // Probes
        NbtList probeList = new NbtList();
        for (BlockPos probe : probeRegistry.getLinkedProbes()) {
            probeList.add(NbtLong.of(probe.asLong()));
        }
        nbt.put("Probes", probeList);

        // Intakes
        NbtList intakeList = new NbtList();
        for (BlockPos intake : linkedIntakes) {
            intakeList.add(NbtLong.of(intake.asLong()));
        }
        nbt.put("Intakes", intakeList);

        // Process probes
        nbt.put("ProcessProbes", processProbeManager.writeToNbt());

        // Chest configs
        nbt.put("ChestConfigs", chestConfigManager.writeToNbt());

        // XP
        nbt.putInt("StoredXP", storedExperience);
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.readNbt(nbt, registryLookup);

        // Probes
        if (nbt.contains("Probes", NbtElement.LIST_TYPE)) {
            NbtList probeList = nbt.getList("Probes", NbtElement.LONG_TYPE);
            for (int i = 0; i < probeList.size(); i++) {
                probeRegistry.addProbe(BlockPos.fromLong(((NbtLong)probeList.get(i)).longValue()));
            }
        }

        // Intakes
        linkedIntakes.clear();
        if (nbt.contains("Intakes", NbtElement.LIST_TYPE)) {
            NbtList intakeList = nbt.getList("Intakes", NbtElement.LONG_TYPE);
            for (int i = 0; i < intakeList.size(); i++) {
                linkedIntakes.add(BlockPos.fromLong(((NbtLong)intakeList.get(i)).longValue()));
            }
        }

        // Process probes
        if (nbt.contains("ProcessProbes", NbtElement.LIST_TYPE)) {
            processProbeManager.readFromNbt(nbt.getList("ProcessProbes", NbtElement.COMPOUND_TYPE));
        }

        // Chest configs
        if (nbt.contains("ChestConfigs", NbtElement.LIST_TYPE)) {
            chestConfigManager.readFromNbt(nbt.getList("ChestConfigs", NbtElement.COMPOUND_TYPE));
        }

        // XP
        storedExperience = nbt.getInt("StoredXP");
    }
    *///?}

    // ========================================
    // INVENTORY INTERFACE (Empty - controller doesn't store items)
    // ========================================

    @Override public int size() { return 0; }
    @Override public boolean isEmpty() { return true; }
    @Override public ItemStack getStack(int slot) { return ItemStack.EMPTY; }
    @Override public ItemStack removeStack(int slot, int amount) { return ItemStack.EMPTY; }
    @Override public ItemStack removeStack(int slot) { return ItemStack.EMPTY; }
    @Override public void setStack(int slot, ItemStack stack) {}
    @Override public void clear() {}

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return pos.isWithinDistance(player.getBlockPos(), 8.0);
    }

    // ========================================
    // SCREEN HANDLER
    // ========================================

    @Override
    public Text getDisplayName() {
        return Text.translatable("container.smartsorter.storage_controller");
    }

    @Nullable
    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        return new StorageControllerScreenHandler(syncId, playerInventory, this);
    }

    // ========================================
    // CLEANUP
    // ========================================

    public void onRemoved() {
        if (world instanceof ServerWorld serverWorld) {
            ChunkKeeper.unregister(serverWorld, pos);
        }
        if (world != null && !world.isClient()) {
            // Drop XP as experience orbs
            if (storedExperience > 0) {
                // Spawn XP orbs
                net.minecraft.entity.ExperienceOrbEntity.spawn(
                        (ServerWorld) world,
                        net.minecraft.util.math.Vec3d.ofCenter(pos),
                        storedExperience
                );

                // Notify nearby players
                for (net.minecraft.server.network.ServerPlayerEntity player :
                        ((ServerWorld) world).getPlayers()) {
                    double distance = player.squaredDistanceTo(
                            pos.getX() + 0.5,
                            pos.getY() + 0.5,
                            pos.getZ() + 0.5
                    );

                    if (distance < 256) { // 16 blocks
                        player.sendMessage(
                                net.minecraft.text.Text.literal(
                                        "§a[Smart Sorter] §e" + storedExperience + " XP dropped from controller!"
                                ).formatted(net.minecraft.util.Formatting.YELLOW),
                                false
                        );
                    }
                }

                storedExperience = 0;
            }

            // Unlink all probes (ensure they keep their configs)
            for (BlockPos probePos : probeRegistry.getLinkedProbes()) {
                BlockEntity be = world.getBlockEntity(probePos);
                if (be instanceof OutputProbeBlockEntity probe) {
                    probe.removeController(this.pos);
                }
            }

            // Unlink intakes
            for (BlockPos intakePos : linkedIntakes) {
                BlockEntity be = world.getBlockEntity(intakePos);
                if (be instanceof IntakeBlockEntity intake) {
                    intake.clearController();
                }
            }

            // Unlink process probes (they should keep configs)
            processProbeManager.unlinkAll(world);

            // Clear chest names
            chestConfigManager.clearAllChestNames(world);
        }
    }

    private void updateListeners() {
        if (world != null) {
            BlockState state = world.getBlockState(pos);
            world.updateListeners(pos, state, state, 3);
        }
    }
}