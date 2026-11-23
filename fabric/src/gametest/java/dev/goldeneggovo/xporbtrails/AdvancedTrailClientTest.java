package dev.goldeneggovo.xporbtrails;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Counts pixels from the installed trail fragment alone in both real rendering entry points. */
final class AdvancedTrailClientTest {
    private static final int ORB = 100917;
    private record Study(String trail, String profile) {}

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        var saved = context.computeOnClient(client -> XpOrbTrailsClient.CONFIG);
        try {
            world.getServer().runCommand("gamemode spectator @p");
            world.getServer().runCommand("tp @p 0 240 0 0 0");
            world.getServer().runCommand("time set midnight");
            world.getServer().runCommand("weather clear");
            world.getClientLevel().waitForChunksRender();
            context.runOnClient(client -> {
                client.setScreen(null);
                TrailRenderer.updateWorld(null);
                XpOrbTrailsClient.CONFIG = new TrailConfig();
            });
            context.waitTicks(4);
            int background = capture(context, "background");
            for (var study : List.of(new Study("helix", "observatory"), new Study("lightning", "storm"),
                    new Study("wind", "willow"), new Study("inertia", "mirage"), new Study("leaf", "tideglass"),
                    new Study("blade", "dusk"), new Study("braid", "festival"), new Study("crumble", "frost"))) {
                context.runOnClient(client -> {
                    var profile = EffectCollectionClientTest.profile("workshop-" + study.profile);
                    var cfg = new TrailConfig();
                    profile.settings().applyTo(cfg);
                    var trail = cfg.advanced.getAsJsonObject("trail");
                    if (trail == null) throw new AssertionError("Missing bundled trail " + study.trail);
                    cfg.advanced = new com.google.gson.JsonObject();
                    cfg.advanced.add("trail", trail);
                    cfg.showVanillaOrb = false;
                    cfg.flightEffect = "none";
                    cfg.pickupFlash = false;
                    XpOrbTrailsClient.CONFIG = cfg;
                    var screen = new TrailPreviewScreen(null);
                    client.setScreen(screen);
                    try {
                        var clock = TrailPreviewScreen.class.getDeclaredField("demoStartNanos");
                        clock.setAccessible(true);
                        clock.setLong(screen, System.nanoTime() - 2_200_000_000L);
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError(e);
                    }
                });
                context.waitTicks(2);
                if (capture(context, study.trail + "-preview") < 8)
                    throw new AssertionError("Missing rendered preview pixels for " + study.trail);
                context.runOnClient(client -> {
                    client.setScreen(null);
                    TrailRenderer.updateWorld(null);
                    Vec3 camera = client.gameRenderer.getMainCamera().position();
                    var orb = new ExperienceOrb(client.level, camera.x - 1.6, camera.y, camera.z + 4, 1);
                    orb.setId(ORB);
                    orb.setNoGravity(true);
                    client.level.addEntity(orb);
                });
                for (int tick = 0; tick <= 22; tick++) {
                    int step = tick;
                    context.runOnClient(client -> {
                        var orb = (ExperienceOrb) client.level.getEntity(ORB);
                        Vec3 camera = client.gameRenderer.getMainCamera().position();
                        double y = step < 11 ? -0.35 + 0.06 * step : 0.31 - (step - 11) * 0.10;
                        Vec3 p = camera.add(-1.6 + step * 0.14, y, 4);
                        Vec3 movement = p.subtract(orb.position());
                        orb.setPos(p.x, p.y, p.z);
                        orb.setOldPosAndRot();
                        orb.setDeltaMovement(movement);
                        TrailRenderer.track(orb);
                        orb.setDeltaMovement(Vec3.ZERO);
                    });
                    context.waitTicks(1);
                }
                int drawn = capture(context, study.trail + "-world");
                if (drawn < background + 8)
                    throw new AssertionError("Missing world pixels for " + study.trail + ": " + background + " -> " + drawn);
                context.runOnClient(client -> {
                    client.level.removeEntity(ORB, Entity.RemovalReason.DISCARDED);
                    TrailRenderer.updateWorld(null);
                });
            }
        } finally {
            context.runOnClient(client -> {
                if (client.level != null) client.level.removeEntity(ORB, Entity.RemovalReason.DISCARDED);
                client.setScreen(null);
                TrailRenderer.updateWorld(null);
                XpOrbTrailsClient.CONFIG = saved;
            });
        }
    }

    private static int capture(ClientGameTestContext context, String name) {
        return TrailEffectsClientTest.screenshot(context, "advanced-trail-" + name, AdvancedTrailClientTest::coloredPixels);
    }

    private static int coloredPixels(NativeImage image) {
        int hits = 0;
        for (int y = image.getHeight() * 36 / 100; y < image.getHeight() * 68 / 100; y++) {
            for (int x = image.getWidth() / 8; x < image.getWidth() * 7 / 8; x++) {
                int rgb = image.getPixel(x, y);
                int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
                int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
                if (max > 90 && max - min > 24) hits++;
            }
        }
        return hits;
    }
}
