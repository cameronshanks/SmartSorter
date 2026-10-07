package net.shaddii.smartsorter.widget;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.shaddii.smartsorter.network.ChestConfigUpdatePayload;
import net.shaddii.smartsorter.screen.StorageControllerScreen;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.util.ChestSortMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class ChestSelectorWidget {
    private final int x, y, width, height;
    private final Font textRenderer;
    List<ChestConfig> chests = new ArrayList<>();

    private int selectedIndex = -1;
    private Consumer<ChestConfig> onSelectionChange;
    private Consumer<ChestConfig> onConfigUpdate;

    public DropdownWidget dropdown;
    private ChestSortMode currentSortMode = ChestSortMode.PRIORITY;
    private Map<BlockPos, ChestConfig> chestConfigs = new HashMap<>();

    private Button editButton;
    private Button sortButton;
    private EditBox renameField;
    private boolean isRenaming = false;
    private final StorageControllerScreen parentScreen;

    public ChestSelectorWidget(int x, int y, int width, int height, Font textRenderer, StorageControllerScreen parentScreen) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.textRenderer = textRenderer;
        this.parentScreen = parentScreen;

        int dropdownWidth = width - 25;  // Keep full width minus space for buttons
        this.dropdown = new DropdownWidget(x, y, dropdownWidth, height, Component.literal("Select Chest"));

        // Sort button
        this.sortButton = Button.builder(
                Component.literal("📤"),
                btn -> triggerSort()
        ).bounds(x + dropdownWidth + 2, y - 14, 20, 10).build();

        // Edit/Rename button - below sort button
        this.editButton = Button.builder(
                Component.literal("✏"),
                btn -> startRenaming()
        ).bounds(x + dropdownWidth + 2, y, 20, height).build();


        this.renameField = new EditBox(textRenderer, x, y, dropdownWidth, height, Component.literal(""));
        renameField.setMaxLength(32);
        renameField.setVisible(false);
        renameField.setBordered(true);
    }

    public void reselectChestByPos(BlockPos posToSelect) {
        if (posToSelect == null) {
            if (!this.chests.isEmpty()) {
                this.setSelectedIndex(0);
            }
            return;
        }

        for (int i = 0; i < this.chests.size(); i++) {
            if (this.chests.get(i).position.equals(posToSelect)) {
                this.setSelectedIndex(i);
                if (this.onSelectionChange != null) {
                    this.onSelectionChange.accept(this.chests.get(i));
                }
                return;
            }
        }

        if (!this.chests.isEmpty()) {
            this.setSelectedIndex(0);
        }
    }


    private String getPriorityColor(int priority, int totalChests) {
        if (totalChests == 0) return "§7";

        int topThird = Math.max(1, totalChests / 3);
        int bottomThird = Math.max(1, (totalChests * 2) / 3);

        if (priority <= topThird) {
            return "§a"; // High priority (green)
        } else if (priority <= bottomThird) {
            return "§e"; // Medium priority (yellow)
        } else {
            return "§c"; // Low priority (red)
        }
    }

    private String buildChestDisplayText(ChestConfig config, int totalChests) {
        String displayText = config.customName != null && !config.customName.isEmpty()
                ? config.customName
                : "Chest";

        // Add filter mode indicator
        if (config.filterMode == ChestConfig.FilterMode.CUSTOM) {
            displayText += " §d[Custom]";  // Purple for custom
        } else if (config.filterMode == ChestConfig.FilterMode.CATEGORY ||
                config.filterMode == ChestConfig.FilterMode.CATEGORY_AND_PRIORITY ||
                config.filterMode == ChestConfig.FilterMode.OVERFLOW ||
                config.filterMode == ChestConfig.FilterMode.BLACKLIST) {
            displayText += " §7[" + config.filterCategory.getShortName() + "]";
        }

        // Add priority (CUSTOM chests show as priority 0)
        if (config.filterMode == ChestConfig.FilterMode.CUSTOM) {
            displayText += " §d0";  // Purple 0 for custom
        } else {
            displayText += " " + getPriorityColor(config.priority, totalChests) + config.priority;
        }

        // Fullness
        int fullness = config.cachedFullness;
        if (fullness >= 0) {
            String fullnessColor;
            if (fullness >= 90) {
                fullnessColor = "§c";
            } else if (fullness >= 70) {
                fullnessColor = "§e";
            } else {
                fullnessColor = "§a";
            }
            displayText += " " + fullnessColor + fullness + "%";
        } else {
            displayText += " §8--%";
        }

        return displayText;
    }

    private void triggerSort() {
        ChestConfig selected = getSelectedChest();
        if (selected == null) return;

        if (selected.filterMode == ChestConfig.FilterMode.CUSTOM) {
            return;
        }

        ((StorageControllerScreen) this.parentScreen).handleSortThisChest(selected.position);


        if (onConfigUpdate != null) {
            onConfigUpdate.accept(selected);
        }
    }


    public void setSortMode(ChestSortMode mode) {
        this.currentSortMode = mode;
        updateChests(chestConfigs);
    }

    public ChestSortMode getSortMode() {
        return currentSortMode;
    }

    public void updateChests(Map<BlockPos, ChestConfig> configs) {
        this.chestConfigs = configs;
        chests.clear();
        dropdown.clearEntries();

        // Don't filter out CUSTOM chests - include ALL chests
        List<ChestConfig> sortedChests = new ArrayList<>(configs.values());

        // Sort based on current mode
        sortedChests.sort((a, b) -> {
            // CUSTOM chests always go first regardless of sort mode
            if (a.filterMode == ChestConfig.FilterMode.CUSTOM && b.filterMode != ChestConfig.FilterMode.CUSTOM) {
                return -1;
            }
            if (b.filterMode == ChestConfig.FilterMode.CUSTOM && a.filterMode != ChestConfig.FilterMode.CUSTOM) {
                return 1;
            }

            // Both are CUSTOM or both are not CUSTOM
            if (currentSortMode == ChestSortMode.FULLNESS) {
                // Primary: Sort by fullness (descending - most full first)
                int fullnessCompare = Integer.compare(b.cachedFullness, a.cachedFullness);
                if (fullnessCompare != 0) {
                    return fullnessCompare;
                }
                // Secondary: If same fullness, sort by name
                return a.getSortKey(ChestSortMode.NAME).compareTo(b.getSortKey(ChestSortMode.NAME));
            }

            if (currentSortMode == ChestSortMode.PRIORITY) {
                // CUSTOM chests are always priority 0
                int aPriority = a.filterMode == ChestConfig.FilterMode.CUSTOM ? 0 : a.priority;
                int bPriority = b.filterMode == ChestConfig.FilterMode.CUSTOM ? 0 : b.priority;

                if (aPriority != bPriority) {
                    return Integer.compare(aPriority, bPriority);
                }
                // Same priority - sort by name
                return a.getSortKey(ChestSortMode.NAME).compareTo(b.getSortKey(ChestSortMode.NAME));
            }

            // For other modes: TWO-TIER SORTING (filtered first, then by mode)
            boolean aIsFiltered = a.filterMode != ChestConfig.FilterMode.NONE &&
                    a.filterMode != ChestConfig.FilterMode.CUSTOM;
            boolean bIsFiltered = b.filterMode != ChestConfig.FilterMode.NONE &&
                    b.filterMode != ChestConfig.FilterMode.CUSTOM;

            if (aIsFiltered != bIsFiltered) {
                return aIsFiltered ? -1 : 1;
            }

            String keyA = a.getSortKey(currentSortMode);
            String keyB = b.getSortKey(currentSortMode);
            return keyA.compareTo(keyB);
        });

        int totalChests = sortedChests.size();

        for (ChestConfig config : sortedChests) {
            chests.add(config);
            String displayText = buildChestDisplayText(config, totalChests);
            dropdown.addEntry(displayText, displayText);
        }

        if (!chests.isEmpty() && selectedIndex < 0) {
            selectedIndex = 0;
            dropdown.setSelectedIndex(0);
            notifySelectionChange();
        }
    }

    public void setOnSelectionChange(Consumer<ChestConfig> callback) {
        this.onSelectionChange = callback;
        dropdown.setOnSelect(index -> {
            selectedIndex = index;
            notifySelectionChange();
        });
    }

    public void setOnConfigUpdate(Consumer<ChestConfig> callback) {
        this.onConfigUpdate = callback;
    }

    private void startRenaming() {
        if (chests.isEmpty() || isRenaming) return;

        isRenaming = true;
        ChestConfig config = chests.get(selectedIndex);

        renameField.setValue(config.customName != null && !config.customName.isEmpty() ? config.customName : "");
        renameField.setVisible(true);
        renameField.setFocused(true);
        dropdown.visible = false;
    }

    private void finishRenaming() {
        if (!isRenaming) return;

        isRenaming = false;
        String newName = renameField.getValue().trim();
        ChestConfig config = chests.get(selectedIndex);

        config.customName = newName.isEmpty() ? "" : newName;
        ClientPlayNetworking.send(new ChestConfigUpdatePayload(config));

        if (onConfigUpdate != null) {
            onConfigUpdate.accept(config);
        }

        renameField.setVisible(false);
        renameField.setFocused(false);
        dropdown.visible = true;
    }

    private void notifySelectionChange() {
        if (onSelectionChange != null) {
            ChestConfig selected = getSelectedChest();
            onSelectionChange.accept(selected);
        }
    }

    public ChestConfig getSelectedChest() {
        if (selectedIndex >= 0 && selectedIndex < chests.size()) {
            return chests.get(selectedIndex);
        }
        return null;
    }

    public void setSelectedIndex(int index) {
        if (index >= 0 && index < chests.size()) {
            selectedIndex = index;
            dropdown.setSelectedIndex(index);
            notifySelectionChange();
        }
    }

    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Only show sort button if selected chest is NOT custom mode
        ChestConfig selected = getSelectedChest();
        if (selected != null && selected.filterMode != ChestConfig.FilterMode.CUSTOM) {
            sortButton.extractRenderState(context, mouseX, mouseY, delta);
        }

        if (isRenaming) {
            renameField.extractRenderState(context, mouseX, mouseY, delta);
        } else {
            dropdown.extractRenderState(context, mouseX, mouseY, delta);
        }
        editButton.extractRenderState(context, mouseX, mouseY, delta);
    }

    public void renderDropdownIfOpen(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        if (dropdown.isOpen()) {
            dropdown.renderDropdown(context, mouseX, mouseY);

            if (dropdown.isShiftDown()) {
                int hoveredIndex = dropdown.getHoveredEntryIndex();
                if (hoveredIndex >= 0 && hoveredIndex < chests.size()) {
                    ChestConfig hoveredChest = chests.get(hoveredIndex);
                    if (hoveredChest != null && !hoveredChest.previewItems.isEmpty()) {
                        dropdown.renderItemPreviewTooltip(context, mouseX, mouseY,
                                hoveredIndex - dropdown.getScrollOffset(), // Visible index
                                hoveredChest.previewItems);
                    }
                }
            }
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        MouseButtonInfo mouseInput = new MouseButtonInfo(button, 0);
        MouseButtonEvent click = new MouseButtonEvent(mouseX, mouseY, mouseInput);

        // Only handle sort button if chest is not CUSTOM mode
        ChestConfig selected = getSelectedChest();
        if (selected != null && selected.filterMode != ChestConfig.FilterMode.CUSTOM) {
            if (sortButton.mouseClicked(click, false)) {
                return true;
            }
        }

        if (isRenaming) {
            if (renameField.mouseClicked(click, false)) {
                return true;
            }
            finishRenaming();
            return true;
        }

        if (editButton.mouseClicked(click, false)) {
            return true;
        }

        if (dropdown.mouseClicked(mouseX, mouseY, button)) {
            selectedIndex = dropdown.getSelectedIndex();
            notifySelectionChange();
            return true;
        }

        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return dropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isRenaming) {
            KeyEvent input = new KeyEvent(keyCode, scanCode, modifiers);

            if (keyCode == 257 || keyCode == 335) { // Enter
                finishRenaming();
                return true;
            }
            if (keyCode == 256) { // Escape
                renameField.setValue("");
                finishRenaming();
                return true;
            }
            return renameField.keyPressed(input);
        }

        KeyEvent input = new KeyEvent(keyCode, scanCode, modifiers);
        return dropdown.keyPressed(input);
    }

    public boolean charTyped(char chr, int modifiers) {
        if (isRenaming) {
            CharacterEvent input = new CharacterEvent(chr);
            return renameField.charTyped(input);
        }

        CharacterEvent input = new CharacterEvent(chr);
        return dropdown.charTyped(input);
    }

    public boolean isDropdownOpen() {
        return dropdown.isOpen();
    }

    public boolean isCurrentlyRenaming() {
        return isRenaming;
    }
}