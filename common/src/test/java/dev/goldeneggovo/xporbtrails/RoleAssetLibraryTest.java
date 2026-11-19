package dev.goldeneggovo.xporbtrails;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RoleAssetLibraryTest {
    private static final Set<String> PICKUP_FILES = Set.of("file:pickup/soft", "file:pickup/star", "file:pickup/ring",
            "file:pickup/reflow", "file:pickup/shards", "file:pickup/spiral", "file:pickup/starfall");

    private static String pennantSprite(AssetRole role) {
        return switch (role) {
            case ORB -> "sprite:orb/luna-moth";
            case PICKUP -> "sprite:pickup/collect-burst";
            case TRAIL -> "sprite:trail/comet-pennant";
        };
    }

    @TempDir Path config;

    @Test
    void freshInstallCreatesOnlyRoleAndProfileFoldersAndLoadsExamples() throws Exception {
        XpOrbTrailsClient.initialize(config);
        try (var children = Files.list(config.resolve("xp_orb_trails"))) {
            assertEquals(Set.of("orb", "pickup", "trail", "profiles"),
                    children.filter(Files::isDirectory).map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        }
        for (var role : AssetRole.values()) {
            assertEquals(Set.of("sprite:" + role.folder + "/comet-strip", pennantSprite(role)), XpOrbTrailsClient.sprites(role).keySet());
            Set<String> expected = switch (role) {
                case ORB -> Set.of("file:orb/starfall", "file:orb/twin-orbit");
                case TRAIL -> Set.of();
                case PICKUP -> pickupFilesWith("file:pickup/twin-orbit");
            };
            assertEquals(expected, XpOrbTrailsClient.effects(role).keySet());
        }
    }

    @Test
    void upgradesStarfallWithoutReplacingTheImportedExampleOrRestoringDeletedFiles() throws Exception {
        Path root = config.resolve("xp_orb_trails");
        Path profiles = Files.createDirectories(root.resolve("profiles"));
        Files.writeString(profiles.resolve(".installed"), "standard.json\nrainbow.json\ncomet-pennant.json\n");
        Path orb = Files.createDirectories(root.resolve("orb/effects")).resolve("starfall.json");
        Path pickup = Files.createDirectories(root.resolve("pickup/effects")).resolve("starfall.json");
        Files.writeString(pickup.getParent().getParent().resolve(".installed-effects"), "soft.json\nstar.json\nring.json\nreflow.json\nshards.json\nspiral.json\n");
        String custom = """
                {"version":1,"name":"My sparks","layers":[{"frames":[{"t":0},{"t":1}]}]}
                """;
        Files.writeString(orb, custom);

        XpOrbTrailsClient.initialize(config);
        assertTrue(Files.exists(profiles.resolve("starfall.json")), "Upgrade must offer the Starfall profile");
        assertEquals("Starfall Bloom", XpOrbTrailsClient.effect(AssetRole.PICKUP, "file:pickup/starfall").name());
        assertEquals(custom, Files.readString(orb));
        assertEquals("My sparks", XpOrbTrailsClient.effect(AssetRole.ORB, "file:orb/starfall").name());

        Files.delete(orb);
        Files.delete(pickup);
        Files.delete(profiles.resolve("starfall.json"));
        XpOrbTrailsClient.initialize(config);
        assertFalse(Files.exists(orb));
        assertFalse(Files.exists(pickup));
        assertFalse(Files.exists(profiles.resolve("starfall.json")));
    }

    @Test
    void installsEveryFileDeclaredInTheSpriteCatalogIntoItsRoleFolder() throws Exception {
        XpOrbTrailsClient.initialize(config);
        String sprites = "/assets/xporbtrails/sprites/";
        try (var manifest = getClass().getResourceAsStream(sprites + "defaults.txt")) {
            assertNotNull(manifest, "Bundled sprite catalog");
            for (String path : new String(manifest.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                String[] parts = path.split("/", 2);
                Path installed = config.resolve("xp_orb_trails").resolve(parts[0]).resolve("sprites").resolve(parts[1]);
                try (var asset = getClass().getResourceAsStream(sprites + path)) {
                    assertNotNull(asset, path);
                    assertArrayEquals(asset.readAllBytes(), Files.readAllBytes(installed), path);
                }
            }
        }
    }

    @Test
    void upgradesExistingSpriteDirectoriesAndLoadsThePennantProfileArtwork() throws Exception {
        Path root = config.resolve("xp_orb_trails");
        for (var role : AssetRole.values()) Files.createDirectories(root.resolve(role.folder + "/sprites"));
        Files.createDirectories(root.resolve("profiles"));
        Files.createFile(root.resolve("profiles/.installed"));
        XpOrbTrailsClient.initialize(config);
        assertEquals(0, XpOrbTrailsClient.reloadFileStyles());
        var cfg = new TrailConfig();
        XpOrbTrailsClient.fileStyles().stream().filter(style -> style.name().equals("Comet Pennant")).findFirst()
                .orElseThrow().settings().applyTo(cfg);
        assertFalse(cfg.showVanillaOrb);
        assertEquals(0.85, cfg.orbScale);
        assertEquals("ribbon", cfg.trailSpriteMode);
        assertEquals(0.48, cfg.ribbonRepeatLength);
        var trail = XpOrbTrailsClient.sprite(AssetRole.TRAIL, cfg.trailSprite);
        assertEquals(new SpriteSheet.Ribbon(10, 14, "tile"), trail.ribbon());
        assertEquals(800, trail.durationMillis());
        assertEquals(56, trail.frameAt(0, true).width());
        var orb = XpOrbTrailsClient.sprite(AssetRole.ORB, cfg.flightEffect);
        assertEquals("Luna Moth", orb.displayName().getString());
        assertEquals(24, orb.frameAt(0, true).width());
        var pickup = XpOrbTrailsClient.sprite(AssetRole.PICKUP, cfg.pickupFlashStyle);
        assertEquals("Collect burst", pickup.displayName().getString());
        assertEquals(42 * 33, pickup.durationMillis());
        assertFalse(pickup.loop());
        for (String credit : Set.of("LICENSE.txt", "NOTICE.txt", "SOURCE.txt")) {
            assertTrue(Files.size(root.resolve("pickup/sprites/collect-burst").resolve(credit)) > 0);
        }
    }

    @Test
    void keepsCustomSpriteFoldersAndHonorsSubsequentAtlasDeletions() throws Exception {
        Path root = config.resolve("xp_orb_trails");
        Path trail = Files.createDirectories(root.resolve("trail/sprites/comet-pennant"));
        assertTrue(ImageIO.write(new BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB), "PNG", trail.resolve("own.png").toFile()));
        XpOrbTrailsClient.initialize(config);
        assertEquals(3, XpOrbTrailsClient.sprite(AssetRole.TRAIL, "sprite:trail/comet-pennant").width());
        assertFalse(Files.exists(trail.resolve("sheet.json")));
        Path orb = root.resolve("orb/sprites/luna-moth");
        Files.delete(orb.resolve("sheet.json"));
        Files.delete(orb.resolve("sheet.png"));
        Files.delete(orb);
        XpOrbTrailsClient.initialize(config);
        assertFalse(Files.exists(orb));
        assertNull(XpOrbTrailsClient.sprite(AssetRole.ORB, "sprite:orb/luna-moth"));
        assertNotNull(XpOrbTrailsClient.sprite(AssetRole.PICKUP, "sprite:pickup/collect-burst"));
    }

    @Test
    void sameNamesResolveByRoleInWorldAndPreviewLookupsAcrossReloads() throws Exception {
        Path root = config.resolve("xp_orb_trails");
        int color = 0xFF000001;
        for (var role : AssetRole.values()) {
            Path dir = Files.createDirectories(root.resolve(role.folder + "/sprites/same"));
            var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, color++);
            assertTrue(ImageIO.write(image, "PNG", dir.resolve("sheet.png").toFile()));
            if (role == AssetRole.TRAIL) continue;
            Path effects = Files.createDirectories(root.resolve(role.folder + "/effects"));
            try (var input = getClass().getResourceAsStream("/assets/xporbtrails/effects/twin-orbit.json")) {
                String effect = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                Files.writeString(effects.resolve("same.json"), effect.replace("Twin Orbit", role.folder));
            }
        }
        Files.createDirectories(root.resolve("sprites/obsolete"));
        Files.copy(root.resolve("orb/sprites/same/sheet.png"), root.resolve("sprites/obsolete/sheet.png"));
        Files.createDirectories(root.resolve("effects"));
        Files.writeString(root.resolve("effects/obsolete.json"), "{");
        Files.createDirectories(root.resolve("styles"));
        Files.writeString(root.resolve("styles/obsolete.json"), "{");

        XpOrbTrailsClient.initialize(config);
        assertEquals(0, XpOrbTrailsClient.reloadFileStyles());
        color = 0xFF000001;
        for (var role : AssetRole.values()) {
            String id = "sprite:" + role.folder + "/same";
            assertEquals(Set.of(id, pennantSprite(role)), XpOrbTrailsClient.sprites(role).keySet());
            assertEquals(color++, XpOrbTrailsClient.sprite(role, id).image().getRGB(0, 0));
            if (role != AssetRole.TRAIL) {
                String effectId = "file:" + role.folder + "/same";
                assertEquals(role == AssetRole.PICKUP ? pickupFilesWith(effectId) : Set.of(effectId, "file:orb/starfall"),
                        XpOrbTrailsClient.effects(role).keySet());
                assertEquals(role.folder, XpOrbTrailsClient.effect(role, effectId).name());
            }
        }
        assertNull(XpOrbTrailsClient.sprite(AssetRole.ORB, "sprite:pickup/same"));
        assertNull(XpOrbTrailsClient.effect(AssetRole.PICKUP, "file:orb/same"));
        assertNotSame(XpOrbTrailsClient.sprite(AssetRole.ORB, "sprite:orb/same"),
                XpOrbTrailsClient.sprite(AssetRole.TRAIL, "sprite:trail/same"));
        var settings = new TrailConfig();
        settings.flightEffect = "sprite:orb/same";
        settings.trailSprite = "body";
        assertSame(XpOrbTrailsClient.sprite(AssetRole.ORB, settings.flightEffect),
                FixedStampHistory.Source.selected(settings).sprite());
        settings.trailSprite = "sprite:trail/same";
        settings.fixedStamps = true;
        assertSame(XpOrbTrailsClient.sprite(AssetRole.TRAIL, settings.trailSprite),
                FixedStampHistory.Source.selected(settings).sprite());
        assertTrue(Files.exists(root.resolve("sprites/obsolete/sheet.png")));
        assertEquals("{", Files.readString(root.resolve("effects/obsolete.json")));
        assertEquals("{", Files.readString(root.resolve("styles/obsolete.json")));

        var oldPickup = XpOrbTrailsClient.sprite(AssetRole.PICKUP, "sprite:pickup/same");
        Files.writeString(root.resolve("pickup/sprites/same/sheet.png"), "broken");
        Files.delete(root.resolve("orb/effects/same.json"));
        assertEquals(1, XpOrbTrailsClient.reloadFileStyles());
        assertSame(oldPickup, XpOrbTrailsClient.sprite(AssetRole.PICKUP, "sprite:pickup/same"));
        assertNull(XpOrbTrailsClient.effect(AssetRole.ORB, "file:orb/same"));
        assertEquals("pickup", XpOrbTrailsClient.effect(AssetRole.PICKUP, "file:pickup/same").name());
    }
    private static Set<String> pickupFilesWith(String id) {
        var ids = new java.util.HashSet<>(PICKUP_FILES);
        ids.add(id);
        return ids;
    }
}
