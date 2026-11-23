package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonElement;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;

final class MotionWorkshopPath {
    record Node(Vec3 p, Vec3 tangent, Vec3 normal, double distance, double time, double pressure, double tilt) {}
    record Ring(Vec3 p, Vec3 tangent, double distance) {}
    final List<Node> nodes = new ArrayList<>();
    final List<Ring> rings = new ArrayList<>();
    private Lighting replay;

    record Lighting(double[] stops, double[] traveled, double[] arrival, double length) {
        double distanceAt(double u) {
            double d = Math.clamp(u, 0, 1) * length;
            for (int i = 1; i < stops.length; i++) {
                double span = traveled[i] - traveled[i - 1];
                if (span > 1e-8 && traveled[i] >= d)
                    return stops[i - 1] + (stops[i] - stops[i - 1]) * (d - traveled[i - 1]) / span;
            }
            return stops[stops.length - 1];
        }
    }

    Lighting sequence(JsonElement order) {
        int count = rings.size();
        var indices = new LinkedHashSet<Integer>();
        boolean forward = order != null && order.isJsonPrimitive() && order.getAsString().equals("forward");
        if (order != null && order.isJsonArray()) {
            for (var n : order.getAsJsonArray()) {
                int i = n.getAsInt();
                if (i < 0) i += count;
                if (i >= 0 && i < count) indices.add(i);
            }
        }
        for (int i = 0; i < count; i++) indices.add(forward ? i : count - i - 1);
        double head = nodes.getLast().distance, tail = nodes.getFirst().distance;
        double[] stops = new double[count + 2], traveled = new double[count + 2], arrival = new double[count];
        stops[0] = forward ? tail : head;
        stops[count + 1] = forward ? head : tail;
        int slot = 1;
        for (int index : indices) stops[slot++] = rings.get(index).distance;
        for (int i = 1; i < stops.length; i++) traveled[i] = traveled[i - 1] + Math.abs(stops[i] - stops[i - 1]);
        double length = traveled[traveled.length - 1];
        slot = 1;
        for (int index : indices) arrival[index] = traveled[slot++] / Math.max(1e-8, length);
        return new Lighting(stops, traveled, arrival, length);
    }

    void sample(Vec3 p, double time, OrbEffectOptions pen, OrbEffectOptions hoops, double lifetime) {
        if (!nodes.isEmpty() && (p.distanceToSqr(nodes.getLast().p) > 64 || time < nodes.getLast().time)) {
            nodes.clear();
            rings.clear();
        }
        while (nodes.size() > 1 && time - nodes.getFirst().time > lifetime) nodes.removeFirst();
        if (!nodes.isEmpty() && time - nodes.getLast().time > lifetime) {
            nodes.clear();
            rings.clear();
        } else if (!nodes.isEmpty()) {
            while (!rings.isEmpty() && rings.getFirst().distance < nodes.getFirst().distance) rings.removeFirst();
        }
        if (nodes.isEmpty()) {
            nodes.add(new Node(p, Vec3.ZERO, Vec3.Y_AXIS, 0, time, 1, 0));
            return;
        }
        Node prev = nodes.getLast();
        Vec3 delta = p.subtract(prev.p);
        double distance = delta.length();
        if (distance < .012) return;
        Vec3 t = delta.scale(1 / distance);
        boolean first = prev.tangent.lengthSqr() < .5;
        Vec3 n = first ? AdvancedTrails.initialNormal(t) : AdvancedTrails.transport(prev.normal, prev.tangent, t);
        double turn = first ? 0 : Math.acos(Math.clamp(t.dot(prev.tangent), -1, 1));
        double speed = distance / Math.max(1, time - prev.time);
        double response = 1 - pen.number("memory", .48, 0, .95);
        double target = .22 + .88 / (1 + pen.number("speedSensitivity", 6, 0, 100) * speed)
                + pen.number("turnPressure", 1, 0, 4) * turn / Math.PI;
        Vec3 bendAxis = first ? Vec3.ZERO : prev.tangent.cross(t);
        double bendSign = bendAxis.dot(prev.normal);
        if (Math.abs(bendSign) < 1e-5) bendSign = bendAxis.dot(prev.tangent.cross(prev.normal));
        double bend = Math.copySign(bendAxis.length(), bendSign);
        double tilt = prev.tilt + response * (bend * Math.toRadians(pen.number("tiltDegrees", 75, 0, 180)) - prev.tilt);
        double pressure = prev.pressure + response * (target - prev.pressure);
        double end = prev.distance + distance;
        if (first) nodes.set(0, new Node(prev.p, t, n, prev.distance, prev.time, prev.pressure, prev.tilt));
        nodes.add(new Node(p, t, n, end, time, pressure, tilt));
        if (nodes.size() > 80) nodes.removeFirst();
        if (hoops != OrbEffectOptions.EMPTY) {
            double spacing = hoops.number("spacing", .40, .08, 4);
            for (double d = (Math.floor((prev.distance + 1e-8) / spacing) + 1) * spacing; d <= end + 1e-8; d += spacing)
                rings.add(new Ring(prev.p.lerp(p, (d - prev.distance) / distance), t, d));
            double start = nodes.getFirst().distance;
            while (!rings.isEmpty() && (rings.size() > hoops.count("maxRings", 18, 2, 48) || rings.getFirst().distance < start))
                rings.removeFirst();
        }
    }

    void drawBrush(EffectFrame out, OrbEffectOptions o, TrailConfig cfg, double now, Vec3 head) {
        Vec3 lastLeft = null, lastRight = null;
        double angle = Math.toRadians(o.number("nibDegrees", 42, -180, 180));
        double width = o.number("width", cfg.width, .005, 1);
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            double life = opacity(node, now, cfg);
            Vec3 axis = OrbEffectOptions.around(node.normal, node.tangent.cross(node.normal), angle + node.tilt);
            Vec3 center = i + 1 == nodes.size() && head != null ? head : node.p;
            Vec3 side = axis.scale(width * node.pressure * .5);
            Vec3 left = center.add(side), right = center.subtract(side);
            if (lastLeft != null && life > 0) {
                int color = OrbEffectOptions.fade(o.color("color", cfg.gradientColor(progress(node))), life);
                out.quad(lastLeft, left, right, lastRight, color);
            }
            lastLeft = left;
            lastRight = right;
        }
    }

    void drawMorph(EffectFrame out, OrbEffectOptions o, TrailConfig cfg, double now, Vec3 head) {
        if (nodes.size() < 2) return;
        int sides = cfg.usesCustomCrossSection() ? cfg.crossSectionRadii.length : cfg.crossSectionSides;
        double radius = o.number("radius", cfg.width * .5, .005, 1);
        int tips = o.count("starPoints", 4, 2, 12);
        double inner = o.number("innerRadius", .35, .05, 1), flat = o.number("flatness", .045, .001, 1);
        double middle = o.number("starPosition", .50, .05, .95);
        Vec3[] previous = null;
        for (int j = 0; j < nodes.size(); j++) {
            Node node = nodes.get(j);
            double fromHead = 1 - progress(node);
            Vec3 center = head != null && j == nodes.size() - 1 ? head : node.p;
            Vec3 b = node.tangent.cross(node.normal);
            Vec3[] ring = new Vec3[sides];
            int color = OrbEffectOptions.fade(cfg.gradientColor(1 - fromHead), opacity(node, now, cfg));
            for (int i = 0; i < sides; i++) {
                double theta = Math.TAU * i / sides;
                Vec3 section = section(theta, fromHead, tips, inner, flat, middle);
                double r = radius * (cfg.usesCustomCrossSection() ? cfg.crossSectionRadii[i] : 1);
                ring[i] = center.add(node.normal.scale(section.x * r)).add(b.scale(section.y * r));
            }
            if (previous != null) {
                for (int i = 0; i < (sides == 2 ? 1 : sides); i++) {
                    int next = (i + 1) % sides;
                    out.quad(previous[i], ring[i], ring[next], previous[next], color);
                }
            }
            previous = ring;
        }
    }

    static Vec3 section(double theta, double u, int tips, double inner, double flat, double middle) {
        double step = Math.PI / tips, phase = theta / step;
        int sector = (int)Math.floor(phase);
        double angle = (phase - sector) * step;
        double a = sector % 2 == 0 ? 1 : inner, b = sector % 2 == 0 ? inner : 1;
        double star = a * b * Math.sin(step) / (a * Math.sin(angle) + b * Math.sin(step - angle));
        double starWeight = u < middle ? u / middle : (1 - u) / (1 - middle);
        double radius = 1 + (star - 1) * starWeight;
        double squash = u < middle ? 1 : 1 - (1 - flat) * ((u - middle) / (1 - middle));
        return new Vec3(radius * Math.cos(theta), radius * Math.sin(theta) * squash, 0);
    }

    double progress(Node node) {
        double first = nodes.getFirst().distance;
        return (node.distance - first) / Math.max(1e-8, nodes.getLast().distance - first);
    }

    private static double opacity(Node n, double now, TrailConfig cfg) {
        return Math.clamp((1 - (now - n.time) / (cfg.lifetimeSeconds * 20)) * cfg.effectStrength, 0, 1);
    }

    Vec3 at(double distance) {
        for (int i = nodes.size() - 1; i > 0; i--) {
            Node a = nodes.get(i - 1), b = nodes.get(i);
            if (distance >= a.distance) return a.p.lerp(b.p, Math.clamp((distance - a.distance) / (b.distance - a.distance), 0, 1));
        }
        return nodes.getFirst().p;
    }

    void drawHoops(EffectFrame out, OrbEffectOptions o, double elapsed) {
        if (rings.isEmpty()) return;
        if (elapsed >= 0 && replay == null) replay = sequence(o.values.get("order"));
        double travel = o.number("replayTicks", 20, 2, 200), after = o.number("expandTicks", 8, 2, 80);
        double radius = o.number("radius", .19, .02, 2), width = o.number("width", .018, .003, .3);
        int resting = o.color("color", 0xff689eb7), lit = o.color("litColor", 0xffffdfa1);
        String sprite = o.text("sprite", "none");
        SpriteSheet sheet = sprite.equals("none") ? null : XpOrbTrailsClient.sprite(AssetRole.TRAIL, sprite);
        for (int index = 0; index < rings.size(); index++) {
            Ring r = rings.get(index);
            double age = elapsed < 0 ? -1 : elapsed - travel * replay.arrival[index];
            if (age >= after) continue;
            double p = Math.max(0, age) / after;
            double size = radius * (1 + p * o.number("expansion", 1.7, 0, 8));
            int color = age < 0 ? OrbEffectOptions.fade(resting, .65) : OrbEffectOptions.fade(lit, 1 - p);
            if (sheet != null) {
                out.decal(sheet, sheet.frameAt(Math.max(0, age) / 20, sheet.loop()), r.p, r.tangent, size * 2, color);
                continue;
            }
            Vec3 normal = AdvancedTrails.initialNormal(r.tangent), binormal = r.tangent.cross(normal);
            Vec3 prev = r.p.add(normal.scale(size));
            for (int i = 1; i <= 16; i++) {
                Vec3 next = r.p.add(OrbEffectOptions.around(normal, binormal, i * Math.TAU / 16).scale(size));
                out.line(prev, next, width, color);
                prev = next;
            }
        }
        if (elapsed >= 0 && elapsed <= travel) {
            Vec3 pos = at(replay.distanceAt(elapsed / travel));
            MotionWorkshop.spark(out, pos, width * 3, lit);
            out.line(at(replay.distanceAt(Math.max(0, elapsed - 1.4) / travel)), pos, width * 1.5, lit);
        }
    }
}
