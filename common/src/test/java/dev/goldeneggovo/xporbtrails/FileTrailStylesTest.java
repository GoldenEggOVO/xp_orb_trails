package dev.goldeneggovo.xporbtrails;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileTrailStylesTest {
    @Test
    void appliesSpriteReferencesBeforeInstallingTheirArtwork(@TempDir Path gameConfig) throws Exception {
        Path styles = Files.createDirectories(gameConfig.resolve("xp_orb_trails/profiles"));
        Files.writeString(styles.resolve("sprited.json"), """
                {"version":1,"trailSprite":"sprite:trail/ribbon","flightEffect":"sprite:orb/idle","pickupFlashStyle":"sprite:pickup/burst",
                 "showVanillaOrb":false,"orbScale":2,"trailSpriteMode":"ribbon","stampSpacing":0.5,"stampScale":3}
                """);
        XpOrbTrailsClient.initialize(gameConfig);
        TrailConfig loaded = new TrailConfig();
        style(XpOrbTrailsClient.fileStyles(), "sprited").settings().applyTo(loaded);
        assertEquals("sprite:trail/ribbon", loaded.trailSprite);
        assertEquals("sprite:orb/idle", loaded.flightEffect);
        assertEquals("sprite:pickup/burst", loaded.pickupFlashStyle);
        assertFalse(loaded.showVanillaOrb);
        assertEquals(2, loaded.orbScale);
        assertEquals("ribbon", loaded.trailSpriteMode);
        assertEquals(0.5, loaded.stampSpacing);
        assertEquals(3, loaded.stampScale);
    }

    @TempDir Path directory;

    @Test
    void loadsEchoFixedPlacementAndTilingThroughTheFileStyleMenu() throws Exception {
        Files.createDirectories(styles());
        Files.writeString(styles().resolve("echo.json"), """
                {"version":1,"trailSprite":"body","fixedStamps":true,"ribbonMapping":"tile","ribbonRepeatLength":0.35}
                """);
        XpOrbTrailsClient.initialize(directory);
        var active = new TrailConfig();
        style(XpOrbTrailsClient.fileStyles(), "echo").settings().applyTo(active);
        assertEquals("body", active.trailSprite);
        assertTrue(active.fixedStamps);
        assertEquals("tile", active.ribbonMapping);
        assertEquals(0.35, active.ribbonRepeatLength);
    }

    @Test
    void collectionLabProfilesKeepDedicatedAndCombinedEffectsAndResetOnlyTheSelectedPage() throws Exception {
        var styles = EffectCollectionFixture.install(directory);
        var singles = new java.util.HashSet<String>();
        for (var profile : styles) {
            if (!profile.name().startsWith("lab-")) continue;
            var effects = profile.settings().advanced.getAsJsonArray("workshop");
            if (effects.size() == 1) {
                String type = effects.get(0).getAsJsonObject().get("type").getAsString();
                assertEquals("lab-" + type, profile.name());
                assertTrue(singles.add(type));
            }
        }
        assertEquals(java.util.Set.of("directional", "relief", "fracture", "crossing", "redstone", "fusion",
                "rig", "brush", "morph", "hoops", "hand", "ink", "events"), singles);
        var current = new TrailConfig();
        style(styles, "lab-butterfly").settings().applyTo(current);
        assertEquals(List.of("assemble", "shed"), types(current));
        current.restoreAdvanced(new TrailConfig(), "pickup");
        assertEquals(List.of("shed"), types(current));
        style(styles, "lab-river").settings().applyTo(current);
        assertEquals(List.of("school", "current"), types(current));
        current.restoreAdvanced(new TrailConfig(), "scene");
        assertFalse(current.advanced.has("workshop"));
    }

    private static List<String> types(TrailConfig current) {
        return current.advanced.getAsJsonArray("workshop").asList().stream()
                .map(effect -> effect.getAsJsonObject().get("type").getAsString()).toList();
    }

    private Path styles() {
        return directory.resolve("xp_orb_trails/profiles");
    }

    @Test
    void willowAndStormKeepTheirOtherSceneEffectsWithoutPlayerConnections() throws Exception {
        assertFalse(SceneRule.TYPES.contains("merge"));
        assertFalse(SceneRule.TYPES.contains("tether"));
        var expected = Map.of("workshop-willow", List.of("beat", "capture"),
                "workshop-storm", List.of("turn", "speed"));
        for (var profile : expected.entrySet()) {
            var json = EffectCollectionFixture.profile(profile.getKey());
            var scene = json.getAsJsonObject("advanced").getAsJsonArray("scene");
            assertEquals(profile.getValue(), scene.asList().stream()
                    .map(rule -> rule.getAsJsonObject().get("type").getAsString()).toList());
        }
    }

    @Test
    void collectionWorkshopProfilesCoverAllProgramsAndKeepIndependentCopies() throws Exception {
        var styles = EffectCollectionFixture.install(directory);
        var workshops = styles.stream().filter(s -> s.name().startsWith("workshop-")).toList();
        assertEquals(9, workshops.size());
        var bodies = new java.util.HashSet<String>();
        var pickups = new java.util.HashSet<String>();
        var trails = new java.util.HashSet<com.google.gson.JsonObject>();
        var optics = new java.util.HashSet<String>();
        var rules = new java.util.HashSet<String>();
        for (var style : workshops) {
            var config = new TrailConfig();
            style.settings().applyTo(config);
            var advanced = config.advanced;
            assertNotNull(style.nameKey());
            assertNotSame(style.settings().advanced, advanced);
            if (advanced.has("body")) bodies.add(advanced.getAsJsonObject("body").get("type").getAsString());
            if (advanced.has("pickup")) pickups.add(advanced.getAsJsonObject("pickup").get("type").getAsString());
            if (advanced.has("trail")) trails.add(advanced.getAsJsonObject("trail"));
            if (advanced.has("optics")) optics.add(advanced.getAsJsonObject("optics").get("type").getAsString());
            SceneRule.parse(advanced.get("scene")).forEach(rule -> rules.add(rule.type()));
            var saved = new TrailConfig.SavedProfile("copy", config);
            var reloaded = new Gson().fromJson(new Gson().toJson(saved), TrailConfig.SavedProfile.class);
            reloaded.applyTo(config);
            assertEquals(advanced, config.advanced);
            assertNotSame(reloaded.advanced, config.advanced);
            style(styles, "standard").settings().applyTo(config);
            assertTrue(config.advanced.isEmpty());
        }
        assertEquals(java.util.Set.of("planet","firefly","magnet","tentacles","droplet","bird"), bodies);
        assertEquals(java.util.Set.of("petals","fireworks","ripple","rebound","vortex"), pickups);
        assertEquals(8, trails.size());
        assertEquals(java.util.Set.of("heat", "light"), optics);
        assertEquals(SceneRule.TYPES, rules);
    }

    @Test
    void malformedAdvancedJsonDoesNotBlockOtherFiles() throws Exception {
        Files.createDirectories(styles());
        Files.writeString(styles().resolve("broken.json"), "{\"version\":1,\"advanced\":{\"scene\":[}");
        var library = new FileTrailStyles(directory.resolve("xp_orb_trails"), TrailConfig::new);
        assertEquals(1, library.reload());
        assertNotNull(style(library.styles(), "standard"));
    }

    private static FileTrailStyles.Style style(List<FileTrailStyles.Style> styles, String name) {
        return styles.stream().filter(style -> style.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void partialProfilesInheritEditableDefaultsWithoutChangingThem() throws Exception {
        Files.createDirectories(styles());
        Files.writeString(styles().resolve("minimal.json"), """
                {"version":1,"width":0.28,"flightEffect":"sprite:orb/pixel","trailSprite":"sprite:trail/comet"}
                """);
        var library = new FileTrailStyles(directory.resolve("xp_orb_trails"), () -> {
            var defaults = new TrailConfig();
            defaults.orbScale = 2.1;
            defaults.stampScale = 3.0;
            defaults.stampSpacing = 4.0;
            return defaults;
        });
        assertEquals(0, library.reload());
        var active = new TrailConfig();
        style(library.styles(), "minimal").settings().applyTo(active);
        assertEquals(2.1, active.orbScale);
        assertEquals(3.0, active.stampScale);
        assertEquals(4.0, active.stampSpacing);
    }

    @Test
    void bundledProfilesAreOrdinaryFilesWithTheOriginalAppearance() {
        var library = new FileTrailStyles(directory.resolve("xp_orb_trails"), TrailConfig::new);
        assertEquals(0, library.reload());
        assertEquals(List.of("Comet Pennant", "rainbow", "standard", "Starfall"), library.styles().stream()
                .map(FileTrailStyles.Style::name).toList());

        var rainbow = new TrailConfig();
        rainbow.colorMode = "rainbow";
        rainbow.rainbowSpeed = 0.22;
        rainbow.tailWidthScale = 0.1;
        rainbow.middleWidthScale = 1.1;
        rainbow.headWidthScale = 0.5;
        rainbow.pickupFlashStyle = "file:pickup/ring";
        rainbow.pickupFlashStrength = 0.7;

        var expected = List.of(new TrailConfig(), rainbow);
        Gson gson = new Gson();
        for (int i = 0; i < expected.size(); i++) {
            var style = style(library.styles(), List.of("standard", "rainbow").get(i));
            assertEquals("screen.xporbtrails.profile." + style.name(), style.nameKey());
            var active = new TrailConfig();
            style.settings().applyTo(active);
            assertEquals(gson.toJsonTree(expected.get(i)), gson.toJsonTree(active), style.name());
        }
    }

    @Test
    void preservesPreinstalledEditsAndHonorsDeletionAfterRestart() throws Exception {
        Files.createDirectories(styles());
        String custom = "{\"version\":1,\"nameKey\":\"screen.xporbtrails.profile.standard\",\"name\":\"My trail\",\"width\":0.64}";
        Files.writeString(styles().resolve("standard.json"), custom);
        var library = new FileTrailStyles(directory.resolve("xp_orb_trails"), TrailConfig::new);
        assertEquals(0, library.reload());
        assertEquals(custom, Files.readString(styles().resolve("standard.json")));
        var profile = style(library.styles(), "My trail");
        assertEquals("My trail", profile.displayName().getString());
        var active = new TrailConfig();
        profile.settings().applyTo(active);
        assertEquals(0.64, active.width);

        Files.delete(styles().resolve("rainbow.json"));
        Files.writeString(styles().resolve("fifth.json"), "{\"version\":1,\"name\":\"Fifth\"}");
        Files.writeString(styles().resolve("standard.json"), custom.replace("0.64", "0.42"));
        var restarted = new FileTrailStyles(directory.resolve("xp_orb_trails"), TrailConfig::new);
        for (var result : List.of(library, restarted)) {
            assertEquals(0, result.reload());
            assertFalse(Files.exists(styles().resolve("rainbow.json")));
            assertEquals(List.of("Comet Pennant", "Fifth", "My trail", "Starfall"),
                    result.styles().stream().map(FileTrailStyles.Style::name).toList());
            style(result.styles(), "My trail").settings().applyTo(active);
            assertEquals(0.42, active.width);
        }
    }

    @Test
    void installsTheExampleOnceAndHonorsEditsAndDeletion() throws Exception {
        XpOrbTrailsClient.initialize(directory);
        var example = XpOrbTrailsClient.fileStyles().getFirst();
        assertEquals("Comet Pennant", example.name());
        assertEquals("ribbon", example.settings().trailSpriteMode);

        Path file = styles().resolve("comet-pennant.json");
        String edited = """
                {"version":1,"name":"My pennant","colorMode":"solid","startColor":"#bCdEF0"}
                """;
        Files.writeString(file, edited);
        XpOrbTrailsClient.initialize(directory);
        assertEquals(edited, Files.readString(file));
        assertEquals(0xBCDEF0, XpOrbTrailsClient.fileStyles().getFirst().settings().startColor);
        Files.delete(file);
        assertEquals(0, XpOrbTrailsClient.reloadFileStyles());
        assertTrue(XpOrbTrailsClient.fileStyles().stream().noneMatch(style -> style.name().equals("My pennant")));
        XpOrbTrailsClient.initialize(directory);
        assertFalse(Files.exists(file));
    }

    @Test
    void reloadsEachFileFromDefaultsAndKeepsTheActiveConfigUntouched() throws Exception {
        Files.createDirectories(styles());
        Files.writeString(styles().resolve("z.json"), "{\"version\":1}");
        Files.writeString(styles().resolve("a.json"), """
                {"version":1,"name":"Ice","colorMode":"gradient","startColor":"#000011",
                 "endColor":"#0022FF","gradientStops":["#123456","#abcdef"],"crossSectionSides":7,
                 "pickupFlashStyle":"file:pickup/shards","flightPulse":true,"pulseSpeed":3,"shardCount":12,
                 "spiralTurns":1.5,"effectStrength":1.3,"headWidthScale":0.5}
                """);
        XpOrbTrailsClient.initialize(directory);
        XpOrbTrailsClient.CONFIG.width = 0.6;
        assertEquals(0, XpOrbTrailsClient.reloadFileStyles());
        assertEquals(List.of("Ice", "Comet Pennant", "rainbow", "standard", "Starfall", "z"), XpOrbTrailsClient.fileStyles().stream().map(FileTrailStyles.Style::name).toList());
        TrailConfig applied = new TrailConfig();
        XpOrbTrailsClient.fileStyles().getFirst().settings().applyTo(applied);
        assertEquals(0x11, applied.startColor);
        assertEquals(0x22FF, applied.endColor);
        assertArrayEquals(new int[]{0x123456, 0xABCDEF}, applied.gradientStops);
        assertEquals(7, applied.crossSectionSides);
        assertEquals("file:pickup/shards", applied.pickupFlashStyle);
        assertTrue(applied.flightPulse);
        assertEquals(3, applied.pulseSpeed);
        assertEquals(12, applied.shardCount);
        assertEquals(1.5, applied.spiralTurns);
        assertEquals(1.3, applied.effectStrength);
        assertEquals(0.5, applied.headWidthScale);
        assertEquals(new TrailConfig().width, applied.width);
        assertEquals(0.6, XpOrbTrailsClient.CONFIG.width);

        XpOrbTrailsClient.fileStyles().getLast().settings().applyTo(applied);
        assertArrayEquals(new int[0], applied.gradientStops);
        assertEquals("file:pickup/soft", applied.pickupFlashStyle);
    }

    @Test
    void isolatesMalformedAndUnsupportedFilesAndRecoversOnReload() throws Exception {
        Files.createDirectories(styles());
        List<String> invalid = List.of(
                "{", "[]", "{}", "{\"version\":2}", "{\"version\":\"1\"}",
                "{\"version\":1,\"startColour\":\"#112233\"}", "{\"version\":1,\"name\":\" \"}",
                "{\"version\":1,\"width\":\"0.8\"}", "{\"version\":1,\"shardCount\":7.5}",
                "{\"version\":1,\"pickupFlash\":\"true\"}", "{\"version\":1,\"colorMode\":\"spark\"}",
                "{\"version\":1,\"fixedStamps\":1}", "{\"version\":1,\"ribbonMapping\":\"repeat-each-point\"}",
                "{\"version\":1,\"flightEffect\":\"file:pickup/burst\"}", "{\"version\":1,\"pickupFlashStyle\":\"sprite:orb/idle\"}",
                "{\"version\":1,\"trailSprite\":\"sprite:orb/idle\"}", "{\"version\":1,\"pickupFlashStyle\":\"shards\"}",
                "{\"version\":1,\"ribbonRepeatLength\":0}", "{\"version\":1,\"ribbonRepeatLength\":4.2}",
                "{\"version\":1,\"startColor\":\"001122\"}", "{\"version\":1,\"pulseSpeed\":4.1}",
                "{\"version\":1,\"gradientStops\":[\"#AA33CC\",12]}", "{\"version\":1,\"gradientStops\":null}",
                "{\"version\":1,\"gradientStops\":[" + "\"#000000\",".repeat(8) + "\"#000000\"]}",
                "{\"version\":1,\"enabled\":true}", "{\"version\":1,\"version\":1}", "{\"version\":1} {}");
        for (int index = 0; index < invalid.size(); index++) {
            Files.writeString(styles().resolve("bad" + index + ".json"), invalid.get(index));
        }
        Files.writeString(styles().resolve("ok.json"), "{\"version\":1,\"gradientStops\":[]}");
        Files.writeString(styles().resolve("notes.txt"), "ignored");
        XpOrbTrailsClient.initialize(directory);
        assertEquals(invalid.size(), XpOrbTrailsClient.reloadFileStyles());
        assertEquals(List.of("Comet Pennant", "ok", "rainbow", "standard", "Starfall"), XpOrbTrailsClient.fileStyles().stream().map(FileTrailStyles.Style::name).toList());
        assertEquals(invalid.getFirst(), Files.readString(styles().resolve("bad0.json")));
        Files.writeString(styles().resolve("bad0.json"), "{\"version\":1}");
        assertEquals(invalid.size() - 1, XpOrbTrailsClient.reloadFileStyles());
        assertEquals(6, XpOrbTrailsClient.fileStyles().size());
    }

    @Test
    void reportsAnUnreadableDirectoryAndKeepsTheLastSuccessfulList() throws Exception {
        XpOrbTrailsClient.initialize(directory);
        var previous = XpOrbTrailsClient.fileStyles();
        Files.move(styles(), styles().resolveSibling("moved"));
        Files.writeString(styles(), "a file blocks the directory");
        assertTrue(XpOrbTrailsClient.reloadFileStyles() > 0);
        assertEquals(previous, XpOrbTrailsClient.fileStyles());
        assertEquals("a file blocks the directory", Files.readString(styles()));
    }
}
