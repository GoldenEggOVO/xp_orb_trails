package dev.goldeneggovo.xporbtrails;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ClientConfigStorageTest {
    @TempDir Path directory;

    @Test
    void backsUpAnInvalidGradientWithoutStoppingStartup() throws Exception {
        Path config = directory.resolve("xp_orb_trails.json");
        String invalid = "{\"gradientStops\":[null]}";
        Files.writeString(config, invalid);
        XpOrbTrailsClient.initialize(directory);
        assertEquals(invalid, Files.readString(directory.resolve("xp_orb_trails.json.broken")));
        assertEquals(0, XpOrbTrailsClient.CONFIG.gradientStops.length);
    }

    @Test
    void loadsAndSavesExistingSettingsInTheSuppliedDirectory() throws Exception {
        Path config = directory.resolve("xp_orb_trails.json");
        Files.writeString(config, "{\"width\":0.42,\"savedPresets\":[{\"name\":\"Existing\",\"startColor\":1193046,\"endColor\":11259375}]}");
        XpOrbTrailsClient.initialize(directory);
        assertEquals(0.42, XpOrbTrailsClient.CONFIG.width);
        assertEquals("Existing", XpOrbTrailsClient.CONFIG.savedPresets.getFirst().name);
        XpOrbTrailsClient.CONFIG.width = 0.37;
        XpOrbTrailsClient.saveConfig();
        XpOrbTrailsClient.initialize(directory);
        assertEquals(0.37, XpOrbTrailsClient.CONFIG.width);
        assertEquals(1193046, XpOrbTrailsClient.CONFIG.savedPresets.getFirst().startColor);
        assertFalse(Files.exists(directory.resolve("xp_orb_trails.json.tmp")));
    }

    @Test
    void readsOnlyCurrentPathsAndLeavesOtherFilesIntact() throws Exception {
        Path unrelatedSettings = directory.resolve("xp-orb-trails.json");
        Path unrelatedProfile = Files.createDirectories(directory.resolve("xp-orb-trails/profiles")).resolve("mine.json");
        Files.writeString(unrelatedSettings, "{\"width\":0.42}");
        Files.writeString(unrelatedProfile, "{\"version\":1}");
        XpOrbTrailsClient.initialize(directory);
        assertEquals(0.1, XpOrbTrailsClient.CONFIG.width);
        assertFalse(Files.exists(directory.resolve("xp_orb_trails/profiles/mine.json")));
        assertEquals("{\"width\":0.42}", Files.readString(unrelatedSettings));
        assertEquals("{\"version\":1}", Files.readString(unrelatedProfile));
    }

    @Test
    void readsCurrentFieldsWithoutInterpretingUnknownKeys() throws Exception {
        Path config = directory.resolve("xp_orb_trails.json");
        Files.writeString(config, """
                {"configVersion":1,"width":0.52,"effectStrength":1.32,"unknownKey":true,"glowStrength":0.2,
                 "savedProfiles":[{"name":"My profile","width":0.4,"effectStrength":1.13}]}
                """);
        XpOrbTrailsClient.initialize(directory);
        assertEquals(0.52, XpOrbTrailsClient.CONFIG.width);
        assertEquals(1.32, XpOrbTrailsClient.CONFIG.effectStrength);
        var profile = XpOrbTrailsClient.CONFIG.savedProfiles.getFirst();
        assertEquals(0.4, profile.width);
        assertEquals(1.13, profile.effectStrength);
        String saved = Files.readString(config);
        assertFalse(saved.contains("configVersion"));
        assertFalse(saved.contains("unknownKey"));
        assertFalse(saved.contains("glowStrength"));
    }

    @Test
    void backsUpBrokenConfigBeforeRestoringDefaults() throws Exception {
        Path config = directory.resolve("xp_orb_trails.json");
        Files.writeString(config, "{broken");
        XpOrbTrailsClient.initialize(directory);
        assertEquals("{broken", Files.readString(directory.resolve("xp_orb_trails.json.broken")));
        assertEquals(0.1, XpOrbTrailsClient.CONFIG.width);
        assertTrue(Files.readString(config).contains("\"width\": 0.1"));
        Files.writeString(config, "null");
        XpOrbTrailsClient.initialize(directory);
        assertEquals("{broken", Files.readString(directory.resolve("xp_orb_trails.json.broken")));
        assertEquals("null", Files.readString(directory.resolve("xp_orb_trails.json.broken-1")));
    }
}
