package net.shaddii.smartsorter.screen;

import net.shaddii.smartsorter.client.ProbeWhitelistPanel;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.widget.ChestConfigPanel;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class OutputProbeScreen extends AbstractContainerScreen<OutputProbeScreenHandler> {
    // ========================================
    // FIELDS
    // ========================================

    private ChestConfigPanel configPanel;
    private ProbeWhitelistPanel whitelistPanel;
    private boolean dropdownOpenCache = false;
    private long lastDropdownCheck = 0;

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public OutputProbeScreen(OutputProbeScreenHandler handler, Inventory inventory, Component title) {
        // Smaller GUI - just for config panel
        super(handler, inventory, title, 180, 140);

        this.titleLabelX = 8;
        this.titleLabelY = 6;

        // Hide player inventory title
        this.inventoryLabelY = 10000; // Move off-screen
    }

    // ========================================
    // INITIALIZATION
    // ========================================

    @Override
    protected void init() {
        super.init();

        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;

        // Chest config panel - disable header (no "Chest Config" text or coordinates)
        configPanel = new ChestConfigPanel(
                x + 8, y + 18,
                imageWidth - 16, imageHeight - 26,
                font,
                true,
                false
        );

        ChestConfig config = menu.getChestConfig();
        if (config != null) {
            configPanel.setConfig(config);
            // Set max priority based on total chests in network
            configPanel.setMaxPriority(10); // You can adjust this or get from handler
        }

        configPanel.setOnConfigUpdate(updatedConfig -> {
            menu.updateChestConfig(updatedConfig);
        });

        addRenderableWidget(configPanel);

        // Whitelist buttons, left of the GUI (only visible for Custom chests)
        whitelistPanel = new ProbeWhitelistPanel(menu, Math.max(2, x - 122), y + 18);
        addRenderableWidget(whitelistPanel.sortingButton());
        addRenderableWidget(whitelistPanel.editingButton());

        // Register mouse events for 1.21.9+
        ScreenMouseEvents.allowMouseClick(this).register((screen, click) -> {
            if (!(screen instanceof OutputProbeScreen gui)) return true;

            if (gui.configPanel != null && gui.configPanel.mouseClicked(click.x(), click.y(), click.button())) {
                return false; // Consume the event
            }
            return true;
        });
    }

    // ========================================
    // HELPER METHOD
    // ========================================

    private boolean isAnyDropdownOpen() {
        // Cache for same frame (assume 60fps = ~16ms)
        long now = System.nanoTime();
        if (now - lastDropdownCheck < 16_000_000) { // 16ms in nanoseconds
            return dropdownOpenCache;
        }

        dropdownOpenCache = configPanel != null && configPanel.isAnyDropdownOpen();
        lastDropdownCheck = now;
        return dropdownOpenCache;
    }


    // ========================================
    // RENDERING
    // ========================================

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractBackground(context, mouseX, mouseY, delta);

        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;

        // Main background
        context.fill(x, y, x + imageWidth, y + imageHeight, 0xFF2B2B2B);
        context.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, 0xFF3C3C3C);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        // Title
        context.text(font, Component.literal("Chest Configuration"), titleLabelX, titleLabelY, 0xFFFFFFFF, false);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        boolean dropdownOpen = isAnyDropdownOpen();

        // Calculate GUI position
        int guiX = (width - imageWidth) / 2;
        int guiY = (height - imageHeight) / 2;

        // 1. Background is drawn by extractBackground() before this method runs

        // 2. Title (NO TRANSLATION - draw at absolute position)
        context.text(font, Component.literal("Chest Configuration"),
                guiX + titleLabelX, guiY + titleLabelY, 0xFFFFFFFF, false);

        // 3. Config panel (already positioned absolutely, no translation needed)
        if (configPanel != null) {
            configPanel.extractRenderState(context, mouseX, mouseY, delta);
        }

        // 4. Inventory slots (if dropdown not blocking)
        if (!dropdownOpen) {
            for (int i = 0; i < this.menu.slots.size(); ++i) {
                this.extractSlot(context, this.menu.slots.get(i), mouseX, mouseY);
            }
        }

        if (whitelistPanel != null) {
            whitelistPanel.extractRenderState(context, mouseX, mouseY, delta);
        }

        // 5. Dropdowns (always on top)
        if (dropdownOpen && configPanel != null) {
            configPanel.renderDropdownsOnly(context, mouseX, mouseY);
        }

        // 6. Tooltips (only if no dropdown)
        if (!dropdownOpen) {
            this.extractTooltip(context, mouseX, mouseY);
        }
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        // Block all tooltips when dropdown is open
        if (isAnyDropdownOpen()) {
            return;
        }
        // Don't render slot tooltips since we don't have inventory
        // ConfigPanel handles its own tooltips
    }

    @Override
    protected boolean isHovering(int x, int y, int width, int height, double pointX, double pointY) {
        // Block slot interaction when dropdown is open
        if (isAnyDropdownOpen()) {
            return false;
        }
        return super.isHovering(x, y, width, height, pointX, pointY);
    }

    // ========================================
    // INPUT HANDLING (VERSION-SPECIFIC)
    // ========================================

    @Override
    public boolean keyPressed(KeyEvent input) {
        // 1. Let the config panel handle all key inputs first
        if (configPanel != null && configPanel.keyPressed(input)) {
            return true;
        }

        // 2. Allow ESC to close, but nothing else
        if (input.key() == 256) { // ESC key
            return super.keyPressed(input);
        }

        // 3. Block inventory key when any widget might be focused
        if (this.minecraft.options.keyInventory.matches(input)) {
            return true; // Block closing
        }

        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        if (configPanel != null && configPanel.charTyped(input)) {
            return true;
        }
        return super.charTyped(input);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (configPanel != null && configPanel.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // ========================================
    // UTILITY
    // ========================================

    @Override
    protected void containerTick() {
        super.containerTick();
        if (whitelistPanel != null) {
            whitelistPanel.tick();
        }
    }

    public ProbeWhitelistPanel getWhitelistPanel() {
        return whitelistPanel;
    }

    /**
     * Update the displayed config (e.g., when synced from server)
     */
    public void refreshConfig() {
        if (configPanel != null) {
            ChestConfig config = menu.getChestConfig();
            configPanel.setConfig(config);
        }
    }
}