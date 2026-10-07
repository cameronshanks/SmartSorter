package net.shaddii.smartsorter.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shaddii.smartsorter.network.ChestConfigUpdatePayload;
import net.shaddii.smartsorter.screen.StorageControllerScreen;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.util.ChestConfig.FilterMode;

/**
 * Bulk chest-mode editing on the Storage Controller's Chests tab:
 *   [From: <mode>]  [To: <mode>]  [Apply to N chests]
 * Apply (click twice to confirm) switches every chest currently in the
 * "From" mode to the "To" mode by sending the normal per-chest
 * ChestConfigUpdatePayload for each one, a few per tick.
 */
public final class BulkEditController {

    // How many chest updates to send per client tick (20 ticks = 1 second).
    private static final int PER_TICK = 5;
    // After the first click on Apply, how long you have to click again to confirm.
    private static final long CONFIRM_MS = 3000L;

    // Size of the Storage Controller GUI texture (same numbers Smart Sorter uses).
    private static final int GUI_W = 194;
    private static final int GUI_H = 202;

    private static final FilterMode[] MODES = FilterMode.values();

    // fromIndex 0 = "Any mode", otherwise MODES[fromIndex - 1]. Default: General Storage.
    private static int fromIndex = 1;
    // Default target: Custom.
    private static int toIndex = Arrays.asList(MODES).indexOf(FilterMode.CUSTOM);

    private static Button fromButton;
    private static Button toButton;
    private static Button applyButton;

    private static long armedUntil = 0L;

    private static final Deque<ChestConfig> queue = new ArrayDeque<>();
    private static int totalQueued = 0;
    private static int totalSent = 0;

    private BulkEditController() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(BulkEditController::onTick);
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    private static void onTick(Minecraft client) {
        Screen current = client.screen;

        if (!(current instanceof StorageControllerScreen screen)) {
            fromButton = null;
            toButton = null;
            applyButton = null;
            if (!queue.isEmpty()) {
                message(client, "Bulk edit stopped: the Storage Controller was closed ("
                        + totalSent + " of " + totalQueued + " chests changed).");
                queue.clear();
            }
            return;
        }

        // Keep sending queued updates even if the player flips to another tab.
        processQueue(client, screen);

        // Smart Sorter wipes all widgets whenever the tab changes, so we only
        // (re)add ours while the Chests tab is showing.
        if (!isChestsTab(screen)) {
            return;
        }
        ensureWidgets(screen);
        updateLabels(screen);
    }

    // ------------------------------------------------------------------
    // Widgets
    // ------------------------------------------------------------------

    private static void ensureWidgets(StorageControllerScreen screen) {
        if (fromButton != null && screen.children().contains(fromButton)) {
            return;
        }

        // Left of the GUI, below Smart Sorter's Items / Chests / Config tab buttons.
        int guiX = (screen.width - GUI_W) / 2;
        int guiY = (screen.height - GUI_H) / 2;
        int w = 120;
        int x = Math.max(2, guiX - w - 2);
        int y = guiY + 92;

        fromButton = Button.builder(Component.literal("From"), b -> {
            fromIndex = (fromIndex + 1) % (MODES.length + 1);
            armedUntil = 0L;
        }).bounds(x, y, w, 16).build();

        toButton = Button.builder(Component.literal("To"), b -> {
            toIndex = (toIndex + 1) % MODES.length;
            armedUntil = 0L;
        }).bounds(x, y + 18, w, 16).build();

        applyButton = Button.builder(Component.literal("Apply"), b -> onApply(screen))
                .bounds(x, y + 36, w, 16).build();

        screen.addWidget(fromButton);
        screen.addWidget(toButton);
        screen.addWidget(applyButton);
    }

    private static void updateLabels(StorageControllerScreen screen) {
        if (fromButton == null) {
            return;
        }
        int matches = matching(screen).size();
        long now = System.currentTimeMillis();

        String fromName = fromIndex == 0 ? "Any mode" : MODES[fromIndex - 1].getDisplayName();
        fromButton.setMessage(Component.literal("From: " + fromName));
        toButton.setMessage(Component.literal("To: " + MODES[toIndex].getDisplayName()));

        if (!queue.isEmpty()) {
            applyButton.active = false;
            applyButton.setMessage(Component.literal("Applying... " + queue.size() + " left"));
        } else if (armedUntil > now) {
            applyButton.active = true;
            applyButton.setMessage(Component.literal("Click again to confirm (" + matches + ")"));
        } else {
            applyButton.active = matches > 0;
            applyButton.setMessage(Component.literal("Apply to " + matches + " chest" + (matches == 1 ? "" : "s")));
        }
    }

    // ------------------------------------------------------------------
    // Logic
    // ------------------------------------------------------------------

    /** Chests that would be changed by the current From/To selection. */
    private static List<ChestConfig> matching(StorageControllerScreen screen) {
        List<ChestConfig> out = new ArrayList<>();
        FilterMode target = MODES[toIndex];
        for (ChestConfig c : screen.getMenu().getChestConfigs().values()) {
            if (c.filterMode == target) {
                continue;
            }
            if (fromIndex != 0 && c.filterMode != MODES[fromIndex - 1]) {
                continue;
            }
            out.add(c);
        }
        return out;
    }

    private static void onApply(StorageControllerScreen screen) {
        long now = System.currentTimeMillis();
        if (armedUntil < now) {
            armedUntil = now + CONFIRM_MS; // first click: ask for confirmation
            return;
        }
        armedUntil = 0L;

        FilterMode target = MODES[toIndex];
        int regularChests = (int) screen.getMenu().getChestConfigs().values().stream()
                .filter(c -> c.filterMode != FilterMode.CUSTOM).count();

        for (ChestConfig old : matching(screen)) {
            ChestConfig updated = old.copy();
            updated.filterMode = target;
            updated.cachedFullness = old.cachedFullness;
            updated.previewItems = old.previewItems;

            // Same special case Smart Sorter's own dropdown applies for Overflow.
            if (target == FilterMode.OVERFLOW) {
                updated.simplePrioritySelection = ChestConfig.SimplePriority.LOWEST;
                updated.priority = Math.max(1, regularChests);
            }
            updated.updateHiddenPriority();
            queue.add(updated);
        }
        totalQueued = queue.size();
        totalSent = 0;
    }

    private static void processQueue(Minecraft client, StorageControllerScreen screen) {
        if (queue.isEmpty()) {
            return;
        }
        StorageControllerScreenHandler handler = screen.getMenu();

        for (int i = 0; i < PER_TICK && !queue.isEmpty(); i++) {
            ChestConfig cfg = queue.poll();
            ClientPlayNetworking.send(new ChestConfigUpdatePayload(cfg));
            handler.updateChestConfig(cfg.position, cfg); // update the local copy so the UI reflects it now
            totalSent++;
        }

        if (queue.isEmpty()) {
            screen.scheduleRefresh(500L); // ask the server for a fresh sync and redraw
            message(client, "Bulk edit done: " + totalSent + " chest" + (totalSent == 1 ? "" : "s") + " changed.");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean isChestsTab(StorageControllerScreen screen) {
        return screen.getCurrentTab() == StorageControllerScreen.Tab.CHESTS;
    }

    private static void message(Minecraft client, String text) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("[Smart Sorter] " + text));
        }
    }
}
