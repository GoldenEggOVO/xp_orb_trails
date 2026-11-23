package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

final class OrbEffectOptions {
    static final OrbEffectOptions EMPTY = new OrbEffectOptions(new JsonObject());
    static final Vec3 UP = new Vec3(0, 1, 0);
    static final Vec3 RIGHT = new Vec3(1, 0, 0);
    static final double TAU = Math.PI * 2;
    final JsonObject values;
    final String type;
    private static final Map<String, Map<String, String>> DEFAULT_SPRITES = Map.of(
            "firefly", Map.of("sprite", "sprite:orb/atlas-firefly"),
            "bird", Map.of("hoverSprite", "sprite:orb/atlas-bird-hover", "glideSprite", "sprite:orb/atlas-bird-glide"),
            "petals", Map.of("sprite", "sprite:pickup/atlas-sakura"),
            "directional", Map.of("sprite", "sprite:orb/lab-skiff"),
            "assemble", Map.of("sprite", "sprite:orb/luna-moth"));

    OrbEffectOptions(JsonObject values) {
        this.values = values;
        type = text("type", "");
    }

    double number(String key, double fallback, double min, double max) {
        JsonElement value = values.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return fallback;
        double result = value.getAsDouble();
        return Double.isFinite(result) ? Math.clamp(result, min, max) : fallback;
    }

    int count(String key, int fallback, int min, int max) {
        return (int) number(key, fallback, min, max);
    }

    String text(String key, String fallback) {
        JsonElement value = values.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : fallback;
    }

    Map<String, String> defaultSprites() {
        return DEFAULT_SPRITES.getOrDefault(type, Map.of());
    }

    int color(String key, int fallback) {
        String value = text(key, "");
        if (value.length() != 7 || value.charAt(0) != '#') return fallback;
        try {
            return 0xff000000 | Integer.parseUnsignedInt(value, 1, 7, 16);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static Vec3 perpendicular(Vec3 normal) {
        return normal.cross(Math.abs(normal.y) < 0.9 ? UP : RIGHT).normalize();
    }

    static int fade(int color, double opacity) {
        return (color & 0xffffff) | (Math.clamp((int) (255 * opacity), 0, 255) << 24);
    }

    static Vec3 around(Vec3 u, Vec3 v, double angle) {
        return u.scale(Math.cos(angle)).add(v.scale(Math.sin(angle)));
    }
}
