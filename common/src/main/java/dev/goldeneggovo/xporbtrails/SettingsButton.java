package dev.goldeneggovo.xporbtrails;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import static dev.goldeneggovo.xporbtrails.TrailConfigScreen.ACCENT;
import static dev.goldeneggovo.xporbtrails.TrailConfigScreen.RULE;
import static dev.goldeneggovo.xporbtrails.TrailConfigScreen.TEXT;

class SettingsButton extends Button {
    enum Style { TAB, SIDEBAR, CONTROL, QUIET, PRIMARY, TOGGLE, FOLD, DELETE }

    private final Style style;
    private final int[] swatches;
    boolean selected;

    SettingsButton(int x, int y, int width, Component label, OnPress action, Style style, boolean selected, int... swatches) {
        super(x, y, width, 20, label, action, DEFAULT_NARRATION);
        this.style = style;
        this.swatches = swatches;
        this.selected = selected;
    }

    @Override protected MutableComponent createNarrationMessage() {
        if (swatches.length != 2) return super.createNarrationMessage();
        var label = getMessage().copy()
                .append(", ").append(Component.translatable("screen.xporbtrails.start_color")).append(" #%06X".formatted(swatches[0]))
                .append(", ").append(Component.translatable("screen.xporbtrails.end_color")).append(" #%06X".formatted(swatches[1]));
        return wrapDefaultNarrationMessage(label);
    }

    @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY();
        boolean highlight = active && isHoveredOrFocused();
        int background = switch (style) {
            case PRIMARY -> !active ? 0xFF1A2523 : highlight ? 0xFF35694E : 0xFF27543F;
            case DELETE -> highlight ? 0xFF4A3432 : 0xFF302727;
            case CONTROL -> !active ? 0xFF1A2523 : highlight ? 0xFF334C40 : 0xFF21332D;
            case TAB, SIDEBAR -> selected || highlight ? 0xFF263C32 : 0;
            default -> highlight ? 0xFF21332D : 0;
        };
        if (background != 0) graphics.fill(x, y, getRight(), getBottom(), background);
        if (style == Style.CONTROL || style == Style.DELETE || style == Style.PRIMARY || isFocused()) {
            graphics.outline(x, y, width, height, isFocused() ? ACCENT : style == Style.PRIMARY && active ? 0xFF66997C : RULE);
        }
        if (selected && style == Style.SIDEBAR) graphics.fill(x, y, x + 3, getBottom(), ACCENT);
        if (selected && style == Style.TAB) graphics.fill(x, getBottom() - 2, getRight(), getBottom(), ACCENT);

        var font = Minecraft.getInstance().font;
        Component label = getMessage();
        int middle = y + height / 2;
        int textY = y + (height - font.lineHeight) / 2;
        int ink = !active ? 0xFF94A19B : style == Style.PRIMARY ? 0xFFF5F7F5
                : style == Style.DELETE ? 0xFFFFC8BF : TEXT;
        int swatchSpace = swatches.length * 9;
        int textX = x + Math.max(5, (width - font.width(label) - swatchSpace) / 2);
        for (int rgb : swatches) {
            graphics.fill(textX, middle - 3, textX + 6, middle + 3, 0xFF000000 | rgb);
            textX += 9;
        }
        switch (style) {
            case SIDEBAR, QUIET -> textX = x + 8;
            case FOLD -> {
                textX = x + 13;
                for (int i = 0; i < 3; i++) {
                    if (selected) graphics.fill(x + 2 + i, middle - 1 + i, x + 7 - i, middle + i, ACCENT);
                    else graphics.fill(x + 3 + i, middle - 2 + i, x + 4 + i, middle + 3 - i, ACCENT);
                }
            }
            case TOGGLE -> {
                int left = getRight() - 25;
                graphics.fill(left, middle - 4, left + 21, middle + 5, selected ? 0xFF346648 : RULE);
                int thumb = left + (selected ? 13 : 2);
                graphics.fill(thumb, middle - 3, thumb + 6, middle + 4, selected ? ACCENT : 0xFFB3BDB7);
                textX = left - 6 - font.width(label);
            }
        }
        if (font.width(label) <= width - 12 - swatchSpace) {
            graphics.text(font, label, textX, textY, ink, false);
        } else {
            graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE)
                    .acceptScrollingWithDefaultCenter(label, x + 5 + swatchSpace, getRight() - 5, y, getBottom());
        }
    }
}
