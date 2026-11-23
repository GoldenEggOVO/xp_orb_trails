package dev.goldeneggovo.xporbtrails;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static dev.goldeneggovo.xporbtrails.OrbEffectOptions.*;

/** One bounded simulation per pickup, also used for vortex particles emitted during flight. */
final class OrbPickupParticles {
    record Surface(Vec3 position, Vec3 normal) {}
    interface Terrain { Surface cast(Vec3 from, Vec3 to); }
    enum Kind { PETAL, ROCKET, SPARK, ICE, POWDER, DRIFT, CAPTURED }
    static final class Particle {
        Vec3 previous, position, velocity;
        Kind kind;
        int age, life, contacts;
        final double phase, spin, size;
        final int color;

        Particle(Kind kind, Vec3 origin, Vec3 velocity, int life, int color, double size, Random random) {
            this.kind = kind;
            position = previous = origin;
            this.velocity = velocity;
            this.life = life;
            this.color = color;
            this.size = size;
            phase = random.nextDouble();
            spin = (random.nextBoolean() ? 1 : -1) * (.08 + random.nextDouble() * .16);
        }
    }

    final List<Particle> particles = new ArrayList<>();
    final OrbEffectOptions options;
    private final Random random;
    private final double gravity, speed, size;
    private final int color, lifetime;
    private Vec3 target;
    private Surface impact;
    private int age;
    private Vec3[][] waves;

    OrbPickupParticles(OrbEffectOptions options, long seed) {
        this.options = options;
        random = new Random(seed);
        gravity = options.number("gravity", .009, 0, .12);
        speed = options.number("speed", .082, .002, .65);
        size = options.number("size", .045, .003, .6);
        color = options.color("color", 0xffc9f4fa);
        lifetime = options.count("lifetimeTicks", 32, 3, 160);
    }

    static boolean supported(String type) {
        return switch (type) {
            case "petals", "fireworks", "ripple", "rebound", "vortex" -> true;
            default -> false;
        };
    }

    private Vec3 direction() {
        double angle = random.nextDouble() * TAU, y = random.nextDouble() * 2 - 1;
        double r = Math.sqrt(1 - y * y);
        return new Vec3(r * Math.cos(angle), y, r * Math.sin(angle));
    }

    void start(Vec3 origin, Terrain terrain) {
        target = origin;
        age = 0;
        if (options.type.equals("ripple")) {
            impact = terrain.cast(origin, origin.add(0, -options.number("probeDepth", 4, .25, 12), 0));
            return;
        }
        if (options.type.equals("vortex")) {
            for (Particle p : particles) {
                if (p.kind != Kind.DRIFT) continue;
                p.kind = Kind.CAPTURED;
                p.age = 0;
                p.life = lifetime;
            }
            return;
        }
        int count = options.count("count", 10, 1, 32);
        for (int i = 0; i < count; i++) {
            Vec3 dir = direction();
            Kind kind = switch (options.type) {
                case "petals" -> Kind.PETAL;
                case "fireworks" -> Kind.ROCKET;
                default -> Kind.ICE;
            };
            Vec3 velocity = dir.scale(speed * (.6 + random.nextDouble() * .6));
            if (kind == Kind.PETAL) velocity = velocity.add(0, speed * .9, 0);
            if (kind == Kind.ICE) velocity = velocity.add(0, speed * .55, 0);
            int life = Math.max(2, (int) Math.round(lifetime * (.78 + .22 * random.nextDouble())));
            particles.add(new Particle(kind, origin, velocity, life, color, size * (.8 + random.nextDouble() * .4), random));
        }
    }

    void emitDrift(Vec3 orb) {
        int count = options.count("count", 16, 1, 32);
        double radius = options.number("radius", .42, .02, 3);
        int births = Math.min(options.count("perTick", 2, 1, 6), count - particles.size());
        for (int i = 0; i < births; i++) {
            Vec3 origin = orb.add(direction().scale(radius * (.6 + random.nextDouble() * .5)));
            particles.add(new Particle(Kind.DRIFT, origin, direction().scale(speed), 16 + random.nextInt(25),
                    random.nextBoolean() ? color : options.color("accent", 0xffb8a2e0), size, random));
        }
    }

    void step(Terrain terrain) {
        age++;
        if (impact != null) {
            sampleWaves(terrain);
            return;
        }
        List<Particle> children = null;
        var iterator = particles.iterator();
        while (iterator.hasNext()) {
            Particle p = iterator.next();
            p.previous = p.position;
            p.age++;
            applyForces(p);
            Vec3 end = p.position.add(p.velocity);
            boolean shatter = false;
            Surface hit = p.kind == Kind.ICE ? terrain.cast(p.position, end) : null;
            double normalSpeed = hit == null ? 0 : p.velocity.dot(hit.normal);
            if (hit != null && normalSpeed < 0) {
                p.position = hit.position.add(hit.normal.scale(.002));
                Vec3 tangent = p.velocity.subtract(hit.normal.scale(normalSpeed));
                p.velocity = tangent.scale(.83).subtract(hit.normal.scale(normalSpeed
                        * options.number("restitution", .62, .02, 1)));
                shatter = ++p.contacts >= options.count("contacts", 2, 1, 6);
            } else {
                p.position = end;
            }
            if (!shatter && p.age < p.life
                    && (p.kind != Kind.CAPTURED || p.position.distanceToSqr(target) > .00035)) continue;
            iterator.remove();
            if (p.kind == Kind.ROCKET || shatter) {
                if (children == null) children = new ArrayList<>();
                emitChildren(children, p, shatter);
            }
        }
        if (children != null) particles.addAll(children.subList(0, Math.min(children.size(), 160 - particles.size())));
    }

    private void applyForces(Particle p) {
        switch (p.kind) {
            case CAPTURED -> {
                Vec3 delta = target.subtract(p.position);
                double attraction = options.number("attraction", .12, .005, .5);
                double tangent = options.number("tangent", .095, -.3, .3);
                double remaining = Math.max(0, 1 - (double) p.age / p.life);
                p.velocity = p.velocity.scale(.68).add(delta.scale(attraction))
                        .add(UP.cross(delta).scale(tangent * remaining));
            }
            case PETAL -> {
                double a = p.phase * TAU + p.age * .29;
                double flutter = options.number("flutter", .004, 0, .04);
                p.velocity = p.velocity.scale(.94).add(Math.sin(a) * flutter, -gravity, Math.cos(a) * flutter);
            }
            case DRIFT -> p.velocity = p.velocity.scale(.97).add(0, Math.sin(p.phase * TAU + p.age * .22) * .0008, 0);
            default -> p.velocity = p.velocity.scale(p.kind == Kind.POWDER ? .92 : .985).add(0, -gravity, 0);
        }
    }

    private void emitChildren(List<Particle> children, Particle parent, boolean powder) {
        int count = options.count("children", powder ? 5 : 8, 1, 16);
        double launch = options.number("childSpeed", .055, .002, .4);
        int life = options.count("childLifetimeTicks", 14, 2, 80);
        int accent = options.color("accent", powder ? 0xffeffaff : 0xffef9eae);
        for (int i = 0; i < count && children.size() < 160; i++) {
            Vec3 v = direction().scale(launch * (.7 + .5 * random.nextDouble()));
            if (powder) v = new Vec3(v.x, Math.abs(v.y) + .008, v.z);
            children.add(new Particle(powder ? Kind.POWDER : Kind.SPARK, parent.position, v, life,
                    i % 2 == 0 ? accent : color, parent.size * (powder ? .36 : .56), random));
        }
    }

    private int fallTicks() { return options.count("fallTicks", 4, 0, 25); }

    private void sampleWaves(Terrain terrain) {
        int layers = options.count("rings", 2, 1, 4), segments = options.count("segments", 20, 8, 36);
        double radius = options.number("radius", .62, .03, 3);
        waves = new Vec3[layers][];
        Vec3 n = impact.normal, u = perpendicular(n), v = n.cross(u);
        for (int layer = 0; layer < layers; layer++) {
            double t = waveProgress(layer);
            if (t < 0 || t >= 1) continue;
            Vec3[] vertices = new Vec3[segments];
            for (int i = 0; i < segments; i++) {
                Vec3 onPlane = impact.position.add(around(u, v, TAU * i / segments).scale(radius * (.045 + .955 * t)));
                Surface ground = terrain.cast(onPlane.add(n.scale(.72)), onPlane.subtract(n.scale(.9)));
                if (ground != null) vertices[i] = ground.position.add(ground.normal.scale(.014));
            }
            waves[layer] = vertices;
        }
    }

    private double waveProgress(int layer) {
        return (age - fallTicks() - layer * options.count("delayTicks", 3, 1, 20)) / (double) lifetime;
    }

    boolean finished() {
        return impact == null ? particles.isEmpty() : waveProgress(options.count("rings", 2, 1, 4) - 1) >= 1;
    }

    void draw(EffectFrame out, double partial) {
        if (impact != null) {
            if (age < fallTicks()) {
                Vec3 current = target.lerp(impact.position, Math.min(1, (age + partial) / fallTicks()));
                out.line(current, current.add(0, size * 2, 0), size, color);
            }
            if (waves != null) for (int k = 0; k < waves.length; k++) {
                Vec3[] ring = waves[k];
                if (ring == null) continue;
                for (int i = 0; i < ring.length; i++) {
                    Vec3 a = ring[i], b = ring[(i + 1) % ring.length];
                    // A rising step divides the wave into two contours on their respective treads.
                    if (a != null && b != null && Math.abs(a.subtract(b).dot(impact.normal)) < .23)
                        out.line(a, b, size, fade(color, 1 - waveProgress(k)));
                }
            }
            return;
        }
        SpriteSheet sheet = options.type.equals("petals")
                ? XpOrbTrailsClient.sprite(AssetRole.PICKUP, options.text("sprite", "sprite:pickup/atlas-sakura")) : null;
        for (Particle p : particles) {
            Vec3 at = p.previous.lerp(p.position, partial);
            double age = Math.max(0, p.age - 1 + partial);
            int tint = fade(p.color, Math.min(1, (p.life - age) / Math.min(7, p.life * .4)));
            if (p.kind == Kind.PETAL) {
                if (sheet != null) out.sprite(sheet, at, p.size, age / 20 + p.phase * sheet.durationMillis() / 1000,
                        tint, p.phase * TAU + age * p.spin * options.number("spinScale", 1, 0, 8));
            } else if (p.kind == Kind.ICE) {
                Vec3 tip = new Vec3(Math.sin(p.phase * TAU + age * p.spin), 1, .28).normalize().scale(p.size);
                out.line(at.subtract(tip), at.add(tip), p.size * .63, tint);
            } else {
                Vec3 axis = p.velocity.lengthSqr() > 1e-9 ? p.velocity.normalize() : UP;
                double length = p.size * (p.kind == Kind.ROCKET ? 2.8 : 1);
                out.line(at.subtract(axis.scale(length)), at.add(axis.scale(p.size * .25)), p.size, tint);
            }
        }
    }
}
