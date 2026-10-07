package net.shaddii.smartsorter.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public class SortProgressOverlay {
    private static int currentProgress = 0;
    private static int totalChests = 0;
    private static boolean isActive = false;
    private static long lastUpdateTime = 0;
    private static final long FADE_DELAY = 2000; // 2 seconds after completion
    private static final int PROGRESS_Y_OFFSET = 140;

    public static void updateProgress(int current, int total, boolean complete) {
        currentProgress = current;
        totalChests = total;
        isActive = !complete || System.currentTimeMillis() - lastUpdateTime < FADE_DELAY;
        lastUpdateTime = System.currentTimeMillis();

        if (complete) {
            isActive = true; // Will fade after FADE_DELAY
        }
    }

    public static void render(GuiGraphicsExtractor context) {
        if (!isActive || totalChests == 0) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        int screenWidth = client.getWindow().getGuiScaledWidth();

        // ========================================
        // ADJUSTABLE SETTINGS
        // ========================================
        int barWidth = 100;          // Width of the progress bar
        int barHeight = 5;           // Height of the progress bar
        int xOffset = 10;            // Distance from right edge of screen
        int yOffset = 10;            // Distance from top of screen
        float textScale = 0.75f;     // Text size (0.5 = half size, 1.0 = normal, 1.5 = 1.5x bigger)
        int textGap = 2;             // Gap between bar and text

        // ========================================
        // POSITION CALCULATION
        // ========================================
        int x = screenWidth - barWidth - xOffset;
        int y = PROGRESS_Y_OFFSET;

        // Calculate alpha for fade effect
        float alpha = 1.0f;
        if (currentProgress >= totalChests) {
            long timeSinceComplete = System.currentTimeMillis() - lastUpdateTime;
            if (timeSinceComplete > FADE_DELAY - 500) {
                alpha = Math.max(0, 1.0f - (timeSinceComplete - (FADE_DELAY - 500)) / 500f);
            }
        }

        if (alpha <= 0) {
            isActive = false;
            return;
        }

        int alphaInt = (int)(alpha * 255) << 24;

        // ========================================
        // DRAW PROGRESS BAR
        // ========================================
        float progressPercent = (float) currentProgress / totalChests;
        int progressWidth = (int)(barWidth * progressPercent);

        // Bar background (dark gray)
        context.fill(x, y, x + barWidth, y + barHeight, alphaInt | 0x3A3A3A);

        // Progress fill
        int barColor = currentProgress >= totalChests ? 0x55FF55 : 0xFFAA00;
        context.fill(x, y, x + progressWidth, y + barHeight, alphaInt | barColor);

        // Bar border (optional - comment out if you don't want it)
        context.fill(x, y, x + barWidth, y + 1, alphaInt | 0x000000); // Top
        context.fill(x, y + barHeight - 1, x + barWidth, y + barHeight, alphaInt | 0x000000); // Bottom

        // ========================================
        // DRAW STATUS TEXT (below bar)
        // ========================================
        String statusText = currentProgress >= totalChests
                ? "✓ Sorted " + totalChests + " chests"
                : "Sorting " + currentProgress + "/" + totalChests;

        int fullTextWidth = client.font.width(statusText);
        int scaledTextWidth = (int)(fullTextWidth * textScale);
        int textX = x + (barWidth - scaledTextWidth) / 2;
        int textY = y + barHeight + textGap;

        context.pose().pushMatrix();
        context.pose().translate(textX, textY);
        context.pose().scale(textScale, textScale);
        context.text(client.font, Component.literal(statusText), 0, 0, 0xFFFFFFFF | alphaInt, true);
        context.pose().popMatrix();
    }
}