package dev.goldeneggovo.xporbtrails;

import org.junit.jupiter.api.Test;
import com.google.gson.Gson;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TrailConfigTest {
    @Test
    void storesTrailLayoutsAcrossProfileCopiesAndConfigReloads() {
        var source = new TrailConfig();
        source.trailSprite = "body";
        source.fixedStamps = true;
        source.ribbonMapping = "tile";
        source.ribbonRepeatLength = 0.75;
        var profile = new TrailConfig.SavedProfile("Runes", source);
        var gson = new Gson();
        var restored = new TrailConfig();
        gson.fromJson(gson.toJson(profile), TrailConfig.SavedProfile.class).applyTo(restored);
        var disk = gson.fromJson(gson.toJson(restored), TrailConfig.class).sanitized();
        assertEquals("body", disk.trailSprite);
        assertEquals(true, disk.fixedStamps);
        assertEquals("tile", disk.ribbonMapping);
        assertEquals(0.75, disk.ribbonRepeatLength);
        var partial = gson.fromJson("{\"trailSpriteMode\":\"ribbon\"}", TrailConfig.class).sanitized();
        assertEquals(false, partial.fixedStamps);
        assertEquals("stretch", partial.ribbonMapping);
        assertEquals(0.5, partial.ribbonRepeatLength);
        disk.ribbonMapping = "typo";
        disk.ribbonRepeatLength = 0;
        assertEquals("stretch", disk.sanitized().ribbonMapping);
        assertEquals(0.1, disk.ribbonRepeatLength);
    }

    @Test
    void customColorsAndAnimationSurviveProfileCopyAndReload() {
        TrailConfig config = new TrailConfig();
        config.startColor = 0;
        config.endColor = 0xffffff;
        config.gradientStops = new int[]{0xff0000};
        config.flightPulse = true;
        config.flightEffect = "file:orb/favorite";
        config.trailSprite = "sprite:trail/comet-strip";
        config.showVanillaOrb = false;
        config.orbScale = 1.5;
        config.trailSpriteMode = "ribbon";
        config.stampSpacing = 2.75;
        config.stampScale = 0.75;
        config.pulseSpeed = 2.3;
        config.shardCount = 10;
        config.spiralTurns = 1.25;
        for (String style : new String[]{"file:pickup/reflow", "file:pickup/shards", "file:pickup/spiral", "file:pickup/other-effect", "sprite:pickup/collect-burst"}) {
            config.pickupFlashStyle = style;
            var profile = new TrailConfig.SavedProfile("Test", config);
            var gson = new Gson();
            var restored = new TrailConfig();
            gson.fromJson(gson.toJson(profile), TrailConfig.SavedProfile.class).applyTo(restored);
            assertEquals(style, restored.pickupFlashStyle);
            assertEquals(true, restored.flightPulse);
            assertEquals("file:orb/favorite", restored.flightEffect);
            assertEquals("sprite:trail/comet-strip", restored.trailSprite);
            assertEquals(false, restored.showVanillaOrb);
            assertEquals(1.5, restored.orbScale);
            assertEquals("ribbon", restored.trailSpriteMode);
            assertEquals(2.75, restored.stampSpacing);
            assertEquals(0.75, restored.stampScale);
            assertEquals(2.3, restored.pulseSpeed);
            assertEquals(10, restored.shardCount);
            assertEquals(1.25, restored.spiralTurns);
            assertEquals(0xff0000, restored.gradientColor(0.5));
            assertEquals(0x7f0000, restored.gradientColor(0.25));
            assertEquals(0xffffff, restored.gradientColor(1));
            restored.gradientStops[0] = 0;
            assertArrayEquals(new int[]{0xff0000}, config.gradientStops);
        }
    }

    @Test
    void missingSettingsAndInvalidFileIdsGetSafeDefaults() {
        var gson = new Gson();
        TrailConfig config = gson.fromJson("{}", TrailConfig.class).sanitized();
        assertEquals("none", config.flightEffect);
        assertEquals("none", config.trailSprite);
        config.flightEffect = "sprite:orb/future";
        config.trailSprite = "file:orb/vector";
        config.sanitized();
        assertEquals("sprite:orb/future", config.flightEffect);
        assertEquals("none", config.trailSprite);
        for (String invalid : new String[]{null, "", "file:", "file:../x", "file:old", "sprite:old",
                "file:pickup/alien", "sprite:trail/alien", "soft"}) {
            config.flightEffect = invalid;
            assertEquals("none", config.sanitized().flightEffect);
        }
        config.pickupFlashStyle = "file:../x";
        assertEquals("file:pickup/soft", config.sanitized().pickupFlashStyle);
    }

    @Test
    void repairsNonFiniteNumbersWithoutDiscardingOtherSettings() throws ReflectiveOperationException {
        Gson gson = new Gson();
        TrailConfig defaults = new TrailConfig();
        for (var field : TrailConfig.class.getFields()) {
            if (field.getType() != double.class) continue;
            for (String value : new String[]{"NaN", "Infinity", "-Infinity"}) {
                var json = gson.toJsonTree(defaults).getAsJsonObject();
                json.addProperty(field.getName(), value);
                json.addProperty("startColor", 0x123456);
                TrailConfig repaired = gson.fromJson(json, TrailConfig.class).sanitized();
                assertEquals(field.getDouble(defaults), field.getDouble(repaired), field.getName() + "=" + value);
                assertEquals(0x123456, repaired.startColor);
                gson.toJson(repaired);
            }
        }
    }

    @Test
    void repairsNonFiniteSavedProfilesBeforeSavingAndApplying() throws ReflectiveOperationException {
        Gson gson = new Gson();
        TrailConfig defaults = new TrailConfig();
        for (var field : TrailConfig.SavedProfile.class.getFields()) {
            if (field.getType() != double.class) continue;
            for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                TrailConfig config = new TrailConfig();
                var profile = new TrailConfig.SavedProfile("Keep my name", defaults);
                field.setDouble(profile, value);
                config.savedProfiles.add(profile);
                config.sanitized();
                assertEquals(TrailConfig.class.getField(field.getName()).getDouble(defaults), field.getDouble(profile), field.getName());
                assertEquals("Keep my name", profile.name);
                profile.applyTo(config);
                gson.toJson(config);
            }
        }
    }

    @Test
    void crossSectionSurvivesProfilesAndReloadAndClampsInvalidValues() {
        Gson gson = new Gson();
        TrailConfig config = gson.fromJson("{\"crossSectionSides\":32}", TrailConfig.class).sanitized();
        assertEquals(32, gson.toJsonTree(config).getAsJsonObject().get("crossSectionSides").getAsInt());
        TrailConfig copy = new TrailConfig();
        TrailConfig.SavedProfile profile = new TrailConfig.SavedProfile("Round", config);
        profile = gson.fromJson(gson.toJson(profile), TrailConfig.SavedProfile.class);
        profile.applyTo(copy);
        assertEquals(32, gson.toJsonTree(copy).getAsJsonObject().get("crossSectionSides").getAsInt());
        for (int value : new int[]{-1, 2, 3, 8, 32, 100}) {
            TrailConfig loaded = gson.fromJson("{\"crossSectionSides\":" + value + "}", TrailConfig.class).sanitized();
            assertEquals(Math.max(2, Math.min(32, value)), gson.toJsonTree(loaded).getAsJsonObject().get("crossSectionSides").getAsInt());
        }
        assertEquals(2, gson.fromJson("{}", TrailConfig.class).crossSectionSides);
        assertEquals(0.1, new TrailConfig().width);
        TrailConfig existing = gson.fromJson("{\"width\":0.24,\"crossSectionSides\":8}", TrailConfig.class).sanitized();
        assertEquals(0.24, existing.width);
        assertEquals(8, existing.crossSectionSides);
    }

    @Test
    void newProfilesAndCopiesPreserveIndependentControlsAfterReload() {
        TrailConfig config = new TrailConfig();
        config.effectStrength = 1.35;
        config.pickupFlashSize = 1.7;
        config.savedProfiles.add(new TrailConfig.SavedProfile("New", config));
        Gson gson = new Gson();
        TrailConfig copy = gson.fromJson(gson.toJson(config), TrailConfig.class).sanitized();
        copy.width = 0.8;
        copy.effectStrength = 0.5;
        copy.savedProfiles.getFirst().applyTo(copy);
        assertEquals(1.35, copy.effectStrength);
        assertEquals(1.7, copy.pickupFlashSize);
        copy.width = 0.1;
        assertEquals(1.7, copy.sanitized().pickupFlashSize);
    }

    @Test
    void sanitizesInvalidSettingsAndSavedProfiles() {
        TrailConfig config = new TrailConfig();
        config.trailCap = 999;
        config.renderRange = -1;
        config.effectStrength = -1;
        config.colorMode = "invalid";
        config.pickupFlashStyle = "invalid";
        config.savedProfiles = new ArrayList<>();
        config.savedProfiles.add(new TrailConfig.SavedProfile("", config));
        for (int i = 0; i < 10; i++) {
            config.savedProfiles.add(new TrailConfig.SavedProfile("Profile " + i, config));
        }

        config.sanitized();

        assertEquals(256, config.trailCap);
        assertEquals(4.0, config.renderRange);
        assertEquals(0.005, config.effectStrength);
        assertEquals("gradient", config.colorMode);
        assertEquals("file:pickup/soft", config.pickupFlashStyle);
        assertEquals(8, config.savedProfiles.size());
        assertEquals("Profile 0", config.savedProfiles.getFirst().name);
    }

    @Test
    void savedProfileRestoresCapturedAppearance() {
        TrailConfig config = new TrailConfig();
        config.enabled = false;
        config.renderRange = 71;
        config.trailCap = 24;
        config.width = 0.42;
        config.colorMode = "rainbow";
        config.pickupFlashStyle = "file:pickup/ring";
        TrailConfig.SavedProfile profile = new TrailConfig.SavedProfile("Favorite", config);
        config.enabled = true;
        config.renderRange = 36;
        config.trailCap = 96;
        config.width = 0.12;
        config.colorMode = "solid";
        config.pickupFlashStyle = "file:pickup/soft";

        profile.applyTo(config);

        assertEquals(false, config.enabled);
        assertEquals(71, config.renderRange);
        assertEquals(24, config.trailCap);
        assertEquals(0.42, config.width);
        assertEquals("rainbow", config.colorMode);
        assertEquals("file:pickup/ring", config.pickupFlashStyle);
    }
}
