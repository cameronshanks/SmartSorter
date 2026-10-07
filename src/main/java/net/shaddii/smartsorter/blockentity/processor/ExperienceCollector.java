package net.shaddii.smartsorter.blockentity.processor;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.world.item.crafting.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import java.util.*;

/**
 * Collects and calculates experience from furnace outputs.
 * Caches XP values per item type.
 */
public class ExperienceCollector {
    private static final int XP_CACHE_MAX = 200;

    private final Map<ItemVariant, Float> experienceCache = new HashMap<>();

    /**
     * Collects experience from furnace output.
     */
    public int collectFurnaceExperience(ServerLevel world,
                                        AbstractFurnaceBlockEntity furnace,
                                        ItemStack outputStack) {

        RecipeType<?> recipeType = getRecipeType(furnace);
        if (recipeType == null) return 0;

        ItemVariant outputVariant = ItemVariant.of(outputStack);

        // Check cache
        Float experiencePerItem = experienceCache.get(outputVariant);

        if (experiencePerItem == null) {
            experiencePerItem = calculateExperience(world, outputStack, recipeType);
            experienceCache.put(outputVariant, experiencePerItem);

            // Maintain cache size
            if (experienceCache.size() > XP_CACHE_MAX) {
                trimCache();
            }
        }

        return Math.round(experiencePerItem * outputStack.getCount());
    }

    /**
     * Calculates experience for an output item.
     */
    private float calculateExperience(ServerLevel world, ItemStack output,
                                      RecipeType<?> recipeType) {
        try {
            Collection<RecipeHolder<?>> allRecipes = world.recipeAccess().getRecipes();

            for (RecipeHolder<?> recipeEntry : allRecipes) {
                Recipe<?> recipe = recipeEntry.value();

                if (recipe.getType() != recipeType) continue;

                if (recipe instanceof AbstractCookingRecipe cookingRecipe) {
                    ItemStack recipeOutput = cookingRecipe.assemble(new SingleRecipeInput(ItemStack.EMPTY));

                    if (ItemStack.isSameItem(recipeOutput, output)) {
                        return cookingRecipe.experience();
                    }
                }
            }
        } catch (Exception e) {
            return 0.1f; // Default XP
        }

        return 0.1f;
    }

    private RecipeType<?> getRecipeType(AbstractFurnaceBlockEntity furnace) {
        if (furnace instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity) {
            return RecipeType.SMELTING;
        }
        if (furnace instanceof net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity) {
            return RecipeType.BLASTING;
        }
        if (furnace instanceof net.minecraft.world.level.block.entity.SmokerBlockEntity) {
            return RecipeType.SMOKING;
        }
        return null;
    }

    private void trimCache() {
        // Remove oldest half
        int toRemove = experienceCache.size() / 2;
        Iterator<Map.Entry<ItemVariant, Float>> it = experienceCache.entrySet().iterator();
        while (it.hasNext() && toRemove > 0) {
            it.next();
            it.remove();
            toRemove--;
        }
    }

    /**
     * Clears the experience cache.
     */
    public void clearCache() {
        experienceCache.clear();
    }
}