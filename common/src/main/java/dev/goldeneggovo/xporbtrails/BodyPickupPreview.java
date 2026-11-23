package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonElement;
import net.minecraft.world.phys.Vec3;

import java.util.function.DoubleFunction;

/** Screen-local replay using the same body solver, emitter and collision response as the world. */
final class BodyPickupPreview {
    private JsonElement bodyJson, pickupJson;
    private long cycle = Long.MIN_VALUE;
    private int tick;
    private Vec3 endpoint;
    private OrbBodyMotion body;
    private OrbPickupParticles pickup;
    private PreviewSteps steps;
    private boolean captured;

    static double afterglowSeconds(TrailConfig cfg) {
        OrbEffectOptions effect = section(cfg.pickupFlash && cfg.advanced != null ? cfg.advanced.get("pickup") : null);
        if (!OrbPickupParticles.supported(effect.type)) return 0;
        int duration = effect.count("lifetimeTicks", 32, 3, 160);
        if (effect.type.equals("ripple")) duration += effect.count("fallTicks", 4, 0, 25)
                + (effect.count("rings", 2, 1, 4) - 1) * effect.count("delayTicks", 3, 1, 20);
        if (effect.type.equals("fireworks") || effect.type.equals("rebound"))
            duration += effect.count("childLifetimeTicks", 14, 2, 80);
        return (duration + 1) / 20.0;
    }

    private static OrbEffectOptions section(JsonElement value) {
        return value != null && value.isJsonObject() ? new OrbEffectOptions(value.getAsJsonObject()) : OrbEffectOptions.EMPTY;
    }

    void append(EffectFrame out, TrailConfig cfg, DoubleFunction<Vec3> flight, long cycleIndex,
                double cycleSeconds, double collectedAtSeconds) {
        JsonElement bodySection = cfg.advanced == null ? null : cfg.advanced.get("body");
        JsonElement pickupSection = cfg.advanced == null || !cfg.pickupFlash ? null : cfg.advanced.get("pickup");
        if (bodySection == null && pickupSection == null) return;
        double clock = cycleSeconds * 20;
        int frameTick = (int) Math.floor(clock), takeTick = (int) Math.round(collectedAtSeconds * 20);
        Vec3 end = flight.apply(collectedAtSeconds);
        if (cycle != cycleIndex || frameTick < tick || bodyJson != bodySection || pickupJson != pickupSection
                || !end.equals(endpoint)) {
            cycle = cycleIndex;
            endpoint = end;
            tick = -1;
            bodyJson = bodySection;
            pickupJson = pickupSection;
            captured = false;
            var b = section(bodySection);
            var p = section(pickupSection);
            body = OrbBodyMotion.supported(b.type) ? new OrbBodyMotion(b, flight.apply(0), cycleIndex + 71) : null;
            pickup = OrbPickupParticles.supported(p.type) ? new OrbPickupParticles(p, cycleIndex + 23) : null;
            steps = new PreviewSteps(end);
        }
        if (body == null && pickup == null) return;
        for (; tick < frameTick;) {
            ++tick;
            if (tick < takeTick) {
                Vec3 pos = flight.apply(tick / 20.0);
                if (body != null) body.step(pos, pos.subtract(flight.apply(Math.max(0, tick - 1) / 20.0)));
                if (pickup != null && pickup.options.type.equals("vortex")) {
                    pickup.step(steps);
                    pickup.emitDrift(pos);
                }
            } else if (!captured) {
                if (pickup != null) pickup.start(end, steps);
                captured = true;
            } else if (pickup != null && !pickup.finished()) pickup.step(steps);
        }
        if (captured && pickup != null && (pickup.options.type.equals("ripple") || pickup.options.type.equals("rebound")))
            steps.draw(out);
        double partial = clock - frameTick;
        if (!captured && body != null) body.draw(out, flight.apply(cycleSeconds), partial);
        if (pickup != null && !pickup.finished()) pickup.draw(out, partial);
    }

    /** Two adjacent treads with a solid riser for the settings demonstration. */
    private record PreviewSteps(Vec3 center) implements OrbPickupParticles.Terrain {
        private static final double EDGE = .17, LOW = -.47, HIGH = -.18, REACH = 1.3;

        @Override public OrbPickupParticles.Surface cast(Vec3 from, Vec3 to) {
            Vec3 start = from.subtract(center), delta = to.subtract(from);
            double nearest = Double.POSITIVE_INFINITY;
            Vec3 normal = OrbEffectOptions.UP;
            if (delta.y < -1e-10) {
                for (double level : new double[]{LOW, HIGH}) {
                    double t = (level - start.y) / delta.y;
                    if (t < 0 || t > 1 || t >= nearest) continue;
                    Vec3 p = start.add(delta.scale(t));
                    if (Math.abs(p.x) <= REACH && Math.abs(p.z) <= REACH && (p.x >= EDGE) == (level == HIGH)) nearest = t;
                }
            }
            if (delta.x > 1e-10 && start.x < EDGE) {
                double t = (EDGE - start.x) / delta.x;
                Vec3 p = start.add(delta.scale(t));
                if (t >= 0 && t <= 1 && t < nearest && p.y >= LOW && p.y <= HIGH && Math.abs(p.z) <= REACH) {
                    nearest = t;
                    normal = new Vec3(-1, 0, 0);
                }
            }
            return Double.isFinite(nearest) ? new OrbPickupParticles.Surface(from.add(delta.scale(nearest)), normal) : null;
        }

        void draw(EffectFrame out) {
            for (boolean raised : new boolean[]{false, true}) {
                double lowX = raised ? EDGE : -REACH, highX = raised ? REACH : EDGE, y = raised ? HIGH : LOW;
                out.quad(center.add(lowX, y, -REACH), center.add(lowX, y, REACH),
                        center.add(highX, y, REACH), center.add(highX, y, -REACH), raised ? 0xa335514f : 0xa32a4646);
            }
            out.quad(center.add(EDGE, LOW, -REACH), center.add(EDGE, LOW, REACH),
                    center.add(EDGE, HIGH, REACH), center.add(EDGE, HIGH, -REACH), 0xbc1d3337);
        }
    }
}
