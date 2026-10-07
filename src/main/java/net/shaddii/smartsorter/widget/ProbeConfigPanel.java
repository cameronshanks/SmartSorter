package net.shaddii.smartsorter.widget;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.shaddii.smartsorter.network.ProbeConfigUpdatePayload;
import net.shaddii.smartsorter.util.FuelFilterMode;
import net.shaddii.smartsorter.util.ProcessProbeConfig;
import net.shaddii.smartsorter.util.RecipeFilterMode;
import org.joml.Matrix3x2f;

import java.util.function.Consumer;

public class ProbeConfigPanel {

    private long lastStatusChangeTime = 0;
    private boolean previousEnabledState = false;

    private final int x, y, width, height;
    private final Font textRenderer;

    private ProcessProbeConfig config;

    private CheckboxWidget enabledCheckbox;
    private DropdownWidget recipeFilterDropdown;
    private DropdownWidget fuelFilterDropdown;

    private Consumer<ProcessProbeConfig> onConfigUpdate;

    private long warningStartTime = 0;
    private static final long WARNING_BLINK_DURATION = 500;

    private static final int PADDING = 5;
    private static final int LINE_HEIGHT = 11;
    private static final int DROPDOWN_WIDTH = 80;
    private static final int DROPDOWN_HEIGHT = 10;

    public ProbeConfigPanel(int x, int y, int width, int height, Font textRenderer) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.textRenderer = textRenderer;

        initializeWidgets();
    }

    private void initializeWidgets() {
        int innerX = x + PADDING;
        int innerY = y + 18;

        enabledCheckbox = CheckboxWidget.builder(Component.literal("Enabled"), textRenderer)
                .pos(innerX, innerY)
                .dimensions(55, 9)
                .callback(this::onEnabledChanged)
                .build();

        recipeFilterDropdown = new DropdownWidget(
                innerX + 35, innerY + 12,
                DROPDOWN_WIDTH, DROPDOWN_HEIGHT,
                Component.literal("")
        );

        fuelFilterDropdown = new DropdownWidget(
                innerX + 35, innerY + 25,
                DROPDOWN_WIDTH, DROPDOWN_HEIGHT,
                Component.literal("")
        );
    }

    public void setConfig(ProcessProbeConfig newConfig) {
        this.config = newConfig;

        if (config == null) {
            return;
        }

        enabledCheckbox.setChecked(config.enabled);
        setupRecipeFilterDropdown();
        setupFuelFilterDropdown();
    }

    private void setupRecipeFilterDropdown() {
        recipeFilterDropdown.clearEntries();

        RecipeFilterMode[] validFilters = config.getValidRecipeFilters();
        boolean currentFilterIsValid = config.isRecipeFilterValid();

        for (RecipeFilterMode mode : validFilters) {
            recipeFilterDropdown.addEntry(mode.getDisplayName(), mode.getDisplayName());
        }

        if (!currentFilterIsValid) {
            String invalidLabel = "§c" + config.recipeFilter.getDisplayName();
            recipeFilterDropdown.addEntry(invalidLabel, config.recipeFilter.getDisplayName());
            recipeFilterDropdown.setSelectedIndex(validFilters.length);
            warningStartTime = System.currentTimeMillis();
        } else {
            for (int i = 0; i < validFilters.length; i++) {
                if (validFilters[i] == config.recipeFilter) {
                    recipeFilterDropdown.setSelectedIndex(i);
                    break;
                }
            }
            warningStartTime = 0;
        }

        recipeFilterDropdown.setOnSelect(index -> {
            if (config != null && index < validFilters.length) {
                config.recipeFilter = validFilters[index];
                warningStartTime = 0;
                sendConfigUpdate();
                notifyUpdate();
                setupRecipeFilterDropdown();
            }
        });
    }

    private void setupFuelFilterDropdown() {
        fuelFilterDropdown.clearEntries();

        for (FuelFilterMode mode : FuelFilterMode.values()) {
            fuelFilterDropdown.addEntry(mode.getDisplayName(), mode.getDisplayName());
        }

        fuelFilterDropdown.setSelectedIndex(config.fuelFilter.ordinal());

        fuelFilterDropdown.setOnSelect(index -> {
            if (config != null) {
                config.fuelFilter = FuelFilterMode.values()[index];
                sendConfigUpdate();
                notifyUpdate();
            }
        });
    }

    private void onEnabledChanged(boolean checked) {
        if (config != null) {
            if (config.enabled != checked) {
                lastStatusChangeTime = System.currentTimeMillis();
                previousEnabledState = config.enabled;
            }

            config.enabled = checked;
            sendConfigUpdate();
            notifyUpdate();
        }
    }

    private void sendConfigUpdate() {
        if (config != null) {
            ClientPlayNetworking.send(new ProbeConfigUpdatePayload(
                    config.position,
                    config.customName,
                    config.enabled,
                    config.recipeFilter,
                    config.fuelFilter
            ));
        }
    }

    private void notifyUpdate() {
        if (onConfigUpdate != null && config != null) {
            onConfigUpdate.accept(config);
        }
    }

    private void drawCenteredError(GuiGraphicsExtractor context, String error, int yPos) {
        float scale = 0.6f;
        int textWidth = (int)(textRenderer.width(error) * scale);
        int centerX = x + (width / 2) - (textWidth / 2);  // Center horizontally

        drawScaledText(context, "§c" + error, centerX, yPos, 0xFFFF5555, scale);
    }


    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {

        drawBackground(context);

        if (config == null) {
            drawNoProbeMessage(context);
            return;
        }

        int currentY = y + 4;

        currentY = drawTitle(context, currentY);
        currentY = drawMachineInfo(context, currentY);

        currentY += 7;
        enabledCheckbox.extractRenderState(context, mouseX, mouseY, delta);

        currentY += LINE_HEIGHT;
        currentY = drawRecipeFilter(context, currentY, mouseX, mouseY, delta);

        currentY += 13;
        drawFuelFilter(context, currentY, mouseX, mouseY, delta);

        currentY += 13;
        drawStatistics(context, currentY);



        if (!config.isRecipeFilterValid()) {
            String error = config.getValidationError();
            if (error != null) {
                currentY += 10;  // Space below statistics
                drawCenteredError(context, error, currentY);
            }
        }

        renderDropdowns(context, mouseX, mouseY);
    }

    private void drawBackground(GuiGraphicsExtractor context) {
        context.fill(x, y, x + width, y + height, 0xFF2B2B2B);
        context.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF3C3C3C);
    }

    private void drawNoProbeMessage(GuiGraphicsExtractor context) {
        String message = "No probe selected";
        float scale = 0.65f;
        int scaledTextWidth = (int)(textRenderer.width(message) * scale);
        int textX = x + width / 2 - scaledTextWidth / 2;
        int textY = y + height / 2 - 4;

        drawScaledText(context, message, textX, textY, 0xFF888888, scale);
    }

    private int drawTitle(GuiGraphicsExtractor context, int currentY) {
        String title = "Configuration";
        String statusIcon = "";
        int statusColor = 0xFFFFFFFF;

        if (config != null) {
            if (config.enabled) {
                statusIcon = " §a✓";
                statusColor = 0xFF55FF55;
            } else {
                statusIcon = " §c✗";
                statusColor = 0xFFFF5555;
            }

            long timeSinceChange = System.currentTimeMillis() - lastStatusChangeTime;
            if (timeSinceChange < 1000) {
                float pulse = (float)(Math.sin(timeSinceChange * 0.005) * 0.5 + 0.5);
                int alpha = 128 + (int)(127 * pulse);
                statusColor = (alpha << 24) | (statusColor & 0x00FFFFFF);
            }
        }

        drawScaledText(context, title + statusIcon, x + PADDING, currentY, statusColor, 0.7f);
        return currentY + 8;
    }

    private int drawMachineInfo(GuiGraphicsExtractor context, int currentY) {
        int innerX = x + PADDING;

        String machineInfo = "§7Type: §e" + config.machineType;
        drawScaledText(context, machineInfo, innerX, currentY, 0xFFFFFFFF, 0.65f);

        String location = String.format("§8[%d, %d, %d]",
                config.position.getX(),
                config.position.getY(),
                config.position.getZ());

        int machineTextWidth = (int)(textRenderer.width(machineInfo) * 0.65f);
        int locationX = innerX + machineTextWidth + 3;
        drawScaledText(context, location, locationX, currentY, 0xFFAAAAAA, 0.65f);

        return currentY;
    }

    private int drawRecipeFilter(GuiGraphicsExtractor context, int currentY, int mouseX, int mouseY, float delta) {
        int innerX = x + PADDING;

        drawScaledText(context, "§7Recipe:", innerX, currentY + 1, 0xFFAAAAAA, 0.65f);


        if (!config.isRecipeFilterValid()) {
            drawWarningIcon(context, x + width - 15, y + 4);
            int dropdownEndX = innerX + 35 + 80;
            drawScaledText(context, "§c(Invalid)", dropdownEndX + 2, currentY + 1, 0xFFFF5555, 0.7f);
        }

        recipeFilterDropdown.extractRenderState(context, mouseX, mouseY, delta);

        return currentY;
    }

    private void drawFuelFilter(GuiGraphicsExtractor context, int currentY, int mouseX, int mouseY, float delta) {
        int innerX = x + PADDING;
        drawScaledText(context, "§7Fuel:", innerX, currentY + 1, 0xFFAAAAAA, 0.65f);
        fuelFilterDropdown.extractRenderState(context, mouseX, mouseY, delta);
    }

    private void drawStatistics(GuiGraphicsExtractor context, int currentY) {
        int innerX = x + PADDING;

        String statusText;
        int statusColor;

        if (config.enabled) {
            statusText = "§aActive";
            statusColor = 0xFF55FF55;
        } else {
            statusText = "§cDisabled";
            statusColor = 0xFFFF5555;
        }

        String stats = String.format("%s §7- Processed: §f%,d", statusText, config.itemsProcessed);
        drawScaledText(context, stats, innerX, currentY, 0xFFFFFFFF, 0.65f);
    }

    private void drawWarningIcon(GuiGraphicsExtractor context, int iconX, int iconY) {
        long elapsed = System.currentTimeMillis() - warningStartTime;
        boolean showWarning = (elapsed / WARNING_BLINK_DURATION) % 2 == 0;

        if (showWarning) {drawScaledText(context, "§c⚠", iconX, iconY, 0xFFFFFFFF, 0.8f);
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

    private void renderDropdowns(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        if (recipeFilterDropdown != null && recipeFilterDropdown.isOpen()) {
            recipeFilterDropdown.renderDropdown(context, mouseX, mouseY);
        }
        if (fuelFilterDropdown != null && fuelFilterDropdown.isOpen()) {
            fuelFilterDropdown.renderDropdown(context, mouseX, mouseY);
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (config == null) return false;

        MouseButtonInfo mouseInput = new MouseButtonInfo(button, 0);
        MouseButtonEvent click = new MouseButtonEvent(mouseX, mouseY, mouseInput);

        if (enabledCheckbox.mouseClicked(click, false)) {
            return true;
        }
        if (recipeFilterDropdown.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (fuelFilterDropdown.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        return false;
    }
    
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (recipeFilterDropdown.isOpen()) {
            return recipeFilterDropdown.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        }
        if (fuelFilterDropdown.isOpen()) {
            return fuelFilterDropdown.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        }
        return false;
    }

    public void setOnConfigUpdate(Consumer<ProcessProbeConfig> callback) {
        this.onConfigUpdate = callback;
    }

    public ProcessProbeConfig getConfig() {
        return config;
    }
}