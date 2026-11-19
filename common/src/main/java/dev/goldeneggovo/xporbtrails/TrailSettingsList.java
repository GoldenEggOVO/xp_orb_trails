package dev.goldeneggovo.xporbtrails;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import java.util.List;

final class TrailSettingsList extends ContainerObjectSelectionList<TrailSettingsList.Row> {
    private static final int INDENT = 22;
    private int depth;

    TrailSettingsList(int x, int y, int width, int height) {
        super(Minecraft.getInstance(), width, height, y, 24);
        setX(x);
        centerListVertically = false;
    }

    @Override public int getRowWidth() { return getWidth() - 18; }

    @Override protected void extractListBackground(GuiGraphicsExtractor graphics) { }
    @Override protected void extractListSeparators(GuiGraphicsExtractor graphics) { }

    @Override protected void extractScrollbar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!scrollable()) return;
        int x = scrollBarX() + 1;
        int y = scrollBarY();
        graphics.fill(x + 1, getY(), x + 2, getBottom(), TrailConfigScreen.RULE);
        graphics.fill(x, y, x + 3, y + scrollerHeight(),
                isOverScrollbar(mouseX, mouseY) ? TrailConfigScreen.ACCENT : 0xFF688B7D);
    }

    void nested(Runnable content) {
        depth++;
        try { content.run(); }
        finally { depth--; }
    }

    void row(Component label, AbstractWidget control) {
        Row row = new Row(label, List.of(control));
        int lines = minecraft.font.split(label, row.labelWidth(getRowWidth() - 4)).size();
        addEntry(row, Math.max(24, lines * minecraft.font.lineHeight + 8));
    }

    void note(Component label) {
        Row row = new Row(label, List.of());
        int lines = minecraft.font.split(label, row.labelWidth(getRowWidth() - 4)).size();
        addEntry(row, Math.max(18, lines * minecraft.font.lineHeight + 8));
    }

    void actions(AbstractWidget primary, AbstractWidget secondary) {
        addEntry(new Row(Component.empty(), List.of(primary, secondary)), 24);
    }

    void paletteRow(Component label, TrailConfig palette, AbstractWidget button) {
        Row row = new Row(label, List.of(button));
        row.palette = palette;
        int lines = minecraft.font.split(label, row.labelWidth(getRowWidth() - 4)).size();
        addEntry(row, Math.max(26, lines * minecraft.font.lineHeight + 13));
    }

    final class Row extends ContainerObjectSelectionList.Entry<Row> {
        private final Component label;
        private final List<AbstractWidget> controls;
        private final int indent = depth * INDENT;
        private TrailConfig palette;

        Row(Component label, List<AbstractWidget> controls) {
            this.label = label;
            this.controls = controls;
        }

        boolean matches(Component name) {
            return label.equals(name) || controls.stream().anyMatch(w -> w.getMessage().equals(name));
        }

        private int labelWidth(int width) {
            if (controls.isEmpty()) return Math.max(1, width - indent);
            return Math.max(1, (width - indent - 12) / 2);
        }

        private void positionControl() {
            if (controls.isEmpty()) return;
            int x = getContentX() + indent;
            int right = getContentRight();
            int y = getContentY() + Math.max(0, (getContentHeight() - 20) / 2);
            if (controls.size() == 2) {
                var secondary = controls.getLast();
                int w = Math.max(1, Math.min((right - x) / 2, Math.max(44, minecraft.font.width(secondary.getMessage()) + 16)));
                secondary.setRectangle(w, 20, right - w, y);
                right -= w + 4;
            } else if (!label.getString().isEmpty()) {
                x += labelWidth(getContentWidth()) + 12;
            }
            controls.getFirst().setRectangle(Math.max(1, right - x), 20, x, y);
        }
        @Override public void setX(int x) { super.setX(x); positionControl(); }
        @Override public void setY(int y) { super.setY(y); positionControl(); }
        @Override public void setWidth(int width) { super.setWidth(width); positionControl(); }
        @Override public void setHeight(int height) { super.setHeight(height); positionControl(); }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getContentX() + indent;
            int right = getContentRight();
            int middle = getContentY() + getContentHeight() / 2;
            if (indent > 0) {
                graphics.fill(x - 8, getY(), x - 7, getY() + getHeight(), 0xFF32483E);
            }
            int labelWidth = labelWidth(getContentWidth());
            var lines = minecraft.font.split(label, labelWidth);
            int y = middle - minecraft.font.lineHeight * lines.size() / 2 - (palette == null ? 0 : 3);
            for (var line : lines) {
                graphics.text(minecraft.font, line, x, y, controls.isEmpty() ? 0xFFACC0B4 : TrailConfigScreen.TEXT, false);
                y += minecraft.font.lineHeight;
            }
            if (palette != null) {
                int swatchWidth = Math.min(64, labelWidth);
                double seconds = System.nanoTime() / 1.0E9;
                for (int i = 0; i < swatchWidth; i++) {
                    int rgb = TrailPreviewScreen.colorAt(palette, (double) i / Math.max(1, swatchWidth - 1), seconds);
                    graphics.fill(x + i, y + 1, x + i + 1, y + 5, 0xFF000000 | rgb);
                }
            }
            graphics.fill(x, getContentY() + getContentHeight(), right, getContentY() + getContentHeight() + 1, 0xFF2D3D38);
            for (var control : controls) control.extractRenderState(graphics, mouseX, mouseY, delta);
        }

        @Override public List<? extends GuiEventListener> children() { return controls; }
        @Override public List<? extends NarratableEntry> narratables() { return controls; }
    }
}
