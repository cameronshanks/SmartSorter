package net.shaddii.smartsorter.widget;

import org.joml.Matrix3x2f;

import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class CheckboxWidget extends AbstractWidget {
    private boolean checked;
    private final Consumer<Boolean> onToggle;
    private final Font textRenderer;

    public CheckboxWidget(int x, int y, int width, int height, Component message,
                          Font textRenderer, boolean initialState,
                          Consumer<Boolean> onToggle) {
        super(x, y, width, height, message);
        this.textRenderer = textRenderer;
        this.checked = initialState;
        this.onToggle = onToggle;
    }

    @Override
    public void onClick(MouseButtonEvent click, boolean doubled) {
        checked = !checked;
        if (onToggle != null) {
            onToggle.accept(checked);
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Draw checkbox box (SMALLER)
        int boxSize = 9;
        int boxX = getX();
        int boxY = getY() + (height - boxSize) / 2;

        // Box background
        context.fill(boxX, boxY, boxX + boxSize, boxY + boxSize, 0xFF000000);
        context.fill(boxX + 1, boxY + 1, boxX + boxSize - 1, boxY + boxSize - 1,
                isHovered() ? 0xFFEEEEEE : 0xFFCCCCCC);

        // Checkmark
        if (checked) {
            context.fill(boxX + 2, boxY + 2, boxX + boxSize - 2, boxY + boxSize - 2, 0xFF00AA00);
        }

        // Label (SCALED DOWN)
        float scale = 0.65f;
        int labelX = boxX + boxSize + 3;
        int labelY = getY() + (height - 6) / 2;

        
        Matrix3x2f oldMatrix = new Matrix3x2f(context.pose());
        Matrix3x2f scaleMatrix = new Matrix3x2f().scaling(scale, scale);
        context.pose().mul(scaleMatrix);

        Matrix3x2f translateMatrix = new Matrix3x2f().translation(labelX / scale, labelY / scale);
        context.pose().mul(translateMatrix);

        context.text(textRenderer, getMessage(), 0, 0, 0xFFFFFFFF, false);
        context.pose().set(oldMatrix);
    }

    public void setChecked(boolean checked) {
        this.checked = checked;
    }

    public boolean isChecked() {
        return checked;
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput builder) {
        this.defaultButtonNarrationText(builder);
    }

    // Builder pattern
    public static Builder builder(Component text, Font textRenderer) {
        return new Builder(text, textRenderer);
    }

    public static class Builder {
        private final Component text;
        private final Font textRenderer;
        private int x, y;
        private int width = 100, height = 20;
        private boolean initialState = false;
        private Consumer<Boolean> callback;

        Builder(Component text, Font textRenderer) {
            this.text = text;
            this.textRenderer = textRenderer;
        }

        public Builder pos(int x, int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public Builder dimensions(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder checked(boolean state) {
            this.initialState = state;
            return this;
        }

        public Builder callback(Consumer<Boolean> callback) {
            this.callback = callback;
            return this;
        }

        public CheckboxWidget build() {
            return new CheckboxWidget(x, y, width, height, text, textRenderer,
                    initialState, callback);
        }
    }
}