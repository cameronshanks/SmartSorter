package net.shaddii.smartsorter.util;

import net.minecraft.util.StringRepresentable;

/**
 * Defines how items should be sorted in the Storage Controller display.
 */
public enum SortMode implements StringRepresentable {
    NAME("name"),
    COUNT("count");

    private final String name;

    SortMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public static SortMode fromString(String name) {
        for (SortMode mode : values()) {
            if (mode.name.equals(name)) {
                return mode;
            }
        }
        return NAME; // default
    }

    /**
     * Returns the next sort mode in the cycle.
     */
    public SortMode next() {
        return values()[(this.ordinal() + 1) % values().length];
    }

    /**
     * User-friendly display name for tooltips/buttons.
     */
    public String getDisplayName() {
        return switch (this) {
            case NAME -> "Az";
            case COUNT -> "9↓";
        };
    }
}