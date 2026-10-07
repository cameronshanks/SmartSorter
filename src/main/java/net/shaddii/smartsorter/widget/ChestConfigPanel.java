package net.shaddii.smartsorter.widget;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.shaddii.smartsorter.network.ChestConfigUpdatePayload;
import net.shaddii.smartsorter.util.Category;
import net.shaddii.smartsorter.util.CategoryManager;
import net.shaddii.smartsorter.util.ChestConfig;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ChestConfigPanel implements Renderable, GuiEventListener, NarratableEntry {
    private final int x, y, width, height;
    private final Font textRenderer;

    private ChestConfig currentConfig;

    private boolean applyingConfig = false;
    private Consumer<ChestConfig> onConfigUpdate;

    // Widgets
    private DropdownWidget categoryDropdown;
    private DropdownWidget filterModeDropdown;
    private CheckboxWidget strictNBTCheckbox;
    private EditBox priorityField;
    private DropdownWidget priorityDropdown;
    private EditBox nameField;
    private Button renameButton;
    private int maxPriority = 1;
    private final boolean showHeader;
    private boolean externalDropdownOpen = false;

    private final List<Category> categoryList = new ArrayList<>();

    private static final int PADDING = 5;
    private static final int LINE_HEIGHT = 11;

    // Renaming state
    private boolean isRenaming = false;
    private final boolean showRenameButton;


    public ChestConfigPanel(int x, int y, int width, int height, Font textRenderer, boolean showRenameButton, boolean showHeader) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.textRenderer = textRenderer;
        this.showRenameButton = showRenameButton;
        this.showHeader = showHeader;

        initWidgets();
    }

    public ChestConfigPanel(int x, int y, int width, int height, Font textRenderer, boolean showRenameButton) {
        this(x, y, width, height, textRenderer, showRenameButton, true);
    }

    public ChestConfigPanel(int x, int y, int width, int height, Font textRenderer) {
        this(x, y, width, height, textRenderer, false, true);
    }

    private void initWidgets() {
        int innerX = x + PADDING;
        int innerY = y + 18;

        // Name field (initially hidden)
        if (showRenameButton) {
            nameField = new EditBox(textRenderer, innerX, innerY, width - PADDING * 2 - 45, 10, Component.literal(""));
            nameField.setMaxLength(32);
            nameField.setVisible(false);
            nameField.setResponder(this::onNameChanged);

            renameButton = Button.builder(Component.literal("✎"), btn -> toggleRename())
                    .bounds(innerX + width - PADDING * 2 - 40, innerY, 35, 10)
                    .build();

            innerY += 13; // Adjust for name field space
        }

        categoryDropdown = new DropdownWidget(innerX, innerY, 80, 10, Component.literal(""));
        List<Category> allCategories = CategoryManager.getInstance().getAllCategories();
        for (Category category : allCategories) {
            categoryList.add(category);
            categoryDropdown.addEntry(category.getShortName(), category.getDisplayName());
        }
        categoryDropdown.setOnSelect(this::onCategoryChanged);

        if (!showHeader) {
            // Probe screen - simple dropdown
            priorityDropdown = new DropdownWidget(innerX + 35, innerY, 70, 10, Component.literal(""));
            for (ChestConfig.SimplePriority sp : ChestConfig.SimplePriority.values()) {
                priorityDropdown.addEntry(sp.getDisplayName(), sp.getDescription());
            }
            priorityDropdown.setOnSelect(this::onPriorityDropdownChanged);
        } else {
            // Controller screen - numeric input
            priorityField = new EditBox(textRenderer, innerX + 35, innerY, 30, 10, Component.literal(""));
            priorityField.setMaxLength(3);
            priorityField.setValue("1");
            priorityField.setResponder(this::onPriorityChanged);
        }

        filterModeDropdown = new DropdownWidget(innerX + 35, innerY + 36, 100, 10, Component.literal(""));
        for (ChestConfig.FilterMode mode : ChestConfig.FilterMode.values()) {
            filterModeDropdown.addEntry(mode.getDisplayName(), mode.getDisplayName());
        }
        filterModeDropdown.setOnSelect(this::onFilterModeChanged);

        strictNBTCheckbox = CheckboxWidget.builder(Component.literal("Match NBT"), textRenderer)
                .pos(innerX, innerY + 50)
                .dimensions(60, 9)
                .checked(false)
                .callback(this::onStrictNBTChanged)
                .build();
    }

    // Toggle rename mode
    private void toggleRename() {
        isRenaming = !isRenaming;
        nameField.setVisible(isRenaming);

        if (isRenaming) {
            nameField.setFocused(true);
            if (currentConfig != null && currentConfig.customName != null) {
                nameField.setValue(currentConfig.customName);
            }
        } else {
            nameField.setFocused(false);
            // Save on close
            if (currentConfig != null && !nameField.getValue().equals(currentConfig.customName)) {
                currentConfig.customName = nameField.getValue();
                notifyUpdate();
            }
        }
    }

    // Name changed callback
    private void onNameChanged(String text) {
        // Update happens when rename mode is toggled off
    }

    private void onPriorityChanged(String text) {
        if (applyingConfig || currentConfig == null || text.isEmpty()) return;
        try {
            int value = Integer.parseInt(text);
            if (value < 1) value = 1;
            if (value > maxPriority) value = maxPriority;
            if (currentConfig.priority == value) return;

            // Create a copy of the config with new priority
            ChestConfig updatedConfig = new ChestConfig(
                    currentConfig.position,
                    currentConfig.customName,      // customName is 2nd parameter
                    currentConfig.filterCategory,  // filterCategory is 3rd parameter
                    value,                         // priority is 4th parameter
                    currentConfig.filterMode,      // filterMode is 5th parameter
                    currentConfig.autoItemFrame    // autoItemFrame is 6th parameter
            );
            updatedConfig.strictNBTMatch = currentConfig.strictNBTMatch;
            updatedConfig.simplePrioritySelection = null; // Clear simple selection for manual change
            updatedConfig.updateHiddenPriority();

            // Send update - this will trigger priority shifting
            ClientPlayNetworking.send(new ChestConfigUpdatePayload(updatedConfig));

            if (onConfigUpdate != null) {
                onConfigUpdate.accept(updatedConfig);
            }
        } catch (NumberFormatException e) {}
    }


    private void onPriorityDropdownChanged(int index) {
        if (currentConfig != null && index >= 0 && index < ChestConfig.SimplePriority.values().length) {
            ChestConfig.SimplePriority selected = ChestConfig.SimplePriority.values()[index];
            currentConfig.simplePrioritySelection = selected;
            currentConfig.priority = selected.getNumericValue(maxPriority);
            currentConfig.updateHiddenPriority();
            notifyUpdate();
        }
    }

    private void onStrictNBTChanged(boolean checked) {
        if (currentConfig != null) {
            currentConfig.strictNBTMatch = checked;
            notifyUpdate();
        }
    }

    @Override
    public boolean isFocused() {
        return (priorityField != null && priorityField.isFocused()) ||
                (nameField != null && nameField.isFocused());
    }

    @Override
    public void setFocused(boolean focused) {
        if (priorityField != null) {
            priorityField.setFocused(focused);
        }
        if (nameField != null && !focused) {
            nameField.setFocused(false);
        }
    }

    public void setConfig(ChestConfig config) {
        // Filling the fields in must not count as the user editing them
        applyingConfig = true;
        try {
            applyConfig(config);
        } finally {
            applyingConfig = false;
        }
    }

    private void applyConfig(ChestConfig config) {
        this.currentConfig = config;
        if (config == null) {
            categoryDropdown.setSelectedIndex(0);
            priorityField.setValue("1");
            filterModeDropdown.setSelectedIndex(0);

            // Only clear nameField if it exists
            if (nameField != null) {
                nameField.setValue("");
                nameField.setVisible(false);
            }
            isRenaming = false;
            return;
        }

        int categoryIndex = 0;
        for (int i = 0; i < categoryList.size(); i++) {
            if (categoryList.get(i).getId().equals(config.filterCategory.getId())) {
                categoryIndex = i;
                break;
            }
        }
        categoryDropdown.setSelectedIndex(categoryIndex);

        if (priorityDropdown != null) {
            ChestConfig.SimplePriority sp = config.simplePrioritySelection != null
                    ? config.simplePrioritySelection
                    : ChestConfig.SimplePriority.fromNumeric(config.priority, maxPriority);
            priorityDropdown.setSelectedIndex(sp.ordinal());
        } else if (priorityField != null) {
            String priorityText = String.valueOf(config.priority);
            if (!priorityField.getValue().equals(priorityText)) {
                priorityField.setValue(priorityText);
            }
        }

        filterModeDropdown.setSelectedIndex(config.filterMode.ordinal());
        strictNBTCheckbox.setChecked(config.strictNBTMatch);

        // Only set name field if it exists
        if (nameField != null) {
            if (config.customName != null && !config.customName.isEmpty()) {
                nameField.setValue(config.customName);
            } else {
                nameField.setValue("");
            }
        }
    }


    public void setMaxPriority(int max) {
        this.maxPriority = Math.max(1, max);
    }

    public void setOnConfigUpdate(Consumer<ChestConfig> callback) {
        this.onConfigUpdate = callback;
    }

    private void onCategoryChanged(int index) {
        if (currentConfig != null && index >= 0 && index < categoryList.size()) {
            currentConfig.filterCategory = categoryList.get(index);
            currentConfig.updateHiddenPriority();
            notifyUpdate();
        }
    }

    private void onFilterModeChanged(int index) {
        if (currentConfig != null && index >= 0 && index < ChestConfig.FilterMode.values().length) {
            ChestConfig.FilterMode newMode = ChestConfig.FilterMode.values()[index];
            ChestConfig.FilterMode oldMode = currentConfig.filterMode;

            currentConfig.filterMode = newMode;

            // Auto-set priority when switching TO overflow mode
            if (newMode == ChestConfig.FilterMode.OVERFLOW && oldMode != ChestConfig.FilterMode.OVERFLOW) {
                currentConfig.simplePrioritySelection = ChestConfig.SimplePriority.LOWEST;
                currentConfig.priority = maxPriority; // Set to lowest priority (highest number)

                // Update the priority dropdown/field display
                if (priorityDropdown != null) {
                    priorityDropdown.setSelectedIndex(ChestConfig.SimplePriority.LOWEST.ordinal());
                } else if (priorityField != null) {
                    priorityField.setValue(String.valueOf(maxPriority));
                }
            }

            currentConfig.updateHiddenPriority();
            notifyUpdate();
        }
    }

    private void notifyUpdate() {
        if (onConfigUpdate != null && currentConfig != null) {
            currentConfig.updateHiddenPriority();
            ClientPlayNetworking.send(new ChestConfigUpdatePayload(currentConfig));
            onConfigUpdate.accept(currentConfig);
        }
    }

    public void setExternalDropdownOpen(boolean open) {
        this.externalDropdownOpen = open;
    }

    public void renderDropdownsOnly(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        if (currentConfig == null) return;

        // Only render if actually open
        if (categoryDropdown != null && categoryDropdown.isOpen() && currentConfig.filterMode.needsCategoryFilter()) {
            categoryDropdown.renderDropdown(context, mouseX, mouseY);
        }
        if (filterModeDropdown != null && filterModeDropdown.isOpen()) {
            filterModeDropdown.renderDropdown(context, mouseX, mouseY);
        }
        if (priorityDropdown != null && priorityDropdown.isOpen()) {
            priorityDropdown.renderDropdown(context, mouseX, mouseY);
        }
    }

    /**
     * Check if any dropdown is currently open
     */
    public boolean isAnyDropdownOpen() {
        return (categoryDropdown != null && categoryDropdown.isOpen()) ||
                (filterModeDropdown != null && filterModeDropdown.isOpen()) ||
                (priorityDropdown != null && priorityDropdown.isOpen());
    }

    /**
     * Close all open dropdowns
     */
    public void closeAllDropdowns() {
        if (categoryDropdown != null) categoryDropdown.close();
        if (filterModeDropdown != null) filterModeDropdown.close();
        if (priorityDropdown != null) priorityDropdown.close();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(x, y, x + width, y + height, 0xFF2B2B2B);
        context.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF3C3C3C);

        if (currentConfig == null) {
            drawScaledText(context, "No chest selected", x + width / 2 - 30, y + height / 2, 0xFF888888, 0.65f);
            return;
        }

        // Check if ANY dropdown is open
        boolean anyDropdownOpen = (categoryDropdown != null && categoryDropdown.isOpen()) ||
                (filterModeDropdown != null && filterModeDropdown.isOpen()) ||
                (priorityDropdown != null && priorityDropdown.isOpen()) ||
                externalDropdownOpen;

        // Only hide name field when dropdowns are open (if renaming)
        if (nameField != null && isRenaming) {
            nameField.setVisible(!anyDropdownOpen);
        }

        int currentY = y + 4;
        int innerX = x + PADDING;

        // Only show header if enabled
        if (showHeader) {
            drawScaledText(context, "Chest Config", innerX, currentY, 0xFFFFFFFF, 0.7f);
            currentY += 8;
        }

        // Show name or rename field
        if (showRenameButton) {
            if (isRenaming && !anyDropdownOpen) {
                nameField.setX(innerX);
                nameField.setY(currentY);
                nameField.setWidth(width - PADDING * 2 - 40);
                nameField.extractRenderState(context, mouseX, mouseY, delta);
            } else if (currentConfig.customName != null && !currentConfig.customName.isEmpty()) {
                String nameDisplay = currentConfig.customName.length() > 25
                        ? currentConfig.customName.substring(0, 22) + "..."
                        : currentConfig.customName;
                drawScaledText(context, "§7Name: §f" + nameDisplay, innerX, currentY, 0xFFFFFFFF, 0.65f);
            } else {
                drawScaledText(context, "§7Name: §8(unnamed)", innerX, currentY, 0xFF888888, 0.65f);
            }

            if (renameButton != null) {
                renameButton.setX(innerX + width - PADDING * 2 - 40);
                renameButton.setY(currentY - 1);
                renameButton.extractRenderState(context, mouseX, mouseY, delta);
            }

            currentY += 12;
        } else {
            if (currentConfig.customName != null && !currentConfig.customName.isEmpty()) {
                String nameDisplay = currentConfig.customName.length() > 25
                        ? currentConfig.customName.substring(0, 22) + "..."
                        : currentConfig.customName;
                drawScaledText(context, "§7Name: §f" + nameDisplay, innerX, currentY, 0xFFFFFFFF, 0.65f);
                currentY += 7;
            }
        }

        if (showHeader) {
            String location = String.format("§8[%d, %d, %d]",
                    currentConfig.position.getX(),
                    currentConfig.position.getY(),
                    currentConfig.position.getZ());
            drawScaledText(context, location, innerX, currentY, 0xFFAAAAAA, 0.65f);
            currentY += 10;
        }

        // Filter category dropdown
        if (currentConfig.filterMode.needsCategoryFilter()) {
            drawScaledText(context, "§7Filter:", innerX, currentY + 1, 0xFFAAAAAA, 0.65f);
            categoryDropdown.setX(innerX + 35);
            categoryDropdown.setY(currentY);
            categoryDropdown.extractRenderState(context, mouseX, mouseY, delta);
            currentY += 13;
        }

        // Priority field/dropdown
        drawScaledText(context, "§7Priority:", innerX, currentY + 1, 0xFFAAAAAA, 0.65f);

        if (priorityDropdown != null) {
            // Probe screen - show dropdown
            priorityDropdown.setX(innerX + 35);
            priorityDropdown.setY(currentY);
            priorityDropdown.extractRenderState(context, mouseX, mouseY, delta);
        } else if (priorityField != null) {
            // Controller screen - ALWAYS show text field, just don't make it interactive when dropdowns are open
            priorityField.setX(innerX + 35);
            priorityField.setY(currentY);
            priorityField.setVisible(true); // ALWAYS visible
            priorityField.setEditable(!anyDropdownOpen); // But not editable when dropdowns are open
            priorityField.extractRenderState(context, mouseX, mouseY, delta);
            drawScaledText(context, "§8(1-" + maxPriority + ")", innerX + 68, currentY + 1, 0xFF888888, 0.55f);
        }

        currentY += 13;

        // Filter mode dropdown
        drawScaledText(context, "§7Mode:", innerX, currentY + 1, 0xFFAAAAAA, 0.65f);
        filterModeDropdown.setX(innerX + 35);
        filterModeDropdown.setY(currentY);
        filterModeDropdown.extractRenderState(context, mouseX, mouseY, delta);
        currentY += 13;

        if (currentConfig.filterMode != null) {
            String description = "§8" + currentConfig.filterMode.getDescription();
            drawScaledText(context, description, innerX + 2, currentY, 0xFF888888, 0.55f);
            currentY += 8;
        }

        // Strict NBT checkbox
        if (currentConfig.filterMode == ChestConfig.FilterMode.CUSTOM) {
            strictNBTCheckbox.setX(innerX);
            strictNBTCheckbox.setY(currentY);
            strictNBTCheckbox.extractRenderState(context, mouseX, mouseY, delta);

            int descX = innerX + 63;
            if (currentConfig.strictNBTMatch) {
                drawScaledText(context, "§8- Exact match (enchants, damage)", descX, currentY + 1, 0xFF888888, 0.55f);
            } else {
                drawScaledText(context, "§8- Item type only (ignores NBT)", descX, currentY + 1, 0xFF888888, 0.55f);
            }
        }

        // Always ensure visibility
        if (priorityField != null) {
            priorityField.setVisible(true);
        }
        if (nameField != null) {
            nameField.setVisible(isRenaming && !anyDropdownOpen);
        }

        // Render dropdowns on top - ALL versions need this
        if (categoryDropdown != null && categoryDropdown.isOpen()) {
            categoryDropdown.renderDropdown(context, mouseX, mouseY);
        }
        if (filterModeDropdown != null && filterModeDropdown.isOpen()) {
            filterModeDropdown.renderDropdown(context, mouseX, mouseY);
        }
        if (priorityDropdown != null && priorityDropdown.isOpen()) {
            priorityDropdown.renderDropdown(context, mouseX, mouseY);
        }
    }

    private void drawScaledText(GuiGraphicsExtractor context, String text, int x, int y, int color, float scale) {
        Matrix3x2f oldMatrix = new Matrix3x2f(context.pose());
        Matrix3x2f scaleMatrix = new Matrix3x2f().scaling(scale, scale);
        context.pose().mul(scaleMatrix);
        Matrix3x2f translateMatrix = new Matrix3x2f().translation(x / scale, y / scale);
        context.pose().mul(translateMatrix);
        context.text(textRenderer, text, 0, 0, color, false);
        context.pose().set(oldMatrix);
    }

    public boolean keyPressed(KeyEvent input) {
        if (nameField != null && nameField.isFocused()) {
            if (input.key() == 257) { // Enter key
                toggleRename();
                return true;
            }
            return nameField.keyPressed(input);
        }
        if (priorityField != null && priorityField.isFocused()) {
            return priorityField.keyPressed(input);
        }
        return false;
    }

    public boolean charTyped(CharacterEvent input) {
        if (nameField != null && nameField.isFocused()) {
            return nameField.charTyped(input);
        }
        if (priorityField != null && priorityField.isFocused()) {
            return priorityField.charTyped(input);
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (currentConfig == null) return false;

        if (filterModeDropdown.isOpen()) {
            return filterModeDropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        if (priorityDropdown != null && priorityDropdown.isOpen()) {
            return priorityDropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        if (currentConfig.filterMode.needsCategoryFilter() && categoryDropdown.isOpen()) {
            return categoryDropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        return false;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (currentConfig == null) return false;

        // Only handle rename button if enabled
        if (showRenameButton && renameButton != null) {
            if (renameButton.mouseClicked(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0)), false)) {
                return true;
            }

            // Handle name field
            if (isRenaming) {
                if (nameField.mouseClicked(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0)), false)) {
                    nameField.setFocused(true);
                    return true;
                }
            }
        }

        if (currentConfig.filterMode.needsCategoryFilter()) {
            if (categoryDropdown.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }

        if (priorityDropdown != null) {
            if (priorityDropdown.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }

        if (priorityField != null && priorityField.mouseClicked(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0)), false)) {
            priorityField.setFocused(true);
            return true;
        }

        if (filterModeDropdown.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        if (currentConfig.filterMode == ChestConfig.FilterMode.CUSTOM) {
            if (strictNBTCheckbox.mouseClicked(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0)), false)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public NarrationPriority narrationPriority() {
        return NarrationPriority.NONE;
    }

    @Override
    public void updateNarration(NarrationElementOutput builder) {
        // Can be left empty
    }
}