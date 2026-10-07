package net.shaddii.smartsorter.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.shaddii.smartsorter.network.CategorySyncPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class CategoryManager extends SimpleJsonResourceReloadListener<JsonElement> {
    private static final Logger LOGGER = LoggerFactory.getLogger("smartsorter");
    private static CategoryManager INSTANCE;


    private final List<Category> sortedCategories = new ArrayList<>();
    private final Map<Item, Category> itemCategoryIndex = new HashMap<>();

    // --- START OF CHANGES ---
    private boolean indexIsBuilt = false; // <-- ADD THIS FLAG

    public CategoryManager() {
        super(Codec.PASSTHROUGH.xmap(
                dynamic -> dynamic.convert(JsonOps.INSTANCE).getValue(),
                jsonElement -> new com.mojang.serialization.Dynamic<>(JsonOps.INSTANCE, jsonElement)
        ), FileToIdConverter.json("smartsorter/categories"));
    }

    public static CategoryManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CategoryManager();
        }
        return INSTANCE;
    }


    @Override
    protected void apply(Map<Identifier, JsonElement> prepared, ResourceManager manager, ProfilerFiller profiler) {
        sortedCategories.clear();
        itemCategoryIndex.clear();
        indexIsBuilt = false; // <-- RESET THE FLAG ON RELOAD

        sortedCategories.add(Category.ALL);

        for (Map.Entry<Identifier, JsonElement> entry : prepared.entrySet()) {
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                Identifier id = Identifier.parse(obj.get("id").getAsString());
                String displayName = obj.get("display_name").getAsString();
                String shortName = obj.has("short_name") ? obj.get("short_name").getAsString() : displayName;
                int order = obj.get("order").getAsInt();
                Category category = new Category(id, displayName, shortName, order);

                if (obj.has("items")) {
                    obj.getAsJsonArray("items").forEach(element -> category.addEntry(element.getAsString()));
                }
                sortedCategories.add(category);
            } catch (Exception e) {
                LOGGER.error("Failed to load category from {}: {}", entry.getKey(), e.getMessage());
            }
        }

        sortedCategories.add(Category.MISC);
        Collections.sort(sortedCategories);

        // DO NOT BUILD THE INDEX HERE. THIS IS THE CRITICAL CHANGE.
        // buildItemIndex(); // <-- DELETE OR COMMENT OUT THIS LINE

        LOGGER.info("Smart Sorter: Loaded {} categories. Index will be built on first use.", sortedCategories.size());
    }

    private void buildItemIndex() {
        // If the index is already built, do nothing.
        if (indexIsBuilt) {
            return;
        }

        LOGGER.info("Building Smart Sorter item index for the first time...");
        itemCategoryIndex.clear();

        // Pre-build the caches for all categories at once.
        for (Category category : sortedCategories) {
            category.buildCache();
        }

        for (Item item : BuiltInRegistries.ITEM) {
            boolean foundMatch = false;
            for (Category category : sortedCategories) {
                if (category == Category.ALL || category == Category.MISC) {
                    continue;
                }

                if (category.matches(item)) {
                    itemCategoryIndex.put(item, category);
                    foundMatch = true;
                    break;
                }
            }

            if (!foundMatch) {
                itemCategoryIndex.put(item, Category.MISC);
            }
        }

        indexIsBuilt = true; // Mark the index as complete.
        LOGGER.info("Smart Sorter index built, {} items categorized.", itemCategoryIndex.size());
    }

    public Category categorize(Item item) {
        buildItemIndex();
        return itemCategoryIndex.getOrDefault(item, Category.MISC);
    }


    public List<Category> getAllCategories() {
        return new ArrayList<>(sortedCategories);
    }

    public Category getCategory(Identifier id) {
        return sortedCategories.stream()
                .filter(c -> c.getId().equals(id))
                .findFirst()
                .orElse(Category.MISC);
    }

    public Category getCategory(String idString) {
        Identifier id = Identifier.tryParse(idString);
        return id != null ? getCategory(id) : Category.MISC;
    }

    public void updateFromServer(List<CategorySyncPayload.CategoryData> serverCategories) {
        LOGGER.info("Updating categories from server, received {} categories", serverCategories.size());

        sortedCategories.clear();
        itemCategoryIndex.clear();
        indexIsBuilt = false;

        // Always add ALL first
        sortedCategories.add(Category.ALL);

        // Add server categories WITH their item patterns
        for (CategorySyncPayload.CategoryData data : serverCategories) {
            try {
                Identifier id = Identifier.tryParse(data.id());
                if (id != null && !id.equals(Category.ALL.getId()) && !id.equals(Category.MISC.getId())) {
                    Category category = new Category(id, data.displayName(), data.shortName(), data.order());

                    // Add all the item patterns to the category
                    LOGGER.debug("Loading category {}: {} items", data.displayName(), data.items().size());
                    for (String itemEntry : data.items()) {
                        category.addEntry(itemEntry);
                    }
                    sortedCategories.add(category);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load category {}: {}", data.id(), e.getMessage());
            }
        }

        // Always add MISC last
        sortedCategories.add(Category.MISC);
        Collections.sort(sortedCategories);

        LOGGER.info("Updated categories from server: {} categories loaded", sortedCategories.size());
    }

    public List<CategorySyncPayload.CategoryData> getCategoryData() {
        List<CategorySyncPayload.CategoryData> data = new ArrayList<>();
        for (Category cat : sortedCategories) {
            if (cat != Category.ALL && cat != Category.MISC) {
                data.add(new CategorySyncPayload.CategoryData(
                        cat.getId().toString(),
                        cat.getDisplayName(),
                        cat.getShortName(),
                        cat.getOrder(),
                        new ArrayList<>(cat.getEntries())
                ));
            }
        }
        return data;
    }
}