package dev.goldeneggovo.xporbtrails;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

final class AssetFiles {
    private static final String SPRITES = "/assets/xporbtrails/sprites/";

    private AssetFiles() {}

    static Map<String, Path> find(Path root, String kind, String example) throws IOException {
        boolean sprites = kind.equals("sprites");
        Map<String, Map<String, List<String>>> catalog = sprites ? spriteCatalog() : Map.of();
        String prefix = sprites ? "sprite:" : "file:";
        var entries = new TreeMap<String, Path>();
        for (var role : AssetRole.values()) {
            if (!sprites && role == AssetRole.TRAIL) continue;
            Path directory = root.resolve(role.folder).resolve(kind);
            Files.createDirectories(directory.getParent());
            try {
                Files.createDirectory(directory);
                try (var input = AssetFiles.class.getResourceAsStream("/assets/xporbtrails/" + kind + "/" + example)) {
                    if (input == null) throw new IOException("Missing bundled example " + example);
                    Path target = directory.resolve(example);
                    Files.createDirectories(target.getParent());
                    Files.copy(input, target);
                }
            } catch (FileAlreadyExistsException ignored) {
                // Keep edits and deletions on reload.
            }
            for (var sheet : catalog.getOrDefault(role.folder, Map.of()).entrySet()) {
                installSprite(directory, role.folder, sheet.getKey(), sheet.getValue());
            }
            if (!sprites) installTemplates(directory, directory.getParent().resolve(".installed-effects"),
                    "/assets/xporbtrails/effects/" + role.folder + "/");
            collect(directory, prefix + role.folder + "/", sprites, entries);
        }
        return entries;
    }

    private static Map<String, Map<String, List<String>>> spriteCatalog() throws IOException {
        var catalog = new HashMap<String, Map<String, List<String>>>();
        try (var manifest = AssetFiles.class.getResourceAsStream(SPRITES + "defaults.txt")) {
            if (manifest == null) throw new IOException("Missing bundled sprite list");
            var reader = new BufferedReader(new InputStreamReader(manifest, StandardCharsets.UTF_8));
            for (String path : reader.lines().toList()) {
                String[] parts = path.split("/", -1);
                if (parts.length != 3 || !SpriteLibrary.isSpriteId("sprite:" + parts[0] + "/" + parts[1])
                        || !parts[2].matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*")) {
                    throw new IOException("Invalid bundled sprite path " + path);
                }
                catalog.computeIfAbsent(parts[0], key -> new HashMap<>())
                        .computeIfAbsent(parts[1], key -> new ArrayList<>()).add(parts[2]);
            }
        }
        return catalog;
    }

    private static void installSprite(Path directory, String role, String sprite, List<String> files) throws IOException {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw new IOException("Expected asset directory: " + directory);
        Path receipt = directory.getParent().resolve(".installed-" + sprite);
        if (Files.exists(receipt)) return;
        Path target = directory.resolve(sprite);
        if (Files.notExists(target, NOFOLLOW_LINKS)) {
            // Install complete sheets together. Existing folders can contain a player's own descriptor or PNG names.
            Path staging = Files.createTempDirectory(directory.getParent(), ".install-sprite-");
            try {
                for (String file : files) {
                    String resource = SPRITES + role + "/" + sprite + "/" + file;
                    try (var asset = AssetFiles.class.getResourceAsStream(resource)) {
                        if (asset == null) throw new IOException("Missing bundled sprite file " + resource);
                        Files.copy(asset, staging.resolve(file));
                    }
                }
                try {
                    Files.move(staging, target);
                } catch (FileAlreadyExistsException ignored) { }
            } finally {
                for (String file : files) Files.deleteIfExists(staging.resolve(file));
                Files.deleteIfExists(staging);
            }
        }
        try {
            Files.createFile(receipt);
        } catch (FileAlreadyExistsException ignored) { }
    }

    static void installTemplates(Path directory, Path receipt, String resources) throws IOException {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw new IOException("Expected asset directory: " + directory);
        var installed = new HashSet<String>();
        if (Files.exists(receipt)) installed.addAll(Files.readAllLines(receipt));
        try (var manifest = AssetFiles.class.getResourceAsStream(resources + "defaults.txt")) {
            if (manifest == null) throw new IOException("Missing bundled catalog " + resources);
            var reader = new BufferedReader(new InputStreamReader(manifest, StandardCharsets.UTF_8));
            for (String file : reader.lines().toList()) {
                if (installed.contains(file)) continue;
                try (var asset = AssetFiles.class.getResourceAsStream(resources + file)) {
                    if (asset == null) throw new IOException("Missing bundled template " + resources + file);
                    try {
                        Files.copy(asset, directory.resolve(file));
                    } catch (FileAlreadyExistsException ignored) {
                        // A pre-existing file belongs to the player.
                    }
                }
                Files.writeString(receipt, file + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        }
    }

    private static void collect(Path directory, String prefix, boolean sprites, Map<String, Path> entries) throws IOException {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw new IOException("Expected asset directory: " + directory);
        try (var paths = Files.list(directory)) {
            paths.forEach(path -> {
                String name = path.getFileName().toString();
                if (sprites && Files.isDirectory(path, NOFOLLOW_LINKS)) entries.put(prefix + name, path);
                if (!sprites && name.endsWith(".json") && Files.isRegularFile(path)) {
                    entries.put(prefix + name.substring(0, name.length() - 5), path);
                }
            });
        }
    }
}
