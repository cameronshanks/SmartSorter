package net.shaddii.smartsorter.blockentity.processor;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.util.ProcessProbeConfig;

import java.util.Map;

/**
 * Handles all smelting machine processing (furnace, blast furnace, smoker).
 * Optimized for batch operations and minimal controller queries.
 */
public class SmeltingProcessor {
    private static final int MAX_FUEL_PER_INSERT = 16;
    private static final int MAX_INPUT_PER_INSERT = 4;

    private final RecipeValidator recipeValidator;
    private final ExperienceCollector xpCollector;

    // Stats
    private int itemsProcessed = 0;

    public SmeltingProcessor(RecipeValidator validator, ExperienceCollector collector) {
        this.recipeValidator = validator;
        this.xpCollector = collector;
    }

    /**
     * Main processing method for all smelting machines (furnace, blast furnace, smoker).
     */
    public void processSmeltingMachine(ServerLevel world, BlockPos probePos,
                                       AbstractFurnaceBlockEntity machine,
                                       StorageControllerBlockEntity controller,
                                       ProcessProbeConfig config) {

        // First extract outputs
        boolean outputsHandled = extractOutputs(world, probePos, machine, controller);
        if (!outputsHandled) return;

        // Check what the smelting machine needs
        SmeltingNeeds needs = analyzeSmeltingNeeds(machine);
        if (!needs.needsInput && !needs.needsFuel) return;

        // Get network items once
        Map<ItemVariant, Long> networkItems = controller.getNetworkItems();
        RecipeType<?> recipeType = recipeValidator.getRecipeType(machine);

        // Process needed items
        if (needs.needsInput) {
            supplyInput(world, machine, controller, networkItems,
                    needs, recipeType, config);
        }

        if (needs.needsFuel) {
            supplyFuel(world, machine, controller, networkItems,
                    needs, config);
        }
    }

    /**
     * Extracts outputs from smelting machine to network.
     */
    private boolean extractOutputs(ServerLevel world, BlockPos probePos,
                                   Container inventory,
                                   StorageControllerBlockEntity controller) {
        boolean allExtracted = true;
        int outputSlot = 2; // Standard output slot for all vanilla smelting machines

        ItemStack output = inventory.getItem(outputSlot);
        if (!output.isEmpty()) {
            ItemStack toInsert = output.copy();
            ItemStack remaining = controller.insertItem(toInsert).remainder();

            if (remaining.isEmpty()) {
                // Collect XP from smelting
                if (inventory instanceof AbstractFurnaceBlockEntity smeltingMachine) {
                    int xp = xpCollector.collectFurnaceExperience(world, smeltingMachine, toInsert);
                    if (xp > 0) {
                        controller.addExperience(xp);
                    }
                }

                inventory.setItem(outputSlot, ItemStack.EMPTY);
                inventory.setChanged();
                itemsProcessed += toInsert.getCount();

                // Sync stats
                controller.syncProbeStatsToClients(probePos, itemsProcessed);
            } else {
                allExtracted = false;
            }
        }

        return allExtracted;
    }

    /**
     * Supplies input items to smelting machine.
     */
    private void supplyInput(ServerLevel world, AbstractFurnaceBlockEntity machine,
                             StorageControllerBlockEntity controller,
                             Map<ItemVariant, Long> networkItems,
                             SmeltingNeeds needs, RecipeType<?> recipeType,
                             ProcessProbeConfig config) {

        for (Map.Entry<ItemVariant, Long> entry : networkItems.entrySet()) {
            if (entry.getValue() <= 0) continue;

            ItemVariant variant = entry.getKey();

            // Check if existing input matches
            if (!needs.currentInput.isEmpty()) {
                if (!ItemVariant.of(needs.currentInput).equals(variant)) continue;
            } else {
                // Check if item can be smelted
                if (!recipeValidator.canSmelt(world, variant, machine, recipeType)) continue;
                if (!recipeValidator.matchesRecipeFilter(variant, config.recipeFilter)) continue;
            }

            int amount = (int) Math.min(needs.inputSpace, entry.getValue());
            ItemStack extracted = controller.extractItem(variant, amount);

            if (!extracted.isEmpty()) {
                ItemStack existing = machine.getItem(0);
                if (existing.isEmpty()) {
                    machine.setItem(0, extracted);
                } else {
                    existing.grow(extracted.getCount());
                }
                machine.setChanged();
                break;
            }
        }
    }

    /**
     * Supplies fuel to smelting machine.
     */
    private void supplyFuel(ServerLevel world, AbstractFurnaceBlockEntity machine,
                            StorageControllerBlockEntity controller,
                            Map<ItemVariant, Long> networkItems,
                            SmeltingNeeds needs, ProcessProbeConfig config) {

        for (Map.Entry<ItemVariant, Long> entry : networkItems.entrySet()) {
            if (entry.getValue() <= 0) continue;

            ItemVariant variant = entry.getKey();

            // Check if existing fuel matches
            if (!needs.currentFuel.isEmpty()) {
                if (!ItemVariant.of(needs.currentFuel).equals(variant)) continue;
            } else {
                // Check if item is fuel
                if (!recipeValidator.isFuel(world, variant)) continue;
                if (!recipeValidator.matchesFuelFilter(variant, config.fuelFilter)) continue;
            }

            int amount = (int) Math.min(needs.fuelSpace, entry.getValue());
            ItemStack extracted = controller.extractItem(variant, amount);

            if (!extracted.isEmpty()) {
                ItemStack existing = machine.getItem(1);
                if (existing.isEmpty()) {
                    machine.setItem(1, extracted);
                } else {
                    existing.grow(extracted.getCount());
                }
                machine.setChanged();
                break;
            }
        }
    }

    /**
     * Analyzes what a smelting machine needs.
     */
    private SmeltingNeeds analyzeSmeltingNeeds(AbstractFurnaceBlockEntity machine) {
        SmeltingNeeds needs = new SmeltingNeeds();

        // Check input slot (slot 0)
        ItemStack input = machine.getItem(0);
        if (input.isEmpty()) {
            needs.needsInput = true;
            needs.inputSpace = MAX_INPUT_PER_INSERT;
        } else if (input.getCount() < input.getMaxStackSize()) {
            needs.needsInput = true;
            needs.currentInput = input;
            needs.inputSpace = Math.min(
                    input.getMaxStackSize() - input.getCount(),
                    MAX_INPUT_PER_INSERT
            );
        }

        // Check fuel slot (slot 1)
        ItemStack fuel = machine.getItem(1);
        if (fuel.isEmpty()) {
            needs.needsFuel = true;
            needs.fuelSpace = MAX_FUEL_PER_INSERT;
        } else if (fuel.getCount() < fuel.getMaxStackSize()) {
            needs.needsFuel = true;
            needs.currentFuel = fuel;
            needs.fuelSpace = Math.min(
                    fuel.getMaxStackSize() - fuel.getCount(),
                    MAX_FUEL_PER_INSERT
            );
        }

        return needs;
    }

    /**
     * Gets the number of items processed.
     */
    public int getItemsProcessed() {
        return itemsProcessed;
    }

    /**
     * Sets the items processed count (for loading from NBT).
     */
    public void setItemsProcessed(int count) {
        this.itemsProcessed = count;
    }

    /**
     * Container for analyzing what a smelting machine needs.
     */
    private static class SmeltingNeeds {
        boolean needsInput = false;
        boolean needsFuel = false;
        int inputSpace = 0;
        int fuelSpace = 0;
        ItemStack currentInput = ItemStack.EMPTY;
        ItemStack currentFuel = ItemStack.EMPTY;
    }
}