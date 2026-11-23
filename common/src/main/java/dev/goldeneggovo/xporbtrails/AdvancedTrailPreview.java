package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonElement;
import net.minecraft.world.phys.Vec3;

import java.util.function.DoubleFunction;

/** Advances the preview in game ticks; the GUI and world use the same path history, expiry and particle code. */
final class AdvancedTrailPreview {
    private TrailRenderer.Trail trail;
    private long cycle;
    private JsonElement profile;
    private int lastTick;
    private static final long TICK = 50_000_000L;

    void append(EffectFrame out, TrailConfig cfg, DoubleFunction<Vec3> flight, long cycleIndex, double cycleSeconds,
                double collectedAt, double fade) {
        int targetTick = (int) Math.floor(cycleSeconds * 20);
        JsonElement selection = cfg.advanced.get("trail");
        if (trail == null || cycle != cycleIndex || lastTick > targetTick || profile != selection) {
            trail = new TrailRenderer.Trail();
            trail.advancedState = AdvancedTrails.state(null, cfg);
            lastTick = -1;
            cycle = cycleIndex;
            profile = selection;
        }
        long lifetime = Math.round(cfg.lifetimeSeconds * 1e9);
        long seed = 31 + cycleIndex;
        while (lastTick < targetTick) {
            int tick = ++lastTick;
            long time = tick * TICK;
            if (tick / 20.0 <= collectedAt) {
                Vec3 center = flight.apply(tick / 20.0);
                if (trail.advancedState != null) {
                    Vec3 velocity = center.subtract(flight.apply(Math.max(0, tick - 1) / 20.0)).scale(20);
                    trail.advancedState.move(center, velocity, time, seed);
                }
                trail.append(center, time, cfg.pointSpacing);
                trail.flow(cfg.smoothFlow);
            }
            trail.expireAdvanced(time, lifetime, seed, cfg);
        }
        long now = Math.round(cycleSeconds * 1e9);
        trail.expireAdvanced(now, lifetime, seed, cfg);
        Vec3 live = cycleSeconds < collectedAt ? flight.apply(cycleSeconds) : null;
        if (fade > 0) AdvancedTrails.append(out, trail.samples(live), live, now, seed, fade, cfg);
        if (trail.advancedState != null) trail.advancedState.appendParticles(out, now, cfg.effectStrength);
    }
}
