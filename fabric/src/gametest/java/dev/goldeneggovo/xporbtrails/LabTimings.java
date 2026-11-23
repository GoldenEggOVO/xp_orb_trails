package dev.goldeneggovo.xporbtrails;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class LabTimings {
    private static final int BASE = 320100, ORBS = 48, CAPACITY = 32000;
    private static final long[] intervals = new long[CAPACITY], renderer = new long[CAPACITY];
    private static boolean running, sampling;
    private static long last, start;
    private static int frames, draws, tick;

    public static void beforeRender() {
        if (!sampling) return;
        start = System.nanoTime();
        if (last != 0 && frames < CAPACITY) intervals[frames++] = start - last;
        last = start;
    }

    public static void afterRender() {
        if (sampling && start != 0 && draws < CAPACITY)
            renderer[draws++] = System.nanoTime() - start;
    }

    public static void step() {
        if (!running) return;
        var client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        if (sampling
                && client.getFramerateLimitTracker().getThrottleReason()
                        != com.mojang.blaze3d.platform.FramerateLimitTracker.FramerateThrottleReason
                                .NONE)
            throw new AssertionError(
                    "Throttled benchmark: "
                            + client.getFramerateLimitTracker().getThrottleReason());
        tick++;
        for (int i = 0; i < ORBS; i++) {
            int slot = (tick + 3 * i) % 52;
            double t = slot / 52., a = i * 2.39996323 + tick * .028;
            var pos =
                    new Vec3(
                            .5 + Math.cos(a) * (3.6 - 2.7 * t),
                            240.4 + .09 * (i % 5) + Math.sin(a) * .21,
                            3 + Math.sin(a) * (1.9 - .7 * t));
            var orb = (ExperienceOrb) client.level.getEntity(BASE + i);
            if (orb == null) {
                orb = new ExperienceOrb(client.level, pos.x, pos.y, pos.z, 1 + i % 7);
                orb.setId(BASE + i);
                orb.setNoGravity(true);
                orb.setUUID(new java.util.UUID(31, i + 1));
                client.level.addEntity(orb);
            }
            orb.setOldPosAndRot();
            orb.setPos(pos.x, pos.y, pos.z);
            orb.setDeltaMovement(Vec3.ZERO);
            if (tick % 26 == 0 && i % 4 == 0) {
                client.getConnection()
                        .handleTakeItemEntity(
                                new ClientboundTakeItemEntityPacket(
                                        BASE + i, client.player.getId(), 1));
                client.level.removeEntity(BASE + i, Entity.RemovalReason.DISCARDED);
            }
        }
    }

    private static double percentile(long[] source, int n, double quantile) {
        var copy = Arrays.copyOf(source, n);
        Arrays.sort(copy);
        return copy[Math.min(n - 1, (int) Math.ceil(n * quantile) - 1)] / 1e6;
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext world) {
        world.getServer().runCommand("gamemode spectator @p");
        world.getServer().runCommand("tp @p 0.5 240 -2 0 11");
        world.getServer().runCommand("fill -8 239 -4 8 239 12 polished_andesite");
        world.getServer().runCommand("weather clear");
        world.getServer().runCommand("time set noon");
        world.getClientLevel().waitForChunksRender();
        ctx.waitTicks(20);
        ctx.runOnClient(
                c -> {
                    c.options.enableVsync().set(false);
                    c.options.framerateLimit().set(260);
                    c.options
                            .inactivityFpsLimit()
                            .set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
                });
        var modes =
                List.of(
                        "off",
                        "standard",
                        "lab-events",
                        "lab-butterfly",
                        "lab-relief",
                        "workshop-mirage",
                        "workshop-mirage",
                        "lab-relief",
                        "lab-butterfly",
                        "lab-events",
                        "standard",
                        "off");
        int round = 0;
        try {
            for (var mode : modes) {
                ctx.runOnClient(
                        c -> {
                            running = false;
                            for (int i = 0; i < ORBS; i++)
                                c.level.removeEntity(BASE + i, Entity.RemovalReason.DISCARDED);
                            TrailRenderer.updateWorld(null);
                            if (mode.equals("off")) {
                                XpOrbTrailsClient.CONFIG = new TrailConfig();
                                XpOrbTrailsClient.CONFIG.enabled = false;
                            } else LabClientTest.apply(mode);
                            running = true;
                            tick = 0;
                        });
                ctx.waitTicks(36);
                ctx.runOnClient(
                        c -> {
                            last = start = 0;
                            frames = draws = 0;
                            sampling = true;
                        });
                // Force rendered frames even when the Fabric test driver skips idle rendering.
                for (int i = 0; i < 60; i++) ctx.waitTicks(1);
                int run = ++round;
                ctx.runOnClient(
                        c -> {
                            sampling = false;
                            running = false;
                            if (frames < 10 || draws < 10)
                                throw new AssertionError("Too few measured frames " + frames);
                            System.out.printf(
                                    Locale.ROOT,
                                    "XPORB_FRAMES run=%d mode=%s orbs=%d resolution=%dx%d frames=%d"
                                            + " interval_p50=%.3f interval_p95=%.3f render_p50=%.3f"
                                            + " render_p95=%.3f%n",
                                    run,
                                    mode,
                                    ORBS,
                                    c.getWindow().getWidth(),
                                    c.getWindow().getHeight(),
                                    frames,
                                    percentile(intervals, frames, .5),
                                    percentile(intervals, frames, .95),
                                    percentile(renderer, draws, .5),
                                    percentile(renderer, draws, .95));
                        });
            }
        } finally {
            ctx.runOnClient(
                    c -> {
                        sampling = running = false;
                        for (int i = 0; i < ORBS; i++)
                            c.level.removeEntity(BASE + i, Entity.RemovalReason.DISCARDED);
                    });
        }
    }
}
