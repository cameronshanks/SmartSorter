package net.shaddii.smartsorter.screen.util;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ItemGridRenderer {
    private static final String[] AMOUNT_CACHE = new String[1000];

    static {
        for (int i = 0; i < 1000; i++) {
            AMOUNT_CACHE[i] = String.valueOf(i);
        }
    }

    private final Font textRenderer;

    // Cache formatted amounts to avoid string operations every frame
    private final Map<Long, String> formattedAmountCache = new HashMap<>();
    private int framesSinceLastCacheClear = 0;

    public ItemGridRenderer(Font textRenderer) {
        this.textRenderer = textRenderer;
    }

    public void renderItems(GuiGraphicsExtractor context,
                            List<Map.Entry<ItemVariant, Long>> items,
                            int startIndex, int endIndex,
                            int gridX, int gridY,
                            int itemsPerRow, int slotSize,
                            int mouseX, int mouseY) {

        for (int i = startIndex; i < endIndex; i++) {
            int relativeIndex = i - startIndex;
            int row = relativeIndex / itemsPerRow;
            int col = relativeIndex % itemsPerRow;

            int slotX = gridX + (col * slotSize);
            int slotY = gridY + (row * slotSize);

            var entry = items.get(i);
            ItemVariant variant = entry.getKey();
            long amount = entry.getValue();

            // Slot background
            context.fill(slotX, slotY, slotX + 16, slotY + 16, 0x8B8B8B8B);

            // Draw item with enchantment glow
            ItemStack stack = variant.toStack();
            context.item(stack, slotX, slotY);

            // Draw amount with cached formatting
            if (amount > 1) {
                String amountText = formattedAmountCache.computeIfAbsent(amount, this::formatAmount);
                float scale = 0.75f;

                int rawWidth = textRenderer.width(amountText);
                float scaledWidth = rawWidth * scale;

                float textX = slotX + 16 - scaledWidth;
                float textY = slotY + 9;

                renderScaledText(context, amountText, textX, textY, scale, 0xFFFFFFFF);
            }

            // Hover highlight
            if (isMouseOverSlot(slotX, slotY, mouseX, mouseY)) {
                context.fill(slotX, slotY, slotX + 16, slotY + 16, 0x80FFFFFF);
            }
        }

        // Clear cache periodically to prevent memory leak
        framesSinceLastCacheClear++;
        if (framesSinceLastCacheClear > 600) { // Every 10 seconds at 60fps
            clearCacheIfNeeded();
            framesSinceLastCacheClear = 0;
        }
    }

    public void renderScrollbar(GuiGraphicsExtractor context,
                                int x, int y,
                                int width, int height,
                                float scrollProgress,
                                boolean enabled) {
        // Scrollbar track
        context.fill(x, y, x + width, y + height, 0xFFC6C6C6);
        context.fill(x, y, x + 1, y + height, 0xFF373737);
        context.fill(x + width - 1, y, x + width, y + height, 0xFFFFFFFF);

        // Scrollbar handle
        if (enabled) {
            int handleHeight = 15;
            int maxHandleOffset = height - handleHeight;
            int handleY = y + (int) (scrollProgress * maxHandleOffset);

            context.fill(x + 1, handleY, x + width - 1, handleY + handleHeight, 0xFF8B8B8B);
            context.fill(x + 1, handleY, x + width - 1, handleY + 1, 0xFFFFFFFF);
        }
    }

    private void renderScaledText(GuiGraphicsExtractor context, String text, float x, float y, float scale, int color) {
        context.pose().pushMatrix();
        context.pose().translate(x, y);
        context.pose().scale(scale, scale);
        context.text(textRenderer, text, 0, 0, color, true);
        context.pose().popMatrix();
    }

    private String formatAmount(long amount) {
        if (amount < 1000) {
            return amount < AMOUNT_CACHE.length ? AMOUNT_CACHE[(int) amount] : String.valueOf(amount);
        } else if (amount >= 1_000_000_000) {
            if (amount >= 10_000_000_000L) {
                return (amount / 1_000_000_000) + "B";
            } else {
                long billions = amount / 1_000_000_000;
                long remainder = (amount % 1_000_000_000) / 100_000_000;
                return remainder > 0 ? billions + "." + remainder + "B" : billions + "B";
            }
        } else if (amount >= 1_000_000) {
            if (amount >= 10_000_000) {
                return (amount / 1_000_000) + "M";
            } else {
                long millions = amount / 1_000_000;
                long remainder = (amount % 1_000_000) / 100_000;
                return remainder > 0 ? millions + "." + remainder + "M" : millions + "M";
            }
        } else {
            if (amount >= 10_000) {
                return (amount / 1000) + "k";
            } else {
                long thousands = amount / 1000;
                long remainder = (amount % 1000) / 100;
                return remainder > 0 ? thousands + "." + remainder + "k" : thousands + "k";
            }
        }
    }

    private void clearCacheIfNeeded() {
        if (formattedAmountCache.size() > 500) {
            formattedAmountCache.clear();
        }
    }

    private boolean isMouseOverSlot(int slotX, int slotY, double mouseX, double mouseY) {
        return mouseX >= slotX && mouseX < slotX + 16 && mouseY >= slotY && mouseY < slotY + 16;
    }
}