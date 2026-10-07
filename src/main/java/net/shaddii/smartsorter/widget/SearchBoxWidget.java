package net.shaddii.smartsorter.widget;

import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;

/**
 * Minimal search box widget that implements Element & Drawable so it can be added with addDrawableChild(...)
 * - replaced drawBorder() (not present) with simple fills/lines
 * - clipboard paste uses MinecraftClient.getInstance().getWindow().getHandle()
 */
public class SearchBoxWidget implements Renderable, GuiEventListener {
    private final Font textRenderer;
    private int x, y, width, height;

    private String text = "";
    private boolean isFocused = false;
    private int cursorPosition = 0;
    private int tickCounter = 0;

    private Consumer<String> onTextChanged;

    // Visual settings
    private static final int BACKGROUND_COLOR = 0xFF8B8B8B;
    private static final int BORDER_COLOR = 0xFF000000;
    private static final int FOCUSED_BORDER_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int PLACEHOLDER_COLOR = 0xFF808080;
    private static final String PLACEHOLDER = "Search...";

    public SearchBoxWidget(Font textRenderer, int x, int y, int width, int height) {
        this.textRenderer = textRenderer;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public void setOnTextChanged(Consumer<String> callback) {
        this.onTextChanged = callback;
    }

    // Drawable implementation
    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Draw background
        context.fill(x, y, x + width, y + height, BACKGROUND_COLOR);

        // Draw border (1px)
        int borderColor = isFocused ? FOCUSED_BORDER_COLOR : BORDER_COLOR;
        // top
        context.horizontalLine(x, x + width - 1, y, borderColor);
        // bottom
        context.horizontalLine(x, x + width - 1, y + height - 1, borderColor);
        // left
        context.verticalLine(x, y, y + height - 1, borderColor);
        // right
        context.verticalLine(x + width - 1, y, y + height - 1, borderColor);

        // Draw text or placeholder
        String displayText = text.isEmpty() && !isFocused ? PLACEHOLDER : text;
        int textColor = text.isEmpty() && !isFocused ? PLACEHOLDER_COLOR : TEXT_COLOR;

        // Trim text if too long
        String trimmedText = textRenderer.plainSubstrByWidth(displayText, width - 8);

        // Draw text (vertical center)
        context.text(textRenderer, trimmedText, x + 4, y + (height - 8) / 2, textColor, false);

        // Draw cursor if focused
        if (isFocused && (tickCounter / 6) % 2 == 0) {
            int cursorX = x + 4 + textRenderer.width(text.substring(0, Math.min(cursorPosition, text.length())));
            context.fill(cursorX, y + 2, cursorX + 1, y + height - 2, TEXT_COLOR);
        }
    }

    public void tick() {
        tickCounter++;
    }

    // Element implementation: mouseClicked
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean wasClicked = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) { // Left click
            setFocused(wasClicked);

            if (wasClicked && !text.isEmpty()) {
                // Calculate cursor position from click
                int relativeX = (int) (mouseX - x - 4);
                cursorPosition = getCursorPositionFromX(relativeX);
            }
        }

        return wasClicked;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return false;
    }

    private int getCursorPositionFromX(int px) {
        int currentX = 0;
        for (int i = 0; i <= text.length(); i++) {
            int nextX = textRenderer.width(text.substring(0, i));
            if (px < (currentX + nextX) / 2) {
                return Math.max(0, i - 1);
            }
            currentX = nextX;
        }
        return text.length();
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isFocused) return false;

        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE:
                setFocused(false);
                return true;

            case GLFW.GLFW_KEY_BACKSPACE:
                if (!text.isEmpty() && cursorPosition > 0) {
                    text = text.substring(0, cursorPosition - 1) + text.substring(cursorPosition);
                    cursorPosition--;
                    onTextChange();
                }
                return true;

            case GLFW.GLFW_KEY_DELETE:
                if (cursorPosition < text.length()) {
                    text = text.substring(0, cursorPosition) + text.substring(cursorPosition + 1);
                    onTextChange();
                }
                return true;

            case GLFW.GLFW_KEY_LEFT:
                if (cursorPosition > 0) {
                    cursorPosition--;
                }
                return true;

            case GLFW.GLFW_KEY_RIGHT:
                if (cursorPosition < text.length()) {
                    cursorPosition++;
                }
                return true;

            case GLFW.GLFW_KEY_HOME:
                cursorPosition = 0;
                return true;

            case GLFW.GLFW_KEY_END:
                cursorPosition = text.length();
                return true;

            case GLFW.GLFW_KEY_A:
                if (isControlDown()) {
                    // Select all (future feature)
                    return true;
                }
                break;

            case GLFW.GLFW_KEY_V:
                if (isControlDown()) {
                    // Paste from clipboard (grab client window handle)
                    long handle = Minecraft.getInstance().getWindow().handle();
                    String clipboard = GLFW.glfwGetClipboardString(handle);
                    if (clipboard != null && !clipboard.isEmpty()) {
                        // naive paste: append all chars via charTyped to respect validation
                        for (char c : clipboard.toCharArray()) {
                            charTyped(c, 0);
                        }
                    }
                    return true;
                }
                break;
        }

        return false;
    }

    public boolean charTyped(char chr, int modifiers) {
        if (!isFocused) return false;

        // Filter valid characters
        if (isValidChar(chr)) {
            text = text.substring(0, cursorPosition) + chr + text.substring(cursorPosition);
            cursorPosition++;
            onTextChange();
            return true;
        }

        return false;
    }

    // focus is tracked internally

    private boolean isControlDown() {
        long handle = Minecraft.getInstance().getWindow().handle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    private boolean isValidChar(char chr) {
        // Allow letters, numbers, spaces, and common symbols
        return chr >= 32 && chr != 127;
    }

    private void onTextChange() {
        if (onTextChanged != null) {
            onTextChanged.accept(text);
        }
    }

    public void setFocused(boolean focused) {
        if (this.isFocused != focused) {
            this.isFocused = focused;
            if (focused) {
                cursorPosition = text.length();
            }
        }
    }

    public boolean isFocused() {
        return isFocused;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
        this.cursorPosition = text.length();
        onTextChange();
    }

    public void clear() {
        setText("");
    }
}
