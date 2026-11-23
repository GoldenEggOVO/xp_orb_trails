package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Set;

final class AdvancedSettings {
    private static final Set<String> BODY =
            Set.of("planet", "firefly", "magnet", "tentacles", "droplet", "bird");
    private static final Set<String> PICKUP =
            Set.of("petals", "fireworks", "ripple", "rebound", "vortex");

    static void validate(JsonObject settings) {
        for (var e : settings.entrySet()) {
            String key = e.getKey();
            JsonElement value = e.getValue();
            if (key.equals("workshop")) {
                Workshop.validate(value);
                continue;
            }
            if (key.equals("scene")) {
                SceneRule.parse(value);
                continue;
            }
            if (!value.isJsonObject())
                throw new IllegalArgumentException("advanced." + key + " must be an object");
            var obj = value.getAsJsonObject();
            switch (key) {
                case "body" -> requireType(obj, BODY, key);
                case "pickup" -> requireType(obj, PICKUP, key);
                case "trail" -> AdvancedTrails.validate(obj);
                case "optics" -> requireType(obj, Set.of("heat", "light"), key);
                default -> throw new IllegalArgumentException("Unknown advanced field " + key);
            }
        }
    }

    static String requireType(JsonObject obj, Set<String> types, String section) {
        var v = obj.get("type");
        if (v == null
                || !v.isJsonPrimitive()
                || !v.getAsJsonPrimitive().isString()
                || !types.contains(v.getAsString()))
            throw new IllegalArgumentException("Unknown or missing advanced." + section + ".type");
        return v.getAsString();
    }

    private AdvancedSettings() {}
}
