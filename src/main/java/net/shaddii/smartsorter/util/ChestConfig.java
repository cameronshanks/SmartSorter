package net.shaddii.smartsorter.util;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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

    public static void write(RegistryFriendlyByteBuf buf, ChestConfig config) {
        buf.writeBlockPos(config.position);
        buf.writeUtf(config.customName);
        buf.writeUtf(config.filterCategory.asString());
        buf.writeInt(config.priority);
        buf.writeEnum(config.filterMode);
        buf.writeBoolean(config.autoItemFrame);
        buf.writeBoolean(config.strictNBTMatch);
        buf.writeInt(config.cachedFullness);

        if (config.simplePrioritySelection != null) {
            buf.writeBoolean(true);
            buf.writeEnum(config.simplePrioritySelection);
        } else {
            buf.writeBoolean(false);
        }

        // Write preview items
        buf.writeVarInt(config.previewItems.size());
        for (ItemStack stack : config.previewItems) {
            ItemStack.STREAM_CODEC.encode(buf, stack);
        }
    }

    public static ChestConfig read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String name = buf.readUtf();
        Category category = CategoryManager.getInstance().getCategory(buf.readUtf());
        int priority = buf.readInt();
        FilterMode mode = buf.readEnum(FilterMode.class);
        boolean autoFrame = buf.readBoolean();
        boolean strictNBT = buf.readBoolean();

        ChestConfig config = new ChestConfig(pos, name, category, priority, mode, autoFrame);
        config.strictNBTMatch = strictNBT;
        config.cachedFullness = buf.readInt();

        boolean hasSimplePriority = buf.readBoolean();
        if (hasSimplePriority) {
            config.simplePrioritySelection = buf.readEnum(SimplePriority.class);
        } else {
            config.simplePrioritySelection = SimplePriority.MEDIUM;
        }

        // Read preview items
        int itemCount = buf.readVarInt();
        config.previewItems = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            config.previewItems.add(ItemStack.STREAM_CODEC.decode(buf));
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

    // Size of one routing tier. Priorities are ranks (1 = Highest) and never
    // reach this, so tiers can't overlap however many chests there are.
    private static final int TIER = 100_000;

    private int calculateHiddenPriority() {
        // Routing order (higher hiddenPriority = tried first), one tier per mode:
        // Filtered Priority > Custom > Priority > Dedicated > Blacklist > General > Overflow.
        // Within a tier, a lower priority number (1 = Highest) is tried first.
        int rank = Math.max(0, Math.min(priority, TIER - 1));
        int tier = switch (filterMode) {
            case CATEGORY_AND_PRIORITY -> 6;
            case CUSTOM -> 5;
            case PRIORITY -> 4;
            case CATEGORY -> 3;
            case BLACKLIST -> 2;
            case NONE -> 1;
            case OVERFLOW -> 0;
        };
        return tier * TIER - rank;
    }

    public void updateHiddenPriority() {
        this.hiddenPriority = calculateHiddenPriority();
    }

    public CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();
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

    public static ChestConfig fromNbt(CompoundTag nbt) {
        BlockPos pos = BlockPos.of(nbt.getLong("pos").orElse(0L));
        String name = nbt.getStringOr("name", "");
        Category category = CategoryManager.getInstance().getCategory(nbt.getStringOr("category", "smartsorter:all"));

        // Migration: Check if old format (string) exists
        int priority;
        if (nbt.contains("priorityLevel")) {
            // Old format - convert
            String oldPriority = nbt.getStringOr("priorityLevel", "MEDIUM");
            priority = switch (oldPriority) {
                case "HIGH" -> 1;
                case "LOW" -> 3;
                default -> 2; // MEDIUM
            };
        } else {
            priority = nbt.getIntOr("priority", 1);
        }

        FilterMode mode = FilterMode.valueOf(nbt.getStringOr("filterMode", "NONE"));
        boolean autoFrame = nbt.getBooleanOr("autoItemFrame", false);
        boolean strictNBT = nbt.getBooleanOr("strictNBT", false);
        int cachedFull = nbt.getIntOr("cachedFullness", -1);

        ChestConfig config = new ChestConfig(pos, name, category, priority, mode, autoFrame);
        config.strictNBTMatch = strictNBT;
        config.cachedFullness = cachedFull;

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