package net.shaddii.smartsorter.screen;

import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.client.OverflowNotificationOverlay;
import net.shaddii.smartsorter.client.SortProgressOverlay;
import net.shaddii.smartsorter.screen.tabs.*;
import org.lwjgl.glfw.GLFW;

import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import java.util.*;

public class StorageControllerScreen extends AbstractContainerScreen<StorageControllerScreenHandler> {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(SmartSorter.MOD_ID, "textures/gui/storage_controller.png");

    public net.minecraft.client.gui.Font getFont() {
        return this.font;
    }

    public void addWidget(net.minecraft.client.gui.components.AbstractWidget widget) {
        this.addRenderableWidget(widget);
    }

    public void setWidgetFocused(net.minecraft.client.gui.components.events.GuiEventListener element) {
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
    private final List<Button> tabButtons = new ArrayList<>();

    public StorageControllerScreen(StorageControllerScreenHandler handler, Inventory inventory, Component title) {
        super(handler, inventory, title, 194, 202);
        this.titleLabelX = 7;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 109;
    }

    @Override
    protected void init() {
        super.init();

        // Initialize tab components
        tabs.put(Tab.STORAGE, new StorageTabComponent(this, menu));
        tabs.put(Tab.CHESTS, new ChestsTabComponent(this, menu));
        tabs.put(Tab.AUTO_PROCESSING, new AutoProcessingTabComponent(this, menu));

        // Initialize tab buttons
        initTabButtons();

        // Initialize current tab
        TabComponent activeTab = tabs.get(currentTab);
        if (activeTab != null) {
            activeTab.init((width - imageWidth) / 2, (height - imageHeight) / 2);
        }

        // Register mouse events for newer versions
        registerMouseEvents();

        menu.requestSync();
    }

    private void initTabButtons() {
        int guiX = (width - imageWidth) / 2;
        int guiY = (height - imageHeight) / 2;
        int tabX = guiX - 60;
        int tabY = guiY + 10;
        int tabWidth = 58;
        int tabHeight = 22;
        int tabSpacing = 4;

        tabButtons.clear();

        Button storageTab = Button.builder(
                Component.literal("Items"),
                btn -> switchTab(Tab.STORAGE)
        ).bounds(tabX, tabY, tabWidth, tabHeight).build();

        Button chestsTab = Button.builder(
                Component.literal("Chests"),
                btn -> switchTab(Tab.CHESTS)
        ).bounds(tabX, tabY + (tabHeight + tabSpacing), tabWidth, tabHeight).build();

        Button processingTab = Button.builder(
                Component.literal("Config"),
                btn -> switchTab(Tab.AUTO_PROCESSING)
        ).bounds(tabX, tabY + 2 * (tabHeight + tabSpacing), tabWidth, tabHeight).build();

        tabButtons.add(storageTab);
        tabButtons.add(chestsTab);
        tabButtons.add(processingTab);

        addRenderableWidget(storageTab);
        addRenderableWidget(chestsTab);
        addRenderableWidget(processingTab);
    }

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

    private void switchTab(Tab newTab) {
        if (currentTab == newTab) return;

        // Close current tab
        TabComponent oldTab = tabs.get(currentTab);
        if (oldTab != null) {
            oldTab.onClose();
        }

        currentTab = newTab;
        clearWidgets();

        // Re-add tab buttons
        for (Button btn : tabButtons) {
            addRenderableWidget(btn);
        }

        // Initialize new tab
        TabComponent activeTab = tabs.get(currentTab);
        if (activeTab != null) {
            activeTab.init((width - imageWidth) / 2, (height - imageHeight) / 2);
        }

        registerMouseEvents();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Slots, labels and widgets (the background is drawn by extractBackground)
        extractContents(context, mouseX, mouseY, delta);

        TabComponent activeTab = tabs.get(currentTab);
        if (activeTab != null) {
            activeTab.extractRenderState(context, mouseX, mouseY, delta);
        }

        // Render overlays with proper z-layering
        context.pose().pushMatrix();
        OverflowNotificationOverlay.render(context, 0f);
        SortProgressOverlay.render(context);
        context.pose().popMatrix();

        // Cursor stack goes on top of everything, including the tab contents
        extractCarriedItem(context, mouseX, mouseY);
        extractSnapbackItem(context);
        extractTooltip(context, mouseX, mouseY);
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
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractBackground(context, mouseX, mouseY, delta);

        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;

        context.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0, 0, imageWidth, imageHeight, 256, 256);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        // Foreground is now handled by tab components
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        super.extractTooltip(context, mouseX, mouseY);

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
    @Override
    public boolean keyPressed(KeyEvent input) {
        TabComponent activeTab = tabs.get(currentTab);
        if (activeTab != null && activeTab.keyPressed(input.key(), 0, input.modifiers())) {
            return true;
        }

        // CRITICAL FIX: Don't close GUI when typing in search field
        if (isAnyTextFieldFocused() && this.minecraft.options.keyInventory.matches(input)) {
            return true; // Block inventory key when typing
        }

        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        TabComponent activeTab = tabs.get(currentTab);
        if (activeTab != null && activeTab.charTyped((char) input.codepoint(), 0)) {
            return true;
        }
        return super.charTyped(input);
    }

    @Override
    public boolean keyReleased(KeyEvent input) {
        return super.keyReleased(input);
    }

    // Input handling for older versions (1.21.8 and below)

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

            if (minecraft != null) {
                minecraft.execute(() -> {
                    menu.requestSync();
                    markDirty();
                });
            }
        }).start();
    }

    public boolean isShiftDown() {
        long handle = Minecraft.getInstance().getWindow().handle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    // Helper method for scaled text drawing (version-specific)
    public void drawScaledText(GuiGraphicsExtractor context, String text, float x, float y, float scale, int color) {
        context.pose().pushMatrix();
        context.pose().translate(x, y);
        context.pose().scale(scale, scale);
        context.text(font, Component.literal(text), 0, 0, color, true);
        context.pose().popMatrix();
    }
}