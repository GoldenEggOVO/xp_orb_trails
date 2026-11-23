package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

final class AdvancedSceneClientTest {
    private static final int BASE = 100893;

    static void run(ClientGameTestContext ctx) {
        TestSingleplayerContext world = ctx.worldBuilder().create();
        try {
            if (Boolean.getBoolean("xporb.atlasShaderProbe")) {
                SceneOpticsClientTest.run(ctx, world);
                return;
            }
            scenes(ctx, world);
            AdvancedBodyPickupClientTest.run(ctx, world);
            AdvancedTrailClientTest.run(ctx, world);
            SceneOpticsClientTest.run(ctx, world);
            selectWorkshops(ctx);
        } finally {
            TrailClientTest.closeWorld(ctx, world);
        }
    }

    private static void selectWorkshops(ClientGameTestContext ctx) {
        var reload = ctx.computeOnClient(client -> {
            client.getLanguageManager().setSelected("zh_cn");
            client.options.languageCode = "zh_cn";
            return client.reloadResourcePacks();
        });
        ctx.waitFor(client -> reload.isDone() && client.getOverlay() == null);
        ctx.runOnClient(client -> client.setScreen(XpOrbTrailsClient.createConfigScreen(null)));
        ctx.clickScreenButton("screen.xporbtrails.profiles");
        var workshops = ctx.computeOnClient(client -> EffectCollectionClientTest.profiles().stream()
                .filter(s -> s.name().startsWith("workshop-")).toList());
        if (workshops.size() != 9) throw new AssertionError("Missing workshop presets");
        for (var style : workshops) {
            TrailClientTest.click(ctx, style.displayName().getString());
            ctx.runOnClient(client -> {
                if (!style.settings().advanced.equals(XpOrbTrailsClient.CONFIG.advanced))
                    throw new AssertionError("Menu did not apply " + style.name());
            });
            if (style.name().equals("workshop-observatory") || style.name().equals("workshop-festival")) {
                TrailClientTest.click(ctx, "screen.xporbtrails.open_preview");
                ctx.waitTicks(32);
                ctx.takeScreenshot(style.name() + "-zh");
                ctx.runOnClient(client -> client.screen.onClose());
            }
        }
        var before = ctx.computeOnClient(client -> XpOrbTrailsClient.CONFIG.advanced.deepCopy());
        ctx.clickScreenButton("screen.xporbtrails.trail");
        TrailClientTest.click(ctx, "screen.xporbtrails.reset");
        before.remove("trail");
        ctx.runOnClient(client -> {
            if (!before.equals(XpOrbTrailsClient.CONFIG.advanced))
                throw new AssertionError("Trail page reset changed unrelated effect sections");
            client.setScreen(null);
        });
    }

    private static Map<String, JsonObject> rules() {
        var found = new LinkedHashMap<String, JsonObject>();
        for (var style : EffectCollectionClientTest.profiles()) {
            var area = style.settings().advanced;
            if (area.has("scene"))
                for (var item : area.getAsJsonArray("scene")) {
                    var r = item.getAsJsonObject();
                    found.put(r.get("type").getAsString(), r);
                }
        }
        if (!found.keySet().equals(SceneRule.TYPES))
            throw new AssertionError("Expected all scene rules, got " + found.keySet());
        return found;
    }

    private static void scenes(ClientGameTestContext ctx, TestSingleplayerContext world) {
        world.getServer().runCommand("gamemode spectator @p");
        world.getServer().runCommand("tp @p 0.5 240 0.5 0 9");
        world.getServer().runCommand("fill -7 238 -1 7 238 10 stone");
        world.getServer().runCommand("fill -7 239 -1 7 239 10 sand");
        world.getServer().runCommand("fillbiome -8 232 -8 8 248 12 minecraft:plains");
        world.getServer().runCommand("weather rain");
        world.getClientLevel().waitForChunksRender();
        ctx.waitTicks(10);
        var configs = ctx.computeOnClient(c -> rules());
        for (var entry : configs.entrySet()) {
            String name = entry.getKey();
            ctx.runOnClient(
                    client -> {
                        TrailRenderer.updateWorld(null);
                        XpOrbTrailsClient.CONFIG = new TrailConfig();
                        JsonArray arr = new JsonArray();
                        arr.add(entry.getValue().deepCopy());
                        XpOrbTrailsClient.CONFIG.advanced.add("scene", arr);
                        for (int i = 0; i < 3; i++) {
                            var orb =
                                    new ExperienceOrb(
                                            client.level,
                                            -.8 + i * 1.15,
                                            240.10,
                                            3.4 + i * .45,
                                            7 + i * 2);
                            orb.setId(BASE + i);
                            orb.setNoGravity(true);
                            client.level.addEntity(orb);
                            TrailRenderer.track(orb);
                        }
                    });
            if (name.equals("underwater"))
                world.getServer().runCommand("fill -3 240 2 3 241 6 water");
            int peak = 0;
            for (int step = 0; step < 12; step++) {
                final int phase = step;
                ctx.waitTicks(1);
                int size =
                        ctx.computeOnClient(
                                client -> {
                                    var level = client.level;
                                    for (int i = 0; i < 3; i++) {
                                        var orb = (ExperienceOrb) level.getEntity(BASE + i);
                                        double x =
                                                -.8
                                                        + i * 1.15
                                                        + (phase < 6
                                                                ? phase * .08
                                                                : (12 - phase) * .10);
                                        orb.setPos(
                                                x,
                                                240.10,
                                                3.4
                                                        + i * .45
                                                        + (phase >= 6 ? (phase - 6) * .08 : 0));
                                        SceneEffects.track(orb);
                                    }
                                    if (name.equals("rain")) level.setRainLevel(1);
                                    var first = (ExperienceOrb) level.getEntity(BASE);
                                    if (phase == 4) {
                                        if (name.equals("level")) client.player.experienceLevel++;
                                        if (name.equals("chain")
                                                || name.equals("combo")
                                                || name.equals("capture"))
                                            TrailRenderer.confirmPickup(
                                                    first, client.player.getId());
                                        if (name.equals("beat"))
                                            client.getSoundManager()
                                                    .play(
                                                            SimpleSoundInstance.forUI(
                                                                    SoundEvents
                                                                            .EXPERIENCE_ORB_PICKUP,
                                                                    1));
                                    }
                                    Vec3 player = client.player.getPosition(1).add(0, .75, 0);
                                    var frame = new EffectFrame() {
                                        @Override
                                        void line(Vec3 a, Vec3 b, double width, int color) {
                                            if (a.distanceToSqr(b) > .25 && (a.distanceToSqr(player) < 1e-8
                                                    || b.distanceToSqr(player) < 1e-8))
                                                throw new AssertionError("Scene draws a line to the player: " + name);
                                            super.line(a, b, width, color);
                                        }
                                    };
                                    SceneEffects.extract(frame, level, 1);
                                    return frame.size();
                                });
                peak = Math.max(peak, size);
            }
            if (peak == 0) throw new AssertionError("No world geometry for scene rule " + name);
            if (name.equals("stars") || name.equals("dusk"))
                ctx.takeScreenshot("atlas-scene-" + name);
            System.out.println("XPORB_SCENE " + name + " peak=" + peak);
            ctx.runOnClient(
                    c -> {
                        for (int i = 0; i < 3; i++)
                            c.level.removeEntity(BASE + i, Entity.RemovalReason.DISCARDED);
                    });
            if (name.equals("underwater"))
                world.getServer().runCommand("fill -3 240 2 3 241 6 air");
        }
        ctx.runOnClient(
                c -> {
                    TrailRenderer.updateWorld(null);
                    XpOrbTrailsClient.CONFIG = new TrailConfig();
                });
    }
}
