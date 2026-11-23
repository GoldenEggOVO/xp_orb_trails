package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Compiled profile values. Distances are blocks, angles are degrees and curve positions run tail to head. */
final class AdvancedTrailStyle {
    enum Geometry { RIBBON, ARC, CRUMBLE, PARTICLES }
    enum Birth { DISTANCE, EXPIRY }
    record Strand(double phase, double radius, double depth, double width, int color) {}
    record Branches(int count, double length, double angle, double jitter, double flashSeconds) {}
    record Wake(Birth birth, double spacing, int burst, double lifetime, double size, double inheritVelocity,
                double scatter, double gravity, double drag, double turbulence, double frequency,
                double tangentSpeed, int color, int endColor) {}

    final Geometry geometry;
    final double period, roll, orientation, breakAt, breakSpacing;
    final List<Strand> strands;
    final Curve width, opacity, color;
    final Branches branches;
    final Wake particles;

    AdvancedTrailStyle(JsonObject json) {
        String type = string(json.get("geometry"), "geometry");
        geometry = switch (type) {
            case "ribbon" -> Geometry.RIBBON;
            case "arc" -> Geometry.ARC;
            case "crumble" -> Geometry.CRUMBLE;
            case "particles" -> Geometry.PARTICLES;
            default -> throw invalid("geometry", "expected ribbon, arc, crumble or particles");
        };
        fields(json, Set.of("geometry", "period", "roll", "orientation", "breakAt", "breakSpacing", "strands",
                "widthCurve", "opacityCurve", "colorCurve", "branches", "particles"));
        period = number(json, "period", 1, 0.08, 16);
        roll = Math.toRadians(number(json, "roll", 0, -1440, 1440));
        orientation = Math.toRadians(number(json, "orientation", 0, -360, 360));
        breakAt = number(json, "breakAt", 0.72, 0.05, 0.99);
        breakSpacing = number(json, "breakSpacing", 0.18, 0.04, 2);
        width = Curve.read(json.get("widthCurve"), "widthCurve", 4, false);
        opacity = Curve.read(json.get("opacityCurve"), "opacityCurve", 1, false);
        color = Curve.read(json.get("colorCurve"), "colorCurve", 0, true);
        if (json.has("strands")) {
            var list = new ArrayList<Strand>();
            for (var entry : array(json.get("strands"), "strands", 1, 4)) {
                JsonObject s = object(entry, "strands");
                fields(s, Set.of("phase", "radius", "depth", "width", "color"));
                double radius = number(s, "radius", 0, 0, 2);
                list.add(new Strand(Math.toRadians(number(s, "phase", 0, -360, 360)), radius,
                        number(s, "depth", radius, 0, 2), number(s, "width", 1, 0.01, 4), rgb(s.get("color"), -1)));
            }
            strands = List.copyOf(list);
        } else strands = List.of(new Strand(0, 0, 0, 1, -1));
        if (json.has("branches") && geometry != Geometry.ARC) throw invalid("branches", "requires arc geometry");
        JsonObject b = json.has("branches") ? object(json.get("branches"), "branches") : new JsonObject();
        fields(b, Set.of("count", "length", "minTurn", "jitter", "flashSeconds"));
        branches = new Branches(integer(b, "count", 3, 0, 6), number(b, "length", 0.35, 0.02, 2),
                Math.toRadians(number(b, "minTurn", 8, 1, 180)), number(b, "jitter", 0.035, 0, 0.5),
                number(b, "flashSeconds", 0.12, 0.04, 1));
        if (json.has("particles")) {
            JsonObject p = object(json.get("particles"), "particles");
            fields(p, Set.of("birth", "spacing", "burst", "lifetime", "size", "inheritVelocity", "scatter", "gravity",
                    "drag", "turbulence", "frequency", "tangentSpeed", "color", "endColor"));
            Birth birth = switch (string(p.get("birth"), "particles.birth")) {
                case "distance" -> Birth.DISTANCE;
                case "expiry" -> Birth.EXPIRY;
                default -> throw invalid("particles.birth", "expected distance or expiry");
            };
            particles = new Wake(birth, number(p, "spacing", 0.15, 0.03, 4), integer(p, "burst", 1, 1, 4),
                    number(p, "lifetime", 0.7, 0.05, 3), number(p, "size", 0.035, 0.005, 0.5),
                    number(p, "inheritVelocity", 0, 0, 2), number(p, "scatter", 0.2, 0, 5),
                    number(p, "gravity", 0, -20, 20), number(p, "drag", 0, 0, 16),
                    number(p, "turbulence", 0, 0, 4), number(p, "frequency", 2, 0.1, 12),
                    number(p, "tangentSpeed", 0.4, -5, 5), rgb(p.get("color"), 0xFFFFFF), rgb(p.get("endColor"), -1));
        } else particles = null;
        if (geometry == Geometry.PARTICLES && particles == null)
            throw invalid("particles", "particle-only geometry requires an emitter");
    }

    int color(TrailConfig cfg, double u, double seconds) {
        return color == null ? TrailEffects.colorAt(cfg, u, seconds, 0.88F) : (int) color.at(u);
    }

    record Curve(double[] positions, double[] values, boolean colors) {
        static Curve read(JsonElement data, String name, double max, boolean colors) {
            if (data == null) return null;
            JsonArray stops = array(data, name, 1, 16);
            double[] x = new double[stops.size()], y = new double[stops.size()];
            for (int i = 0; i < x.length; i++) {
                JsonArray stop = array(stops.get(i), name, 2, 2);
                x[i] = number(stop.get(0), name, 0, 1);
                if (i > 0 && x[i] <= x[i - 1]) throw invalid(name, "positions must increase");
                y[i] = colors ? rgb(stop.get(1), -1) : number(stop.get(1), name, 0, max);
            }
            return new Curve(x, y, colors);
        }

        double at(double u) {
            if (u <= positions[0]) return values[0];
            for (int i = 1; i < positions.length; i++) {
                if (u <= positions[i]) {
                    double f = (u - positions[i - 1]) / (positions[i] - positions[i - 1]);
                    return colors ? TrailEffects.mix((int) values[i - 1], (int) values[i], f)
                            : values[i - 1] + (values[i] - values[i - 1]) * f;
                }
            }
            return values[values.length - 1];
        }
    }

    private static void fields(JsonObject obj, Set<String> names) {
        for (String key : obj.keySet()) if (!names.contains(key)) throw invalid(key, "unknown field");
    }

    private static int rgb(JsonElement element, int fallback) {
        if (element == null) return fallback;
        String s = string(element, "color");
        if (!s.matches("#[a-fA-F0-9]{6}")) throw invalid("color", "expected #RRGGBB");
        return Integer.parseInt(s.substring(1), 16);
    }

    private static JsonObject object(JsonElement e, String key) {
        if (!e.isJsonObject()) throw invalid(key, "expected object");
        return e.getAsJsonObject();
    }

    private static JsonArray array(JsonElement e, String key, int min, int max) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() < min || e.getAsJsonArray().size() > max)
            throw invalid(key, "expected " + min + " to " + max + " entries");
        return e.getAsJsonArray();
    }

    private static String string(JsonElement e, String key) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw invalid(key, "expected string");
        return e.getAsString();
    }

    private static int integer(JsonObject o, String key, int fallback, int min, int max) {
        double x = number(o, key, fallback, min, max);
        if (x != Math.rint(x)) throw invalid(key, "expected integer");
        return (int) x;
    }

    private static double number(JsonObject o, String key, double fallback, double min, double max) {
        return o.has(key) ? number(o.get(key), key, min, max) : fallback;
    }

    private static double number(JsonElement e, String key, double min, double max) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw invalid(key, "expected number");
        double x = e.getAsDouble();
        if (!Double.isFinite(x) || x < min || x > max) throw invalid(key, "expected " + min + " to " + max);
        return x;
    }

    private static IllegalArgumentException invalid(String key, String message) {
        return new IllegalArgumentException("advanced.trail." + key + ": " + message);
    }
}
