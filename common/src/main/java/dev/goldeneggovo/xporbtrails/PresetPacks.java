package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipFile;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** ZIP paths are valid only during a single reload. Libraries keep parsed data, never these paths. */
final class PresetPacks implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("XP Orb Trails");
    static final String ID_SUFFIX = "(?::[0-9a-f]{32})?";
    private final List<FileSystem> open = new ArrayList<>();
    final List<Profile> profiles = new ArrayList<>();
    final Map<String, Path> sprites = new TreeMap<>();
    final Map<String, Path> effects = new TreeMap<>();

    record Profile(Path file, String pack, Map<String, String> references) {}

    int scan(Path directory) {
        List<Path> archives;
        try {
            Files.createDirectories(directory);
            try (var files = Files.list(directory)) {
                archives = files.filter(p -> Files.isRegularFile(p, NOFOLLOW_LINKS))
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            }
        } catch (IOException | UncheckedIOException e) {
            LOGGER.warn("Could not list XP Orb Trails packs in {}: {}", directory, e.getMessage());
            return 1;
        }
        int errors = 0;
        for (var archive : archives) {
            try {
                load(archive);
            } catch (IOException | IllegalArgumentException | UncheckedIOException e) {
                LOGGER.warn("Skipping XP Orb Trails pack {}: {}", archive.getFileName(), e.getMessage());
                errors++;
            }
        }
        return errors;
    }

    private void load(Path archive) throws IOException {
        if (Files.size(archive) > 64L * 1024 * 1024) throw new IOException("ZIP exceeds 64 MiB");
        try (var zip = new ZipFile(archive.toFile())) {
            if (zip.size() > 4096) throw new IOException("ZIP exceeds 4096 entries");
        }
        var fs = FileSystems.newFileSystem(archive, Map.of());
        boolean keepOpen = false;
        try {
            Path root = contentRoot(fs.getPath("/"));
            String filename = archive.getFileName().toString();
            String suffix = ":" + UUID.nameUUIDFromBytes(filename.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
            var references = new TreeMap<String, String>();
            var packSprites = new TreeMap<String, Path>();
            var packEffects = new TreeMap<String, Path>();
            for (var role : AssetRole.values()) {
                collect(root, role, "sprites", packSprites, suffix, references);
                if (role != AssetRole.TRAIL) collect(root, role, "effects", packEffects, suffix, references);
            }
            var files = Files.isDirectory(root.resolve("profiles")) ? FileTrailStyles.jsonFiles(root.resolve("profiles")) : List.<Path>of();
            if (files.isEmpty() && packSprites.isEmpty() && packEffects.isEmpty()) throw new IOException("No profiles or artwork found");
            var ids = Map.copyOf(references);
            for (var file : files) profiles.add(new Profile(file, filename, ids));
            sprites.putAll(packSprites);
            effects.putAll(packEffects);
            open.add(fs);
            keepOpen = true;
        } finally {
            if (!keepOpen) fs.close();
        }
    }

    private static void collect(Path root, AssetRole role, String kind, Map<String, Path> dest,
                                String suffix, Map<String, String> references) throws IOException {
        Path directory = root.resolve(role.folder).resolve(kind);
        if (!Files.isDirectory(directory)) return;
        boolean sprite = kind.equals("sprites");
        var local = new TreeMap<String, Path>();
        AssetFiles.collect(directory, (sprite ? "sprite:" : "file:") + role.folder + "/", sprite, local);
        for (var entry : local.entrySet()) {
            String id = entry.getKey();
            String qualified = id + suffix;
            references.put(id, qualified);
            dest.put(qualified, entry.getValue());
        }
    }

    private static boolean hasContent(Path root) {
        if (Files.isDirectory(root.resolve("profiles"))) return true;
        for (var role : AssetRole.values()) {
            if (Files.isDirectory(root.resolve(role.folder).resolve("sprites"))
                    || role != AssetRole.TRAIL && Files.isDirectory(root.resolve(role.folder).resolve("effects"))) return true;
        }
        return false;
    }

    private static Path atRoot(Path dir) {
        for (var candidate : List.of(dir, dir.resolve("xp_orb_trails"), dir.resolve("config/xp_orb_trails")))
            if (hasContent(candidate)) return candidate;
        return null;
    }

    private static Path contentRoot(Path root) throws IOException {
        var direct = atRoot(root);
        if (direct != null) return direct;
        try (var entries = Files.list(root)) {
            var matches = entries.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith(".") && !p.getFileName().toString().equals("__MACOSX"))
                    .map(PresetPacks::atRoot).filter(java.util.Objects::nonNull).limit(2).toList();
            if (matches.size() == 1) return matches.getFirst();
        }
        throw new IOException("Expected one pack root containing profiles or role artwork folders");
    }

    static void bindArtwork(JsonObject settings, Map<String, String> ids) {
        bind(settings, ids);
        for (String section : List.of("body", "pickup")) {
            if (settings.has(section)) bindDefaultSprites(settings.getAsJsonObject(section), ids);
        }
        if (settings.has("workshop")) {
            for (var node : settings.getAsJsonArray("workshop")) bindDefaultSprites(node.getAsJsonObject(), ids);
        }
    }

    private static void bindDefaultSprites(JsonObject node, Map<String, String> ids) {
        new OrbEffectOptions(node).defaultSprites().forEach((field, sprite) -> {
            if (!node.has(field) && ids.containsKey(sprite)) node.addProperty(field, ids.get(sprite));
        });
    }

    private static JsonElement bind(JsonElement value, Map<String, String> ids) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String replacement = ids.get(value.getAsString());
            if (replacement != null) return new JsonPrimitive(replacement);
        } else if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) entry.setValue(bind(entry.getValue(), ids));
        } else if (value.isJsonArray()) {
            var array = value.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) array.set(i, bind(array.get(i), ids));
        }
        return value;
    }

    @Override public void close() {
        for (var fs : open) {
            try {
                fs.close();
            } catch (IOException e) {
                LOGGER.warn("Could not close XP Orb Trails pack: {}", e.getMessage());
            }
        }
    }
}
