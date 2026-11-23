package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Set;

final class WorldWorkshopStyle {
    private static final Map<String, Set<String>> FIELDS = Map.of(
            "school", Set.of("count", "spacing", "size", "speed", "sprite"),
            "crossing", Set.of("tolerance", "memoryTicks", "lifetimeTicks"),
            "current", Set.of("perTick", "speed", "lifetimeTicks"),
            "redstone", Set.of("count", "radius", "response"),
            "fusion", Set.of("range", "radius"));
    static final Set<String> TYPES = FIELDS.keySet();
    final int count, perTick, memoryTicks, lifetimeTicks, color, secondary;
    final double range, radius, spacing, size, speed, tolerance;
    final boolean opens, brightens;
    final String sprite;

    WorldWorkshopStyle(JsonObject json) {
        String type = AdvancedSettings.requireType(json, TYPES, "workshop");
        for (String key : json.keySet()) {
            if (!key.equals("type") && !key.equals("color") && !key.equals("secondary") && !FIELDS.get(type).contains(key))
                throw new IllegalArgumentException("Unknown " + type + " option " + key);
        }
        count = integer(json, "count", 6, 2, 12);
        perTick = integer(json, "perTick", 1, 1, 3);
        memoryTicks = integer(json, "memoryTicks", 20, 2, 50);
        lifetimeTicks = integer(json, "lifetimeTicks", 16, 4, 48);
        range = number(json, "range", 1.6, .35, 3);
        radius = number(json, "radius", type.equals("fusion") ? .13 : .34, .04, .65);
        spacing = number(json, "spacing", .24, .08, .6);
        size = number(json, "size", .11, .04, .25);
        speed = number(json, "speed", .13, .025, .4);
        tolerance = number(json, "tolerance", .12, .015, .3);
        color = color(json, "color", 0xff8aedcf);
        secondary = color(json, "secondary", 0xffffedac);
        var response = json.get("response");
        if (response != null && (!response.isJsonPrimitive() || !response.getAsJsonPrimitive().isString()
                || !Set.of("both", "opening", "brightness").contains(response.getAsString())))
            throw new IllegalArgumentException("response must be both, opening or brightness");
        opens = response == null || !response.getAsString().equals("brightness");
        brightens = response == null || !response.getAsString().equals("opening");
        var sheet = json.get("sprite");
        if (sheet != null && (!sheet.isJsonPrimitive() || !sheet.getAsJsonPrimitive().isString()
                || !(sheet.getAsString().equals("none") || SpriteLibrary.isSpriteId(sheet.getAsString()) && AssetRole.ORB.accepts(sheet.getAsString()))))
            throw new IllegalArgumentException("school sprite must use sprite:orb/name or none");
        sprite = sheet == null ? "none" : sheet.getAsString();
    }

    private static double number(JsonObject json, String key, double fallback, double min, double max) {
        JsonElement value = json.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())
                || value.getAsDouble() < min || value.getAsDouble() > max)
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        return value.getAsDouble();
    }

    private static int integer(JsonObject json, String key, int fallback, int min, int max) {
        double value = number(json, key, fallback, min, max);
        if (value != Math.rint(value)) throw new IllegalArgumentException(key + " must be an integer");
        return (int) value;
    }

    private static int color(JsonObject json, String key, int fallback) {
        JsonElement value = json.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !value.getAsString().matches("#[a-fA-F0-9]{6}"))
            throw new IllegalArgumentException(key + " must use #RRGGBB");
        return 0xff000000 | Integer.parseUnsignedInt(value.getAsString().substring(1), 16);
    }
}
