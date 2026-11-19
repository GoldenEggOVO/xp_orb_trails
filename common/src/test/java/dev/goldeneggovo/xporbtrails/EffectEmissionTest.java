package dev.goldeneggovo.xporbtrails;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.function.DoubleFunction;

import static org.junit.jupiter.api.Assertions.*;

class EffectEmissionTest {
    private static CustomEffect effect(String emitter) throws IOException {
        return CustomEffect.read(new StringReader("""
                {"version":1,"emitter":{%s},"layers":[{"frames":[{"t":0},{"t":1}]}]}
                """.formatted(emitter)), "test");
    }

    @Test
    void lastPickupWaveCanOutliveTheFlashAndOverlapAnotherCollection() throws IOException {
        var effect = effect("""
                "bursts":[{"time":0,"count":1},{"time":0.6,"count":2}],"lifetime":0.3
                """);
        var trail = new TrailRenderer.Trail();
        Vec3 a = new Vec3(1, 2, 3), b = new Vec3(4, 5, 6);
        trail.addPickup(EffectEmission.pickup(effect, 7, 0, a, 0.1, null));
        assertTrue(trail.emissions(400_000_000L, null, effect).isEmpty());
        assertEquals(1, trail.pickups.size());
        trail.addPickup(EffectEmission.pickup(effect, 8, 600_000_000L, b, 0.1, null));
        var frames = trail.emissions(750_000_000L, null, effect);
        assertEquals(2, frames.size());
        assertTrue(frames.getFirst().pickup());
        assertEquals(2, frames.getFirst().particles().size());
        assertEquals(a, frames.getFirst().particles().getFirst().position());
        assertEquals(b, frames.getLast().particles().getFirst().position());
        assertTrue(trail.emissions(1_000_000_000L, null, effect).isEmpty());
        assertEquals(1, trail.pickups.size());
        assertTrue(trail.emissions(1_600_000_000L, null, effect).isEmpty());
        assertTrue(trail.pickups.isEmpty());
    }

    @Test
    void stackedOrbRetainsBoundedEventsAndSharesOneParticleBudget() throws IOException {
        var effect = effect("""
                "bursts":[{"time":0,"count":20}],"lifetime":1
                """);
        var trail = new TrailRenderer.Trail();
        for (int i = 0; i < 12; i++) {
            trail.addPickup(EffectEmission.pickup(effect, i, 0, new Vec3(i, 0, 0), 0.2, null));
        }
        assertEquals(TrailRenderer.Trail.MAX_PICKUPS, trail.pickups.size());
        var frames = trail.emissions(150_000_000L, null, effect);
        assertEquals(64, frames.stream().mapToInt(frame -> frame.particles().size()).sum());
        assertEquals(8, frames.getFirst().particles().getFirst().position().x);
        assertEquals(4, frames.getFirst().particles().size());
        assertEquals(11, frames.getLast().particles().getFirst().position().x);
        assertEquals(20, frames.getLast().particles().size());
    }

    @Test
    void previewCatchupAndReplayGiveTheSamePositionsAcrossTurns() throws IOException {
        var effect = effect("""
                "distance":0.1,"lifetime":2,"rotation":[-30,30],"size":[0.5,1.5]
                """);
        DoubleFunction<Vec3> path = t -> new Vec3(Math.min(t, 0.4) * 5, Math.max(t - 0.4, 0) * 5, 0);
        var smooth = EffectEmission.flight(effect, 19, 0L, path.apply(0));
        var replay = EffectEmission.flight(effect, 19, 5_000_000_000L, path.apply(0));
        for (long n = 11_000_000; n < 800_000_000; n += 11_000_000) smooth.moveAlong(n, path);
        smooth.moveAlong(800_000_000L, path);
        replay.moveAlong(5_800_000_000L, path);
        var fine = smooth.sample(800_000_000L).particles();
        var caughtUp = replay.sample(5_800_000_000L).particles();
        assertEquals(40, fine.size());
        assertEquals(fine.size(), caughtUp.size());
        for (int i = 0; i < fine.size(); i++) {
            var a = fine.get(i);
            var b = caughtUp.get(i);
            assertEquals(0, a.position().distanceTo(b.position()), 1.0E-9);
            assertEquals(a.progress(), b.progress(), 1.0E-9);
            assertEquals(a.size(), b.size());
            assertEquals(a.rotation(), b.rotation());
        }
        assertEquals(0, fine.getFirst().position().y, 1.0E-9);
        assertEquals(2, fine.getLast().position().y, 1.0E-9);
        assertEquals(fine.size(), smooth.sample(1_000_000_000L).particles().size());
        assertTrue(smooth.sample(3_000_000_000L).particles().isEmpty());
    }
}
