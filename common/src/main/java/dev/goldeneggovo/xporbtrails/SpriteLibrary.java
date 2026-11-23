package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

final class SpriteLibrary {
    private static final Logger LOGGER = LoggerFactory.getLogger("XP Orb Trails");
    private static final Pattern ID = Pattern.compile("sprite:(orb|pickup|trail)/[a-z0-9_-]{1,64}" + PresetPacks.ID_SUFFIX);
    private static final String EXAMPLE = "comet-strip/comet.png";
    private static final int MAX_SHEETS = 64;
    private static final int MAX_TOTAL_PIXELS = 16 * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private final Path directory;
    private volatile RoleAssets<SpriteSheet> assets = new RoleAssets<>(Map.of());

    SpriteLibrary(Path directory) {
        this.directory = directory;
    }

    static boolean isSpriteId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    Map<String, SpriteSheet> sheets() {
        return assets.all;
    }

    Map<String, SpriteSheet> sheets(AssetRole role) {
        return assets.forRole(role);
    }

    synchronized int reload() {
        return reload(Map.of());
    }

    synchronized int reload(Map<String, Path> packed) {
        List<Map.Entry<String, Path>> folders;
        Map<String, SpriteSheet> sheets = assets.all;
        int errors = 0;
        try {
            // Reserve capacity across all roles for previously loaded sheets first.
            var sources = AssetFiles.find(directory, "sprites", EXAMPLE);
            sources.putAll(packed);
            folders = sources.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> !sheets.containsKey(entry.getKey()))).toList();
        } catch (IOException | UncheckedIOException exception) {
            LOGGER.warn("Could not read sprite directory {}: {}", directory, exception.getMessage());
            return errors + 1;
        }
        Map<String, SpriteSheet> loaded = new TreeMap<>();
        int availablePixels = MAX_TOTAL_PIXELS;
        for (var entry : folders) {
            SpriteSheet previous = sheets.get(entry.getKey());
            if (previous != null) availablePixels -= previous.width() * previous.height();
        }
        for (var entry : folders) {
            Path folder = entry.getValue();
            String stem = folder.getFileName().toString();
            String id = entry.getKey();
            SpriteSheet previous = sheets.get(id);
            if (previous != null) availablePixels += previous.width() * previous.height();
            SpriteSheet sheet;
            try {
                if (stem.contains(":") || !isSpriteId(id)) throw new IOException("Expected a folder name matching [a-z0-9_-]{1,64}");
                sheet = loadFolder(folder, stem, availablePixels, loaded.size());
            } catch (IOException | UncheckedIOException | InvalidPathException exception) {
                LOGGER.warn("Could not load sprite {}: {}", folder, exception.getMessage());
                sheet = previous;
                errors++;
            }
            if (sheet != null) {
                loaded.put(id, sheet);
                availablePixels -= sheet.width() * sheet.height();
            }
        }
        assets = new RoleAssets<>(loaded);
        return errors;
    }

    private static SpriteSheet loadFolder(Path folder, String stem, int availablePixels, int loadedCount) throws IOException {
        Path json = null, singlePng = null;
        int pngCount = 0;
        try (var paths = Files.list(folder)) {
            var entries = paths.iterator();
            while (entries.hasNext()) {
                Path file = entries.next();
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) continue;
                if (name.endsWith(".json")) {
                    if (json != null) throw new IOException("Expected at most one JSON descriptor per sprite folder");
                    json = file;
                } else if (name.endsWith(".png")) {
                    pngCount++;
                    singlePng = file;
                }
            }
        }
        if (json == null && pngCount == 0) return null;
        if (loadedCount >= MAX_SHEETS) throw new IOException("At most " + MAX_SHEETS + " sprite atlases can be loaded");
        JsonObject descriptor = json == null ? new JsonObject() : readJson(json);
        JsonObject imageFields = descriptor.has("meta") ? SpriteSheet.object(descriptor.get("meta"), "meta") : descriptor;
        Path png = null;
        if (imageFields.has("image")) {
            String filename = SpriteSheet.string(imageFields, "image");
            if (!filename.toLowerCase(Locale.ROOT).endsWith(".png") || filename.contains("/") || filename.contains("\\")
                    || filename.contains(":") || filename.indexOf(0) >= 0) {
                throw new IOException("image must be a PNG filename in this sprite folder");
            }
            Path requested = folder.resolve(filename);
            if (Files.exists(requested, NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(requested, NOFOLLOW_LINKS)) throw new IOException("Expected a local PNG: " + filename);
                png = requested;
            }
        }
        if (png == null) {
            if (pngCount != 1) throw new IOException("Expected one PNG, or a descriptor naming an existing PNG");
            png = singlePng;
        }
        if (Files.size(png) > 32 * 1024 * 1024) throw new IOException("PNG exceeds 32 MiB");
        ImageReader reader = ImageIO.getImageReadersByFormatName("PNG").next();
        try (var stream = imageStream(png)) {
            reader.setInput(stream, true, true);
            return SpriteSheet.read(reader, descriptor, stem, availablePixels);
        } finally {
            reader.dispose();
        }
    }

    private static ImageInputStream imageStream(Path png) throws IOException {
        if (png.getFileSystem() == java.nio.file.FileSystems.getDefault()) return new FileImageInputStream(png.toFile());
        try (var input = Files.newInputStream(png)) {
            byte[] bytes = input.readNBytes(32 * 1024 * 1024 + 1);
            if (bytes.length > 32 * 1024 * 1024) throw new IOException("PNG exceeds 32 MiB");
            return new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes));
        }
    }

    private static JsonObject readJson(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) throw new IOException("Sprite descriptor exceeds 1 MiB");
            try (var json = new JsonReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8.newDecoder()))) {
                json.setStrictness(Strictness.STRICT);
                JsonObject result = SpriteSheet.object(JsonParser.parseReader(json), "sprite descriptor");
                if (json.peek() != JsonToken.END_DOCUMENT) throw new IOException("Unexpected data after JSON descriptor");
                return result;
            } catch (JsonParseException exception) {
                throw new IOException("Invalid sprite descriptor: " + exception.getMessage(), exception);
            }
        }
    }
}
