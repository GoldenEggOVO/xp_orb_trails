package dev.goldeneggovo.xporbtrails;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

final class LabClientTest {
    static void run(ClientGameTestContext ctx) {
        var world = ctx.worldBuilder().create();
        var previous = ctx.computeOnClient(c -> XpOrbTrailsClient.CONFIG);
        String part = System.getProperty("xporb.labPart", "all");
        try {
            if (part.equals("bench")) {
                LabTimings.run(ctx, world);
                return;
            }
            if (part.equals("all") || part.equals("events")) events(ctx, world);
            if (part.equals("all") || part.equals("spawn")) {
                ctx.runOnClient(LabClientTest::newbornOrbBodies);
                EffectLifecycleClientTest.run(ctx, world);
            }
            if (part.equals("all") || part.equals("pixel")) PixelWorkshopClientTest.run(ctx, world);
            if (part.equals("all") || part.equals("motion"))
                MotionWorkshopClientTest.run(ctx, world);
            if (part.equals("all") || part.equals("world")) WorldWorkshopClientTest.run(ctx, world);
            if (part.equals("all") || part.equals("menu")) menu(ctx);
        } finally {
            ctx.runOnClient(
                    c -> {
                        c.gui.setScreen(null);
                        XpOrbTrailsClient.CONFIG = previous;
                        TrailRenderer.updateWorld(null);
                    });
            TrailClientTest.closeWorld(ctx, world);
        }
    }

    static void apply(String filename) {
        var style =
                filename.equals("Comet Pennant")
                        ? XpOrbTrailsClient.fileStyles().stream().filter(s -> s.pack() == null && s.name().equals(filename)).findFirst().orElseThrow()
                        : EffectCollectionClientTest.profile(filename);
        var c = new TrailConfig();
        style.settings().applyTo(c);
        XpOrbTrailsClient.CONFIG = c;
    }

    private static void newbornOrbBodies(net.minecraft.client.Minecraft client) {
        var camera = client.gameRenderer.mainCamera().position();
        for (String profile : List.of("Comet Pennant", "workshop-observatory", "lab-relief")) {
            apply(profile);
            var cfg = XpOrbTrailsClient.CONFIG;
            TrailRenderer.updateWorld(null);
            var orb = new ExperienceOrb(client.level, camera.x, camera.y, camera.z + 3, 3);
            orb.setId(109543);
            orb.setOldPosAndRot();
            client.level.addEntity(orb);
            try {
                if (orb.tickCount != 0 || nativeBody(client, orb))
                    throw new AssertionError(profile + " showed the vanilla orb before its first tick");
                TrailRenderer.extract(client.level, camera, 0);
                if (profile.equals("Comet Pennant") ? ((List<?>) TrailClientTest.field(null, "renderTrails")).isEmpty()
                        : ((EffectFrame) TrailClientTest.field(null, "advancedFrame")).size() == 0)
                    throw new AssertionError(profile + " had no replacement on the first frame");
                cfg.showVanillaOrb = true;
                if (!nativeBody(client, orb)) throw new AssertionError("Native orb toggle did not take effect");
                cfg.showVanillaOrb = false;
                cfg.enabled = false;
                if (!nativeBody(client, orb)) throw new AssertionError("Master switch did not restore native art");
            } finally {
                client.level.removeEntity(orb.getId(), Entity.RemovalReason.DISCARDED);
            }
        }
        apply("Comet Pennant");
        var cfg = XpOrbTrailsClient.CONFIG;
        cfg.trailCap = 8;
        cfg.renderRange = 8;
        TrailRenderer.updateWorld(null);
        try {
            for (int i = 0; i <= cfg.trailCap; i++) {
                var orb = new ExperienceOrb(client.level, camera.x, camera.y, camera.z + 3, 3);
                orb.setId(109543 + i);
                orb.setOldPosAndRot();
                client.level.addEntity(orb);
                if (nativeBody(client, orb) != (i == cfg.trailCap)) throw new AssertionError("Newborn orb cap at " + i);
            }
            var first = (ExperienceOrb) client.level.getEntity(109543);
            first.setPos(camera.x, camera.y, camera.z + cfg.renderRange + 1);
            if (!nativeBody(client, first)) throw new AssertionError("Out-of-range replacement needs a fallback");
            first.setPos(camera.x, camera.y, camera.z + 3);
            cfg.flightEffect = "sprite:orb/missing-fixture";
            if (!nativeBody(client, first)) throw new AssertionError("Unavailable sprite needs a fallback");
        } finally {
            for (int i = 0; i <= cfg.trailCap; i++)
                client.level.removeEntity(109543 + i, Entity.RemovalReason.DISCARDED);
        }
    }

    private static boolean nativeBody(net.minecraft.client.Minecraft client, ExperienceOrb orb) {
        return ((OrbBodyVisibility) client.getEntityRenderDispatcher().extractEntity(orb, 0)).xporbtrails$showVanillaBody();
    }

    private static void menu(ClientGameTestContext ctx) {
        var reload =
                ctx.computeOnClient(
                        c -> {
                            c.getLanguageManager().setSelected("zh_cn");
                            c.options.languageCode = "zh_cn";
                            return c.reloadResourcePacks();
                        });
        ctx.waitFor(c -> reload.isDone() && c.gui.overlay() == null);
        ctx.runOnClient(c -> c.gui.setScreen(XpOrbTrailsClient.createConfigScreen(null)));
        ctx.clickScreenButton("screen.xporbtrails.profiles");
        var profiles =
                ctx.computeOnClient(
                        c ->
                                EffectCollectionClientTest.profiles().stream()
                                        .filter(s -> s.name().startsWith("lab-"))
                                        .toList());
        if (profiles.size() != 15)
            throw new AssertionError("Expected 15 lab profiles, got " + profiles.size());
        for (var p : profiles) {
            TrailClientTest.click(ctx, p.displayName().getString());
            ctx.runOnClient(
                    c -> {
                        if (!XpOrbTrailsClient.CONFIG.advanced.equals(p.settings().advanced))
                            throw new AssertionError(p.name());
                    });
        }
        ctx.takeScreenshot("effect-collection-profiles-zh");
        System.out.println("XPORB_LAB applied " + profiles.size() + " profiles in Chinese");
    }

    static void events(ClientGameTestContext ctx, TestSingleplayerContext world) {
        world.getServer().runCommand("gamemode spectator @p");
        world.getServer().runCommand("tp @p 0.5 240 0.5 0 12");
        world.getServer().runCommand("fill -4 239 2 4 239 7 polished_andesite");
        world.getServer().runCommand("fill 1 240 4 1 242 5 stone");
        world.getConnection().waitForChunksRender();
        ctx.waitTicks(6);
        final int id = 109542;
        for (String name : List.of("ink", "events", "water")) {
            if (name.equals("water")) {
                world.getServer().runCommand("fill -3 240 3 -1 240 5 glass");
                world.getServer().runCommand("setblock -2 240 4 water");
                world.getConnection().waitForChunksRender();
                ctx.waitTicks(3);
            }
            ctx.runOnClient(
                    c -> {
                        boolean water = name.equals("water");
                        TrailRenderer.updateWorld(null);
                        apply("lab-" + (water ? "ink" : name));
                        if (!name.equals("events")) {
                            var drops =
                                    XpOrbTrailsClient.CONFIG
                                            .advanced
                                            .getAsJsonArray("workshop")
                                            .get(0)
                                            .getAsJsonObject()
                                            .getAsJsonObject("emitters")
                                            .getAsJsonObject("drops");
                            drops.add(
                                    "velocity",
                                    com.google.gson.JsonParser.parseString(
                                            water ? "[0,-0.18,0]" : "[0.18,-0.03,0]"));
                            drops.addProperty("spread", .01);
                        }
                        var orb =
                                new ExperienceOrb(
                                        c.level,
                                        water ? -1.5 : .80,
                                        water ? 241.22 : 240.4,
                                        4.45,
                                        7);
                        orb.setId(id);
                        orb.setNoGravity(true);
                        c.level.addEntity(orb);
                        TrailRenderer.track(orb);
                        c.getConnection()
                                .handleTakeItemEntity(
                                        new ClientboundTakeItemEntityPacket(
                                                id, c.player.getId(), 1));
                        c.level.removeEntity(id, Entity.RemovalReason.DISCARDED);
                    });
            ctx.waitTicks(name.equals("ink") ? 8 : 6);
            ctx.runOnClient(
                    c -> {
                        class Capture extends EffectFrame {
                            int prints, flakes, waterRingEdges;

                            @Override
                            void decal(
                                    SpriteSheet sheet,
                                    SpriteSheet.Frame f,
                                    Vec3 at,
                                    Vec3 normal,
                                    double size,
                                    int color) {
                                if (normal.x < -.9 && at.x <= 1 && at.x > .99) prints++;
                            }

                            @Override
                            void line(Vec3 p, Vec3 q, double w, int color) {
                                flakes++;
                                if (p.x < -.5
                                        && p.y > 240.75
                                        && p.y < 241.02
                                        && Math.abs(p.y - q.y) < .0001) waterRingEdges++;
                            }
                        }
                        var f = new Capture();
                        EventParticles.extract(f, 1);
                        int found =
                                switch (name) {
                                    case "ink" -> f.prints;
                                    case "events" -> f.flakes;
                                    default -> f.waterRingEdges;
                                };
                        if (found == 0) throw new AssertionError("Missing " + name + " geometry");
                        System.out.println(
                                "XPORB_LAB "
                                        + name
                                        + " wallPrints="
                                        + f.prints
                                        + " lines="
                                        + f.flakes
                                        + " waterRingEdges="
                                        + f.waterRingEdges);
                    });
            ctx.takeScreenshot("lab-" + name + "-world");
        }
        world.getServer().runCommand("fill -4 240 2 4 242 7 air");
        ctx.waitFor(c -> c.level.getBlockState(new net.minecraft.core.BlockPos(-2, 240, 4)).isAir()
                && c.level.getBlockState(new net.minecraft.core.BlockPos(1, 240, 4)).isAir());
        verifyFloorInkAcrossClockSync(ctx);
        ctx.runOnClient(
                c -> {
                    TrailRenderer.updateWorld(null);
                    XpOrbTrailsClient.CONFIG = new TrailConfig();
                });
    }

    private static void verifyFloorInkAcrossClockSync(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> {
            TrailRenderer.updateWorld(null);
            apply("lab-ink");
            for (int i = 0; i < 2; i++) {
                var orb = new ExperienceOrb(c.level, -1.7 + i * 1.8, 240.12, 4, 3);
                orb.setId(109560 + i);
                c.level.addEntity(orb);
                TrailRenderer.track(orb);
                c.getConnection().handleTakeItemEntity(new ClientboundTakeItemEntityPacket(orb.getId(), c.player.getId(), 1));
                c.level.removeEntity(orb.getId(), Entity.RemovalReason.DISCARDED);
            }
        });
        ctx.waitTicks(26);
        ctx.runOnClient(c -> {
            long time = c.level.getGameTime();
            var before = floorInk();
            if (before.size() < 2) throw new AssertionError("Expected multiple ink decals on the floor");
            try {
                for (int correction : List.of(-1, 40)) {
                    c.getConnection().handleSetTime(new ClientboundSetTimePacket(time + correction, Map.of()));
                    var after = floorInk();
                    System.out.println("XPORB_INK_SYNC correction=" + correction + " floor decals before=" + before.size() + " after=" + after.size());
                    if (!before.equals(after)) throw new AssertionError("Server time correction changed live floor ink");
                }
            } finally {
                c.getConnection().handleSetTime(new ClientboundSetTimePacket(time, Map.of()));
            }
        });
        ctx.waitTicks(38);
        if (!ctx.computeOnClient(c -> floorInk().values().stream().anyMatch(color -> (color >>> 24) > 0 && (color >>> 24) < 255)))
            throw new AssertionError("Floor ink must fade progressively after server time corrections");
        ctx.waitTicks(40);
        if (!ctx.computeOnClient(c -> floorInk().isEmpty()))
            throw new AssertionError("Floor ink must expire after its configured lifetime");
    }

    private static Map<Vec3, Integer> floorInk() {
        var marks = new LinkedHashMap<Vec3, Integer>();
        var frame = new EffectFrame() {
            @Override void decal(SpriteSheet sheet, SpriteSheet.Frame f, Vec3 at, Vec3 normal, double size, int color) {
                if (normal.y > .9) marks.put(at, color);
            }
        };
        EventParticles.extract(frame, 0);
        return marks;
    }
}
