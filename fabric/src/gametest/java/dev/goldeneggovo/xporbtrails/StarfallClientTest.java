package dev.goldeneggovo.xporbtrails;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

final class StarfallClientTest {
    private static final int ORB_ID = 100510;

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        Path root = FabricLoader.getInstance().getConfigDir().resolve("xp_orb_trails");
        boolean record = Boolean.getBoolean("xporb.recordStarfall");
        String previousLanguage = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        try {
            if (record) language(context, "zh_cn");
            for (String name : List.of("profiles/starfall.json", "orb/effects/starfall.json", "pickup/effects/starfall.json")) {
                if (!Files.isRegularFile(root.resolve(name))) throw new AssertionError("Missing bundled Starfall file " + name);
            }
            context.runOnClient(client -> {
                if (XpOrbTrailsClient.reloadFileStyles() != 0) throw new AssertionError("Bundled files must load cleanly");
                XpOrbTrailsClient.CONFIG = new TrailConfig();
                client.gui.setScreen(XpOrbTrailsClient.createConfigScreen(null));
            });
            context.clickScreenButton("screen.xporbtrails.profiles");
            TrailClientTest.click(context, "screen.xporbtrails.profile.starfall");
            context.runOnClient(client -> {
                TrailConfig c = XpOrbTrailsClient.CONFIG;
                if (!c.flightEffect.equals("file:orb/starfall") || !c.pickupFlashStyle.equals("file:pickup/starfall")
                        || !c.customCrossSectionEnabled || c.crossSectionRadii.length != 10)
                    throw new AssertionError("File profile must select both emitters and the five-point cross-section");
            });
            context.clickScreenButton("screen.xporbtrails.open_preview");
            context.waitForScreen(TrailPreviewScreen.class);
            if (record) {
                for (int frame = 0; frame < 55; frame++) {
                    double elapsed = frame * 0.10;
                    context.runOnClient(client -> seek((TrailPreviewScreen) client.gui.screen(), elapsed));
                    context.waitTicks(1);
                    capture(context, "starfall-frame-%03d".formatted(frame));
                }
            }
            context.runOnClient(client -> {
                var preview = (TrailPreviewScreen) client.gui.screen();
                preview.replayPickup();
            });
            context.waitTicks(12);
            if (capture(context, "starfall-preview-pickup") < 5) throw new AssertionError("Missing pickup particles in preview");
            context.runOnClient(client -> {
                client.gui.setScreen(null);
                TrailRenderer.updateWorld(null);
            });
            verifyWorld(context, world, record);
        } finally {
            context.runOnClient(client -> {
                if (client.level != null) client.level.removeEntity(ORB_ID, Entity.RemovalReason.DISCARDED);
                client.gui.setScreen(null);
                TrailRenderer.updateWorld(null);
                XpOrbTrailsClient.CONFIG = new TrailConfig();
            });
            if (record) language(context, previousLanguage);
        }
    }

    static void language(ClientGameTestContext context, String code) {
        var reload = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected(code);
            client.options.languageCode = code;
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null);
        reload.join();
    }

    private static void verifyWorld(ClientGameTestContext context, TestSingleplayerContext world, boolean record) {
        world.getServer().runCommand("time set midnight");
        world.getServer().runCommand("tp @p 0 240 0 0 0");
        context.waitTicks(8);
        int background = capture(context, "starfall-world-background");
        context.runOnClient(client -> {
            Vec3 camera = client.gameRenderer.mainCamera().position();
            var orb = new ExperienceOrb(client.level, camera.x + 1.5, camera.y, camera.z + 4, 1);
            orb.setId(ORB_ID);
            orb.setNoGravity(true);
            client.level.addEntity(orb);
            TrailRenderer.track(orb);
        });
        for (int tick = 1; tick <= 18; tick++) {
            double step = tick;
            context.runOnClient(client -> {
                var orb = (ExperienceOrb) client.level.getEntity(ORB_ID);
                Vec3 camera = client.gameRenderer.mainCamera().position();
                orb.setPos(camera.x + 1.5 - step * 0.14, camera.y + 0.25 * Math.sin(step * 0.24), camera.z + 4);
                orb.setDeltaMovement(Vec3.ZERO);
                TrailRenderer.track(orb);
            });
            context.waitTicks(1);
            if (record) capture(context, "starfall-flight-%02d".formatted(tick));
        }
        capture(context, "starfall-world-flight");
        var pickupTime = new AtomicLong();
        context.runOnClient(client -> {
            client.getConnection().handleTakeItemEntity(new ClientboundTakeItemEntityPacket(ORB_ID, client.player.getId(), 1));
            pickupTime.set(System.nanoTime());
            client.level.removeEntity(ORB_ID, Entity.RemovalReason.DISCARDED);
        });
        if (record) recordBurst(context, pickupTime.get(), 0, 5);
        context.waitFor(client -> System.nanoTime() - pickupTime.get() >= 600_000_000L);
        int afterWindow = capture(context, "starfall-world-bloom");
        if (afterWindow < background + 12) throw new AssertionError("Particles must outlive the emission window: "
                + background + " -> " + afterWindow);
        if (record) recordBurst(context, pickupTime.get(), 7, 14);
        context.waitFor(client -> System.nanoTime() - pickupTime.get() >= 1_650_000_000L);
        int cleared = capture(context, "starfall-world-expired");
        if (cleared + 8 >= afterWindow) throw new AssertionError("Expired burst particles must disappear");
        verifySection(context);
    }

    private static void recordBurst(ClientGameTestContext context, long pickupTime, int first, int last) {
        for (int frame = first; frame <= last; frame++) {
            long due = pickupTime + frame * 100_000_000L;
            context.waitFor(client -> System.nanoTime() >= due);
            capture(context, "starfall-burst-%02d".formatted(frame));
        }
    }

    private static void verifySection(ClientGameTestContext context) {
        context.runOnClient(client -> {
            TrailRenderer.updateWorld(null);
            var c = XpOrbTrailsClient.CONFIG;
            c.showVanillaOrb = false;
            c.flightEffect = "none";
            c.colorMode = "solid";
            c.startColor = 0x38FFE2;
            c.smoothFlow = 0;
            c.width = 0.46;
            c.crossSectionSides = 10;
            Vec3 camera = client.gameRenderer.mainCamera().position();
            double y = camera.y - c.orbYOffset;
            var orb = new ExperienceOrb(client.level, camera.x, y, camera.z + 4, 1);
            orb.setId(ORB_ID);
            orb.setNoGravity(true);
            client.level.addEntity(orb);
            // Look down the axis of the trail to expose its actual cross-section.
            for (int i = 0; i <= 15; i++) {
                orb.setPos(camera.x, y, camera.z + 4 - i * 0.20);
                orb.setOldPosAndRot();
                TrailRenderer.track(orb);
            }
        });
        context.waitTicks(2);
        int star = TrailEffectsClientTest.screenshot(context, "starfall-section-star", StarfallClientTest::mintPixels);
        context.runOnClient(client -> XpOrbTrailsClient.CONFIG.customCrossSectionEnabled = false);
        context.waitTicks(2);
        int regular = TrailEffectsClientTest.screenshot(context, "starfall-section-regular", StarfallClientTest::mintPixels);
        if (star < 40 || regular < star * 1.25)
            throw new AssertionError("The five recessed valleys must reduce the projected area: " + star + " -> " + regular);
    }

    private static int mintPixels(NativeImage image) {
        int hits = 0;
        for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
            for (int x = image.getWidth() / 3; x < image.getWidth() * 2 / 3; x++) {
                int rgb = image.getPixel(x, y);
                int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
                if (g > 100 && g - r > 52 && b - r > 38) hits++;
            }
        }
        return hits;
    }

    private static void seek(TrailPreviewScreen preview, double elapsed) {
        try {
            var start = TrailPreviewScreen.class.getDeclaredField("demoStartNanos");
            start.setAccessible(true);
            start.setLong(preview, System.nanoTime() - (long) (elapsed * 1.0e9));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int capture(ClientGameTestContext context, String name) {
        return TrailEffectsClientTest.screenshot(context, name, StarfallClientTest::pinkPixels);
    }

    private static int pinkPixels(NativeImage image) {
        int hits = 0;
        for (int y = image.getHeight() / 6; y < image.getHeight() * 4 / 5; y++) {
            for (int x = image.getWidth() / 8; x < image.getWidth() * 19 / 20; x++) {
                int rgb = image.getPixel(x, y);
                int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
                if (r > 110 && r - g > 22 && b - g > 16) hits++;
            }
        }
        return hits;
    }
}
