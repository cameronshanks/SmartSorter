package net.shaddii.smartsorter.client;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2f;

import java.util.*;

public class OverflowNotificationOverlay {
    private static final List<OverflowEntry> entries = new ArrayList<>();
    private static final long DISPLAY_DURATION = 8000;
    private static final long FADE_DURATION = 1000;
    private static final int MAX_VISIBLE = 6;
    private static final int ENTRY_HEIGHT = 18;        // Reduced from 22
    private static final int PANEL_WIDTH = 100;        // Reduced from 200
    private static final int PADDING = 4;              // Reduced from 6
    private static final float TEXT_SCALE = 0.65f;     // Reduced from 0.75f
    private static final int ICON_SIZE = 16;
    private static final int PANEL_Y_OFFSET = 10;

    private static float scrollOffset = 0;
    private static final float SCROLL_SPEED = 20f;

    private static long lastScrollTime = 0;
    private static final long SCROLL_PAUSE_DURATION = 3000;

    private static class OverflowEntry {
        final ItemVariant variant;
        final long amount;
        final String destinationName;
        long creationTime;

        OverflowEntry(ItemVariant variant, long amount, String destinationName) {
            this.variant = variant;
            this.amount = amount;
            this.destinationName = destinationName;
            this.creationTime = System.currentTimeMillis();
        }

        float getAlpha() {
            long elapsed = System.currentTimeMillis() - creationTime;
            if (elapsed < DISPLAY_DURATION - FADE_DURATION) {
                return 1.0f;
            }
            float fadeProgress = (elapsed - (DISPLAY_DURATION - FADE_DURATION)) / (float) FADE_DURATION;
            return Math.max(0, 1.0f - fadeProgress);
        }

        boolean shouldRemove() {
            long elapsed = System.currentTimeMillis() - creationTime;
            return elapsed > DISPLAY_DURATION;
        }

        void extendLifetime(long milliseconds) {
            creationTime = Math.min(
                    creationTime + milliseconds,
                    System.currentTimeMillis()
            );
        }
    }

    public static void show(Map<ItemVariant, Long> overflowItems, Map<ItemVariant, String> destinations) {
        for (Map.Entry<ItemVariant, Long> entry : overflowItems.entrySet()) {
            String destName = destinations.getOrDefault(entry.getKey(), "Overflow Storage");
            addOverflowNotification(entry.getKey(), entry.getValue(), destName);
        }
    }

    public static void addOverflowNotification(ItemVariant variant, long amount, String destinationName) {
        for (OverflowEntry entry : entries) {
            if (entry.variant.equals(variant)) {
                entries.remove(entry);
                break;
            }
        }
        entries.add(0, new OverflowEntry(variant, amount, destinationName));
    }

    public static boolean isActive() {
        return !entries.isEmpty();
    }

    public static void dismiss() {
        entries.clear();
        scrollOffset = 0;
    }

        public static void scroll(int direction) {
            if (entries.size() <= MAX_VISIBLE) return;
            scrollOffset = Math.max(0, Math.min(entries.size() - MAX_VISIBLE, scrollOffset + direction));

            for (OverflowEntry entry : entries) {
                entry.extendLifetime(200); // Extend by 200ms per scroll action
            }
            lastScrollTime = System.currentTimeMillis();
        }

        public static void render(GuiGraphicsExtractor context, float tickDelta) {
        if (entries.isEmpty()) return;

        Minecraft client = Minecraft.getInstance();
        Font textRenderer = client.font;
        int screenWidth = client.getWindow().getGuiScaledWidth();

        entries.removeIf(OverflowEntry::shouldRemove);

        int visibleCount = Math.min(entries.size(), MAX_VISIBLE);
        int panelHeight = (visibleCount * ENTRY_HEIGHT) + (PADDING * 2);

        int panelX = screenWidth - PANEL_WIDTH - 10;
        int panelY = PANEL_Y_OFFSET;

        float panelAlpha = 1.0f;
        if (!entries.isEmpty()) {
            panelAlpha = entries.get(0).getAlpha(); // Use the first (newest) entry's alpha
        }

        // Apply alpha to background
        int alphaInt = (int) (panelAlpha * 0xD0) << 24; // 0xD0 = base alpha (208)
        int bgColor = alphaInt | 0x000000;
        context.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, bgColor);

        // Apply alpha to borders
        int borderAlpha = (int) (panelAlpha * 255) << 24;
        int borderColor = borderAlpha | 0x555555;
        context.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + 1, borderColor);
        context.fill(panelX, panelY + panelHeight - 1, panelX + PANEL_WIDTH, panelY + panelHeight, borderColor);
        context.fill(panelX, panelY, panelX + 1, panelY + panelHeight, borderColor);
        context.fill(panelX + PANEL_WIDTH - 1, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, borderColor);

        // Also apply to scrollbar
        if (entries.size() > MAX_VISIBLE) {
            int scrollBarHeight = Math.max(10, (MAX_VISIBLE * panelHeight) / entries.size());
            int scrollBarY = panelY + (int) ((scrollOffset / entries.size()) * panelHeight);
            int scrollBarX = panelX + PANEL_WIDTH - 4;

            int scrollbarColor = borderAlpha | 0xAAAAAA;
            context.fill(scrollBarX, scrollBarY, scrollBarX + 2, scrollBarY + scrollBarHeight, scrollbarColor);
        }


        int startIndex = (int) scrollOffset;
        int endIndex = Math.min(startIndex + MAX_VISIBLE, entries.size());

        for (int i = startIndex; i < endIndex; i++) {
            OverflowEntry entry = entries.get(i);
            int entryY = panelY + PADDING + ((i - startIndex) * ENTRY_HEIGHT);

            float alpha = entry.getAlpha();
            if (alpha <= 0) continue;

            renderEntry(context, textRenderer, entry, panelX + PADDING, entryY, PANEL_WIDTH - (PADDING * 2), alpha);
        }
    }

    private static void renderEntry(GuiGraphicsExtractor context, Font textRenderer, OverflowEntry entry,
                                    int x, int y, int width, float alpha) {
        int alphaInt = (int) (alpha * 255);
        int textColor = (alphaInt << 24) | 0xFFFFFF;
        int amountColor = (alphaInt << 24) | 0xFFAA00;
        int destColor = (alphaInt << 24) | 0x55FF55;

        ItemStack stack = entry.variant.toStack();

        context.pose().pushMatrix();
        context.pose().translate(new org.joml.Vector2f(x, y));
        context.item(stack, 0, 0);
        context.pose().popMatrix();

        int textX = x + ICON_SIZE + 3;
        int textY = y;

        // Item name (top line, very compact)
        String itemName = entry.variant.getItem().components().getOrDefault(net.minecraft.core.component.DataComponents.ITEM_NAME, net.minecraft.network.chat.CommonComponents.EMPTY).getString();
        if (itemName.length() > 18) {
            itemName = itemName.substring(0, 15) + "...";
        }

        drawScaledText(context, textRenderer, itemName, textX, textY, textColor, TEXT_SCALE);

        // Amount and destination on same line (bottom)
        String amountText = String.format("%,d", entry.amount);
        String destText = entry.destinationName;
        if (destText.length() > 14) {
            destText = destText.substring(0, 11) + "...";
        }

        String bottomLine = "§6" + amountText + " §7→ §a" + destText;
        drawScaledText(context, textRenderer, bottomLine, textX, textY + 8, amountColor, TEXT_SCALE * 0.85f);
    }

    private static void drawScaledText(GuiGraphicsExtractor context, Font textRenderer, String text,
                                       int x, int y, int color, float scale) {
        Matrix3x2f oldMatrix = new Matrix3x2f(context.pose());
        Matrix3x2f scaleMatrix = new Matrix3x2f().scaling(scale, scale);
        context.pose().mul(scaleMatrix);
        Matrix3x2f translateMatrix = new Matrix3x2f().translation(x / scale, y / scale);
        context.pose().mul(translateMatrix);
        context.text(textRenderer, text, 0, 0, color, false);
        context.pose().set(oldMatrix);
    }

    public static boolean handleMouseScroll(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (entries.isEmpty() || entries.size() <= MAX_VISIBLE) return false;

        Minecraft client = Minecraft.getInstance();
        int screenWidth = client.getWindow().getGuiScaledWidth();
        int panelX = screenWidth - PANEL_WIDTH - 10;
        int panelY = PANEL_Y_OFFSET;
        int visibleCount = Math.min(entries.size(), MAX_VISIBLE);
        int panelHeight = (visibleCount * ENTRY_HEIGHT) + (PADDING * 2);

        boolean isHovering = mouseX >= panelX && mouseX < panelX + PANEL_WIDTH &&
                mouseY >= panelY && mouseY < panelY + panelHeight;

        if (isHovering) {
            scrollOffset = Math.max(0, Math.min(entries.size() - MAX_VISIBLE,
                    scrollOffset - (float) (verticalAmount * SCROLL_SPEED / 100.0)));

            for (OverflowEntry entry : entries) {
                entry.extendLifetime(200);
            }
            lastScrollTime = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    public static boolean handleMouseClick(double mouseX, double mouseY, int button) {
        if (entries.isEmpty()) return false;

        Minecraft client = Minecraft.getInstance();
        int screenWidth = client.getWindow().getGuiScaledWidth();
        int panelX = screenWidth - PANEL_WIDTH - 10;
        int panelY = PANEL_Y_OFFSET;
        int visibleCount = Math.min(entries.size(), MAX_VISIBLE);
        int panelHeight = (visibleCount * ENTRY_HEIGHT) + (PADDING * 2);

        boolean isHovering = mouseX >= panelX && mouseX < panelX + PANEL_WIDTH &&
                mouseY >= panelY && mouseY < panelY + panelHeight;

        if (isHovering && button == 2) {
            long timeSinceScroll = System.currentTimeMillis() - lastScrollTime;

            // Only dismiss if user hasn't scrolled recently
            if (timeSinceScroll > 500) { // 500ms grace period
                entries.clear();
                scrollOffset = 0;
                lastScrollTime = 0; // Reset scroll timer
                return true;
            }
            return true; // Still consume the click, but don't dismiss
        }


        return isHovering;
    }

    public static void clear() {
        entries.clear();
        scrollOffset = 0;
    }
}