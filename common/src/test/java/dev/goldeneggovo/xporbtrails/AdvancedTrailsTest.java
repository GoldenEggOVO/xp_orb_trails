package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdvancedTrailsTest {
    private static final long MS = 1_000_000L;
    private static final Vec3 UP = new Vec3(0, 1, 0), RIGHT = new Vec3(1, 0, 0);

    private static TrailConfig config(String trail) {
        var cfg = new TrailConfig();
        cfg.advanced.add("trail", JsonParser.parseString(trail));
        cfg.effectStrength = 1;
        cfg.lifetimeSeconds = 1;
        cfg.width = 0.1;
        return cfg;
    }

    private static List<TrailRenderer.Sample> path(boolean turn) {
        List<TrailRenderer.Sample> samples = new ArrayList<>();
        for (int i = 0; i <= 24; i++) {
            Vec3 p = turn && i > 12 ? new Vec3(1.2, (i - 12) * 0.1, 0) : RIGHT.scale(i * 0.1);
            samples.add(new TrailRenderer.Sample(p, 0, i * 0.1, 1));
        }
        return samples;
    }

    private record Mesh(List<Vec3> vertices, List<Integer> colors) {
        Vec3 endCenter(int quad) {
            return vertices.get(quad * 4 + 1).lerp(vertices.get(quad * 4 + 2), 0.5);
        }
    }

    private static Mesh mesh(EffectFrame frame) {
        var vertices = new ArrayList<Vec3>();
        var colors = new ArrayList<Integer>();
        Vec3 camera = new Vec3(0, 0, 5);
        var out = (VertexConsumer) Proxy.newProxyInstance(AdvancedTrailsTest.class.getClassLoader(),
                new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("addVertex"))
                        vertices.add(camera.add((float) args[0], (float) args[1], (float) args[2]));
                    if (method.getName().equals("setColor")) colors.add((int) args[0]);
                    return proxy;
                });
        frame.draw(out, camera);
        return new Mesh(vertices, colors);
    }

    private static EffectFrame trail(TrailConfig cfg, boolean turn, long now) {
        var frame = new EffectFrame();
        assertTrue(AdvancedTrails.append(frame, path(turn), null, now, 42L, 1, cfg));
        return frame;
    }

    @Test
    void independentKnotsControlColorWidthAndOpacityAtTheirOwnPositions() {
        var cfg = config("""
                {"geometry":"ribbon","widthCurve":[[0,0],[0.125,2],[0.4,0.1],[0.75,3],[1,0]],
                 "opacityCurve":[[0,0.25],[0.75,1]],"colorCurve":[[0,"#000000"],[0.5,"#00FF00"],[1,"#FFFFFF"]]}
                """);
        Mesh mesh = mesh(trail(cfg, false, 0));
        assertEquals(0.2, mesh.vertices.get(9).distanceTo(mesh.vertices.get(10)), 1e-6);
        assertEquals(0.3, mesh.vertices.get(69).distanceTo(mesh.vertices.get(70)), 1e-6);
        assertEquals(0, mesh.vertices.get(93).distanceTo(mesh.vertices.get(94)), 1e-6);
        assertEquals(0x00FF00, mesh.colors.get(47) & 0xffffff);
        assertTrue((mesh.colors.get(0) >>> 24) < (mesh.colors.get(47) >>> 24));
        assertEquals(255, mesh.colors.get(75) >>> 24);
    }

    @Test
    void opposedStrandsStayOnOppositeSidesOfTheTransportedBendAndRollInWorldSpace() {
        var cfg = config("""
                {"geometry":"ribbon","period":0.8,"roll":360,"widthCurve":[[0,1]],"strands":[
                  {"phase":0,"radius":0.12,"width":1,"color":"#FFCC00"},
                  {"phase":180,"radius":0.12,"width":0.5,"color":"#22AAFF"}]}
                """);
        Mesh mesh = mesh(trail(cfg, true, 0));
        List<TrailRenderer.Sample> path = path(true);
        for (int i = 0; i < 24; i++) {
            Vec3 a = mesh.endCenter(i * 2), b = mesh.endCenter(i * 2 + 1);
            assertEquals(0.24, a.distanceTo(b), 1e-6);
            assertTrue(a.lerp(b, 0.5).distanceTo(path.get(i + 1).position()) < 1e-6);
        }
        assertEquals(0.10, mesh.vertices.get(1).distanceTo(mesh.vertices.get(2)), 1e-6);
        assertEquals(0.05, mesh.vertices.get(5).distanceTo(mesh.vertices.get(6)), 1e-6);
        Vec3 side1 = mesh.vertices.get(1).subtract(mesh.vertices.get(2)).normalize();
        Vec3 side3 = mesh.vertices.get(17).subtract(mesh.vertices.get(18)).normalize();
        assertEquals(0, side1.dot(side3), 1e-5);
        assertEquals(0xffcc00, mesh.colors.get(0) & 0xffffff);
        assertEquals(0x22aaff, mesh.colors.get(4) & 0xffffff);
        Vec3 t = RIGHT, n = UP;
        for (Vec3 next : List.of(new Vec3(0.01, 1, 0).normalize(), UP, UP.scale(-1), RIGHT.scale(-1))) {
            n = AdvancedTrails.transport(n, t, next);
            assertEquals(1, n.length(), 1e-6);
            assertEquals(0, n.dot(next), 1e-6);
            t = next;
        }
    }

    @Test
    void branchesAppearAtBendsAndKeepTheirShapeDuringOneFlash() {
        var cfg = config("""
                {"geometry":"arc","branches":{"minTurn":25,"jitter":0.05,"count":2,"flashSeconds":0.20}}
                """);
        assertEquals(24, trail(cfg, false, 10 * MS).size());
        var corner = trail(cfg, true, 10 * MS);
        assertTrue(corner.size() > 24 && corner.size() <= 32);
        assertEquals(mesh(corner).vertices, mesh(trail(cfg, true, 20 * MS)).vertices);
        assertEquals(24, trail(cfg, true, 170 * MS).size());
        assertNotEquals(mesh(corner).vertices, mesh(trail(cfg, true, 240 * MS)).vertices);
    }

    @Test
    void emittedSparksKeepLaunchVelocityWhenTheOrbTurnsAndDisappearOnSchedule() {
        var cfg = config("""
                {"geometry":"particles","particles":{"birth":"distance","spacing":1,"scatter":0,
                    "inheritVelocity":0.5,"gravity":2,"drag":0,"lifetime":0.5}}
                """);
        var state = AdvancedTrails.state(null, cfg);
        state.move(Vec3.ZERO, RIGHT.scale(4), 0, 7);
        state.move(RIGHT, RIGHT.scale(4), 100 * MS, 7);
        var before = new EffectFrame();
        state.appendParticles(before, 200 * MS, 1);
        assertEquals(1, before.size());
        state.move(new Vec3(1, 1, 0), UP.scale(4), 200 * MS, 7);
        var after = new EffectFrame();
        state.appendParticles(after, 200 * MS, 1);
        assertEquals(2, after.size());
        assertEquals(mesh(before).vertices, mesh(after).vertices.subList(0, 4));
        Mesh m = mesh(after);
        Vec3 firstTip = m.vertices.get(2).lerp(m.vertices.get(3), 0.5);
        assertEquals(1.2, firstTip.x, 1e-6);
        assertEquals(-0.01, firstTip.y, 1e-6);
        assertFalse(state.alive(700 * MS));
    }

    @Test
    void turbulentWindIsCoherentAcrossNearbyBirthplacesAndOverTime() {
        var cfg = config("""
                {"geometry":"particles","particles":{"birth":"distance","gravity":-3,"drag":1.2,"turbulence":2,"frequency":2}}
                """);
        var wake = new AdvancedTrailStyle(cfg.advanced.getAsJsonObject("trail")).particles;
        var a = new AdvancedTrails.Particle(Vec3.ZERO, Vec3.ZERO, 0, 0, 1);
        var b = new AdvancedTrails.Particle(new Vec3(0.0001, 0.0001, 0), Vec3.ZERO, 1000, 0, 1);
        Vec3 pushed = AdvancedTrails.position(a, 0.6, wake);
        assertTrue(pushed.y > 0 && pushed.x * pushed.x + pushed.z * pushed.z > 0.05);
        assertTrue(pushed.distanceTo(AdvancedTrails.position(b, 0.6, wake)) < 0.001);
        assertTrue(pushed.distanceTo(AdvancedTrails.position(a, 0.6001, wake)) < 0.001);
    }

    @Test
    void decayingTrailBreaksBeforeItsNodeEmitsCappedTangentColoredDebris() {
        var cfg = config("""
                {"geometry":"crumble","breakAt":0.4,"breakSpacing":0.4,"colorCurve":[[0,"#23AB42"],[1,"#FFFFFF"]],
                 "particles":{"birth":"expiry","burst":2,"tangentSpeed":1,"scatter":0,"lifetime":0.5,"drag":0}}
                """);
        assertTrue(trail(cfg, false, 900 * MS).size() < trail(cfg, false, 200 * MS).size());
        var state = AdvancedTrails.state(null, cfg);
        assertFalse(state.alive(500 * MS));
        for (int i = 0; i < 80; i++) state.expire(Vec3.ZERO, RIGHT, 1000 * MS, 1000 * MS, i, cfg);
        var frame = new EffectFrame();
        state.appendParticles(frame, 1100 * MS, 1);
        assertEquals(2 * AdvancedTrails.State.MAX_EVENTS, frame.size());
        Mesh original = mesh(frame);
        assertEquals(0x23ab42, original.colors.getFirst() & 0xffffff);
        assertEquals(0.1, original.vertices.stream().mapToDouble(v -> v.x).average().orElseThrow(), 1e-6);
        for (int i = 1; i <= 5; i++)
            for (int j = 0; j < 12; j++) state.expire(UP, RIGHT, (1000 + i) * MS, (1000 + i) * MS, j, cfg);
        var bounded = new EffectFrame();
        state.appendParticles(bounded, 1100 * MS, 1);
        assertEquals(AdvancedTrails.State.CAPACITY, bounded.size());
        assertFalse(state.alive(1505 * MS));
    }

    @Test
    void malformedProfilesFailValidationAndFallBackWithoutReplacingNativeTrails() {
        for (String bad : List.of(
                "{\"geometry\":\"ribbon\",\"widthCurve\":[[0.2,1],[0.2,2]]}",
                "{\"geometry\":\"ribbon\",\"opacityCurve\":[[0,2]]}",
                "{\"geometry\":\"ribbon\",\"colorCurve\":[[0,\"#abc\"]]}",
                "{\"geometry\":\"ribbon\",\"strands\":[]}",
                "{\"geometry\":\"arc\",\"branches\":{\"count\":7}}",
                "{\"geometry\":\"particles\",\"particles\":{\"birth\":\"distance\",\"inheritVelocity\":42}}")) {
            var cfg = config(bad);
            assertThrows(IllegalArgumentException.class, () -> AdvancedTrails.validate(cfg.advanced.getAsJsonObject("trail")));
            assertFalse(AdvancedTrails.append(new EffectFrame(), List.of(), Vec3.ZERO, 0, 0, 1, cfg));
            assertNull(AdvancedTrails.state(null, cfg));
        }
    }

    @Test
    void previewReplaysTickBirthsAndKeepsFragmentsVisibleAfterTheOrbDisappears() {
        var cfg = config("""
                {"geometry":"crumble","particles":{"birth":"expiry","burst":1,"lifetime":0.8,"scatter":0}}
                """);
        cfg.lifetimeSeconds = 0.5;
        var preview = new AdvancedTrailPreview();
        var one = new EffectFrame();
        preview.append(one, cfg, t -> RIGHT.scale(t * 3), 3, 1.7, 1, 0);
        assertTrue(one.size() > 0);
        var replay = new EffectFrame();
        preview.append(new EffectFrame(), cfg, t -> RIGHT.scale(t * 3), 4, 0.4, 1, 1);
        preview.append(replay, cfg, t -> RIGHT.scale(t * 3), 4, 1.7, 1, 0);
        assertEquals(one.size(), replay.size());
        var clear = new EffectFrame();
        preview.append(clear, cfg, t -> RIGHT.scale(t * 3), 4, 2.4, 1, 0);
        assertEquals(0, clear.size());
    }
}
