package net.shaddii.smartsorter.client;

import java.util.HashSet;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.shaddii.smartsorter.network.WhitelistRequestPayload;
import net.shaddii.smartsorter.network.WhitelistToggleEditModePayload;
import net.shaddii.smartsorter.network.WhitelistToggleEnabledPayload;
import net.shaddii.smartsorter.network.WhitelistUpdatePayload;
import net.shaddii.smartsorter.screen.OutputProbeScreen;
import net.shaddii.smartsorter.screen.OutputProbeScreenHandler;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.util.ChestConfig.FilterMode;

/**
 * The Output Probe's whitelist buttons, shown left of the screen while the
 * chest is in Custom mode:
 *
 *   "Sorting: ON/OFF (N)"  - whether the whitelist filters items for this chest.
 *   "Editing: ON/OFF"      - whether right-clicking the probe block with an item
 *                            adds/removes it (see OutputProbeBlock.onUse).
 *
 * Two separate toggles, so turning sorting on doesn't make every later click
 * an accidental edit. Items are added by clicking the block, not here.
 *
 * The client's ChestConfig copy doesn't carry the whitelist (Smart Sorter's
 * config sync packets don't include it), so the panel asks the server for
 * the current state when it opens and shows "Loading..." until it arrives.
 */
public final class ProbeWhitelistPanel {

    private static final long REQUEST_TIMEOUT_MS = 5000L;

    private final OutputProbeScreenHandler handler;
    private final Button sortingButton;
    private final Button editingButton;
    private BlockPos trackedPos;
    private boolean awaitingResponse = false;
    private long requestSentAt = 0L;

    public ProbeWhitelistPanel(OutputProbeScreenHandler handler, int x, int y) {
        this.handler = handler;
        this.sortingButton = Button.builder(Component.literal("Sorting"), btn -> this.send(true))
                .bounds(x, y, 120, 16)
                .build();
        this.editingButton = Button.builder(Component.literal("Editing"), btn -> this.send(false))
                .bounds(x, y + 18, 120, 16)
                .build();
        this.sortingButton.visible = false;
        this.editingButton.visible = false;
    }

    public Button sortingButton() {
        return this.sortingButton;
    }

    public Button editingButton() {
        return this.editingButton;
    }

    /** Called every client tick while the screen is open. */
    public void tick() {
        ChestConfig config = this.handler.getChestConfig();
        BlockPos chestPos = this.handler.chestPos;
        boolean eligible = config != null && chestPos != null && config.filterMode == FilterMode.CUSTOM;

        this.sortingButton.visible = eligible;
        this.editingButton.visible = eligible;
        if (!eligible) {
            this.trackedPos = null;
            return;
        }

        if (this.trackedPos == null || !this.trackedPos.equals(chestPos)) {
            this.trackedPos = chestPos;
            this.awaitingResponse = true;
            this.requestSentAt = System.currentTimeMillis();
            ClientPlayNetworking.send(new WhitelistRequestPayload(chestPos));
        }

        if (this.awaitingResponse && System.currentTimeMillis() - this.requestSentAt > REQUEST_TIMEOUT_MS) {
            this.awaitingResponse = false; // never leave the buttons stuck on "Loading..."
        }

        this.sortingButton.active = !this.awaitingResponse;
        this.editingButton.active = !this.awaitingResponse;
        if (this.awaitingResponse) {
            this.sortingButton.setMessage(Component.literal("Loading..."));
            this.editingButton.setMessage(Component.literal("Loading..."));
        } else {
            this.sortingButton.setMessage(Component.literal(
                    "Sorting: " + (config.whitelistEnabled ? "ON" : "OFF") + " (" + config.getWhitelist().size() + ")"));
            this.editingButton.setMessage(Component.literal("Editing: " + (config.whitelistEditMode ? "ON" : "OFF")));
        }
    }

    /** OutputProbeScreen.render() draws everything itself instead of calling super.render(). */
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        this.sortingButton.extractRenderState(context, mouseX, mouseY, delta);
        this.editingButton.extractRenderState(context, mouseX, mouseY, delta);
    }

    private void send(boolean sorting) {
        BlockPos chestPos = this.handler.chestPos;
        if (chestPos == null) {
            return;
        }
        if (sorting) {
            ClientPlayNetworking.send(new WhitelistToggleEnabledPayload(chestPos));
        } else {
            ClientPlayNetworking.send(new WhitelistToggleEditModePayload(chestPos));
        }
    }

    /**
     * Server state for a chest: the reply to our request, or the echo after a
     * toggle or a block-click edit. Applied if an Output Probe screen for that
     * chest is open.
     */
    public static void onServerState(WhitelistUpdatePayload payload) {
        Minecraft client = Minecraft.getInstance();
        if (!(client.screen instanceof OutputProbeScreen screen)) {
            return;
        }
        OutputProbeScreenHandler handler = screen.getMenu();
        ChestConfig config = handler.getChestConfig();
        if (config == null || !payload.position().equals(handler.chestPos)) {
            return;
        }

        config.whitelistEnabled = payload.enabled();
        config.whitelistEditMode = payload.editMode();
        config.setWhitelist(new HashSet<>(payload.items()));
        ProbeWhitelistPanel panel = screen.getWhitelistPanel();
        if (panel != null) {
            panel.awaitingResponse = false;
        }
    }
}
