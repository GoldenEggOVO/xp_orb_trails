package dev.goldeneggovo.xporbtrails;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

final class TrailSpriteClientTest {
    private static final int ORB_ID = 100402;

    static void verifyWorld(ClientGameTestContext context) {
        Path sprites = FabricLoader.getInstance().getConfigDir().resolve("xp_orb_trails");
        try {
            Path trailSprites = sprites.resolve("trail/sprites");
            Path probe = Files.createTempDirectory(trailSprites, "pixel-probe-");
            Path orbProbe = Files.createDirectory(sprites.resolve("orb/sprites").resolve(probe.getFileName()));
            Path pickupProbe = Files.createDirectory(sprites.resolve("pickup/sprites").resolve(probe.getFileName()));
            try {
                var image = new BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 16; x++) {
                        if ((x % 8 < 4) == (x < 8)) image.setRGB(x, y, 0xFFFF00FF);
                        else if (x >= 8) image.setRGB(x, y, 0xFF00FFFF);
                    }
                }
                ImageIO.write(image, "png", probe.resolve("sheet.png").toFile());
                String metadata = """
                        {"name":"Pixel probe","image":"sheet.png","frameWidth":8,"frameHeight":8,
                         "frames":2,"fps":1,"loop":%s}
                        """;
                Files.writeString(probe.resolve("sheet.json"), metadata.formatted(false));
                for (Path sibling : List.of(orbProbe, pickupProbe)) {
                    Files.copy(probe.resolve("sheet.png"), sibling.resolve("sheet.png"));
                    Files.copy(probe.resolve("sheet.json"), sibling.resolve("sheet.json"));
                }
                String id = "sprite:trail/" + probe.getFileName();
                String bodyId = "sprite:orb/" + probe.getFileName();
                String pickupId = "sprite:pickup/" + probe.getFileName();
                context.runOnClient(client -> {
                    XpOrbTrailsClient.reloadFileStyles();
                    require(XpOrbTrailsClient.sprites(AssetRole.TRAIL).containsKey(id), "PNG probe must load from disk");
                    XpOrbTrailsClient.CONFIG = probeConfig();
                    TrailRenderer.updateWorld(null);
                    addOrb(client);
                });
                context.waitTicks(2);
                int off = capture(context, "sprite-flight-off");
                verifyBody(context, bodyId);
                for (Path location : List.of(probe, orbProbe, pickupProbe))
                    Files.writeString(location.resolve("sheet.json"), metadata.formatted(true));
                context.runOnClient(client -> XpOrbTrailsClient.reloadFileStyles());

                // The camera faces +Z. The finished orb is on the left, with its recorded tail crossing screen center.
                context.runOnClient(client -> {
                    var c = XpOrbTrailsClient.CONFIG;
                    c.flightEffect = "none";
                    c.trailSprite = id;
                    var orb = (ExperienceOrb) client.level.getEntity(ORB_ID);
                    var camera = client.gameRenderer.mainCamera().position();
                    for (int i = 0; i <= 14; i++) {
                        orb.setPos(camera.x + 1.45 - (14 - i) * 0.2, camera.y, camera.z + 4);
                        TrailRenderer.track(orb);
                    }
                });
                context.waitTicks(3);
                int history = TrailEffectsClientTest.captureMagenta(context, "sprite-trail-history", 0.52, 0.71);
                require(history >= off + 16, "Trail PNG must draw away from the orb along its recorded path: " + history);
                context.runOnClient(client -> XpOrbTrailsClient.CONFIG.trailSprite = "sprite:trail/missing-probe");
                context.waitTicks(2);
                int missing = TrailEffectsClientTest.captureMagenta(context, "sprite-trail-missing", 0.52, 0.71);
                require(missing + 16 <= history, "Removing the trail sprite must clear its pixels");

                var pickedAt = new AtomicLong();
                context.runOnClient(client -> {
                    client.level.removeEntity(ORB_ID, Entity.RemovalReason.DISCARDED);
                    TrailRenderer.updateWorld(null);
                    var c = XpOrbTrailsClient.CONFIG;
                    c.trailSprite = "none";
                    c.pickupFlash = true;
                    c.pickupFlashStyle = pickupId;
                    c.pickupFlashSeconds = 0.05;
                    pickUp(client, addOrb(client));
                    pickedAt.set(System.nanoTime());
                });
                context.waitFor(client -> System.nanoTime() - pickedAt.get() >= 150_000_000L);
                require(capture(context, "sprite-pickup") >= off + 20, "Pickup PNG must outlast the built-in duration");
                context.waitFor(client -> System.nanoTime() - pickedAt.get() >= 2_100_000_000L, 1500);
                require(capture(context, "sprite-pickup-finished") < off + 8, "A looping PNG must play only once on pickup");
                verifyTrailModes(context, trailSprites, bodyId, id);
            } finally {
                removeFixture(probe, context);
                removeFixture(orbProbe, context);
                removeFixture(pickupProbe, context);
            }

            String fixture = System.getProperty("xporb.spriteFixture");
            if (fixture != null) verifyCollectBurst(context, sprites.resolve("pickup/sprites"), Path.of(fixture));
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    private static void verifyBody(ClientGameTestContext context, String id) {
        var visible = measure(context, "orb-native-visible");
        context.runOnClient(client -> XpOrbTrailsClient.CONFIG.showVanillaOrb = false);
        context.waitTicks(3);
        var hidden = measure(context, "orb-native-hidden");
        require(visible.green >= hidden.green + 18, "The native orb must disappear when hidden");
        context.runOnClient(client -> XpOrbTrailsClient.CONFIG.enabled = false);
        context.waitTicks(3);
        require(measure(context, "orb-native-master-disabled").green >= hidden.green + 18,
                "Disabling the mod must restore the native orb immediately");
        long started = context.computeOnClient(client -> {
            var c = XpOrbTrailsClient.CONFIG;
            c.enabled = true;
            c.flightEffect = id;
            TrailRenderer.updateWorld(null);
            TrailRenderer.track((ExperienceOrb) client.level.getEntity(ORB_ID));
            return System.nanoTime();
        });
        context.waitFor(client -> System.nanoTime() - started >= 2_200_000_000L, 1500);
        var first = measure(context, "orb-replacement-loop-first");
        context.waitFor(client -> System.nanoTime() - started >= 3_200_000_000L, 1500);
        var second = measure(context, "orb-replacement-loop-second");
        require(first.magenta >= 20 && second.magenta >= 20 && Math.abs(first.magentaX - second.magentaX) >= 4,
                "Both halves of the two-frame non-looping sheet must animate again on the hidden native orb");
        require(first.green <= hidden.green + 4 && second.green <= hidden.green + 4,
                "Replacing the orb must keep native pixels hidden");
        context.runOnClient(client -> XpOrbTrailsClient.CONFIG.orbScale = 2);
        context.waitTicks(3);
        require(measure(context, "orb-replacement-scale-2").magenta >= first.magenta * 2,
                "Doubling the replacement orb scale must increase its pixel area");
        verifyReplacementFallbacks(context, id, hidden.green);
    }

    private static void verifyReplacementFallbacks(ClientGameTestContext context, String artwork, int hiddenGreen) {
        context.runOnClient(client -> {
            var c = XpOrbTrailsClient.CONFIG;
            c.renderRange = 4;
            var orb = (ExperienceOrb) client.level.getEntity(ORB_ID);
            var camera = client.gameRenderer.mainCamera().position();
            orb.setPos(camera.x, camera.y, camera.z + 6);
        });
        context.waitTicks(3);
        var distant = measure(context, "orb-replacement-outside-range");
        require(distant.green > hiddenGreen + 6 && distant.magenta == 0,
                "An orb outside the trail range must keep its native body");

        context.runOnClient(client -> {
            var c = XpOrbTrailsClient.CONFIG;
            c.renderRange = 36;
            c.trailCap = 8;
            TrailRenderer.updateWorld(null);
            var camera = client.gameRenderer.mainCamera().position();
            for (int i = 1; i <= 8; i++) {
                var other = new ExperienceOrb(client.level, camera.x - i, camera.y, camera.z - 5, 1);
                other.setId(ORB_ID + i);
                other.setNoGravity(true);
                other.setDeltaMovement(Vec3.ZERO);
                client.level.addEntity(other);
                TrailRenderer.track(other);
            }
            var orb = (ExperienceOrb) client.level.getEntity(ORB_ID);
            orb.setPos(camera.x, camera.y, camera.z + 4);
            TrailRenderer.track(orb);
        });
        try {
            context.waitTicks(3);
            var overCap = measure(context, "orb-replacement-over-cap");
            require(overCap.green >= hiddenGreen + 18 && overCap.magenta == 0,
                    "A nearby orb outside the trail cap must keep its native body");
        } finally {
            context.runOnClient(client -> {
                for (int i = 1; i <= 8; i++) client.level.removeEntity(ORB_ID + i, Entity.RemovalReason.DISCARDED);
                TrailRenderer.updateWorld(null);
                XpOrbTrailsClient.CONFIG.trailCap = 96;
                TrailRenderer.track((ExperienceOrb) client.level.getEntity(ORB_ID));
            });
        }
        context.runOnClient(client -> XpOrbTrailsClient.CONFIG.flightEffect = "sprite:orb/missing-probe");
        context.waitTicks(3);
        var missing = measure(context, "orb-replacement-art-missing");
        require(missing.green >= hiddenGreen + 18 && missing.magenta == 0,
                "A missing replacement must fall back to the native body");
        context.runOnClient(client -> XpOrbTrailsClient.CONFIG.flightEffect = artwork);
    }

    private static void verifyTrailModes(ClientGameTestContext context, Path sprites, String bodyId, String trailId) throws IOException {
        Path folder = Files.createTempDirectory(sprites, "ribbon-probe-");
        Path caps = Files.createTempDirectory(sprites, "caps-probe-");
        try {
            var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++)
                image.setRGB(x, y, y < 5 || x < 4 ? 0xFFFF00FF : 0xFF00FFFF);
            ImageIO.write(image, "png", folder.resolve("sheet.png").toFile());
            Files.writeString(folder.resolve("sheet.json"), """
                    {"name":"Asymmetric ribbon","image":"sheet.png","frameWidth":8,"frameHeight":8,"frames":1}
                    """);
            var capImage = new BufferedImage(16, 4, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 4; y++) for (int x = 0; x < 16; x++) {
                int color = x < 4 ? 0xFFFF00FF : x < 8 ? 0xFF00FFFF : x < 12 ? 0 : 0xFFFFFF00;
                capImage.setRGB(x, y, color);
            }
            ImageIO.write(capImage, "png", caps.resolve("sheet.png").toFile());
            Files.writeString(caps.resolve("sheet.json"), """
                    {"name":"Sheet with caps","image":"sheet.png","frameWidth":16,"frameHeight":4,
                     "ribbon":{"tailPixels":4,"headPixels":4,"middle":"tile"}}
                    """);
            String capsId = "sprite:trail/" + caps.getFileName();
            context.runOnClient(client -> {
                XpOrbTrailsClient.reloadFileStyles();
                var c = XpOrbTrailsClient.CONFIG = probeConfig();
                c.showVanillaOrb = false;
                c.lifetimeSeconds = 10;
                c.smoothFlow = 0;
                c.trailSprite = "sprite:trail/" + folder.getFileName();
                c.stampSpacing = 4;
                c.stampScale = 0.65;
                addOrb(client);
            });
            var sparse = captureCurve(context, "trail-stamps-sparse");
            context.runOnClient(client -> XpOrbTrailsClient.CONFIG.width *= 2);
            var wide = captureCurve(context, "trail-stamps-width-doubled");
            require(sparse.runs >= 3 && sparse.runs == wide.runs && Math.abs(sparse.thickness - wide.thickness) <= 2,
                    "Changing trail width must preserve stamp count and size");
            context.runOnClient(client -> XpOrbTrailsClient.CONFIG.stampScale = 1.2);
            var large = captureCurve(context, "trail-stamps-larger");
            require(large.runs == sparse.runs && large.thickness > sparse.thickness + 3,
                    "Stamp scale must enlarge each stamp independently of spacing");
            context.runOnClient(client -> {
                XpOrbTrailsClient.CONFIG.stampScale = 0.65;
                XpOrbTrailsClient.CONFIG.stampSpacing = 1.5;
            });
            var dense = captureCurve(context, "trail-stamps-dense");
            require(dense.runs >= sparse.runs + 2 && Math.abs(sparse.thickness - dense.thickness) <= 2,
                    "Decreasing spacing must add separate stamps of the same size");
            context.runOnClient(client -> {
                XpOrbTrailsClient.CONFIG.width = 0.22;
                XpOrbTrailsClient.CONFIG.trailSpriteMode = "ribbon";
            });
            var ribbon = captureCurve(context, "trail-ribbon-curved");
            require(ribbon.runs == 1 && ribbon.rise >= 9 && ribbon.cyan >= 12 && ribbon.cyanX + 8 < ribbon.magentaX,
                    "The asymmetric sheet must stretch continuously over the curved trail with its cyan end at the head");
            verifyRibbonMapping(context, capsId);
            verifyFixedStamps(context, "sprite:trail/" + folder.getFileName());
            verifyAfterimages(context, bodyId);
            verifyPreviews(context, bodyId, trailId, capsId);
        } finally {
            context.runOnClient(client -> client.level.removeEntity(ORB_ID, Entity.RemovalReason.DISCARDED));
            removeFixture(folder, context);
            removeFixture(caps, context);
        }
    }

    private static void verifyRibbonMapping(ClientGameTestContext context, String capsId) {
        context.runOnClient(client -> {
            var c = XpOrbTrailsClient.CONFIG;
            c.ribbonMapping = "tile";
            c.ribbonRepeatLength = 0.45;
            c.width = 0.32;
            trackLine(client, -1.45, 1.45, true);
        });
        context.waitTicks(2);
        var tiled = bands(context, "trail-ribbon-tiled", false);
        require(tiled.cyan.size() >= 5, "The ordinary ribbon must repeat cyan along its length");
        for (String mode : List.of("distribute_per_segment", "repeat_per_segment", "static")) {
            context.runOnClient(client -> XpOrbTrailsClient.CONFIG.ribbonMapping = mode);
            context.waitTicks(2);
            var runs = bands(context, "trail-ribbon-" + mode, false).cyan;
            require(mode.equals("distribute_per_segment") ? runs.size() == 1 : runs.size() >= 3,
                    "Unexpected world ribbon pattern for " + mode + ": " + runs);
        }
        context.runOnClient(client -> {
            var c = XpOrbTrailsClient.CONFIG;
            c.trailSprite = capsId;
            c.ribbonMapping = "stretch";
        });
        context.waitTicks(2);
        var caps = bands(context, "trail-ribbon-segmented", false);
        require(caps.pink.size() == 1 && caps.cyan.size() >= 4 && caps.yellow.size() == 1
                        && caps.yellow.getFirst() < caps.cyan.getFirst() && caps.cyan.getLast() < caps.pink.getFirst(),
                "Head and tail must each appear once, with the sheet's tiled middle between them");
    }

    private static void verifyFixedStamps(ClientGameTestContext context, String sprite) {
        context.runOnClient(client -> {
            var c = XpOrbTrailsClient.CONFIG;
            c.trailSpriteMode = "stamps";
            c.trailSprite = sprite;
            c.stampScale = 0.55;
            c.stampSpacing = 3;
            c.fixedStamps = true;
            trackLine(client, -1.45, 0.47, true);
        });
        context.waitTicks(2);
        var before = bands(context, "trail-fixed-before", false).pink;
        context.runOnClient(client -> trackLine(client, 0.47, 1.35, false));
        context.waitTicks(2);
        var after = bands(context, "trail-fixed-extended", false).pink;
        require(before.size() >= 3 && after.size() > before.size(), "A longer path must add fixed stamps");
        for (int center : before) {
            require(after.stream().anyMatch(other -> Math.abs(other - center) <= 2),
                    "Extending the path moved a previously deposited stamp at " + center);
        }
    }

    private static void trackLine(Minecraft client, double from, double to, boolean fresh) {
        if (fresh) TrailRenderer.updateWorld(null);
        var camera = client.gameRenderer.mainCamera().position();
        var orb = (ExperienceOrb) client.level.getEntity(ORB_ID);
        for (int i = 0; i <= 18; i++) {
            orb.setPos(camera.x + from + (to - from) * i / 18, camera.y, camera.z + 4);
            if (i == 0) orb.setOldPosAndRot();
            TrailRenderer.track(orb);
            TrailRenderer.extract(client.level, camera, 1);
            orb.setOldPosAndRot();
        }
    }

    private static void verifyAfterimages(ClientGameTestContext context, String bodyId) {
        long birth = context.computeOnClient(client -> {
            var c = XpOrbTrailsClient.CONFIG;
            c.trailSprite = "body";
            c.flightEffect = bodyId;
            c.showVanillaOrb = false;
            c.stampSpacing = 3;
            c.stampScale = 0.55;
            c.orbScale = 1;
            trackLine(client, -1.45, -0.1, true);
            return System.nanoTime();
        });
        context.waitFor(client -> System.nanoTime() - birth >= 1_080_000_000L, 1500);
        context.runOnClient(client -> trackLine(client, -0.1, 1.45, false));
        context.waitTicks(2);
        var first = bands(context, "trail-afterimages-birth-frames", false);
        context.waitFor(client -> System.nanoTime() - birth >= 2_150_000_000L, 1500);
        var second = bands(context, "trail-afterimages-next-frame", false);
        var initial = first.pink;
        var later = second.pink;
        require(initial.size() >= 4 && later.size() >= 4 && second.cyan.size() >= 2
                        && second.cyan.getLast() < later.getLast(),
                "Newer afterimages must retain the cyan second frame after the head returns to magenta");
        // The test sheet switches its magenta half every second. Only the live body may follow that change.
        // The head is at the left. Inspect the interior copies away from both the head and the oldest faded sample.
        for (int i = 1; i < initial.size() - 1; i++) {
            int center = initial.get(i);
            require(later.stream().anyMatch(other -> Math.abs(other - center) <= 2),
                    "An afterimage advanced to a different frame after birth at " + center);
        }
        context.runOnClient(client -> XpOrbTrailsClient.CONFIG.flightEffect = "none");
        context.waitTicks(2);
        var hidden = bands(context, "trail-afterimages-no-body", false);
        require(hidden.pink.isEmpty() && hidden.green.isEmpty(), "No visible body must leave no afterimages");
        context.runOnClient(client -> {
            XpOrbTrailsClient.CONFIG.showVanillaOrb = true;
            trackLine(client, -1.45, 1.45, true);
        });
        context.waitTicks(2);
        var vanilla = bands(context, "trail-afterimages-vanilla", false);
        require(vanilla.green.size() >= 3, "The native orb texture must leave separately visible afterimages");
    }

    private static Pixels captureCurve(ClientGameTestContext context, String name) {
        context.runOnClient(client -> {
            TrailRenderer.updateWorld(null);
            var orb = (ExperienceOrb) client.level.getEntity(ORB_ID);
            var camera = client.gameRenderer.mainCamera().position();
            for (int i = 0; i <= 20; i++) {
                double t = i / 20.0;
                orb.setPos(camera.x + 3 * (t - 0.5), camera.y + 0.9 * Math.sin(Math.PI * t), camera.z + 4);
                TrailRenderer.track(orb);
            }
        });
        context.waitTicks(2);
        return measure(context, name);
    }

    private record Pixels(int green, int magenta, double magentaX, int cyan, double cyanX,
                          int runs, int thickness, double rise) {}

    private record Bands(List<Integer> pink, List<Integer> cyan, List<Integer> yellow, List<Integer> green) {}

    private static Bands bands(ClientGameTestContext context, String name, boolean preview) {
        return TrailEffectsClientTest.screenshot(context, name, image -> {
            int w = image.getWidth(), h = image.getHeight();
            int[][] columns = new int[4][w];
            int left = preview ? w / 40 : w / 4, right = w - left;
            for (int x = left; x < right; x++) {
                for (int y = h * 3 / 10; y < h * 3 / 4; y++) {
                    int pixel = image.getPixel(x, y);
                    int r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
                    if (Math.min(r, b) - g > 24) columns[0][x]++;
                    if (Math.min(g, b) - r > 24) columns[1][x]++;
                    if (Math.min(r, g) - b > 24 && Math.abs(r - g) < 40) columns[2][x]++;
                    if (g > 28 && g - b > 15 && r < g * 1.4 + 8) columns[3][x]++;
                }
            }
            var result = new Bands(runCenters(columns[0]), runCenters(columns[1]),
                    runCenters(columns[2]), runCenters(columns[3]));
            System.out.println("XPORB_SPRITE_BANDS " + name + " " + result);
            return result;
        });
    }

    private static List<Integer> runCenters(int[] columns) {
        var centers = new ArrayList<Integer>();
        for (int x = 0; x < columns.length; x++) {
            if (columns[x] < 2) continue;
            int start = x;
            while (x + 1 < columns.length && columns[x + 1] >= 2) x++;
            centers.add((start + x) / 2);
        }
        return centers;
    }

    private static Pixels measure(ClientGameTestContext context, String name) {
        return TrailEffectsClientTest.screenshot(context, name, image -> {
            int w = image.getWidth(), h = image.getHeight();
            int green = 0, pink = 0, cyan = 0, runs = 0, thickness = 0, left = w, right = 0;
            long pinkX = 0, cyanX = 0;
            int[] counts = new int[w], heights = new int[w];
            for (int x = w / 4; x < w * 3 / 4; x++) {
                for (int y = h / 5; y < h * 2 / 3; y++) {
                    int argb = image.getPixel(x, y);
                    int r = argb >> 16 & 255, g = argb >> 8 & 255, b = argb & 255;
                    if (Math.abs(x - w / 2) < w / 20 && Math.abs(y - h / 2) < h / 9
                            && g > 28 && g - b > 15 && r < g * 1.4 + 8) green++;
                    if (Math.min(r, b) - g > 26) {
                        pink++;
                        pinkX += x;
                    } else if (Math.min(g, b) - r > 26) {
                        cyan++;
                        cyanX += x;
                    } else continue;
                    counts[x]++;
                    heights[x] += y;
                }
                if (counts[x] == 0) continue;
                if (counts[x - 1] == 0) runs++;
                thickness = Math.max(thickness, counts[x]);
                left = Math.min(x, left);
                right = x;
            }
            double rise = 0;
            if (right > left) {
                int a = left + (right - left) / 8, mid = (left + right) / 2, b = right - (right - left) / 8;
                if (counts[a] > 0 && counts[mid] > 0 && counts[b] > 0)
                    rise = (heights[a] / (double) counts[a] + heights[b] / (double) counts[b]) / 2
                            - heights[mid] / (double) counts[mid];
            }
            var pixels = new Pixels(green, pink, pinkX / (double) Math.max(1, pink), cyan,
                    cyanX / (double) Math.max(1, cyan), runs, thickness, rise);
            System.out.println("XPORB_SPRITE_PIXELS " + name + " " + pixels);
            return pixels;
        });
    }

    private static void verifyCollectBurst(ClientGameTestContext context, Path sprites, Path fixture) throws IOException {
        Path temporary = Files.createTempDirectory(sprites, "collect-burst-");
        try {
            Files.copy(fixture.resolve("sheet.png"), temporary.resolve("sheet.png"));
            Files.copy(fixture.resolve("sheet.json"), temporary.resolve("sheet.json"));
            String id = "sprite:pickup/" + temporary.getFileName();
            context.runOnClient(client -> {
                XpOrbTrailsClient.reloadFileStyles();
                var sheet = XpOrbTrailsClient.sprite(AssetRole.PICKUP, id);
                require(sheet != null && !sheet.loop() && "Collect burst".equals(sheet.displayName().getString())
                                && sheet.width() == 784 && sheet.height() == 648 && sheet.durationMillis() == 1386,
                        "Official Collect burst metadata and original duration must be preserved");
                for (int i = 0; i < 42; i++) {
                    var frame = sheet.frameAt((i * 33 + 1) / 1000.0, false);
                    require(frame != null && frame.x() == (i % 7) * 112 && frame.y() == (i / 7) * 108
                                    && frame.width() == 112 && frame.height() == 108 && frame.pivotX() == 56 && frame.pivotY() == 94,
                            "Wrong source rectangle or pivot in official frame " + i);
                }
                require(sheet.frameAt(1.387, false) == null, "Official Collect burst must end after frame 42");
                XpOrbTrailsClient.CONFIG = probeConfig();
                TrailRenderer.updateWorld(null);
            });
            context.waitTicks(2);
            int off = capture(context, "collect-burst-off");
            context.runOnClient(client -> {
                var c = XpOrbTrailsClient.CONFIG;
                c.pickupFlash = true;
                c.pickupFlashSize = 2.25;
                c.pickupFlashStyle = id;
                pickUp(client, addOrb(client));
            });
            context.waitTicks(1);
            int on = capture(context, "collect-burst-world");
            require(on >= off + 8, "Official PNG must render visible pink pixels at pickup: " + off + " -> " + on);
        } finally {
            removeFixture(temporary, context);
        }
    }

    private static void verifyPreviews(ClientGameTestContext context, String bodyId, String id, String capsId) {
        context.runOnClient(client -> {
            XpOrbTrailsClient.CONFIG = probeConfig();
            XpOrbTrailsClient.CONFIG.trailSprite = id;
            var preview = new TrailPreviewScreen(null);
            client.gui.setScreen(preview);
            seekPreview(preview);
        });
        context.waitTicks(2);
        require(capture(context, "sprite-preview-full") >= 16, "The expanded preview must draw PNG trail stamps");
        context.runOnClient(client -> {
            var c = XpOrbTrailsClient.CONFIG;
            c.trailSprite = capsId;
            c.trailSpriteMode = "ribbon";
            c.showVanillaOrb = false;
            c.lifetimeSeconds = 10;
            c.width = 0.32;
            c.ribbonRepeatLength = 0.35;
            seekPreview((TrailPreviewScreen)client.gui.screen());
        });
        context.waitTicks(2);
        var caps = bands(context, "sprite-preview-sheet-caps", true);
        require(caps.pink.size() == 1 && caps.cyan.size() >= 3 && caps.yellow.size() == 1
                        && caps.pink.getFirst() < caps.yellow.getFirst(),
                "The GUI ribbon must keep both caps while repeating the middle");
        context.runOnClient(client -> {
            var c = XpOrbTrailsClient.CONFIG;
            c.trailSprite = id;
            c.ribbonMapping = "tile";
            seekPreview((TrailPreviewScreen)client.gui.screen());
        });
        context.waitTicks(2);
        require(bands(context, "sprite-preview-tiled", true).pink.size() >= 4,
                "The GUI ribbon must repeat an ordinary sprite at its configured length");
        for (String mode : List.of("distribute_per_segment", "repeat_per_segment", "static")) {
            context.runOnClient(client -> {
                XpOrbTrailsClient.CONFIG.ribbonMapping = mode;
                seekPreview((TrailPreviewScreen)client.gui.screen());
            });
            context.waitTicks(2);
            var runs = bands(context, "sprite-preview-" + mode, true).pink;
            require(mode.equals("distribute_per_segment") ? runs.size() == 1 : runs.size() >= 3,
                    "Unexpected preview ribbon pattern for " + mode + ": " + runs);
        }
        context.runOnClient(client -> {
            var c = XpOrbTrailsClient.CONFIG;
            c.trailSpriteMode = "stamps";
            c.fixedStamps = true;
            c.stampSpacing = 5;
            c.stampScale = 0.7;
            seekPreview((TrailPreviewScreen)client.gui.screen());
        });
        context.waitTicks(2);
        require(bands(context, "sprite-preview-fixed", true).pink.size() >= 3,
                "Fixed stamps must render separately in the preview");
        context.runOnClient(client -> {
            var c = XpOrbTrailsClient.CONFIG;
            c.trailSprite = "body";
            c.flightEffect = bodyId;
            c.showVanillaOrb = false;
            seekPreview((TrailPreviewScreen)client.gui.screen());
        });
        context.waitTicks(2);
        var copies = bands(context, "sprite-preview-afterimages", true);
        require(copies.pink.size() >= 3 && !copies.cyan.isEmpty(), "The preview must draw historical body frames");
        int scale = context.computeOnClient(client -> client.options.guiScale().get());
        context.runOnClient(client -> {
            XpOrbTrailsClient.CONFIG = probeConfig();
            XpOrbTrailsClient.CONFIG.trailSprite = id;
            client.options.guiScale().set(1);
            client.resizeGui();
            var settings = new TrailConfigScreen(null);
            client.gui.setScreen(settings);
            try {
                var field = TrailConfigScreen.class.getDeclaredField("preview");
                field.setAccessible(true);
                seekPreview((TrailPreviewScreen) field.get(settings));
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        context.waitTicks(2);
        int compact = TrailEffectsClientTest.captureMagenta(context, "sprite-preview-compact", 0.70, 0.98);
        require(compact >= 8, "The settings sidebar must draw PNG trail stamps: " + compact);
        verifyAppearanceControls(context);
        context.runOnClient(client -> {
            client.gui.setScreen(null);
            client.options.guiScale().set(scale);
            client.resizeGui();
        });
    }

    private static void verifyAppearanceControls(ClientGameTestContext context) {
        context.clickScreenButton("screen.xporbtrails.trail");
        checkRows(context, true, "trail_type", "trail_sprite", "stamp_scale", "stamp_spacing", "fixed_stamps");
        checkRows(context, false, "orb_scale", "cross_section", "color_mode", "flash_style", "width");
        context.clickScreenButton("screen.xporbtrails.orb_body");
        checkRows(context, true, "show_vanilla_orb", "flight_effect");
        TrailClientTest.click(context, "screen.xporbtrails.show_vanilla_orb");
        context.runOnClient(client -> require(!XpOrbTrailsClient.CONFIG.showVanillaOrb, "The native-orb control must hide the body"));
        TrailClientTest.click(context, "screen.xporbtrails.flight_effect");
        TrailClientTest.click(context, "Pixel probe");
        checkRows(context, true, "orb_scale");
        context.clickScreenButton("screen.xporbtrails.pickup");
        TrailClientTest.click(context, "screen.xporbtrails.pickup_flash");
        TrailClientTest.click(context, "screen.xporbtrails.flash_style");
        TrailClientTest.click(context, "Pixel probe");
        checkRows(context, false, "pickup_flash_duration", "color_mode");
        context.clickScreenButton("screen.xporbtrails.trail");
        TrailClientTest.click(context, "screen.xporbtrails.trail_type");
        TrailClientTest.click(context, "screen.xporbtrails.trail_sprite_mode.ribbon");
        checkRows(context, true, "width");
        checkRows(context, false, "stamp_scale", "stamp_spacing", "fixed_stamps", "ribbon_repeat_length");
        for (String mode : TrailConfig.RIBBON_MAPPINGS) {
            TrailClientTest.click(context, "screen.xporbtrails.ribbon_mapping");
            TrailClientTest.click(context, "screen.xporbtrails.ribbon_mapping." + mode);
            context.runOnClient(client -> require(mode.equals(XpOrbTrailsClient.CONFIG.ribbonMapping),
                    "Selected ribbon mapping must apply: " + mode));
            checkRows(context, mode.equals("tile") || mode.equals("static"), "ribbon_repeat_length");
        }
        context.takeScreenshot("menu-trail-png-ribbon");
        TrailClientTest.click(context, "screen.xporbtrails.trail_type");
        TrailClientTest.click(context, "screen.xporbtrails.trail_sprite_mode.stamps");
        checkRows(context, true, "stamp_scale", "stamp_spacing");
        checkRows(context, false, "width");
        TrailClientTest.click(context, "screen.xporbtrails.fixed_stamps");
        context.runOnClient(client -> require(XpOrbTrailsClient.CONFIG.fixedStamps, "Anchor stamps toggle must apply"));
        context.takeScreenshot("menu-trail-png-stamps");
        TrailClientTest.click(context, "screen.xporbtrails.trail_sprite");
        TrailClientTest.click(context, "Sheet with caps");
        TrailClientTest.click(context, "screen.xporbtrails.trail_type");
        TrailClientTest.click(context, "screen.xporbtrails.trail_sprite_mode.ribbon");
        checkRows(context, true, "ribbon_caps", "ribbon_repeat_length");
        checkRows(context, false, "ribbon_mapping", "fixed_stamps");
        TrailClientTest.click(context, "screen.xporbtrails.trail_type");
        TrailClientTest.click(context, "screen.xporbtrails.trail_sprite.body");
        checkRows(context, true, "stamp_spacing", "stamp_scale");
        checkRows(context, false, "trail_sprite", "fixed_stamps", "ribbon_mapping", "cross_section");
        context.clickScreenButton("screen.xporbtrails.orb_body");
        TrailClientTest.click(context, "screen.xporbtrails.flight_effect");
        TrailClientTest.click(context, "options.off");
        context.runOnClient(client -> {
            var c = XpOrbTrailsClient.CONFIG;
            require("body".equals(c.trailSprite) && !c.showVanillaOrb && "none".equals(c.flightEffect),
                    "The invisible body must yield empty afterimages");
        });
        context.clickScreenButton("screen.xporbtrails.trail");
        context.takeScreenshot("menu-afterimages-no-body");
        checkRows(context, true, "trail_sprite.body_hidden");
        checkRows(context, false, "stamp_spacing", "stamp_scale", "flight_pulse");
        context.clickScreenButton("screen.xporbtrails.orb_body");
        TrailClientTest.click(context, "screen.xporbtrails.show_vanilla_orb");
        context.clickScreenButton("screen.xporbtrails.trail");
        checkRows(context, true, "stamp_spacing", "stamp_scale");
        context.clickScreenButton("screen.xporbtrails.reset");
        context.runOnClient(client -> {
            var c = XpOrbTrailsClient.CONFIG;
            require("none".equals(c.trailSprite) && !c.fixedStamps && "stretch".equals(c.ribbonMapping)
                    && c.ribbonRepeatLength == 0.5, "Appearance reset must restore legacy placement and stretch");
        });
    }

    private static void checkRows(ClientGameTestContext context, boolean expected, String... keys) {
        context.runOnClient(client -> {
            var list = (TrailSettingsList) client.gui.screen().children().stream()
                    .filter(TrailSettingsList.class::isInstance).findFirst().orElseThrow();
            for (String key : keys) {
                var label = net.minecraft.network.chat.Component.translatable("screen.xporbtrails." + key);
                boolean found = list.children().stream().anyMatch(row -> row.matches(label) || row.children().stream()
                        .filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
                        .map(net.minecraft.client.gui.components.AbstractWidget.class::cast)
                        .anyMatch(widget -> widget.getMessage().getString().equals(label.getString())));
                require(found == expected, "Unexpected visibility for " + key + ": " + found);
            }
        });
    }

    private static void seekPreview(TrailPreviewScreen preview) {
        try {
            var field = TrailPreviewScreen.class.getDeclaredField("demoStartNanos");
            field.setAccessible(true);
            field.setLong(preview, System.nanoTime() - 2_450_000_000L);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static TrailConfig probeConfig() {
        var c = new TrailConfig();
        c.startColor = c.endColor = 0x00FF00;
        c.width = 0.22;
        c.tailWidthScale = c.middleWidthScale = c.headWidthScale = 1;
        c.effectStrength = c.pickupFlashStrength = 1;
        c.additiveGlow = false;
        c.pickupFlash = false;
        c.cameraPush = c.motionShift = 0;
        return c;
    }

    private static ExperienceOrb addOrb(Minecraft client) {
        var camera = client.gameRenderer.mainCamera().position();
        var orb = new ExperienceOrb(client.level, camera.x, camera.y, camera.z + 4, 1);
        orb.setId(ORB_ID);
        orb.setNoGravity(true);
        orb.setDeltaMovement(Vec3.ZERO);
        client.level.addEntity(orb);
        TrailRenderer.track(orb);
        return orb;
    }

    private static void pickUp(Minecraft client, ExperienceOrb orb) {
        client.getConnection().handleTakeItemEntity(new ClientboundTakeItemEntityPacket(orb.getId(), client.player.getId(), 1));
        client.level.removeEntity(orb.getId(), Entity.RemovalReason.DISCARDED);
    }

    private static int capture(ClientGameTestContext context, String name) {
        return TrailEffectsClientTest.captureMagenta(context, name, 0.25, 0.75);
    }

    private static void removeFixture(Path directory, ClientGameTestContext context) throws IOException {
        Files.deleteIfExists(directory.resolve("sheet.json"));
        Files.deleteIfExists(directory.resolve("sheet.png"));
        Files.delete(directory);
        context.runOnClient(client -> XpOrbTrailsClient.reloadFileStyles());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
