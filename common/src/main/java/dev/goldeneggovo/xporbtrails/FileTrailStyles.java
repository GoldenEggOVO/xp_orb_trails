package dev.goldeneggovo.xporbtrails;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

final class FileTrailStyles {
    record Style(String name, String nameKey, TrailConfig.SavedProfile settings) {
        Component displayName() {
            return nameKey == null ? Component.literal(name) : Component.translatableWithFallback(nameKey, name);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("XP Orb Trails");
    private static final String RESOURCES = "/assets/xporbtrails/profiles/";
    private static final Pattern RGB = Pattern.compile("#[0-9a-fA-F]{6}");
    private final Path directory;
    private final Supplier<TrailConfig> defaults;
    private List<Style> styles = List.of();

    FileTrailStyles(Path assetDirectory, Supplier<TrailConfig> defaults) {
        directory = assetDirectory.resolve("profiles");
        this.defaults = defaults;
    }

    List<Style> styles() {
        return styles;
    }

    int reload() {
        int errors = 0;
        try {
            Files.createDirectories(directory);
            AssetFiles.installTemplates(directory, directory.resolve(".installed"), RESOURCES);
        } catch (IOException | UncheckedIOException exception) {
            LOGGER.warn("Could not install bundled trail profiles: {}", exception.getMessage());
            errors++;
        }
        List<Style> loaded = new ArrayList<>();
        List<Path> files;
        try {
            files = jsonFiles(directory);
        } catch (IOException | UncheckedIOException exception) {
            LOGGER.warn("Could not read trail profiles directory {}: {}", directory, exception.getMessage());
            return errors + 1;
        }
        for (Path file : files) {
            try {
                loaded.add(read(file));
            } catch (IOException exception) {
                LOGGER.warn("Skipping trail style {}: {}", file, exception.getMessage());
                errors++;
            }
        }
        styles = List.copyOf(loaded);
        return errors;
    }

    private static List<Path> jsonFiles(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(Files::isRegularFile).sorted(Comparator.comparing(Path::getFileName)).toList();
        }
    }

    private Style read(Path file) throws IOException {
        TrailConfig config = defaults.get();
        String filename = file.getFileName().toString();
        String name = filename.substring(0, filename.length() - ".json".length());
        String nameKey = null;
        Set<String> seen = new HashSet<>();
        try (JsonReader json = new JsonReader(Files.newBufferedReader(file))) {
            json.setStrictness(Strictness.STRICT);
            expect(json, JsonToken.BEGIN_OBJECT);
            json.beginObject();
            while (json.hasNext()) {
                String field = json.nextName();
                if (!seen.add(field)) throw new IOException("Duplicate field " + field);
                switch (field) {
                    case "version" -> integer(json, 1, 1);
                    case "name" -> name = string(json).strip();
                    case "nameKey" -> nameKey = string(json).strip();
                    case "colorMode" -> config.colorMode = choice(json, List.of("solid", "gradient", "rainbow"));
                    case "startColor" -> config.startColor = color(json);
                    case "endColor" -> config.endColor = color(json);
                    case "gradientStops" -> config.gradientStops = gradientStops(json);
                    case "rainbowSpeed" -> config.rainbowSpeed = number(json, 0.02, 2.0);
                    case "width" -> config.width = number(json, 0.02, 1.0);
                    case "crossSectionSides" -> config.crossSectionSides = integer(json, 2, 32);
                    case "crossSectionRadii" -> config.crossSectionRadii = CrossSectionRadii.readProfile(json);
                    case "customCrossSectionEnabled" -> config.customCrossSectionEnabled = bool(json);
                    case "tailWidthScale" -> config.tailWidthScale = number(json, 0, 1.5);
                    case "middleWidthScale" -> config.middleWidthScale = number(json, 0, 2.0);
                    case "headWidthScale" -> config.headWidthScale = number(json, 0.05, 2.0);
                    case "lifetimeSeconds" -> config.lifetimeSeconds = number(json, 0.25, 10.0);
                    case "pickupFadeSeconds" -> config.pickupFadeSeconds = number(json, 0.05, 3.0);
                    case "effectStrength" -> config.effectStrength = number(json, 0.005, 2.0);
                    case "additiveGlow" -> config.additiveGlow = bool(json);
                    case "motionShift" -> config.motionShift = number(json, 0, 1.0);
                    case "cameraPush" -> config.cameraPush = number(json, 0, 1.0);
                    case "smoothFlow" -> config.smoothFlow = number(json, 0, 1.0);
                    case "pickupFlash" -> config.pickupFlash = bool(json);
                    case "pickupFlashStyle" -> config.pickupFlashStyle = effect(json, AssetRole.PICKUP);
                    case "pickupFlashStrength" -> config.pickupFlashStrength = number(json, 0.1, 2.0);
                    case "pickupFlashSeconds" -> config.pickupFlashSeconds = number(json, 0.08, 1.0);
                    case "pickupFlashSize" -> config.pickupFlashSize = number(json, 0.02, 8.34);
                    case "flightPulse" -> config.flightPulse = bool(json);
                    case "flightEffect" -> config.flightEffect = effect(json, AssetRole.ORB);
                    case "showVanillaOrb" -> config.showVanillaOrb = bool(json);
                    case "orbScale" -> config.orbScale = number(json, 0.25, 4.0);
                    case "trailSpriteMode" -> config.trailSpriteMode = choice(json, List.of("stamps", "ribbon"));
                    case "fixedStamps" -> config.fixedStamps = bool(json);
                    case "ribbonMapping" -> config.ribbonMapping = choice(json, TrailConfig.RIBBON_MAPPINGS);
                    case "ribbonRepeatLength" -> config.ribbonRepeatLength = number(json, 0.1, 4.0);
                    case "stampSpacing" -> config.stampSpacing = number(json, 0.25, 10.0);
                    case "stampScale" -> config.stampScale = number(json, 0.20, 10.0);
                    case "trailSprite" -> {
                        String id = string(json);
                        if (!TrailConfig.validTrailArtwork(id)) throw new IOException("trailSprite must be none, body, or sprite:trail/<id>");
                        config.trailSprite = id;
                    }
                    case "pulseSpeed" -> config.pulseSpeed = number(json, 0.1, 4.0);
                    case "shardCount" -> config.shardCount = integer(json, 3, 16);
                    case "spiralTurns" -> config.spiralTurns = number(json, 0.15, 2.0);
                    default -> throw new IOException("Unknown style field " + field);
                }
            }
            json.endObject();
            expect(json, JsonToken.END_DOCUMENT);
        }
        if (!seen.contains("version")) throw new IOException("Missing required field version (expected 1)");
        if (name.isBlank()) throw new IOException("Style name must contain visible text");
        if (nameKey != null && nameKey.isBlank()) throw new IOException("Style nameKey must contain visible text");
        config.resolveCrossSection(seen.contains("crossSectionSides"), seen.contains("crossSectionRadii"),
                seen.contains("customCrossSectionEnabled"));
        return new Style(name, seen.contains("name") ? null : nameKey, new TrailConfig.SavedProfile(name, config));
    }

    private static int[] gradientStops(JsonReader json) throws IOException {
        expect(json, JsonToken.BEGIN_ARRAY);
        int[] colors = new int[8];
        int count = 0;
        json.beginArray();
        while (json.hasNext()) {
            if (count == colors.length) throw new IOException("gradientStops accepts at most eight colors");
            colors[count++] = color(json);
        }
        json.endArray();
        return Arrays.copyOf(colors, count);
    }

    private static int color(JsonReader json) throws IOException {
        String value = string(json);
        if (!RGB.matcher(value).matches()) throw new IOException(json.getPreviousPath() + " must be a #RRGGBB color");
        return Integer.parseInt(value.substring(1), 16);
    }

    private static String choice(JsonReader json, List<String> choices) throws IOException {
        String value = string(json);
        if (!choices.contains(value)) throw new IOException(json.getPreviousPath() + " must be one of " + choices);
        return value;
    }

    private static String effect(JsonReader json, AssetRole role) throws IOException {
        String value = string(json);
        if (!(role == AssetRole.ORB && "none".equals(value)) && !TrailConfig.isExternalEffect(value, role)) {
            throw new IOException(json.getPreviousPath() + " must reference a compatible effect or sprite");
        }
        return value;
    }

    private static String string(JsonReader json) throws IOException {
        expect(json, JsonToken.STRING);
        return json.nextString();
    }

    private static boolean bool(JsonReader json) throws IOException {
        expect(json, JsonToken.BOOLEAN);
        return json.nextBoolean();
    }

    private static double number(JsonReader json, double min, double max) throws IOException {
        expect(json, JsonToken.NUMBER);
        double value = json.nextDouble();
        if (value < min || value > max) throw new IOException(json.getPreviousPath() + " must be between " + min + " and " + max);
        return value;
    }

    private static int integer(JsonReader json, int min, int max) throws IOException {
        expect(json, JsonToken.NUMBER);
        int value;
        try {
            value = new BigDecimal(json.nextString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IOException(json.getPreviousPath() + " must be an integer");
        }
        if (value < min || value > max) {
            String range = min == max ? String.valueOf(min) : "between " + min + " and " + max;
            throw new IOException(json.getPreviousPath() + " must be " + range);
        }
        return value;
    }

    private static void expect(JsonReader json, JsonToken expected) throws IOException {
        if (json.peek() != expected) throw new IOException(json.getPath() + " expected " + expected + ", found " + json.peek());
    }
}
