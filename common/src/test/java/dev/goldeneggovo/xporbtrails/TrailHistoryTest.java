package dev.goldeneggovo.xporbtrails;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrailHistoryTest {
    @Test
    void interpolationReusesOnlyUnchangedSamplesAndKeepsPublishedFramesStable() {
        var cached = new TrailRenderer.Trail();
        var fresh = new TrailRenderer.Trail();
        for (var trail : List.of(cached, fresh)) {
            for (int i = 0; i < 12; i++)
                trail.append(new Vec3(i, Math.sin(i), i / 2.0), i * 10L, 0.5);
        }
        var first = cached.samples(null);
        var oldTip = first.getLast();
        var tip = new Vec3(10.5, .25, 6.1);
        var moving = cached.samples(tip);
        assertEquals(fresh.samples(tip), moving);
        assertSame(first.getFirst(), moving.getFirst());
        assertSame(moving, cached.samples(tip));
        assertSame(oldTip, first.getLast());

        cached.flow(.7);
        fresh.flow(.7);
        var flowed = cached.samples(tip);
        assertNotEquals(moving, flowed);
        assertEquals(fresh.samples(tip), flowed);
        cached.expireBefore(65);
        fresh.expireBefore(65);
        assertEquals(fresh.samples(tip), cached.samples(tip));
        cached.append(new Vec3(20, 3, 9), 150, .5);
        fresh.append(new Vec3(20, 3, 9), 150, .5);
        assertEquals(fresh.samples(null), cached.samples(null));
        cached.expireBefore(151);
        assertTrue(cached.samples(tip).isEmpty());
    }

    @Test
    void retainsNewestPointsWhenHistoryReachesItsLimit() throws Exception {
        Object trail = newTrail();
        for (int i = 0; i <= 400; i++) append(trail, new Vec3(i * 0.25, 0, 0), i * 1_000_000L, 0.05);
        List<?> points = points(trail);
        assertEquals(128, points.size());
        assertTrue(position(points.getFirst()).x > 90.0);
        assertEquals(new Vec3(100, 0, 0), position(points.getLast()));
        assertEquals(400_000_000L, field(points.getLast(), "time"));
        long previous = Long.MIN_VALUE;
        for (Object point : points) {
            long time = (long) field(point, "time");
            assertTrue(time >= previous);
            previous = time;
        }
    }

    @Test
    void ignoresTinyInitialMovesAndUpdatesTheLiveTipWithoutGrowingHistory() throws Exception {
        Object trail = newTrail();
        append(trail, Vec3.ZERO, 1L, 0.18);
        append(trail, new Vec3(0.005, 0, 0), 2L, 0.18);
        assertEquals(1, points(trail).size());
        assertEquals(Vec3.ZERO, position(points(trail).getFirst()));
        append(trail, new Vec3(1, 0, 0), 100L, 0.18);
        int size = points(trail).size();
        append(trail, new Vec3(1.001, 0, 0), 101L, 0.18);
        assertEquals(size, points(trail).size());
        assertEquals(new Vec3(1.001, 0, 0), position(points(trail).getLast()));
    }

    @Test
    void flowPreservesTheHeadAndPointTimes() throws Exception {
        for (double strength : new double[]{0.0, 0.48, 1.0}) {
            Object trail = newTrail();
            append(trail, Vec3.ZERO, 0L, 0.18);
            append(trail, new Vec3(1, 0, 0), 100L, 0.18);
            List<?> points = points(trail);
            var times = points.stream().map(point -> field(point, "time")).toList();
            Method flow = trail.getClass().getDeclaredMethod("flow", double.class);
            flow.setAccessible(true);
            flow.invoke(trail, strength);
            assertEquals(new Vec3(1, 0, 0), position(points.getLast()));
            assertEquals(times, points.stream().map(point -> field(point, "time")).toList());
            assertTrue(position(points.getFirst()).x > 0.0);
            assertTrue(position(points.getFirst()).x < position(points.get(1)).x);
        }
    }

    private static Object newTrail() throws Exception {
        var constructor = Class.forName(TrailRenderer.class.getName() + "$Trail").getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void append(Object trail, Vec3 point, long time, double spacing) throws Exception {
        var append = trail.getClass().getDeclaredMethod("append", Vec3.class, long.class, double.class);
        append.setAccessible(true);
        append.invoke(trail, point, time, spacing);
    }

    private static List<?> points(Object trail) { return (List<?>) field(trail, "points"); }
    private static Vec3 position(Object point) { return (Vec3) field(point, "position"); }

    private static Object field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
}
