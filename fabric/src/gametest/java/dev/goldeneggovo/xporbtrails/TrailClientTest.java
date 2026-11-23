package dev.goldeneggovo.xporbtrails;

import com.terraformersmc.modmenu.ModMenu;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.ExperienceOrb;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TrailClientTest implements FabricClientGameTest {
    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("xporb.collectionGallery")) {
            EffectCollectionClientTest.withPack(context, () -> EffectCollectionClientTest.gallery(context));
            return;
        }
        if (System.getProperty("xporb.testPack") != null) {
            EffectCollectionClientTest.withPack(context, () ->
                    PresetPackClientTest.run(context, java.nio.file.Path.of(System.getProperty("xporb.testPack"))));
            return;
        }
        if (Boolean.getBoolean("xporb.labProbe")) {
            EffectCollectionClientTest.withPack(context, () -> LabClientTest.run(context));
            return;
        }
        if (Boolean.getBoolean("xporb.advancedProbe")) {
            EffectCollectionClientTest.withPack(context, () -> AdvancedSceneClientTest.run(context));
            return;
        }
        if (Boolean.getBoolean("xporb.shaderProbe")) {
            ShaderTrailClientTest.run(context);
            return;
        }
        if (Boolean.getBoolean("xporb.probeRestart")) {
            context.runOnClient(client -> {
                check(XpOrbTrailsClient.CONFIG.fixedStamps && XpOrbTrailsClient.CONFIG.trailSprite.equals("body")
                                && XpOrbTrailsClient.CONFIG.ribbonMapping.equals("tile") && XpOrbTrailsClient.CONFIG.ribbonRepeatLength == 0.65,
                        "Stamp and ribbon layouts must survive a restart");
                check(XpOrbTrailsClient.CONFIG.width == 0.37, "Trail width must survive a client restart");
                check(!XpOrbTrailsClient.CONFIG.showVanillaOrb && XpOrbTrailsClient.CONFIG.orbScale == 1.6
                                && "ribbon".equals(XpOrbTrailsClient.CONFIG.trailSpriteMode)
                                && XpOrbTrailsClient.CONFIG.stampScale == 1.2 && XpOrbTrailsClient.CONFIG.stampSpacing == 2.5,
                        "Body and sprite-trail settings must survive a client restart");
                check(XpOrbTrailsClient.CONFIG.flightPulse && XpOrbTrailsClient.CONFIG.pulseSpeed == 1.5
                                && "file:orb/twin-orbit".equals(XpOrbTrailsClient.CONFIG.flightEffect)
                                && XpOrbTrailsClient.CONFIG.shardCount == 7 && XpOrbTrailsClient.CONFIG.spiralTurns == 1.5
                                && "file:pickup/shards".equals(XpOrbTrailsClient.CONFIG.pickupFlashStyle),
                        "Pickup and flight settings must survive a client restart");
                check(XpOrbTrailsClient.CONFIG.savedPresets.stream().anyMatch(p -> p.name.equals("Probe Color")),
                        "Saved colors must survive a client restart");
                check(XpOrbTrailsClient.CONFIG.savedProfiles.stream().anyMatch(p -> p.name.equals("Probe Profile")),
                        "Saved profiles must survive a client restart");
            });
            if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
            return;
        }
        AssetNamesClientTest.run(context);
        context.runOnClient(client -> {
            verifyCometStripName();
            client.options.guiScale().set(2);
            client.resizeGui();
            XpOrbTrailsClient.CONFIG = new TrailConfig();
            XpOrbTrailsClient.CONFIG.pickupFlashStyle = "file:pickup/ring";
            client.gui.setScreen(ModMenu.getConfigScreen("xporbtrails", client.gui.screen()));
        });
        context.waitForScreen(TrailConfigScreen.class);
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            for (String tab : List.of("general", "trail", "pickup", "orb_body", "profiles")) {
                check(screen.children().stream().anyMatch(child -> child instanceof AbstractWidget w &&
                        w.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("screen.xporbtrails." + tab).getString())),
                        "Missing settings page " + tab);
            }
            findControl(screen, "screen.xporbtrails.enabled");
            check(client.font.width("■") == 13, "The test font must replace the square glyph with blank space");
        });
        verifySwatches(context, "menu-swatches-en",
                new SwatchRow("screen.xporbtrails.start_color", 0xE6FF2A),
                new SwatchRow("screen.xporbtrails.end_color", 0x66FF00));
        context.clickScreenButton("screen.xporbtrails.trail");
        context.waitTicks(3);
        context.runOnClient(client -> {
            AbstractWidget width = findControl(client.gui.screen(), "screen.xporbtrails.width");
            var readout = new net.minecraft.client.input.MouseButtonEvent(width.getRight() - 5, width.getY() + 10,
                    new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0));
            client.gui.screen().mouseClicked(readout, false);
            client.gui.screen().mouseReleased(readout);
            check(XpOrbTrailsClient.CONFIG.width == 0.1, "Clicking the numeric readout must preserve trail width");
            XpOrbTrailsClient.CONFIG.enabled = false;
            XpOrbTrailsClient.CONFIG.renderRange = 71;
            XpOrbTrailsClient.CONFIG.trailCap = 24;
            XpOrbTrailsClient.CONFIG.width = 0.44;
        });
        context.clickScreenButton("screen.xporbtrails.profiles");
        checkFolderButton(context);
        if (Boolean.getBoolean("xporb.openFolderProbe")) {
            context.clickScreenButton("screen.xporbtrails.open_packs");
        }
        click(context, "screen.xporbtrails.profile.standard");
        context.runOnClient(client -> {
            check(!XpOrbTrailsClient.CONFIG.enabled && XpOrbTrailsClient.CONFIG.renderRange == 71
                    && XpOrbTrailsClient.CONFIG.trailCap == 24,
                    "File profiles must preserve enabled state and performance settings");
            check(XpOrbTrailsClient.CONFIG.width == 0.1, "File profiles must still apply their appearance");
            XpOrbTrailsClient.CONFIG.enabled = true;
        });
        context.clickScreenButton("screen.xporbtrails.open_preview");
        context.waitForScreen(TrailPreviewScreen.class);
        for (int sides : new int[]{2, 3, 8, 32}) {
            context.runOnClient(client -> {
                XpOrbTrailsClient.CONFIG.crossSectionSides = sides;
                try {
                    var start = TrailPreviewScreen.class.getDeclaredField("demoStartNanos");
                    start.setAccessible(true); start.setLong(client.gui.screen(), System.nanoTime() - 2_000_000_000L);
                } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
            });
            context.waitTicks(2);
            context.takeScreenshot("preview-cross-section-" + sides);
        }
        context.runOnClient(client -> XpOrbTrailsClient.CONFIG.crossSectionSides = 2);
        context.clickScreenButton("screen.xporbtrails.replay_pickup");
        context.runOnClient(client -> {
            double elapsed = (System.nanoTime() - (long) field(client.gui.screen(), "demoStartNanos")) / 1_000_000_000.0;
            check(elapsed >= 0 && elapsed < 0.5, "Replay must start at the beginning of orb travel");
            TrailConfig longFade = new TrailConfig();
            longFade.pickupFadeSeconds = 3.0;
            check(TrailPreviewScreen.cycleDuration(longFade) > 7.0, "The demo must finish a three-second fade before looping");
            verifyPreviewAccuracy();
        });
        context.takeScreenshot("preview-replay-start");
        context.waitFor(client -> System.nanoTime() - (long) field(client.gui.screen(), "demoStartNanos") >= 2_000_000_000L);
        context.takeScreenshot("preview-replay-travel");
        context.waitFor(client -> System.nanoTime() - (long) field(client.gui.screen(), "demoStartNanos") >= 4_040_000_000L);
        context.takeScreenshot("preview-replay-pickup");
        context.clickScreenButton("screen.xporbtrails.replay_pickup");
        context.runOnClient(client -> check(System.nanoTime() - (long) field(client.gui.screen(), "demoStartNanos") < 500_000_000L,
                "Replaying again during pickup must return to the start"));
        context.runOnClient(client -> client.gui.screen().onClose());
        context.clickScreenButton("screen.xporbtrails.pickup");
        context.runOnClient(client -> {
            XpOrbTrailsClient.CONFIG.pickupFadeSeconds = 1.1;
            XpOrbTrailsClient.CONFIG.pickupFlash = false;
            XpOrbTrailsClient.CONFIG.additiveGlow = false;
            XpOrbTrailsClient.CONFIG.effectStrength = 0.91;
        });
        context.clickScreenButton("screen.xporbtrails.reset");
        context.runOnClient(client -> {
            check("file:pickup/soft".equals(XpOrbTrailsClient.CONFIG.pickupFlashStyle), "Reset pickup page must restore the flash style");
            check(XpOrbTrailsClient.CONFIG.pickupFadeSeconds == 1.1, "Pickup reset must preserve trail fading");
            check(XpOrbTrailsClient.CONFIG.pickupFlash, "Pickup reset must restore the flash toggle");
            check(!XpOrbTrailsClient.CONFIG.additiveGlow && XpOrbTrailsClient.CONFIG.effectStrength == 0.91,
                    "Pickup reset must preserve overall effects");
        });
        context.waitTicks(3);
        context.takeScreenshot("menu-pickup-small");
        context.clickScreenButton("screen.xporbtrails.trail");
        slideCrossSection(context, 1.0);
        context.runOnClient(client -> check(XpOrbTrailsClient.CONFIG.crossSectionSides == 32,
                "Round selection must use 32 sides"));
        slideCrossSection(context, 0.37);
        context.runOnClient(client -> check(XpOrbTrailsClient.CONFIG.crossSectionSides == 13,
                "Cross-section slider must round to integer sides"));
        slideCrossSection(context, 0.0);
        context.runOnClient(client -> {
            check(XpOrbTrailsClient.CONFIG.crossSectionSides == 2, "Flat selection must use a billboard");
            var slider = widgets(client.gui.screen()).stream().filter(w -> w instanceof AbstractSliderButton
                    && w.getMessage().getString().equals("2")).findFirst().orElseThrow();
            client.setLastInputType(net.minecraft.client.InputType.KEYBOARD_TAB);
            slider.setFocused(true);
            slider.keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT, 0, 0));
            check(XpOrbTrailsClient.CONFIG.crossSectionSides == 3, "One arrow press must increase cross-section sides by one");
            client.setLastInputType(net.minecraft.client.InputType.MOUSE);
            verifyCrossSectionGeometry();
            TrailEffectsClientTest.verifyMeshes();
        });
        context.clickScreenButton("screen.xporbtrails.reset");
        context.runOnClient(client -> check(XpOrbTrailsClient.CONFIG.crossSectionSides == 2,
                "Trail reset must restore the camera-facing cross-section"));
        context.takeScreenshot("cross-section-slider");
        context.runOnClient(client -> {
            for (int i = 0; i < 12; i++) XpOrbTrailsClient.CONFIG.savedPresets.add(
                    new TrailConfig.SavedPreset(net.minecraft.network.chat.Component.translatable(
                            "screen.xporbtrails.saved_preset_name", i + 1).getString(), i, i + 1));
        });
        click(context, "screen.xporbtrails.edit_shared_colors");
        var beforeFold = configModifiedTime();
        click(context, "screen.xporbtrails.saved_colors");
        check(configModifiedTime().equals(beforeFold), "Expanding a settings section must not write the config");
        context.runOnClient(client -> check(widgets(client.gui.screen()).stream().anyMatch(w ->
                w.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable(
                        "screen.xporbtrails.save_preset").getString()) && !w.active), "Saving must stop at twelve colors"));
        var managesColors = new java.util.concurrent.atomic.AtomicBoolean();
        context.runOnClient(client -> {
            managesColors.set(widgets(client.gui.screen()).stream().anyMatch(w -> w.getMessage().getString().equals(
                    net.minecraft.network.chat.Component.translatable("screen.xporbtrails.manage_color").getString())));
            check(managesColors.get(), "Saved colors must expose rename and overwrite management");
        });
        if (managesColors.get()) {
            click(context, "screen.xporbtrails.manage_color");
            check(configModifiedTime().equals(beforeFold), "Selecting a saved color must not write the config");
            String first = net.minecraft.network.chat.Component.translatable("screen.xporbtrails.saved_preset_name", 1).getString();
            click(context, first);
            context.runOnClient(client -> check(XpOrbTrailsClient.CONFIG.startColor == 0 && XpOrbTrailsClient.CONFIG.endColor == 1,
                    "A saved pair must apply from its own row"));
            context.runOnClient(client -> {
                var name = widgets(client.gui.screen()).stream().filter(w -> w instanceof net.minecraft.client.gui.components.EditBox)
                        .map(w -> (net.minecraft.client.gui.components.EditBox) w).findFirst().orElseThrow();
                check(widgets(client.gui.screen()).stream().noneMatch(w -> w.active && w.getMessage().equals(
                        net.minecraft.network.chat.Component.translatable("screen.xporbtrails.rename_color"))),
                        "An unchanged color name must disable Rename");
                name.setValue(" ");
                check(widgets(client.gui.screen()).stream().anyMatch(w -> !w.active && w.getMessage().getString().equals(
                        net.minecraft.network.chat.Component.translatable("screen.xporbtrails.rename_color").getString())),
                        "Blank saved color names must disable Rename");
                name.setValue("Renamed Color");
            });
            click(context, "screen.xporbtrails.rename_color");
            context.runOnClient(client -> {
                check("Renamed Color".equals(XpOrbTrailsClient.CONFIG.savedPresets.getFirst().name),
                        "Renaming a color must preserve the saved entry");
                XpOrbTrailsClient.CONFIG.startColor = 0x123456;
                XpOrbTrailsClient.CONFIG.endColor = 0xABCDEF;
            });
            click(context, "screen.xporbtrails.overwrite_color");
            context.runOnClient(client -> {
                var saved = XpOrbTrailsClient.CONFIG.savedPresets.getFirst();
                check(saved.startColor == 0x123456 && saved.endColor == 0xABCDEF
                                && "Renamed Color".equals(saved.name) && XpOrbTrailsClient.CONFIG.savedPresets.size() == 12,
                        "Overwriting a full color list must replace only the selected pair and retain its name");
                try {
                    var persisted = new com.google.gson.Gson().fromJson(java.nio.file.Files.readString(
                            net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("xp_orb_trails.json")), TrailConfig.class);
                    check(persisted.savedPresets.getFirst().startColor == 0x123456
                                    && "Renamed Color".equals(persisted.savedPresets.getFirst().name),
                            "Saved color management must persist to disk");
                } catch (java.io.IOException exception) { throw new AssertionError(exception); }
            });
            context.takeScreenshot("saved-color-edit");
            verifySwatches(context, "menu-swatches-saved", new SwatchRow("Renamed Color", 0x123456, 0xABCDEF));
        }
        var beforeDelete = configModifiedTime();
        click(context, "screen.xporbtrails.delete_color");
        check(configModifiedTime().equals(beforeDelete), "Arming deletion must not write the config");
        context.runOnClient(client -> check(XpOrbTrailsClient.CONFIG.savedPresets.size() == 12,
                "Deleting a color must require confirmation"));
        click(context, "screen.xporbtrails.confirm_delete_color");
        context.runOnClient(client -> {
            check(XpOrbTrailsClient.CONFIG.savedPresets.size() == 11, "Confirmed deletion must free a color slot");
            check(net.minecraft.network.chat.Component.translatable("screen.xporbtrails.saved_preset_name", 2)
                            .getString().equals(XpOrbTrailsClient.CONFIG.savedPresets.getFirst().name),
                    "Deletion must preserve the other saved colors");
        });
        click(context, "screen.xporbtrails.save_preset");
        context.runOnClient(client -> {
            check(XpOrbTrailsClient.CONFIG.savedPresets.size() == 12, "A freed color slot must be reusable");
            check(XpOrbTrailsClient.CONFIG.savedPresets.stream().map(p -> p.name).distinct().count() == 12,
                    "Saving after deletion must not duplicate an existing color name");
        });
        context.takeScreenshot("saved-colors-management");
        click(context, "screen.xporbtrails.mode.gradient");
        context.waitForScreen(TrailChoiceScreen.class);
        click(context, "screen.xporbtrails.mode.rainbow");
        context.waitForScreen(TrailConfigScreen.class);
        context.runOnClient(client -> {
            check("rainbow".equals(XpOrbTrailsClient.CONFIG.colorMode), "List selection must apply the selected color mode");
            check(widgets(client.gui.screen()).stream().noneMatch(w -> w.getMessage().getString().contains("#")),
                    "Rainbow mode must hide fixed color controls");
        });
        click(context, net.minecraft.network.chat.Component.translatable("screen.xporbtrails.saved_preset_name", 2).getString());
        context.runOnClient(client -> check("gradient".equals(XpOrbTrailsClient.CONFIG.colorMode)
                        && XpOrbTrailsClient.CONFIG.startColor == 1 && XpOrbTrailsClient.CONFIG.endColor == 2,
                "Applying a saved pair in rainbow mode must display its gradient"));
        click(context, "screen.xporbtrails.mode.gradient");
        click(context, "screen.xporbtrails.mode.solid");
        context.runOnClient(client -> check(widgets(client.gui.screen()).stream()
                .filter(w -> w.getMessage().getString().contains("#")).count() == 1,
                "Solid mode must show only one color control"));
        context.clickScreenButton("screen.xporbtrails.trail");
        int collapsedCount = context.computeOnClient(client -> (int) widgets(client.gui.screen()).stream()
                .filter(AbstractSliderButton.class::isInstance).count());
        click(context, "screen.xporbtrails.animation");
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            var path = screen.getCurrentFocusPath();
            check(path != null && screen.children().contains(screen.getFocused())
                            && path.leafComponent() instanceof AbstractWidget widget && widget.getMessage().getString().equals(
                                    net.minecraft.network.chat.Component.translatable("screen.xporbtrails.animation").getString()),
                    "Expanding motion must retain keyboard focus on the header");
            check(widgets(screen).stream().filter(AbstractSliderButton.class::isInstance).count() == collapsedCount + 3,
                    "Motion exposes its three controls in the trail page");
        });
        click(context, "screen.xporbtrails.animation");
        context.runOnClient(client -> {
            XpOrbTrailsClient.CONFIG.pickupFadeSeconds = 1.1;
            XpOrbTrailsClient.CONFIG.pickupFlash = false;
            XpOrbTrailsClient.CONFIG.additiveGlow = false;
        });
        context.clickScreenButton("screen.xporbtrails.reset");
        context.runOnClient(client -> {
            check(XpOrbTrailsClient.CONFIG.pickupFadeSeconds == new TrailConfig().pickupFadeSeconds, "Trail reset restores its fade");
            check(!XpOrbTrailsClient.CONFIG.additiveGlow && !XpOrbTrailsClient.CONFIG.pickupFlash,
                    "Trail reset preserves global and pickup preferences");
            XpOrbTrailsClient.CONFIG.renderRange = 83;
        });
        context.clickScreenButton("screen.xporbtrails.general");
        context.clickScreenButton("screen.xporbtrails.reset");
        context.runOnClient(client -> check(XpOrbTrailsClient.CONFIG.additiveGlow
                && XpOrbTrailsClient.CONFIG.renderRange == new TrailConfig().renderRange
                && !XpOrbTrailsClient.CONFIG.pickupFlash, "General reset covers performance and preserves pickup"));
        context.runOnClient(client -> {
            XpOrbTrailsClient.CONFIG.savedProfiles.add(new TrailConfig.SavedProfile("Regression", new TrailConfig()));
            XpOrbTrailsClient.CONFIG.width = 0.33;
            XpOrbTrailsClient.CONFIG.effectStrength = 0.91;
        });
        context.clickScreenButton("screen.xporbtrails.profiles");
        click(context, "screen.xporbtrails.manage_profile");
        context.runOnClient(client -> check(XpOrbTrailsClient.CONFIG.width == 0.33,
                "Managing a profile must not overwrite unsaved settings"));
        click(context, "screen.xporbtrails.overwrite_profile");
        context.runOnClient(client -> {
            check(XpOrbTrailsClient.CONFIG.savedProfiles.getFirst().effectStrength == 0.91,
                    "Overwriting a profile must capture the merged strength");
            widgets(client.gui.screen()).stream().filter(w -> w instanceof net.minecraft.client.gui.components.EditBox)
                    .map(w -> (net.minecraft.client.gui.components.EditBox) w).findFirst().orElseThrow().setValue("Renamed");
        });
        click(context, "screen.xporbtrails.rename_profile");
        context.runOnClient(client -> check("Renamed".equals(XpOrbTrailsClient.CONFIG.savedProfiles.getFirst().name),
                "Renaming a saved profile must persist its new name"));
        click(context, "screen.xporbtrails.delete_profile");
        context.runOnClient(client -> check(XpOrbTrailsClient.CONFIG.savedProfiles.size() == 1, "Delete must require confirmation"));
        context.clickScreenButton("screen.xporbtrails.trail");
        context.clickScreenButton("screen.xporbtrails.profiles");
        context.runOnClient(client -> check(widgets(client.gui.screen()).stream().noneMatch(w -> w.getMessage().equals(
                net.minecraft.network.chat.Component.translatable("screen.xporbtrails.confirm_delete_profile"))),
                "Leaving Profiles must disarm its delete button"));
        click(context, "screen.xporbtrails.manage_profile");
        click(context, "screen.xporbtrails.delete_profile");
        click(context, "screen.xporbtrails.confirm_delete_profile");
        context.runOnClient(client -> check(XpOrbTrailsClient.CONFIG.savedProfiles.isEmpty(), "Confirmed deletion must remove the profile"));
        context.runOnClient(client -> XpOrbTrailsClient.CONFIG.savedProfiles.add(new TrailConfig.SavedProfile(
                net.minecraft.network.chat.Component.translatable("screen.xporbtrails.saved_profile_name", 2).getString(), new TrailConfig())));
        context.clickScreenButton("screen.xporbtrails.trail");
        context.clickScreenButton("screen.xporbtrails.profiles");
        click(context, "screen.xporbtrails.save_profile");
        context.runOnClient(client -> check(XpOrbTrailsClient.CONFIG.savedProfiles.stream().map(p -> p.name).distinct().count() == 2,
                "Saving a profile must choose an unused name"));
        context.clickScreenButton("screen.xporbtrails.trail");
        context.runOnClient(client -> {
            var list = (TrailSettingsList) client.gui.screen().children().stream().filter(w -> w instanceof TrailSettingsList).findFirst().orElseThrow();
            list.setScrollAmount(0);
            client.setLastInputType(net.minecraft.client.InputType.KEYBOARD_TAB);
            for (int i = 0; i < 30 && list.scrollAmount() == 0; i++) {
                client.gui.screen().keyPressed(new net.minecraft.client.input.KeyEvent(
                        com.mojang.blaze3d.platform.InputConstants.KEY_TAB, '\t', 0));
            }
            check(list.scrollAmount() > 0, "Keyboard navigation must reveal controls below the viewport");
            client.options.guiScale().set(1);
            client.resizeGui();
            client.gui.screen().setFocused(null);
            client.setLastInputType(net.minecraft.client.InputType.MOUSE);
        });
        context.waitTicks(3);
        context.takeScreenshot("menu-trail-wide");
        context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });
        var languageReload = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>>();
        context.runOnClient(client -> {
            client.getLanguageManager().setSelected("zh_cn");
            client.options.languageCode = "zh_cn";
            languageReload.set(client.reloadResourcePacks());
        });
        context.waitFor(client -> languageReload.get().isDone() && client.gui.overlay() == null);
        var originalConfig = new java.util.concurrent.atomic.AtomicReference<TrailConfig>();
        context.runOnClient(client -> {
            originalConfig.set(XpOrbTrailsClient.CONFIG);
            XpOrbTrailsClient.CONFIG = new TrailConfig();
            client.gui.setScreen(ModMenu.getConfigScreen("xporbtrails", client.gui.screen()));
        });
        context.waitTicks(5);
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            int right = 0;
            for (String page : List.of("general", "trail", "pickup", "orb_body", "profiles")) {
                var tab = findControl(screen, "screen.xporbtrails." + page);
                check(tab.getX() >= right && tab.getRight() <= screen.width &&
                                tab.getWidth() >= client.font.width(tab.getMessage()),
                        "Chinese tabs must fit without overlap in a small window: " + page);
                right = tab.getRight();
            }
        });
        context.getInput().setCursorPos(2, 2);
        context.waitTicks(2);
        context.takeScreenshot("menu-default-general-chinese-small");
        context.runOnClient(client -> client.getWindow().setWindowed(1500, 850));
        context.waitFor(client -> client.gui.screen().width >= 680);
        context.getInput().setCursorPos(2, 2);
        context.waitTicks(56);
        context.runOnClient(client -> findControl(client.gui.screen(), "screen.xporbtrails.enabled"));
        context.takeScreenshot("menu-default-general-chinese-wide");
        verifySwatches(context, "menu-swatches-zh",
                new SwatchRow("screen.xporbtrails.start_color", 0xE6FF2A),
                new SwatchRow("screen.xporbtrails.end_color", 0x66FF00));
        for (String page : List.of("trail", "pickup", "orb_body", "profiles")) {
            context.clickScreenButton("screen.xporbtrails." + page);
            context.getInput().setCursorPos(2, 2);
            context.waitTicks(3);
            if (page.equals("profiles")) checkFolderButton(context);
            context.takeScreenshot("menu-zh-wide-" + page);
        }
        context.clickScreenButton("screen.xporbtrails.trail");
        click(context, "screen.xporbtrails.custom_shape");
        context.runOnClient(client -> {
            var child = findControl(client.gui.screen(), "screen.xporbtrails.head_width");
            var list = (TrailSettingsList) client.gui.screen().children().stream().filter(TrailSettingsList.class::isInstance).findFirst().orElseThrow();
            check(child.getX() > list.getX(), "Custom shape controls must be inset under their parent");
        });
        click(context, "screen.xporbtrails.custom_shape");
        slideCrossSection(context, 0.2);
        context.takeScreenshot("menu-shape-chinese-wide");
        context.clickScreenButton("screen.xporbtrails.pickup");
        click(context, "screen.xporbtrails.pickup_flash");
        context.runOnClient(client -> {
            check(!XpOrbTrailsClient.CONFIG.pickupFlash, "Pickup flash must switch off");
            check(widgets(client.gui.screen()).stream().noneMatch(w -> w.getMessage().getString().equals(
                    net.minecraft.network.chat.Component.translatable("screen.xporbtrails.flash_style.soft").getString())),
                    "Disabled pickup flash details must be hidden");
        });
        click(context, "screen.xporbtrails.pickup_flash");
        context.runOnClient(client -> check(XpOrbTrailsClient.CONFIG.pickupFlash, "Pickup flash must switch back on"));
        click(context, "screen.xporbtrails.preview_pickup");
        context.waitForScreen(TrailPreviewScreen.class);
        context.clickScreenButton("screen.xporbtrails.preview_pickup");
        context.runOnClient(client -> {
            double elapsed = (System.nanoTime() - (long) field(client.gui.screen(), "demoStartNanos")) / 1_000_000_000.0;
            check(elapsed >= 3.65 && elapsed < 4.25, "Pickup replay must begin just before collection");
            client.gui.screen().onClose();
        });
        context.clickScreenButton("screen.xporbtrails.trail");
        click(context, "screen.xporbtrails.flight_pulse");
        context.runOnClient(client -> check(XpOrbTrailsClient.CONFIG.flightPulse, "Appearance must enable the flight pulse"));
        click(context, "screen.xporbtrails.flight_pulse");
        context.runOnClient(client -> check(!XpOrbTrailsClient.CONFIG.flightPulse, "Appearance must disable the flight pulse"));
        context.clickScreenButton("screen.xporbtrails.orb_body");
        click(context, "screen.xporbtrails.flight_effect");
        click(context, "effect.xporbtrails.twin-orbit");
        context.clickScreenButton("screen.xporbtrails.pickup");
        click(context, "screen.xporbtrails.flash_style");
        click(context, "effect.xporbtrails.twin-orbit");
        context.runOnClient(client -> check("file:orb/twin-orbit".equals(XpOrbTrailsClient.CONFIG.flightEffect)
                        && "file:pickup/twin-orbit".equals(XpOrbTrailsClient.CONFIG.pickupFlashStyle),
                "Both choosers must select the effect by its file name and store its stable id"));
        for (String style : List.of("soft", "star", "ring", "reflow", "shards", "spiral")) {
            click(context, "screen.xporbtrails.flash_style");
            click(context, "screen.xporbtrails.flash_style." + style);
            context.runOnClient(client -> check(("file:pickup/" + style).equals(XpOrbTrailsClient.CONFIG.pickupFlashStyle),
                    "Pickup style selection must apply " + style));
        }
        context.clickScreenButton("screen.xporbtrails.general");
        click(context, "screen.xporbtrails.mode.gradient");
        context.takeScreenshot("menu-choice-chinese");
        context.clickScreenButton("gui.back");
        click(context, "screen.xporbtrails.start_color");
        context.waitForScreen(ColorPickerScreen.class);
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            var input = widgets(screen).stream().filter(w -> w instanceof net.minecraft.client.gui.components.EditBox)
                    .map(w -> (net.minecraft.client.gui.components.EditBox) w).findFirst().orElseThrow();
            var done = widgets(screen).stream().filter(w -> w.getMessage().equals(
                    net.minecraft.network.chat.Component.translatable("gui.done"))).findFirst().orElseThrow();
            input.setValue("#12X");
            check(!done.active, "Incomplete or invalid hex must disable Done");
            int originalColor = XpOrbTrailsClient.CONFIG.startColor;
            var secondary = new net.minecraft.client.input.MouseButtonEvent(screen.width / 2, (int) field(screen, "paletteY") + 3,
                    new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT, 0));
            screen.mouseClicked(secondary, false);
            screen.mouseReleased(secondary);
            check(XpOrbTrailsClient.CONFIG.startColor == originalColor, "Secondary click must preserve the selected color");
        });
        context.waitTicks(2);
        context.takeScreenshot("menu-invalid-hex-chinese");
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            screen.resize(screen.width, screen.height);
            var input = widgets(screen).stream().filter(w -> w instanceof net.minecraft.client.gui.components.EditBox)
                    .map(w -> (net.minecraft.client.gui.components.EditBox) w).findFirst().orElseThrow();
            check("#12X".equals(input.getValue()), "Resizing must preserve the unfinished color input");
            input.setValue("#123ABC");
        });
        context.takeScreenshot("menu-picker-chinese");
        verifySwatches(context, "menu-swatch-picker", new SwatchRow("screen.xporbtrails.hex", 0x123ABC));
        context.clickScreenButton("gui.done");
        context.runOnClient(client -> check(XpOrbTrailsClient.CONFIG.startColor == 0x123ABC,
                "The hex input and Done button must apply the edited trail color"));
        context.clickScreenButton("screen.xporbtrails.general");
        context.waitTicks(3);
        context.takeScreenshot("menu-general-chinese");
        verifySwatches(context, "menu-swatches-edited",
                new SwatchRow("screen.xporbtrails.start_color", 0x123ABC),
                new SwatchRow("screen.xporbtrails.end_color", 0x66FF00));
        context.runOnClient(client -> client.getWindow().setWindowed(854, 480));
        context.waitFor(client -> client.gui.screen().width < 680);
        context.waitTicks(3);
        context.takeScreenshot("menu-general-chinese-small");
        context.runOnClient(client -> client.getWindow().setWindowed(1500, 850));
        context.waitFor(client -> client.gui.screen().width >= 680);
        context.clickScreenButton("screen.xporbtrails.profiles");
        click(context, "screen.xporbtrails.profile.standard");
        context.runOnClient(client -> check(XpOrbTrailsClient.CONFIG.startColor == new TrailConfig().startColor,
                "Clicking the Standard file profile must apply it"));
        context.runOnClient(client -> verifyCometStripName());
        context.takeScreenshot("menu-profiles-chinese");
        context.runOnClient(client -> XpOrbTrailsClient.CONFIG.renderRange = 71);
        context.runOnClient(client -> check(XpOrbTrailsClient.fileStyles().stream().anyMatch(style -> "Comet Pennant".equals(style.name())),
                "A fresh installation must load the bundled Comet Pennant file style"));
        click(context, "screen.xporbtrails.reload_styles");
        click(context, "screen.xporbtrails.profile.comet-pennant");
        context.runOnClient(client -> {
            var config = XpOrbTrailsClient.CONFIG;
            check(!config.showVanillaOrb && "ribbon".equals(config.trailSpriteMode)
                            && "sprite:orb/luna-moth".equals(config.flightEffect)
                            && "sprite:pickup/collect-burst".equals(config.pickupFlashStyle)
                            && "sprite:trail/comet-pennant".equals(config.trailSprite),
                    "Comet Pennant must select the moth, pickup burst and pennant ribbon");
            for (var role : AssetRole.values()) {
                String id = switch (role) {
                    case ORB -> config.flightEffect;
                    case PICKUP -> config.pickupFlashStyle;
                    case TRAIL -> config.trailSprite;
                };
                check(XpOrbTrailsClient.sprite(role, id) != null, "Missing bundled artwork: " + id);
            }
            check(config.renderRange == 71, "File styles must preserve the user's render range");
        });
        context.takeScreenshot("menu-file-style-comet-pennant");
        verifyEditableFiles(context);
        context.runOnClient(client -> {
            client.getWindow().setWindowed(854, 480);
            XpOrbTrailsClient.CONFIG = originalConfig.get();
        });
        context.waitFor(client -> client.gui.screen().width < 680);
        context.runOnClient(client -> {
            client.gui.screen().onClose();
            check("尾迹".equals(net.minecraft.network.chat.Component.translatable("screen.xporbtrails.trail").getString()),
                    "Menu must follow the Minecraft language");
            client.getLanguageManager().setSelected("en_us");
            client.options.languageCode = "en_us";
            languageReload.set(client.reloadResourcePacks());
        });
        context.waitFor(client -> languageReload.get().isDone() && client.gui.overlay() == null);
        context.waitTicks(3);
        context.runOnClient(client -> {
            var screen = new ColorPickerScreen(client.gui.screen(), 0x00FF00, color -> { });
            client.gui.setScreen(screen);
            screen.resize(427, 240);
            for (var child : screen.children()) {
                if (child instanceof AbstractWidget widget) {
                    check(widget.getY() >= 0 && widget.getBottom() <= 240,
                            "Color picker controls must fit a 240-pixel GUI height");
                }
            }
        });
        context.takeScreenshot("color-picker-small");
        context.runOnClient(client -> client.gui.screen().onClose());
        context.runOnClient(client -> client.gui.screen().onClose());

        TestSingleplayerContext world = context.worldBuilder().create();
        try {
            world.getConnection().waitForChunksRender();
            context.runOnClient(client -> {
                trails().clear();
                XpOrbTrailsClient.CONFIG = new TrailConfig();
                var orb = new ExperienceOrb(client.level, client.player.getX() + 0.5,
                        client.player.getY(), client.player.getZ(), 1);
                orb.setId(100200); client.level.addEntity(orb);
                for (int i = 0; i < 3; i++) { orb.setPos(orb.getX() + 0.2, orb.getY(), orb.getZ()); TrailRenderer.track(orb); }
                client.level.removeEntity(orb.getId(), net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            });
            context.waitTicks(1);
            context.runOnClient(client -> check(snapshot().stream().noneMatch(t -> (double) field(t, "flashProgress") >= 0),
                    "An orb removed near the player without a pickup packet must not flash"));
            context.runOnClient(client -> {
                trails().clear();
                var orb = new ExperienceOrb(client.level, client.player.getX() + 1,
                        client.player.getY() + 1, client.player.getZ(), 1);
                orb.setId(100201); client.level.addEntity(orb);
                for (int i = 0; i < 3; i++) { orb.setPos(orb.getX() + 0.2, orb.getY(), orb.getZ()); TrailRenderer.track(orb); }
                client.getConnection().handleTakeItemEntity(new net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket(
                        orb.getId(), client.player.getId(), 1));
            });
            context.waitTicks(1);
            context.runOnClient(client -> {
                check(snapshot().stream().anyMatch(t -> (double) field(t, "flashProgress") >= 0),
                        "A real pickup packet must flash even while the orb remains loaded");
                client.level.removeEntity(100201, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                trails().clear();
            });
            context.runOnClient(client -> {
                var orb = new ExperienceOrb(client.level, client.player.getX() + 10,
                        client.player.getY() + 1, client.player.getZ(), 1);
                orb.setId(100202); orb.setNoGravity(true); orb.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                client.level.addEntity(orb); TrailRenderer.track(orb);
                client.getConnection().handleTakeItemEntity(new net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket(
                        orb.getId(), client.player.getId(), 1));
            });
            context.waitTicks(1);
            context.runOnClient(client -> {
                check(snapshot().stream().anyMatch(t -> (double) field(t, "flashProgress") >= 0),
                        "A stationary orb must flash without needing a multi-point trail");
                client.level.removeEntity(100202, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                trails().clear();
            });
            var pickedTrail = new java.util.concurrent.atomic.AtomicReference<Object>();
            context.runOnClient(client -> XpOrbTrailsClient.CONFIG.showVanillaOrb = false);
            int xpBeforePickup = context.computeOnClient(client -> client.player.totalExperience);
            world.getServer().runCommand("execute at @p run summon minecraft:experience_orb ~10 ~1 ~ {Value:1,Count:3,NoGravity:1b,Motion:[0.0d,0.0d,0.0d],Tags:[\"xporb_pickup_test\"]}");
            context.waitFor(client -> !trails().isEmpty());
            context.runOnClient(client -> pickedTrail.set(trails().values().iterator().next()));
            world.getServer().runCommand("execute at @p run tp @e[type=minecraft:experience_orb,tag=xporb_pickup_test] ~ ~ ~");
            context.waitFor(client -> (long) field(pickedTrail.get(), "pickupAt") != 0);
            context.waitFor(client -> client.player.totalExperience > xpBeforePickup);
            context.runOnClient(client -> check(field(pickedTrail.get(), "pickupPosition") != null,
                    "Vanilla server pickup packets must record a flash position"));
            context.takeScreenshot("pickup-flash-real");
            world.getServer().runCommand("kill @e[type=minecraft:experience_orb,tag=xporb_pickup_test]");
            context.runOnClient(client -> {
                XpOrbTrailsClient.CONFIG.showVanillaOrb = true;
                trails().clear();
            });
            context.runOnClient(client -> {
                trails().clear();
                XpOrbTrailsClient.CONFIG.trailCap = 8;
                var camera = client.gameRenderer.mainCamera().position();
                var orbs = new ArrayList<ExperienceOrb>();
                for (int i = 0; i < 9; i++) {
                    var orb = new ExperienceOrb(client.level, camera.x, camera.y, camera.z + 3, 1);
                    orb.setId(100000 + i);
                    orbs.add(orb);
                }
                for (int tick = 0; tick < 5; tick++) {
                    for (var orb : orbs) {
                        orb.setPos(orb.getX() + 0.1, orb.getY(), orb.getZ());
                        TrailRenderer.track(orb);
                    }
                }
                long continuous = trails().values().stream()
                        .filter(trail -> ((List<?>) field(trail, "points")).size() >= 2).count();
                check(continuous == 8, "Over-cap orbs must retain eight continuous trails; got " + continuous);
                trails().clear();
                var farOrb = new ExperienceOrb(client.level, camera.x + 100, camera.y, camera.z, 1);
                farOrb.setId(100100);
                TrailRenderer.track(farOrb);
                check(trails().isEmpty(), "Out-of-range orbs must not consume trail slots");
                trails().clear();
                XpOrbTrailsClient.CONFIG = new TrailConfig();
            });
            for (boolean glow : new boolean[]{true, false}) {
                context.runOnClient(client -> XpOrbTrailsClient.CONFIG.additiveGlow = glow);
                world.getServer().runCommand("execute at @p run summon minecraft:experience_orb ~ ~2 ~3 {Value:1,Motion:[0.1d,0.1d,0.0d]}");
                context.waitFor(client -> !snapshot().isEmpty());
                context.waitTicks(3);
                context.takeScreenshot(glow ? "trail-glow" : "trail-alpha");
            }
            for (int sides : new int[]{2, 8, 32}) {
                context.runOnClient(client -> { XpOrbTrailsClient.CONFIG.crossSectionSides = sides; trails().clear(); });
                world.getServer().runCommand("execute at @p run summon minecraft:experience_orb ~ ~2 ~3 {Value:1,Motion:[0.1d,0.1d,0.0d]}");
                context.waitFor(client -> !snapshot().isEmpty());
                context.waitTicks(5);
                context.takeScreenshot("cross-section-" + sides);
            }
            context.runOnClient(client -> XpOrbTrailsClient.CONFIG.enabled = false);
            context.waitTicks(2);
            context.runOnClient(client -> {
                check(snapshot().isEmpty() && trails().isEmpty(), "Disabling must clear visible and tracked trails immediately");
                XpOrbTrailsClient.CONFIG.enabled = true;
            });
            world.getServer().runCommand("execute at @p run summon minecraft:experience_orb ~ ~2 ~3 {Value:1,Motion:[0.1d,0.1d,0.0d]}");
            context.waitFor(client -> !snapshot().isEmpty());
            TrailEffectsClientTest.verifyWorld(context, world);
            StarfallClientTest.run(context, world);
        } finally {
            closeWorld(context, world);
        }
        context.waitTicks(2);
        context.runOnClient(client -> {
            check(trails().isEmpty() && snapshot().isEmpty() && field(null, "lastLevel") == null,
                    "Disconnect must release the world and all trail state");
            XpOrbTrailsClient.CONFIG = new TrailConfig();
            XpOrbTrailsClient.CONFIG.width = 0.37;
            XpOrbTrailsClient.CONFIG.showVanillaOrb = false;
            XpOrbTrailsClient.CONFIG.orbScale = 1.6;
            XpOrbTrailsClient.CONFIG.trailSprite = "body";
            XpOrbTrailsClient.CONFIG.fixedStamps = true;
            XpOrbTrailsClient.CONFIG.ribbonMapping = "tile";
            XpOrbTrailsClient.CONFIG.ribbonRepeatLength = 0.65;
            XpOrbTrailsClient.CONFIG.trailSpriteMode = "ribbon";
            XpOrbTrailsClient.CONFIG.stampScale = 1.2;
            XpOrbTrailsClient.CONFIG.stampSpacing = 2.5;
            XpOrbTrailsClient.CONFIG.flightPulse = true;
            XpOrbTrailsClient.CONFIG.flightEffect = "file:orb/twin-orbit";
            XpOrbTrailsClient.CONFIG.pulseSpeed = 1.5;
            XpOrbTrailsClient.CONFIG.shardCount = 7;
            XpOrbTrailsClient.CONFIG.spiralTurns = 1.5;
            XpOrbTrailsClient.CONFIG.pickupFlashStyle = "file:pickup/shards";
            XpOrbTrailsClient.CONFIG.savedPresets.add(new TrailConfig.SavedPreset("Probe Color", 0x123456, 0xabcdef));
            XpOrbTrailsClient.CONFIG.savedProfiles.add(new TrailConfig.SavedProfile("Probe Profile", XpOrbTrailsClient.CONFIG));
            XpOrbTrailsClient.saveConfig();
        });
        if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
    }

    static void closeWorld(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> {
            var server = client.getSingleplayerServer();
            // The 26.3 client queues a synchronous player-removal task before Fabric's disconnect tick hook.
            // Let the server drain that task before it returns to the gametest tick barrier.
            server.execute(() -> server.managedBlock(() -> !server.isRunning()));
        });
        world.close();
    }

    private void check(boolean success, String message) {
        if (!success) failures.add(message);
    }

    private static void verifyCometStripName() {
        for (var role : AssetRole.values()) {
            String id = "sprite:" + role.folder + "/comet-strip";
            String label = TrailConfigScreen.effectLabel(role, id).getString();
            String expected = net.minecraft.network.chat.Component.translatable("sprite.xporbtrails.comet-strip").getString();
            if (!label.equals(expected)) throw new AssertionError(id + " displayed as " + label);
        }
    }

    private void verifyEditableFiles(ClientGameTestContext context) {
        var root = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("xp_orb_trails");
        var preset = root.resolve("profiles/standard.json");
        try {
            for (String folder : List.of("orb/effects", "orb/sprites", "pickup/effects", "pickup/sprites", "trail/sprites")) {
                check(java.nio.file.Files.isDirectory(root.resolve(folder)), "Missing role folder " + folder);
            }
            for (String retired : List.of("styles", "sprites", "effects", "profiles/builtin")) {
                check(!java.nio.file.Files.exists(root.resolve(retired)), "Fresh installation must not create " + retired);
            }
            byte[] original = java.nio.file.Files.readAllBytes(preset);
            try {
                var edited = com.google.gson.JsonParser.parseString(new String(original, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                edited.addProperty("name", "Edited in JSON");
                edited.addProperty("width", 0.41);
                java.nio.file.Files.writeString(preset, edited.toString());
                click(context, "screen.xporbtrails.reload_styles");
                click(context, "Edited in JSON");
                context.runOnClient(client -> check(XpOrbTrailsClient.CONFIG.width == 0.41,
                        "Editing the bundled profile on disk must update its name and applied width"));
            } finally {
                java.nio.file.Files.write(preset, original);
                click(context, "screen.xporbtrails.reload_styles");
            }
            var flash = root.resolve("pickup/effects/soft.json");
            byte[] originalFlash = java.nio.file.Files.readAllBytes(flash);
            try {
                var edited = com.google.gson.JsonParser.parseString(new String(originalFlash, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                edited.addProperty("name", "Edited pickup in JSON");
                java.nio.file.Files.writeString(flash, edited.toString());
                click(context, "screen.xporbtrails.reload_styles");
                context.clickScreenButton("screen.xporbtrails.pickup");
                click(context, "screen.xporbtrails.flash_style");
                click(context, "Edited pickup in JSON");
                context.runOnClient(client -> check("file:pickup/soft".equals(XpOrbTrailsClient.CONFIG.pickupFlashStyle),
                        "Renaming a pickup file's display name must preserve its ID"));

                java.nio.file.Files.delete(flash);
                context.clickScreenButton("screen.xporbtrails.profiles");
                click(context, "screen.xporbtrails.reload_styles");
                context.clickScreenButton("screen.xporbtrails.pickup");
                click(context, "screen.xporbtrails.flash_style");
                context.runOnClient(client -> {
                    var labels = widgets(client.gui.screen()).stream().map(w -> w.getMessage().getString()).toList();
                    check(!labels.contains("Edited pickup in JSON") && !labels.contains(net.minecraft.network.chat.Component
                                    .translatable("screen.xporbtrails.flash_style.soft").getString()),
                            "Deleting the default pickup file must remove its chooser entry");
                });
                context.clickScreenButton("gui.back");
            } finally {
                java.nio.file.Files.write(flash, originalFlash);
                context.clickScreenButton("screen.xporbtrails.profiles");
                click(context, "screen.xporbtrails.reload_styles");
            }
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
    }

    private static java.nio.file.attribute.FileTime configModifiedTime() {
        try {
            return java.nio.file.Files.getLastModifiedTime(net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getConfigDir().resolve("xp_orb_trails.json"));
        } catch (java.io.IOException exception) { throw new AssertionError(exception); }
    }

    private void verifyPreviewAccuracy() {
        try {
            var progress = TrailPreviewScreen.class.getDeclaredMethod("pickupProgress", double.class, double.class);
            progress.setAccessible(true);
            for (double duration : new double[]{0.08, 0.22, 1.0}) {
                double halfway = (double) progress.invoke(null, 4.0 + duration / 2, duration);
                double finished = (double) progress.invoke(null, 4.0 + duration, duration);
                check(Math.abs(halfway - 0.5) < 1e-9 && Math.abs(finished - 1) < 1e-9,
                        "Preview flash duration must match the configured in-game seconds");
            }
        } catch (ReflectiveOperationException exception) {
            check(false, "Preview flash timing must expose the same duration used in-game");
        }
        try {
            var vertices = TrailPreviewScreen.class.getDeclaredMethod("crossSectionVertices", int.class, double.class);
            vertices.setAccessible(true);
            for (int sides : new int[]{2, 3, 8, 32}) {
                double[] polygon = (double[]) vertices.invoke(null, sides, 12.0);
                check(polygon.length == (sides == 2 ? 8 : sides * 2), "Preview must draw the selected cross-section geometry");
                double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
                for (int i = 1; i < polygon.length; i += 2) {
                    minY = Math.min(minY, polygon[i]); maxY = Math.max(maxY, polygon[i]);
                }
                check(sides == 2 ? maxY - minY <= 3 : maxY - minY > 12,
                        "Preview must distinguish a flat ribbon from a polygonal tube");
            }
        } catch (ReflectiveOperationException exception) {
            check(false, "Preview must render a cross-section diagram instead of changing only its label");
        }
    }

    private void verifyCrossSectionGeometry() {
        var tangent = new net.minecraft.world.phys.Vec3(1, 0, 0);
        for (var view : List.of(new net.minecraft.world.phys.Vec3(0, 0, 1),
                new net.minecraft.world.phys.Vec3(0, 1, 0), tangent)) {
            var right = TrailRenderer.billboardRight(tangent, view, null);
            check(Math.abs(right.lengthSqr() - 1) < 1e-9 && Math.abs(right.dot(tangent)) < 1e-9,
                    "Billboard must retain finite unit width even when viewed end-on");
            check(Math.abs(right.dot(view)) < 1e-9, "Billboard width must face the current camera");
        }
        try {
            Class<?> sampleType = Class.forName(TrailRenderer.class.getName() + "$Sample");
            var constructor = sampleType.getDeclaredConstructor(net.minecraft.world.phys.Vec3.class, long.class);
            constructor.setAccessible(true);
            var samples = new ArrayList<>();
            for (int i = 0; i < 3; i++) samples.add(constructor.newInstance(new net.minecraft.world.phys.Vec3(i, 0, 0), 0L));
            var append = java.util.Arrays.stream(TrailRenderer.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals("appendStyledTube")).findFirst().orElseThrow();
            append.setAccessible(true);
            for (int sides : new int[]{2, 3, 8, 32}) {
                int[] vertices = {0};
                long[] fingerprint = {1};
                var out = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{com.mojang.blaze3d.vertex.VertexConsumer.class}, (proxy, method, args) -> {
                            if (method.getName().equals("addVertex") && args.length == 3) {
                                vertices[0]++;
                                for (Object coordinate : args) {
                                    float value = ((Number) coordinate).floatValue();
                                    check(Float.isFinite(value), "Cross-section vertices must be finite");
                                    fingerprint[0] = fingerprint[0] * 31 + Float.floatToIntBits(value);
                                }
                            }
                            if (method.getName().equals("setColor") && args.length == 1)
                                fingerprint[0] = fingerprint[0] * 31 + ((Number) args[0]).intValue();
                            return proxy;
                        });
                var config = new TrailConfig();
                config.width = 0.24;
                config.startColor = config.endColor = 0xFFFFFF;
                config.colorMode = "solid";
                config.tailWidthScale = config.middleWidthScale = config.headWidthScale = 1.0;
                config.crossSectionSides = sides;
                append.invoke(null, out, samples, new net.minecraft.world.phys.Vec3(0, 0, 3),
                        0L, 100L, config, 0.78, -1.0, false);
                check(vertices[0] == 8 * (sides == 2 ? 1 : sides),
                        "Mesh must emit the selected face count without doubling the billboard");
                long expected = switch (sides) {
                    case 2 -> 6052789791297900145L;
                    case 3 -> 61044112680327065L;
                    case 8 -> 4513098808163761609L;
                    case 32 -> -2688908051212465271L;
                    default -> throw new AssertionError("No original mesh fingerprint recorded for " + sides + " sides");
                };
                // Recorded from the original renderer: hash the ordered float coordinates and ARGB colors.
                check(fingerprint[0] == expected, "Optimized vertices and colors must match the original mesh for " + sides + " sides");
            }
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }

    private void checkFolderButton(ClientGameTestContext context) {
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            var configRoot = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("xp_orb_trails");
            check(XpOrbTrailsClient.assetDirectory().equals(configRoot) && java.nio.file.Files.isDirectory(configRoot.resolve("packs")),
                    "The packs folder must exist inside this instance's config directory");
            String key = "screen.xporbtrails.open_packs";
            var folder = findControl(screen, key);
            var preview = findControl(screen, "screen.xporbtrails.open_preview");
            check(folder.visible && folder.active && !folder.getMessage().getString().equals(key)
                            && folder.getWidth() >= client.font.width(folder.getMessage()) + 10
                            && folder.getX() >= 0 && folder.getRight() <= preview.getX()
                            && folder.getY() == preview.getY() && folder.getBottom() <= screen.height,
                    "The translated packs folder button must fit to the left of Preview");
        });
    }

    private static Map<?, ?> trails() { return (Map<?, ?>) field(null, "TRAILS"); }
    private static List<?> snapshot() { return (List<?>) field(null, "renderTrails"); }

    private static List<AbstractWidget> widgets(Screen screen) {
        List<AbstractWidget> result = new ArrayList<>();
        collect(screen, result);
        return result;
    }

    private record SwatchRow(String key, int... colors) {}

    private void verifySwatches(ClientGameTestContext context, String name, SwatchRow... rows) {
        reveal(context, rows[0].key());
        context.getInput().setCursorPos(2, 2);
        context.waitTicks(2);
        var bounds = context.computeOnClient(client -> {
            var screen = client.gui.screen();
            int scale = client.getWindow().getGuiScale();
            var spans = new ArrayList<ScreenRectangle>();
            for (var row : rows) {
                var widget = findControl(screen, row.key());
                int left = widget.getX(), right = widget.getRight();
                if (screen instanceof ColorPickerScreen) {
                    // The picker displays its preview immediately to the right of the HEX editor.
                    left = widget.getRight();
                    right = screen.width / 2 + 80;
                } else if (row.colors().length == 1) {
                    check(widget.getMessage().getString().equals("#%06X".formatted(row.colors()[0])),
                            "The color button must include its readable HEX value");
                }
                spans.add(new ScreenRectangle(left * scale, widget.getY() * scale,
                        (right - left) * scale, widget.getHeight() * scale));
            }
            return spans;
        });
        TrailEffectsClientTest.screenshot(context, name, image -> {
            for (int i = 0; i < rows.length; i++) {
                var span = bounds.get(i);
                int x = span.left(), y = span.top() + span.height() / 2;
                for (int rgb : rows[i].colors()) {
                    int run = 0;
                    while (x < span.right() && run < 4) {
                        run = (image.getPixel(x++, y) & 0xFFFFFF) == rgb ? run + 1 : 0;
                    }
                    check(run == 4, "%s: %s must draw #%06X in order".formatted(name, rows[i].key(), rgb));
                }
            }
            return true;
        });
    }

    private static void reveal(ClientGameTestContext context, String key) {
        context.runOnClient(client -> {
            AbstractWidget target = findControl(client.gui.screen(), key);
            for (var child : client.gui.screen().children()) {
                if (child instanceof TrailSettingsList list) {
                    for (var row : list.children()) {
                        if (row.children().contains(target)) list.setScrollAmount(list.scrollAmount() + row.getY() - list.getY() - 8);
                    }
                }
            }
        });
    }

    static void click(ClientGameTestContext context, String key) {
        reveal(context, key);
        context.waitTicks(2);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            AbstractWidget target = findControl(screen, key);
            var event = new net.minecraft.client.input.MouseButtonEvent(target.getX() + target.getWidth() / 2.0,
                    target.getY() + target.getHeight() / 2.0,
                    new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0));
            if (!screen.mouseClicked(event, false)) throw new AssertionError("Control did not accept click: " + key);
            screen.mouseReleased(event);
            System.out.println("Menu click " + key + " at " + event.x() + "," + event.y()
                    + " -> " + client.gui.screen().getClass().getSimpleName());
        });
        context.waitTicks(2);
    }

    static AbstractWidget findControl(Screen screen, String key) {
        var label = net.minecraft.network.chat.Component.translatable(key);
        for (var child : screen.children()) {
            if (child instanceof TrailSettingsList list) {
                for (var row : list.children()) {
                    if (row.matches(label) && row.children().size() == 1) return (AbstractWidget) row.children().getFirst();
                }
            }
        }
        return widgets(screen).stream().filter(w -> w.getMessage().getString().equals(label.getString()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing control: " + key));
    }

    private static void slideCrossSection(ClientGameTestContext context, double fraction) {
        context.runOnClient(client -> {
            var slider = widgets(client.gui.screen()).stream().filter(w -> w instanceof AbstractSliderButton
                    && w.getMessage().getString().equals(Integer.toString(XpOrbTrailsClient.CONFIG.crossSectionSides))).findFirst().orElseThrow();
            for (var child : client.gui.screen().children()) if (child instanceof TrailSettingsList list) {
                for (var row : list.children()) if (row.children().contains(slider))
                    list.setScrollAmount(list.scrollAmount() + row.getY() - list.getY() - 8);
            }
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            var slider = widgets(client.gui.screen()).stream().filter(w -> w instanceof AbstractSliderButton
                    && w.getMessage().getString().equals(Integer.toString(XpOrbTrailsClient.CONFIG.crossSectionSides))).findFirst().orElseThrow();
            var event = new net.minecraft.client.input.MouseButtonEvent(slider.getX() + 4 + (slider.getWidth() - 47) * fraction,
                    slider.getY() + 10, new net.minecraft.client.input.MouseButtonInfo(
                            com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0));
            if (!client.gui.screen().mouseClicked(event, false)) throw new AssertionError("Slider did not accept click");
            client.gui.screen().mouseReleased(event);
        });
        context.waitTicks(2);
    }

    private static void collect(ContainerEventHandler parent, List<AbstractWidget> result) {
        for (GuiEventListener child : parent.children()) {
            if (child instanceof AbstractWidget widget) result.add(widget);
            if (child instanceof ContainerEventHandler container) collect(container, result);
        }
    }

    static Object field(Object target, String name) {
        try {
            var field = (target == null ? TrailRenderer.class : target.getClass()).getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
