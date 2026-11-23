package dev.goldeneggovo.xporbtrails;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

final class EffectLibrary {
    private static final Logger LOGGER = LoggerFactory.getLogger("XP Orb Trails");
    private static final String EXAMPLE = "twin-orbit.json";
    private static final int MAX_FILE_BYTES = 64 * 1024;
    private static final Pattern FILE_ID = Pattern.compile("file:(orb|pickup)/[a-z0-9_-]{1,64}" + PresetPacks.ID_SUFFIX);
    private final Path directory;
    private volatile RoleAssets<CustomEffect> assets = new RoleAssets<>(Map.of());

    EffectLibrary(Path directory) {
        this.directory = directory;
    }

    static boolean isFileId(String id) {
        return id != null && FILE_ID.matcher(id).matches();
    }

    Map<String, CustomEffect> effects() {
        return assets.all;
    }

    Map<String, CustomEffect> effects(AssetRole role) {
        return assets.forRole(role);
    }

    synchronized int reload() {
        return reload(Map.of());
    }

    synchronized int reload(Map<String, Path> packed) {
        Map<String, Path> files;
        try {
            files = AssetFiles.find(directory, "effects", EXAMPLE);
            files.putAll(packed);
        } catch (IOException | UncheckedIOException exception) {
            LOGGER.warn("Could not read effect directories in {}: {}", directory, exception.getMessage());
            return 1;
        }
        int errors = 0;
        Map<String, CustomEffect> loaded = new TreeMap<>();
        for (var entry : files.entrySet()) {
            String id = entry.getKey();
            Path file = entry.getValue();
            String filename = file.getFileName().toString();
            String stem = filename.substring(0, filename.length() - ".json".length());
            try {
                if (stem.contains(":") || !isFileId(id)) throw new IOException("Expected a filename matching [a-z0-9_-]{1,64}.json");
                loaded.put(id, read(file, stem));
            } catch (IOException exception) {
                LOGGER.warn("Could not load trail effect {}: {}", file, exception.getMessage());
                CustomEffect previous = assets.all.get(id);
                if (previous != null) loaded.put(id, previous);
                errors++;
            }
        }
        assets = new RoleAssets<>(loaded);
        return errors;
    }

    private static CustomEffect read(Path file, String stem) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] bytes = stream.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("Effect file exceeds 64 KiB");
            var reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8.newDecoder());
            String role = file.getParent().getParent().getFileName().toString();
            return CustomEffect.read(reader, stem, "effect.xporbtrails." + role + "." + stem, "effect.xporbtrails." + stem);
        }
    }
}
