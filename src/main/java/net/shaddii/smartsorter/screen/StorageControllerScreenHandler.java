package net.shaddii.smartsorter.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.network.*;
import net.shaddii.smartsorter.util.*;

import java.util.*;

/**
 * HEAVILY OPTIMIZED FOR 1000+ CHESTS
 * - Removed excessive updateNetworkCache() calls
 * - Delta updates instead of full syncs
 * - Batched operations
 * - Cached expensive calculations
 */
public class StorageControllerScreenHandler extends AbstractContainerMenu {
    // ========================================
    // CONSTANTS - OPTIMIZED
    // ========================================

    private static final int PLAYER_INV_Y = 120;
    private static final int HOTBAR_Y = 178;
    private static final int PLAYER_INVENTORY_SIZE = 27;
    private static final int HOTBAR_SIZE = 9;
    private static final int TOTAL_SLOTS = PLAYER_INVENTORY_SIZE + HOTBAR_SIZE;

    // OPTIMIZATION: Increased batch delay to reduce sync frequency
    private static final long SYNC_BATCH_DELAY_MS = 200; // Changed from 50ms to 200ms
    private static final int CONFIG_BATCH_SIZE = 10; // Changed from 5 to 10

    // ========================================
    // FIELDS
    // ========================================

    public final StorageControllerBlockEntity controller;
    public final BlockPos controllerPos;

    // Client-side cached state
    private Map<ItemVariant, Long> clientNetworkItems = Collections.emptyMap();
    private Map<BlockPos, ProcessProbeConfig> clientProbeConfigs = Collections.emptyMap();
    private Map<BlockPos, ChestConfig> clientChestConfigs = Collections.emptyMap();
    private int clientStoredXp = 0;

    // UI state
    private SortMode sortMode = SortMode.NAME;
    private Category filterCategory = Category.ALL;

    // Sync state
    private boolean needsFullSync = true;
    private boolean pendingSync = false;
    private long lastSyncTime = 0;
    private boolean configsSynced = false;

    // Cache for expensive operations
    private final Map<BlockPos, Integer> chestFullnessCache = new HashMap<>();
    private final Map<BlockPos, List<ItemStack>> chestPreviewCache = new HashMap<>();
    private long lastCacheClear = 0;
    private static final long CACHE_CLEAR_INTERVAL = 5000;

    // ========================================
    // CONSTRUCTORS
    // ========================================

    public StorageControllerScreenHandler(int syncId, Inventory inv, StorageControllerBlockEntity controller) {
        super(SmartSorter.STORAGE_CONTROLLER_SCREEN_HANDLER, syncId);
        this.controller = controller;
        this.controllerPos = controller != null ? controller.getBlockPos() : null;

        addPlayerInventory(inv);
        addPlayerHotbar(inv);

        if (inv.player instanceof ServerPlayer sp) {
            // OPTIMIZATION: Only update cache once on GUI open
            if (controller != null) {
                controller.forceUpdateCache();
            }

            // Send categories ONCE on open
            CategoryManager categoryManager = CategoryManager.getInstance();
            ServerPlayNetworking.send(sp, new CategorySyncPayload(categoryManager.getCategoryData()));

            // Don't send configs yet - client will request after screen is ready
        }
    }

    public StorageControllerScreenHandler(int syncId, Inventory inv) {
        super(SmartSorter.STORAGE_CONTROLLER_SCREEN_HANDLER, syncId);
        this.controller = null;
        this.controllerPos = null;

        addPlayerInventory(inv);
        addPlayerHotbar(inv);

        requestSync();
    }

    // ========================================
    // INVENTORY SETUP
    // ========================================

    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, PLAYER_INV_Y + row * 18));
            }
        }
    }

    private void addPlayerHotbar(Inventory inv) {
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(inv, col, 8 + col * 18, HOTBAR_Y));
        }
    }

    // ========================================
    // SCREEN HANDLER OVERRIDES - OPTIMIZED
    // ========================================

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= this.slots.size()) {
            return ItemStack.EMPTY;
        }

        Slot slot = this.slots.get(slotIndex);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack stackInSlot = slot.getItem();
        ItemStack original = stackInSlot.copy();

        if (slotIndex >= 0 && slotIndex < TOTAL_SLOTS) {
            if (controller == null) {
                return ItemStack.EMPTY;
            }

            ItemStack remaining = controller.insertItem(stackInSlot.copy()).remainder();
            int inserted = stackInSlot.getCount() - (remaining.isEmpty() ? 0 : remaining.getCount());

            if (inserted > 0) {
                slot.setByPlayer(remaining);
                slot.setChanged();

                // FIXED: Immediate sync for shift-click
                if (player instanceof ServerPlayer sp) {
                    requestImmediateSync(sp);
                }

                return original;
            }
        }

        return ItemStack.EMPTY;
    }

    @Override
    public void clicked(int index, int button, ContainerInput type, Player player) {
        if (index < 0 || index >= this.slots.size()) {
            super.clicked(index, button, type, player);
            return;
        }

        super.clicked(index, button, type, player);

        // OPTIMIZATION: Removed sendNetworkUpdate() - not needed for player inventory operations
        // The controller's tick() will handle cache updates automatically
    }

    @Override
    public boolean stillValid(Player player) {
        return controller == null || controller.stillValid(player);
    }

    // ========================================
    // NETWORK SYNCING - HEAVILY OPTIMIZED
    // ========================================

    /**
     * CRITICAL OPTIMIZATION: Removed controller.updateNetworkCache() call
     * The controller's tick() already handles cache updates
     */
    public void sendNetworkUpdate(ServerPlayer player) {
        if (controller == null) return;

        this.needsFullSync = true;

        Map<ItemVariant, Long> items = controller.getNetworkItems();
        int xp = controller.getStoredExperience();

        // OPTIMIZATION: Only send configs on first sync or when explicitly requested
        if (!configsSynced) {
            Map<BlockPos, ProcessProbeConfig> configs = controller.getProcessProbeConfigs();
            Map<BlockPos, ChestConfig> chestConfigs = controller.getChestConfigs();

            // Send items with actual probe configs (not empty map)
            StorageControllerSyncPacket.send(player, items, xp, configs);

            // Send chest configs separately
            if (!chestConfigs.isEmpty()) {
                sendConfigsInBatches(player, chestConfigs, ChestConfigBatchPayload::new);
            }

            configsSynced = true;
        } else {
            // Subsequent updates: only send items and XP
            StorageControllerSyncPacket.send(player, items, xp, new HashMap<>());
        }

        this.needsFullSync = false;
    }

    /**
     * OPTIMIZED: Delta updates only
     */
    public void sendNetworkUpdate(ServerPlayer player, Map<ItemVariant, Long> changes) {
        if (controller == null) return;

        if (this.needsFullSync) {
            sendNetworkUpdate(player);
        } else if (!changes.isEmpty()) {
            ServerPlayNetworking.send(player, new StorageDeltaSyncPayload(changes));
        }
    }

    public void markConfigsDirty() {
        configsSynced = false;
    }

    /**
     * FIXED: Immediate sync for critical operations
     */
    public void requestImmediateSync(ServerPlayer player) {
        if (controller == null || player == null) return;
        sendNetworkUpdate(player);
    }

    private <T, P extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> void sendConfigsInBatches(
            ServerPlayer player,
            Map<BlockPos, T> configs,
            java.util.function.Function<Map<BlockPos, T>, P> payloadFactory) {

        Map<BlockPos, T> batch = new HashMap<>();
        int count = 0;

        for (Map.Entry<BlockPos, T> entry : configs.entrySet()) {
            batch.put(entry.getKey(), entry.getValue());
            count++;

            if (count >= CONFIG_BATCH_SIZE) {
                ServerPlayNetworking.send(player, payloadFactory.apply(new HashMap<>(batch)));
                batch.clear();
                count = 0;
            }
        }

        if (!batch.isEmpty()) {
            ServerPlayNetworking.send(player, payloadFactory.apply(new HashMap<>(batch)));
        }
    }

    public void requestSync() {
        if (controller == null) {
            ClientPlayNetworking.send(new SyncRequestPayload());
        }
    }

    // ========================================
    // CLIENT STATE UPDATES
    // ========================================

    public void updateNetworkItems(Map<ItemVariant, Long> items) {
        this.clientNetworkItems = Collections.unmodifiableMap(new HashMap<>(items));
    }

    public void updateProbeConfigs(Map<BlockPos, ProcessProbeConfig> configs) {
        Map<BlockPos, ProcessProbeConfig> updated = new HashMap<>(clientProbeConfigs);
        updated.putAll(configs);
        this.clientProbeConfigs = Collections.unmodifiableMap(updated);
    }

    public void clearProbeConfigs() {
        this.clientProbeConfigs = Collections.emptyMap();
    }

    public void updateChestConfigs(Map<BlockPos, ChestConfig> configs) {
        Map<BlockPos, ChestConfig> updated = new HashMap<>(clientChestConfigs);
        updated.putAll(configs);
        this.clientChestConfigs = Collections.unmodifiableMap(updated);
    }

    public void clearChestConfigs() {
        this.clientChestConfigs = Collections.emptyMap();
    }

    public void updateStoredXp(int xp) {
        this.clientStoredXp = xp;
    }

    public void updateProbeStats(BlockPos position, int itemsProcessed) {
        if (controller != null) {
            ProcessProbeConfig config = controller.getProbeConfig(position);
            if (config != null) {
                config.itemsProcessed = itemsProcessed;
            }
        } else {
            Map<BlockPos, ProcessProbeConfig> mutable = new HashMap<>(clientProbeConfigs);
            ProcessProbeConfig config = mutable.get(position);
            if (config != null) {
                config.itemsProcessed = itemsProcessed;
                mutable.put(position, config);
                this.clientProbeConfigs = Collections.unmodifiableMap(mutable);
            }
        }
    }

    public void refreshChestFullness() {
        if (controller == null) return;

        long now = System.currentTimeMillis();
        if (now - lastCacheClear > CACHE_CLEAR_INTERVAL) {
            chestFullnessCache.clear();
            chestPreviewCache.clear();
            lastCacheClear = now;
        }

        Map<BlockPos, ChestConfig> configs = controller.getChestConfigs();
        Map<BlockPos, ChestConfig> mutable = new HashMap<>(configs);

        for (ChestConfig config : mutable.values()) {
            config.cachedFullness = chestFullnessCache.computeIfAbsent(
                    config.position,
                    this::calculateChestFullness
            );
            config.previewItems = chestPreviewCache.computeIfAbsent(
                    config.position,
                    this::getChestPreviewItems
            );
        }

        clientChestConfigs = Collections.unmodifiableMap(mutable);
    }

    // ========================================
    // GETTERS
    // ========================================

    public Map<ItemVariant, Long> getNetworkItems() {
        if (controller != null) return controller.getNetworkItems();
        return clientNetworkItems;
    }

    public Map<BlockPos, ProcessProbeConfig> getProcessProbeConfigs() {
        if (controller != null) return controller.getProcessProbeConfigs();
        return clientProbeConfigs;
    }

    public Map<BlockPos, ChestConfig> getChestConfigs() {
        if (controller != null) return controller.getChestConfigs();
        return clientChestConfigs;
    }

    public int getStoredExperience() {
        if (controller != null) return controller.getStoredExperience();
        return clientStoredXp;
    }

    public SortMode getSortMode() {
        return sortMode;
    }

    public Category getFilterCategory() {
        return filterCategory;
    }

    // ========================================
    // SETTERS - OPTIMIZED
    // ========================================

    public void setSortMode(SortMode mode) {
        this.sortMode = mode;
        // OPTIMIZATION: Removed immediate sync - UI state doesn't need network update
    }

    public void setFilterCategory(Category category) {
        this.filterCategory = category;
        // OPTIMIZATION: Removed immediate sync - UI state doesn't need network update
    }

    // ========================================
    // ITEM OPERATIONS - OPTIMIZED
    // ========================================

    public void requestExtraction(ItemVariant variant, int amount, boolean toInventory) {
        if (controller != null) {
            Player player = getPlayerFromSlots();
            if (player != null) {
                extractItem(variant, amount, toInventory, player);

                // FIXED: Immediate sync for extractions
                if (player instanceof ServerPlayer sp) {
                    player.containerMenu.setCarried(getCarried());
                }
            }
        } else {
            ClientPlayNetworking.send(new ExtractionRequestPayload(variant, amount, toInventory));
        }
    }

    public void requestDeposit(ItemStack stack, int amount) {
        if (controller != null) {
            Player player = getPlayerFromSlots();
            if (player != null) {
                depositItem(stack, amount, player);

                // FIXED: Immediate sync for deposits
                if (player instanceof ServerPlayer sp) {
                    player.inventoryMenu.setCarried(getCarried());
                }
            }
        } else {
            ClientPlayNetworking.send(new DepositRequestPayload(ItemVariant.of(stack), amount));
        }
    }

    public void extractItem(ItemVariant variant, int amount, boolean toInventory, Player player) {
        if (controller == null || player == null) return;

        // Validate against actual network contents
        Map<ItemVariant, Long> networkItems = controller.getNetworkItems();
        long available = networkItems.getOrDefault(variant, 0L);
        int safeAmount = Math.min(amount, (int)Math.min(available, 64));

        if (safeAmount <= 0) return;

        ItemStack extracted = controller.extractItem(variant, safeAmount);
        if (extracted.isEmpty()) return;

        // Force immediate cache update
        controller.forceUpdateCache();

        if (toInventory) {
            player.getInventory().add(extracted);
            if (!extracted.isEmpty()) {
                player.drop(extracted, false);
            }
        } else {
            ItemStack cursor = getCarried();

            if (cursor.isEmpty()) {
                setCarried(extracted);
            } else if (ItemStack.isSameItemSameComponents(cursor, extracted)) {
                int maxStack = Math.min(cursor.getMaxStackSize(), 64);
                int canAdd = maxStack - cursor.getCount();
                if (canAdd > 0) {
                    int toAdd = Math.min(canAdd, extracted.getCount());
                    cursor.grow(toAdd);
                    extracted.shrink(toAdd);
                    setCarried(cursor);
                }
                if (!extracted.isEmpty()) {
                    ItemStack remaining = controller.insertItem(extracted).remainder();
                    if (!remaining.isEmpty()) {
                        player.drop(remaining, false);
                    }
                }
            } else {
                if (!player.getInventory().add(extracted)) {
                    player.drop(extracted, false);
                }
            }
        }

        // Sync cursor to client
        if (player instanceof ServerPlayer sp) {
            player.containerMenu.setCarried(getCarried());
            sendNetworkUpdate(sp);
        }
    }

    public void depositItem(ItemStack stack, int amount, Player player) {
        if (controller == null || player == null || stack.isEmpty()) return;

        ItemStack cursor = getCarried();
        if (cursor.isEmpty()) return;

        ItemStack toDeposit = cursor.copy();
        toDeposit.setCount(Math.min(amount, cursor.getCount()));

        ItemStack remaining = controller.insertItem(toDeposit).remainder();
        int deposited = toDeposit.getCount() - (remaining.isEmpty() ? 0 : remaining.getCount());

        if (deposited > 0) {
            cursor.shrink(deposited);
            setCarried(cursor.isEmpty() ? ItemStack.EMPTY : cursor);

            if (player instanceof ServerPlayer sp) {
                player.inventoryMenu.setCarried(getCarried());
                // controller.forceUpdateCache();
                sendNetworkUpdate(sp);
            }
        }
    }

    // ========================================
    // CONFIG MANAGEMENT
    // ========================================

    private int calculateChestFullness(BlockPos chestPos) {
        if (controller == null || controller.getLevel() == null) return -1;
        return controller.calculateChestFullness(chestPos);
    }

    private List<ItemStack> getChestPreviewItems(BlockPos chestPos) {
        if (controller == null) return new ArrayList<>();

        for (BlockPos probePos : controller.getLinkedProbes()) {
            BlockEntity be = controller.getLevel().getBlockEntity(probePos);
            if (be instanceof OutputProbeBlockEntity probe) {
                if (chestPos.equals(probe.getTargetPos())) {
                    Container inv = probe.getTargetInventory();
                    if (inv == null) break;

                    List<ItemStack> items = new ArrayList<>();
                    for (int i = 0; i < inv.getContainerSize() && items.size() < 8; i++) {
                        ItemStack stack = inv.getItem(i);
                        if (!stack.isEmpty()) {
                            items.add(stack.copy());
                        }
                    }
                    return items;
                }
            }
        }
        return new ArrayList<>();
    }

    // ========================================
    // PRIORITY MANAGEMENT
    // ========================================

    public void handleChestAddition(BlockPos chestPos, ChestConfig config) {
        if (controller == null) return;

        chestFullnessCache.remove(chestPos);
        chestPreviewCache.remove(chestPos);

        controller.updateChestConfig(chestPos, config);

        Player player = getPlayerFromSlots();
        if (player instanceof ServerPlayer sp) {
            markConfigsDirty();
            requestImmediateSync(sp); // Batched instead of immediate
        }
    }

    public void handleChestRemoval(BlockPos chestPos) {
        if (controller == null) return;

        chestFullnessCache.remove(chestPos);
        chestPreviewCache.remove(chestPos);

        controller.removeChestConfig(chestPos);

        Player player = getPlayerFromSlots();
        if (player instanceof ServerPlayer sp) {
            markConfigsDirty();
            requestImmediateSync(sp); // Batched instead of immediate
        }
    }

    public void handleSimplePrioritySelection(BlockPos chestPos, ChestConfig.SimplePriority simplePriority) {
        if (controller == null) return;

        ChestConfig config = controller.getChestConfig(chestPos);
        if (config == null) return;

        config.simplePrioritySelection = simplePriority;
        controller.updateChestConfig(chestPos, config);

        Player player = getPlayerFromSlots();
        if (player instanceof ServerPlayer sp) {
            requestImmediateSync(sp); // Batched instead of immediate
        }
    }

    public void updateChestConfig(BlockPos position, ChestConfig config) {
        chestFullnessCache.remove(position);
        chestPreviewCache.remove(position);

        if (controller != null) {
            controller.updateChestConfig(position, config);

            Map<BlockPos, ChestConfig> updatedConfigs = controller.getChestConfigs();
            updateChestConfigs(updatedConfigs);

            Player player = getPlayerFromSlots();
            if (player instanceof ServerPlayer sp) {
                markConfigsDirty();
                sendConfigsInBatches(sp, updatedConfigs, ChestConfigBatchPayload::new);
            }
        } else {
            Map<BlockPos, ChestConfig> mutable = new HashMap<>(clientChestConfigs);
            mutable.put(position, config);
            clientChestConfigs = Collections.unmodifiableMap(mutable);
        }
    }

    public void applyPriorityUpdatesFromServer(Map<BlockPos, ChestPriorityBatchPayload.PriorityUpdate> updates) {
        Map<BlockPos, ChestConfig> mutable = new HashMap<>(clientChestConfigs);

        for (Map.Entry<BlockPos, ChestPriorityBatchPayload.PriorityUpdate> entry : updates.entrySet()) {
            ChestConfig config = mutable.get(entry.getKey());
            if (config != null) {
                config.priority = entry.getValue().priority();
                config.simplePrioritySelection = entry.getValue().simplePriority();
                config.hiddenPriority = entry.getValue().hiddenPriority();
                config.cachedFullness = entry.getValue().cachedFullness();
                mutable.put(entry.getKey(), config);
            }
        }

        clientChestConfigs = Collections.unmodifiableMap(mutable);
    }

    // ========================================
    // HELPER METHODS
    // ========================================

    private Inventory getFirstPlayerInventory() {
        for (Slot s : this.slots) {
            if (s.container instanceof Inventory pi) return pi;
        }
        return null;
    }

    private Player getPlayerFromSlots() {
        Inventory pi = getFirstPlayerInventory();
        return pi != null ? pi.player : null;
    }

    // ========================================
    // NETWORK PAYLOAD DEFINITIONS
    // ========================================

    public record SyncRequestPayload() implements CustomPacketPayload {
        public static final Type<SyncRequestPayload> ID =
                new Type<>(Identifier.fromNamespaceAndPath(SmartSorter.MOD_ID, "sync_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncRequestPayload> CODEC =
                StreamCodec.ofMember((b, p) -> {}, b -> new SyncRequestPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record DepositRequestPayload(ItemVariant variant, int amount) implements CustomPacketPayload {
        public static final Type<DepositRequestPayload> ID =
                new Type<>(Identifier.fromNamespaceAndPath(SmartSorter.MOD_ID, "deposit_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DepositRequestPayload> CODEC =
                StreamCodec.ofMember((value, buf) -> write(buf, value), buf -> read(buf));

        public static void write(RegistryFriendlyByteBuf buf, DepositRequestPayload payload) {
            ItemStack.STREAM_CODEC.encode(buf, payload.variant().toStack(1));
            buf.writeVarInt(payload.amount());
        }

        public static DepositRequestPayload read(RegistryFriendlyByteBuf buf) {
            ItemStack s = ItemStack.STREAM_CODEC.decode(buf);
            return new DepositRequestPayload(ItemVariant.of(s), buf.readVarInt());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record ExtractionRequestPayload(ItemVariant variant, int amount, boolean toInventory)
            implements CustomPacketPayload {
        public static final Type<ExtractionRequestPayload> ID =
                new Type<>(Identifier.fromNamespaceAndPath(SmartSorter.MOD_ID, "extraction_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ExtractionRequestPayload> CODEC =
                StreamCodec.ofMember((value, buf) -> write(buf, value), buf -> read(buf));

        public static void write(RegistryFriendlyByteBuf buf, ExtractionRequestPayload payload) {
            ItemStack.STREAM_CODEC.encode(buf, payload.variant().toStack(1));
            buf.writeVarInt(payload.amount());
            buf.writeBoolean(payload.toInventory());
        }

        public static ExtractionRequestPayload read(RegistryFriendlyByteBuf buf) {
            ItemStack s = ItemStack.STREAM_CODEC.decode(buf);
            ItemVariant v = ItemVariant.of(s);
            int amt = buf.readVarInt();
            boolean inv = buf.readBoolean();
            return new ExtractionRequestPayload(v, amt, inv);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }
}