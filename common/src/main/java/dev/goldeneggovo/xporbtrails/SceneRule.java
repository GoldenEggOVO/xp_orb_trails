package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

record SceneRule(
        String type,
        double radius,
        double width,
        double range,
        double threshold,
        int count,
        double duration,
        int primary,
        int secondary,
        String sound,
        String sprite,
        JsonObject biomes) {
    static final Set<String> TYPES =
            Set.of(
                    "stars",
                    "chain",
                    "combo",
                    "level",
                    "values",
                    "wake",
                    "turn",
                    "speed",
                    "groundcolor",
                    "underwater",
                    "rain",
                    "dusk",
                    "biome",
                    "dust",
                    "capture",
                    "beat");

    static List<SceneRule> parse(JsonElement value) {
        if (value == null) return List.of();
        if (!value.isJsonArray() || value.getAsJsonArray().size() > 8)
            throw new IllegalArgumentException("advanced.scene must be an array of up to 8 rules");
        var list = new ArrayList<SceneRule>();
        var seen = new HashSet<String>();
        for (var element : value.getAsJsonArray()) {
            if (!element.isJsonObject())
                throw new IllegalArgumentException("Scene rule must be an object");
            var obj = element.getAsJsonObject();
            var type = AdvancedSettings.requireType(obj, TYPES, "scene");
            if (!seen.add(type)) throw new IllegalArgumentException("Duplicate scene rule " + type);
            var opt = new OrbEffectOptions(obj);
            double limit =
                    switch (type) {
                        case "combo" -> 1.0;
                        case "turn" -> 28;
                        case "dust" -> .65;
                        case "values" -> 4;
                        default -> .14;
                    };
            JsonElement biomes = obj.get("biomes");
            if (biomes != null && !biomes.isJsonObject())
                throw new IllegalArgumentException(
                        "biomes must map biome IDs or #tags to variants");
            list.add(
                    new SceneRule(
                            type,
                            opt.number("radius", .38, .02, 4),
                            opt.number("width", .032, .005, .3),
                            opt.number("range", 5, .1, 32),
                            opt.number("threshold", limit, .001, 180),
                            opt.count("count", 4, 1, 24),
                            opt.number("duration", 1, .08, 5),
                            opt.color("color", 0xff9ef9ca),
                            opt.color("secondary", 0xffd7acf9),
                            opt.text("sound", "minecraft:entity.experience_orb.pickup"),
                            opt.text("sprite", ""),
                            biomes == null ? new JsonObject() : biomes.getAsJsonObject()));
        }
        return List.copyOf(list);
    }

    static int mix(int a, int b, double t) {
        t = Math.clamp(t, 0, 1);
        return 0xff000000
                | channel(a >> 16, b >> 16, t) << 16
                | channel(a >> 8, b >> 8, t) << 8
                | channel(a, b, t);
    }

    private static int channel(int a, int b, double t) {
        return (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
    }
}
