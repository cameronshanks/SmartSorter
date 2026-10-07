package net.shaddii.smartsorter.blockentity.controller;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;

import java.util.*;

/**
 * Removed ALL inventory organization
 */
public class ChestSortingService {
    private final ItemRoutingService routingService;
    private final ProbeRegistry probeRegistry;
    private final NetworkInventoryManager networkManager;

    public ChestSortingService(ItemRoutingService routingService,
                               ProbeRegistry probeRegistry,
                               NetworkInventoryManager networkManager) {
        this.routingService = routingService;
        this.probeRegistry = probeRegistry;
        this.networkManager = networkManager;
    }

    public void sortChests(Level world, List<BlockPos> chestPositions,
                           ServerPlayer player) {
        if (world == null || world.isClientSide()) return;

        Map<ItemVariant, Long> overflowCounts = new HashMap<>();
        Map<ItemVariant, String> overflowDestinations = new HashMap<>();

        for (BlockPos chestPos : chestPositions) {
            sortChest(world, chestPos, overflowCounts, overflowDestinations);
        }

        if (player != null && !overflowCounts.isEmpty()) {
            sendOverflowNotification(player, overflowCounts, overflowDestinations);
        }
    }

    /**
     * Removed ALL InventoryOrganizer calls
     * Organization is unnecessary and causes 300,000+ operations with large networks
     */
    public void sortChest(Level world, BlockPos chestPos,
                          Map<ItemVariant, Long> overflowCounts,
                          Map<ItemVariant, String> overflowDestinations) {

        OutputProbeBlockEntity sourceProbe = findProbeForChest(world, chestPos);
        if (sourceProbe == null) return;

        Container sourceInv = sourceProbe.getTargetInventory();
        if (sourceInv == null) return;

        // Extract all items (REMOVED pre-organization)
        List<ItemStack> itemsToSort = extractAllItems(sourceInv);
        if (itemsToSort.isEmpty()) return;

        // Insert items into network
        List<ItemStack> unsortedItems = new ArrayList<>();

        for (ItemStack stack : itemsToSort) {
            ItemVariant variant = ItemVariant.of(stack);
            int originalCount = stack.getCount();

            ItemRoutingService.InsertionResult result =
                    routingService.insertItem(world, stack);

            // Track overflow
            if (result.overflowed()) {
                long overflowed = originalCount - result.remainder().getCount();
                if (overflowed > 0) {
                    overflowCounts.merge(variant, overflowed, Long::sum);
                    if (result.destinationName() != null) {
                        overflowDestinations.put(variant, result.destinationName());
                    }
                }
            }

            // Collect unsorted remainder
            if (!result.remainder().isEmpty()) {
                unsortedItems.add(result.remainder());
            }
        }

        // Return unsorted items to source
        for (ItemStack stack : unsortedItems) {
            insertIntoInventory(sourceProbe, stack);
        }

        // organizeModifiedChests() - unnecessary overhead
    }

    private List<ItemStack> extractAllItems(Container inv) {
        List<ItemStack> items = new ArrayList<>();

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                items.add(inv.removeItemNoUpdate(i));
            }
        }

        inv.setChanged();
        return items;
    }

    private OutputProbeBlockEntity findProbeForChest(Level world, BlockPos chestPos) {
        // OPTIMIZATION: Use index to find probe directly (O(1) instead of O(n))
        BlockPos probePos = probeRegistry.getProbeForChest(world, chestPos);
        if (probePos == null) return null;

        BlockEntity be = world.getBlockEntity(probePos);
        return be instanceof OutputProbeBlockEntity probe ? probe : null;
    }

    private void insertIntoInventory(OutputProbeBlockEntity probe, ItemStack stack) {
        Container inv = probe.getTargetInventory();
        if (inv == null) return;

        int maxStackSize = Math.min(stack.getMaxStackSize(), inv.getMaxStackSize());
        boolean inventoryChanged = false;

        for (int i = 0; i < inv.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack slotStack = inv.getItem(i);

            if (slotStack.isEmpty()) {
                int toAdd = Math.min(maxStackSize, stack.getCount());
                inv.setItem(i, stack.copyWithCount(toAdd));
                stack.shrink(toAdd);
                inventoryChanged = true;
            } else if (ItemStack.isSameItemSameComponents(slotStack, stack)) {
                int canAdd = maxStackSize - slotStack.getCount();
                if (canAdd > 0) {
                    int toAdd = Math.min(canAdd, stack.getCount());
                    slotStack.grow(toAdd);
                    stack.shrink(toAdd);
                    inventoryChanged = true;
                }
            }
        }

        if (inventoryChanged) {
            inv.setChanged();
        }
    }

    private void sendOverflowNotification(ServerPlayer player,
                                          Map<ItemVariant, Long> overflowCounts,
                                          Map<ItemVariant, String> destinations) {
        // Notification logic
    }
}