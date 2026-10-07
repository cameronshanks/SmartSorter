package net.shaddii.smartsorter.util;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

public class ProcessProbeConfig {
    public BlockPos position;
    public String customName = null;
    public String machineType;
    public boolean enabled = true;
    public RecipeFilterMode recipeFilter = RecipeFilterMode.ORES_ONLY;
    public FuelFilterMode fuelFilter = FuelFilterMode.COAL_ONLY;
    public int itemsProcessed = 0;

    // Custom whitelists
    public Set<String> customRecipeWhitelist = new HashSet<>();
    public Set<String> customFuelWhitelist = new HashSet<>();

    // For numbering (set by controller)
    public int index = 0;

    public ProcessProbeConfig() {
    }

    public ProcessProbeConfig(BlockPos position, String machineType) {
        this.position = position;
        this.machineType = machineType;
    }

    public boolean isRecipeFilterValid() {
        if (machineType == null || machineType.equals("Unknown")) {
            return true;
        }

        String normalizedType = machineType.toLowerCase().replace("minecraft:", "");

        // A Blast Furnace CANNOT cook food. A Smoker CANNOT smelt ores/metals.
        return switch (normalizedType) {
            case "blast_furnace" -> recipeFilter != RecipeFilterMode.FOOD_ONLY;
            case "smoker" -> recipeFilter != RecipeFilterMode.ORES_ONLY && recipeFilter != RecipeFilterMode.RAW_METALS_ONLY;
            default -> true; // Furnace and others are assumed to be compatible with all filters.
        };
    }

    /**
     * Get a user-friendly error message for invalid configurations
     */
    public String getValidationError() {
        if (isRecipeFilterValid()) {
            return null;
        }

        String normalizedType = machineType.toLowerCase();
        if (normalizedType.contains(":")) {
            normalizedType = normalizedType.substring(normalizedType.indexOf(':') + 1);
        }

        return switch (normalizedType) {
            case "blast_furnace" ->
                    "Blast Furnace can only smelt ores and metal tools/armor";

            case "smoker" ->
                    "Smoker can only cook food items";

            default ->
                    "Invalid recipe filter for this machine type";
        };
    }

    /**
     * Get valid recipe filters for this machine type
     */
    public RecipeFilterMode[] getValidRecipeFilters() {
        if (machineType == null) {
            return RecipeFilterMode.values();
        }

        String normalizedType = machineType.toLowerCase().replace("minecraft:", "");

        return switch (normalizedType) {
            case "blast_furnace" -> new RecipeFilterMode[] {
                    RecipeFilterMode.ALL_SMELTABLE,
                    RecipeFilterMode.ORES_ONLY,
                    RecipeFilterMode.RAW_METALS_ONLY,
                    RecipeFilterMode.NO_WOOD,
                    RecipeFilterMode.CUSTOM
            };
            case "smoker" -> new RecipeFilterMode[] {
                    RecipeFilterMode.ALL_SMELTABLE,
                    RecipeFilterMode.FOOD_ONLY,
                    RecipeFilterMode.NO_WOOD,
                    RecipeFilterMode.CUSTOM
            };
            default -> RecipeFilterMode.values();
        };
    }


    public void setIndex(int index) {
        this.index = index;
    }

    public String getDisplayName() {
        if (customName != null && !customName.isEmpty()) {
            return customName;
        }
        // Format machine type: "minecraft:furnace" -> "Furnace"
        String type = machineType;
        if (type.contains(":")) {
            type = type.substring(type.indexOf(':') + 1);
        }
        if (!type.isEmpty()) {
            type = type.substring(0, 1).toUpperCase() + type.substring(1).toLowerCase();
        }
        return type + " #" + (index + 1);  // Use actual index, not hardcoded "1"
    }

    public void resetStats() {
        this.itemsProcessed = 0;
    }

    public CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();

        nbt.putLong("pos", position.asLong());
        if (customName != null) {
            nbt.putString("customName", customName);
        }
        nbt.putString("machineType", machineType);
        nbt.putBoolean("enabled", enabled);
        nbt.putString("recipeFilter", recipeFilter.asString());
        nbt.putString("fuelFilter", fuelFilter.asString());
        nbt.putInt("itemsProcessed", itemsProcessed);
        nbt.putInt("index", index);

        // Custom recipe whitelist
        ListTag recipeList = new ListTag();
        for (String itemId : customRecipeWhitelist) {
            recipeList.add(StringTag.valueOf(itemId));
        }
        nbt.put("customRecipes", recipeList);

        // Custom fuel whitelist
        ListTag fuelList = new ListTag();
        for (String itemId : customFuelWhitelist) {
            fuelList.add(StringTag.valueOf(itemId));
        }
        nbt.put("customFuels", fuelList);

        return nbt;
    }

    public static ProcessProbeConfig fromNbt(CompoundTag nbt) {
        ProcessProbeConfig config = new ProcessProbeConfig();

        
        // Read position
        long posLong = nbt.getLongOr("pos", 0L);
        config.position = BlockPos.of(posLong);

        // Read strings with defaults
        if (nbt.contains("customName")) {
            config.customName = nbt.getStringOr("customName", null);
        }

        config.machineType = nbt.getStringOr("machineType", "Unknown");
        config.enabled = nbt.getBooleanOr("enabled", true);

        String recipeStr = nbt.getStringOr("recipeFilter", "ORES_ONLY");
        config.recipeFilter = RecipeFilterMode.fromString(recipeStr);

        String fuelStr = nbt.getStringOr("fuelFilter", "COAL_ONLY");
        config.fuelFilter = FuelFilterMode.fromString(fuelStr);

        config.itemsProcessed = nbt.getIntOr("itemsProcessed", 0);
        config.index = nbt.getIntOr("index", 0);

        // Read custom recipe whitelist
        if (nbt.contains("customRecipes")) {
            nbt.getList("customRecipes").ifPresent(recipeList -> {
                for (int i = 0; i < recipeList.size(); i++) {
                    recipeList.getString(i).ifPresent(itemId -> {
                        if (!itemId.isEmpty()) {
                            config.customRecipeWhitelist.add(itemId);
                        }
                    });
                }
            });
        }

        // Read custom fuel whitelist
        if (nbt.contains("customFuels")) {
            nbt.getList("customFuels").ifPresent(fuelList -> {
                for (int i = 0; i < fuelList.size(); i++) {
                    fuelList.getString(i).ifPresent(itemId -> {
                        if (!itemId.isEmpty()) {
                            config.customFuelWhitelist.add(itemId);
                        }
                    });
                }
            });
        }

        return config;
    }

    public ProcessProbeConfig copy() {
        ProcessProbeConfig copy = new ProcessProbeConfig();
        copy.position = this.position;
        copy.customName = this.customName;
        copy.machineType = this.machineType;
        copy.enabled = this.enabled;
        copy.recipeFilter = this.recipeFilter;
        copy.fuelFilter = this.fuelFilter;
        copy.itemsProcessed = this.itemsProcessed;
        copy.index = this.index;

        // Only copy whitelists if they're non-empty (most configs won't use custom filters)
        if (!this.customRecipeWhitelist.isEmpty()) {
            copy.customRecipeWhitelist = new HashSet<>(this.customRecipeWhitelist);
        }
        if (!this.customFuelWhitelist.isEmpty()) {
            copy.customFuelWhitelist = new HashSet<>(this.customFuelWhitelist);
        }

        return copy;
    }

    public boolean isFunctionallyEqual(ProcessProbeConfig other) {
        if (other == null) return false;
        if (this == other) return true;

        return this.enabled == other.enabled &&
                this.recipeFilter == other.recipeFilter &&
                this.fuelFilter == other.fuelFilter &&
                Objects.equals(this.customName, other.customName);
        // Note: itemsProcessed intentionally excluded - it changes frequently
    }

}