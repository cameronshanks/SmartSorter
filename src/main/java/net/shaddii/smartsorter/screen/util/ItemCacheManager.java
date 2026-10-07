package net.shaddii.smartsorter.screen.util;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.shaddii.smartsorter.util.Category;
import net.shaddii.smartsorter.util.CategoryManager;
import net.shaddii.smartsorter.util.SortMode;

import java.util.*;

public class ItemCacheManager {
    private List<Map.Entry<ItemVariant, Long>> cachedFilteredList = null;
    private List<Map.Entry<ItemVariant, Long>> cachedUnfilteredList = null;
    private String lastSearchTerm = "";
    private SortMode lastSortMode = SortMode.NAME;
    private Category lastFilterCategory = Category.ALL;
    private int lastItemCount = 0;

    // Category cache to avoid repeated lookups
    private final Map<ItemVariant, Category> categoryCache = new HashMap<>();
    private final CategoryManager categoryManager = CategoryManager.getInstance();

    public List<Map.Entry<ItemVariant, Long>> getFilteredItems(
            Map<ItemVariant, Long> items,
            String search,
            SortMode sortMode,
            Category category) {

        // Cheaper checks first
        int itemCount = items.size();
        boolean searchChanged = !search.equals(lastSearchTerm);
        boolean sortChanged = sortMode != lastSortMode;
        boolean categoryChanged = !category.equals(lastFilterCategory);
        boolean itemsChanged = itemCount != lastItemCount;

        // Fast path: nothing changed
        if (cachedFilteredList != null && !searchChanged && !sortChanged && !categoryChanged && !itemsChanged) {
            return cachedFilteredList;
        }

        // Incremental update for search changes only (typing in search box)
        if (cachedFilteredList != null && !sortChanged && !categoryChanged && !itemsChanged && searchChanged) {
            List<Map.Entry<ItemVariant, Long>> result = filterBySearch(cachedUnfilteredList, search);
            cachedFilteredList = result;
            lastSearchTerm = search;
            return result;
        }

        // Full rebuild
        List<Map.Entry<ItemVariant, Long>> result = new ArrayList<>(items.entrySet());

        // Filter by category first (most selective)
        if (category != Category.ALL) {
            result = filterByCategory(result, category);
        }

        // Store unfiltered (by search) list for incremental updates
        cachedUnfilteredList = new ArrayList<>(result);

        // Filter by search
        if (!search.isEmpty()) {
            result = filterBySearch(result, search);
        }

        // Sort
        sortList(result, sortMode);

        // Update cache
        cachedFilteredList = result;
        lastItemCount = itemCount;
        lastSearchTerm = search;
        lastSortMode = sortMode;
        lastFilterCategory = category;

        return result;
    }

    private List<Map.Entry<ItemVariant, Long>> filterByCategory(List<Map.Entry<ItemVariant, Long>> list, Category category) {
        List<Map.Entry<ItemVariant, Long>> result = new ArrayList<>(list.size());

        for (var entry : list) {
            Category itemCategory = categoryCache.computeIfAbsent(
                    entry.getKey(),
                    variant -> categoryManager.categorize(variant.getItem())
            );

            if (itemCategory.equals(category)) {
                result.add(entry);
            }
        }

        return result;
    }

    private List<Map.Entry<ItemVariant, Long>> filterBySearch(List<Map.Entry<ItemVariant, Long>> list, String search) {
        if (search.isEmpty()) {
            return list;
        }

        String lowerSearch = search.toLowerCase();
        List<Map.Entry<ItemVariant, Long>> result = new ArrayList<>(list.size());

        for (var entry : list) {
            String itemName = entry.getKey().getItem().components().getOrDefault(net.minecraft.core.component.DataComponents.ITEM_NAME, net.minecraft.network.chat.CommonComponents.EMPTY).getString().toLowerCase();
            if (itemName.contains(lowerSearch)) {
                result.add(entry);
            }
        }

        return result;
    }

    private void sortList(List<Map.Entry<ItemVariant, Long>> list, SortMode sortMode) {
        switch (sortMode) {
            case NAME -> list.sort((a, b) -> {
                String nameA = a.getKey().getItem().components().getOrDefault(net.minecraft.core.component.DataComponents.ITEM_NAME, net.minecraft.network.chat.CommonComponents.EMPTY).getString();
                String nameB = b.getKey().getItem().components().getOrDefault(net.minecraft.core.component.DataComponents.ITEM_NAME, net.minecraft.network.chat.CommonComponents.EMPTY).getString();
                return nameA.compareTo(nameB);
            });
            case COUNT -> list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        }
    }

    public void invalidate() {
        cachedFilteredList = null;
        cachedUnfilteredList = null;
        // Keep category cache as items don't change categories
    }

    public void clearAll() {
        cachedFilteredList = null;
        cachedUnfilteredList = null;
        categoryCache.clear();
    }
}