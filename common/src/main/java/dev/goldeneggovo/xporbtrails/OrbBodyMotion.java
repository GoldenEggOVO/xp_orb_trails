package dev.goldeneggovo.xporbtrails;

import net.minecraft.world.phys.Vec3;

import java.util.Random;

import static dev.goldeneggovo.xporbtrails.OrbEffectOptions.*;

/** Fixed-tick simulation. Swarm positions use orb-local space, tentacle nodes use world space. */
final class OrbBodyMotion {
    private final OrbEffectOptions options;
    private final Random random;
    private final int ticksPerLife;
    private final double radius;
    private final double size;
    private final int color;
    private Vec3 center;
    private Vec3 heading = RIGHT;
    private int ticks, poseTicks;
    boolean gliding;
    double strain = 1, lastStrain = 1, strainVelocity;
    final Vec3[] points, previous;
    private final Vec3[] velocities;
    private final int[] ages;
    private final double[] phases;
    private final int chainNodes;

    OrbBodyMotion(OrbEffectOptions options, Vec3 origin, long seed) {
        this.options = options;
        this.random = new Random(seed);
        center = origin;
        ticksPerLife = options.count("lifetimeTicks", 34, 8, 160);
        radius = options.number("radius", .28, .015, 2);
        size = options.number("size", .1, .005, 2);
        color = options.color("color", 0xffbde9ef);
        chainNodes = options.count("nodes", 7, 2, 14);
        int count = switch (options.type) {
            case "firefly", "magnet" -> options.count("count", 10, 1, 32);
            case "tentacles" -> options.count("count", 4, 1, 8) * chainNodes;
            default -> 0;
        };
        points = new Vec3[count];
        previous = new Vec3[count];
        velocities = new Vec3[count];
        ages = new int[count];
        phases = new double[count];
        for (int i = 0; i < count; i++) {
            phases[i] = random.nextDouble();
            if (options.type.equals("tentacles")) {
                int index = i % chainNodes;
                points[i] = anchor(origin, i / chainNodes).add(0, -index * spacing(), 0);
            } else {
                birth(i);
                int stagger = i * ticksPerLife / count;
                for (int t = 0; t < stagger; t++) moveSwarm(i);
            }
            previous[i] = points[i];
        }
    }

    static boolean supported(String type) {
        return switch (type) {
            case "planet", "firefly", "magnet", "tentacles", "droplet", "bird" -> true;
            default -> false;
        };
    }

    static Vec3 dipole(Vec3 position, double pole) {
        Vec3 north = position.subtract(pole, 0, 0), south = position.add(pole, 0, 0);
        return inverseSquare(north).subtract(inverseSquare(south)).normalize();
    }

    private static Vec3 inverseSquare(Vec3 offset) {
        return offset.scale(1 / Math.max(1e-7, Math.pow(offset.lengthSqr(), 1.5)));
    }

    private void birth(int i) {
        double angle = random.nextDouble() * TAU;
        if (options.type.equals("firefly")) {
            double y = random.nextDouble() * 2 - 1, r = Math.sqrt(1 - y * y);
            Vec3 direction = new Vec3(r * Math.cos(angle), y, r * Math.sin(angle));
            points[i] = direction.scale(radius);
            velocities[i] = direction.scale(options.number("launchSpeed", .027, 0, .15))
                    .add(perpendicular(direction).scale(.009));
        } else {
            double r = radius * .18;
            points[i] = new Vec3(radius * .68 - random.nextDouble() * r * .8,
                    r * Math.cos(angle), r * Math.sin(angle));
        }
        ages[i] = 0;
        previous[i] = points[i];
    }

    private void moveSwarm(int i) {
        previous[i] = points[i];
        if (++ages[i] >= ticksPerLife) {
            birth(i);
            return;
        }
        if (options.type.equals("firefly")) {
            double phase = phases[i] * TAU + ages[i] * .37;
            Vec3 flutter = new Vec3(Math.cos(phase), .45 * Math.sin(phase * 1.3), Math.sin(phase));
            double pull = options.number("attraction", .022, 0, .12);
            velocities[i] = velocities[i].scale(.90).add(flutter.scale(.0018))
                    .subtract(points[i].scale(pull * ages[i] / ticksPerLife));
            points[i] = points[i].add(velocities[i]);
        } else {
            double pole = radius * .68, step = options.number("fieldSpeed", .035, .003, .15);
            Vec3 mid = points[i].add(dipole(points[i], pole).scale(step * .5));
            points[i] = points[i].add(dipole(mid, pole).scale(step));
            if (points[i].distanceToSqr(new Vec3(-pole, 0, 0)) < step * step * 1.5) birth(i);
        }
    }

    void step(Vec3 next, Vec3 motion) {
        ticks++;
        double speed = motion.length();
        if (speed > .004) heading = motion.scale(1 / speed);
        switch (options.type) {
            case "firefly", "magnet" -> {
                for (int i = 0; i < points.length; i++) moveSwarm(i);
            }
            case "tentacles" -> advanceChains(next);
            case "bird" -> {
                double high = options.number("glideSpeed", .14, .002, 2);
                double low = Math.min(high * .99, options.number("hoverSpeed", .07, 0, 2));
                boolean pose = gliding ? speed > low : speed >= high;
                poseTicks = pose == gliding ? poseTicks + 1 : 0;
                gliding = pose;
            }
            case "droplet" -> {
                lastStrain = strain;
                double target = 1 + Math.min(1.8, speed * options.number("stretchGain", 3.8, 0, 18));
                double spring = options.number("spring", .22, .01, .6);
                strainVelocity = (strainVelocity + (target - strain) * spring)
                        * options.number("damping", .77, .2, .98);
                strain = Math.clamp(strain + strainVelocity, .50, 3.5);
            }
        }
        center = next;
    }

    private double spacing() {
        return options.number("spacing", .055, .012, .25);
    }

    private Vec3 anchor(Vec3 origin, int chain) {
        double angle = chain * TAU / (points.length / chainNodes);
        return origin.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
    }

    private void advanceChains(Vec3 next) {
        double damping = options.number("damping", .82, .1, .99), spacing = spacing();
        Vec3 displacement = next.subtract(center);
        for (int i = 0; i < points.length; i++) {
            Vec3 old = points[i];
            if (displacement.lengthSqr() > 9) {
                old = old.add(displacement);
                previous[i] = previous[i].add(displacement);
            }
            if (i % chainNodes == 0) {
                points[i] = anchor(next, i / chainNodes);
            } else {
                double wave = ticks * .17 + i / chainNodes;
                Vec3 predicted = old.add(old.subtract(previous[i]).scale(damping))
                        .add(.0014 * Math.cos(wave), -.008, .0014 * Math.sin(wave));
                Vec3 delta = predicted.subtract(points[i - 1]);
                Vec3 direction = delta.lengthSqr() < 1e-12 ? UP.scale(-1) : delta.normalize();
                points[i] = points[i - 1].add(direction.scale(spacing));
            }
            previous[i] = old;
        }
    }

    SpriteSheet birdSheet() {
        return XpOrbTrailsClient.sprite(AssetRole.ORB, gliding
                ? options.text("glideSprite", "sprite:orb/atlas-bird-glide")
                : options.text("hoverSprite", "sprite:orb/atlas-bird-hover"));
    }

    void draw(EffectFrame out, Vec3 at, double partial) {
        switch (options.type) {
            case "planet" -> drawPlanet(out, at, partial);
            case "firefly" -> {
                SpriteSheet sheet = XpOrbTrailsClient.sprite(AssetRole.ORB, options.text("sprite", "sprite:orb/atlas-firefly"));
                if (sheet == null) return;
                for (int i = 0; i < points.length; i++) {
                    double age = (ages[i] + partial) / 20 + phases[i] * sheet.durationMillis() / 1000;
                    Vec3 p = at.add(previous[i].lerp(points[i], partial));
                    double alpha = Math.min(1, Math.min((ages[i] + partial + 1) / 4, (ticksPerLife - ages[i]) / 5.0));
                    out.sprite(sheet, p, size, age, fade(color, alpha));
                }
            }
            case "magnet" -> {
                double pole = radius * .68;
                out.line(at.subtract(pole, 0, 0), at, size * 1.4, 0xffce858e);
                out.line(at, at.add(pole, 0, 0), size * 1.4, 0xff82afca);
                for (int i = 0; i < points.length; i++) {
                    Vec3 local = previous[i].lerp(points[i], partial);
                    Vec3 p = at.add(local), tangent = dipole(local, pole).scale(size);
                    out.line(p.subtract(tangent), p.add(tangent), size * .38, color);
                }
            }
            case "tentacles" -> {
                for (int i = 0; i < points.length; i++) {
                    int k = i % chainNodes;
                    if (k == 0) continue;
                    Vec3 a = previous[i - 1].lerp(points[i - 1], partial);
                    Vec3 b = previous[i].lerp(points[i], partial);
                    out.line(a, b, size * (1 - .7 * k / chainNodes), color);
                }
            }
            case "droplet" -> {
                double stretch = lastStrain + (strain - lastStrain) * partial;
                ellipsoid(out, at, heading, size * stretch, size / Math.sqrt(stretch), color);
            }
            case "bird" -> {
                SpriteSheet sheet = birdSheet();
                if (sheet != null) out.sprite(sheet, at, size, (poseTicks + partial) / 20, color);
            }
        }
    }

    private void drawPlanet(EffectFrame out, Vec3 at, double partial) {
        double tilt = Math.toRadians(options.number("tiltDegrees", 32, -180, 180));
        double yaw = Math.toRadians(options.number("yawDegrees", 18, -360, 360)
                + (ticks + partial) * options.number("precessionDegrees", .6, -20, 20));
        Vec3 normal = new Vec3(Math.sin(tilt) * Math.cos(yaw), Math.cos(tilt), Math.sin(tilt) * Math.sin(yaw));
        Vec3 u = perpendicular(normal), v = normal.cross(u);
        double width = Math.min(radius * .8, options.number("ringWidth", .026, .002, .25));
        int segments = options.count("segments", 28, 8, 48);
        Vec3 last = u;
        for (int i = 1; i <= segments; i++) {
            Vec3 next = around(u, v, i * TAU / segments);
            out.quad(at.add(last.scale(radius)), at.add(next.scale(radius)),
                    at.add(next.scale(radius - width)), at.add(last.scale(radius - width)), color);
            last = next;
        }
        ellipsoid(out, at, UP, size * .95, size, options.color("coreColor", 0xff988bbd));
    }

    /** A closed surface keeps the silhouette aligned to the velocity axis from every viewpoint. */
    private static void ellipsoid(EffectFrame out, Vec3 center, Vec3 axis, double along, double across, int tint) {
        Vec3 u = perpendicular(axis), v = axis.cross(u);
        Vec3[] below = new Vec3[10], above = new Vec3[10];
        for (int band = 0; band <= 6; band++) {
            double lat = Math.PI * (band / 6.0 - .5);
            Vec3 middle = center.add(axis.scale(along * Math.sin(lat)));
            for (int i = 0; i < above.length; i++) {
                above[i] = middle.add(around(u, v, TAU * i / above.length).scale(across * Math.cos(lat)));
            }
            if (band != 0) for (int i = 0; i < above.length; i++) {
                int j = (i + 1) % above.length;
                double light = .30 * Math.max(0, Math.cos(TAU * i / above.length - .55) * Math.cos(lat) + .4 * Math.sin(lat));
                int shade = 0xff000000 | TrailEffects.mix(tint, 0xffffff, light);
                out.quad(below[i], below[j], above[j], above[i], shade);
            }
            Vec3[] swap = below;
            below = above;
            above = swap;
        }
    }
}
