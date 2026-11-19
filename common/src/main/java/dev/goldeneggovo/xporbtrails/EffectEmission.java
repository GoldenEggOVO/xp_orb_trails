package dev.goldeneggovo.xporbtrails;

import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.DoubleFunction;

/** One orb's flight or one collection event, timed independently of the trail fade. */
final class EffectEmission {
    record Frame(CustomEffect effect, boolean pickup, List<ParticleEmitter.Particle> particles) {}

    final CustomEffect effect;
    private final ParticleEmitter.State state;
    private final long startedAt;
    private final double duration;
    private final DoubleFunction<Vec3> pickupPath;
    private Vec3 source;
    private double advanced;

    private EffectEmission(CustomEffect effect, long seed, long startedAt, Vec3 origin,
                           double duration, DoubleFunction<Vec3> pickupPath) {
        this.effect = effect;
        this.startedAt = startedAt;
        this.duration = duration;
        this.pickupPath = pickupPath;
        source = origin;
        state = effect.emitter.start(seed);
        state.advance(0, origin);
    }

    static EffectEmission flight(CustomEffect effect, long seed, long now, Vec3 origin) {
        return new EffectEmission(effect, seed, now, origin, Double.POSITIVE_INFINITY, null);
    }

    static EffectEmission pickup(CustomEffect effect, long seed, long now, Vec3 origin,
                                 double flashSeconds, DoubleFunction<Vec3> path) {
        return new EffectEmission(effect, seed, now, path == null ? origin : path.apply(0),
                pickupDuration(effect, flashSeconds), path);
    }

    static double pickupDuration(CustomEffect effect, double flashSeconds) {
        return Math.max(flashSeconds, effect.emitter.lastBurstTime());
    }

    private double age(long now) {
        return Math.max(0, (now - startedAt) / 1.0E9);
    }

    void move(long now, Vec3 position) {
        advance(age(now), position);
    }

    private void advance(double time, Vec3 position) {
        if (time <= advanced) return;
        state.advance(time, position);
        advanced = time;
        source = position;
    }

    // Piecewise-linear tick segments preserve distance crossings even when preview frames are skipped.
    void moveAlong(long now, DoubleFunction<Vec3> path) {
        advancePath(age(now), path);
    }

    private void advancePath(double time, DoubleFunction<Vec3> path) {
        if (time <= advanced) return;
        int tick = (int) Math.floor(time * 20);
        for (int i = (int) Math.floor(advanced * 20) + 1; i <= tick; i++) {
            double endpoint = i / 20.0;
            advance(endpoint, path.apply(endpoint));
        }
        if (time > advanced) {
            Vec3 start = path.apply(tick / 20.0), end = path.apply((tick + 1) / 20.0);
            advance(time, start.lerp(end, time * 20 - tick));
        }
    }

    boolean finishedPickup(long now) {
        return age(now) >= duration + effect.emitter.maxLifetime();
    }

    Frame sample(long now) {
        double time = age(now);
        boolean pickup = Double.isFinite(duration);
        if (pickup) {
            double until = Math.min(time, duration);
            if (pickupPath == null) advance(until, source);
            else advancePath(until, pickupPath);
        }
        return new Frame(effect, pickup, state.sample(time, source));
    }
}
