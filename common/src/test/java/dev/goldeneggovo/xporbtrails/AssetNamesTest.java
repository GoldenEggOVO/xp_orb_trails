package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class AssetNamesTest {
    private static final Path SPRITE_EXAMPLES = Path.of(System.getProperty("xporb.spriteExamples",
            "../examples/2.0.0example/sprite_sheet_examples.zip"));
    @TempDir Path config;

    @Test void everyBundledAndPackagedMenuEntryHasEnglishAndChineseLabels() throws Exception {
        Path packs = Files.createDirectories(config.resolve("xp_orb_trails/packs"));
        Files.copy(SPRITE_EXAMPLES, packs.resolve(SPRITE_EXAMPLES.getFileName()));
        var styles = EffectCollectionFixture.install(config);
        assertEquals(4 + 4 + 24, styles.size());
        var english = language("en_us");
        var chinese = language("zh_cn");
        for (var style : styles) checkLabel(style.displayName(), english, chinese);
        for (var sprite : XpOrbTrailsClient.sprites().values()) checkLabel(sprite.displayName(), english, chinese);
        for (var effect : XpOrbTrailsClient.effects().values()) checkLabel(effect.displayName(), english, chinese);
    }

    private static Map<String, String> language(String code) throws IOException {
        var translations = new HashMap<String, String>();
        try (var input = AssetNamesTest.class.getResourceAsStream("/assets/xporbtrails/lang/" + code + ".json")) {
            assertNotNull(input);
            Language.loadFromJson(input, translations::put);
        }
        return translations;
    }

    private static void checkLabel(Component label, Map<String, String> english, Map<String, String> chinese) {
        String key = assertInstanceOf(TranslatableContents.class, label.getContents(), label.toString()).getKey();
        assertFalse(english.getOrDefault(key, "").isBlank(), key);
        assertTrue(chinese.getOrDefault(key, "").codePoints().anyMatch(c -> Character.UnicodeScript.of(c)
                == Character.UnicodeScript.HAN), key);
    }

    @Test void versionTwoExampleArchiveKeepsItsLiteralEnglishNamesAndPngOnlyKoi() throws Exception {
        var english = language("en_us");
        try (var archive = new ZipFile(SPRITE_EXAMPLES.toFile())) {
            var descriptors = archive.stream().filter(e -> e.getName().endsWith(".json")).toList();
            assertEquals(4 + 11, descriptors.size());
            for (var entry : descriptors) {
                try (var reader = new InputStreamReader(archive.getInputStream(entry), StandardCharsets.UTF_8)) {
                    JsonObject fields = JsonParser.parseReader(reader).getAsJsonObject();
                    if (fields.has("meta")) fields = fields.getAsJsonObject("meta").getAsJsonObject("pixelfx");
                    String key = fields.get("nameKey").getAsString();
                    assertEquals(english.get(key), fields.get("name").getAsString(), entry.getName());
                }
            }
            assertNotNull(archive.getEntry("orb/sprites/porcelain_koi/sheet.png"));
            assertNull(archive.getEntry("orb/sprites/porcelain_koi/sheet.json"));
        }
    }

    @Test void oldEnglishFilesLocalizeOnReadAndRenamesSurviveWithOrWithoutKeys() throws Exception {
        XpOrbTrailsClient.initialize(config);
        Path root = config.resolve("xp_orb_trails");
        var files = Map.of(
                "profiles/comet-pennant.json", "screen.xporbtrails.profile.comet-pennant",
                "orb/effects/starfall.json", "effect.xporbtrails.orb.starfall",
                "pickup/effects/starfall.json", "effect.xporbtrails.pickup.starfall",
                "orb/sprites/luna-moth/sheet.json", "sprite.xporbtrails.luna-moth",
                "pickup/sprites/collect-burst/sheet.json", "sprite.xporbtrails.collect-burst");
        for (String path : files.keySet()) {
            Path file = root.resolve(path);
            var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            var fields = nameFields(json);
            assertEquals(files.get(path), fields.remove("nameKey").getAsString());
            Files.writeString(file, json.toString());
        }
        assertEquals(0, XpOrbTrailsClient.reloadFileStyles());
        var labels = labels();
        for (var entry : files.entrySet()) {
            assertEquals(entry.getValue(), assertInstanceOf(TranslatableContents.class,
                    labels.get(entry.getKey()).getContents()).getKey());
            Path file = root.resolve(entry.getKey());
            var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            var fields = nameFields(json);
            assertFalse(fields.has("nameKey"), "Loading must not rewrite " + file);
            fields.addProperty("name", "My artwork");
            if (!entry.getKey().startsWith("pickup/")) fields.addProperty("nameKey", entry.getValue());
            Files.writeString(file, json.toString());
        }
        XpOrbTrailsClient.initialize(config);
        for (var label : labels().values()) assertEquals(Component.literal("My artwork"), label);
    }

    private static JsonObject nameFields(JsonObject descriptor) {
        return descriptor.has("meta") ? descriptor.getAsJsonObject("meta").getAsJsonObject("pixelfx") : descriptor;
    }

    private static Map<String, Component> labels() {
        return Map.of("profiles/comet-pennant.json", XpOrbTrailsClient.fileStyles().getFirst().displayName(),
                "orb/effects/starfall.json", XpOrbTrailsClient.effects().get("file:orb/starfall").displayName(),
                "pickup/effects/starfall.json", XpOrbTrailsClient.effects().get("file:pickup/starfall").displayName(),
                "orb/sprites/luna-moth/sheet.json", XpOrbTrailsClient.sprites().get("sprite:orb/luna-moth").displayName(),
                "pickup/sprites/collect-burst/sheet.json", XpOrbTrailsClient.sprites().get("sprite:pickup/collect-burst").displayName());
    }
}
