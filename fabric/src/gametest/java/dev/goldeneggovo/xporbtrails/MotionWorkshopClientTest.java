package dev.goldeneggovo.xporbtrails;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.phys.Vec3;

import java.util.List;

final class MotionWorkshopClientTest {
    private static final int ORB = 101074;

    static void run(ClientGameTestContext ctx, TestSingleplayerContext world) {
        var saved = ctx.computeOnClient(c -> XpOrbTrailsClient.CONFIG);
        var view = ctx.computeOnClient(c -> c.options.getCameraType());
        var arm = ctx.computeOnClient(c -> c.player.getMainArm());
        try {
            world.getServer().runCommand("gamemode spectator @p");
            world.getServer().runCommand("tp @p 0 271 0 0 0");
            world.getServer().runCommand("fill -8 269 -8 8 269 8 stone");
            world.getServer().runCommand("time set midnight");
            world.getServer().runCommand("weather clear");
            world.getClientLevel().waitForChunksRender();
            ctx.waitTicks(3);
            for (String type : List.of("rig", "brush", "morph", "hoops")) {
                apply(ctx, type);
                int pieces = ctx.computeOnClient(c -> {
                    Vec3 center = c.gameRenderer.getMainCamera().position().add(0, 0, 4.1);
                    var orb = new ExperienceOrb(c.level, center.x - 1, center.y, center.z, 3);
                    orb.setId(ORB);
                    orb.setNoGravity(true);
                    c.level.addEntity(orb);
                    for (int i = 0; i <= 16; i++) {
                        Vec3 p = center.add(-1.25 + i * .15, .25 * Math.sin(i * .45), i > 8 ? (i - 8) * .08 : 0);
                        orb.setPos(p.x, p.y, p.z);
                        orb.setOldPosAndRot();
                        TrailRenderer.track(orb);
                    }
                    var frame = new EffectFrame();
                    MotionWorkshop.extract(frame, c.level, 1);
                    return frame.size();
                });
                int min = switch (type) {case "rig" -> 30; case "morph" -> 160; case "hoops" -> 48; default -> 12;};
                if (pieces < min) throw new AssertionError(type + " incomplete geometry: " + pieces);
                ctx.waitTicks(1);
                int pixels = TrailEffectsClientTest.screenshot(ctx, "lab-" + type + "-world", MotionWorkshopClientTest::visiblePixels);
                if (pixels < 12) throw new AssertionError(type + " invisible in world framebuffer: " + pixels);
                if (type.equals("hoops")) {
                    ctx.runOnClient(c -> {
                        var o = Workshop.options("hoops");
                        o.addProperty("sprite", "sprite:trail/comet-pennant");
                        if (XpOrbTrailsClient.sprite(AssetRole.TRAIL, o.get("sprite").getAsString()) == null)
                            throw new AssertionError("Missing trail artwork");
                        var frame = new EffectFrame();
                        MotionWorkshop.extract(frame, c.level, 1);
                        if (frame.size() < 3 || frame.size() >= 16)
                            throw new AssertionError("Artwork did not replace each 16-edge hoop: " + frame.size());
                    });
                    ctx.waitTicks(1);
                    ctx.takeScreenshot("lab-hoops-artwork");
                    ctx.runOnClient(c -> Workshop.options("hoops").addProperty("sprite", "none"));
                    ctx.runOnClient(c -> {
                        var orb = (ExperienceOrb)c.level.getEntity(ORB);
                        TrailRenderer.confirmPickup(orb, c.player.getId());
                        c.level.removeEntity(ORB, Entity.RemovalReason.DISCARDED);
                    });
                    ctx.waitTicks(10);
                    int replay = ctx.computeOnClient(c -> {
                        var frame = new EffectFrame();
                        MotionWorkshop.extract(frame, c.level, 1);
                        return frame.size();
                    });
                    if (replay < 19) throw new AssertionError("Missing detached ring replay: " + replay);
                    ctx.takeScreenshot("lab-hoops-reverse");
                }
                System.out.println("XPORB_LAB " + type + " geometry=" + pieces);
            }
            world.getServer().runCommand("gamemode creative @p");
            world.getServer().runCommand("item replace entity @p weapon.mainhand with minecraft:diamond_sword");
            world.getServer().runCommand("item replace entity @p weapon.offhand with minecraft:lantern");
            ctx.waitTicks(5);
            for (var camera : List.of(CameraType.FIRST_PERSON, CameraType.THIRD_PERSON_BACK)) {
                for (var hand : InteractionHand.values()) {
                    apply(ctx, "hand");
                    ctx.runOnClient(c -> {
                        c.options.setCameraType(camera);
                        c.player.setMainArm(camera.isFirstPerson() ? HumanoidArm.RIGHT : HumanoidArm.LEFT);
                        var item = XpOrbTrailsClient.CONFIG.advanced.getAsJsonArray("workshop").get(0).getAsJsonObject();
                        item.addProperty("targetHand", hand == InteractionHand.MAIN_HAND ? "main" : "off");
                        item.addProperty("durationTicks", 80);
                    });
                    world.getServer().runCommand("tp @p 0 270 0 0 0");
                    // The equipped item rises and the camera eye height settles after spectator/creative or camera changes.
                    // Sample a stable rendered pose before the translation trial.
                    ctx.waitTicks(15);
                    pickup(ctx);
                    ctx.waitFor(c -> MotionWorkshopHand.captured(c.player.getId(), hand));
                    Vec3 before = settledEndpoint(ctx, hand);
                    world.getServer().runCommand("tp @p 2 270 0 0 0");
                    ctx.waitTicks(6);
                    Vec3 moved = ctx.computeOnClient(c -> MotionWorkshopHand.target(c.player, hand, 1));
                    float bodyBefore = ctx.computeOnClient(c -> c.player.yBodyRot);
                    // A standing player can turn their head while both hands remain in place.
                    // A near half-turn exceeds the vanilla head/body yaw limit and visibly turns the held model.
                    int angle = camera.isFirstPerson() ? 85 : 170;
                    world.getServer().runCommand("tp @p 2 270 0 " + angle + " 0");
                    ctx.waitTicks(10);
                    float bodyAfter = ctx.computeOnClient(c -> c.player.yBodyRot);
                    if (!camera.isFirstPerson() && Math.abs(net.minecraft.util.Mth.wrapDegrees(bodyAfter - bodyBefore)) < 35)
                        throw new AssertionError("Player torso did not rotate: " + bodyBefore + " -> " + bodyAfter);
                    Vec3 turned = ctx.computeOnClient(c -> MotionWorkshopHand.target(c.player, hand, 1));
                    double translationError = moved.subtract(before).distanceTo(new Vec3(2, 0, 0));
                    double rotationTravel = moved.distanceTo(turned);
                    if (translationError > .48 || rotationTravel < .08)
                        throw new AssertionError("Item endpoint " + camera + "/" + hand + " translationError=" + translationError
                                + " rotationTravel=" + rotationTravel + " bodyYaw=" + bodyBefore + " -> " + bodyAfter + " coordinates=" + before + " -> " + moved + " -> " + turned);
                    System.out.println("XPORB_LAB hand " + camera + " " + hand + " translationError=" + translationError
                            + " rotationTravel=" + rotationTravel);
                }
            }
            ctx.takeScreenshot("lab-hand-lantern");
        } finally {
            ctx.runOnClient(c -> {
                if (c.level.getEntity(ORB) != null) c.level.removeEntity(ORB, Entity.RemovalReason.DISCARDED);
                c.options.setCameraType(view);
                c.player.setMainArm(arm);
                TrailRenderer.updateWorld(null);
                XpOrbTrailsClient.CONFIG = saved;
            });
        }
    }

    private static Vec3 settledEndpoint(ClientGameTestContext ctx, InteractionHand hand) {
        Vec3 previous = ctx.computeOnClient(c -> MotionWorkshopHand.target(c.player, hand, 1));
        int stable = 0;
        for (int i = 0; i < 20; i++) {
            ctx.waitTicks(1);
            Vec3 next = ctx.computeOnClient(c -> MotionWorkshopHand.target(c.player, hand, 1));
            stable = next.distanceTo(previous) < .012 ? stable + 1 : 0;
            if (stable >= 3) return next;
            previous = next;
        }
        throw new AssertionError("Held item and camera failed to settle for " + hand + " at " + previous);
    }

    private static int visiblePixels(NativeImage image) {
        int total = 0;
        for (int y = image.getHeight() / 4; y < image.getHeight() * 2 / 3; y++) {
            for (int x = image.getWidth() / 8; x < image.getWidth() * 7 / 8; x++) {
                int rgb = image.getPixel(x, y);
                int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
                int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
                if (max > 95 && max - min > 30) total++;
            }
        }
        return total;
    }

    private static void apply(ClientGameTestContext ctx, String type) {
        ctx.runOnClient(c -> {
            if (c.level.getEntity(ORB) != null) c.level.removeEntity(ORB, Entity.RemovalReason.DISCARDED);
            TrailRenderer.updateWorld(null);
            var profile = EffectCollectionClientTest.profile("lab-" + type);
            var cfg = new TrailConfig();
            profile.settings().applyTo(cfg);
            XpOrbTrailsClient.CONFIG = cfg;
        });
    }

    private static void pickup(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> {
            Vec3 p = c.player.getEyePosition().add(c.player.getViewVector(1).scale(2.5)).add(.35, .15, 0);
            var orb = new ExperienceOrb(c.level, p.x, p.y, p.z, 3);
            orb.setId(ORB);
            orb.setNoGravity(true);
            c.level.addEntity(orb);
            TrailRenderer.track(orb);
            TrailRenderer.confirmPickup(orb, c.player.getId());
            c.level.removeEntity(ORB, Entity.RemovalReason.DISCARDED);
        });
    }

    private MotionWorkshopClientTest() {}
}
