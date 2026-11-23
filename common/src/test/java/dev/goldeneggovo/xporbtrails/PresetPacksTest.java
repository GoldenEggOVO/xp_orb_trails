package dev.goldeneggovo.xporbtrails;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class PresetPacksTest {
    @TempDir Path config;
    private static final String PROFILE = """
            {"version":1,"name":"Shared Look","flightEffect":"sprite:orb/spark","pickupFlashStyle":"file:pickup/soft",
             "trailSprite":"sprite:trail/comet-strip","advanced":{"workshop":[{"type":"relief","sprite":"sprite:orb/spark"}]}}
            """;

    private Path packs() { return config.resolve("xp_orb_trails/packs"); }

    private void pack(String archive, String prefix, int color) throws Exception {
        var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, color);
        var png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        byte[] effect;
        try (var source = getClass().getResourceAsStream("/assets/xporbtrails/effects/pickup/soft.json")) {
            assertNotNull(source);
            effect = source.readAllBytes();
        }
        Files.createDirectories(packs());
        try (var zip = new ZipOutputStream(Files.newOutputStream(packs().resolve(archive)))) {
            for (var entry : Map.of("profiles/sample.json", PROFILE.getBytes(StandardCharsets.UTF_8),
                    "orb/sprites/spark/star.png", png.toByteArray(), "pickup/effects/soft.json", effect).entrySet()) {
                zip.putNextEntry(new ZipEntry(prefix + entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    private static FileTrailStyles.Style profile(String archive) {
        return XpOrbTrailsClient.fileStyles().stream().filter(s -> "Shared Look".equals(s.name())
                && s.displayName().getString().contains(archive)).findFirst().orElseThrow();
    }

    @Test void publishedCollectionIsOptionalAndResolvesArtworkAtEveryDepth() throws Exception {
        var defaults = List.of("Comet Pennant", "rainbow", "standard", "Starfall");
        XpOrbTrailsClient.initialize(config);
        assertEquals(defaults, XpOrbTrailsClient.fileStyles().stream().map(FileTrailStyles.Style::name).toList());
        var defaultSprites = Set.copyOf(XpOrbTrailsClient.sprites().keySet());
        var defaultEffects = Set.copyOf(XpOrbTrailsClient.effects().keySet());
        var all = EffectCollectionFixture.install(config);
        assertEquals(28, all.size());
        var packed = all.stream().filter(s -> EffectCollectionFixture.ARCHIVE.getFileName().toString().equals(s.pack())).toList();
        assertEquals(Set.of("workshop-observatory", "workshop-willow", "workshop-storm", "workshop-tideglass",
                "workshop-frost", "workshop-festival", "workshop-dusk", "workshop-mirage", "workshop-beacon",
                "lab-directional", "lab-relief", "lab-fracture", "lab-crossing", "lab-redstone", "lab-fusion",
                "lab-rig", "lab-brush", "lab-morph", "lab-hoops", "lab-hand", "lab-ink", "lab-events",
                "lab-butterfly", "lab-river"), packed.stream().map(FileTrailStyles.Style::name).collect(Collectors.toSet()));
        for (var style : packed) {
            var active = new TrailConfig();
            style.settings().applyTo(active);
            var source = EffectCollectionFixture.profile(style.name());
            var loaded = new Gson().toJsonTree(active).getAsJsonObject();
            for (var field : source.entrySet()) {
                checkReferences(field.getValue(), loaded.get(field.getKey()), style.name() + "." + field.getKey(),
                        defaultSprites, defaultEffects);
            }
        }
        Files.delete(packs().resolve(EffectCollectionFixture.ARCHIVE.getFileName()));
        assertEquals(0, XpOrbTrailsClient.reloadFileStyles());
        assertEquals(defaults, XpOrbTrailsClient.fileStyles().stream().map(FileTrailStyles.Style::name).toList());
        assertEquals(defaultSprites, XpOrbTrailsClient.sprites().keySet());
        assertEquals(defaultEffects, XpOrbTrailsClient.effects().keySet());
    }

    private static void checkReferences(JsonElement source, JsonElement loaded, String path,
                                        Set<String> defaultSprites, Set<String> defaultEffects) {
        if (source.isJsonObject()) {
            for (var field : source.getAsJsonObject().entrySet()) {
                var child = loaded != null && loaded.isJsonObject() ? loaded.getAsJsonObject().get(field.getKey()) : null;
                checkReferences(field.getValue(), child, path + "." + field.getKey(), defaultSprites, defaultEffects);
            }
        } else if (source.isJsonArray()) {
            var array = source.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                var child = loaded != null && loaded.isJsonArray() && i < loaded.getAsJsonArray().size()
                        ? loaded.getAsJsonArray().get(i) : null;
                checkReferences(array.get(i), child, path + "[" + i + "]", defaultSprites, defaultEffects);
            }
        } else if (source.isJsonPrimitive() && source.getAsJsonPrimitive().isString()) {
            String original = source.getAsString();
            boolean sprite = original.startsWith("sprite:");
            if (!sprite && !original.startsWith("file:")) return;
            assertNotNull(loaded, path);
            String bound = loaded.getAsString();
            if ((sprite ? defaultSprites : defaultEffects).contains(original)) {
                assertEquals(original, bound, path);
            } else {
                assertTrue(bound.startsWith(original + ":"), path + " was not bound to pack artwork: " + bound);
            }
            var role = java.util.Arrays.stream(AssetRole.values()).filter(r -> r.accepts(original)).findFirst().orElseThrow();
            assertNotNull(sprite ? XpOrbTrailsClient.sprite(role, bound) : XpOrbTrailsClient.effect(role, bound), path);
        }
    }

    @Test void zipProfilesResolveTheirOwnArtworkAndSurviveRestart() throws Exception {
        pack("one.zip", "", 0xff00ff00);
        pack("彩色.ZIP", "published/config/xp_orb_trails/", 0xffaa0044);
        XpOrbTrailsClient.initialize(config);
        profile("彩色").settings().applyTo(XpOrbTrailsClient.CONFIG);
        var active = XpOrbTrailsClient.CONFIG;
        assertEquals(active.flightEffect, active.advanced.getAsJsonArray("workshop").get(0).getAsJsonObject().get("sprite").getAsString());
        assertEquals("sprite:trail/comet-strip", active.trailSprite);
        assertNotNull(XpOrbTrailsClient.effect(AssetRole.PICKUP, active.pickupFlashStyle));
        String id = active.flightEffect;
        assertEquals(0xffaa0044, XpOrbTrailsClient.sprite(AssetRole.ORB,id).image().getRGB(0,0));
        var other = profile("one").settings();
        assertNotEquals(id, other.flightEffect);
        assertNotEquals(other.pickupFlashStyle, active.pickupFlashStyle);
        assertEquals(0xff00ff00, XpOrbTrailsClient.sprite(AssetRole.ORB,other.flightEffect).image().getRGB(0,0));
        XpOrbTrailsClient.saveConfig();
        XpOrbTrailsClient.initialize(config);
        assertEquals(id, XpOrbTrailsClient.CONFIG.flightEffect);
        assertNotNull(XpOrbTrailsClient.sprite(AssetRole.ORB,id));
        assertFalse(Files.exists(config.resolve("xp_orb_trails/orb/sprites/spark")));
        assertFalse(Files.exists(config.resolve("xp_orb_trails/profiles/sample.json")));
    }

    @Test void replacingOrDeletingAnArchiveTakesEffectOnReload() throws Exception {
        XpOrbTrailsClient.initialize(config);
        assertTrue(Files.isDirectory(packs()));
        pack("demo.zip", "config/xp_orb_trails/", 0xff4400cc);
        assertEquals(0, XpOrbTrailsClient.reloadFileStyles());
        String id = profile("demo").settings().flightEffect;
        pack("demo.zip", "config/xp_orb_trails/", 0xffcc0044);
        assertEquals(0, XpOrbTrailsClient.reloadFileStyles());
        assertEquals(0xffcc0044, XpOrbTrailsClient.sprite(AssetRole.ORB,id).image().getRGB(0,0));
        Files.writeString(packs().resolve("broken.zip"), "incomplete download");
        assertTrue(XpOrbTrailsClient.reloadFileStyles() > 0);
        assertEquals(id, profile("demo").settings().flightEffect);
        Files.delete(packs().resolve("demo.zip"));
        Files.delete(packs().resolve("broken.zip"));
        assertEquals(0, XpOrbTrailsClient.reloadFileStyles());
        assertNull(XpOrbTrailsClient.sprite(AssetRole.ORB,id));
        assertTrue(XpOrbTrailsClient.fileStyles().stream().noneMatch(s -> "Shared Look".equals(s.name())));
    }

    @Test void optionalEffectSpritesUseArtworkFromTheSamePack() throws Exception {
        Files.createDirectories(packs());
        try (var source = new ZipFile(EffectCollectionFixture.ARCHIVE.toFile());
             var zip = new ZipOutputStream(Files.newOutputStream(packs().resolve("implicit.zip")))) {
            for (var entry : source.stream().filter(e -> e.getName().contains("/sprites/") && !e.isDirectory()).toList()) {
                zip.putNextEntry(new ZipEntry(entry.getName()));
                try (var input = source.getInputStream(entry)) { input.transferTo(zip); }
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("profiles/firefly.json"));
            zip.write("""
                    {"version":1,"showVanillaOrb":false,"advanced":{"body":{"type":"firefly"},"pickup":{"type":"petals"},
                     "workshop":[{"type":"directional"},{"type":"assemble"}]}}
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("profiles/bird.json"));
            zip.write("""
                    {"version":1,"advanced":{"body":{"type":"bird","glideSprite":"sprite:orb/luna-moth"}}}
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        XpOrbTrailsClient.initialize(config);
        assertEquals(0, XpOrbTrailsClient.reloadFileStyles());
        var packed = XpOrbTrailsClient.fileStyles().stream().filter(s -> "implicit.zip".equals(s.pack()))
                .collect(Collectors.toMap(FileTrailStyles.Style::name, FileTrailStyles.Style::settings));
        var active = XpOrbTrailsClient.CONFIG;
        packed.get("firefly").applyTo(active);
        assertTrue(BodyPickupEffects.hasBody(active), "Firefly body should use the pack's atlas");
        var pickup = active.advanced.getAsJsonObject("pickup").get("sprite").getAsString();
        assertNotNull(XpOrbTrailsClient.sprite(AssetRole.PICKUP, pickup));
        var workshop = active.advanced.getAsJsonArray("workshop");
        assertNotNull(XpOrbTrailsClient.sprite(AssetRole.ORB, workshop.get(0).getAsJsonObject().get("sprite").getAsString()));
        assertFalse(workshop.get(1).getAsJsonObject().has("sprite"), "Use the built-in moth when the pack has none");
        packed.get("bird").applyTo(active);
        assertTrue(BodyPickupEffects.hasBody(active), "Hover and glide artwork should resolve independently");
        assertEquals("sprite:orb/luna-moth", active.advanced.getAsJsonObject("body").get("glideSprite").getAsString());
    }
}
