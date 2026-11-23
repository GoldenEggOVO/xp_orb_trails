package dev.goldeneggovo.xporbtrails.test;

import dev.goldeneggovo.xporbtrails.*;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

public final class TrailClientProbe {
    private record Step(BooleanSupplier ready, Runnable action) { }
    private static final ArrayDeque<Step> STEPS = new ArrayDeque<>();
    private static final Minecraft CLIENT = Minecraft.getInstance();
    private static final long START = System.nanoTime();
    private static Function<Screen, Screen> configScreen;
    private static Path configDirectory;
    private static boolean started, finished;
    private static ExperienceOrb movingOrb;
    private static Object pickedTrail;
    private static CompletableFuture<Void> reload;
    private static int assertions;
    private static volatile boolean capturePending;
    private static volatile Throwable captureFailure;

    private TrailClientProbe() { }

    public static void configure(Path directory, Function<Screen, Screen> factory) {
        configDirectory = directory;
        configScreen = factory;
    }

    public static void tick() {
        if (finished || configScreen == null) return;
        try {
            if (captureFailure != null) throw new AssertionError("Screenshot verification failed", captureFailure);
            if (System.nanoTime() - START >= 180_000_000_000L) throw new AssertionError("Client probe timed out");
            if (!started) {
                if (CLIENT.level == null || CLIENT.player == null || CLIENT.gui.overlay() != null) return;
                started = true;
                plan();
            }
            if (movingOrb != null) {
                movingOrb.setPos(movingOrb.position().add(0.18, 0.015, 0));
                TrailRenderer.track(movingOrb);
            }
            Step next = STEPS.peek();
            if (next != null && next.ready.getAsBoolean()) {
                STEPS.remove();
                next.action.run();
            }
        } catch (Throwable exception) {
            exception.printStackTrace();
            finish(false, exception.toString());
        }
    }

    private static void plan() {
        step(() -> {
            String location = XpOrbTrailsClient.class.getProtectionDomain().getCodeSource().getLocation().toString();
            check(location.contains(".jar"), "Probe must load the packaged mod: " + location);
            check(XpOrbTrailsClient.class.getResource("/assets/xporbtrails/profiles/comet-pennant.json") != null,
                    "The packaged mod must include the Comet Pennant profile");
            if (Boolean.getBoolean("xporb.expectShaders")) {
                try {
                    Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    Object iris = api.getMethod("getInstance").invoke(null);
                    check((boolean) api.getMethod("isShaderPackInUse").invoke(iris), "Iris shader pack must be active");
                    System.out.println("XPORB_SHADER_PROBE iris_active=true");
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError("Iris must be loaded for the shader probe", error);
                }
            }
            boolean restart = Boolean.getBoolean("xporb.probeRestart");
            check(XpOrbTrailsClient.CONFIG.width == (restart ? 0.37 : 0.42), "Existing configuration must load");
            check(XpOrbTrailsClient.CONFIG.savedPresets.stream().anyMatch(p -> p.name.equals(restart ? "Probe Color" : "Existing")),
                    "Saved colors must survive startup");
            if (restart) {
                check(XpOrbTrailsClient.CONFIG.savedProfiles.stream().anyMatch(p -> p.name.equals("Probe Profile")),
                        "Saved profiles must survive restart");
                finish(true, "Restart persistence verified");
            }
            CLIENT.options.guiScale().set(2);
            CLIENT.resizeGui();
            CLIENT.gui.setScreen(configScreen.apply(CLIENT.gui.screen()));
            check(CLIENT.gui.screen() instanceof TrailConfigScreen, "Loader config button must create settings");
            check("GENERAL".equals(field(CLIENT.gui.screen(), "page").toString()), "Settings must open on General");
            var tabs = CLIENT.gui.screen().children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .limit(5).map(b -> b.getMessage().getString()).toList();
            check(tabs.equals(List.of("General", "Trail", "Pickup", "Orb Body", "Profiles")), "Unexpected tab order: " + tabs);
        });
        capture("menu-general-en");
        step(() -> {
            XpOrbTrailsClient.CONFIG.width = 0.44;
            click("pickup");
            XpOrbTrailsClient.CONFIG.pickupFlash = false;
            click("reset");
            check(XpOrbTrailsClient.CONFIG.pickupFlash && XpOrbTrailsClient.CONFIG.width == 0.44,
                    "Pickup reset must preserve trail settings");
        });
        capture("menu-pickup");
        step(() -> {
            click("general");
            check(widgets(CLIENT.gui.screen()).stream().anyMatch(w -> w.visible && w.active
                            && w.getMessage().equals(Component.translatable("screen.xporbtrails.open_config_folder"))),
                    "General footer must expose the config folder");
            XpOrbTrailsClient.CONFIG.renderRange = 71;
            click("reset");
            check(XpOrbTrailsClient.CONFIG.renderRange == new TrailConfig().renderRange,
                    "General reset must restore performance settings");
            click("profiles");
            check(Files.isDirectory(configDirectory.resolve("xp_orb_trails/profiles"))
                            && Files.isRegularFile(configDirectory.resolve("xp_orb_trails.json")),
                    "Settings and profiles must use the active instance's underscore paths");
            check(widgets(CLIENT.gui.screen()).stream().anyMatch(w -> w.visible && w.active
                            && w.getMessage().equals(Component.translatable("screen.xporbtrails.open_packs"))),
                    "Profiles footer must expose the packs folder");
            check(widgets(CLIENT.gui.screen()).stream().noneMatch(w -> w.getMessage().equals(Component.translatable("screen.xporbtrails.reset"))),
                    "Profiles must not reset other pages: " + widgets(CLIENT.gui.screen()).stream().map(w -> w.getMessage().getString() + "=" + w.active).toList());
        });
        capture("menu-profiles");
        step(() -> {
            click("profile.comet-pennant");
            var c = XpOrbTrailsClient.CONFIG;
            check(c.trailSprite.equals("sprite:trail/comet-pennant") && c.trailSpriteMode.equals("ribbon")
                            && c.flightEffect.equals("sprite:orb/luna-moth") && c.pickupFlashStyle.equals("sprite:pickup/collect-burst"),
                    "Comet Pennant must apply its bundled body, pickup and trail");
            click("open_preview");
        });
        waitTicks(36);
        capture("bundled-comet-pennant");
        step(() -> {
            CLIENT.gui.screen().onClose();
            click("profile.standard");
        });
        step(() -> {
            click("profile.starfall");
            var c = XpOrbTrailsClient.CONFIG;
            check(c.customCrossSectionEnabled && c.crossSectionRadii.length == 10
                            && c.flightEffect.equals("file:orb/starfall") && c.pickupFlashStyle.equals("file:pickup/starfall"),
                    "The bundled Starfall profile must apply its section and both emitters");
            click("open_preview");
        });
        waitTicks(42);
        capture("starfall-flight");
        step(() -> CLIENT.gui.screen().onClose());
        step(() -> {
            click("pickup");
            click("preview_pickup");
        });
        waitTicks(16);
        capture("starfall-pickup");
        step(() -> {
            CLIENT.gui.screen().onClose();
            click("profiles");
            click("profile.standard");
        });
        if (System.getProperty("xporb.spriteDemo") != null || Boolean.getBoolean("xporb.layoutDemo")) {
            step(() -> {
                if (widgets(CLIENT.gui.screen()).stream().noneMatch(w -> w.getMessage().getString().equals(
                        Component.translatable("screen.xporbtrails.reload_styles").getString()))) click("file_styles");
                click("reload_styles");
            });
        }
        if (System.getProperty("xporb.spriteDemo") != null) {
            for (String mode : List.of("ribbon", "stamps")) {
                boolean ribbon = mode.equals("ribbon");
                step(() -> {
                    click(Component.literal(ribbon ? "Starlight Ribbon" : "Starlight Sparks"));
                    TrailConfig c = XpOrbTrailsClient.CONFIG;
                    check(!c.showVanillaOrb && c.flightEffect.equals("sprite:orb/starlight-crystal")
                                    && c.trailSpriteMode.equals(mode) && c.trailSprite.equals(ribbon ? "sprite:trail/starlight-weave" : "sprite:trail/comet-strip")
                                    && c.pickupFlashStyle.equals("sprite:pickup/collect-burst"),
                            "The installed demo must apply its body, trail and pickup in " + mode + " mode");
                    click("open_preview");
                });
                waitTicks(36);
                capture(ribbon ? "starlight-ribbon-preview" : "starlight-sparks-preview");
                step(() -> CLIENT.gui.screen().onClose());
            }
        }
        if (Boolean.getBoolean("xporb.layoutDemo")) {
            step(TrailClientProbe::verifyEditablePickup);
            step(() -> check(widgets(CLIENT.gui.screen()).stream()
                    .noneMatch(w -> w.getMessage().getString().equals("Retired layout probe")),
                    "Profiles from the retired styles directory must stay out of the menu"));
            step(() -> reload = language("zh_cn"));
            await(() -> reload.isDone() && CLIENT.gui.overlay() == null);
            for (String sample : List.of("Rune Chain", "Star Prints", "Moth Echo", "Comet Pennant")) {
                step(() -> {
                    if (sample.equals("Comet Pennant")) click("profile.comet-pennant");
                    else click(Component.literal(sample));
                    var c = XpOrbTrailsClient.CONFIG;
                    boolean selected = switch (sample) {
                        case "Rune Chain" -> c.trailSprite.equals("sprite:trail/rune-link") && c.ribbonMapping.equals("tile")
                                && c.ribbonRepeatLength == 0.38;
                        case "Star Prints" -> c.trailSprite.equals("sprite:trail/comet-strip") && c.fixedStamps;
                        case "Moth Echo" -> c.trailSprite.equals("body") && !c.showVanillaOrb;
                        default -> c.trailSprite.equals("sprite:trail/comet-pennant") && c.trailSpriteMode.equals("ribbon");
                    };
                    check(selected && c.flightEffect.equals("sprite:orb/luna-moth"), "File style must apply " + sample);
                    click("open_preview");
                });
                waitTicks(36);
                capture(sample.toLowerCase(java.util.Locale.ROOT).replace(' ', '-'));
                step(() -> CLIENT.gui.screen().onClose());
            }
            if (Boolean.getBoolean("xporb.lineDemo")) {
                for (var option : Map.of("Stretch", "stretch", "Tile", "tile", "DistributePerSegment", "distribute_per_segment",
                        "RepeatPerSegment", "repeat_per_segment", "Static", "static").entrySet()) {
                    step(() -> {
                        click(Component.literal(option.getKey()));
                        check(XpOrbTrailsClient.CONFIG.ribbonMapping.equals(option.getValue()), "File mode must apply " + option.getKey());
                        click("open_preview");
                    });
                    waitTicks(36);
                    capture("uv-" + option.getValue());
                    step(() -> CLIENT.gui.screen().onClose());
                }
            }
            step(() -> reload = language("en_us"));
            await(() -> reload.isDone() && CLIENT.gui.overlay() == null);
        }
        step(() -> {
            click("trail");
            click("reset");
            check(XpOrbTrailsClient.CONFIG.width == 0.1, "Trail reset must restore default width");
            click("open_preview");
            check(CLIENT.gui.screen() instanceof TrailPreviewScreen, "Preview must open");
        });
        capture("preview");
        step(() -> {
            CLIENT.gui.screen().onClose();
            reload = language("zh_cn");
        });
        await(() -> reload.isDone() && CLIENT.gui.overlay() == null);
        step(() -> check("尾迹".equals(Component.translatable("screen.xporbtrails.trail").getString()), "Chinese menu must load"));
        capture("menu-trail-zh");
        step(() -> reload = language("en_us"));
        await(() -> reload.isDone() && CLIENT.gui.overlay() == null);
        step(() -> {
            CLIENT.gui.screen().onClose();
            CLIENT.gui.setScreen(null);
            KeyMapping key = java.util.Arrays.stream(CLIENT.options.keyMappings)
                    .filter(k -> k.getName().equals("key.xporbtrails.open_settings")).findFirst().orElseThrow();
            key.setKey(InputConstants.getKey("key.keyboard.f8"));
            KeyMapping.resetMapping();
            KeyMapping.click(key.getKey());
        });
        await(() -> CLIENT.gui.screen() instanceof TrailConfigScreen);
        step(() -> {
            CLIENT.gui.screen().onClose();
            CLIENT.gui.setScreen(null);
            CLIENT.options.pauseOnLostFocus = false;
            XpOrbTrailsClient.CONFIG = new TrailConfig();
            trails().clear();
            command("execute at @p run summon minecraft:experience_orb ~10 ~1 ~ {Value:1,Count:3,NoGravity:1b,Tags:[\"xporb_probe\"]}");
        });
        await(() -> !trails().isEmpty());
        step(() -> {
            pickedTrail = trails().values().iterator().next();
            command("execute at @p run tp @e[type=minecraft:experience_orb,tag=xporb_probe] ~ ~ ~");
        });
        await(() -> (long) field(pickedTrail, "pickupAt") != 0);
        capture("pickup-real");
        step(() -> {
            check(field(pickedTrail, "pickupPosition") != null, "Server pickup must confirm the flash position");
            command("kill @e[type=minecraft:experience_orb,tag=xporb_probe]");
            trails().clear();
            movingOrb = orb(100100);
        });
        waitTicks(4);
        step(() -> {
            CLIENT.level.removeEntity(movingOrb.getId(), Entity.RemovalReason.DISCARDED);
            movingOrb = null;
        });
        waitTicks(2);
        step(() -> check(snapshot().stream().noneMatch(t -> (double) field(t, "flashProgress") >= 0),
                "An orb disappearing without a pickup packet must not flash"));
        int index = 0;
        for (int sides : new int[]{2, 3, 8, 32}) {
            for (boolean glow : new boolean[]{true, false}) {
                int id = 100200 + index++;
                step(() -> {
                    trails().clear();
                    if (movingOrb != null) CLIENT.level.removeEntity(movingOrb.getId(), Entity.RemovalReason.DISCARDED);
                    XpOrbTrailsClient.CONFIG.crossSectionSides = sides;
                    XpOrbTrailsClient.CONFIG.additiveGlow = glow;
                    XpOrbTrailsClient.CONFIG.startColor = 0xff00ff;
                    XpOrbTrailsClient.CONFIG.endColor = 0xff00ff;
                    movingOrb = orb(id);
                });
                waitTicks(8);
                step(() -> check(!snapshot().isEmpty(), "Render extraction must produce a visible trail"));
                capture("trail-" + sides + (glow ? "-glow" : "-alpha"));
            }
        }
        step(() -> {
            movingOrb = null;
            XpOrbTrailsClient.CONFIG.enabled = false;
        });
        waitTicks(2);
        step(() -> {
            check(snapshot().isEmpty() && trails().isEmpty(), "Disabling must clear render and world state");
            XpOrbTrailsClient.CONFIG = new TrailConfig();
            XpOrbTrailsClient.CONFIG.width = 0.37;
            XpOrbTrailsClient.CONFIG.savedPresets.add(new TrailConfig.SavedPreset("Probe Color", 0x123456, 0xabcdef));
            XpOrbTrailsClient.CONFIG.savedProfiles.add(new TrailConfig.SavedProfile("Probe Profile", XpOrbTrailsClient.CONFIG));
            XpOrbTrailsClient.saveConfig();
            CLIENT.disconnectWithSavingScreen();
        });
        await(() -> CLIENT.level == null);
        waitTicks(2);
        step(() -> {
            check(trails().isEmpty() && snapshot().isEmpty() && field(null, "lastLevel") == null, "Disconnect must release all trail state");
            finish(true, "Packaged client and world checks passed");
        });
    }

    private static ExperienceOrb orb(int id) {
        Vec3 camera = CLIENT.gameRenderer.mainCamera().position();
        ExperienceOrb orb = new ExperienceOrb(CLIENT.level, camera.x - 1, camera.y + 0.5, camera.z + 3, 1);
        orb.setId(id);
        orb.setNoGravity(true);
        CLIENT.level.addEntity(orb);
        return orb;
    }

    private static void command(String command) {
        var server = CLIENT.getSingleplayerServer();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
    }

    private static CompletableFuture<Void> language(String language) {
        CLIENT.getLanguageManager().setSelected(language);
        CLIENT.options.languageCode = language;
        return CLIENT.reloadResourcePacks();
    }

    private static void step(Runnable action) { STEPS.add(new Step(() -> true, action)); }
    private static void await(BooleanSupplier ready) { STEPS.add(new Step(ready, () -> { })); }
    private static void waitTicks(int ticks) {
        AtomicInteger remaining = new AtomicInteger(ticks);
        await(() -> remaining.decrementAndGet() <= 0);
    }

    private static void capture(String name) {
        waitTicks(3);
        step(() -> {
            capturePending = true;
            Screenshot.takeScreenshot(CLIENT.gameRenderer.mainRenderTarget(), image -> {
                try {
                    Path directory = CLIENT.gameDirectory.toPath().resolve("screenshots");
                    Files.createDirectories(directory);
                    image.writeToFile(directory.resolve("probe-" + name + ".png"));
                    if (name.startsWith("trail-")) {
                        int pixels = 0;
                        for (int y = 0; y < image.getHeight(); y++) {
                            for (int x = 0; x < image.getWidth(); x++) {
                                int color = image.getPixel(x, y);
                                int red = (color >> 16) & 255, green = (color >> 8) & 255, blue = color & 255;
                                if (red > 100 && blue > 100 && Math.min(red, blue) - green > 25) pixels++;
                            }
                        }
                        check(pixels >= 3, name + " must draw magenta trail pixels, found " + pixels);
                    }
                } catch (Throwable exception) {
                    captureFailure = exception;
                } finally {
                    image.close();
                    capturePending = false;
                }
            });
        });
        await(() -> !capturePending);
    }

    private static List<AbstractWidget> widgets(ContainerEventHandler parent) {
        List<AbstractWidget> result = new ArrayList<>();
        for (var child : parent.children()) {
            if (child instanceof AbstractWidget widget) result.add(widget);
            if (child instanceof ContainerEventHandler container) result.addAll(widgets(container));
        }
        return result;
    }

    private static void verifyEditablePickup() {
        Path root = configDirectory.resolve("xp_orb_trails");
        Path ring = root.resolve("pickup/effects/ring.json");
        try {
            check(Files.isRegularFile(root.resolve("profiles/standard.json")), "Standard must be an ordinary file profile");
            for (String style : List.of("soft", "star", "ring", "reflow", "shards", "spiral")) {
                check(Files.isRegularFile(root.resolve("pickup/effects/" + style + ".json")), "Missing pickup file " + style);
            }
            String original = Files.readString(ring);
            try {
                var edited = com.google.gson.JsonParser.parseString(original).getAsJsonObject();
                edited.addProperty("name", "Probe Edited Ring");
                Files.writeString(ring, edited.toString());
                click("reload_styles");
                click("pickup");
                click(Component.translatable("screen.xporbtrails.flash_style.soft"));
                click(Component.literal("Probe Edited Ring"));
                check(XpOrbTrailsClient.CONFIG.pickupFlashStyle.equals("file:pickup/ring"), "Edited flash must apply by its file id");
                click("profiles");
            } finally {
                Files.writeString(ring, original);
                if (CLIENT.gui.screen() instanceof TrailConfigScreen && field(CLIENT.gui.screen(), "page").toString().equals("PROFILES")) click("reload_styles");
            }
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
    }

    private static void click(String key) {
        click(Component.translatable("screen.xporbtrails." + key));
    }

    private static void click(Component label) {
        Button button = widgets(CLIENT.gui.screen()).stream().filter(w -> w instanceof Button && w.active && w.getMessage().getString().equals(label.getString()))
                .map(w -> (Button) w).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + label.getString()));
        button.onPress(new net.minecraft.client.input.InputWithModifiers() {
            public int input() { return InputConstants.MOUSE_BUTTON_LEFT; }
            public int modifiers() { return 0; }
        });
    }

    private static Object field(Object target, String name) {
        try {
            var field = (target == null ? TrailRenderer.class : target.getClass()).getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }

    @SuppressWarnings("unchecked") private static Map<Integer, Object> trails() { return (Map<Integer, Object>) field(null, "TRAILS"); }
    @SuppressWarnings("unchecked") private static List<Object> snapshot() { return (List<Object>) field(null, "renderTrails"); }
    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
        assertions++;
    }

    private static void finish(boolean success, String message) {
        finished = true;
        try {
            Path result = CLIENT.gameDirectory.toPath().resolve(Boolean.getBoolean("xporb.probeRestart") ? "probe-restart.json" : "probe-result.json");
            Files.writeString(result, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(
                    Map.of("success", success, "message", message, "assertions", assertions, "config", configDirectory.toString())));
        } catch (java.io.IOException exception) { throw new AssertionError(exception); }
        CLIENT.stop();
    }
}
