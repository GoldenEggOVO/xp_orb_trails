package dev.goldeneggovo.xporbtrails;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EffectLibraryTest {
    private static final String EFFECT = """
            {"version":1,"layers":[{"color":"head","count":1,"frames":[
            {"t":0,"radius":0,"angle":0,"x":0,"y":0,"width":0.1,"height":0.1,"rotation":0,"opacity":0},
            {"t":1,"radius":0,"angle":0,"x":0,"y":0,"width":0.1,"height":0.1,"rotation":0,"opacity":1}]}]}
            """;
    @TempDir Path directory;

    @BeforeEach
    void isolatePickupFixtures() throws Exception {
        Files.createDirectories(directory.resolve("xp_orb_trails/orb/effects"));
    }

    private Path effects() {
        return directory.resolve("xp_orb_trails/pickup/effects");
    }

    @Test
    void installsTheBundledEffectForAnExistingProfilesDirectoryAndRespectsEditsAndDeletion() throws Exception {
        Files.createDirectories(directory.resolve("xp_orb_trails/profiles"));
        XpOrbTrailsClient.initialize(directory);
        assertEquals("Twin Orbit", XpOrbTrailsClient.effects().get("file:pickup/twin-orbit").name());
        Path example = effects().resolve("twin-orbit.json");
        Files.writeString(example, EFFECT);
        XpOrbTrailsClient.initialize(directory);
        assertEquals("twin-orbit", XpOrbTrailsClient.effects().get("file:pickup/twin-orbit").name());
        Files.delete(example);
        assertEquals(0, XpOrbTrailsClient.reloadFileStyles());
        XpOrbTrailsClient.initialize(directory);
        assertFalse(Files.exists(example));
        assertEquals(7, XpOrbTrailsClient.effects(AssetRole.PICKUP).size());
    }

    @Test
    void editedPickupFilesControlGeometryAndCopiedReflowEvenAfterRestart() throws Exception {
        Files.createDirectories(effects());
        Files.writeString(effects().resolve("star.json"), EFFECT);
        XpOrbTrailsClient.initialize(directory);
        assertEquals(4, vertices("star").size(), "An existing file must win on first installation");
        assertEquals(12, vertices("soft").size());
        assertEquals(64, vertices("ring").size());
        assertEquals(24, vertices("shards").size());
        assertEquals(96, vertices("spiral").size());
        var ring = effects().resolve("ring.json");
        Files.writeString(ring, Files.readString(ring).replace("\"segments\": 16", "\"segments\": 5"));
        var reflow = effects().resolve("reflow.json");
        Files.copy(reflow, effects().resolve("custom.json"));
        Files.writeString(reflow, Files.readString(reflow).replace("\"reflow\": true", "\"reflow\": false"));
        Files.delete(effects().resolve("soft.json"));
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 0) assertEquals(0, XpOrbTrailsClient.reloadFileStyles());
            else XpOrbTrailsClient.initialize(directory);
            assertFalse(Files.exists(effects().resolve("soft.json")));
            assertNull(XpOrbTrailsClient.effect(AssetRole.PICKUP, "file:pickup/soft"));
            assertEquals(20, vertices("ring").size());
            assertFalse(XpOrbTrailsClient.effect(AssetRole.PICKUP, "file:pickup/reflow").reflow);
            assertTrue(XpOrbTrailsClient.effect(AssetRole.PICKUP, "file:pickup/custom").reflow);
            assertEquals(vertices("reflow"), vertices("custom"));
        }
    }

    private record Vertex(double x, double y, double z, boolean worldAxes, int color) {}

    private List<Vertex> vertices(String stem) {
        var effect = XpOrbTrailsClient.effect(AssetRole.PICKUP, "file:pickup/" + stem);
        var vertices = new ArrayList<Vertex>();
        effect.draw(0.5, 1, new TrailConfig(), 1, 2,
                (x, y, z, worldAxes, argb) -> vertices.add(new Vertex(x, y, z, worldAxes, argb)));
        return vertices;
    }

    @Test
    void listsByFilenameAndKeepsLastGoodEffectsDuringAnInvalidEdit() throws Exception {
        Files.createDirectories(effects());
        Path later = effects().resolve("z.json");
        Path first = effects().resolve("a.json");
        Files.writeString(later, EFFECT);
        Files.writeString(first, EFFECT);
        Files.writeString(effects().resolve("readme.txt"), "ignored");
        EffectLibrary library = new EffectLibrary(directory.resolve("xp_orb_trails"));
        assertEquals(0, library.reload());
        var before = library.effects();
        assertEquals(List.of("file:orb/starfall", "file:pickup/a", "file:pickup/reflow", "file:pickup/ring", "file:pickup/shards",
                "file:pickup/soft", "file:pickup/spiral", "file:pickup/star", "file:pickup/starfall", "file:pickup/z"), List.copyOf(before.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> before.remove("file:pickup/a"));

        Files.writeString(first, "{");
        Files.delete(later);
        Files.writeString(effects().resolve("b.json"), EFFECT);
        Files.writeString(effects().resolve("bad id.json"), EFFECT);
        Files.writeString(effects().resolve("broken.json"), "{");
        assertEquals(3, library.reload());
        assertEquals(10, library.effects().size());
        assertTrue(library.effects().containsKey("file:pickup/b"));
        assertFalse(library.effects().containsKey("file:pickup/z"));
        assertSame(before.get("file:pickup/a"), library.effects().get("file:pickup/a"));
        assertTrue(before.containsKey("file:pickup/z"));

        Files.writeString(first, EFFECT.replace("\"version\"", "\"name\":\"Repaired\",\"version\""));
        assertEquals(2, library.reload());
        assertEquals("Repaired", library.effects().get("file:pickup/a").name());
    }

    @Test
    void boundsFilesByBytesAndRetainsTheMapWhenDirectoryListingFails() throws Exception {
        Files.createDirectories(effects());
        Path file = effects().resolve("exact.json");
        String exact = EFFECT + " ".repeat(64 * 1024 - EFFECT.length());
        Files.writeString(file, exact);
        EffectLibrary library = new EffectLibrary(directory.resolve("xp_orb_trails"));
        assertEquals(0, library.reload());
        var effect = library.effects().get("file:pickup/exact");
        Files.writeString(file, exact + " ");
        assertEquals(1, library.reload());
        assertSame(effect, library.effects().get("file:pickup/exact"));

        Files.move(effects(), effects().resolveSibling("moved"));
        Files.writeString(effects(), "blocked");
        var before = library.effects();
        assertEquals(1, library.reload());
        assertSame(before, library.effects());
    }

    @Test
    void validatesFileIdsAndLetsStylesReferToEffectsAddedLater() throws Exception {
        assertFalse(EffectLibrary.isFileId(null));
        for (String id : List.of("file:pickup/", "FILE:a", "file:pickup/a/b", "file:pickup/../a", "file:pickup/Z", "file:pickup/a.b", "file:pickup/" + "a".repeat(65))) {
            assertFalse(EffectLibrary.isFileId(id), id);
        }
        assertTrue(EffectLibrary.isFileId("file:pickup/09_small-orbit"));
        assertTrue(EffectLibrary.isFileId("file:pickup/" + "x".repeat(64)));

        Path styles = Files.createDirectories(directory.resolve("xp_orb_trails/profiles"));
        Files.createDirectories(effects());
        Files.writeString(styles.resolve("ok.json"), """
                {"version":1,"pickupFlashStyle":"file:pickup/absent","flightEffect":"file:orb/elsewhere"}
                """);
        Files.writeString(styles.resolve("bad.json"), "{\"version\":1,\"flightEffect\":\"shards\"}");
        Files.writeString(styles.resolve("bad-pickup.json"), "{\"version\":1,\"pickupFlashStyle\":\"file:pickup/Bad\"}");
        Files.writeString(effects().resolve("broken.json"), "{");
        XpOrbTrailsClient.initialize(directory);
        assertEquals(3, XpOrbTrailsClient.reloadFileStyles());
        TrailConfig applied = new TrailConfig();
        XpOrbTrailsClient.fileStyles().stream().filter(style -> style.name().equals("ok")).findFirst().orElseThrow()
                .settings().applyTo(applied);
        assertEquals("file:pickup/absent", applied.pickupFlashStyle);
        assertEquals("file:orb/elsewhere", applied.flightEffect);
    }
}
