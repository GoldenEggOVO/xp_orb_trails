package dev.goldeneggovo.xporbtrails;

import com.google.common.hash.Hashing;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

final class ProfileUpgrades {
    private static final Set<String> RETIRED_WORKSHOP = Set.of("vein", "seal");
    private static final Set<String> RETIRED_SCENE = Set.of("merge", "tether");
    private static final String PROFILE_RECEIPT = "# retired 2.1 beta profiles";
    private static final String SPRITE_RECEIPT = "# retired 2.1 beta sprites";
    private static final String MANIFEST = "/assets/xporbtrails/retired-beta-templates.sha256";
    private static final int MAX_TEMPLATE_BYTES = 16 * 1024;

    static void removeRetiredEffects(JsonObject advanced) {
        remove(advanced, "workshop", RETIRED_WORKSHOP);
        remove(advanced, "scene", RETIRED_SCENE);
    }

    private static void remove(JsonObject advanced, String section, Set<String> retired) {
        var nodes = advanced.get(section);
        if (nodes == null || !nodes.isJsonArray()) return;
        boolean changed = nodes.getAsJsonArray().asList().removeIf(node -> {
            if (!node.isJsonObject()) return false;
            var type = node.getAsJsonObject().get("type");
            return type != null && type.isJsonPrimitive() && retired.contains(type.getAsString());
        });
        if (changed && nodes.getAsJsonArray().isEmpty()) advanced.remove(section);
    }

    static void retireProfiles(Path directory) throws IOException {
        Path receipt = directory.resolve(".installed");
        if (!Files.isRegularFile(receipt, NOFOLLOW_LINKS)) return;
        var installed = Files.readAllLines(receipt);
        if (installed.contains(PROFILE_RECEIPT)) return;
        for (var row : manifest()) {
            if (!row[0].startsWith("profiles/")) continue;
            String name = row[0].substring("profiles/".length());
            Path file = directory.resolve(name);
            if (installed.contains(name) && Arrays.asList(row).subList(1, row.length).contains(fingerprint(file))) {
                archive(file, directory);
            }
        }
        Files.writeString(receipt, PROFILE_RECEIPT + "\n", StandardOpenOption.APPEND);
    }

    // Run after profiles are loaded (including ZIP-local references) and before the sprite library scans.
    static void retireSprites(Path root, TrailConfig current, TrailConfig defaults, List<FileTrailStyles.Style> styles)
            throws IOException {
        Path receipt = root.resolve("profiles/.installed");
        if (!Files.isRegularFile(receipt, NOFOLLOW_LINKS) || Files.readAllLines(receipt).contains(SPRITE_RECEIPT)) return;
        var used = new HashSet<String>();
        collectArtwork(current, used);
        for (var profile : current.savedProfiles) collectArtwork(profile, used);
        collectArtwork(defaults, used);
        for (var style : styles) collectArtwork(style.settings(), used);
        for (var row : manifest()) {
            if (row[0].startsWith("profiles/")) continue;
            String[] path = row[0].split("/");
            String sprite = path[2];
            if (used.contains("sprite:" + path[0] + "/" + sprite)) continue;
            Path role = root.resolve(path[0]);
            Path directory = role.resolve("sprites");
            Path folder = directory.resolve(sprite);
            if (!Files.isDirectory(role, NOFOLLOW_LINKS) || !Files.isDirectory(directory, NOFOLLOW_LINKS)
                    || !Files.isDirectory(folder, NOFOLLOW_LINKS)
                    || !Files.isRegularFile(role.resolve(".installed-" + sprite), NOFOLLOW_LINKS)) continue;
            try (var files = Files.list(folder)) {
                if (files.limit(3).count() != 2) continue;
            }
            if (row[1].equals(fingerprint(folder.resolve("sheet.json"))) && row[2].equals(fingerprint(folder.resolve("sheet.png")))) {
                archive(folder, role);
            }
        }
        Files.writeString(receipt, SPRITE_RECEIPT + "\n", StandardOpenOption.APPEND);
    }

    private static void collectArtwork(ProfileSettings settings, Set<String> used) {
        used.add(settings.flightEffect);
        used.add(settings.pickupFlashStyle);
        used.add(settings.trailSprite);
        collectArtwork(settings.advanced, used);
    }

    private static void collectArtwork(JsonElement value, Set<String> used) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            used.add(value.getAsString());
        } else if (value.isJsonArray()) {
            for (var item : value.getAsJsonArray()) collectArtwork(item, used);
        } else if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            for (var item : object.entrySet()) collectArtwork(item.getValue(), used);
            // Some effect nodes use a local sheet even when the user omits the sprite field.
            var options = new OrbEffectOptions(object);
            options.defaultSprites().forEach((field, sprite) -> used.add(options.text(field, sprite)));
        }
    }

    private static List<String[]> manifest() throws IOException {
        var input = ProfileUpgrades.class.getResourceAsStream(MANIFEST);
        if (input == null) throw new IOException("Missing beta template fingerprints");
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.startsWith("#")).map(line -> line.split(" ")).toList();
        }
    }

    private static String fingerprint(Path file) throws IOException {
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) > MAX_TEMPLATE_BYTES) return "";
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_TEMPLATE_BYTES + 1);
            if (bytes.length > MAX_TEMPLATE_BYTES) return "";
            if (file.getFileName().toString().endsWith(".json")) {
                // Several Windows beta builds contain CR-CR-LF in generated JSON.
                String json = new String(bytes, StandardCharsets.UTF_8).replace("\r\r\n", "\n").replace("\r\n", "\n");
                return Hashing.sha256().hashString(json, StandardCharsets.UTF_8).toString();
            }
            return Hashing.sha256().hashBytes(bytes).toString();
        }
    }

    private static void archive(Path file, Path parent) throws IOException {
        Path backup = parent.resolve(".retired-beta");
        try {
            Files.createDirectory(backup);
        } catch (FileAlreadyExistsException ignored) { }
        if (!Files.isDirectory(backup, NOFOLLOW_LINKS)) throw new IOException("Expected backup directory: " + backup);
        try {
            Files.move(file, backup.resolve(file.getFileName()));
        } catch (FileAlreadyExistsException ignored) {
            // Both copies belong to the player once a backup already exists.
        }
    }

    private ProfileUpgrades() {}
}
