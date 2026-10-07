package net.shaddii.smartsorter.screen.util;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class TooltipRenderer {
    private static final int MAX_CACHE_SIZE = 100;

    private final Font textRenderer;
    private final Minecraft client;

    // LRU cache for multiple tooltips
    private final Map<TooltipKey, List<Component>> tooltipCache = new LinkedHashMap<TooltipKey, List<Component>>(MAX_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<TooltipKey, List<Component>> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    public TooltipRenderer(Font textRenderer) {
        this.textRenderer = textRenderer;
        this.client = Minecraft.getInstance();
    }

    public List<Component> createItemTooltip(ItemVariant variant, long amount) {
        TooltipKey key = new TooltipKey(variant, amount);

        // Check cache
        List<Component> cached = tooltipCache.get(key);
        if (cached != null) {
            return cached;
        }

        // Generate tooltip (expensive operation)
        List<Component> tooltip = generateTooltip(variant, amount);

        // Cache it
        tooltipCache.put(key, tooltip);

        return tooltip;
    }

    private List<Component> generateTooltip(ItemVariant variant, long amount) {
        List<Component> tooltip = new ArrayList<>();
        ItemStack stack = variant.toStack();

        // Use version-specific code to get the full tooltip
        try {
            TooltipFlag tooltipType = client != null && client.options.advancedItemTooltips ?
                    TooltipFlag.ADVANCED : TooltipFlag.NORMAL;

            List<Component> vanillaTooltip = stack.getTooltipLines(
                    Item.TooltipContext.EMPTY,
                    client != null ? client.player : null,
                    tooltipType
            );
            tooltip.addAll(vanillaTooltip);
        } catch (Exception e) {
            tooltip.add(stack.getHoverName());
        }

        // Add storage amount
        tooltip.add(Component.literal("")); // Empty line
        tooltip.add(Component.literal("§7Stored: §f" + String.format("%,d", amount)));

        // Add controls
        tooltip.add(Component.literal(""));
        tooltip.add(Component.literal("§8Left-Click: §7Take stack (64)"));
        tooltip.add(Component.literal("§8Right-Click: §7Take half (32)"));
        tooltip.add(Component.literal("§8Ctrl+Left: §7Take quarter (16)"));
        tooltip.add(Component.literal("§8Shift-Click: §7To inventory"));

        return tooltip;
    }

    public void invalidateCache() {
        tooltipCache.clear();
    }

    private String toRoman(int number) {
        if (number <= 0 || number > 10) return String.valueOf(number);

        String[] romans = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return romans[Math.min(number, 10)];
    }

    // Record for cache key with proper equals/hashCode
    private static final class TooltipKey {
        private final ItemVariant variant;
        private final long amount;
        private final int hashCode;

        TooltipKey(ItemVariant variant, long amount) {
            this.variant = variant;
            this.amount = amount;
            this.hashCode = Objects.hash(variant, amount);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TooltipKey)) return false;
            TooltipKey that = (TooltipKey) o;
            return amount == that.amount && variant.equals(that.variant);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}