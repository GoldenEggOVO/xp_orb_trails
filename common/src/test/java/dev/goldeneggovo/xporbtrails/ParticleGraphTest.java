package dev.goldeneggovo.xporbtrails;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

class ParticleGraphTest {
    private static ParticleGraph collectionGraph(String id) {
        return ParticleGraph.read(EffectCollectionFixture.profile("lab-" + id)
                .getAsJsonObject("advanced").getAsJsonArray("workshop").get(0).getAsJsonObject());
    }

    private static EventParticles.Surface floor(Vec3 a, Vec3 b, boolean fluids) {
        if (a.y >= 0 && b.y < 0)
            return new EventParticles.Surface(
                    a.lerp(b, a.y / (a.y - b.y)), new Vec3(0, 1, 0), "solid");
        return null;
    }

    @Test
    void crystalChildrenInheritBirthColourAndDeathSizeAndKeepTheirOwnImpactColour() throws Exception {
        var engine = new EventParticles.Engine(collectionGraph("events"));
        engine.emitRoot(new Vec3(0, .3, 0), 0, 7, ParticleGraphTest::floor);
        var glints = engine.live.stream().filter(p -> p.name.equals("glint")).toList();
        assertEquals(7, glints.size());
        assertTrue(glints.stream().allMatch(p -> p.color == 0xffa6bdff));
        assertTrue(engine.live.stream().noneMatch(p -> p.name.equals("snow")));
        boolean bounce = false, snow = false, death = false;
        for (int t = 1; t < 150; t++) {
            engine.advance(t, ParticleGraphTest::floor);
            bounce |= engine.live.stream().anyMatch(p -> p.name.equals("ice") && p.hits == 1);
            for (var p : engine.live) {
                if (p.name.equals("snow") && p.age == 0) {
                    assertTrue(bounce);
                    assertEquals(0xfff6f7ff, p.color);
                    assertEquals(.002, p.at.y, .0001);
                    snow = true;
                }
                if (p.name.equals("afterglow")) {
                    assertEquals(.095, p.size, 1e-8);
                    death = true;
                }
            }
        }
        assertTrue(bounce && snow && death);
        assertTrue(engine.live.isEmpty());
    }

    @Test
    void inkSticksToHitFaceAndExpires() throws Exception {
        var engine = new EventParticles.Engine(collectionGraph("ink"));
        engine.emitRoot(new Vec3(0, .4, 0), 0, 12, ParticleGraphTest::floor);
        Vec3 location = null;
        for (int t = 1; t < 30; t++) {
            engine.advance(t, ParticleGraphTest::floor);
            var print = engine.live.stream().filter(p -> p.def.decal()).findFirst();
            if (print.isPresent()) {
                var p = print.get();
                assertEquals(new Vec3(0, 1, 0), p.normal);
                assertEquals(.002, p.at.y, 1e-6);
                assertEquals(p.last, p.at);
                location = p.at;
            }
        }
        assertNotNull(location);
        for (int t = 30; t < 160; t++) engine.advance(t, ParticleGraphTest::floor);
        assertTrue(engine.live.isEmpty());
    }

    @Test
    void waterCreatesRingsWhileSolidWallsKeepTheirOwnDecal() throws Exception {
        var engine = new EventParticles.Engine(collectionGraph("ink"));
        engine.emitRoot(new Vec3(0, .2, 0), 0, 7, ParticleGraphTest::floor);
        for (int t = 1; t < 20; t++)
            engine.advance(
                    t,
                    (from, to, fluids) -> {
                        assertTrue(fluids);
                        var hit = floor(from, to, fluids);
                        return hit == null
                                ? null
                                : new EventParticles.Surface(hit.point(), hit.normal(), "water");
                    });
        assertTrue(engine.live.stream().anyMatch(p -> p.def.ring() && p.normal.y == 1));
        assertTrue(engine.live.stream().noneMatch(p -> p.name.equals("print")));
    }

    @Test
    void pickupAfterRenderGapSurvivesAndChildrenShareTheCurrentTickBudget() {
        var definition =
                JsonParser.parseString(
                                """
                                {"root":"burst","emitters":{"burst":{"count":64,"lifetime":2,"on":{"death":[{"emit":"spark"}]}},
                                 "spark":{"count":2,"lifetime":5}}}
                                """)
                        .getAsJsonObject();
        var engine = new EventParticles.Engine(ParticleGraph.read(definition));
        engine.emitRoot(Vec3.ZERO, 0, 7, ParticleGraphTest::floor);
        engine.emitRoot(Vec3.ZERO, 42, 8, ParticleGraphTest::floor);
        engine.advance(42, ParticleGraphTest::floor);
        assertEquals(64, engine.live.size());
        engine.emitRoot(Vec3.ZERO, 44, 9, ParticleGraphTest::floor);
        assertEquals(128, engine.live.size());
        assertTrue(engine.live.stream().allMatch(p -> p.born == 44 && p.name.equals("spark")));
    }

    @Test
    void recursiveEmittersObeyLimitsAndUnknownTargetsAreRejected() {
        var obj =
                JsonParser.parseString(
                                """
                                {"root":"loop","emitters":{"loop":{"count":64,"on":{"birth":[{"emit":"loop"}]}}}}
                                """)
                        .getAsJsonObject();
        var engine = new EventParticles.Engine(ParticleGraph.read(obj));
        for (int i = 0; i < 4; i++) engine.emitRoot(Vec3.ZERO, 0, i, ParticleGraphTest::floor);
        assertEquals(EventParticles.MAX_BIRTHS_PER_TICK, engine.live.size());
        assertTrue(engine.live.stream().allMatch(p -> p.depth <= EventParticles.MAX_DEPTH));
        obj.getAsJsonObject("emitters")
                .getAsJsonObject("loop")
                .getAsJsonObject("on")
                .getAsJsonArray("birth")
                .get(0)
                .getAsJsonObject()
                .addProperty("emit", "missing");
        assertThrows(IllegalArgumentException.class, () -> ParticleGraph.read(obj));
    }
}
