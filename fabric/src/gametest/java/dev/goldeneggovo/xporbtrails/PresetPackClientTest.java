package dev.goldeneggovo.xporbtrails;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Vector2d;

final class PresetPackClientTest {
    static void run(ClientGameTestContext context, Path example) {
        var directory = context.computeOnClient(client -> XpOrbTrailsClient.assetDirectory().resolve("packs"));
        String zip = example.getFileName().toString();
        try {
            Files.copy(example, directory.resolve(zip), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var reload = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected("zh_cn");
            client.options.languageCode = "zh_cn";
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null);
        var parent = context.computeOnClient(client -> client.gui.screen());
        var opened = new ArrayList<Path>();
        context.runOnClient(client -> {
            client.options.guiScale().set(2);
            client.getWindow().setWindowed(854, 480);
            client.gui.setScreen(new TrailConfigScreen(parent, opened::add));
        });
        context.waitFor(client -> client.gui.screen().width < 680);
        checkFolder(context, "open_config_folder", directory.getParent(), opened);
        boolean enabled = context.computeOnClient(client -> XpOrbTrailsClient.CONFIG.enabled);
        clickVisible(context, "enabled");
        if (context.computeOnClient(client -> XpOrbTrailsClient.CONFIG.enabled) == enabled)
            throw new AssertionError("General toggle did not respond to the mouse");
        clickVisible(context, "enabled");
        context.clickScreenButton("screen.xporbtrails.profiles");
        checkFolder(context, "open_packs", directory, opened);
        checkScrollbar(context);
        context.runOnClient(client -> client.getWindow().setWindowed(1500, 850));
        context.waitFor(client -> client.gui.screen().width >= 680);
        checkScrollbar(context);
        int expanded = context.computeOnClient(client -> list(client.gui.screen()).children().size());
        clickVisible(context, "file_styles");
        if (context.computeOnClient(client -> list(client.gui.screen()).children().size()) >= expanded)
            throw new AssertionError("File Profiles did not fold after dragging the scrollbar");
        clickVisible(context, "file_styles");
        TrailClientTest.click(context, "screen.xporbtrails.reload_styles");
        var styles = context.computeOnClient(client -> XpOrbTrailsClient.fileStyles().stream()
                .filter(s -> zip.equals(s.pack())).toList());
        if (styles.size() != 4) throw new AssertionError("Expected the four profiles from " + zip);
        context.getInput().setCursorPos(2, 2);
        context.waitTicks(2);
        context.takeScreenshot("zip-profiles-zh");
        for (String name : List.of("Brass Clockwork", "Ion Drive", "Lantern Bloom", "Koi Stream")) {
            var style = styles.stream().filter(s -> name.equals(s.name())).findFirst().orElseThrow();
            TrailClientTest.click(context, style.displayName().getString());
            context.runOnClient(client -> {
                var c = XpOrbTrailsClient.CONFIG;
                if (!c.flightEffect.equals(style.settings().flightEffect)
                        || XpOrbTrailsClient.sprite(AssetRole.ORB, c.flightEffect) == null
                        || XpOrbTrailsClient.sprite(AssetRole.PICKUP, c.pickupFlashStyle) == null
                        || XpOrbTrailsClient.sprite(AssetRole.TRAIL, c.trailSprite) == null)
                    throw new AssertionError("Missing packed artwork for " + name);
            });
        }
        TrailClientTest.click(context, "screen.xporbtrails.open_preview");
        long openedAt = System.nanoTime();
        context.waitFor(client -> System.nanoTime() - openedAt >= 2_000_000_000L);
        context.runOnClient(client -> {
            if (!(client.gui.screen() instanceof TrailPreviewScreen)) throw new AssertionError("Expected preview");
        });
        context.takeScreenshot("zip-koi-preview");
        context.runOnClient(client -> client.gui.setScreen(parent));
        System.out.println("XPORB_PACK verified folder targets, compact and wide scrollbar drags, menu controls, 4 pack selections, 12 sprite references, and Koi preview");
    }

    private static void checkFolder(ClientGameTestContext context, String key, Path expected, List<Path> opened) {
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            var list = list(screen);
            var folder = TrailClientTest.findControl(screen, "screen.xporbtrails." + key);
            var names = List.of(Component.translatable("screen.xporbtrails.open_config_folder"),
                    Component.translatable("screen.xporbtrails.open_packs"));
            if (folder.getY() < list.getBottom() || screen.children().stream()
                    .filter(e -> e instanceof AbstractWidget w && names.contains(w.getMessage())).count() != 1
                    || list.children().stream().anyMatch(r -> names.stream().anyMatch(r::matches)))
                throw new AssertionError("Expected a single folder button in the footer: " + key);
            opened.clear();
        });
        clickVisible(context, key);
        context.runOnClient(client -> {
            if (!opened.equals(List.of(expected.toAbsolutePath().normalize())))
                throw new AssertionError(key + " opened " + opened + " instead of " + expected);
        });
    }

    private static TrailSettingsList list(Screen screen) {
        return screen.children().stream().filter(TrailSettingsList.class::isInstance)
                .map(TrailSettingsList.class::cast).findFirst().orElseThrow();
    }

    private static void checkScrollbar(ClientGameTestContext context) {
        var list = context.computeOnClient(client -> list(client.gui.screen()));
        context.runOnClient(client -> {
            if (list.maxScrollAmount() <= 0 || list.scrollAmount() != 0)
                throw new AssertionError("Expected an overflowing list at its top");
        });
        for (boolean down : List.of(true, false)) {
            var from = context.computeOnClient(client -> new Vector2d(
                    down ? list.getRight() - list.scrollbarWidth() + 0.5 : list.getRight() - 0.5,
                    list.scrollBarY() + 2));
            int endY = context.computeOnClient(client -> down ? list.getBottom() + 2 : list.getY() - 2);
            moveCursor(context, from.x, from.y);
            context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
            try {
                moveCursor(context, from.x + 12, (from.y + endY) / 2);
                context.runOnClient(client -> {
                    if (list.scrollAmount() <= 0 || list.scrollAmount() >= list.maxScrollAmount())
                        throw new AssertionError("Scrollbar drag did not move through the list");
                });
                moveCursor(context, from.x + 12, endY);
            } finally {
                context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
            }
            context.runOnClient(client -> {
                if (list.scrollAmount() != (down ? list.maxScrollAmount() : 0))
                    throw new AssertionError("Scrollbar drag did not reach the list boundary");
            });
        }
    }

    private static void clickVisible(ClientGameTestContext context, String key) {
        var point = context.computeOnClient(client -> {
            var widget = TrailClientTest.findControl(client.gui.screen(), "screen.xporbtrails." + key);
            return new Vector2d(widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0);
        });
        moveCursor(context, point.x, point.y);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
    }

    private static void moveCursor(ClientGameTestContext context, double x, double y) {
        var point = context.computeOnClient(client -> {
            var window = client.getWindow();
            return new Vector2d(x * window.getScreenWidth() / window.getGuiScaledWidth(),
                    y * window.getScreenHeight() / window.getGuiScaledHeight());
        });
        context.getInput().setCursorPos(point.x, point.y);
        context.waitTicks(2);
    }

    private PresetPackClientTest() {}
}
