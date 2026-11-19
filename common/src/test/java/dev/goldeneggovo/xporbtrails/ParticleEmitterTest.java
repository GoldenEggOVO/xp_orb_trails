package dev.goldeneggovo.xporbtrails;

import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParticleEmitterTest {
    private static final String LAYER = "{\"frames\":[{\"t\":0},{\"t\":1}]}";

    private static CustomEffect read(String emitter, String layer) throws IOException {
        return CustomEffect.read(new StringReader("{\"version\":1,\"emitter\":{" + emitter
                + "},\"layers\":[" + layer + "]}"), "particles");
    }

    @Test
    void readsCombinedEmissionModesAndParticlePropertiesInExistingVersionOneEffects() {
        assertDoesNotThrow(() -> read("""
                "rate":2,"distance":0.5,"bursts":[{"time":0,"count":3},{"time":0.25,"count":2}],
                "lifetime":[0.2,0.8],"speed":[1,2],"direction":[0,2,0],"spread":70,
                "size":[0.6,1.4],"rotation":[0,360],"spin":[-30,45],"gravity":4,"drag":1.5,
                "space":"world","colors":["#FF9922","#44aaff"]
                """, LAYER));
    }

    @Test
    void placesTimedBurstsAndDistanceCrossingsAlongEachTraversedSegment() throws IOException {
        ParticleEmitter emitter = read("""
                "rate":1,"distance":1.5,"lifetime":3,"bursts":[{"time":0,"count":1},{"time":0.25,"count":2}]
                """, LAYER).emitter;
        var state = emitter.start(71);
        state.advance(0, Vec3.ZERO);
        state.advance(0.5, new Vec3(1, 0, 0));
        state.advance(1, new Vec3(1, 2, 0));
        state.advance(1, new Vec3(1, 2, 0));
        assertEquals(List.of(Vec3.ZERO, new Vec3(0.5, 0, 0), new Vec3(0.5, 0, 0),
                        new Vec3(1, 0.5, 0), new Vec3(1, 2, 0), new Vec3(1, 2, 0)),
                state.sample(1, Vec3.ZERO).stream().map(ParticleEmitter.Particle::position).toList());
        assertEquals(0.375 / 3, state.sample(1, Vec3.ZERO).get(3).progress(), 1e-10);
    }

    @Test
    void physicsUsesSecondsWithGravityAndExponentialVelocityDragInWorldAndLocalSpace() throws IOException {
        String motion = "\"bursts\":[{\"time\":0,\"count\":1}],\"lifetime\":3,\"speed\":4,\"direction\":[1,0,0],\"gravity\":2";
        Vec3 start = new Vec3(10, 5, 3), end = new Vec3(20, 9, 3);
        for (boolean local : List.of(false, true)) {
            var state = read(motion + ",\"space\":\"" + (local ? "local" : "world") + "\"", LAYER).emitter.start(123);
            state.advance(0, start);
            assertEquals((local ? end : start).add(4, -1, 0), state.sample(1, end).getFirst().position());
            assertTrue(state.sample(3, end).isEmpty());
        }
        var drag = read(motion + ",\"drag\":1", LAYER).emitter.start(123);
        drag.advance(0, Vec3.ZERO);
        Vec3 position = drag.sample(1, end).getFirst().position();
        assertEquals(2.5284822353, position.x, 1e-9);
        assertEquals(-0.7357588823, position.y, 1e-9);
    }

    @Test
    void combinesSeededBirthAttributesAndSamplesIndependentOfFrameSubdivision() throws IOException {
        var emitter = read("""
                "rate":4,"distance":0.25,"bursts":[{"time":0.25,"count":12}],
                "lifetime":[1.1,3],"speed":[1,2],"spread":45,"size":[0.4,1.5],
                "rotation":[-90,90],"spin":[40,120],"colors":["#123456","#abcdef"]
                """, LAYER).emitter;
        var coarse = emitter.start(27);
        var fine = emitter.start(27);
        var differentSeed = emitter.start(28);
        for (var state : List.of(coarse, fine, differentSeed)) state.advance(0, Vec3.ZERO);
        coarse.advance(1, new Vec3(1, 0, 0));
        differentSeed.advance(1, new Vec3(1, 0, 0));
        for (int i = 1; i <= 64; i++) fine.advance(i / 64.0, new Vec3(i / 64.0, 0, 0));
        var particles = coarse.sample(1, Vec3.ZERO);
        assertEquals(20, particles.size());
        assertEquals(particles, fine.sample(1, Vec3.ZERO));
        assertNotEquals(particles, differentSeed.sample(1, Vec3.ZERO));
        assertTrue(particles.stream().map(ParticleEmitter.Particle::size).distinct().count() > 12);
        assertEquals(2, particles.stream().map(ParticleEmitter.Particle::headColor).distinct().count());
        var first = particles.getFirst();
        var later = coarse.sample(1.01, Vec3.ZERO).getFirst();
        assertEquals(first.headColor(), later.headColor());
        assertEquals(first.size(), later.size());
        assertTrue(later.rotation() > first.rotation());
        assertTrue(particles.stream().allMatch(p -> p.position().y >= 0));
    }

    @Test
    void drawsLayersAtParticleAgeWithItsSizeRotationAndPaletteApplied() throws IOException {
        var effect = read("""
                "bursts":[{"time":0,"count":1}],"lifetime":2,"size":2,"rotation":45,"spin":45,"colors":["#345678"]
                """, "{\"frames\":[{\"t\":0,\"x\":1,\"width\":2,\"height\":2},{\"t\":1,\"opacity\":0}]}");
        var state = effect.emitter.start(1);
        state.advance(0, Vec3.ZERO);
        var vertices = new ArrayList<Vec3>();
        effect.drawParticle(state.sample(1, Vec3.ZERO).getFirst(), 1, new TrailConfig(), 0, 0, (x,y,z,world,rgb) -> {
            assertEquals(0x7F345678, rgb);
            vertices.add(new Vec3(x, y, z));
        });
        assertEquals(4, vertices.size());
        assertEquals(0, vertices.stream().mapToDouble(v -> v.x).average().orElseThrow(), 1e-12);
        assertEquals(2, vertices.stream().mapToDouble(v -> v.y).average().orElseThrow(), 1e-12);
        assertEquals(2, vertices.stream().mapToDouble(v -> v.x).max().orElseThrow(), 1e-12);
        assertFalse(effect.usesTrailColors());
    }

    @Test
    void limitsBothBirthsAndMultipliedLayerGeometryAfterLongGaps() throws IOException {
        var emitter = read("\"rate\":128,\"distance\":0.01,\"lifetime\":10", LAYER).emitter;
        var state = emitter.start(1);
        state.advance(0, Vec3.ZERO);
        state.advance(1_000_000, new Vec3(1_000_000, 0, 0));
        var particles = state.sample(1_000_000, Vec3.ZERO);
        assertEquals(64, particles.size());
        assertTrue(particles.stream().allMatch(p -> p.position().x > 999_999));
        var heavy = read("\"bursts\":[{\"time\":0,\"count\":64}]", "{\"shape\":\"ring\",\"segments\":64," + LAYER.substring(1));
        var limited = heavy.emitter.start(25);
        limited.advance(0, Vec3.ZERO);
        assertEquals(6, limited.sample(0, Vec3.ZERO).size());
    }

    @Test
    void keepsExactDistanceCrossingsWhenTheSameDiagonalIsSubdividedAtHighFrameRates() throws IOException {
        var state = read("\"distance\":1,\"lifetime\":3", LAYER).emitter.start(1);
        state.advance(0, Vec3.ZERO);
        for (int i = 1; i <= 240; i++) {
            state.advance(i / 240.0, new Vec3(3.0 * i / 240, 4.0 * i / 240, 0));
        }
        var particles = state.sample(1, Vec3.ZERO);
        assertEquals(5, particles.size());
        assertEquals(3, particles.getLast().position().x, 1e-12);
        assertEquals(4, particles.getLast().position().y, 1e-12);
    }

    @Test
    void includesBirthAndExcludesTheExactDecimalLifetimeBoundary() throws IOException {
        var state = read("\"bursts\":[{\"time\":0.2,\"count\":1}],\"lifetime\":0.1", LAYER).emitter.start(1);
        state.advance(0, Vec3.ZERO);
        state.advance(0.2, Vec3.ZERO);
        assertTrue(state.sample(0.19, Vec3.ZERO).isEmpty());
        assertEquals(1, state.sample(0.2, Vec3.ZERO).size());
        assertTrue(state.sample(0.3, Vec3.ZERO).isEmpty());
    }

    @Test
    void tinyDragApproachesUndampedMotion() throws IOException {
        var state = read("\"bursts\":[{\"time\":0,\"count\":1}],\"drag\":5e-324,\"speed\":4", LAYER).emitter.start(0);
        state.advance(0, Vec3.ZERO);
        assertEquals(1, state.sample(0.25, Vec3.ZERO).getFirst().position().y, 1e-12);
    }

    @Test
    void rejectsInvalidOrUnboundedEmissionDefinitions() {
        for (String emitter : List.of("", "\"rate\":0", "\"rate\":129", "\"distance\":0", "\"rate\":2,\"space\":\"screen\"",
                "\"rate\":1,\"speed\":[2,1]", "\"rate\":1,\"speed\":[1]", "\"rate\":1,\"speed\":[1,2,3]",
                "\"rate\":1,\"direction\":[0,0,0]", "\"rate\":1,\"direction\":[0,1]",
                "\"rate\":1,\"lifetime\":0", "\"rate\":1,\"spread\":181", "\"rate\":1,\"drag\":-0.1",
                "\"bursts\":[{\"count\":1}]", "\"bursts\":[{\"time\":-1,\"count\":1}]", "\"bursts\":[{\"time\":0,\"count\":65}]",
                "\"rate\":1,\"colors\":[]", "\"rate\":1,\"colors\":[\"head\"]", "\"rate\":1,\"colorz\":[]",
                "\"rate\":1,\"rate\":2")) {
            assertThrows(IOException.class, () -> read(emitter, LAYER), emitter);
        }
    }
}
