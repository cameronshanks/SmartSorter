package net.shaddii.smartsorter.blockentity;

import com.mojang.serialization.DataResult;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.block.OutputProbeBlock;
import net.shaddii.smartsorter.chunk.ChunkKeeper;
import net.shaddii.smartsorter.config.SmartSorterConfig;
import net.shaddii.smartsorter.screen.OutputProbeScreenHandler;
import net.shaddii.smartsorter.util.Category;
import net.shaddii.smartsorter.util.CategoryManager;
import net.shaddii.smartsorter.util.ChangeCounter;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.util.ChestSnapshot;
import net.shaddii.smartsorter.util.RoutingIndexEpoch;
import net.shaddii.smartsorter.util.SortUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class OutputProbeBlockEntity extends BlockEntity implements ExtendedMenuProvider {
    // ========================================
    // DATA RECORD
    // ========================================
    public record ProbeData(BlockPos chestPos, @Nullable ChestConfig config) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ProbeData> CODEC = StreamCodec.ofMember(
                (value, buf) -> {
                    buf.writeBlockPos(value.chestPos);
                    buf.writeBoolean(value.config != null);
                    if (value.config != null) {
                        buf.writeBlockPos(value.config.position);
                        buf.writeUtf(value.config.customName != null ? value.config.customName : "");
                        buf.writeUtf(value.config.filterCategory.asString());
                        buf.writeVarInt(value.config.priority);
                        buf.writeUtf(value.config.filterMode.name());
                        buf.writeBoolean(value.config.strictNBTMatch);
                        buf.writeBoolean(value.config.autoItemFrame);

                        if (value.config.simplePrioritySelection != null) {
                            buf.writeBoolean(true);
                            buf.writeUtf(value.config.simplePrioritySelection.name());
                        } else {
                            buf.writeBoolean(false);
                        }
                    }
                },
                (buf) -> {
                    BlockPos chestPos = buf.readBlockPos();
                    boolean hasConfig = buf.readBoolean();
                    ChestConfig config = null;

                    if (hasConfig) {
                        BlockPos configPos = buf.readBlockPos();
                        String customName = buf.readUtf();
                        String categoryId = buf.readUtf();
                        int priority = buf.readVarInt();
                        String filterMode = buf.readUtf();
                        boolean strictNBT = buf.readBoolean();
                        boolean autoFrame = buf.readBoolean();

                        config = new ChestConfig(configPos);
                        config.customName = customName;
                        config.filterCategory = CategoryManager.getInstance().getCategory(categoryId);
                        config.priority = priority;
                        config.filterMode = ChestConfig.FilterMode.valueOf(filterMode);
                        config.strictNBTMatch = strictNBT;
                        config.autoItemFrame = autoFrame;

                        boolean hasSimplePriority = buf.readBoolean();
                        if (hasSimplePriority) {
                            String simplePriorityStr = buf.readUtf();
                            try {
                                config.simplePrioritySelection = ChestConfig.SimplePriority.valueOf(simplePriorityStr);
                            } catch (Exception e) {
                                config.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
                            }
                        } else {
                            config.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
                        }
                    }

                    return new ProbeData(chestPos, config);
                }
        );
    }

    // ========================================
    // CONSTANTS
    // ========================================

    private static final long VALIDATION_INTERVAL = 200L;

    // ========================================
    // FIELDS
    // ========================================

    // Local chest configuration storage
    private ChestConfig localChestConfig = null;

    // Multi-block linking
    private final List<BlockPos> linkedBlocks = new ArrayList<>();
    private List<BlockPos> linkedBlocksCopy = null;
    private boolean linkedBlocksCopyDirty = true;

    // Cache
    private BlockPos cachedChestPos = null;
    private ChestConfig cachedConfig = null;
    private long cacheValidUntil = 0;
    private static final long CACHE_DURATION = 20;
    private boolean needsInitialSync = true;

    // Target caches (see getTargetPos / getTargetInventory / accepts)
    private BlockState targetPosForState;
    private BlockPos cachedTargetPos;
    private Container cachedInventory;
    private BlockEntity cachedPrimary;
    private BlockEntity cachedSecondary;
    private BlockState cachedInventoryForState;
    private final ChestSnapshot snapshot = new ChestSnapshot();

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public OutputProbeBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.PROBE_BE_TYPE, pos, state);
    }

    // ========================================
    // INITIALIZATION
    // ========================================

    // Initialize chest config when probe is placed
    public void onPlaced(Level world) {
        if (world.isClientSide()) return;

        BlockPos targetPos = getTargetPos();
        if (targetPos != null && localChestConfig == null) {
            // Create default config for this chest
            localChestConfig = new ChestConfig(targetPos);

            // Set default SimplePriority based on FilterMode
            if (localChestConfig.filterMode == ChestConfig.FilterMode.OVERFLOW) {
                localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.LOWEST;
                localChestConfig.priority = 999;
            } else {
                // Default is MEDIUM for all non-overflow chests
                localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
                localChestConfig.priority = 5; // Middle priority
            }

            // Try to read existing name from chest
            BlockEntity be = world.getBlockEntity(targetPos);
            if (be != null) {
                CompoundTag nbt = be.saveWithoutMetadata(world.registryAccess());
                if (nbt.contains("CustomName")) {
                    try {
                        DataResult<Component> result = ComponentSerialization.CODEC.parse(
                                world.registryAccess().createSerializationContext(NbtOps.INSTANCE),
                                nbt.get("CustomName")
                        );
                        result.result().ifPresent(text ->
                                localChestConfig.customName = text.getString()
                        );
                    } catch (Exception ignored) {}
                }
            }

            localChestConfig.updateHiddenPriority();
            setChanged();
        }
    }

    // ========================================
    // TICK LOGIC
    // ========================================

    public static void tick(Level world, BlockPos pos, BlockState state, OutputProbeBlockEntity be) {
        if (world.isClientSide()) return;

        if (be.needsInitialSync) {
            be.needsInitialSync = false;
            be.updateLinkedState();
        }

        // Validate linked blocks periodically
        if (world.getGameTime() % VALIDATION_INTERVAL == 0) {
            be.validateLinkedBlocks();
        }
    }

    private void validateLinkedBlocks() {
        if (level == null) return;

        // Links in unloaded chunks are kept: looking them up would force-load the chunk.
        boolean removedAny = linkedBlocks.removeIf(blockPos -> {
            if (!level.hasChunkAt(blockPos)) return false;
            BlockEntity be = level.getBlockEntity(blockPos);
            return !(be instanceof StorageControllerBlockEntity || be instanceof IntakeBlockEntity);
        });

        if (removedAny) {
            updateLinkedState();
        }
    }

    // ========================================
    // CHEST CONFIG MANAGEMENT
    // ========================================

    /**
     * Get the chest configuration.
     * Priority: Controller's config > Local config > Create new
     */
    public ChestConfig getChestConfig() {
        BlockPos targetPos = getTargetPos();
        if (targetPos == null) return null;

        long currentTime = level != null ? level.getGameTime() : 0;

        // Return cached if still valid
        if (cachedConfig != null && currentTime < cacheValidUntil) {
            return cachedConfig;
        }

        // Try controller first
        for (BlockPos blockPos : linkedBlocks) {
            if (level == null) break;
            // A controller in an unloaded chunk is skipped (falls back to the
            // local copy) instead of being force-loaded.
            if (!level.hasChunkAt(blockPos)) continue;
            BlockEntity be = level.getBlockEntity(blockPos);
            if (be instanceof StorageControllerBlockEntity controller) {
                ChestConfig controllerConfig = controller.getChestConfig(targetPos);
                if (controllerConfig != null) {
                    cachedConfig = controllerConfig;
                    cacheValidUntil = currentTime + CACHE_DURATION;
                    return controllerConfig;
                }
            }
        }

        // Use local config
        if (localChestConfig == null) {
            localChestConfig = new ChestConfig(targetPos);
            localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;

            if (localChestConfig.filterMode == ChestConfig.FilterMode.OVERFLOW) {
                localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.LOWEST;
            }
        } else if (!localChestConfig.position.equals(targetPos)) {
            localChestConfig = new ChestConfig(targetPos);
            localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;

            if (localChestConfig.filterMode == ChestConfig.FilterMode.OVERFLOW) {
                localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.LOWEST;
            }
        }

        cachedConfig = localChestConfig;
        cacheValidUntil = currentTime + CACHE_DURATION;
        return localChestConfig;
    }

    public void invalidateConfigCache() {
        cachedConfig = null;
        cacheValidUntil = 0;
        // Filter edits and bulk edits reach the probe through here.
        snapshot.invalidate();
    }

    /** Drops the cached target position, inventory and contents snapshot. */
    public void invalidateTargetCache() {
        targetPosForState = null;
        cachedTargetPos = null;
        clearInventoryCache();
    }

    private void clearInventoryCache() {
        cachedInventory = null;
        cachedPrimary = null;
        cachedSecondary = null;
        cachedInventoryForState = null;
        snapshot.invalidate();
    }

    /**
     * Update the local chest configuration.
     * Also syncs to controller if linked.
     */
    public void setChestConfig(ChestConfig config) {
        if (config == null) return;

        this.localChestConfig = config.copy();

        if (this.localChestConfig.simplePrioritySelection == null) {
            this.localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
        }

        this.localChestConfig.updateHiddenPriority();

        invalidateConfigCache();
        RoutingIndexEpoch.bump();
        setChanged();

        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }

        syncConfigToController();
    }

    /**
     * Sync local config to linked controller
     */
    private void syncConfigToController() {
        if (level == null || level.isClientSide() || localChestConfig == null) return;

        for (BlockPos blockPos : linkedBlocks) {
            BlockEntity be = level.getBlockEntity(blockPos);
            if (be instanceof StorageControllerBlockEntity controller) {
                // Controller's updateChestConfig handles priority shifting
                controller.updateChestConfig(localChestConfig.position, localChestConfig);
                controller.setChanged();
            }
        }
    }

    // ========================================
    // LINKING MANAGEMENT
    // ========================================

    public boolean addLinkedBlock(BlockPos blockPos) {
        if (level == null) return false;

        BlockEntity newBE = level.getBlockEntity(blockPos);

        // If it's a controller, REMOVE ALL STALE CONTROLLER LINKS FIRST
        if (newBE instanceof StorageControllerBlockEntity) {
            // Remove any position that CLAIMS to be a controller but isn't valid anymore
            linkedBlocks.removeIf(existingPos -> {
                BlockEntity existingBE = level.getBlockEntity(existingPos);

                // Remove if:
                // 1. No block entity at that position
                // 2. Block entity is a controller (remove ALL old controllers)
                return existingBE == null || existingBE instanceof StorageControllerBlockEntity;
            });
        }

        // Now add the new controller
        if (!linkedBlocks.contains(blockPos)) {
            linkedBlocks.add(blockPos);
            linkedBlocksCopyDirty = true;
            setChanged();

            if (level != null) {
                BlockState state = level.getBlockState(worldPosition);
                level.sendBlockUpdated(worldPosition, state, state, 3);

                // Sync config
                if (newBE instanceof StorageControllerBlockEntity controller && localChestConfig != null) {
                    controller.updateChestConfig(localChestConfig.position, localChestConfig);
                }
            }
            updateLinkedState();
            return true;
        }

        return false;
    }

    public boolean removeLinkedBlock(BlockPos blockPos) {
        boolean removed = linkedBlocks.remove(blockPos);
        if (removed) {
            linkedBlocksCopyDirty = true;
            setChanged();

            if (level != null) {
                BlockState state = level.getBlockState(worldPosition);
                level.sendBlockUpdated(worldPosition, state, state, 3);
            }
            updateLinkedState();
        }
        return removed;
    }

    public List<BlockPos> getLinkedBlocks() {
        if (linkedBlocksCopyDirty || linkedBlocksCopy == null) {
            linkedBlocksCopy = new ArrayList<>(linkedBlocks);
            linkedBlocksCopyDirty = false;
        }
        return linkedBlocksCopy;
    }

    public void updateLinkedState() {
        if (level != null && !level.isClientSide()) {
            BlockState currentState = level.getBlockState(worldPosition);
            if (currentState.is(SmartSorter.PROBE_BLOCK)) {
                // Check if any linked block is a controller
                boolean isLinked = false;
                for (BlockPos blockPos : linkedBlocks) {
                    BlockEntity be = level.getBlockEntity(blockPos);
                    if (be instanceof StorageControllerBlockEntity) {
                        isLinked = true;
                        break;
                    }
                }

                BlockState newState = currentState.setValue(OutputProbeBlock.LINKED, isLinked);
                if (currentState != newState) {
                    level.setBlock(worldPosition, newState, 3);
                }
            }
        }
    }


    public void updateLocalConfig(ChestConfig config) {
        if (config == null) return;

        this.localChestConfig = config.copy();
        RoutingIndexEpoch.bump();
        setChanged();

        // Sync to world for client updates
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    // ========================================
    // LEGACY COMPATIBILITY
    // ========================================

    @Deprecated
    public void setLinkedController(BlockPos controllerPos) {
        addLinkedBlock(controllerPos);
    }

    @Deprecated
    public BlockPos getLinkedController() {
        if (level == null) return null;

        for (BlockPos blockPos : linkedBlocks) {
            BlockEntity be = level.getBlockEntity(blockPos);
            if (be instanceof StorageControllerBlockEntity) {
                return blockPos;
            }
        }
        return null;
    }

    // ========================================
    // STORAGE ACCESS
    // ========================================

    /** Cached per block-state object: a rotation swaps the state, so it's picked up for free. */
    public BlockPos getTargetPos() {
        if (level == null) return null;
        BlockState state = getBlockState();
        if (state != targetPosForState) {
            cachedTargetPos = worldPosition.relative(state.getValue(OutputProbeBlock.FACING));
            targetPosForState = state;
        }
        return cachedTargetPos;
    }

    public Storage<ItemVariant> getTargetStorage() {
        if (level == null) return null;

        Direction face = getBlockState().getValue(OutputProbeBlock.FACING);
        BlockPos targetPos = worldPosition.relative(face);

        Storage<ItemVariant> sidedStorage = ItemStorage.SIDED.find(level, targetPos, face.getOpposite());
        if (sidedStorage != null) return sidedStorage;

        Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, targetPos, null);
        if (storage != null) return storage;

        Container inv = getTargetInventory();
        if (inv != null) return ContainerStorage.of(inv, null);

        return null;
    }

    /**
     * The target chest's inventory, cached until a neighbor update
     * (OutputProbeBlock.neighborUpdate), a data reload, a block-state change,
     * or either backing block entity being removed/unloaded. "Nothing there"
     * is never cached.
     *
     * Never reads a block in an unloaded chunk (that forces a synchronous
     * chunk load): returns null so the probe is skipped this pass, and asks
     * the chunk keeper for that chunk if keepChunksLoaded is on.
     */
    public Container getTargetInventory() {
        if (level == null) return null;

        BlockState probeState = getBlockState();
        Container cached = cachedInventory;
        if (cached != null
                && cachedInventoryForState == probeState
                && !cachedPrimary.isRemoved()
                && (cachedSecondary == null || !cachedSecondary.isRemoved())) {
            return cached;
        }
        clearInventoryCache();

        BlockPos targetPos = getTargetPos();
        if (!level.hasChunkAt(targetPos)) {
            requestChunk(targetPos);
            return null;
        }

        BlockState targetState = level.getBlockState(targetPos);

        // Handle regular chests (single or double)
        if (targetState.getBlock() instanceof ChestBlock chestBlock) {
            BlockPos partnerPos = null;
            if (targetState.hasProperty(ChestBlock.TYPE) && targetState.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                partnerPos = targetPos.relative(ChestBlock.getConnectedDirection(targetState));
                if (!level.hasChunkAt(partnerPos)) {
                    // ChestBlock.getInventory would read the other half and force-load its chunk.
                    requestChunk(partnerPos);
                    return null;
                }
            }

            Container chestInv = ChestBlock.getContainer(chestBlock, targetState, level, targetPos, true);
            if (chestInv != null) {
                BlockEntity primary = level.getBlockEntity(targetPos);
                BlockEntity secondary = chestInv instanceof CompoundContainer && partnerPos != null
                        ? level.getBlockEntity(partnerPos) : null;
                // Only cache when every backing block entity was found, so the
                // isRemoved() checks above really cover the inventory.
                if (primary != null && (secondary != null || !(chestInv instanceof CompoundContainer))) {
                    storeInventory(chestInv, primary, secondary, probeState);
                }
                return chestInv;
            }
        }

        // Handle other inventories
        if (level.getBlockEntity(targetPos) instanceof Container inv) {
            storeInventory(inv, (BlockEntity) inv, null, probeState);
            return inv;
        }

        return null;
    }

    private void storeInventory(Container inv, BlockEntity primary, BlockEntity secondary, BlockState probeState) {
        cachedInventory = inv;
        cachedPrimary = primary;
        cachedSecondary = secondary;
        cachedInventoryForState = probeState;
    }

    private void requestChunk(BlockPos chunkOf) {
        if (SmartSorterConfig.keepChunksLoaded && level instanceof ServerLevel serverWorld) {
            ChunkKeeper.requestExtraChunk(serverWorld, worldPosition, chunkOf);
        }
    }

    /** Sum of the backing block entities' markDirty() counters; MIN_VALUE when not cached. */
    private long contentsVersion(Container inv) {
        if (inv != cachedInventory || cachedPrimary == null) {
            return Long.MIN_VALUE;
        }
        long version = ((ChangeCounter) cachedPrimary).smartsorter$getChangeCount();
        if (cachedSecondary != null) {
            version += ((ChangeCounter) cachedSecondary).smartsorter$getChangeCount();
        }
        return version;
    }

    /**
     * What the target chest holds, rebuilt only when the chest changed (its
     * markDirty() counter moved), the inventory was re-resolved, the config
     * cache was invalidated, or chestCacheMaxAgeTicks passed.
     */
    private ChestSnapshot currentSnapshot(Container inv) {
        long now = level != null ? level.getGameTime() : 0L;
        if (!snapshot.isCurrent(inv, contentsVersion(inv), now, SmartSorterConfig.chestCacheMaxAgeTicks)) {
            snapshot.rebuild(inv, now);
            // Read after the scan: generating loot can mark the chest dirty.
            snapshot.setVersion(contentsVersion(inv));
        }
        return snapshot;
    }

    /** Whether the target chest holds any item (answered from the snapshot). */
    public boolean targetHasItems() {
        Container inv = getTargetInventory();
        return inv != null && !currentSnapshot(inv).isEmpty();
    }

    // ========================================
    // ITEM ACCEPTANCE LOGIC
    // ========================================

    /**
     * Whether this probe's chest takes {@code incoming}. Answered from the
     * contents snapshot with set lookups - no slot scans, no allocations.
     *
     * A chest counts as full for an item only when no slot is empty AND no
     * slot holds that exact item (components included) below its max stack,
     * so a chest of single placeholder items keeps accepting those items.
     */
    public boolean accepts(ItemVariant incoming) {
        if (level == null) return false;

        Container inv = getTargetInventory();
        if (inv == null) return false;

        ChestSnapshot contents = currentSnapshot(inv);
        if (!contents.hasRoomFor(incoming)) return false;

        // Now check filter rules
        ChestConfig chestConfig = getChestConfig();
        if (chestConfig != null) {
            // Whitelist overlay on CUSTOM chests: exactly the listed items
            if (chestConfig.whitelistEnabled) {
                return chestConfig.getWhitelist().contains(incoming.getItem());
            }

            switch (chestConfig.filterMode) {
                case NONE:
                case PRIORITY:
                    return true;

                case CATEGORY:
                case CATEGORY_AND_PRIORITY:
                case OVERFLOW: {
                    Category itemCategory = CategoryManager.getInstance().categorize(incoming.getItem());
                    return itemCategory.equals(chestConfig.filterCategory) ||
                            chestConfig.filterCategory.equals(Category.ALL);
                }

                case BLACKLIST: {
                    Category itemCategory = CategoryManager.getInstance().categorize(incoming.getItem());
                    return !itemCategory.equals(chestConfig.filterCategory);
                }

                case CUSTOM:
                    // Chest must already hold a match (an empty chest accepts
                    // nothing); room was checked above.
                    return chestConfig.strictNBTMatch
                            ? contents.containsVariant(incoming)
                            : contents.containsItem(incoming.getItem());

                default:
                    return false;
            }
        }

        return false;
    }

    public boolean contains(ItemVariant variant) {
        if (level == null) return false;
        Container inv = getTargetInventory();
        return inv != null && currentSnapshot(inv).containsVariant(variant);
    }

    public boolean hasSpace(ItemVariant variant, int amount) {
        Container inv = getTargetInventory();
        return inv != null && currentSnapshot(inv).hasRoomFor(variant);
    }

    // ========================================
    // STATUS
    // ========================================

    /** What this probe currently sends to its chest, e.g. "General Storage" or "Custom (whitelist: 3 items)". */
    public String getFilterSummary() {
        ChestConfig config = getChestConfig();
        if (config == null) return "Not facing an inventory";
        if (config.filterMode == ChestConfig.FilterMode.CUSTOM && config.whitelistEnabled) {
            int n = config.getWhitelist().size();
            return "Custom (whitelist: " + n + (n == 1 ? " item)" : " items)");
        }
        return config.filterMode.getDisplayName();
    }

    // ========================================
    // SCREEN HANDLER FACTORY
    // ========================================

    @Override
    public Component getDisplayName() {
        return Component.literal("Output Probe");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
        return new OutputProbeScreenHandler(syncId, playerInventory, this);
    }

    @Override
    public ProbeData getScreenOpeningData(ServerPlayer player) {
        BlockPos targetPos = getTargetPos();
        ChestConfig config = getChestConfig();

        return new ProbeData(
                targetPos != null ? targetPos : BlockPos.ZERO,
                config
        );
    }

    // ========================================
    // CLEANUP
    // ========================================

    public void onRemoved(Level world) {
        invalidateTargetCache();
        RoutingIndexEpoch.bump();
        if (world instanceof ServerLevel serverWorld) {
            ChunkKeeper.unregister(serverWorld, worldPosition);
        }
        if (world == null || world.isClientSide()) return;

        BlockPos targetPos = getTargetPos();

        // Make a copy to avoid concurrent modification
        List<BlockPos> linkedBlocksCopy = new ArrayList<>(linkedBlocks);

        for (BlockPos blockPos : linkedBlocksCopy) {
            BlockEntity be = world.getBlockEntity(blockPos);

            if (be instanceof StorageControllerBlockEntity controller) {
                // Remove this probe from controller
                controller.removeProbe(worldPosition);

                // Check if we should remove the chest config
                if (targetPos != null) {
                    boolean stillHasProbe = false;

                    // Check remaining probes in the controller
                    List<BlockPos> remainingProbes = controller.getLinkedProbes();

                    for (BlockPos otherProbePos : remainingProbes) {
                        // Skip if it's this probe (shouldn't happen, but safety check)
                        if (otherProbePos.equals(worldPosition)) {
                            continue;
                        }

                        BlockEntity otherBe = world.getBlockEntity(otherProbePos);
                        if (otherBe instanceof OutputProbeBlockEntity otherProbe) {
                            BlockPos otherTarget = otherProbe.getTargetPos();

                            if (targetPos.equals(otherTarget)) {
                                stillHasProbe = true;
                                break;
                            }
                        }
                    }

                    if (!stillHasProbe) {
                        controller.removeChestConfig(targetPos);
                    }
                }
            }
        }

        linkedBlocks.clear();
        localChestConfig = null;
    }

    public void clearController() {
        linkedBlocks.removeIf(blockPos -> {
            return true; // Remove everything when clearing
        });
        linkedBlocksCopyDirty = true;

        setChanged();

        if (level != null) {
            BlockState state = level.getBlockState(worldPosition);
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    public void removeController(BlockPos controllerPos) {
        if (linkedBlocks.remove(controllerPos)) {
            linkedBlocksCopyDirty = true;
            setChanged();

            if (level != null) {
                BlockState state = level.getBlockState(worldPosition);
                level.sendBlockUpdated(worldPosition, state, state, 3);
            }
            updateLinkedState();
        }
    }

    // ========================================
    // NBT SERIALIZATION
    // ========================================

    private void writeProbeData(ValueOutput view) {
        view.putInt("linked_blocks_count", linkedBlocks.size());
        for (int i = 0; i < linkedBlocks.size(); i++) {
            view.putLong("linked_block_" + i, linkedBlocks.get(i).asLong());
        }

        // Save local chest config
        if (localChestConfig != null) {
            view.putBoolean("has_local_config", true);
            view.putLong("local_chest_pos", localChestConfig.position.asLong());
            view.putString("local_chest_name", localChestConfig.customName != null ? localChestConfig.customName : "");
            view.putString("local_chest_category", localChestConfig.filterCategory.asString());
            view.putInt("local_chest_priority", localChestConfig.priority);
            view.putString("local_chest_mode", localChestConfig.filterMode.name());
            view.putBoolean("local_chest_nbt", localChestConfig.strictNBTMatch);
            view.putBoolean("local_chest_frame", localChestConfig.autoItemFrame);

            if (localChestConfig.simplePrioritySelection != null) {
                view.putString("local_simple_priority", localChestConfig.simplePrioritySelection.name());
            }
        } else {
            view.putBoolean("has_local_config", false);
        }
    }


    private void writeProbeData(CompoundTag nbt) {
        nbt.putInt("linked_blocks_count", linkedBlocks.size());
        for (int i = 0; i < linkedBlocks.size(); i++) {
            nbt.putLong("linked_block_" + i, linkedBlocks.get(i).asLong());
        }

        // Save local chest config
        if (localChestConfig != null) {
            nbt.putBoolean("has_local_config", true);
            nbt.putLong("local_chest_pos", localChestConfig.position.asLong());
            nbt.putString("local_chest_name", localChestConfig.customName != null ? localChestConfig.customName : "");
            nbt.putString("local_chest_category", localChestConfig.filterCategory.asString());
            nbt.putInt("local_chest_priority", localChestConfig.priority);
            nbt.putString("local_chest_mode", localChestConfig.filterMode.name());
            nbt.putBoolean("local_chest_nbt", localChestConfig.strictNBTMatch);
            nbt.putBoolean("local_chest_frame", localChestConfig.autoItemFrame);
            if (localChestConfig.simplePrioritySelection != null) {
                nbt.putString("local_simple_priority", localChestConfig.simplePrioritySelection.name());
            }
        } else {
            nbt.putBoolean("has_local_config", false);
        }
    }

    private void readProbeData(ValueInput view) {
        linkedBlocks.clear();
        int count = view.getIntOr("linked_blocks_count", 0);
        for (int i = 0; i < count; i++) {
            view.getLong("linked_block_" + i).ifPresent(posLong -> {
                linkedBlocks.add(BlockPos.of(posLong));
            });
        }

        // Load local chest config
        if (view.getBooleanOr("has_local_config", false)) {
            view.getLong("local_chest_pos").ifPresent(posLong -> {
                BlockPos chestPos = BlockPos.of(posLong);
                String name = view.getStringOr("local_chest_name", "");
                String categoryStr = view.getStringOr("local_chest_category", "smartsorter:all");
                int priority = view.getIntOr("local_chest_priority", 1);
                String modeStr = view.getStringOr("local_chest_mode", "NONE");
                boolean strictNBT = view.getBooleanOr("local_chest_nbt", false);
                boolean autoFrame = view.getBooleanOr("local_chest_frame", false);

                Category category = CategoryManager.getInstance().getCategory(categoryStr);
                ChestConfig.FilterMode filterMode = ChestConfig.FilterMode.valueOf(modeStr);

                localChestConfig = new ChestConfig(chestPos, name, category, priority, filterMode, autoFrame);
                localChestConfig.strictNBTMatch = strictNBT;

                String simplePriorityStr = view.getStringOr("local_simple_priority", null);
                if (simplePriorityStr != null && !simplePriorityStr.isEmpty()) {
                    try {
                        localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.valueOf(simplePriorityStr);
                    } catch (Exception e) {
                        localChestConfig.simplePrioritySelection = null;  // Changed from MEDIUM to null
                    }
                } else {
                    // Keep as null if not saved (indicates manual priority)
                    localChestConfig.simplePrioritySelection = null;
                }
            });
        }
    }

    @Override
    public void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);
        writeProbeData(view);
    }

    @Override
    public void loadAdditional(ValueInput view) {
        super.loadAdditional(view);
        readProbeData(view);
        // Placed or chunk loaded: re-resolve the target and rebuild routing indexes.
        invalidateTargetCache();
        RoutingIndexEpoch.bump();
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registryLookup) {
        return saveWithoutMetadata(registryLookup);
    }

    /** Runs before the block entity is removed, for every kind of removal. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        onRemoved(level);
    }
}
