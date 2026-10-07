package net.shaddii.smartsorter.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

public class Category implements Comparable<Category> {
    private final Identifier id;
    private final String displayName;
    private final String shortName;
    private final int order;
    private final Set<TagKey<Item>> tags = new HashSet<>();
    private final Set<Identifier> items = new HashSet<>();

    private Set<Item> matchingItemsCache = null;
    private boolean cacheBuilt = false;

    // Special categories (always exist)
    public static final Category ALL = new Category(
            Identifier.fromNamespaceAndPath("smartsorter", "all"),
            "All Items",
            "All",
            -1
    );

    public static final Category MISC = new Category(
            Identifier.fromNamespaceAndPath("smartsorter", "misc"),
            "Misc / Rare",
            "Misc",
            9999
    );

    public Category(Identifier id, String displayName, String shortName, int order) {
        this.id = id;
        this.displayName = displayName;
        this.shortName = shortName;
        this.order = order;
    }

    /**
     * Add a tag or item ID to this category
     * Format: "#minecraft:logs" for tags, "minecraft:dirt" for items
     */
    public void addEntry(String entry) {
        if (entry.startsWith("#")) {
            // It's a tag
            String tagId = entry.substring(1);
            Identifier tagIdentifier = Identifier.tryParse(tagId);
            if (tagIdentifier != null) {
                this.tags.add(TagKey.create(Registries.ITEM, tagIdentifier));
            }
        } else {
            // It's an item ID
            Identifier itemId = Identifier.tryParse(entry);
            if (itemId != null) {
                this.items.add(itemId);
            }
        }
    }

    public void buildCache() {
        if (cacheBuilt) return;

        matchingItemsCache = new HashSet<>();

        // Add direct items
        for (Identifier itemId : items) {
            Item item = BuiltInRegistries.ITEM.getValue(itemId);
            if (item != null) {
                matchingItemsCache.add(item);
            }
        }

        // Add items from tags
        for (TagKey<Item> tag : tags) {
            BuiltInRegistries.ITEM.getTagOrEmpty(tag).forEach(entry -> {
                matchingItemsCache.add(entry.value());
            });
        }

        cacheBuilt = true;
    }

    /**
     * Check if an item belongs to this category
     */
    public boolean matches(Item item) {
        if (this == ALL) {
            return true;
        }
        if (this == MISC) {
            return false;
        }
        buildCache();
        return matchingItemsCache != null && matchingItemsCache.contains(item);
    }

    // Getters
    public Identifier getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getShortName() { return shortName; }
    public int getOrder() { return order; }

    /**
     * Get all entries (tags and items) in the same format as addEntry() expects
     */
    public List<String> getEntries() {
        List<String> entries = new ArrayList<>();

        // Add tags with # prefix
        for (TagKey<Item> tag : tags) {
            entries.add("#" + tag.location().toString());
        }

        // Add items without prefix
        for (Identifier itemId : items) {
            entries.add(itemId.toString());
        }

        return entries;
    }

    public String asString() {
        return id.toString();
    }

    public Set<Item> getMatchingItems() {
        if (!cacheBuilt) buildCache();
        return matchingItemsCache != null ? matchingItemsCache : Collections.emptySet();
    }

    @Override
    public int compareTo(Category other) {
        int orderCompare = Integer.compare(this.order, other.order);
        return orderCompare != 0 ? orderCompare : this.displayName.compareTo(other.displayName);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Category other)) return false;
        return this.id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}