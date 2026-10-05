package net.shaddii.smartsorter.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.client.OverflowNotificationOverlay;
import net.shaddii.smartsorter.client.SortProgressOverlay;
import net.shaddii.smartsorter.screen.tabs.*;
import org.lwjgl.glfw.GLFW;

//? if >= 1.21.9 {
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.CharInput;
//?}
//? if >= 1.21.8 {
import net.minecraft.client.gl.RenderPipelines;
//?}

import java.util.*;

public class StorageControllerScreen extends HandledScreen<StorageControllerScreenHandler> {
    private static final Identifier TEXTURE = Identifier.of(SmartSorter.MOD_ID, "textures/gui/storage_controller.png");

    public net.minecraft.client.font.TextRenderer getTextRenderer() {
        return this.textRenderer;
    }

    public void addWidget(net.minecraft.client.gui.widget.ClickableWidget widget) {
        this.addDrawableChild(widget);
    }

    public void setWidgetFocused(net.minecraft.client.gui.Element element) {
        this.setFocused(element);
    }

    public enum Tab {
        STORAGE("Storage"),
        CHESTS("Chests"),
        AUTO_PROCESSING("Auto-Processing");

        private final String name;
        Tab(String name) { this.name = name; }
        public String getName() { return name; }
    }

    private Tab currentTab = Tab.STORAGE;

    public Tab getCurrentTab() {
        return currentTab;
    }
    private final Map<Tab, TabComponent> tabs = new HashMap<>();
    private final List<ButtonWidget> tabButtons = new ArrayList<>();

    public StorageControllerScreen(StorageControllerScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = 194;
        this.backgroundHeight = 202;
        this.titleX = 7;
        this.titleY = 6;
        this.playerInventoryTitleX = 8;
        this.playerInventoryTitleY = 109;
    }

    @Override
    protected void init() {
        super.init();

        // Initialize tab components
        tabs.put(Tab.STORAGE, new StorageTabComponent(this, handler));
        tabs.put(Tab.CHESTS, new ChestsTabComponent(this, handler));
        tabs.put(Tab.AUTO_PROCESSING, new AutoProcessingTabComponent(this, handler));

        // Initialize tab buttons
        initTabButtons();

        // Initialize current tab
        TabComponent activeTab = tabs.get(currentTab);
        if (activeTab != null) {
            activeTab.init((width - backgroundWidth) / 2, (height - backgroundHeight) / 2);
        }

        // Register mouse events for newer versions
        //? if >=1.21.9 {
        registerMouseEvents();
         //?}

        handler.requestSync();
    }

    private void initTabButtons() {
        int guiX = (width - backgroundWidth) / 2;
        int guiY = (height - backgroundHeight) / 2;
        int tabX = guiX - 60;
        int tabY = guiY + 10;
        int tabWidth = 58;
        int tabHeight = 22;
        int tabSpacing = 4;

        tabButtons.clear();

        ButtonWidget storageTab = ButtonWidget.builder(
                Text.literal("Items"),
                btn -> switchTab(Tab.STORAGE)
        ).dimensions(tabX, tabY, tabWidth, tabHeight).build();

        ButtonWidget chestsTab = ButtonWidget.builder(
                Text.literal("Chests"),
                btn -> switchTab(Tab.CHESTS)
        ).dimensions(tabX, tabY + (tabHeight + tabSpacing), tabWidth, tabHeight).build();

        ButtonWidget processingTab = ButtonWidget.builder(
                Text.literal("Config"),
                btn -> switchTab(Tab.AUTO_PROCESSING)
        ).dimensions(tabX, tabY + 2 * (tabHeight + tabSpacing), tabWidth, tabHeight).build();

        tabButtons.add(storageTab);
        tabButtons.add(chestsTab);
        tabButtons.add(processingTab);

        addDrawableChild(storageTab);
        addDrawableChild(chestsTab);
        addDrawableChild(processingTab);
    }

    //? if >=1.21.9 {
    private void registerMouseEvents() {
        ScreenMouseEvents.allowMouseClick(this).register((screen, click) -> {
            if (!(screen instanceof StorageControllerScreen gui)) return true;

            TabComponent activeTab = gui.tabs.get(gui.currentTab);
            if (activeTab != null && activeTab.mouseClicked(click.x(), click.y(), click.button())) {
                return false;
            }

            return true;
        });

        ScreenMouseEvents.allowMouseRelease(this).register((screen, click) -> {
            if (!(screen instanceof StorageControllerScreen gui)) return true;

            TabComponent activeTab = gui.tabs.get(gui.currentTab);
            if (activeTab != null && activeTab.mouseReleased(click.x(), click.y(), click.button())) {
                return false;
            }

            return true;
        });

        ScreenMouseEvents.allowMouseDrag(this).register((screen, click, deltaX, deltaY) -> {
            if (!(screen instanceof StorageControllerScreen gui)) return true;

            TabComponent activeTab = gui.tabs.get(gui.currentTab);
            if (activeTab != null && activeTab.mouseDragged(click.x(), click.y(), click.button(), deltaX, deltaY)) {
                return false;
            }

            return true;
        });

        ScreenMouseEvents.allowMouseScroll(this).register((screen, mouseX, mouseY, horizontal, vertical) -> {
            if (!(screen instanceof StorageControllerScreen gui)) return true;

            TabComponent activeTab = gui.tabs.get(gui.currentTab);
            if (activeTab != null && activeTab.mouseScrolled(mouseX, mouseY, horizontal, vertical)) {
                return false;
            }

            return true;
        });
    }
    //?}

    private void switchTab(Tab newTab) {
        if (currentTab == newTab) return;

        // Close current tab
        TabComponent oldTab = tabs.get(currentTab);
        if (oldTab != null) {
            oldTab.onClose();
        }

        currentTab = newTab;
        clearChildren();

        // Re-add tab buttons
        for (ButtonWidget btn : tabButtons) {
            addDrawableChild(btn);
        }

        // Initialize new tab
        TabComponent activeTab = tabs.get(currentTab);
        if (activeTab != null) {
            activeTab.init((width - backgroundWidth) / 2, (height - backgroundHeight) / 2);
        }

        //? if >=1.21.9 {
        registerMouseEvents();
         //?}
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        TabComponent activeTab = tabs.get(currentTab);
        if (activeTab != null) {
            activeTab.render(context, mouseX, mouseY, delta);
        }

        drawMouseoverTooltip(context, mouseX, mouseY);

        // Render overlays with proper z-layering
        //? if >=1.21.8 {
        context.getMatrices().pushMatrix();
        OverflowNotificationOverlay.render(context, 0f);
        SortProgressOverlay.render(context);
        context.getMatrices().popMatrix();
        //?} else {
        /*context.getMatrices().push();
        context.getMatrices().translate(0, 0, 400);
        OverflowNotificationOverlay.render(context, 0f);
        SortProgressOverlay.render(context);
        context.getMatrices().pop();
        *///?}

        // CRITICAL FIX: Render cursor stack on top of everything
        if (this.handler.getCursorStack() != null && !this.handler.getCursorStack().isEmpty()) {
            ItemStack cursorStack = this.handler.getCursorStack();

            //? if >=1.21.8 {
            context.drawItem(cursorStack, mouseX - 8, mouseY - 8);
            context.drawStackOverlay(this.textRenderer, cursorStack, mouseX - 8, mouseY - 8);
            //?} else {
            /*context.getMatrices().push();
            context.getMatrices().translate(0, 0, 500);
            context.drawItem(cursorStack, mouseX - 8, mouseY - 8);
            context.drawItemInSlot(this.textRenderer, cursorStack, mouseX - 8, mouseY - 8);
            context.getMatrices().pop();
            *///?}
        }
    }

    private boolean isAnyTextFieldFocused() {
        TabComponent activeTab = tabs.get(currentTab);
        if (activeTab == null) return false;

        // Check if storage tab has search field focused
        if (activeTab instanceof StorageTabComponent storageTab) {
            return storageTab.isSearchFieldFocused();
        }

        return false;
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        //? if >=1.21.8 {
        context.drawTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0, 0, backgroundWidth, backgroundHeight, 256, 256);
        //?} else {
        /*context.drawTexture(TEXTURE, x, y, 0, 0, backgroundWidth, backgroundHeight, 256, 256);
         *///?}
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        // Foreground is now handled by tab components
    }

    @Override
    protected void drawMouseoverTooltip(DrawContext context, int mouseX, int mouseY) {
        super.drawMouseoverTooltip(context, mouseX, mouseY);

        if (currentTab == Tab.STORAGE) {
            StorageTabComponent storageTab = (StorageTabComponent) tabs.get(Tab.STORAGE);
            if (storageTab != null) {
                storageTab.renderTooltip(context, mouseX, mouseY);
            }
        } else if (currentTab == Tab.CHESTS) {
            ChestsTabComponent chestsTab = (ChestsTabComponent) tabs.get(Tab.CHESTS);
            if (chestsTab != null) {
                chestsTab.renderTooltips(context, mouseX, mouseY);
            }
        }
    }

    // Input handling for 1.21.9+
    //? if >=1.21.9 {
    @Override
    public boolean keyPressed(KeyInput input) {
        TabComponent activeTab = tabs.get(currentTab);
        if (activeTab != null && activeTab.keyPressed(input.key(), 0, input.modifiers())) {
            return true;
        }

        // CRITICAL FIX: Don't close GUI when typing in search field
        if (isAnyTextFieldFocused() && this.client.options.inventoryKey.matchesKey(input)) {
            return true; // Block inventory key when typing
        }

        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        TabComponent activeTab = tabs.get(currentTab);
        if (activeTab != null && activeTab.charTyped((char) input.codepoint(), input.modifiers())) {
            return true;
        }
        return super.charTyped(input);
    }

    @Override
    public boolean keyReleased(KeyInput input) {
        return super.keyReleased(input);
    }
    //?}

    // Input handling for older versions (1.21.8 and below)
    //? if <=1.21.8 {
    /*@Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        TabComponent activeTab = tabs.get(currentTab);
        if (activeTab != null && activeTab.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        TabComponent activeTab = tabs.get(currentTab);
        if (activeTab != null && activeTab.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        TabComponent activeTab = tabs.get(currentTab);
        if (activeTab != null && activeTab.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        TabComponent activeTab = tabs.get(currentTab);
        if (activeTab != null && activeTab.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        TabComponent activeTab = tabs.get(currentTab);
        if (activeTab != null && activeTab.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }

        // CRITICAL FIX: Don't close GUI when typing in search field
        if (isAnyTextFieldFocused() && this.client.options.inventoryKey.matchesKey(keyCode, scanCode)) {
            return true; // Block inventory key when typing
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        TabComponent activeTab = tabs.get(currentTab);
        if (activeTab != null && activeTab.charTyped(chr, modifiers)) {
            return true;
        }
        return super.charTyped(chr, modifiers);
    }
    *///?}

    // Public API methods
    public void markDirty() {
        for (TabComponent tab : tabs.values()) {
            tab.markDirty();
        }
    }

    public void onPriorityUpdate() {
        if (currentTab == Tab.CHESTS) {
            ChestsTabComponent chestsTab = (ChestsTabComponent) tabs.get(Tab.CHESTS);
            if (chestsTab != null) {
                chestsTab.onPriorityUpdate();
            }
        }
    }

    public void updateProbeStats(BlockPos position, int itemsProcessed) {
        if (currentTab == Tab.AUTO_PROCESSING) {
            AutoProcessingTabComponent processingTab = (AutoProcessingTabComponent) tabs.get(Tab.AUTO_PROCESSING);
            if (processingTab != null) {
                processingTab.updateProbeStats(position, itemsProcessed);
            }
        }
    }

    public void handleSortThisChest(BlockPos chestPos) {
        ChestsTabComponent chestsTab = (ChestsTabComponent) tabs.get(Tab.CHESTS);
        if (chestsTab != null) {
            chestsTab.handleSortThisChest(chestPos);
        }
    }

    public void scheduleRefresh(long delayMs) {
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {}

            if (client != null) {
                client.execute(() -> {
                    handler.requestSync();
                    markDirty();
                });
            }
        }).start();
    }

    public boolean isShiftDown() {
        long handle = MinecraftClient.getInstance().getWindow().getHandle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    // Helper method for scaled text drawing (version-specific)
    public void drawScaledText(DrawContext context, String text, float x, float y, float scale, int color) {
        //? if >=1.21.8 {
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(x, y);
        context.getMatrices().scale(scale, scale);
        context.drawText(textRenderer, Text.literal(text), 0, 0, color, true);
        context.getMatrices().popMatrix();
        //?} else {
        /*context.getMatrices().push();
        context.getMatrices().translate(x, y, 0);
        context.getMatrices().scale(scale, scale, scale);
        context.drawText(textRenderer, Text.literal(text), 0, 0, color, true);
        context.getMatrices().pop();
        *///?}
    }
}