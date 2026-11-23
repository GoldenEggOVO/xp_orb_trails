package dev.goldeneggovo.xporbtrails;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

final class WorldWorkshopClientTest {
    private static final int BASE = 104825;

    static void run(ClientGameTestContext ctx, TestSingleplayerContext world) {
        var saved = ctx.computeOnClient(mc -> XpOrbTrailsClient.CONFIG);
        try {
            world.getServer().runCommand("gamemode spectator @p");
            world.getServer().runCommand("tp @p 0.5 246 0.5 0 9");
            world.getServer().runCommand("weather clear");
            world.getServer().runCommand("time set noon");
            world.getServer().runCommand("fill -6 244 1 16 250 10 air");
            world.getClientLevel().waitForChunksRender();
            ctx.waitTicks(5);
            school(ctx, world);
            crossing(ctx);
            current(ctx, world);
            redstone(ctx, world);
            fusion(ctx, world);
        } finally {
            ctx.runOnClient(mc -> {
                clear(mc);
                XpOrbTrailsClient.CONFIG = saved;
            });
        }
    }

    private static void select(ClientGameTestContext ctx, String name) {
        ctx.runOnClient(mc -> {
            clear(mc);
            var cfg = new TrailConfig();
            boolean river = name.equals("school") || name.equals("current");
            var style = EffectCollectionClientTest.profile(river ? "lab-river" : "lab-" + name);
            style.settings().applyTo(cfg);
            if (river) cfg.advanced.getAsJsonArray("workshop").asList()
                    .removeIf(effect -> !name.equals(effect.getAsJsonObject().get("type").getAsString()));
            XpOrbTrailsClient.CONFIG = cfg;
            mc.setScreen(null);
        });
    }

    private static void clear(Minecraft mc) {
        for (int i = 0; i < 3; i++) if (mc.level.getEntity(BASE + i) != null)
            mc.level.removeEntity(BASE + i, Entity.RemovalReason.DISCARDED);
        TrailRenderer.updateWorld(null);
        Workshop.clear();
    }

    private static void place(Minecraft mc, int slot, Vec3 at) {
        ExperienceOrb orb = (ExperienceOrb) mc.level.getEntity(BASE + slot);
        if (orb == null) {
            // The test drives positions against the real ClientLevel at known tick boundaries.
            orb = new ExperienceOrb(mc.level, at.x, at.y, at.z, 7) {
                @Override public void tick() {}
            };
            orb.setId(BASE + slot);
            mc.level.addEntity(orb);
        }
        orb.setPos(at);
        TrailRenderer.track(orb);
    }

    private record Frame(int primitives, List<Vec3> points) {}

    private static Frame frame(Minecraft mc) {
        var frame = new EffectFrame();
        Workshop.extract(frame, mc.level, 1, System.nanoTime());
        Vec3 camera = mc.gameRenderer.getMainCamera().position();
        var points = new ArrayList<Vec3>();
        VertexConsumer sink = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                new Class[]{VertexConsumer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("addVertex") && args.length == 3)
                        points.add(camera.add((float) args[0], (float) args[1], (float) args[2]));
                    return proxy;
                });
        frame.draw(sink, camera);
        return new Frame(frame.size(), points);
    }

    private static void school(ClientGameTestContext ctx, TestSingleplayerContext world) {
        select(ctx, "school");
        world.getServer().runCommand("fill 0 246 5 0 248 5 stone");
        ctx.waitFor(mc -> mc.level.getBlockState(new BlockPos(0, 246, 5)).is(Blocks.STONE));
        int sides = 0;
        for (int step = 0; step < 40; step++) {
            ctx.waitTicks(1);
            final double z = Math.min(7.8, 4.1 + step * .14);
            int seen = ctx.computeOnClient(mc -> {
                place(mc, 0, new Vec3(.5, 246.6, z));
                var fish = frame(mc);
                if (fish.primitives != 24) throw new AssertionError("Missing school of eight fish");
                int mask = 0;
                for (int i = 0; i < 8; i++) {
                    Vec3 nose = fish.points.get(i * 12), tail = fish.points.get(i * 12 + 2);
                    Vec3 center = nose.lerp(tail, 1 / 1.72);
                    if (center.z > 5.025 && center.z < 5.975) {
                        if (center.x > .003 && center.x < .997) throw new AssertionError("Fish entered stone " + center);
                        mask |= center.x < .5 ? 1 : 2;
                    }
                }
                return mask;
            });
            sides |= seen;
            if (step == 21) ctx.takeScreenshot("lab-school-world");
        }
        if (sides != 3) throw new AssertionError("School did not pass both sides of the pillar: " + sides);
        ctx.runOnClient(mc -> {
            var cfg = XpOrbTrailsClient.CONFIG;
            cfg.advanced = cfg.advanced.deepCopy();
            var glide = EffectCollectionClientTest.profile("workshop-festival").settings().advanced
                    .getAsJsonObject("body").get("glideSprite").getAsString();
            var selected = XpOrbTrailsClient.sprite(AssetRole.ORB, glide);
            if (selected == null) throw new AssertionError("Packed bird atlas was not loaded");
            cfg.advanced.getAsJsonArray("workshop").get(0).getAsJsonObject().addProperty("sprite", glide);
            place(mc, 0, new Vec3(.5, 246.6, 7.8));
            var sprites = new EffectFrame() {
                int birds;
                @Override void sprite(SpriteSheet sheet, Vec3 at, double size, double age, int color, double rotation) {
                    if (sheet == selected && Double.isFinite(rotation)) birds++;
                }
            };
            Workshop.extract(sprites, mc.level, 1, System.nanoTime());
            if (sprites.birds != 8) throw new AssertionError("School did not use the selected orb sprite sheet");
        });
        world.getServer().runCommand("fill 0 246 5 0 248 5 air");
        System.out.println("XPORB_WORLD school split_both_sides");
    }

    private static void crossing(ClientGameTestContext ctx) {
        select(ctx, "crossing");
        Vec3[] first = {new Vec3(-.5, 246.6, 5), new Vec3(1.5, 246.6, 5),
                new Vec3(.5, 246.6, 4.3), new Vec3(.5, 246.6, 5.7)};
        for (Vec3 p : first) {
            ctx.waitTicks(1);
            ctx.runOnClient(mc -> {
                place(mc, 0, p);
                if (frame(mc).primitives != 0) throw new AssertionError("A trail knotted with itself");
            });
        }
        ctx.runOnClient(mc -> {
            mc.level.removeEntity(BASE, Entity.RemovalReason.DISCARDED);
            place(mc, 1, new Vec3(.5, 246.6, 4.25));
        });
        ctx.waitTicks(1);
        ctx.runOnClient(mc -> {
            place(mc, 1, new Vec3(.5, 246.6, 5.75));
            if (frame(mc).primitives != 24) throw new AssertionError("No knot on prior orb path");
        });
        ctx.takeScreenshot("lab-crossing-world");
        ctx.waitTicks(18);
        ctx.runOnClient(mc -> { if (frame(mc).primitives != 0) throw new AssertionError("Knot outlived its effect"); });
        System.out.println("XPORB_WORLD crossing historical_other_only_and_expires");
    }

    private static void current(ClientGameTestContext ctx, TestSingleplayerContext world) {
        select(ctx, "current");
        world.getServer().runCommand("tp @p -1.5 247.8 2.4 0 43");
        world.getServer().runCommand("fill -5 245 4 4 245 6 stone");
        world.getServer().runCommand("fill -5 246 4 4 246 4 glass");
        world.getServer().runCommand("fill -5 246 6 4 246 6 stone");
        world.getServer().runCommand("setblock -5 246 5 stone");
        world.getServer().runCommand("setblock -4 246 5 water");
        for (String block : List.of("0 245 8 soul_sand", "2 245 8 magma_block", "0 246 8 water", "2 246 8 water"))
            world.getServer().runCommand("setblock " + block);
        Vec3 birth = new Vec3(-1.5, 246.12, 5.5);
        ctx.waitFor(mc -> WorldWorkshop.flow(mc.level, birth).x > .1
                && WorldWorkshop.flow(mc.level, new Vec3(.5, 246.2, 8.5)).y > .5
                && WorldWorkshop.flow(mc.level, new Vec3(2.5, 246.2, 8.5)).y < -.5);
        List<Double> starts = ctx.computeOnClient(mc -> {
            place(mc, 0, birth);
            var sample = frame(mc);
            if (sample.primitives != 4) throw new AssertionError("Stream must seed two motes");
            return List.of(sample.points.get(4).x, sample.points.get(12).x);
        });
        ctx.waitTicks(1);
        ctx.runOnClient(mc -> {
            place(mc, 0, birth);
            var sample = frame(mc);
            if (sample.points.get(4).x <= starts.get(0) + .04 || sample.points.get(12).x <= starts.get(1) + .04)
                throw new AssertionError("Existing motes did not travel downstream");
        });
        for (int i = 0; i < 10; i++) {
            ctx.waitTicks(1);
            ctx.runOnClient(mc -> place(mc, 0, birth));
        }
        ctx.takeScreenshot("lab-current-world");
        System.out.println("XPORB_WORLD current downstream_advection_and_both_bubble_directions");
        world.getServer().runCommand("fill -5 244 4 4 247 9 air");
    }

    private static void redstone(ClientGameTestContext ctx, TestSingleplayerContext world) {
        select(ctx, "redstone");
        world.getServer().runCommand("tp @p -1.5 246 1.9 0 17");
        world.getServer().runCommand("fill -3 245 5 13 245 5 stone");
        world.getServer().runCommand("setblock -3 246 5 redstone_block");
        world.getServer().runCommand("fill -2 246 5 13 246 5 redstone_wire");
        ctx.waitFor(mc -> WorldWorkshop.signal(mc.level, new BlockPos(-2, 246, 5)) == 15
                && WorldWorkshop.signal(mc.level, new BlockPos(5, 246, 5)) == 8);
        int[] strengths = {0, 8, 15};
        double last = 0;
        for (int expected : strengths) {
            ctx.waitTicks(1);
            double spread = ctx.computeOnClient(mc -> {
                BlockPos wire = new BlockPos(13 - expected, 246, 5);
                if (WorldWorkshop.signal(mc.level, wire) != expected) throw new AssertionError("Wrong wire level " + expected);
                Vec3 p = Vec3.atBottomCenterOf(wire).add(0, .14, 0);
                place(mc, 0, p);
                return frame(mc).points.get(2).x - p.x;
            });
            if (spread <= last) throw new AssertionError("Petals did not open at level " + expected);
            last = spread;
        }
        ctx.takeScreenshot("lab-redstone-world");
        System.out.println("XPORB_WORLD redstone actual_wire_0_8_15");
        world.getServer().runCommand("fill -3 246 5 13 246 5 air");
        world.getServer().runCommand("fill -3 245 5 13 245 5 air");
    }

    private static void fusion(ClientGameTestContext ctx, TestSingleplayerContext world) {
        select(ctx, "fusion");
        world.getServer().runCommand("tp @p 0 246 2.2 0 14");
        ctx.waitTicks(3);
        for (double distance : new double[]{2.3, .75, 2.3}) {
            ctx.waitTicks(1);
            ctx.runOnClient(mc -> {
                place(mc, 0, new Vec3(-.4, 246.7, 4.7));
                place(mc, 1, new Vec3(-.4 + distance, 246.7, 4.7));
                int expected = distance < 1 ? 48 : 0;
                if (frame(mc).primitives != expected) throw new AssertionError("Unexpected fusion geometry at " + distance);
            });
            if (distance < 1) ctx.takeScreenshot("lab-fusion-world");
        }
        System.out.println("XPORB_WORLD fusion approach_and_separation");
    }

    private WorldWorkshopClientTest() {}
}
