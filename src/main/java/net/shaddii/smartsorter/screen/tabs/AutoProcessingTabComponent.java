package net.shaddii.smartsorter.screen.tabs;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.shaddii.smartsorter.network.CollectXpPayload;
import net.shaddii.smartsorter.screen.StorageControllerScreen;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.util.ProcessProbeConfig;
import net.shaddii.smartsorter.widget.ProbeConfigPanel;
import net.shaddii.smartsorter.widget.ProbeSelectorWidget;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public class AutoProcessingTabComponent extends TabComponent {
    private ProbeSelectorWidget probeSelector;
    private ProbeConfigPanel configPanel;

    private BlockPos lastSelectedProbePos = null;
    private int lastCollectedXp = 0;
    private long lastCollectionTime = 0;

    // Remove the TextRenderer parameter
    public AutoProcessingTabComponent(StorageControllerScreen parent, StorageControllerScreenHandler handler) {
        super(parent, handler);  // Don't pass textRenderer
    }

    @Override
    protected void initWidgets() {
        // Probe selector - use parent.getTextRenderer()
        probeSelector = new ProbeSelectorWidget(
                guiX + 8, guiY + 18,
                backgroundWidth - 16, 10,
                parent.getFont()  // Use parent.getTextRenderer() instead of textRenderer
        );

        Map<BlockPos, ProcessProbeConfig> configs = handler.getProcessProbeConfigs();
        probeSelector.updateProbes(configs);

        // Restore selection
        if (lastSelectedProbePos != null && configs.containsKey(lastSelectedProbePos)) {
            restoreSelection(configs);
        }

        probeSelector.setOnSelectionChange(config -> {
            if (configPanel != null) {
                configPanel.setConfig(config);
                lastSelectedProbePos = config != null ? config.position : null;
            }
        });

        probeSelector.setOnConfigUpdate(config -> markDirty());

        // Config panel - use parent.getTextRenderer()
        configPanel = new ProbeConfigPanel(
                guiX + 8, guiY + 30,
                backgroundWidth - 16, 75,
                parent.getFont()  // Use parent.getTextRenderer() instead of textRenderer
        );

        ProcessProbeConfig selected = probeSelector.getSelectedProbe();
        configPanel.setConfig(selected);

        if (selected != null) {
            lastSelectedProbePos = selected.position;
        }

        configPanel.setOnConfigUpdate(config -> markDirty());
    }

    // Continue with other methods, replacing all instances of `textRenderer` with `parent.getTextRenderer()`

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.text(parent.getFont(), "Process Config", guiX + 8, guiY + 6, 0xFF404040, false);

        renderXpDisplay(context, mouseX, mouseY);

        if (probeSelector != null) {
            probeSelector.extractRenderState(context, mouseX, mouseY, delta);
        }

        if (configPanel != null) {
            configPanel.extractRenderState(context, mouseX, mouseY, delta);
        }

        renderFloatingText(context);

        if (probeSelector != null) {
            probeSelector.renderDropdownIfOpen(context, mouseX, mouseY);
        }
    }

    private void renderXpDisplay(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int xp = handler.getStoredExperience();
        long timeSinceCollection = System.currentTimeMillis() - lastCollectionTime;

        float textScale = 0.7f;
        String xpText = "XP: " + xp;
        int xpTextWidth = (int)(parent.getFont().width(xpText) * textScale);

        int xpX = guiX + backgroundWidth - 85;
        int xpY = guiY + 6;

        // Background
        context.fill(xpX - 2, xpY - 1, xpX + xpTextWidth + 32, xpY + 10, 0xAA000000);
        context.fill(xpX - 3, xpY - 2, xpX + xpTextWidth + 33, xpY - 1, 0xFFFFFFFF);
        context.fill(xpX - 3, xpY + 10, xpX + xpTextWidth + 33, xpY + 11, 0xFF888888);

        // XP text
        parent.drawScaledText(context, xpText, xpX, xpY + 1, textScale, 0xFFFFFF00);

        // Collect button
        int btnX = xpX + xpTextWidth + 3;
        int btnY = xpY;
        int btnWidth = 28;
        int btnHeight = 9;

        boolean hovered = mouseX >= btnX && mouseX < btnX + btnWidth &&
                mouseY >= btnY && mouseY < btnY + btnHeight;
        boolean justClicked = timeSinceCollection < 200;
        int btnBg = xp > 0 ? (justClicked ? 0xFFFFFF55 : (hovered ? 0xFF55FF55 : 0xFF00AA00)) : 0xFF444444;
        int textColor = xp > 0 ? 0xFFFFFFFF : 0xFF888888;

        // Button background
        context.fill(btnX, btnY, btnX + btnWidth, btnY + btnHeight, btnBg);
        context.fill(btnX, btnY, btnX + btnWidth, btnY + 1, 0xFFFFFFFF);
        context.fill(btnX, btnY + btnHeight - 1, btnX + btnWidth, btnY + btnHeight, 0xFF888888);
        context.fill(btnX, btnY, btnX + 1, btnY + btnHeight, 0xFFFFFFFF);
        context.fill(btnX + btnWidth - 1, btnY, btnX + btnWidth, btnY + btnHeight, 0xFF888888);

        // Button text
        float btnTextScale = 0.65f;
        parent.drawScaledText(context, "Collect", btnX + 3, btnY + 2, btnTextScale, textColor);
    }

    private void renderFloatingText(GuiGraphicsExtractor context) {
        long timeSinceCollection = System.currentTimeMillis() - lastCollectionTime;
        if (timeSinceCollection < 2000 && lastCollectedXp > 0) {
            float alpha = 1.0f - (timeSinceCollection / 2000.0f);
            int yOffset = (int) (timeSinceCollection / 20);

            String collectedText = "+" + lastCollectedXp + " XP!";
            float scale = 0.7f;
            int scaledWidth = (int)(parent.getFont().width(collectedText) * scale);
            int collectedX = guiX + backgroundWidth / 2 - scaledWidth / 2;
            int collectedY = guiY + 50 - yOffset;

            int color = (int) (alpha * 255) << 24 | 0x55FF55;

            parent.drawScaledText(context, collectedText, collectedX, collectedY, scale, color);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // XP collect button
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            float textScale = 0.7f;
            String xpText = "XP: " + handler.getStoredExperience();
            int xpTextWidth = (int)(parent.getFont().width(xpText) * textScale);

            int xpX = guiX + backgroundWidth - 85;
            int btnX = xpX + xpTextWidth + 3;
            int btnY = guiY + 6;
            int btnWidth = 28;
            int btnHeight = 9;

            if (mouseX >= btnX && mouseX <= btnX + btnWidth &&
                    mouseY >= btnY && mouseY <= btnY + btnHeight) {
                collectXp();
                return true;
            }
        }

        if (probeSelector != null && probeSelector.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        if (configPanel != null && configPanel.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        return false;
    }

    // ... rest of the methods (no change needed for these)

    private void restoreSelection(Map<BlockPos, ProcessProbeConfig> configs) {
        List<ProcessProbeConfig> configList = new ArrayList<>(configs.values());
        configList.sort((a, b) -> {
            if (a.position.getX() != b.position.getX())
                return Integer.compare(a.position.getX(), b.position.getX());
            if (a.position.getY() != b.position.getY())
                return Integer.compare(a.position.getY(), b.position.getY());
            return Integer.compare(a.position.getZ(), b.position.getZ());
        });

        for (int i = 0; i < configList.size(); i++) {
            if (configList.get(i).position.equals(lastSelectedProbePos)) {
                probeSelector.setSelectedIndex(i);
                break;
            }
        }
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
        if (probeSelector != null) {
            if (probeSelector.isDropdownOpen()) {
                return probeSelector.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
            }
            if (probeSelector.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
                return true;
            }
        }

        if (configPanel != null && configPanel.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }

        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (probeSelector != null && probeSelector.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (probeSelector != null && probeSelector.charTyped(chr, modifiers)) {
            return true;
        }
        return false;
    }

    @Override
    public void markDirty() {
        // Refresh probe data if needed
    }

    private void collectXp() {
        int xp = handler.getStoredExperience();
        if (xp > 0) {
            lastCollectedXp = xp;
            lastCollectionTime = System.currentTimeMillis();
            ClientPlayNetworking.send(new CollectXpPayload());
        }
    }

    public void updateProbeStats(BlockPos position, int itemsProcessed) {
        Map<BlockPos, ProcessProbeConfig> configs = handler.getProcessProbeConfigs();
        ProcessProbeConfig config = configs.get(position);

        if (config != null) {
            config.itemsProcessed = itemsProcessed;

            if (probeSelector != null && configPanel != null) {
                ProcessProbeConfig selected = probeSelector.getSelectedProbe();
                if (selected != null && selected.position.equals(position)) {
                    selected.itemsProcessed = itemsProcessed;
                    configPanel.setConfig(selected);
                }
            }

            if (probeSelector != null) {
                probeSelector.updateProbes(handler.getProcessProbeConfigs());
            }

            markDirty();
        }
    }
}