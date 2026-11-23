package dev.goldeneggovo.xporbtrails;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ProfileUpgradesTest {
    @TempDir Path config;

    @Test void archivesOnlyRecordedOriginalsAndLeavesLaterImportsAlone() throws Exception {
        Path profiles = Files.createDirectories(config.resolve("xp_orb_trails/profiles"));
        Path original = profiles.resolve("lab-butterfly.json");
        Path unrecorded = profiles.resolve("lab-river.json");
        try (var zip = new ZipFile(EffectCollectionFixture.ARCHIVE.toFile())) {
            copyEntry(zip, "profiles/lab-butterfly.json", original);
            copyEntry(zip, "profiles/lab-river.json", unrecorded);
        }
        Path edited = profiles.resolve("lab-relief.json");
        String custom = "{\"version\":1,\"name\":\"My relief\"}";
        Files.writeString(edited, custom);
        Files.writeString(profiles.resolve(".installed"), "lab-butterfly.json\nlab-relief.json\n");
        byte[] expected = Files.readAllBytes(original);

        XpOrbTrailsClient.initialize(config);
        Path backup = profiles.resolve(".retired-beta/lab-butterfly.json");
        assertFalse(Files.exists(original));
        assertArrayEquals(expected, Files.readAllBytes(backup));
        assertEquals(custom, Files.readString(edited));
        assertTrue(Files.isRegularFile(unrecorded));
        Files.copy(backup, original);
        assertEquals(0, XpOrbTrailsClient.reloadFileStyles());
        assertArrayEquals(expected, Files.readAllBytes(original));
    }

    @Test void retainsUsedAndCustomizedAtlasesAndArchivesTheWholeUnusedSheet() throws Exception {
        Path root = Files.createDirectories(config.resolve("xp_orb_trails"));
        var folders = List.of("orb/sprites/atlas-bird-glide", "orb/sprites/atlas-bird-hover", "orb/sprites/atlas-firefly",
                "orb/sprites/lab-faults", "orb/sprites/lab-skiff", "pickup/sprites/atlas-sakura");
        try (var zip = new ZipFile(EffectCollectionFixture.ARCHIVE.toFile())) {
            for (String name : folders) {
                Path folder = Files.createDirectories(root.resolve(name));
                Files.createFile(folder.getParent().getParent().resolve(".installed-" + folder.getFileName()));
                for (String file : List.of("sheet.png", "sheet.json")) copyEntry(zip, name + "/" + file, folder.resolve(file));
            }
        }
        Path profiles = Files.createDirectories(root.resolve("profiles"));
        Files.writeString(profiles.resolve("mine.json"), """
                {"version":1,"advanced":{"body":{"type":"bird"}}}
                """);
        Files.writeString(config.resolve("xp_orb_trails.json"), """
                {"flightEffect":"sprite:orb/atlas-firefly",
                 "savedProfiles":[{"name":"Petal","advanced":{"pickup":{"type":"petals"}}}]}
                """);
        Files.writeString(root.resolve("orb/sprites/lab-skiff/credits.txt"), "My extra file");

        XpOrbTrailsClient.initialize(config);
        for (String name : folders) {
            Path folder = root.resolve(name);
            if (name.endsWith("lab-faults")) {
                assertFalse(Files.exists(folder));
                assertTrue(Files.isRegularFile(root.resolve("orb/.retired-beta/lab-faults/sheet.png")));
                assertTrue(Files.isRegularFile(root.resolve("orb/.retired-beta/lab-faults/sheet.json")));
                assertFalse(XpOrbTrailsClient.sprites().containsKey("sprite:orb/lab-faults"));
            } else {
                assertTrue(Files.isRegularFile(folder.resolve("sheet.png")), name);
            }
        }
    }

    private static void copyEntry(ZipFile zip, String name, Path target) throws Exception {
        try (var input = zip.getInputStream(zip.getEntry(name))) {
            Files.copy(input, target);
        }
    }
}
