package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

final class SceneOpticsClientTest {
    private static final int ORB_ID = 100781;

    static void run(ClientGameTestContext context) {
        TestSingleplayerContext world = context.worldBuilder().create();
        try {
            run(context, world);
        } finally {
            TrailClientTest.closeWorld(context, world);
        }
    }

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        TrailConfig previous = context.computeOnClient(client -> XpOrbTrailsClient.CONFIG);
        try {
            if (Boolean.getBoolean("xporb.atlasShaderProbe")) {
                context.runOnClient(client -> {
                    try {
                        Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                        Object iris = api.getMethod("getInstance").invoke(null);
                        boolean active = (boolean) api.getMethod("isShaderPackInUse").invoke(iris);
                        if (!active) throw new AssertionError("An active Iris shader pack is required");
                        System.out.println("XPORB_OPTICS iris_active=true");
                    } catch (ReflectiveOperationException error) {
                        throw new AssertionError("Iris is required for the shader probe", error);
                    }
                });
            }
            world.getServer().runCommand("gamemode spectator @p");
            world.getServer().runCommand("tp @p 0.5 300 0 0 0");
            world.getServer().runCommand("time set day");
            world.getServer().runCommand("weather clear");
            for (int x = -8; x <= 8; x++) {
                String material = (x & 1) == 0 ? "stone_bricks" : "smooth_quartz";
                world.getServer().runCommand("fill " + x + " 293 7 " + x + " 310 7 " + material);
            }
            world.getClientLevel().waitForChunksRender();
            context.waitTicks(12);
            context.runOnClient(client -> {
                TrailRenderer.updateWorld(null);
                XpOrbTrailsClient.CONFIG = new TrailConfig();
                var c = XpOrbTrailsClient.CONFIG;
                c.showVanillaOrb = false;
                c.flightEffect = "none";
                c.trailSprite = "body";
                c.orbYOffset = 0;
                Vec3 camera = client.gameRenderer.getMainCamera().position();
                var orb = new ExperienceOrb(client.level, camera.x, camera.y, camera.z + 5, 1);
                orb.setId(ORB_ID);
                orb.setNoGravity(true);
                orb.setDeltaMovement(Vec3.ZERO);
                orb.setOldPosAndRot();
                client.level.addEntity(orb);
                TrailRenderer.track(orb);
            });
            for (boolean hidden : new boolean[]{false, true}) {
                if (hidden) {
                    world.getServer().runCommand("fill -8 293 2 8 310 2 stone_bricks");
                    world.getClientLevel().waitForChunksRender();
                    context.waitTicks(12);
                }
                for (String mode : new String[]{"light", "heat"}) {
                    String name = "scene-optics-" + mode + (hidden ? "-occluded" : "-visible");
                    context.runOnClient(client -> {
                        var opts = new JsonObject();
                        opts.addProperty("type", mode);
                        opts.addProperty("strength", mode.equals("heat") ? 6.0 : 2.6);
                        opts.addProperty("radius", mode.equals("heat") ? 1.2 : 4.25);
                        opts.addProperty("color", "#ff0000");
                        opts.addProperty("speed", 0.0);
                        XpOrbTrailsClient.CONFIG.advanced.add("optics", opts);
                    });
                    context.waitTicks(6);
                    compare(context, name, mode.equals("heat"), hidden);
                }
            }
        } finally {
            context.runOnClient(client -> {
                if (client.level != null) client.level.removeEntity(ORB_ID, Entity.RemovalReason.DISCARDED);
                TrailRenderer.updateWorld(null);
                XpOrbTrailsClient.CONFIG = previous;
            });
        }
    }

    private record Capture(int width, int height, int[] pixels) {}

    private static void compare(ClientGameTestContext context, String name, boolean heat, boolean hidden) {
        var before = new CompletableFuture<Capture>();
        var after = new CompletableFuture<Capture>();
        context.runOnClient(client -> {
            RenderTarget sceneCopy;
            try {
                var field = SceneOptics.class.getDeclaredField("sceneCopy");
                field.setAccessible(true);
                sceneCopy = (RenderTarget) field.get(null);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
            if (sceneCopy == null) throw new AssertionError("The optics pass did not copy the scene color");
            Path directory = client.gameDirectory.toPath().resolve("screenshots");
            // Queue both readbacks together, so moving shadows, clouds or a temporal shader
            // cannot generate false positives by changing the background between captures.
            capture(sceneCopy, directory.resolve(name + "-input.png"), before);
            capture(client.getMainRenderTarget(), directory.resolve(name + "-output.png"), after);
        });
        context.waitFor(client -> before.isDone() && after.isDone());
        Capture a = before.join(), b = after.join();
        if (a.width != b.width || a.height != b.height) throw new AssertionError("Optics target sizes differ");
        int brighter = 0, darker = 0, redLight = 0, sampled = 0;
        for (int y = a.height * 42 / 100; y < a.height * 58 / 100; y++) {
            for (int x = a.width * 44 / 100; x < a.width * 56 / 100; x++) {
                if (Math.abs(x - a.width / 2) < 16 && Math.abs(y - a.height / 2) < 16) continue;
                int i = x + y * a.width;
                int dr = channel(b.pixels[i], 16) - channel(a.pixels[i], 16);
                int dg = channel(b.pixels[i], 8) - channel(a.pixels[i], 8);
                int db = channel(b.pixels[i], 0) - channel(a.pixels[i], 0);
                if (Math.min(dr, Math.min(dg, db)) >= 5) brighter++;
                if (Math.max(dr, Math.max(dg, db)) <= -5) darker++;
                if (dr >= 8 && dr > Math.max(Math.abs(dg), Math.abs(db)) + 6) redLight++;
                sampled++;
            }
        }
        System.out.printf("XPORB_OPTICS %s samples=%d brighter=%d darker=%d redLight=%d%n",
                name, sampled, brighter, darker, redLight);
        int floor = Math.max(4, sampled / 400);
        if (heat ? (hidden ? brighter + darker > floor : Math.min(brighter, darker) < floor)
                : (hidden ? redLight > floor : redLight < floor)) {
            throw new AssertionError("Unexpected pixel response for " + name);
        }
    }

    private static int channel(int pixel, int shift) {
        return (pixel >> shift) & 255;
    }

    private static void capture(RenderTarget target, Path path, CompletableFuture<Capture> result) {
        Screenshot.takeScreenshot(target, image -> {
            try (image) {
                Files.createDirectories(path.getParent());
                image.writeToFile(path);
                result.complete(new Capture(image.getWidth(), image.getHeight(), image.getPixels()));
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
    }
}
