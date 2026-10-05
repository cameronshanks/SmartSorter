package net.shaddii.smartsorter.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.shaddii.smartsorter.client.ProbeWhitelistPanel;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.widget.ChestConfigPanel;

//? if >= 1.21.9 {
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.CharInput;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
//?}

public class OutputProbeScreen extends HandledScreen<OutputProbeScreenHandler> {
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

    public OutputProbeScreen(OutputProbeScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);

        // Smaller GUI - just for config panel
        this.backgroundWidth = 180;
        this.backgroundHeight = 140;

        this.titleX = 8;
        this.titleY = 6;

        // Hide player inventory title
        this.playerInventoryTitleY = 10000; // Move off-screen
    }

    // ========================================
    // INITIALIZATION
    // ========================================

    @Override
    protected void init() {
        super.init();

        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        // Chest config panel - disable header (no "Chest Config" text or coordinates)
        configPanel = new ChestConfigPanel(
                x + 8, y + 18,
                backgroundWidth - 16, backgroundHeight - 26,
                textRenderer,
                true,
                false
        );

        ChestConfig config = handler.getChestConfig();
        if (config != null) {
            configPanel.setConfig(config);
            // Set max priority based on total chests in network
            configPanel.setMaxPriority(10); // You can adjust this or get from handler
        }

        configPanel.setOnConfigUpdate(updatedConfig -> {
            handler.updateChestConfig(updatedConfig);
        });

        addDrawableChild(configPanel);

        // Whitelist buttons, left of the GUI (only visible for Custom chests)
        whitelistPanel = new ProbeWhitelistPanel(handler, Math.max(2, x - 122), y + 18);
        addDrawableChild(whitelistPanel.sortingButton());
        addDrawableChild(whitelistPanel.editingButton());

        //? if >= 1.21.9 {
        // Register mouse events for 1.21.9+
        ScreenMouseEvents.allowMouseClick(this).register((screen, click) -> {
            if (!(screen instanceof OutputProbeScreen gui)) return true;

            if (gui.configPanel != null && gui.configPanel.mouseClicked(click.x(), click.y(), click.button())) {
                return false; // Consume the event
            }
            return true;
        });
        //?}
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

    //? if <1.21.8 {
    /*private void renderSlot(DrawContext context, net.minecraft.screen.slot.Slot slot) {
        int slotX = slot.x;
        int slotY = slot.y;
        ItemStack itemStack = slot.getStack();

        // Draw slot background
        context.fill(slotX - 1, slotY - 1, slotX + 17, slotY + 17, 0xFF8B8B8B);
        context.fill(slotX, slotY, slotX + 16, slotY + 16, 0xFF373737);

        if (!itemStack.isEmpty()) {
            context.drawItem(itemStack, slotX, slotY);
            context.drawItemInSlot(this.textRenderer, itemStack, slotX, slotY);
        }
    }
    *///?}

    // ========================================
    // RENDERING
    // ========================================

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        // Main background
        context.fill(x, y, x + backgroundWidth, y + backgroundHeight, 0xFF2B2B2B);
        context.fill(x + 1, y + 1, x + backgroundWidth - 1, y + backgroundHeight - 1, 0xFF3C3C3C);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        // Title
        context.drawText(textRenderer, Text.literal("Chest Configuration"), titleX, titleY, 0xFFFFFFFF, false);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        boolean dropdownOpen = isAnyDropdownOpen();

        // Calculate GUI position
        int guiX = (width - backgroundWidth) / 2;
        int guiY = (height - backgroundHeight) / 2;

        // 1. Background
        this.renderBackground(context, mouseX, mouseY, delta);
        drawBackground(context, delta, mouseX, mouseY);

        // 2. Title (NO TRANSLATION - draw at absolute position)
        context.drawText(textRenderer, Text.literal("Chest Configuration"),
                guiX + titleX, guiY + titleY, 0xFFFFFFFF, false);

        // 3. Config panel (already positioned absolutely, no translation needed)
        if (configPanel != null) {
            configPanel.render(context, mouseX, mouseY, delta);
        }

        // 4. Inventory slots (if dropdown not blocking)
        if (!dropdownOpen) {
            for (int i = 0; i < this.handler.slots.size(); ++i) {
                //? if >=1.21.11 {
                this.drawSlot(context, this.handler.slots.get(i), this.x, this.y);
                //?} else if >=1.21.8 {
                /*this.drawSlot(context, this.handler.slots.get(i));
                 *///?} else {
                /*this.renderSlot(context, this.handler.slots.get(i));
                 *///?}
            }
        }

        if (whitelistPanel != null) {
            whitelistPanel.render(context, mouseX, mouseY, delta);
        }

        // 5. Dropdowns (always on top)
        if (dropdownOpen && configPanel != null) {
            configPanel.renderDropdownsOnly(context, mouseX, mouseY);
        }

        // 6. Tooltips (only if no dropdown)
        if (!dropdownOpen) {
            this.drawMouseoverTooltip(context, mouseX, mouseY);
        }
    }

    @Override
    protected void drawMouseoverTooltip(DrawContext context, int mouseX, int mouseY) {
        // Block all tooltips when dropdown is open
        if (isAnyDropdownOpen()) {
            return;
        }
        // Don't render slot tooltips since we don't have inventory
        // ConfigPanel handles its own tooltips
    }

    @Override
    protected boolean isPointWithinBounds(int x, int y, int width, int height, double pointX, double pointY) {
        // Block slot interaction when dropdown is open
        if (isAnyDropdownOpen()) {
            return false;
        }
        return super.isPointWithinBounds(x, y, width, height, pointX, pointY);
    }

    // ========================================
    // INPUT HANDLING (VERSION-SPECIFIC)
    // ========================================

    //? if >= 1.21.9 {
    @Override
    public boolean keyPressed(KeyInput input) {
        // 1. Let the config panel handle all key inputs first
        if (configPanel != null && configPanel.keyPressed(input)) {
            return true;
        }

        // 2. Allow ESC to close, but nothing else
        if (input.key() == 256) { // ESC key
            return super.keyPressed(input);
        }

        // 3. Block inventory key when any widget might be focused
        if (this.client.options.inventoryKey.matchesKey(input)) {
            return true; // Block closing
        }

        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (configPanel != null && configPanel.charTyped(input)) {
            return true;
        }
        return super.charTyped(input);
    }
    //?} else {
    /*@Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 1. Let the config panel handle all key inputs first
        if (configPanel != null && configPanel.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }

        // 2. Allow ESC to close
        if (keyCode == 256) { // ESC key
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        // 3. Block inventory key
        if (this.client.options.inventoryKey.matchesKey(keyCode, scanCode)) {
            return true; // Block closing
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (configPanel != null && configPanel.charTyped(chr, modifiers)) {
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Block inventory clicks when dropdown is open
        if (isAnyDropdownOpen()) {
            if (configPanel != null && configPanel.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            // Close dropdown if clicking outside
            configPanel.closeAllDropdowns();
            return true;
        }

        if (configPanel != null && configPanel.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    *///?}

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
    protected void handledScreenTick() {
        super.handledScreenTick();
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
            ChestConfig config = handler.getChestConfig();
            configPanel.setConfig(config);
        }
    }
}