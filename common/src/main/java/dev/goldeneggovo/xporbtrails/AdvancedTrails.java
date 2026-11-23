package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.List;

final class AdvancedTrails {
    private static final int MAX_SEGMENTS = 80;
    private static final double TAU = Math.PI * 2;
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private record Cached(JsonElement source, AdvancedTrailStyle style) {}
    private static volatile Cached cache = new Cached(null, null);

    private AdvancedTrails() {}

    static void validate(JsonObject trail) {
        new AdvancedTrailStyle(trail);
    }

    static boolean enabled(TrailConfig cfg) { return style(cfg) != null; }

    static double afterglowSeconds(TrailConfig cfg) {
        AdvancedTrailStyle style = style(cfg);
        if (style == null || style.particles == null) return 0;
        return style.particles.lifetime() + (style.particles.birth() == AdvancedTrailStyle.Birth.EXPIRY ? cfg.lifetimeSeconds : 0);
    }

    private static AdvancedTrailStyle style(TrailConfig cfg) {
        JsonElement source = cfg.advanced == null ? null : cfg.advanced.get("trail");
        if (source == null || !source.isJsonObject()) return null;
        Cached c = cache;
        if (c.source == source) return c.style;
        AdvancedTrailStyle compiled = null;
        try {
            compiled = new AdvancedTrailStyle(source.getAsJsonObject());
        } catch (IllegalArgumentException ignored) {
            // Manually edited configs can bypass profile import validation.
        }
        cache = new Cached(source, compiled);
        return compiled;
    }

    static boolean append(EffectFrame out, List<TrailRenderer.Sample> samples, Vec3 orbPosition, long nowNanos,
                          long seed, double fade, TrailConfig cfg) {
        AdvancedTrailStyle style = style(cfg);
        if (style == null) return false;
        if (style.geometry == AdvancedTrailStyle.Geometry.PARTICLES || fade <= 0 || samples.size() < 2) return true;
        int last = samples.size() - 1;
        int count = Math.min(last, MAX_SEGMENTS);
        var first = samples.getFirst();
        double distance = first.birthDistance();
        double length = samples.getLast().birthDistance() - distance;
        boolean anchored = length > 1e-6;
        if (!anchored) {
            length = 0;
            for (int i = 1; i <= count; i++) length += samples.get(i * last / count).position()
                    .distanceTo(samples.get((i - 1) * last / count).position());
            if (length <= 1e-6) return true;
        }
        double seconds = nowNanos * 1e-9;
        double flashPhase = seconds / style.branches.flashSeconds();
        long flash = seed ^ (long) Math.floor(flashPhase);
        Vec3 normal = null, previousTangent = null, previousCenter = first.position(), lastArc = null;
        Vec3[] left = new Vec3[style.strands.size()], right = new Vec3[left.length];
        int branches = 0;
        double lastBranch = -Double.MAX_VALUE;
        for (int i = 0; i <= count; i++) {
            int index = i * last / count;
            var sample = samples.get(index);
            Vec3 center = sample.position();
            distance = anchored ? sample.birthDistance() : distance + center.distanceTo(previousCenter);
            double u = Math.clamp((distance - first.birthDistance()) / length, 0, 1);
            Vec3 tangent = TrailRenderer.tangent(samples, index);
            normal = normal == null ? initialNormal(tangent) : transport(normal, previousTangent, tangent);
            Vec3 binormal = tangent.cross(normal);
            double angle = distance * TAU / style.period;
            double width = cfg.width * (style.width == null ? TrailRenderer.widthScale(cfg, u) : style.width.at(u));
            double age = Math.clamp((nowNanos - sample.time()) / (cfg.lifetimeSeconds * 1e9), 0, 1);
            double ageFade = style.geometry == AdvancedTrailStyle.Geometry.CRUMBLE ? 1 - 0.45 * age
                    : 1 - TrailEffects.smooth(age);
            double alpha = cfg.effectStrength * fade * ageFade * (style.opacity == null ? 1 : style.opacity.at(u));
            int rgb = style.color(cfg, u, seconds);
            int color = argb(rgb, alpha);
            switch (style.geometry) {
                case RIBBON -> {
                    for (int s = 0; s < left.length; s++) {
                        var strand = style.strands.get(s);
                        double phase = angle + strand.phase();
                        Vec3 axis = center;
                        if (strand.radius() != 0 || strand.depth() != 0)
                            axis = offset(center, normal, Math.cos(phase) * strand.radius(),
                                    binormal, Math.sin(phase) * strand.depth());
                        double roll = style.orientation + style.roll * distance / style.period + strand.phase();
                        double radius = width * strand.width() * 0.5;
                        Vec3 side = offset(Vec3.ZERO, normal, Math.cos(roll) * radius, binormal, Math.sin(roll) * radius);
                        Vec3 a = axis.add(side), b = axis.subtract(side);
                        if (i > 0 && (color >>> 24) > 0)
                            out.quad(left[s], a, b, right[s], strand.color() < 0 ? color : argb(strand.color(), alpha));
                        left[s] = a;
                        right[s] = b;
                    }
                }
                case ARC -> {
                    var arc = style.branches;
                    double k = distance / Math.max(0.10, arc.length() * 0.5);
                    long cell = (long) Math.floor(k);
                    double tip = Math.min(1, Math.min(u, 1 - u) * 10);
                    Vec3 offset = normal.scale(noise(flash, cell, k - cell)).add(binormal.scale(noise(~flash, cell, k - cell)));
                    Vec3 arcCenter = center.add(offset.scale(arc.jitter() * tip));
                    if (i > 0) out.line(lastArc, arcCenter, Math.max(0.003, width), color);
                    int span = Math.max(2, last / 14);
                    if (branches < arc.count() && i > 0 && i < count && distance - lastBranch >= arc.length()
                            && flashPhase - Math.floor(flashPhase) < 0.75) {
                        Vec3 before = center.subtract(samples.get(Math.max(0, index - span)).position()).normalize();
                        Vec3 after = samples.get(Math.min(last, index + span)).position().subtract(center).normalize();
                        if (before.lengthSqr() > 0.5 && after.lengthSqr() > 0.5 && before.dot(after) < Math.cos(arc.angle())) {
                            drawBranch(out, arcCenter, tangent, normal, binormal, arc.length(), width, color, flash ^ cell);
                            lastBranch = distance;
                            branches++;
                        }
                    }
                    lastArc = arcCenter;
                }
                case CRUMBLE -> {
                    if (i > 0) {
                        // Old spans pull apart before the original node emits its world-space fragments.
                        double split = TrailEffects.smooth((age - style.breakAt) / (1 - style.breakAt));
                        double slot = (distance / style.breakSpacing) % 1;
                        if (slot >= 0.55 * split)
                            out.line(previousCenter.lerp(center, split * 0.22), center, Math.max(0.004, width), color);
                    }
                }
                default -> { }
            }
            previousCenter = center;
            previousTangent = tangent;
        }
        return true;
    }

    private static void drawBranch(EffectFrame out, Vec3 center, Vec3 tangent, Vec3 normal, Vec3 binormal,
                                   double length, double width, int color, long key) {
        double angle = random(key) * TAU;
        Vec3 direction = normal.scale(Math.cos(angle)).add(binormal.scale(Math.sin(angle))).add(tangent.scale(-0.45)).normalize();
        Vec3 side = direction.cross(tangent).scale(length * (0.09 + 0.12 * random(~key)));
        Vec3 last = center;
        for (int i = 1; i <= 4; i++) {
            Vec3 p = center.add(direction.scale(length * i / 4));
            if (i < 4) p = p.add(side.scale(i % 2 == 0 ? -0.5 : 1));
            out.line(last, p, Math.max(0.003, width * (0.72 - 0.11 * i)), color);
            last = p;
        }
    }

    static Vec3 initialNormal(Vec3 tangent) {
        // Rotate world up from the +Z reference frame. There is no near-vertical axis switch.
        if (tangent.z < -0.999999) return UP.subtract(tangent.scale(tangent.y)).normalize();
        double f = tangent.y / (1 + tangent.z);
        return new Vec3(-tangent.x * f, 1 - tangent.y * f, -tangent.y).normalize();
    }

    /** Minimal rotation between successive tangents, including a deterministic half turn at a cusp. */
    static Vec3 transport(Vec3 normal, Vec3 from, Vec3 to) {
        double cosine = from.dot(to);
        if (cosine < -0.999999) return normal.subtract(to.scale(normal.dot(to))).normalize();
        double projection = -normal.dot(to) / (1 + cosine);
        return offset(normal, from, projection, to, projection).normalize();
    }

    private static Vec3 offset(Vec3 center, Vec3 a, double x, Vec3 b, double y) {
        return new Vec3(center.x + a.x * x + b.x * y, center.y + a.y * x + b.y * y,
                center.z + a.z * x + b.z * y);
    }

    private static int argb(int rgb, double alpha) {
        return (Math.clamp((int) (alpha * 255), 0, 255) << 24) | (rgb & 0xffffff);
    }

    private static double noise(long seed, long cell, double t) {
        double a = random(seed + cell), b = random(seed + cell + 1);
        return (a + (b - a) * t) * 2 - 1;
    }

    private static double random(long x) {
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return ((x ^ (x >>> 31)) >>> 11) * 0x1.0p-53;
    }

    static State state(State old, TrailConfig cfg) {
        AdvancedTrailStyle style = style(cfg);
        if (style == null || style.particles == null) return null;
        JsonElement source = cfg.advanced.get("trail");
        return old != null && old.source == source ? old : new State(style, source);
    }

    record Particle(Vec3 origin, Vec3 velocity, long born, int rgb, double size) {}

    /** Stored on the orb's trail. Birth positions and launch velocities stay fixed after each event. */
    static final class State {
        static final int CAPACITY = 48, MAX_EVENTS = 8;
        private final AdvancedTrailStyle style;
        private final JsonElement source;
        private final AdvancedTrailStyle.Wake settings;
        private final ArrayDeque<Particle> particles = new ArrayDeque<>();
        private Vec3 previous;
        private Vec3 lastExpiryTangent = Vec3.ZERO;
        private long previousTime, sequence, expiryFrame;
        private int expiryCount;
        private double remainder;

        State(AdvancedTrailStyle style, JsonElement source) {
            this.style = style;
            this.source = source;
            settings = style.particles;
        }

        void move(Vec3 center, Vec3 velocity, long now, long seed) {
            if (settings.birth() != AdvancedTrailStyle.Birth.DISTANCE) return;
            if (previous != null && now <= previousTime) return;
            if (previous != null) {
                double length = previous.distanceTo(center);
                double spacing = settings.spacing();
                int crossings = (int) Math.floor((remainder + length) / spacing);
                for (int i = Math.max(1, crossings - MAX_EVENTS + 1); i <= crossings && i > 0; i++) {
                    double t = Math.clamp((i * spacing - remainder) / length, 0, 1);
                    long time = previousTime + (long) ((now - previousTime) * t);
                    Vec3 drift = velocity.scale(settings.inheritVelocity());
                    emit(previous.lerp(center, t), drift, time, settings.color(), seed ^ (sequence + i));
                }
                sequence += crossings;
                remainder = (remainder + length) % spacing;
            }
            previous = center;
            previousTime = now;
        }

        void expire(Vec3 position, Vec3 tangent, long expiresNanos, long now, long seed, TrailConfig cfg) {
            if (!usesExpiry() || expiresNanos > now) return;
            if (tangent.lengthSqr() > 1e-9) lastExpiryTangent = tangent.normalize();
            if (lastExpiryTangent.lengthSqr() < 0.5) return;
            if (now - expiresNanos >= settings.lifetime() * 1e9) return;
            if (expiryFrame != now) {
                expiryFrame = now;
                expiryCount = 0;
            }
            if (expiryCount >= MAX_EVENTS) return;
            expiryCount++;
            int inheritedColor = style.color(cfg, 0, expiresNanos * 1e-9);
            emit(position, lastExpiryTangent.scale(settings.tangentSpeed()), expiresNanos,
                    inheritedColor, seed ^ (++sequence));
        }

        boolean usesExpiry() { return settings.birth() == AdvancedTrailStyle.Birth.EXPIRY; }
        void endPath() { lastExpiryTangent = Vec3.ZERO; }

        private void emit(Vec3 pos, Vec3 velocity, long time, int rgb, long key) {
            for (int i = 0; i < settings.burst(); i++) {
                long n = key * 0x9e3779b97f4a7c15L + i * 4L;
                Vec3 jitter = new Vec3(random(n) - 0.5, random(n + 1) - 0.5, random(n + 2) - 0.5);
                if (particles.size() >= CAPACITY) particles.removeFirst();
                particles.addLast(new Particle(pos, velocity.add(jitter.scale(2 * settings.scatter())),
                        time, rgb, settings.size() * (0.7 + 0.6 * random(n + 3))));
            }
        }

        boolean alive(long now) {
            long oldest = now - (long) (settings.lifetime() * 1e9);
            while (!particles.isEmpty() && particles.getFirst().born <= oldest) particles.removeFirst();
            return !particles.isEmpty();
        }

        void appendParticles(EffectFrame out, long now, double strength) {
            if (!alive(now) || strength <= 0) return;
            for (Particle p : particles) {
                double age = (now - p.born) * 1e-9;
                double progress = age / settings.lifetime();
                if (age < 0 || progress >= 1) continue;
                Vec3 center = position(p, age, settings);
                int rgb = settings.endColor() < 0 ? p.rgb : TrailEffects.mix(p.rgb, settings.endColor(), progress);
                int color = argb(rgb, strength * (1 - TrailEffects.smooth(progress)));
                double size = p.size * (1 - 0.5 * progress);
                if (settings.birth() == AdvancedTrailStyle.Birth.EXPIRY) {
                    out.line(center.add(0, -size * 0.5, 0), center.add(0, size * 0.5, 0), size, color);
                } else {
                    Vec3 previous = position(p, Math.max(0, age - 0.045), settings);
                    // Keep a visible cap while a spark is young or its launch velocity is zero.
                    if (center.distanceToSqr(previous) < size * size) previous = center.add(0, -size, 0);
                    out.line(previous, center, size, color);
                    if (settings.turbulence() > 0) {
                        Vec3 tip = center.add(center.subtract(previous).scale(0.7));
                        out.line(center, tip, size * 0.45, color);
                    }
                }
            }
        }
    }

    static Vec3 position(Particle p, double age, AdvancedTrailStyle.Wake settings) {
        double drag = settings.drag();
        double travel = drag == 0 ? age : -Math.expm1(-drag * age) / drag;
        double fall = drag == 0 ? 0.5 * age * age : (age - travel) / drag;
        Vec3 base = p.origin.add(p.velocity.scale(travel)).add(0, -settings.gravity() * fall, 0);
        if (settings.turbulence() == 0 || age == 0) return base;
        // Integrate a smooth spatial and temporal wind field at three midpoints of the free trajectory.
        double dx = 0, dz = 0, step = age / 3;
        for (int i = 0; i < 3; i++) {
            double t = (i + 0.5) * step;
            double f = (i + 0.5) / 3;
            double x = p.origin.x + (base.x - p.origin.x) * f + dx;
            double y = p.origin.y + (base.y - p.origin.y) * f;
            double z = p.origin.z + (base.z - p.origin.z) * f + dz;
            double phase = (p.born * 1e-9 + t) * settings.frequency();
            dx += step * settings.turbulence() * Math.sin(phase + y * 2.3 + z * 1.4);
            dz += step * settings.turbulence() * Math.cos(phase * 0.73 + x * 1.8 - y);
        }
        return base.add(dx, 0, dz);
    }
}
