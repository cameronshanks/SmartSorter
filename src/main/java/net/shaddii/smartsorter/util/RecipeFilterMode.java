package net.shaddii.smartsorter.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

public enum RecipeFilterMode {
    ALL_SMELTABLE("All Smeltable"),
    ORES_ONLY("Ores Only"),
    FOOD_ONLY("Food Only"),
    RAW_METALS_ONLY("Raw Metals"),
    NO_WOOD("No Wood"),
    CUSTOM("Custom Whitelist");

    private final String displayName;

    RecipeFilterMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean matches(ItemStack input, RecipeType<?> recipeType) {
        return switch (this) {
            case ALL_SMELTABLE -> true;
            case ORES_ONLY -> isOre(input);
            case FOOD_ONLY -> isFood(input);
            case RAW_METALS_ONLY -> isRawMetal(input);
            case NO_WOOD -> !isWood(input);
            case CUSTOM -> false; // Will check custom whitelist separately
        };
    }

    private boolean isOre(ItemStack stack) {
        Item item = stack.getItem();
        // Check common ore tags
        return item.getDefaultInstance().is(TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", "ores")))
                || item.getDefaultInstance().is(TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("minecraft", "ores")))
                || BuiltInRegistries.ITEM.getKey(item).getPath().contains("ore");
    }

    private boolean isFood(ItemStack stack) {
        // In 1.21.10, use getComponents() to check for food
        return stack.getComponents().has(net.minecraft.core.component.DataComponents.FOOD);
    }

    private boolean isRawMetal(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return id.startsWith("raw_");
    }

    private boolean isWood(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return id.contains("log") || id.contains("wood") || id.contains("plank");
    }

    public String asString() {
        return this.name();
    }

    public static RecipeFilterMode fromString(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return ORES_ONLY; // Safe default
        }
    }
}