package dev.goldeneggovo.xporbtrails;

import net.minecraft.world.phys.Vec3;

import java.util.function.BiPredicate;

import static dev.goldeneggovo.xporbtrails.OrbEffectOptions.*;

final class WorldWorkshopGeometry {
    /** Closest points on two finite segments, restricted to transverse crossings. */
    static Vec3 crossing(Vec3 p, Vec3 q, Vec3 r, Vec3 s, double tolerance) {
        Vec3 u = q.subtract(p), v = s.subtract(r), w = p.subtract(r);
        double a = u.lengthSqr(), b = u.dot(v), c = v.lengthSqr();
        double determinant = a * c - b * b;
        if (a < .0001 || c < .0001 || determinant < a * c * .12) return null;
        double d = u.dot(w), e = v.dot(w);
        double t = Math.clamp((b * e - c * d) / determinant, 0, 1);
        double h = Math.clamp((b * t + e) / c, 0, 1);
        t = Math.clamp((b * h - d) / a, 0, 1);
        Vec3 first = p.add(u.scale(t)), second = r.add(v.scale(h));
        return first.distanceToSqr(second) <= tolerance * tolerance ? first.lerp(second, .5) : null;
    }

    static final class Fish {
        Vec3 previous, position, heading;
        private Vec3 velocity = Vec3.ZERO;

        Fish(Vec3 position, Vec3 heading) {
            this.position = this.previous = position;
            this.heading = heading;
        }

        void swim(Vec3 target, Vec3 right, int lane, double speed, BiPredicate<Vec3, Vec3> clear) {
            previous = position;
            Vec3 offset = target.subtract(position);
            if (offset.lengthSqr() < .0001) return;
            Vec3 forward = offset.normalize();
            double reach = Math.min(offset.length(), .65);
            if (!clear.test(position, position.add(forward.scale(reach)))) {
                // On opposite sides of a school, fish choose opposite routes first.
                Vec3 side = right.scale(lane);
                Vec3 turn = side.add(forward.scale(.36)).normalize();
                if (!clear.test(position, position.add(turn.scale(.32)))) turn = side;
                if (!clear.test(position, position.add(turn.scale(.32)))) turn = UP;
                velocity = turn.scale(speed);
            } else {
                velocity = velocity.lerp(forward.scale(Math.min(speed, offset.length() * .45)), .45);
            }
            if (velocity.lengthSqr() < 1e-8) return;
            Vec3 next = position.add(velocity);
            if (clear.test(position, next.add(velocity.normalize().scale(.045)))) {
                position = next;
                heading = velocity.normalize();
            } else velocity = Vec3.ZERO;
        }

        void draw(EffectFrame out, float partial, double phase, double size, int color, int trim, Vec3 camera) {
            Vec3 p = previous.lerp(position, partial);
            Vec3 side = heading.cross(camera.subtract(p)).normalize();
            if (side.lengthSqr() < .1) side = perpendicular(heading);
            Vec3 nose = p.add(heading.scale(size));
            Vec3 gill = p.add(heading.scale(size * .18)), sideFin = side.scale(size * .42);
            Vec3 tail = p.subtract(heading.scale(size * .72));
            Vec3 fin = tail.subtract(heading.scale(size * .66)).add(side.scale(Math.sin(phase) * size * .17));
            out.quad(nose, gill.add(sideFin), tail, gill.subtract(sideFin), color);
            out.quad(tail, fin.add(sideFin), fin.add(heading.scale(size * .18)), fin.subtract(sideFin), trim);
            out.line(nose.lerp(gill, .66), gill, size * .14, trim);
        }
    }

    static void redstone(EffectFrame out, Vec3 p, int power, WorldWorkshopStyle style) {
        double t = Math.clamp(power, 0, 15) / 15.;
        double open = style.opens ? t : .6, brightness = style.brightens ? t : .85;
        double radius = style.radius;
        Vec3 root = p.add(0, -.10, 0);
        double spread = radius * (.12 + .88 * open);
        int color = fade(SceneRule.mix(style.color, style.secondary, brightness), .34 + .66 * brightness);
        for (int i = 0; i < style.count; i++) {
            double angle = TAU * i / style.count;
            Vec3 radial = new Vec3(Math.cos(angle), 0, Math.sin(angle));
            Vec3 tip = root.add(radial.scale(spread)).add(0, radius * (1.25 - .94 * open), 0);
            Vec3 mid = root.lerp(tip, .57).add(radial.scale(radius * .09));
            Vec3 side = radial.cross(UP).scale(radius * (.045 + .14 * open));
            out.quad(root, mid.add(side), tip, mid.subtract(side), color);
        }
    }

    static double waist(double distance, double range, double radius) {
        double closeness = Math.clamp(1 - distance / range, 0, 1);
        return radius * Math.sqrt(closeness) * (.55 + .45 * closeness);
    }

    static void fusion(EffectFrame out, Vec3 a, Vec3 b, double range, double radius, int color, int glow) {
        double distance = a.distanceTo(b);
        if (distance <= .03 || distance >= range) return;
        Vec3 axis = b.subtract(a).normalize(), u = perpendicular(axis), v = axis.cross(u);
        double neck = waist(distance, range, radius);
        int skin = fade(color, Math.min(.82, neck / radius * 2));
        Vec3[] last = new Vec3[8];
        for (int ring = 0; ring <= 6; ring++) {
            double t = ring / 6., edge = 2 * t - 1;
            double width = neck + (radius - neck) * edge * edge;
            Vec3 center = a.lerp(b, t);
            Vec3[] next = new Vec3[8];
            for (int i = 0; i < next.length; i++) next[i] = center.add(around(u, v, i * TAU / next.length).scale(width));
            if (ring > 0) {
                for (int i = 0; i < next.length; i++) {
                    int j = (i + 1) % next.length;
                    out.quad(last[i], last[j], next[j], next[i], i == 2 ? fade(glow, (skin >>> 24) / 255.) : skin);
                }
            }
            last = next;
        }
    }

    static void knot(EffectFrame out, Vec3 at, double t, int color, int glint) {
        double opacity = 1 - t;
        for (int i = 0; i < 16; i++) {
            double a = i * TAU / 16, b = (i + 1) * TAU / 16;
            out.line(at.add(knotPoint(a, t)), at.add(knotPoint(b, t)), .022 * opacity, fade(color, opacity));
            if (i % 2 == 0) {
                Vec3 dir = new Vec3(Math.cos(a), Math.sin(3 * a + .4), Math.sin(a)).normalize();
                out.line(at.add(dir.scale(.10 + .28 * t)), at.add(dir.scale(.16 + .40 * t)), .023 * opacity, fade(glint, opacity));
            }
        }
    }

    private static Vec3 knotPoint(double a, double t) {
        return new Vec3(Math.sin(2 * a), Math.sin(3 * a), Math.cos(2 * a)).scale(.10 + .04 * t);
    }

    private WorldWorkshopGeometry() {}
}
