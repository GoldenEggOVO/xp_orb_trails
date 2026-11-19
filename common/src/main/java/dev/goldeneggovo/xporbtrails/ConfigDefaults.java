package dev.goldeneggovo.xporbtrails;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ConfigDefaults {
    private static final Logger LOGGER = LoggerFactory.getLogger("XP Orb Trails");
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).setPrettyPrinting().create();
    private final Path file;
    private final JsonObject bundled;
    private JsonObject selected;

    ConfigDefaults(Path assetDirectory) {
        file = assetDirectory.resolve("defaults.json");
        try (var input = ConfigDefaults.class.getResourceAsStream("/assets/xporbtrails/defaults.json")) {
            if (input == null) throw new IllegalStateException("Missing bundled settings defaults");
            bundled = JSON.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), JsonObject.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read bundled settings defaults", exception);
        }
        selected = bundled;
    }

    int reload() {
        try {
            Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                try (var input = ConfigDefaults.class.getResourceAsStream("/assets/xporbtrails/defaults.json")) {
                    try {
                        Files.copy(input, file);
                    } catch (FileAlreadyExistsException ignored) {
                        // Another client installed its copy first.
                    }
                }
            }
            JsonObject merged = bundled.deepCopy();
            try (var reader = Files.newBufferedReader(file)) {
                JsonObject overrides = JSON.fromJson(reader, JsonObject.class);
                if (overrides == null) throw new JsonParseException("Expected a JSON object");
                for (var entry : overrides.entrySet()) {
                    JsonElement value = entry.getValue();
                    if (!bundled.has(entry.getKey())) continue;
                    if (!matchesType(bundled.get(entry.getKey()), value)) {
                        throw new JsonParseException("Incorrect setting type: " + entry.getKey());
                    }
                    merged.add(entry.getKey(), value);
                }
            }
            TrailConfig valid = JSON.fromJson(merged, TrailConfig.class).sanitized();
            if (!merged.getAsJsonArray("crossSectionRadii").isEmpty() && valid.crossSectionRadii.length == 0) {
                throw new JsonParseException("Invalid crossSectionRadii in defaults");
            }
            selected = JSON.toJsonTree(valid).getAsJsonObject();
            return 0;
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            LOGGER.warn("Cannot load setting defaults from {}", file, exception);
            return 1;
        }
    }

    private static boolean matchesType(JsonElement expected, JsonElement value) {
        if (expected.isJsonArray()) return value.isJsonArray();
        if (!value.isJsonPrimitive()) return false;
        JsonPrimitive type = expected.getAsJsonPrimitive();
        JsonPrimitive item = value.getAsJsonPrimitive();
        if (type.isNumber()) return item.isNumber();
        if (type.isBoolean()) return item.isBoolean();
        return item.isString();
    }

    TrailConfig create() {
        return JSON.fromJson(selected, TrailConfig.class);
    }
}
