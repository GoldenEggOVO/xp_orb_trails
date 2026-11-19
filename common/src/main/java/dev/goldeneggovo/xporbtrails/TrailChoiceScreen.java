package dev.goldeneggovo.xporbtrails;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;

final class TrailChoiceScreen extends Screen {
    record Choice(Component label, Runnable select, Component disabledReason) {
        Choice(Component label, Runnable select) { this(label, select, null); }
    }
    private final Screen parent;
    private final List<Choice> choices;
    private int panelX, panelY, panelWidth, panelHeight;

    TrailChoiceScreen(Screen parent, Component title, List<Choice> choices) {
        super(title);
        this.parent = parent;
        this.choices = choices;
    }

    @Override protected void init() {
        panelWidth = Math.min(280, width - 24);
        panelHeight = Math.min(height - 12, 72 + choices.size() * 24);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        var list = addRenderableWidget(new TrailSettingsList(panelX + 6, panelY + 30, panelWidth - 12, panelHeight - 64));
        for (Choice choice : choices) {
            var button = new SettingsButton(0, 0, panelWidth - 24, choice.label(), b -> {
                choice.select().run();
                onClose();
            }, SettingsButton.Style.CONTROL, false);
            if (choice.disabledReason() != null) {
                button.active = false;
                button.setTooltip(Tooltip.create(choice.disabledReason()));
            }
            list.row(Component.empty(), button);
        }
        addRenderableWidget(new SettingsButton(width / 2 - 48, panelY + panelHeight - 27, 96,
                Component.translatable("gui.back"), b -> onClose(), SettingsButton.Style.CONTROL, false));
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, TrailConfigScreen.PANEL);
        graphics.outline(panelX, panelY, panelWidth, panelHeight, TrailConfigScreen.RULE);
        graphics.text(font, title, panelX + 15, panelY + 12, TrailConfigScreen.TEXT, false);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
