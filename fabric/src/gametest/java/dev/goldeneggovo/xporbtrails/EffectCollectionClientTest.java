package dev.goldeneggovo.xporbtrails;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;

final class EffectCollectionClientTest {
    private static final int ORB = 114732;

    static void withPack(ClientGameTestContext context, Runnable test) {
        withPack(context, Path.of(System.getProperty("xporb.effectCollection")), () -> {
            if (context.computeOnClient(mc -> profiles().size()) != 24)
                throw new AssertionError("Expected 24 styles from the published effect collection");
            test.run();
        });
    }

    static void withPack(ClientGameTestContext context, Path source, Runnable test) {
        var destination = context.computeOnClient(mc -> XpOrbTrailsClient.assetDirectory().resolve("packs")
                .resolve(source.getFileName()));
        try {
            boolean exists = Files.exists(destination);
            if (exists && Files.mismatch(source, destination) != -1)
                throw new AssertionError("Test directory contains a different pack: " + destination);
            if (!exists) Files.copy(source, destination);
            try {
                context.runOnClient(mc -> {
                    if (XpOrbTrailsClient.reloadFileStyles() != 0) throw new AssertionError("Failed to load " + source);
                });
                test.run();
            } finally {
                if (!exists) {
                    Files.delete(destination);
                    context.runOnClient(mc -> XpOrbTrailsClient.reloadFileStyles());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<FileTrailStyles.Style> profiles() {
        String pack = Path.of(System.getProperty("xporb.effectCollection")).getFileName().toString();
        return XpOrbTrailsClient.fileStyles().stream().filter(s -> pack.equals(s.pack())).toList();
    }

    static FileTrailStyles.Style profile(String name) {
        return profiles().stream().filter(s -> name.equals(s.name())).findFirst().orElseThrow();
    }

    static void gallery(ClientGameTestContext ctx) {
        var world = ctx.worldBuilder().create();
        var saved = ctx.computeOnClient(mc -> XpOrbTrailsClient.CONFIG);
        boolean wasHidden = ctx.computeOnClient(mc -> mc.options.hideGui);
        var clockFailures = new ArrayList<String>();
        var orbIds = List.of(ORB, ORB + 1);
        try {
            world.getServer().runCommand("gamemode spectator @p");
            world.getServer().runCommand("tp @p 0.5 241 0.5 0 18");
            world.getServer().runCommand("time set noon");
            world.getServer().runCommand("weather clear");
            world.getServer().runCommand("fill -12 239 -2 12 239 14 gray_concrete");
            world.getServer().runCommand("fill -12 240 9 12 251 9 gray_concrete");
            world.getClientLevel().waitForChunksRender();
            ctx.runOnClient(mc -> mc.options.hideGui = true);
            ctx.waitTicks(8);
            for (var style : ctx.computeOnClient(mc -> profiles())) {
                boolean river = style.name().equals("lab-river");
                boolean tide = style.name().equals("workshop-tideglass");
                boolean dusk = style.name().equals("workshop-dusk");
                boolean redstone = style.name().equals("lab-redstone");
                boolean groundBurst = style.name().equals("lab-ink") || style.name().equals("lab-events");
                boolean hoops = style.name().equals("lab-hoops");
                if (groundBurst) world.getServer().runCommand("tp @p 1.25 241.8 1.25 0 42");
                if (dusk) {
                    world.getServer().runCommand("time set 12700");
                    world.getServer().runCommand("weather rain");
                    ctx.waitTicks(20);
                }
                if (tide) {
                    world.getServer().runCommand("fill -4 240 2 3 240 6 polished_deepslate");
                    world.getServer().runCommand("fill -3 240 3 0 240 5 water");
                    world.getServer().runCommand("tp @p 0.4 242 1.4 -3 39");
                }
                if (river) {
                    world.getServer().runCommand("tp @p 0.4 241.5 2 -2 50");
                    world.getServer().runCommand("fill -3 239 4 3 239 4 air");
                    world.getServer().runCommand("fill -3 238 4 3 238 4 gray_concrete");
                    world.getServer().runCommand("setblock -3 239 4 water");
                    ctx.waitFor(mc -> !mc.level.getFluidState(new net.minecraft.core.BlockPos(0, 239, 4)).isEmpty());
                }
                if (redstone) world.getServer().runCommand("fill -2 239 4 2 239 4 redstone_block");
                ctx.runOnClient(mc -> {
                    clear(mc);
                    var cfg = new TrailConfig();
                    style.settings().applyTo(cfg);
                    XpOrbTrailsClient.CONFIG = cfg;
                });
                for (int step = 0; step <= 18; step++) {
                    final double t = step / 18.0;
                    ctx.runOnClient(mc -> {
                        double y = river ? 240.13 : tide ? 241.33 : redstone ? 240.18 : 240.88;
                        double z = hoops ? 6.05 - 2.7*t : 4.48;
                        place(mc, ORB, new Vec3(-1.35 + 2.6*t, y + .18*Math.sin(t*Math.TAU), z));
                        if (river) place(mc, ORB + 1, new Vec3(-1.35 + 1.9*t, 239.37, 4.50));
                        else if (!groundBurst && !hoops) place(mc, ORB + 1, new Vec3(.48, y - .08, 3.23 + 1.8*t));
                    });
                    ctx.waitTicks(1);
                }
                TrailEffectsClientTest.screenshot(ctx, "collection-" + style.name() + "-flight", image -> true);
                int flightSamples = ctx.computeOnClient(mc -> EffectClockClientTest.check(mc, orbIds, style.name() + "/flight", clockFailures));
                ctx.runOnClient(mc -> {
                    mc.getConnection().handleTakeItemEntity(new ClientboundTakeItemEntityPacket(ORB, mc.player.getId(), 1));
                    mc.level.removeEntity(ORB, Entity.RemovalReason.DISCARDED);
                });
                if (groundBurst || hoops) {
                    String name = style.name();
                    ctx.waitFor(mc -> {
                        int visible = pickupGeometry(mc, name);
                        int minimum = name.equals("lab-ink") ? 5 : name.equals("lab-events") ? 28 : 32;
                        if (visible < minimum) return false;
                        System.out.println("XPORB_COLLECTION " + name + " pickup primitives=" + visible);
                        return true;
                    });
                    ctx.waitTicks(2);
                } else ctx.waitTicks(style.name().equals("lab-butterfly") ? 16 : tide ? 14 : 5);
                TrailEffectsClientTest.screenshot(ctx, "collection-" + style.name() + "-pickup", image -> true);
                int pickupSamples = ctx.computeOnClient(mc -> EffectClockClientTest.check(mc, orbIds, style.name() + "/pickup", clockFailures));
                if (flightSamples + pickupSamples == 0) clockFailures.add(style.name() + " produced no visible effect");
                if (groundBurst || tide || river) world.getServer().runCommand("tp @p 0.5 241 0.5 0 18");
                if (river || redstone) world.getServer().runCommand("fill -3 239 4 3 239 4 gray_concrete");
                if (tide) world.getServer().runCommand("fill -4 240 2 3 240 6 air");
                if (dusk) {
                    world.getServer().runCommand("time set noon");
                    world.getServer().runCommand("weather clear");
                }
                System.out.println("XPORB_COLLECTION captured " + style.name());
            }
            if (!clockFailures.isEmpty()) throw new AssertionError("Server time corrections changed effects: " + clockFailures);
        } finally {
            ctx.runOnClient(mc -> {
                clear(mc);
                mc.options.hideGui = wasHidden;
                XpOrbTrailsClient.CONFIG = saved;
            });
            TrailClientTest.closeWorld(ctx, world);
        }
    }

    private static int pickupGeometry(Minecraft mc, String name) {
        var options = XpOrbTrailsClient.CONFIG.advanced.getAsJsonArray("workshop").get(0).getAsJsonObject();
        int lineColor = switch (name) {
            case "lab-events" -> Integer.parseInt(options.getAsJsonObject("emitters").getAsJsonObject("snow")
                    .get("color").getAsString().substring(1), 16);
            case "lab-hoops" -> Integer.parseInt(options.get("litColor").getAsString().substring(1), 16);
            default -> -1;
        };
        var frame = new EffectFrame() {
            int visible;
            @Override void line(Vec3 p, Vec3 q, double width, int color) {
                if ((color & 0xffffff) == lineColor && (color >>> 24) >= 128) visible++;
            }
            @Override void decal(SpriteSheet sheet, SpriteSheet.Frame uv, Vec3 p, Vec3 normal, double size, int color) {
                if (name.equals("lab-ink") && normal.y > .9 && (color >>> 24) >= 128) visible++;
            }
        };
        if (name.equals("lab-hoops")) MotionWorkshop.extract(frame, mc.level, 1);
        else EventParticles.extract(frame, 1);
        return frame.visible;
    }

    private static void clear(Minecraft mc) {
        for (int i = ORB; i <= ORB + 1; i++) {
            if (mc.level.getEntity(i) != null) mc.level.removeEntity(i, Entity.RemovalReason.DISCARDED);
        }
        TrailRenderer.updateWorld(null);
        TrailRenderer.updateWorld(mc.level);
    }

    private static void place(Minecraft mc, int id, Vec3 position) {
        var orb = (ExperienceOrb) mc.level.getEntity(id);
        if (orb == null) {
            orb = new ExperienceOrb(mc.level, position.x, position.y, position.z, 3) {
                @Override public void tick() { tickCount++; }
            };
            orb.setId(id);
            mc.level.addEntity(orb);
        }
        orb.setDeltaMovement(position.subtract(orb.position()));
        orb.setPos(position);
        orb.setOldPosAndRot();
        TrailRenderer.track(orb);
    }
}
