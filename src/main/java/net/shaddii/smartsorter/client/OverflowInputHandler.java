package net.shaddii.smartsorter.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.Screen;

public class OverflowInputHandler {

    public static void register() {
        // Register for all screens (including in-game HUD)
        ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            registerForScreen(screen);
        });
    }

    private static void registerForScreen(Screen screen) {
        // Mouse scroll
        ScreenMouseEvents.allowMouseScroll(screen).register((scr, mouseX, mouseY, horizontal, vertical) -> {
            // If overflow overlay handles it, block other handlers
            return !OverflowNotificationOverlay.handleMouseScroll(mouseX, mouseY, horizontal, vertical);
        });

        // Mouse click
        ScreenMouseEvents.allowMouseClick(screen).register((scr, click) -> {
            return !OverflowNotificationOverlay.handleMouseClick(click.x(), click.y(), click.button());
        });
    }
}