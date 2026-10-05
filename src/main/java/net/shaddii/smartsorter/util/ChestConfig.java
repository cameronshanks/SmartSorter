package net.shaddii.smartsorter.util;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChestConfig {
    public final BlockPos position;
    public String customName;
    public Category filterCategory;
    public int priority = 1;
    public FilterMode filterMode;
    public boolean autoItemFrame;
    public boolean strictNBTMatch = false;
    public SimplePriority simplePrioritySelection = SimplePriority.MEDIUM;

    // Hidden priority for special chests (set by filter mode)
    public int hiddenPriority;
    public int cachedFullness = -1;

    public transient List<ItemStack> previewItems = new ArrayList<>();

    /*
     * Whitelist overlay for CUSTOM chests. A whitelist chest still reports
     * filterMode == CUSTOM everywhere; OutputProbeBlockEntity.accepts() checks
     * whitelistEnabled first and then accepts exactly the listed items instead
     * of "items already in the chest". Edited from the Output Probe screen's
     * Sorting/Editing buttons and by right-clicking the probe with an item
     * while editing is on. Synced with the Whitelist* payloads, persisted by
     * StorageControllerBlockEntity.
     */
    public boolean whitelistEnabled = false;
    /** Right-clicking the probe with an item adds/removes it instead of testing it. */
    public boolean whitelistEditMode = false;
    private Set<Item> whitelist = new HashSet<>();

    /** Never null. Treat as read-only; use setWhitelist() to change it. */
    public Set<Item> getWhitelist() {
        return whitelist;
    }

    /** Stores a copy, so callers can't change the set behind accepts()' back. */
    public void setWhitelist(Set<Item> items) {
        this.whitelist = new HashSet<>(items);
    }

    public void copyWhitelistFrom(ChestConfig other) {
        this.whitelistEnabled = other.whitelistEnabled;
        this.whitelistEditMode = other.whitelistEditMode;
        this.whitelist = new HashSet<>(other.whitelist);
    }

    public enum FilterMode {
        NONE("General Storage", "Accepts any items"),
        CATEGORY("Dedicated", "Only accepts items in selected category"),
        PRIORITY("Priority Storage", "Accepts any items (fills earlier)"),
        CATEGORY_AND_PRIORITY("Filtered Priority", "Category filter + high priority"),
        OVERFLOW("Overflow Storage", "Only category items (fills last)"),
        BLACKLIST("Blacklist", "Everything EXCEPT selected category"),
        CUSTOM("Custom", "Only items matching chest contents");

        private final String displayName;
        private final String description;

        FilterMode(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }

        public FilterMode next() {
            FilterMode[] modes = values();
            return modes[(this.ordinal() + 1) % modes.length];
        }

        // For routing priority calculation
        public int getBasePriority() {
            return switch (this) {
                case CATEGORY_AND_PRIORITY -> 1000;
                case CUSTOM -> 800;
                case PRIORITY -> 500;
                case CATEGORY -> 300;
                case BLACKLIST -> 100;
                case OVERFLOW -> -500;
                case NONE -> 0;
            };
        }

        public boolean needsCategoryFilter() {
            return this == CATEGORY || this == CATEGORY_AND_PRIORITY ||
                    this == OVERFLOW || this == BLACKLIST;
        }

        public boolean isBlacklistMode() {
            return this == BLACKLIST;
        }
    }

    public enum SimplePriority {
        HIGHEST("Highest", "§a", "Gets items first"),
        HIGH("High", "§2", "High priority"),
        MEDIUM("Medium", "§e", "Normal priority"),
        LOW("Low", "§6", "Low priority"),
        LOWEST("Lowest", "§c", "Gets items last");

        private final String displayName;
        private final String colorCode;
        private final String description;

        SimplePriority(String displayName, String colorCode, String description) {
            this.displayName = displayName;
            this.colorCode = colorCode;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getColorCode() { return colorCode; }
        public String getDescription() { return description; }

        public int getNumericValue(int maxPriority) {
            int effectiveMax = Math.max(5, maxPriority);

            return switch (this) {
                case HIGHEST -> 1;
                case HIGH -> Math.max(2, effectiveMax / 5);
                case MEDIUM -> Math.max(3, effectiveMax / 2);
                case LOW -> Math.max(4, (effectiveMax * 4) / 5);
                case LOWEST -> Math.max(5, effectiveMax);
            };
        }

        public static SimplePriority fromNumeric(int priority, int maxPriority) {
            if (maxPriority <= 0) return MEDIUM;
            if (priority <= 0) return HIGHEST;
            if (priority > maxPriority) return LOWEST;

            // Calculate percentage position
            double percentage = (double) priority / (double) maxPriority;

            // Map to 5 buckets
            if (percentage <= 0.20) return HIGHEST;  // Top 20%
            if (percentage <= 0.40) return HIGH;     // 20-40%
            if (percentage <= 0.60) return MEDIUM;   // 40-60%
            if (percentage <= 0.80) return LOW;      // 60-80%
            return LOWEST;                           // Bottom 20%
        }
    }

    public static void write(RegistryByteBuf buf, ChestConfig config) {
        buf.writeBlockPos(config.position);
        buf.writeString(config.customName);
        buf.writeString(config.filterCategory.asString());
        buf.writeInt(config.priority);
        buf.writeEnumConstant(config.filterMode);
        buf.writeBoolean(config.autoItemFrame);
        buf.writeBoolean(config.strictNBTMatch);
        buf.writeInt(config.cachedFullness);

        if (config.simplePrioritySelection != null) {
            buf.writeBoolean(true);
            buf.writeEnumConstant(config.simplePrioritySelection);
        } else {
            buf.writeBoolean(false);
        }

        // Write preview items
        buf.writeVarInt(config.previewItems.size());
        for (ItemStack stack : config.previewItems) {
            ItemStack.PACKET_CODEC.encode(buf, stack);
        }
    }

    public static ChestConfig read(RegistryByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String name = buf.readString();
        Category category = CategoryManager.getInstance().getCategory(buf.readString());
        int priority = buf.readInt();
        FilterMode mode = buf.readEnumConstant(FilterMode.class);
        boolean autoFrame = buf.readBoolean();
        boolean strictNBT = buf.readBoolean();

        ChestConfig config = new ChestConfig(pos, name, category, priority, mode, autoFrame);
        config.strictNBTMatch = strictNBT;
        config.cachedFullness = buf.readInt();

        boolean hasSimplePriority = buf.readBoolean();
        if (hasSimplePriority) {
            config.simplePrioritySelection = buf.readEnumConstant(SimplePriority.class);
        } else {
            config.simplePrioritySelection = SimplePriority.MEDIUM;
        }

        // Read preview items
        int itemCount = buf.readVarInt();
        config.previewItems = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            config.previewItems.add(ItemStack.PACKET_CODEC.decode(buf));
        }

        return config;
    }


    public ChestConfig(BlockPos position) {
        this.position = position;
        this.customName = "";
        this.filterCategory = Category.ALL;
        this.priority = 1;  // Default to highest
        this.filterMode = FilterMode.NONE;
        this.autoItemFrame = false;
        this.hiddenPriority = 0;
        this.simplePrioritySelection = SimplePriority.MEDIUM;
    }

    public ChestConfig(BlockPos position, String customName, Category filterCategory,
                       int priority, FilterMode filterMode, boolean autoItemFrame) {
        this.position = position;
        this.customName = customName;
        this.filterCategory = filterCategory;
        this.priority = priority;
        this.filterMode = filterMode;
        this.autoItemFrame = autoItemFrame;
        this.hiddenPriority = calculateHiddenPriority();
        if (filterMode == FilterMode.OVERFLOW) {
            this.simplePrioritySelection = SimplePriority.LOWEST;
        } else {
            this.simplePrioritySelection = SimplePriority.MEDIUM;
        }
    }

    private int calculateHiddenPriority() {
        int baseValue = filterMode.getBasePriority();
        // Lower priority number = higher actual priority
        // So invert it: subtract from a large number
        return baseValue - priority;
    }

    public void updateHiddenPriority() {
        // Priority order (higher number = processed first):
        // 1. CATEGORY_AND_PRIORITY: 10000+ (highest)
        // 2. CUSTOM: 5000 (opportunistic matching)
        // 3. PRIORITY: 1000-10000 (user priority)
        // 4. CATEGORY: 100-1000 (user priority, lower than PRIORITY)
        // 5. BLACKLIST: 50
        // 6. NONE (General): -500 (second to last)
        // 7. OVERFLOW: -1000 (always last)

        switch (filterMode) {
            case CATEGORY_AND_PRIORITY -> {
                // Highest priority with category filter
                this.hiddenPriority = 10000 + (priority * 100);
            }
            case CUSTOM -> {
                // Custom chests - mid-high priority for opportunistic matching
                this.hiddenPriority = 5000;
            }
            case PRIORITY -> {
                // Priority-based routing (user priority 1-10 maps to 1000-10000)
                this.hiddenPriority = priority * 1000;
            }
            case CATEGORY -> {
                // Category filter with lower priority than PRIORITY
                this.hiddenPriority = priority * 100;
            }
            case BLACKLIST -> {
                // Blacklist mode
                this.hiddenPriority = 50;
            }
            case NONE -> {
                // General storage - second to last
                this.hiddenPriority = -500;
            }
            case OVERFLOW -> {
                // Overflow - always last
                this.hiddenPriority = -1000;
            }
        }
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putLong("pos", position.asLong());
        nbt.putString("name", customName);
        nbt.putString("category", filterCategory.asString());
        nbt.putInt("priority", priority);
        nbt.putString("filterMode", filterMode.name());
        nbt.putBoolean("autoItemFrame", autoItemFrame);
        nbt.putBoolean("strictNBT", strictNBTMatch);
        nbt.putInt("cachedFullness", cachedFullness);
        nbt.putString("simplePrioritySelection", simplePrioritySelection.name());

        if (simplePrioritySelection != null) {
            nbt.putString("simplePrioritySelection", simplePrioritySelection.name());
        }

        return nbt;
    }

    public static ChestConfig fromNbt(NbtCompound nbt) {
        //? if >=1.21.8 {
        BlockPos pos = BlockPos.fromLong(nbt.getLong("pos").orElse(0L));
        String name = nbt.getString("name", "");
        Category category = CategoryManager.getInstance().getCategory(nbt.getString("category", "smartsorter:all"));

        // Migration: Check if old format (string) exists
        int priority;
        if (nbt.contains("priorityLevel")) {
            // Old format - convert
            String oldPriority = nbt.getString("priorityLevel", "MEDIUM");
            priority = switch (oldPriority) {
                case "HIGH" -> 1;
                case "LOW" -> 3;
                default -> 2; // MEDIUM
            };
        } else {
            priority = nbt.getInt("priority", 1);
        }

        FilterMode mode = FilterMode.valueOf(nbt.getString("filterMode", "NONE"));
        boolean autoFrame = nbt.getBoolean("autoItemFrame", false);
        boolean strictNBT = nbt.getBoolean("strictNBT", false);
        int cachedFull = nbt.getInt("cachedFullness", -1);
        //?} else {
    /*BlockPos pos = BlockPos.fromLong(nbt.getLong("pos"));
    String name = nbt.contains("name") ? nbt.getString("name") : "";
    String categoryStr = nbt.contains("category") ? nbt.getString("category") : "smartsorter:all";
    Category category = CategoryManager.getInstance().getCategory(categoryStr);

    // Migration: Check if old format (string) exists
    int priority;
    if (nbt.contains("priorityLevel")) {
        // Old format - convert
        String oldPriority = nbt.getString("priorityLevel");
        priority = switch (oldPriority) {
            case "HIGH" -> 1;
            case "LOW" -> 3;
            default -> 2; // MEDIUM
        };
    } else {
        priority = nbt.contains("priority") ? nbt.getInt("priority") : 1;
    }

    String modeStr = nbt.contains("filterMode") ? nbt.getString("filterMode") : "NONE";
    FilterMode mode = FilterMode.valueOf(modeStr);
    boolean autoFrame = nbt.contains("autoItemFrame") && nbt.getBoolean("autoItemFrame");
    boolean strictNBT = nbt.contains("strictNBT") && nbt.getBoolean("strictNBT");
    int cachedFull = nbt.contains("cachedFullness") ? nbt.getInt("cachedFullness") : -1;
    *///?}

        ChestConfig config = new ChestConfig(pos, name, category, priority, mode, autoFrame);
        config.strictNBTMatch = strictNBT;
        config.cachedFullness = cachedFull;

        //? if >=1.21.8 {
        // Load SimplePriority selection
        if (nbt.contains("simplePrioritySelection")) {
            nbt.getString("simplePrioritySelection").ifPresent(str -> {
                try {
                    config.simplePrioritySelection = SimplePriority.valueOf(str);
                } catch (Exception e) {
                    config.simplePrioritySelection = SimplePriority.MEDIUM;
                }
            });
        }
        //?} else {
        /*// Load SimplePriority selection
        if (nbt.contains("simplePrioritySelection")) {
            try {
                config.simplePrioritySelection = SimplePriority.valueOf(
                    nbt.getString("simplePrioritySelection")
                );
            } catch (Exception e) {
                config.simplePrioritySelection = SimplePriority.MEDIUM;
            }
        }
        *///?}

        return config;
    }




    public ChestConfig copy() {
        ChestConfig copied = new ChestConfig(position, customName, filterCategory, priority, filterMode, autoItemFrame);
        copied.strictNBTMatch = this.strictNBTMatch;
        copied.hiddenPriority = this.hiddenPriority;
        copied.simplePrioritySelection = this.simplePrioritySelection;
        copied.copyWhitelistFrom(this);

        return copied;
    }

    public String getDisplayName() {
        if (!customName.isEmpty()) {
            return customName;
        }
        return String.format("Chest [%d, %d, %d]", position.getX(), position.getY(), position.getZ());
    }

    public String getSortKey(ChestSortMode mode) {
        return switch (mode) {
            case PRIORITY -> {
                String namePart = customName.isEmpty() ? position.toShortString() : customName;

                // For CUSTOM filter mode, always use priority 0
                int sortPriority = (filterMode == FilterMode.CUSTOM) ? 0 : priority;

                yield String.format("%03d_%s", sortPriority, namePart);
            }
            case NAME -> (customName.isEmpty() ? "zzz_" + position.toShortString() : customName.toLowerCase());
            case FULLNESS -> {
                String namePart = customName.isEmpty() ? position.toShortString() : customName;
                yield String.format("%03d_%s", 100 - cachedFullness, namePart);
            }
            case COORDINATES -> position.toShortString();
        };
    }

}