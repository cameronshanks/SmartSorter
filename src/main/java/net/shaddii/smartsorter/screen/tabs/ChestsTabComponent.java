package net.shaddii.smartsorter.screen.tabs;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.shaddii.smartsorter.network.SortChestsPayload;
import net.shaddii.smartsorter.screen.StorageControllerScreen;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.util.ChestSortMode;
import net.shaddii.smartsorter.widget.ChestConfigPanel;
import net.shaddii.smartsorter.widget.ChestSelectorWidget;
import net.shaddii.smartsorter.widget.DropdownWidget;

import java.util.*;
import java.util.stream.Collectors;

public class ChestsTabComponent extends TabComponent {
    private ChestSelectorWidget chestSelector;
    private ChestConfigPanel chestConfigPanel;
    private Button sortAllButton;
    private DropdownWidget chestSortDropdown;

    private BlockPos lastSelectedChestPos = null;
    private long sortAllClickTime = 0;
    private int sortedChestCount = 0;

    public ChestsTabComponent(StorageControllerScreen parent, StorageControllerScreenHandler handler) {
        super(parent, handler);
    }

    @Override
    protected void initWidgets() {
        // Sort All button - use parent.addWidget() instead of parent.addDrawableChild()
        sortAllButton = Button.builder(
                Component.literal("Sort All"),
                btn -> handleSortAllChests()
        ).bounds(guiX + 75, guiY + 4, 30, 12).build();
        parent.addWidget(sortAllButton);  // Use addWidget

        // Sort mode dropdown
        chestSortDropdown = new DropdownWidget(guiX + 75 + 30 + 2, guiY + 4, 55, 12, Component.literal(""));
        initSortDropdown();
        parent.addWidget(chestSortDropdown);  // Use addWidget

        // Chest selector - use parent.getTextRenderer()
        initChestSelector();

        // Chest config panel
        initConfigPanel();
    }

    private void initSortDropdown() {
        for (ChestSortMode mode : ChestSortMode.values()) {
            String tooltip = switch(mode) {
                case PRIORITY -> "Priority - Sort by routing priority (1-10)";
                case NAME -> "Name - Sort alphabetically by chest name";
                case FULLNESS -> "Fullness - Sort by how full the chest is";
                case COORDINATES -> "Position - Sort by X, Y, Z coordinates";
            };
            chestSortDropdown.addEntry(mode.getDisplayName(), tooltip);
        }

        chestSortDropdown.setSelectedIndex(0);
        chestSortDropdown.setOnSelect(index -> {
            if (index >= 0 && index < ChestSortMode.values().length) {
                ChestSortMode mode = ChestSortMode.values()[index];
                if (chestSelector != null) {
                    chestSelector.setSortMode(mode);
                }
            }
        });
    }

    private void initChestSelector() {
        chestSelector = new ChestSelectorWidget(
                guiX + 8, guiY + 18,
                backgroundWidth - 16, 10,
                parent.getFont(), parent  // Use parent.getTextRenderer()
        );

        Map<BlockPos, ChestConfig> configs = handler.getChestConfigs();
        chestSelector.updateChests(configs);

        // Restore selection
        if (lastSelectedChestPos != null && configs.containsKey(lastSelectedChestPos)) {
            restoreSelection(configs);
        }

        chestSelector.setOnSelectionChange(config -> {
            if (chestConfigPanel != null) {
                chestConfigPanel.setConfig(config);
                lastSelectedChestPos = config != null ? config.position : null;
            }
        });

        chestSelector.setOnConfigUpdate(config -> {
            BlockPos editedChestPos = config.position;
            markDirty();
            handler.updateChestConfig(editedChestPos, config);
            updateChestDisplay(editedChestPos);
        });
    }

    private void initConfigPanel() {
        chestConfigPanel = new ChestConfigPanel(
                guiX + 8, guiY + 30,
                backgroundWidth - 16, 75,
                parent.getFont()
        );

        Map<BlockPos, ChestConfig> configs = handler.getChestConfigs();

        // Calculate correct maxPriority
        int regularChestCount = (int) configs.values().stream()
                .filter(c -> c.filterMode != ChestConfig.FilterMode.CUSTOM)
                .count();
        chestConfigPanel.setMaxPriority(regularChestCount);

        ChestConfig selected = chestSelector.getSelectedChest();
        chestConfigPanel.setConfig(selected);

        if (selected != null) {
            lastSelectedChestPos = selected.position;
        }

        chestConfigPanel.setOnConfigUpdate(config -> {
            BlockPos editedChestPos = config.position;
            markDirty();
            handler.updateChestConfig(editedChestPos, config);
            updateChestDisplay(editedChestPos);
        });
    }

    private void restoreSelection(Map<BlockPos, ChestConfig> configs) {
        ChestSortMode currentMode = chestSelector.getSortMode();
        List<ChestConfig> sortedList = new ArrayList<>(configs.values());
        sortedList.sort((a, b) -> a.getSortKey(currentMode).compareTo(b.getSortKey(currentMode)));

        for (int i = 0; i < sortedList.size(); i++) {
            if (sortedList.get(i).position.equals(lastSelectedChestPos)) {
                chestSelector.setSelectedIndex(i);
                break;
            }
        }
    }

    private void updateChestDisplay(BlockPos editedChestPos) {
        Map<BlockPos, ChestConfig> updatedConfigs = handler.getChestConfigs();
        chestSelector.updateChests(updatedConfigs);
        chestSelector.reselectChestByPos(editedChestPos);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.text(parent.getFont(), "Chest Config", guiX + 8, guiY + 6, 0xFF404040, false);

        if (chestSelector != null) {
            chestSelector.extractRenderState(context, mouseX, mouseY, delta);
        }

        if (chestConfigPanel != null) {
            chestConfigPanel.setExternalDropdownOpen(chestSelector != null && chestSelector.isDropdownOpen());
            chestConfigPanel.extractRenderState(context, mouseX, mouseY, delta);
        }

        // Render floating text
        renderFloatingText(context);

        // Render dropdowns on top
        renderDropdowns(context, mouseX, mouseY);
    }

    private void renderFloatingText(GuiGraphicsExtractor context) {
        long timeSinceSortAll = System.currentTimeMillis() - sortAllClickTime;
        if (timeSinceSortAll < 2000 && sortedChestCount > 0) {
            float alpha = 1.0f - (timeSinceSortAll / 2000.0f);
            int yOffset = (int) (timeSinceSortAll / 20);

            String sortedText = "✓ Sorted " + sortedChestCount + " chest" + (sortedChestCount > 1 ? "s" : "") + "!";
            float scale = 0.8f;
            int scaledWidth = (int)(parent.getFont().width(sortedText) * scale);
            int textX = guiX + backgroundWidth / 2 - scaledWidth / 2;
            int textY = guiY + 108 - yOffset;

            int color = (int) (alpha * 255) << 24 | 0x55FF55;

            // Draw with proper version handling
            parent.drawScaledText(context, sortedText, textX, textY, scale, color);
        }
    }

    private void renderDropdowns(GuiGraphicsExtractor context, int mouseX, int mouseY) {

        if (chestSelector != null) {
            chestSelector.renderDropdownIfOpen(context, mouseX, mouseY);
        }
        if (chestSortDropdown != null && chestSortDropdown.isOpen()) {
            chestSortDropdown.renderDropdown(context, mouseX, mouseY);
        }
        if (chestConfigPanel != null) {
            chestConfigPanel.renderDropdownsOnly(context, mouseX, mouseY);
        }

    }

    public void renderTooltips(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        if (!parent.isShiftDown()) return;

        // Sort All button tooltip
        if (sortAllButton != null && sortAllButton.isMouseOver(mouseX, mouseY)) {
            List<Component> tooltip = Arrays.asList(
                    Component.literal("§6Sort All Chests"),
                    Component.literal("§7Moves all items from every"),
                    Component.literal("§7chest into the network")
            );
            context.setComponentTooltipForNextFrame(parent.getFont(), tooltip, mouseX, mouseY);
            return;
        }

        // Sort dropdown tooltip
        if (chestSortDropdown != null && chestSortDropdown.isMouseOver(mouseX, mouseY)) {
            List<Component> tooltip = Arrays.asList(
                    Component.literal("§6Sort Order"),
                    Component.literal("§7Changes how chests are"),
                    Component.literal("§7ordered in the list")
            );
            context.setComponentTooltipForNextFrame(parent.getFont(), tooltip, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Priority: Sort dropdown first
        if (chestSortDropdown != null) {
            if (chestSortDropdown.isOpen()) {
                if (chestSortDropdown.isMouseOver(mouseX, mouseY)) {
                    return chestSortDropdown.mouseClicked(mouseX, mouseY, button);
                } else {
                    chestSortDropdown.close();
                    return true;
                }
            } else if (chestSortDropdown.isMouseOver(mouseX, mouseY)) {
                return chestSortDropdown.mouseClicked(mouseX, mouseY, button);
            }
        }

        // Then chest selector dropdown
        if (chestSelector != null && chestSelector.isDropdownOpen()) {
            DropdownWidget dropdown = chestSelector.dropdown;
            int dropdownY = dropdown.getDropdownY();
            int visibleEntries = Math.min(6, dropdown.getEntries().size());
            int dropdownHeight = visibleEntries * 12;

            if (mouseX >= dropdown.getX() && mouseX < dropdown.getX() + dropdown.getWidth() &&
                    mouseY >= dropdownY && mouseY < dropdownY + dropdownHeight) {
                return chestSelector.mouseClicked(mouseX, mouseY, button);
            }
            dropdown.close();
            return true;
        }

        // Regular clicks
        boolean chestSelectorClicked = chestSelector != null && chestSelector.mouseClicked(mouseX, mouseY, button);
        boolean configPanelClicked = chestConfigPanel != null && chestConfigPanel.mouseClicked(mouseX, mouseY, button);

        if (!configPanelClicked && chestConfigPanel != null) {
            chestConfigPanel.setFocused(false);
            parent.setFocused(null);
        }

        return chestSelectorClicked || configPanelClicked;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (chestSortDropdown != null && chestSortDropdown.isOpen()) {
            if (chestSortDropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
                return true;
            }
        }

        if (chestSelector != null && chestSelector.isDropdownOpen()) {
            return chestSelector.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        if (chestConfigPanel != null && chestConfigPanel.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }

        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (chestSelector != null) {
            if (chestSelector.isCurrentlyRenaming()) {
                chestSelector.keyPressed(keyCode, scanCode, modifiers);
                return true;
            }
            if (chestSelector.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }

        if (chestConfigPanel != null) {
            return handleKeyPress(chestConfigPanel, keyCode, scanCode, modifiers);
        }

        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (chestSelector != null && chestSelector.charTyped(chr, modifiers)) {
            return true;
        }

        if (chestConfigPanel != null) {
            return handleCharType(chestConfigPanel, chr, modifiers);
        }

        return false;
    }

    @Override
    public void markDirty() {
        if (chestSelector != null) {
            Map<BlockPos, ChestConfig> updatedConfigs = handler.getChestConfigs();

            if (chestConfigPanel != null) {
                int regularChestCount = (int) updatedConfigs.values().stream()
                        .filter(c -> c.filterMode != ChestConfig.FilterMode.CUSTOM)
                        .count();
                chestConfigPanel.setMaxPriority(regularChestCount);
            }

            chestSelector.updateChests(updatedConfigs);

            if (lastSelectedChestPos != null && updatedConfigs.containsKey(lastSelectedChestPos)) {
                chestSelector.reselectChestByPos(lastSelectedChestPos);

                ChestConfig refreshedConfig = updatedConfigs.get(lastSelectedChestPos);
                if (chestConfigPanel != null && refreshedConfig != null) {
                    chestConfigPanel.setConfig(refreshedConfig);
                }
            }
        }
    }

    public void onPriorityUpdate() {
        Map<BlockPos, ChestConfig> updatedConfigs = handler.getChestConfigs();

        if (chestConfigPanel != null) {
            int regularChestCount = (int) updatedConfigs.values().stream()
                    .filter(c -> c.filterMode != ChestConfig.FilterMode.CUSTOM)
                    .count();
            chestConfigPanel.setMaxPriority(regularChestCount);
        }

        if (chestSelector != null) {
            chestSelector.updateChests(updatedConfigs);

            if (lastSelectedChestPos != null && updatedConfigs.containsKey(lastSelectedChestPos)) {
                chestSelector.reselectChestByPos(lastSelectedChestPos);

                ChestConfig refreshedConfig = updatedConfigs.get(lastSelectedChestPos);
                if (chestConfigPanel != null && refreshedConfig != null) {
                    chestConfigPanel.setConfig(refreshedConfig);
                }
            }
        }

        markDirty();
    }

    public void handleSortThisChest(BlockPos chestPos) {
        if (chestPos == null) return;

        List<BlockPos> singleChestList = List.of(chestPos);
        ClientPlayNetworking.send(new SortChestsPayload(singleChestList));

        sortAllClickTime = System.currentTimeMillis();
        sortedChestCount = 1;

        // Schedule refresh
        parent.scheduleRefresh(500);
    }

    private void handleSortAllChests() {
        Map<BlockPos, ChestConfig> chestConfigs = handler.getChestConfigs();
        if (chestConfigs.isEmpty()) return;

        List<SortableDestination> destinations = new ArrayList<>();

        for (ChestConfig config : chestConfigs.values()) {
            if (config.filterMode == ChestConfig.FilterMode.CUSTOM) continue;
            boolean isOverflow = config.filterMode == ChestConfig.FilterMode.OVERFLOW;
            boolean isGeneral = config.filterMode == ChestConfig.FilterMode.NONE;
            destinations.add(new SortableDestination(config.position, config.priority, isOverflow, isGeneral));
        }

        destinations.sort(Comparator
                .comparing(SortableDestination::isOverflow)
                .thenComparing(SortableDestination::isGeneral)
                .thenComparingInt(SortableDestination::priority)
        );

        List<BlockPos> sortedPositions = destinations.stream()
                .map(SortableDestination::pos)
                .collect(Collectors.toList());

        if (sortedPositions.isEmpty()) return;

        ClientPlayNetworking.send(new SortChestsPayload(sortedPositions));

        sortAllClickTime = System.currentTimeMillis();
        sortedChestCount = sortedPositions.size();

        // Schedule refresh
        parent.scheduleRefresh(1000);
    }

    private record SortableDestination(BlockPos pos, int priority, boolean isOverflow, boolean isGeneral) {}
}