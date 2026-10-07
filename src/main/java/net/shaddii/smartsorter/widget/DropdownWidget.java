package net.shaddii.smartsorter.widget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Scrollable dropdown menu widget that opens upwards
 */
public class DropdownWidget extends AbstractWidget {
    private final List<DropdownEntry> entries = new ArrayList<>();
    private boolean isOpen = false;
    private int selectedIndex = 0;
    private Consumer<Integer> onSelect;

    private int hoveredEntryIndex = -1;

    private int scrollOffset = 0; // NEW: Scroll position
    private static final int ENTRY_HEIGHT = 12;
    private static final int MAX_VISIBLE_ENTRIES = 6; // Reduced from 8 for better fit

    public DropdownWidget(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
    }

    public void addEntry(String label, String tooltip) {
        entries.add(new DropdownEntry(label, tooltip));
    }

    public void setSelectedIndex(int index) {
        if (index >= 0 && index < entries.size()) {
            this.selectedIndex = index;
            this.setMessage(Component.literal(entries.get(index).label));

            // Auto-scroll to show selected item when dropdown opens
            if (!isOpen) {
                scrollToShowIndex(index);
            }
        }
    }

    private void scrollToShowIndex(int index) {
        if (index < scrollOffset) {
            scrollOffset = index;
        } else if (index >= scrollOffset + MAX_VISIBLE_ENTRIES) {
            scrollOffset = index - MAX_VISIBLE_ENTRIES + 1;
        }
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setOnSelect(Consumer<Integer> onSelect) {
        this.onSelect = onSelect;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.active || !this.visible) return false;

        boolean insideBase = mouseX >= getX() && mouseX < getX() + width &&
                mouseY >= getY() && mouseY < getY() + height;

        if (isOpen) {
            // Check if clicking in dropdown area
            int dropdownY = getDropdownY();
            int visibleEntries = Math.min(MAX_VISIBLE_ENTRIES, entries.size());
            int dropdownHeight = visibleEntries * ENTRY_HEIGHT;

            if (mouseX >= getX() && mouseX < getX() + width &&
                    mouseY >= dropdownY && mouseY < dropdownY + dropdownHeight) {

                int relativeY = (int) (mouseY - dropdownY);
                int clickedVisibleIndex = relativeY / ENTRY_HEIGHT;
                int clickedActualIndex = scrollOffset + clickedVisibleIndex;

                if (clickedActualIndex >= 0 && clickedActualIndex < entries.size()) {
                    selectEntry(clickedActualIndex);
                    return true;
                }
            }
        }

        if (insideBase) {
            boolean wasOpen = isOpen;
            isOpen = !isOpen;
            if (isOpen && !wasOpen) {
                scrollOffset = 0; // Reset scroll when opening
                scrollToShowIndex(selectedIndex); // Show selected item
            }
            return true;
        }

        return false;
    }

    /**
     * Handle mouse scroll for scrolling through dropdown entries
     */
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!isOpen) return false;

        int dropdownY = getDropdownY();
        int visibleEntries = Math.min(MAX_VISIBLE_ENTRIES, entries.size());
        int dropdownHeight = visibleEntries * ENTRY_HEIGHT;

        // Check if mouse is over dropdown
        if (mouseX >= getX() && mouseX < getX() + width &&
                mouseY >= dropdownY && mouseY < dropdownY + dropdownHeight) {

            int maxScroll = Math.max(0, entries.size() - MAX_VISIBLE_ENTRIES);

            scrollOffset -= (int) verticalAmount;
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));

            return true;
        }

        return false;
    }

    private void selectEntry(int index) {
        this.selectedIndex = index;
        this.setMessage(Component.literal(entries.get(index).label));
        this.isOpen = false;

        if (onSelect != null) {
            onSelect.accept(index);
        }
    }

    /**
     * Determine if dropdown should open upwards or downwards
     */
    private boolean shouldOpenUpwards() {
        Minecraft client = Minecraft.getInstance();
        int screenHeight = client.getWindow().getGuiScaledHeight();

        int visibleEntries = Math.min(MAX_VISIBLE_ENTRIES, entries.size());
        int dropdownHeight = visibleEntries * ENTRY_HEIGHT;

        // Check space above and below
        int spaceAbove = getY();
        int spaceBelow = screenHeight - (getY() + height);

        // If there's more space below, or not enough space above, open downwards
        return spaceAbove >= dropdownHeight && spaceAbove > spaceBelow;
    }

    /**
     * Calculate Y position for dropdown (smart direction)
     */
    public int getDropdownY() {
        int visibleEntries = Math.min(MAX_VISIBLE_ENTRIES, entries.size());
        int dropdownHeight = visibleEntries * ENTRY_HEIGHT;

        if (shouldOpenUpwards()) {
            return getY() - dropdownHeight; // Above the button
        } else {
            return getY() + height; // Below the button
        }
    }

    public List<DropdownEntry> getEntries() {
        return entries;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {
        builder.add(NarratedElementType.TITLE, getMessage());
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        Minecraft client = Minecraft.getInstance();

        // Draw main button
        int buttonColor = this.isHovered() ? 0xFF6B6B6B : 0xFF8B8B8B;
        context.fill(getX(), getY(), getX() + width, getY() + height, buttonColor);

        // Draw button border
        context.fill(getX(), getY(), getX() + width, getY() + 1, 0xFFFFFFFF); // Top
        context.fill(getX(), getY(), getX() + 1, getY() + height, 0xFFFFFFFF); // Left
        context.fill(getX(), getY() + height - 1, getX() + width, getY() + height, 0xFF373737); // Bottom
        context.fill(getX() + width - 1, getY(), getX() + width, getY() + height, 0xFF373737); // Right

        // Draw selected text
        context.text(client.font, getMessage(),
                getX() + 4, getY() + (height - 8) / 2, 0xFFFFFFFF, false);

        // Draw dropdown arrow (changes based on direction)
        String arrow;
        if (isOpen) {
            arrow = shouldOpenUpwards() ? "▲" : "▼";
        } else {
            arrow = "▼";
        }
        context.text(client.font, arrow,
                getX() + width - 12, getY() + (height - 8) / 2, 0xFFFFFFFF, false);
    }

    /**
     * Render the dropdown list (call this separately from the main widget)
     */
    public void renderDropdown(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        if (!isOpen) return;

        Minecraft client = Minecraft.getInstance();

        int dropdownX = getX();
        int dropdownY = getDropdownY();
        int visibleEntries = Math.min(MAX_VISIBLE_ENTRIES, entries.size());
        int dropdownHeight = visibleEntries * ENTRY_HEIGHT;

        // Draw dropdown background
        context.fill(dropdownX, dropdownY, dropdownX + width, dropdownY + dropdownHeight, 0xDD000000);

        hoveredEntryIndex = -1;

        // Draw visible entries
        for (int i = 0; i < visibleEntries; i++) {
            int actualIndex = scrollOffset + i;
            if (actualIndex >= entries.size()) break;

            int entryY = dropdownY + (i * ENTRY_HEIGHT);

            // Highlight hovered entry
            if (mouseX >= dropdownX && mouseX < dropdownX + width &&
                    mouseY >= entryY && mouseY < entryY + ENTRY_HEIGHT) {
                context.fill(dropdownX, entryY, dropdownX + width, entryY + ENTRY_HEIGHT, 0xFF5B5B5B);
                hoveredEntryIndex = actualIndex;
            }

            // Highlight selected entry with green bar
            if (actualIndex == selectedIndex) {
                context.fill(dropdownX, entryY, dropdownX + 2, entryY + ENTRY_HEIGHT, 0xFF55FF55);
            }

            // Draw entry text
            DropdownEntry entry = entries.get(actualIndex);
            context.text(client.font, entry.label,
                    dropdownX + 4, entryY + 2, 0xFFFFFFFF, false);
        }

        // Draw scrollbar if needed
        if (entries.size() > MAX_VISIBLE_ENTRIES) {
            drawScrollbar(context, dropdownX, dropdownY, dropdownHeight);
        }

        // Draw border
        context.fill(dropdownX, dropdownY, dropdownX + width, dropdownY + 1, 0xFFFFFFFF); // Top
        context.fill(dropdownX, dropdownY + dropdownHeight - 1, dropdownX + width, dropdownY + dropdownHeight, 0xFFFFFFFF); // Bottom
        context.fill(dropdownX, dropdownY, dropdownX + 1, dropdownY + dropdownHeight, 0xFFFFFFFF); // Left
        context.fill(dropdownX + width - 1, dropdownY, dropdownX + width, dropdownY + dropdownHeight, 0xFFFFFFFF); // Right
        }

    public int getHoveredEntryIndex() {
        return hoveredEntryIndex;
    }

    /**
     * Render tooltip with item preview when shift is held
     */
    public void renderItemPreviewTooltip(GuiGraphicsExtractor context, int mouseX, int mouseY,
                                         int entryIndex, List<ItemStack> items) {
        if (items == null || items.isEmpty()) return;

        Minecraft client = Minecraft.getInstance();

        // Tooltip dimensions (2 rows × 4 columns)
        int itemsPerRow = 4;
        int rows = 2;
        int slotSize = 18;
        int padding = 4;

        int tooltipWidth = (itemsPerRow * slotSize) + (padding * 2);
        int tooltipHeight = (rows * slotSize) + (padding * 2);

        // Position tooltip to the right of dropdown
        int tooltipX = getX() + width + 4;
        int tooltipY = getDropdownY() + (entryIndex * ENTRY_HEIGHT);

        // Ensure tooltip doesn't go off screen
        if (tooltipX + tooltipWidth > client.getWindow().getGuiScaledWidth()) {
            tooltipX = getX() - tooltipWidth - 4; // Show on left instead
        }

        // Draw background (dark like Sophisticated Backpacks)
        context.fill(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + tooltipHeight, 0xF0100010);

        // Draw border
        context.fill(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + 1, 0xFF5000FF); // Top
        context.fill(tooltipX, tooltipY + tooltipHeight - 1, tooltipX + tooltipWidth, tooltipY + tooltipHeight, 0xFF5000FF); // Bottom
        context.fill(tooltipX, tooltipY, tooltipX + 1, tooltipY + tooltipHeight, 0xFF5000FF); // Left
        context.fill(tooltipX + tooltipWidth - 1, tooltipY, tooltipX + tooltipWidth, tooltipY + tooltipHeight, 0xFF5000FF); // Right

        // Render items in 2×4 grid
        for (int i = 0; i < Math.min(8, items.size()); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) continue;

            int col = i % itemsPerRow;
            int row = i / itemsPerRow;

            int itemX = tooltipX + padding + (col * slotSize) + 1;
            int itemY = tooltipY + padding + (row * slotSize) + 1;

            // Draw slot background
            context.fill(itemX, itemY, itemX + 16, itemY + 16, 0x8B8B8B8B);

            // Draw item
            context.item(stack, itemX, itemY);
                context.itemDecorations(client.font, stack, itemX, itemY);

        }
    }

    public boolean isShiftDown() {
        long handle = Minecraft.getInstance().getWindow().handle();
        return org.lwjgl.glfw.GLFW.glfwGetKey(handle, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS ||
                org.lwjgl.glfw.GLFW.glfwGetKey(handle, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }

    public int getScrollOffset() {
        return scrollOffset;
    }

    private void drawScrollbar(GuiGraphicsExtractor context, int dropdownX, int dropdownY, int dropdownHeight) {
        int scrollbarX = dropdownX + width - 6;
        int scrollbarWidth = 4;

        // Scrollbar track
        context.fill(scrollbarX, dropdownY, scrollbarX + scrollbarWidth, dropdownY + dropdownHeight, 0xFF555555);

        // Scrollbar handle
        int maxScroll = entries.size() - MAX_VISIBLE_ENTRIES;
        float scrollPercentage = (float) scrollOffset / maxScroll;

        int handleHeight = Math.max(10, dropdownHeight * MAX_VISIBLE_ENTRIES / entries.size());
        int handleY = dropdownY + (int) (scrollPercentage * (dropdownHeight - handleHeight));

        context.fill(scrollbarX, handleY, scrollbarX + scrollbarWidth, handleY + handleHeight, 0xFFAAAAAA);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        // Check button area
        if (mouseX >= getX() && mouseX < getX() + width &&
                mouseY >= getY() && mouseY < getY() + height) {
            return true;
        }

        // Check dropdown area if open
        if (isOpen) {
            int dropdownY = getDropdownY();
            int visibleEntries = Math.min(MAX_VISIBLE_ENTRIES, entries.size());
            int dropdownHeight = visibleEntries * ENTRY_HEIGHT;

            return mouseX >= getX() && mouseX < getX() + width &&
                    mouseY >= dropdownY && mouseY < dropdownY + dropdownHeight;
        }

        return false;
    }

    public boolean isOpen() {
        return isOpen;
    }

    public void close() {
        isOpen = false;
    }

    private record DropdownEntry(String label, String tooltip) {
    }

    public void setMaxDisplayedEntries(int max) {
        // Already have MAX_VISIBLE_ENTRIES constant, just for compatibility
        // Can ignore the parameter if max wants a fixed size
    }

    public void clearEntries() {
        entries.clear();
        selectedIndex = 0;

        if (!isOpen) {
            scrollOffset = 0;
        }
    }
}