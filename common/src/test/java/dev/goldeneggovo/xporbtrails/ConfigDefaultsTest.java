package dev.goldeneggovo.xporbtrails;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigDefaultsTest {
    @TempDir Path instance;

    @Test
    void invalidGradientKeepsTheLastLoadedDefaults() throws Exception {
        Path root = Files.createDirectories(instance.resolve("xp_orb_trails"));
        Path file = root.resolve("defaults.json");
        var library = new ConfigDefaults(root);
        Files.writeString(file, "{\"gradientStops\":[255]}");
        assertEquals(0, library.reload());
        String invalid = "{\"gradientStops\":[null]}";
        Files.writeString(file, invalid);
        assertEquals(1, library.reload());
        assertArrayEquals(new int[]{255}, library.create().gradientStops);
        assertEquals(invalid, Files.readString(file));
    }

    @Test
    void freshInstallsAndProfilesUseFileDefaultsWithoutReplacingCurrentSettingsOnReload() throws Exception {
        Path root = Files.createDirectories(instance.resolve("xp_orb_trails"));
        Path file = root.resolve("defaults.json");
        Files.writeString(file, "{\"width\":0.31,\"renderRange\":72,\"unusedSetting\":1}");
        XpOrbTrailsClient.initialize(instance);
        assertEquals(0.31, XpOrbTrailsClient.CONFIG.width);
        assertEquals(72, XpOrbTrailsClient.CONFIG.renderRange);
        Files.writeString(root.resolve("profiles/simple.json"), "{\"version\":1,\"name\":\"Simple\"}");
        Files.writeString(file, "{\"width\":0.44,\"orbScale\":2.4,\"stampSpacing\":2.7}");
        assertEquals(0, XpOrbTrailsClient.reloadFileStyles());
        assertEquals(0.31, XpOrbTrailsClient.CONFIG.width);
        var profile = XpOrbTrailsClient.fileStyles().stream().filter(p -> p.name().equals("Simple")).findFirst().orElseThrow();
        assertEquals(0.44, profile.settings().width);
        assertEquals(2.4, profile.settings().orbScale);
        assertEquals(2.7, profile.settings().stampSpacing);
        var defaults = XpOrbTrailsClient.defaults();
        defaults.gradientStops = new int[]{0x123456};
        assertEquals(0, XpOrbTrailsClient.defaults().gradientStops.length);
        Files.writeString(file, "{");
        assertTrue(XpOrbTrailsClient.reloadFileStyles() > 0);
        assertEquals(0.44, XpOrbTrailsClient.defaults().width);
        assertEquals("{", Files.readString(file));
        Files.writeString(file, "{\"width\":0.44,\"renderRange\":75}");
        Files.writeString(instance.resolve("xp_orb_trails.json"), "{\"width\":0.56}");
        XpOrbTrailsClient.initialize(instance);
        assertEquals(0.56, XpOrbTrailsClient.CONFIG.width);
        assertEquals(75, XpOrbTrailsClient.CONFIG.renderRange);
    }

    @Test
    void pristineInstanceGetsAnEditableDefaultsFileWithoutPlayerData() throws Exception {
        var file = instance.resolve("xp_orb_trails/defaults.json");
        var library = new ConfigDefaults(instance.resolve("xp_orb_trails"));
        assertEquals(0, library.reload());
        var gson = new Gson();
        JsonObject expected = gson.toJsonTree(new TrailConfig()).getAsJsonObject();
        expected.remove("savedProfiles");
        expected.remove("savedPresets");
        assertEquals(expected, gson.fromJson(Files.readString(file), JsonObject.class));
    }
}
