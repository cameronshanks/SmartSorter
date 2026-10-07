package net.shaddii.smartsorter.blockentity.controller;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.util.Category;
import net.shaddii.smartsorter.util.CategoryManager;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.util.RouteCandidates;
import net.shaddii.smartsorter.util.RoutingIndexEpoch;

import java.util.*;

/**
 * OPTIMIZED FOR 1000+ CHESTS
 * - Smart probe filtering (skip obviously incompatible probes)
 * - Single-pass insertion
 * - Cached probe lookups
 * - Early exit strategies
 */
public class ItemRoutingService {
    private static final InsertionResult EMPTY_SUCCESS =
            new InsertionResult(ItemStack.EMPTY, false, null, null, 0);

    private final ProbeRegistry probeRegistry;
    // Per-item routing index (see getCandidates)
    private final Map<Item, RouteCandidates> routeIndex = new HashMap<>();
    private List<BlockPos> indexSource;
    private long indexEpoch;

    public ItemRoutingService(ProbeRegistry probeRegistry) {
        this.probeRegistry = probeRegistry;
    }

    /**
     * Routes {@code stack} into the network: pass 1 tries every filtered
     * destination, pass 2 general and overflow storage, both in the
     * controller's priority order, continuing until the stack is fully
     * inserted (a nearly-full chest takes part and the rest moves on).
     *
     * Instead of calling accepts() on every linked probe, each pass walks a
     * per-Item list of candidate probes (see getCandidates).
     */
    public InsertionResult insertItem(Level world, ItemStack stack) {
        if (world == null || stack.isEmpty()) {
            return new InsertionResult(stack, false, null, null, 0);
        }

        ItemVariant variant = ItemVariant.of(stack);
        ItemStack remaining = stack.copy();

        BlockPos insertedInto = null;
        String insertedIntoName = null;

        // PRE-FILTER: Categorize item once
        Category itemCategory = CategoryManager.getInstance().categorize(stack.getItem());

        List<BlockPos> sortedProbes = probeRegistry.getSortedProbes(world);
        RouteCandidates candidates = getCandidates(world, sortedProbes, stack.getItem(), itemCategory);
        int count = candidates.positions.length;

        // Phase 1: High-priority & filtered destinations
        for (int i = 0; i < count && !remaining.isEmpty(); i++) {
            OutputProbeBlockEntity probe = resolve(world, candidates, i);
            if (probe == null) continue;

            ChestConfig config = probe.getChestConfig();
            if (config == null) continue;

            // Skip overflow/none in first pass
            if (config.filterMode == ChestConfig.FilterMode.NONE ||
                    config.filterMode == ChestConfig.FilterMode.OVERFLOW) {
                continue;
            }

            if (quickCategoryCheck(config, itemCategory) && probe.accepts(variant)) {
                int inserted = insertIntoInventorySinglePass(probe, remaining);

                if (inserted > 0 && insertedInto == null) {
                    insertedInto = probe.getTargetPos();
                    insertedIntoName = getChestDisplayName(config);
                }

                remaining.shrink(inserted);
            }
        }

        if (remaining.isEmpty()) {
            return new InsertionResult(ItemStack.EMPTY, false, insertedInto, insertedIntoName, stack.getCount());
        }

        // Phase 2: General & overflow destinations
        boolean didOverflow = false;

        for (int i = 0; i < count && !remaining.isEmpty(); i++) {
            OutputProbeBlockEntity probe = resolve(world, candidates, i);
            if (probe == null) continue;

            ChestConfig config = probe.getChestConfig();
            if (config == null) continue;

            // Only process overflow/none in second pass
            if (config.filterMode != ChestConfig.FilterMode.NONE &&
                    config.filterMode != ChestConfig.FilterMode.OVERFLOW) {
                continue;
            }

            if (probe.accepts(variant)) {
                int inserted = insertIntoInventorySinglePass(probe, remaining);

                if (inserted > 0) {
                    if (insertedInto == null) {
                        insertedInto = probe.getTargetPos();
                        insertedIntoName = getChestDisplayName(config);
                    }

                    if (config.filterMode == ChestConfig.FilterMode.OVERFLOW) {
                        didOverflow = true;
                    }
                }

                remaining.shrink(inserted);
            }
        }

        if (remaining.isEmpty() && !didOverflow && insertedInto == null) {
            return EMPTY_SUCCESS;
        }

        int totalInserted = stack.getCount() - remaining.getCount();
        return new InsertionResult(remaining, didOverflow, insertedInto, insertedIntoName, totalInserted);
    }

    /**
     * The probes that could possibly take {@code item}, in priority order.
     *
     * Only the config-dependent part of the decision goes in: a probe is left
     * out if it has no block entity, no config, or a category-filtered mode
     * that rejects the item's category - i.e. it would fail both passes no
     * matter what the chest holds. Space, contents and whitelists are still
     * checked live by accepts(), and insertItem() re-checks the config, so
     * the index can only skip probes, never route into a wrong one.
     *
     * Keyed by Item (the only input is the item's category, which components
     * can't change). The whole index is dropped when:
     *  - ProbeRegistry hands back a different sorted list (probe added/removed,
     *    config edited via the controller, epoch change, or its 100-tick refresh);
     *  - RoutingIndexEpoch moved (a probe loaded, its config or mode changed,
     *    a whitelist was edited, a probe was removed);
     *  - a cached probe block entity was removed (see resolve()).
     * Probes in unloaded chunks are never looked up (that would force-load
     * the chunk); their chunk loading bumps the epoch.
     */
    private RouteCandidates getCandidates(Level world, List<BlockPos> sortedProbes, Item item, Category itemCategory) {
        long epoch = RoutingIndexEpoch.get();
        if (indexSource != sortedProbes || indexEpoch != epoch) {
            routeIndex.clear();
            indexSource = sortedProbes;
            indexEpoch = epoch;
        }

        RouteCandidates cached = routeIndex.get(item);
        if (cached != null) {
            return cached;
        }

        List<BlockPos> positions = new ArrayList<>(sortedProbes.size());
        List<OutputProbeBlockEntity> probes = new ArrayList<>(sortedProbes.size());
        for (BlockPos probePos : sortedProbes) {
            if (!world.hasChunkAt(probePos)) continue;
            if (!(world.getBlockEntity(probePos) instanceof OutputProbeBlockEntity probe)) continue;

            ChestConfig config = probe.getChestConfig();
            if (config == null) continue;

            boolean secondPass = config.filterMode == ChestConfig.FilterMode.NONE
                    || config.filterMode == ChestConfig.FilterMode.OVERFLOW;
            if (!secondPass && !quickCategoryCheck(config, itemCategory)) {
                continue; // rejected by pass 1 and not eligible for pass 2
            }
            positions.add(probePos);
            probes.add(probe);
        }

        RouteCandidates built = new RouteCandidates(
                positions.toArray(new BlockPos[0]), probes.toArray(new OutputProbeBlockEntity[0]));
        routeIndex.put(item, built);
        return built;
    }

    /**
     * The block entity recorded for candidate i, or - if it has since been
     * removed (broken, chunk unloaded) - whatever is there now, unless that
     * chunk is unloaded. Either way the index is marked stale.
     */
    private OutputProbeBlockEntity resolve(Level world, RouteCandidates candidates, int i) {
        OutputProbeBlockEntity probe = candidates.probes[i];
        if (!probe.isRemoved()) {
            return probe;
        }
        indexSource = null;
        BlockPos pos = candidates.positions[i];
        if (!world.hasChunkAt(pos)) {
            return null;
        }
        return world.getBlockEntity(pos) instanceof OutputProbeBlockEntity fresh ? fresh : null;
    }

    /**
     * OPTIMIZATION: Quick category check to skip incompatible probes
     */
    private boolean quickCategoryCheck(ChestConfig config, Category itemCategory) {
        return switch (config.filterMode) {
            case CATEGORY, CATEGORY_AND_PRIORITY, OVERFLOW ->
                    itemCategory.equals(config.filterCategory) ||
                            config.filterCategory.equals(Category.ALL);
            case BLACKLIST ->
                    !itemCategory.equals(config.filterCategory);
            case CUSTOM, NONE, PRIORITY ->
                    true; // Need full check
        };
    }

    /**
     * CRITICAL OPTIMIZATION: Single-pass insertion
     * Combines stacking + empty slot filling in ONE loop
     */
    private int insertIntoInventorySinglePass(OutputProbeBlockEntity probe, ItemStack stack) {
        Container inv = probe.getTargetInventory();
        if (inv == null) return 0;

        int originalCount = stack.getCount();
        int maxStackSize = Math.min(stack.getMaxStackSize(), inv.getMaxStackSize());
        boolean inventoryChanged = false;

        int size = inv.getContainerSize();

        // SINGLE PASS: Stack first, then fill empties
        // Pass 1: Try to stack with existing items (prioritize this)
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            ItemStack slotStack = inv.getItem(i);
            if (slotStack.isEmpty()) continue;

            if (ItemStack.isSameItemSameComponents(slotStack, stack)) {
                int canAdd = maxStackSize - slotStack.getCount();
                if (canAdd > 0) {
                    int toAdd = Math.min(canAdd, stack.getCount());
                    slotStack.grow(toAdd);
                    stack.shrink(toAdd);
                    inventoryChanged = true;
                }
            }
        }

        // Pass 2: Fill empty slots
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            ItemStack slotStack = inv.getItem(i);
            if (!slotStack.isEmpty()) continue;

            int toAdd = Math.min(maxStackSize, stack.getCount());
            ItemStack newStack = stack.copy();
            newStack.setCount(toAdd);
            inv.setItem(i, newStack);
            stack.shrink(toAdd);
            inventoryChanged = true;
        }

        // BATCHED markDirty
        if (inventoryChanged) {
            inv.setChanged();
        }

        return originalCount - stack.getCount();
    }

    /**
     * OPTIMIZED: Uses location index to skip probes without the item
     */
    public ItemStack extractItem(Level world, ItemVariant variant, int amount,
                                 NetworkInventoryManager networkManager) {
        if (world == null || amount <= 0) return ItemStack.EMPTY;

        List<BlockPos> probesWithItem = networkManager.getProbesWithItem(variant);
        if (probesWithItem.isEmpty()) {
            return ItemStack.EMPTY;
        }

        int remaining = amount;

        for (BlockPos probePos : probesWithItem) {
            if (remaining <= 0) break;

            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            int extracted = extractFromInventory(probe, variant, remaining);
            remaining -= extracted;
        }

        int totalExtracted = amount - remaining;
        return totalExtracted > 0 ? variant.toStack(totalExtracted) : ItemStack.EMPTY;
    }

    /**
     * OPTIMIZED: Early exit extraction
     */
    private int extractFromInventory(OutputProbeBlockEntity probe,
                                     ItemVariant variant, int amount) {
        Container inv = probe.getTargetInventory();
        if (inv == null) return 0;

        int extracted = 0;
        boolean inventoryChanged = false;

        // OPTIMIZATION: Create reference stack once instead of ItemVariant per slot
        ItemStack variantStack = variant.toStack(1);

        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (extracted >= amount) break;

            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            // OPTIMIZATION: Use ItemStack comparison instead of creating ItemVariant
            if (!ItemStack.isSameItemSameComponents(stack, variantStack)) continue;

            int toExtract = Math.min(amount - extracted, stack.getCount());
            stack.shrink(toExtract);
            extracted += toExtract;
            inventoryChanged = true;
        }

        if (inventoryChanged) {
            inv.setChanged();
        }

        return extracted;
    }

    private String getChestDisplayName(ChestConfig config) {
        if (config.customName != null && !config.customName.isEmpty()) {
            return config.customName;
        }
        return config.filterCategory.getDisplayName() + " Storage";
    }

    public record InsertionResult(
            ItemStack remainder,
            boolean overflowed,
            BlockPos destination,
            String destinationName,
            int amountInserted
    ) {}
}