package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

record ParticleGraph(boolean flight, int interval, String root, Map<String, Emitter> emitters) {
    enum Event {
        BIRTH,
        COLLISION,
        DEATH
    }

    enum Attribute {
        COLOR,
        SIZE,
        VELOCITY
    }

    record Link(String emit, int hit, String surface, Set<Attribute> inherit) {}

    record Emitter(
            int count,
            int life,
            int fadeTicks,
            double size,
            int color,
            String sprite,
            boolean decal,
            boolean ring,
            String face,
            boolean fluids,
            Vec3 velocity,
            double spread,
            double gravity,
            double drag,
            boolean collide,
            double bounce,
            int maxHits,
            Map<Event, List<Link>> links) {}

    static ParticleGraph read(JsonObject item) {
        var context = new OrbEffectOptions(item);
        String trigger = context.text("trigger", "pickup");
        if (!Set.of("pickup", "flight").contains(trigger))
            throw invalid("trigger must be pickup or flight");
        String root = context.text("root", "");
        JsonElement defs = item.get("emitters");
        if (defs == null || !defs.isJsonObject() || defs.getAsJsonObject().size() > 24)
            throw invalid("expected up to 24 named emitters");
        var emitters = new LinkedHashMap<String, Emitter>();
        for (var entry : defs.getAsJsonObject().entrySet()) {
            if (!entry.getKey().matches("[a-z0-9_-]{1,48}") || !entry.getValue().isJsonObject())
                throw invalid("invalid emitter name or definition");
            var obj = entry.getValue().getAsJsonObject();
            var opts = new OrbEffectOptions(obj);
            String kind = opts.text("kind", "particle");
            if (!Set.of("particle", "decal", "ring").contains(kind))
                throw invalid("unknown kind " + kind);
            var links = readLinks(obj.get("on"));
            int lifetime = integer(obj, "lifetime", 18, 1, 200);
            String face = opts.text("face", "any");
            if (!Set.of("any", "floor", "wall", "ceiling").contains(face))
                throw invalid("unknown face " + face);
            var emitter =
                    new Emitter(
                            integer(obj, "count", 1, 1, 64),
                            lifetime,
                            integer(
                                    obj,
                                    "fadeTicks",
                                    Math.max(1, (int) Math.round(lifetime * .35)),
                                    0,
                                    lifetime),
                            number(obj, "size", .065, .004, 3),
                            opts.color("color", 0xffffffff),
                            opts.text("sprite", ""),
                            !kind.equals("particle"),
                            kind.equals("ring"),
                            face,
                            bool(obj, "collideFluids", false),
                            velocity(obj.get("velocity")),
                            number(obj, "spread", .02, 0, 1),
                            number(obj, "gravity", .005, -.5, .5),
                            number(obj, "drag", .98, 0, 1),
                            bool(obj, "collide", links.containsKey(Event.COLLISION)),
                            number(obj, "bounce", .55, 0, 1),
                            integer(obj, "maxHits", 2, 1, 16),
                            links);
            emitters.put(entry.getKey(), emitter);
        }
        if (!emitters.containsKey(root)) throw invalid("root does not name an emitter");
        for (var e : emitters.values())
            for (var links : e.links.values())
                for (var link : links)
                    if (!emitters.containsKey(link.emit))
                        throw invalid("unknown child " + link.emit);
        return new ParticleGraph(
                trigger.equals("flight"),
                integer(item, "interval", 8, 1, 200),
                root,
                Map.copyOf(emitters));
    }

    private static Map<Event, List<Link>> readLinks(JsonElement value) {
        var result = new EnumMap<Event, List<Link>>(Event.class);
        if (value == null) return result;
        if (!value.isJsonObject()) throw invalid("on must be an object");
        for (var event : value.getAsJsonObject().entrySet()) {
            Event key =
                    switch (event.getKey()) {
                        case "birth" -> Event.BIRTH;
                        case "collision" -> Event.COLLISION;
                        case "death" -> Event.DEATH;
                        default -> throw invalid("unknown event " + event.getKey());
                    };
            if (!event.getValue().isJsonArray() || event.getValue().getAsJsonArray().size() > 8)
                throw invalid("on event expects an array of up to 8 links");
            var list = new ArrayList<Link>();
            for (var link : event.getValue().getAsJsonArray()) {
                if (!link.isJsonObject()) throw invalid("child must be an object");
                var o = link.getAsJsonObject();
                String name = new OrbEffectOptions(o).text("emit", "");
                int hit = integer(o, "hit", 0, 0, 16);
                if (hit != 0 && key != Event.COLLISION)
                    throw invalid("hit is only valid on collision");
                var inherit = EnumSet.noneOf(Attribute.class);
                String surface = new OrbEffectOptions(o).text("surface", "any");
                if (!Set.of("any", "water", "lava", "solid").contains(surface))
                    throw invalid("unknown impact surface " + surface);
                if (o.has("inherit")) {
                    if (!o.get("inherit").isJsonArray()) throw invalid("inherit expects an array");
                    for (var a : o.getAsJsonArray("inherit")) {
                        if (!a.isJsonPrimitive() || !a.getAsJsonPrimitive().isString())
                            throw invalid("invalid inherited attribute");
                        inherit.add(
                                switch (a.getAsString()) {
                                    case "color" -> Attribute.COLOR;
                                    case "size" -> Attribute.SIZE;
                                    case "velocity" -> Attribute.VELOCITY;
                                    default -> throw invalid("unknown inherited attribute");
                                });
                    }
                }
                list.add(new Link(name, hit, surface, Set.copyOf(inherit)));
            }
            result.put(key, List.copyOf(list));
        }
        return Map.copyOf(result);
    }

    private static Vec3 velocity(JsonElement value) {
        if (value == null) return Vec3.ZERO;
        if (!value.isJsonArray() || value.getAsJsonArray().size() != 3)
            throw invalid("velocity must contain x, y and z");
        var a = value.getAsJsonArray();
        return new Vec3(
                checked(a.get(0), "velocity", -1, 1),
                checked(a.get(1), "velocity", -1, 1),
                checked(a.get(2), "velocity", -1, 1));
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        if (!o.has(key)) return fallback;
        var v = o.get(key);
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean())
            throw invalid(key + " must be boolean");
        return v.getAsBoolean();
    }

    private static double number(
            JsonObject o, String key, double fallback, double min, double max) {
        return o.has(key) ? checked(o.get(key), key, min, max) : fallback;
    }

    private static int integer(JsonObject o, String key, int fallback, int min, int max) {
        double v = number(o, key, fallback, min, max);
        if (v != Math.rint(v)) throw invalid(key + " must be an integer");
        return (int) v;
    }

    private static double checked(JsonElement element, String key, double min, double max) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
            throw invalid(key + " must be numeric");
        double v = element.getAsDouble();
        if (!Double.isFinite(v) || v < min || v > max)
            throw invalid(key + " outside [" + min + ", " + max + "]");
        return v;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("particle graph: " + message);
    }
}
