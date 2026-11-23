package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static dev.goldeneggovo.xporbtrails.OrbEffectOptions.*;
import static dev.goldeneggovo.xporbtrails.OrbPickupParticles.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class BodyPickupEffectsTest {
    private static OrbEffectOptions options(String json) {
        return new OrbEffectOptions(JsonParser.parseString(json).getAsJsonObject());
    }

    private static List<Vec3> vertices(EffectFrame frame, Vec3 camera) {
        List<Vec3> result = new ArrayList<>();
        VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                new Class[]{VertexConsumer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("addVertex") && args.length == 3)
                        result.add(camera.add((float) args[0], (float) args[1], (float) args[2]));
                    return proxy;
                });
        frame.draw(consumer, camera);
        return result;
    }

    @Test void ringUsesOneTiltedWorldPlaneWhenTheCameraMoves() {
        var planet = new OrbBodyMotion(options("""
                {"type":"planet","segments":12,"radius":0.4,"tiltDegrees":60,"yawDegrees":0,"precessionDegrees":0}
                """), Vec3.ZERO, 8);
        EffectFrame frame = new EffectFrame();
        planet.draw(frame, Vec3.ZERO, .5);
        var front = vertices(frame, new Vec3(0, 0, 2));
        var side = vertices(frame, new Vec3(3, 0, 0));
        Vec3 normal = new Vec3(Math.sin(Math.PI / 3), .5, 0);
        for (int i = 0; i < 12 * 4; i++) {
            assertEquals(0, front.get(i).dot(normal), 2e-7);
            assertEquals(0, front.get(i).distanceTo(side.get(i)), 3e-7);
        }
        assertTrue(front.stream().limit(48).anyMatch(v -> Math.abs(v.y) > .3));
    }

    @Test void shellBirthLaunchesOutwardThenAttractionReturnsTheFirefly() {
        var swarm = new OrbBodyMotion(options("""
                {"type":"firefly","count":1,"radius":0.25,"lifetimeTicks":65,"attraction":0.045}
                """), Vec3.ZERO, 55);
        assertEquals(.25, swarm.points[0].length(), 1e-8);
        for (int i = 0; i < 7; i++) swarm.step(Vec3.ZERO, Vec3.ZERO);
        assertTrue(swarm.points[0].length() > .30);
        for (int i = 7; i < 33; i++) swarm.step(Vec3.ZERO, Vec3.ZERO);
        assertTrue(swarm.points[0].length() < .16);
    }

    @Test void magneticFilingsFollowTheLocalFieldAroundBothPoles() {
        var field = new OrbBodyMotion(options("""
                {"type":"magnet","count":1,"radius":0.32,"fieldSpeed":0.018,"lifetimeTicks":130}
                """), Vec3.ZERO, 9);
        double largestArc = 0, minX = field.points[0].x;
        for (int i = 0; i < 110; i++) {
            Vec3 from = field.points[0];
            field.step(Vec3.ZERO, Vec3.ZERO);
            Vec3 offset = field.points[0].subtract(from);
            if (offset.length() < .020) assertTrue(offset.dot(OrbBodyMotion.dipole(from, .32 * .68)) > 0);
            largestArc = Math.max(largestArc, Math.hypot(from.y, from.z));
            minX = Math.min(minX, from.x);
        }
        assertTrue(largestArc > .15);
        assertTrue(minX < -.15);
    }

    @Test void constrainedTentacleNodesLagWhenOrbTurnsAndKeepSegmentLengths() {
        var chain = new OrbBodyMotion(options("""
                {"type":"tentacles","count":1,"nodes":7,"spacing":0.08,"radius":0.03}
                """), Vec3.ZERO, 3);
        for (int i = 1; i <= 5; i++) chain.step(new Vec3(i * .13, 0, 0), RIGHT);
        assertTrue(chain.points[0].x - chain.points[6].x > .25);
        for (int i = 1; i <= 3; i++) chain.step(new Vec3(.65, 0, i * .13), new Vec3(0, 0, 1));
        assertTrue(chain.points[0].z - chain.points[6].z > .17);
        for (int i = 1; i < chain.points.length; i++)
            assertEquals(.08, chain.points[i].distanceTo(chain.points[i - 1]), 1e-8);
    }

    @Test void speedStretchesAlongTravelAndTheSpringOvershootsAfterAnAbruptStop() {
        var drop = new OrbBodyMotion(options("""
                {"type":"droplet","size":0.13,"spring":0.28,"damping":0.78}
                """), Vec3.ZERO, 2);
        for (int i = 0; i < 28; i++) drop.step(Vec3.ZERO, new Vec3(0, 0, .36));
        EffectFrame geometry = new EffectFrame();
        drop.draw(geometry, Vec3.ZERO, 1);
        var points = vertices(geometry, RIGHT);
        double z = points.stream().mapToDouble(v -> v.z).max().orElseThrow();
        double x = points.stream().mapToDouble(v -> v.x).max().orElseThrow();
        assertTrue(z > 2 * x);
        double minimum = 1;
        for (int i = 0; i < 55; i++) {
            drop.step(Vec3.ZERO, Vec3.ZERO);
            minimum = Math.min(minimum, drop.strain);
        }
        assertTrue(minimum < .9);
        assertEquals(1, drop.strain, .008);
    }

    @Test void glideAndHoverHaveSeparateSwitchingThresholds() {
        var bird = new OrbBodyMotion(options("""
                {"type":"bird","glideSpeed":0.20,"hoverSpeed":0.10}
                """), Vec3.ZERO, 9);
        for (double speed : new double[]{.12,.16,.19}) bird.step(Vec3.ZERO, RIGHT.scale(speed));
        assertFalse(bird.gliding);
        bird.step(Vec3.ZERO, RIGHT.scale(.21));
        bird.step(Vec3.ZERO, RIGHT.scale(.13));
        assertTrue(bird.gliding);
        bird.step(Vec3.ZERO, RIGHT.scale(.09));
        assertFalse(bird.gliding);
    }

    @Test void petalsHaveIndependentAnimationPhasesAngularVelocitiesAndPaths() {
        var petals = new OrbPickupParticles(options("""
                {"type":"petals","count":10,"gravity":0.009}
                """), 90);
        petals.start(Vec3.ZERO, null);
        for (int i = 0; i < 6; i++) petals.step(null);
        assertEquals(10, petals.particles.stream().map(p -> p.position).distinct().count());
        assertEquals(10, petals.particles.stream().map(p -> p.phase).distinct().count());
        assertEquals(10, petals.particles.stream().map(p -> p.spin).distinct().count());
        assertTrue(petals.particles.stream().allMatch(p -> p.kind == PETAL));
    }

    @Test void childFireworksInheritEachParentsActualEndpoint() {
        var firework = new OrbPickupParticles(options("""
                {"type":"fireworks","count":3,"lifetimeTicks":7,"children":4}
                """), 331);
        firework.start(Vec3.ZERO, null);
        var parents = List.copyOf(firework.particles);
        for (int t = 0; t < 8; t++) {
            firework.step(null);
            for (var child : firework.particles) if (child.kind == SPARK && child.age == 0) {
                assertTrue(parents.stream().anyMatch(p -> p.age == p.life && p.position.equals(child.position)));
                assertTrue(child.position.length() > .12);
            }
        }
        assertEquals(12, firework.particles.size());
        assertTrue(firework.particles.stream().allMatch(p -> p.kind == SPARK));
        assertEquals(3, parents.stream().map(p -> p.position).distinct().count());
    }

    @Test void ripplesRaycastEachVertexAndNeverBridgeAcrossRaisedSteps() {
        var ripple = new OrbPickupParticles(options("""
                {"type":"ripple","fallTicks":0,"rings":1,"segments":24,"radius":0.8,"lifetimeTicks":10}
                """), 3);
        OrbPickupParticles.Terrain steps = (a,b) -> {
            double height = a.x > .05 ? .5 : 0;
            return a.y >= height && b.y <= height
                    ? new OrbPickupParticles.Surface(new Vec3(a.x, height, a.z), UP) : null;
        };
        ripple.start(UP, steps);
        for (int i = 0; i < 7; i++) ripple.step(steps);
        EffectFrame frame = new EffectFrame();
        ripple.draw(frame, .5);
        assertEquals(22, frame.size());
        var points = vertices(frame, new Vec3(0, 2, 1));
        List<Double> heights = new ArrayList<>();
        for (int i = 0; i < points.size(); i += 4) {
            double a = (points.get(i).y + points.get(i + 1).y) / 2;
            double b = (points.get(i + 2).y + points.get(i + 3).y) / 2;
            assertEquals(a, b, 3e-7);
            heights.add(a);
        }
        assertTrue(heights.stream().anyMatch(y -> Math.abs(y - .014) < 1e-5));
        assertTrue(heights.stream().anyMatch(y -> Math.abs(y - .514) < 1e-5));
        var empty = new OrbPickupParticles(options("{\"type\":\"ripple\"}"), 3);
        empty.start(UP, (a,b) -> null);
        assertTrue(empty.finished());
    }

    @Test void iceReflectsOnFirstContactAndSpawnsPowderWhereSecondSweepHits() {
        var ice = new OrbPickupParticles(options("""
                {"type":"rebound","count":1,"gravity":0.025,"restitution":0.65,"contacts":2,"children":5,"lifetimeTicks":90}
                """), 35);
        OrbPickupParticles.Terrain floor = (a,b) -> a.y >= 0 && b.y <= 0
                ? new OrbPickupParticles.Surface(a.lerp(b, a.y / (a.y - b.y)), UP) : null;
        ice.start(new Vec3(0, .38, 0), floor);
        var shard = ice.particles.getFirst();
        while (shard.contacts == 0 && shard.age < 60) ice.step(floor);
        assertEquals(1, shard.contacts);
        assertTrue(shard.velocity.y > 0);
        while (shard.contacts == 1 && shard.age < 65) ice.step(floor);
        assertEquals(2, shard.contacts);
        assertEquals(5, ice.particles.size());
        assertTrue(ice.particles.stream().allMatch(p -> p.kind == POWDER && p.position.equals(shard.position)));
    }

    @Test void vortexKeepsTheLiveObjectsAndCapturesThemFromTheirCurrentPositions() {
        var vortex = new OrbPickupParticles(options("""
                {"type":"vortex","count":8,"perTick":4,"attraction":0.15,"tangent":0.13,"lifetimeTicks":40}
                """), 22);
        for (int i = 0; i < 4; i++) {
            vortex.step(null);
            vortex.emitDrift(Vec3.ZERO);
        }
        List<OrbPickupParticles.Particle> free = List.copyOf(vortex.particles);
        var before = free.stream().map(p -> p.position).toList();
        vortex.start(Vec3.ZERO, null);
        assertEquals(free, vortex.particles);
        assertEquals(before, free.stream().map(p -> p.position).toList());
        for (int i = 0; i < 10; i++) vortex.step(null);
        for (int i = 0; i < free.size(); i++) {
            assertEquals(CAPTURED, free.get(i).kind);
            assertTrue(free.get(i).position.length() < before.get(i).length() * .75);
            assertTrue(free.get(i).position.cross(before.get(i)).length() > .00001);
        }
        for (int i = 0; i < 40; i++) vortex.step(null);
        assertTrue(vortex.finished());
    }

    @Test void menuReplayRebuildsTheSameCapturedParticlesAfterSkippingFramesOrRewinding() {
        TrailConfig cfg = new TrailConfig();
        cfg.advanced = JsonParser.parseString("""
                {"pickup":{"type":"vortex","count":8,"perTick":2,"radius":0.5,"lifetimeTicks":40}}
                """).getAsJsonObject();
        var smooth = new BodyPickupPreview();
        var skipped = new BodyPickupPreview();
        java.util.function.DoubleFunction<Vec3> path = seconds -> new Vec3(seconds * .7, Math.sin(seconds), .2 * seconds);
        for (int i = 0; i < 82; i++) smooth.append(new EffectFrame(), cfg, path, 1, i / 20.0, 4.0);
        EffectFrame smoothFrame = new EffectFrame(), skippedFrame = new EffectFrame(), rewind = new EffectFrame();
        smooth.append(smoothFrame, cfg, path, 1, 4.09, 4);
        skipped.append(skippedFrame, cfg, path, 1, 4.09, 4);
        assertEquals(8, smoothFrame.size());
        assertEquals(vertices(smoothFrame, RIGHT), vertices(skippedFrame, RIGHT));
        skipped.append(new EffectFrame(), cfg, path, 1, 5, 4);
        skipped.append(rewind, cfg, path, 1, 4.09, 4);
        assertEquals(vertices(skippedFrame, RIGHT), vertices(rewind, RIGHT));
    }

    @Test void menuStepPreviewContainsTheReprojectedWaveAndWaitsForItsLastRing() {
        TrailConfig cfg = new TrailConfig();
        cfg.advanced = JsonParser.parseString("""
                {"pickup":{"type":"ripple","fallTicks":2,"rings":3,"delayTicks":3,"lifetimeTicks":12}}
                """).getAsJsonObject();
        assertEquals(1.05, BodyPickupPreview.afterglowSeconds(cfg), 1e-8);
        var preview = new BodyPickupPreview();
        EffectFrame frame = new EffectFrame();
        preview.append(frame, cfg, s -> Vec3.ZERO, 0, 4.4, 4.0);
        assertTrue(frame.size() > 15);
        cfg.pickupFlash = false;
        assertEquals(0, BodyPickupPreview.afterglowSeconds(cfg));
    }
}
