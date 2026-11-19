package dev.goldeneggovo.xporbtrails;

import com.google.gson.Gson;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CrossSectionTest {
    static final String STAR = "[1,0.35,1,0.35,1,0.35,1,0.35,1,0.35]";
    private static final Gson JSON = new Gson();

    @TempDir Path directory;

    @Test
    void fileProfileSurvivesSavingAndRestartWithItsOrderedRadii() throws Exception {
        var styles = Files.createDirectories(directory.resolve("xp_orb_trails/profiles"));
        Files.writeString(styles.resolve("star.json"), "{\"version\":1,\"crossSectionRadii\":" + STAR + "}");
        XpOrbTrailsClient.initialize(directory);
        assertEquals(0, XpOrbTrailsClient.reloadFileStyles());
        var profile = XpOrbTrailsClient.fileStyles().stream().filter(s -> s.name().equals("star")).findFirst().orElseThrow();
        profile.settings().applyTo(XpOrbTrailsClient.CONFIG);
        XpOrbTrailsClient.CONFIG.savedProfiles.add(new TrailConfig.SavedProfile("My star", XpOrbTrailsClient.CONFIG));
        XpOrbTrailsClient.saveConfig();
        XpOrbTrailsClient.initialize(directory);
        var afterRestart = new TrailConfig();
        var saved = XpOrbTrailsClient.CONFIG.savedProfiles.getFirst();
        saved.applyTo(afterRestart);
        assertTrue(afterRestart.usesCustomCrossSection());
        assertArrayEquals(JSON.fromJson(STAR, double[].class), afterRestart.crossSectionRadii);
        afterRestart.crossSectionRadii[0] = 0.1;
        assertEquals(1, XpOrbTrailsClient.CONFIG.crossSectionRadii[0]);
        assertEquals(1, saved.crossSectionRadii[0]);
        assertEquals(1, profile.settings().crossSectionRadii[0]);
        afterRestart.customCrossSectionEnabled = false;
        afterRestart.crossSectionSides = 8;
        var restored = new TrailConfig();
        JSON.fromJson(JSON.toJson(new TrailConfig.SavedProfile("Octagon", afterRestart)),
                TrailConfig.SavedProfile.class).applyTo(restored);
        assertFalse(restored.usesCustomCrossSection());
        assertEquals(8, restored.crossSectionSides);
        restored.customCrossSectionEnabled = true;
        assertEquals(0.1, restored.crossSectionRadii[0]);
    }

    @Test
    void brokenProfileIsSkippedWhileOtherProfilesStillLoad() throws Exception {
        var styles = Files.createDirectories(directory.resolve("profiles"));
        var invalid = List.of("null", "[1,1]", "[1,0,1]", "[1,-0.1,1]", "[1,1.1,1]", "[1,\"0.5\",1]",
                "[1,null,1]", "[1,1e999,1]", "[1" + ",1".repeat(32) + "]");
        for (int i = 0; i < invalid.size(); i++) Files.writeString(styles.resolve("bad" + i + ".json"),
                "{\"version\":1,\"crossSectionRadii\":" + invalid.get(i) + "}");
        for (var radii : List.of("[]", "[1,0.2,1]", "[1" + ",1".repeat(31) + "]")) {
            Files.writeString(styles.resolve("ok" + radii.length() + ".json"), "{\"version\":1,\"crossSectionRadii\":" + radii + "}");
        }
        assertEquals(invalid.size(), new FileTrailStyles(directory, TrailConfig::new).reload());
    }

    @Test
    void malformedRadiiInPlayerSettingsKeepOtherSettingsAndTheBuiltinSideCount() {
        for (String invalid : List.of("null", "{}", "\"star\"", "[1,null,1]", "[1,\"1\",1]", "[1,1e999,1]")) {
            var config = JSON.fromJson("{\"width\":0.42,\"crossSectionSides\":8,\"crossSectionRadii\":" + invalid + "}", TrailConfig.class).sanitized();
            assertFalse(config.usesCustomCrossSection(), invalid);
            assertEquals(8, config.crossSectionSides);
            assertEquals(0.42, config.width);
        }
    }

    @Test
    void editableDefaultsCanSupplyAStarAndExplicitBuiltinProfilesCanOverrideIt() throws Exception {
        var file = directory.resolve("defaults.json");
        Files.writeString(file, "{\"crossSectionRadii\":" + STAR + "}");
        var defaults = new ConfigDefaults(directory);
        assertEquals(0, defaults.reload());
        assertTrue(defaults.create().usesCustomCrossSection());
        assertArrayEquals(JSON.fromJson(STAR, double[].class), defaults.create().crossSectionRadii);
        var styles = Files.createDirectories(directory.resolve("profiles"));
        Files.writeString(styles.resolve("inherit.json"), "{\"version\":1}");
        Files.writeString(styles.resolve("octagon.json"), "{\"version\":1,\"crossSectionSides\":8}");
        var library = new FileTrailStyles(directory, defaults::create);
        assertEquals(0, library.reload());
        var active = new TrailConfig();
        library.styles().stream().filter(s -> s.name().equals("inherit")).findFirst().orElseThrow().settings().applyTo(active);
        assertTrue(active.usesCustomCrossSection());
        library.styles().stream().filter(s -> s.name().equals("octagon")).findFirst().orElseThrow().settings().applyTo(active);
        assertFalse(active.usesCustomCrossSection());
        assertEquals(8, active.crossSectionSides);
        Files.writeString(file, "{\"crossSectionRadii\":[1,\"0.2\",1]}");
        assertEquals(1, defaults.reload());
        assertTrue(defaults.create().usesCustomCrossSection());
    }

    @Test
    void explicitSideCountOverridesCustomDefaultsAcrossRestarts() throws Exception {
        var assets = Files.createDirectories(directory.resolve("xp_orb_trails"));
        Files.writeString(assets.resolve("defaults.json"), "{\"crossSectionRadii\":" + STAR + "}");
        var settings = directory.resolve("xp_orb_trails.json");
        Files.writeString(settings, "{\"width\":0.42,\"crossSectionSides\":8}");
        XpOrbTrailsClient.initialize(directory);
        assertFalse(XpOrbTrailsClient.CONFIG.usesCustomCrossSection());
        assertEquals(8, XpOrbTrailsClient.CONFIG.crossSectionSides);
        assertEquals(0.42, XpOrbTrailsClient.CONFIG.width);
        XpOrbTrailsClient.initialize(directory);
        assertFalse(XpOrbTrailsClient.CONFIG.usesCustomCrossSection());
        assertTrue(XpOrbTrailsClient.defaults().usesCustomCrossSection());
    }

    @Test
    void worldTubeExtrudesAlternatingRadiiAcrossATaperedRightAngleBend() {
        var config = new TrailConfig();
        config.crossSectionRadii = JSON.fromJson(STAR, double[].class);
        config.width = 1;
        config.tailWidthScale = 0.2;
        config.middleWidthScale = 0.6;
        config.headWidthScale = 1;
        var samples = List.of(sample(0, 0), sample(1, 0), sample(1, 1));
        var mesh = mesh(config, samples, Vec3.ZERO).vertices;
        assertEquals(80, mesh.size());
        double[] widths = {0.1, 0.3, 0.5};
        Vec3[] tangents = {new Vec3(1, 0, 0), new Vec3(1, 0, 1).normalize(), new Vec3(0, 0, 1)};
        for (int i = 0; i < 3; i++) {
            int start = i == 0 ? 0 : (i - 1) * 40 + 1;
            for (int face = 0; face < 10; face++) {
                Vec3 offset = mesh.get(start + face * 4).subtract(samples.get(i).position());
                assertEquals(widths[i] * (face % 2 == 0 ? 1 : 0.35), offset.length(), 1e-6);
                assertEquals(0, offset.dot(tangents[i]), 1e-6);
                assertEquals(mesh.get((face * 4 + 1)), mesh.get(40 + face * 4));
                int segment = i == 2 ? 40 : 0;
                assertEquals(mesh.get(segment + face * 4 + 2), mesh.get(segment + (face + 1) % 10 * 4 + 1));
            }
        }
        var preview = TrailPreviewScreen.crossSectionVertices(2, 12, config.crossSectionRadii);
        assertEquals(20, preview.length);
        for (int i = 0; i < 10; i++) assertEquals(i % 2 == 0 ? 12 : 4.2, Math.hypot(preview[i * 2], preview[i * 2 + 1]), 1e-6);
    }

    @Test
    void switchingOffTheStarProducesTheOriginalBuiltinMeshesIncludingTheBillboard() {
        var config = new TrailConfig();
        config.crossSectionRadii = JSON.fromJson(STAR, double[].class);
        config.customCrossSectionEnabled = false;
        config.width = 0.24;
        config.startColor = config.endColor = 0xFFFFFF;
        config.colorMode = "solid";
        config.tailWidthScale = config.middleWidthScale = config.headWidthScale = 1;
        var samples = List.of(sample(0, 0), sample(1, 0), sample(2, 0));
        var camera = new Vec3(0, 0, 3);
        for (int sides = 2; sides <= 32; sides++) {
            config.crossSectionSides = sides;
            var mesh = mesh(config, samples, camera);
            assertEquals(8 * (sides == 2 ? 1 : sides), mesh.vertices.size());
            // Float coordinates and ARGB fingerprints recorded by the original client geometry test.
            switch (sides) {
                case 2 -> assertEquals(6052789791297900145L, mesh.fingerprint);
                case 3 -> assertEquals(61044112680327065L, mesh.fingerprint);
                case 8 -> assertEquals(4513098808163761609L, mesh.fingerprint);
                case 32 -> assertEquals(-2688908051212465271L, mesh.fingerprint);
            }
        }
    }

    private static TrailRenderer.Sample sample(double x, double z) {
        return new TrailRenderer.Sample(new Vec3(x, 0, z), 0);
    }

    private record Mesh(List<Vec3> vertices, long fingerprint) {}

    private static Mesh mesh(TrailConfig config, List<TrailRenderer.Sample> samples, Vec3 camera) {
        var positions = new ArrayList<Vec3>();
        long[] fingerprint = {1};
        var out = (VertexConsumer) Proxy.newProxyInstance(CrossSectionTest.class.getClassLoader(), new Class<?>[]{VertexConsumer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addVertex")) {
                        positions.add(new Vec3((float) args[0], (float) args[1], (float) args[2]));
                        for (var v : args) fingerprint[0] = fingerprint[0] * 31 + Float.floatToIntBits((float) v);
                    } else if (method.getName().equals("setColor")) {
                        fingerprint[0] = fingerprint[0] * 31 + (int) args[0];
                    }
                    return proxy;
                });
        TrailRenderer.appendStyledTube(out, samples, camera, 0, 100, config, 0.78, -1, false);
        return new Mesh(positions, fingerprint[0]);
    }
}
