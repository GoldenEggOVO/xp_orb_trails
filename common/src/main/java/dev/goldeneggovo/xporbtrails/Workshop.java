package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.ExperienceOrb;

import java.util.LinkedHashMap;
import java.util.Map;

final class Workshop {
    private enum Group {
        PIXEL,
        WORLD,
        MOTION,
        PARTICLES
    }

    private record EffectType(Group group, String page) {}

    private static final Map<String, EffectType> TYPES = Map.ofEntries(
            Map.entry("assemble", new EffectType(Group.PIXEL, "pickup")),
            Map.entry("shed", new EffectType(Group.PIXEL, "trail")),
            Map.entry("directional", new EffectType(Group.PIXEL, "body")),
            Map.entry("relief", new EffectType(Group.PIXEL, "body")),
            Map.entry("fracture", new EffectType(Group.PIXEL, "pickup")),
            Map.entry("school", new EffectType(Group.WORLD, "scene")),
            Map.entry("crossing", new EffectType(Group.WORLD, "trail")),
            Map.entry("current", new EffectType(Group.WORLD, "scene")),
            Map.entry("redstone", new EffectType(Group.WORLD, "scene")),
            Map.entry("fusion", new EffectType(Group.WORLD, "scene")),
            Map.entry("rig", new EffectType(Group.MOTION, "body")),
            Map.entry("brush", new EffectType(Group.MOTION, "trail")),
            Map.entry("morph", new EffectType(Group.MOTION, "trail")),
            Map.entry("hoops", new EffectType(Group.MOTION, "trail")),
            Map.entry("hand", new EffectType(Group.MOTION, "pickup")),
            Map.entry("ink", new EffectType(Group.PARTICLES, "pickup")),
            Map.entry("events", new EffectType(Group.PARTICLES, "pickup")));
    private static JsonElement source;
    private static final Map<String, JsonObject> selected = new LinkedHashMap<>();
    private static final java.util.EnumSet<Group> active = java.util.EnumSet.noneOf(Group.class);

    static void validate(JsonElement value) {
        if (!value.isJsonArray() || value.getAsJsonArray().size() > TYPES.size())
            throw new IllegalArgumentException(
                    "advanced.workshop must be an array of up to " + TYPES.size() + " effects");
        var seen = new java.util.HashSet<String>();
        for (var item : value.getAsJsonArray()) {
            if (!item.isJsonObject())
                throw new IllegalArgumentException("Expected workshop effect object");
            var obj = item.getAsJsonObject();
            String type = AdvancedSettings.requireType(obj, TYPES.keySet(), "workshop");
            if (!seen.add(type))
                throw new IllegalArgumentException("Duplicate workshop effect " + type);
            switch (TYPES.get(type).group()) {
                case PIXEL -> PixelWorkshop.validate(obj);
                case MOTION -> MotionWorkshop.validate(obj);
                case WORLD -> WorldWorkshop.validate(obj);
                case PARTICLES -> EventParticles.validate(obj);
            }
        }
    }

    static void restore(JsonObject updated, JsonObject defaults, String... pages) {
        var target = new com.google.gson.JsonArray();
        var groups = java.util.List.of(pages);
        if (updated.has("workshop"))
            for (var item : updated.getAsJsonArray("workshop")) {
                if (!groups.contains(TYPES.get(item.getAsJsonObject().get("type").getAsString()).page()))
                    target.add(item);
            }
        if (defaults.has("workshop"))
            for (var item : defaults.getAsJsonArray("workshop")) {
                if (groups.contains(TYPES.get(item.getAsJsonObject().get("type").getAsString()).page()))
                    target.add(item.deepCopy());
            }
        if (target.isEmpty()) updated.remove("workshop");
        else updated.add("workshop", target);
    }

    static JsonObject options(String type) {
        select();
        return selected.get(type);
    }

    static boolean replacesTrail() {
        return options("morph") != null || selected.containsKey("brush");
    }

    private static void select() {
        var next = XpOrbTrailsClient.CONFIG.advanced.get("workshop");
        if (source == next) return;
        clear();
        source = next;
        if (source == null) return;
        for (var item : source.getAsJsonArray()) {
            var obj = item.getAsJsonObject();
            var type = obj.get("type").getAsString();
            selected.put(type, obj);
            active.add(TYPES.get(type).group());
        }
    }

    static void track(ExperienceOrb orb) {
        select();
        if (active.contains(Group.PIXEL)) PixelWorkshop.track(orb);
        if (active.contains(Group.WORLD)) WorldWorkshop.track(orb);
        if (active.contains(Group.MOTION)) MotionWorkshop.track(orb);
        if (active.contains(Group.PARTICLES)) EventParticles.track(orb);
    }

    static void pickup(ExperienceOrb orb, int collector) {
        select();
        if (active.contains(Group.PIXEL) && XpOrbTrailsClient.CONFIG.pickupFlash)
            PixelWorkshop.pickup(orb, collector);
        if (active.contains(Group.MOTION)) MotionWorkshop.pickup(orb, collector);
        if (active.contains(Group.PARTICLES)) EventParticles.pickup(orb);
    }

    static void extract(EffectFrame out, ClientLevel level, float partial, long frameNanos) {
        select();
        if (active.contains(Group.PIXEL)) PixelWorkshop.extract(out, level, partial, frameNanos);
        if (active.contains(Group.MOTION)) MotionWorkshop.extract(out, level, partial);
        if (active.contains(Group.WORLD)) WorldWorkshop.extract(out, level, partial);
        if (active.contains(Group.PARTICLES)) EventParticles.extract(out, partial);
    }

    static void clear() {
        selected.clear();
        active.clear();
        source = null;
        PixelWorkshop.clear();
        WorldWorkshop.clear();
        MotionWorkshop.clear();
        EventParticles.clear();
    }

    private Workshop() {}
}
