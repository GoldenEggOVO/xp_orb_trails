package dev.goldeneggovo.xporbtrails;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

final class PixelWorkshopClientTest {
    private static final int ID = 894351;

    static void run(ClientGameTestContext ctx, TestSingleplayerContext world) {
        TrailConfig original = XpOrbTrailsClient.CONFIG;
        world.getServer().runCommand("gamemode spectator @p");
        world.getServer().runCommand("tp @p 0.5 240 0.5 0 0");
        world.getClientLevel().waitForChunksRender();
        ctx.waitTicks(4);
        try {
            ctx.runOnClient(client -> {
                reset(client);
                XpOrbTrailsClient.CONFIG = new TrailConfig();
            });
            ctx.waitTicks(2);
            var clear = capture(ctx, "baseline");
            for (String type : List.of("directional", "relief", "fracture")) {
                ctx.runOnClient(client -> spawn(client, type));
                ctx.waitTicks(4);
                if (type.equals("fracture")) {
                    ctx.runOnClient(client -> {
                        int before = size(client);
                        require(before == 0, type + " before pickup " + before);
                        client.getConnection().handleTakeItemEntity(new ClientboundTakeItemEntityPacket(ID, client.player.getId(), 1));
                        client.level.removeEntity(ID, Entity.RemovalReason.DISCARDED);
                    });
                    ctx.waitTicks(4);
                }
                ctx.runOnClient(client -> {
                    int count = size(client);
                    int minimum = switch (type) {
                        case "directional" -> 1;
                        case "fracture" -> 5;
                        case "relief" -> 80;
                        default -> throw new AssertionError(type);
                    };
                    require(count >= minimum, type + " submitted " + count + " primitives");
                });
                requireArtwork(ctx, type, clear);
                if (type.equals("relief")) ctx.runOnClient(client -> {
                    var relief = XpOrbTrailsClient.CONFIG.advanced.getAsJsonArray("workshop").get(0).getAsJsonObject();
                    relief.addProperty("pixelBudget", 36);
                    int coarse = size(client);
                    relief.addProperty("minResolution", 24);
                    relief.addProperty("maxResolution", 24);
                    int fixed = size(client);
                    require(fixed >= 80 && fixed > 2 * coarse, "fixed 24-pixel relief: " + fixed + " vs " + coarse + " quads");
                });
                if (type.equals("directional")) orbitViews(ctx, world);
                ctx.runOnClient(PixelWorkshopClientTest::reset);
                ctx.waitTicks(1);
            }
            ctx.runOnClient(c -> spawn(c, "butterfly"));
            ctx.waitTicks(7);
            requireArtwork(ctx, "butterfly-shed", clear);
            ctx.runOnClient(PixelWorkshopClientTest::checkTrailCapture);
            ctx.waitTicks(18);
            requireArtwork(ctx, "butterfly-assemble", clear);
        } finally {
            ctx.runOnClient(client -> {
                reset(client);
                XpOrbTrailsClient.CONFIG = original;
            });
        }
    }

    private static void checkTrailCapture(Minecraft client) {
        class Centers extends EffectFrame {
            final List<Vec3> points = new ArrayList<>();
            @Override void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color) {
                points.add(a.lerp(c, .5));
            }
        }
        var before = new Centers();
        PixelWorkshop.extract(before, client.level, 0, System.nanoTime());
        require(before.points.size() >= 5, "butterfly emitted real flying pixels");
        client.getConnection().handleTakeItemEntity(new ClientboundTakeItemEntityPacket(ID, client.player.getId(), 1));
        client.level.removeEntity(ID, Entity.RemovalReason.DISCARDED);
        var after = new Centers();
        PixelWorkshop.extract(after, client.level, 0, System.nanoTime());
        require(after.points.size() >= 20 && after.points.stream().allMatch(p ->
                        before.points.stream().anyMatch(old -> old.distanceToSqr(p) < 1e-12)),
                "every assembly mote started at a live shed pixel");
    }

    private static void orbitViews(ClientGameTestContext ctx, TestSingleplayerContext world) {
        Vec3 orb = ctx.computeOnClient(c -> c.level.getEntity(ID).position());
        var frames = new HashSet<SpriteSheet.Frame>();
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            world.getServer().runCommand(String.format(Locale.ROOT, "tp @p %.4f 240 %.4f facing %.4f %.4f %.4f",
                    orb.x - 3 * Math.sin(a), orb.z + 3 * Math.cos(a), orb.x, orb.y, orb.z));
            ctx.waitTicks(3);
            var frame = ctx.computeOnClient(c -> {
                class Viewed extends EffectFrame {
                    SpriteSheet.Frame frame;
                    @Override void spriteFrame(SpriteSheet sprite, SpriteSheet.Frame uv, Vec3 pos, double size, int color, double angle) {
                        frame = uv;
                    }
                }
                var out = new Viewed();
                PixelWorkshop.extract(out, c.level, 1, System.nanoTime());
                return out.frame;
            });
            require(frame != null && frames.add(frame), "unique atlas frame at camera sector " + i);
            if (i == 0 || i == 2) capture(ctx, "directional-sector-" + i);
        }
        world.getServer().runCommand("tp @p 0.5 240 0.5 0 0");
        ctx.waitTicks(4);
    }

    private static int[] capture(ClientGameTestContext ctx, String id) {
        return TrailEffectsClientTest.screenshot(ctx, "lab-pixel-" + id + "-world", image -> {
            int left = image.getWidth() / 4, right = image.getWidth() * 3 / 4;
            int top = image.getHeight() / 3, bottom = image.getHeight() * 2 / 3;
            int[] pixels = new int[(right - left) * (bottom - top)];
            for (int y = top; y < bottom; y++) {
                for (int x = left; x < right; x++) pixels[(y - top) * (right - left) + x - left] = image.getPixel(x, y);
            }
            return pixels;
        });
    }

    private static void requireArtwork(ClientGameTestContext ctx, String id, int[] background) {
        var pixels = capture(ctx, id);
        int changed = 0;
        for (int i = 0; i < pixels.length; i++) {
            int a = pixels[i], b = background[i];
            if (Math.abs((a >> 16 & 255) - (b >> 16 & 255)) > 24
                    || Math.abs((a >> 8 & 255) - (b >> 8 & 255)) > 24
                    || Math.abs((a & 255) - (b & 255)) > 24) changed++;
        }
        require(changed >= 3, id + " changed " + changed + " pixels against the empty background");
    }

    private static void spawn(Minecraft client, String type) {
        var style = EffectCollectionClientTest.profile("lab-" + type);
        var config = new TrailConfig();
        style.settings().applyTo(config);
        XpOrbTrailsClient.CONFIG = config;
        Vec3 start = client.gameRenderer.getMainCamera().position().add(-.12, -.14, 3);
        var orb = new ExperienceOrb(client.level, start.x, start.y, start.z, 3);
        orb.setId(ID);
        orb.setNoGravity(true);
        orb.setOldPosAndRot();
        orb.setDeltaMovement(type.equals("butterfly") ? new Vec3(.04, 0, 0) : Vec3.ZERO);
        client.level.addEntity(orb);
        TrailRenderer.track(orb);
    }

    private static int size(Minecraft client) {
        EffectFrame f = new EffectFrame();
        PixelWorkshop.extract(f, client.level, 1, System.nanoTime());
        return f.size();
    }

    private static void reset(Minecraft client) {
        if (client.level.getEntity(ID) != null) client.level.removeEntity(ID, Entity.RemovalReason.DISCARDED);
        TrailRenderer.updateWorld(null);
        TrailRenderer.updateWorld(client.level);
    }

    private static void require(boolean ok, String text) {
        if (!ok) throw new AssertionError(text);
        System.out.println("XPORB_PIXEL " + text);
    }
}
