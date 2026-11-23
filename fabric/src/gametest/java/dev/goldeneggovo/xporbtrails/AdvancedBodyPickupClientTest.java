package dev.goldeneggovo.xporbtrails;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

final class AdvancedBodyPickupClientTest {
    private static final int ORB_ID = 891440;
    private static final Vec3 POSITION = new Vec3(.82, 240.85, 4);

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        TrailConfig previous = XpOrbTrailsClient.CONFIG;
        world.getServer().runCommand("gamemode spectator @p");
        world.getServer().runCommand("tp @p 0.5 240 0.5 0 13");
        world.getServer().runCommand("fill -2 239 2 2 239 6 minecraft:polished_andesite");
        world.getServer().runCommand("fill 1 240 2 2 240 6 minecraft:smooth_stone_slab[type=bottom]");
        world.getConnection().waitForChunksRender();
        context.waitTicks(5);
        try {
            context.runOnClient(client -> {
                reset(client);
                XpOrbTrailsClient.CONFIG = new TrailConfig();
            });
            int background = TrailEffectsClientTest.captureMagenta(context, "advanced-body-baseline", .2, .8);
            for (String type : List.of("planet", "firefly", "magnet", "tentacles", "droplet", "bird")) {
                context.runOnClient(client -> spawn(client, "body", type));
                context.waitTicks(2);
                context.runOnClient(client -> {
                    EffectFrame frame = snapshot(client);
                    int minimum = switch (type) {
                        case "planet", "droplet" -> 30;
                        case "firefly", "magnet", "tentacles" -> 6;
                        default -> 1;
                    };
                    require(frame.size() >= minimum, type + " body emitted " + frame.size() + " primitives");
                });
                if (type.equals("planet")) {
                    int visible = TrailEffectsClientTest.captureMagenta(context, "advanced-planet-world", .2, .8);
                    require(visible > background + 5, "Planet world pixels " + visible + ", baseline " + background);
                } else if (type.equals("bird")) {
                    TrailEffectsClientTest.screenshot(context, "advanced-bird-hover-world", image -> true);
                }
            }
            for (String type : List.of("petals", "fireworks", "ripple", "rebound", "vortex")) {
                context.runOnClient(client -> spawn(client, "pickup", type));
                context.waitTicks(type.equals("vortex") ? 5 : 2);
                context.runOnClient(client -> {
                    int before = snapshot(client).size();
                    require(type.equals("vortex") ? before >= 4 : before == 0, type + " before collection: " + before);
                    client.getConnection().handleTakeItemEntity(new ClientboundTakeItemEntityPacket(ORB_ID, client.player.getId(), 1));
                    client.level.removeEntity(ORB_ID, Entity.RemovalReason.DISCARDED);
                });
                context.waitTicks(type.equals("ripple") ? 11 : type.equals("fireworks") ? 13 : 2);
                context.runOnClient(client -> {
                    EffectFrame frame = snapshot(client);
                    int minimum = type.equals("fireworks") ? 14 : 3;
                    require(frame.size() >= minimum, type + " after collection: " + frame.size());
                    if (type.equals("ripple")) verifyHeights(frame);
                });
                if (type.equals("ripple")) {
                    int visible = TrailEffectsClientTest.captureMagenta(context, "advanced-ripple-steps-world", .2, .8);
                    require(visible > background + 2, "Surface ripple pixels " + visible + ", baseline " + background);
                }
            }
        } finally {
            context.runOnClient(client -> {
                reset(client);
                XpOrbTrailsClient.CONFIG = previous;
            });
            world.getServer().runCommand("fill -2 239 2 2 240 6 minecraft:air");
        }
    }

    private static EffectFrame snapshot(Minecraft client) {
        EffectFrame out = new EffectFrame();
        BodyPickupEffects.extract(out, client.level, 1);
        return out;
    }

    private static void reset(Minecraft client) {
        if (client.level.getEntity(ORB_ID) != null) client.level.removeEntity(ORB_ID, Entity.RemovalReason.DISCARDED);
        TrailRenderer.updateWorld(null);
        TrailRenderer.updateWorld(client.level);
    }

    private static void spawn(Minecraft client, String role, String type) {
        reset(client);
        TrailConfig c = new TrailConfig();
        var style = EffectCollectionClientTest.profiles().stream().filter(s -> {
            var section = s.settings().advanced.getAsJsonObject(role);
            return section != null && section.get("type").getAsString().equals(type);
        }).findFirst().orElseThrow(() -> new AssertionError("No installed profile for " + type));
        style.settings().applyTo(c);
        // Isolate the selected advanced program in the captured framebuffer.
        var section = c.advanced.getAsJsonObject(role).deepCopy();
        if (type.equals("planet") || type.equals("ripple")) {
            section.addProperty("color", "#FF00FF");
            if (type.equals("planet")) section.addProperty("coreColor", "#FF00FF");
            if (type.equals("ripple")) section.addProperty("size", .035);
        }
        c.advanced = new com.google.gson.JsonObject();
        c.advanced.add(role, section);
        c.flightEffect = "none";
        c.pickupFlashStyle = "none";
        c.pickupFlash = true;
        c.showVanillaOrb = false;
        c.additiveGlow = false;
        XpOrbTrailsClient.CONFIG = c;
        var orb = new ExperienceOrb(client.level, POSITION.x, POSITION.y, POSITION.z, 1);
        orb.setId(ORB_ID);
        orb.setNoGravity(true);
        orb.setDeltaMovement(Vec3.ZERO);
        orb.setOldPosAndRot();
        client.level.addEntity(orb);
        TrailRenderer.track(orb);
    }

    private static void verifyHeights(EffectFrame frame) {
        List<Double> heights = new ArrayList<>();
        Vec3 camera = new Vec3(.5, 241.6, .5);
        var consumer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                new Class[]{VertexConsumer.class}, (proxy, m, args) -> {
                    if (m.getName().equals("addVertex") && args.length == 3) heights.add(camera.y + (float) args[1]);
                    return proxy;
                });
        frame.draw(consumer, camera);
        boolean stone = false, slab = false;
        for (int i = 0; i < heights.size(); i += 4) {
            double y = (heights.get(i) + heights.get(i + 1)) * .5;
            stone |= Math.abs(y - 240.014) < .001;
            slab |= Math.abs(y - 240.514) < .001;
        }
        require(stone && slab, "Ripple must submit vertices on both full blocks and half slabs");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("XPORB_ADVANCED_BODY " + message);
    }
}
