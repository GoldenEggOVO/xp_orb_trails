package dev.goldeneggovo.xporbtrails;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

final class SettingsEditBox extends EditBox {
    SettingsEditBox(Font font, int x, int y, int width, int height, Component label) {
        super(font, x, y, width, height, label);
        setTextColor(TrailConfigScreen.TEXT);
        setTextShadow(false);
    }

    // Replace the border sprite while keeping the padded native caret and selection layout.
    @Override public boolean isBordered() { return false; }
    @Override public int getInnerWidth() { return getWidth() - 8; }

    @Override public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(getX(), getY(), getRight(), getBottom(), 0xFF121F1B);
        graphics.outline(getX(), getY(), width, height, isFocused() ? TrailConfigScreen.ACCENT : TrailConfigScreen.RULE);
        super.extractWidgetRenderState(graphics, mouseX, mouseY, delta);
    }
}
