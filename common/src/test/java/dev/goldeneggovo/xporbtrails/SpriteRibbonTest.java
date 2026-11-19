package dev.goldeneggovo.xporbtrails;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpriteRibbonTest {
    private static final SpriteRibbon.FrameMap FULL = map(0, 4096);
    private static final double EPSILON = 1.0E-6;
    @TempDir Path config;

    @Test
    void usesArcLengthOnUnequalSegmentsAndJoinsBothEdgesOfTheBend() {
        var path = List.of(sample(0, 0, 0), sample(3, 0, 0), sample(3, 4, 0));
        var mesh = mesh(FULL, path, null, -1);
        assertEquals(2, mesh.size());
        assertEquals(0, mesh.getFirst().a.u(), EPSILON);
        var corner = mesh.getFirst().b;
        assertEquals(corner, mesh.getLast().a);
        assertEquals(3.0 / 7, corner.u(), EPSILON);
        assertEquals(3, corner.top().lerp(corner.bottom(), 0.5).x, EPSILON);
        assertEquals(1, corner.top().distanceTo(corner.bottom()), EPSILON);
        assertEquals(1, mesh.getLast().b.u(), EPSILON);
    }

    @Test
    void segmentModesUseRetainedPointsEvenWhenTheCurveHasExtraSmoothingVertices() {
        var trail = new TrailRenderer.Trail();
        trail.append(Vec3.ZERO, 0, 2);
        trail.append(new Vec3(0.65, 0, 0), 100, 2);
        trail.append(new Vec3(2.20, 0, 0), 200, 2);
        var path = trail.samples(null);
        assertTrue(path.size() > 3);
        var cfg = flat();
        cfg.ribbonMapping = "distribute_per_segment";
        var distributed = mesh(FULL, path, cfg);
        assertEquals(0.5, edgeAt(distributed, new Vec3(0.65, 0, 0)).u(), EPSILON);
        assertEquals(1, distributed.getLast().b.u(), EPSILON);
        cfg.ribbonMapping = "repeat_per_segment";
        var repeated = mesh(FULL, path, cfg);
        assertEquals(2, repeated.stream().filter(q -> q.a.u() < EPSILON).count());
        assertEquals(2, repeated.stream().filter(q -> q.b.u() > 1 - EPSILON).count());
        assertEquals(0.65, repeated.stream().filter(q -> q.b.u() > 1 - EPSILON).findFirst().orElseThrow().b.top().x, EPSILON);
    }

    @Test
    void staticUvFollowsTheSamePointThroughInterpolationFlowExtensionAndExpiry() {
        var trail = new TrailRenderer.Trail();
        trail.append(Vec3.ZERO, 0, 2);
        trail.append(new Vec3(0.65, 0, 0), 100, 2);
        trail.append(new Vec3(2.20, 0, 0), 200, 2);
        var cfg = flat();
        cfg.ribbonRepeatLength = 1;
        cfg.ribbonMapping = "static";
        var path = trail.samples(new Vec3(1.83, 0, 0));
        var first = mesh(FULL, path, cfg);
        assertEquals(0.20, edgeAt(first, path.getLast().position()).u(), EPSILON);
        assertEquals(0.65, edgeAt(first, new Vec3(0.65, 0, 0)).u(), EPSILON);
        trail.append(new Vec3(3.7, 0, 0), 300, 2);
        trail.flow(1);
        trail.expireBefore(100);
        path = trail.samples(null);
        assertTrue(path.getFirst().position().x > 0.65);
        var moved = mesh(FULL, path, cfg);
        assertEquals(0.65, moved.getFirst().a.u(), EPSILON);
        var oldHead = path.stream().filter(sample -> sample.time() == 200).findFirst().orElseThrow();
        assertTrue(oldHead.position().x > 2.20);
        assertEquals(0.20, edgeAt(moved, oldHead.position()).u(), EPSILON);

        cfg.ribbonMapping = "tile";
        assertEquals(0, mesh(FULL, path, cfg).getFirst().a.u(), EPSILON);
    }

    private static SpriteRibbon.Edge edgeAt(List<Quad> mesh, Vec3 position) {
        return mesh.stream().flatMap(q -> java.util.stream.Stream.of(q.a, q.b))
                .filter(e -> e.top().lerp(e.bottom(), 0.5).distanceTo(position) < EPSILON)
                .findFirst().orElseThrow();
    }

    @Test
    void movementOfTheLiveTipCountsTowardTheNextBirthWithoutRetexturingTheOldPoint() {
        var trail = new TrailRenderer.Trail();
        trail.append(Vec3.ZERO, 0, 0.5);
        trail.append(new Vec3(0.1, 0, 0), 100, 0.5);
        trail.append(new Vec3(0.4, 0, 0), 200, 0.5);
        trail.append(new Vec3(0.6, 0, 0), 300, 0.5);
        var cfg = flat();
        cfg.ribbonMapping = "static";
        cfg.ribbonRepeatLength = 1;
        var mesh = mesh(FULL, trail.samples(null), cfg);
        assertEquals(0.1, edgeAt(mesh, new Vec3(0.4, 0, 0)).u(), EPSILON);
        assertEquals(0.6, mesh.getLast().b.u(), EPSILON);
    }

    @Test
    void keepsBothTrimmedMarginsTheOriginalPivotAndOnlySamplesInsideTheAtlasCell() throws Exception {
        var sheet = croppedSheet();
        var map = new SpriteRibbon.FrameMap(sheet, sheet.frameAt(0, false));
        var mesh = mesh(map, List.of(sample(0, 0, 0), sample(4, 0, 0)), null, -1);
        assertEquals(1, mesh.size());
        var strip = mesh.getFirst();
        assertEquals(new Vec3(1, -0.375, 0), strip.a.top());
        assertEquals(new Vec3(1, 0, 0), strip.a.bottom());
        assertEquals(new Vec3(3, -0.375, 0), strip.b.top());
        assertEquals(4, (int) (strip.a.u() * sheet.width()));
        assertEquals(7, (int) (strip.b.u() * sheet.width()));
        assertEquals(2, (int) (map.v0() * sheet.height()));
        assertEquals(4, (int) (map.v1() * sheet.height()));
    }

    @Test
    void reflowClipsTheOriginalCurveAndRetainsTheUnclippedUvPhase() {
        var path = List.of(sample(0, 0, 0), sample(1, 0, 0), sample(7, 0, 0));
        var trimmed = map(1024, 2048);
        var mesh = mesh(trimmed, path, null, 0.75);
        assertEquals(1, mesh.size());
        var strip = mesh.getFirst();
        // Smoothstep(.75) cuts at .6875 of the six-block second segment.
        assertEquals(5.125, strip.a.top().x, EPSILON);
        assertEquals(5.125 / 7, strip.a.u(), EPSILON);
        assertEquals(5.25, strip.b.top().x, EPSILON);
        assertEquals(0.75, strip.b.u(), EPSILON);
        assertTrue(mesh(trimmed, path, null, 1).isEmpty());
    }

    @Test
    void skipsStationarySegmentsAndKeepsAStableSideWhenLookingAlongThePath() {
        var a = sample(0, 0, 1);
        var b = sample(0, 0, 3);
        assertTrue(mesh(FULL, List.of(a, a, a), Vec3.ZERO, -1).isEmpty());
        var mesh = mesh(FULL, List.of(a, a, b, b, a), Vec3.ZERO, -1);
        assertEquals(2, mesh.size());
        assertEquals(mesh.getFirst().b, mesh.getLast().a);
        var firstSide = mesh.getFirst().a.bottom().subtract(mesh.getFirst().a.top());
        for (var strip : mesh) {
            Vec3 side = strip.b.bottom().subtract(strip.b.top());
            assertEquals(1, side.length(), EPSILON);
            assertTrue(side.dot(firstSide) > 0.99);
            assertTrue(strip.a.u() < strip.b.u());
        }
        var turn = mesh(FULL, List.of(sample(0, 0, 0), sample(1, 0, 0), sample(0, 0, 0)), null, -1);
        assertEquals(new Vec3(1, -0.5, 0), turn.getFirst().b.top());
        assertEquals(new Vec3(1, 0.5, 0), turn.getLast().a.bottom());
    }

    @Test
    void preservesTaperAgeAndDisappearanceOpacityWithWhiteTextureModulation() {
        var cfg = flat();
        cfg.tailWidthScale = 0.25;
        cfg.effectStrength = 0.8;
        cfg.lifetimeSeconds = 1;
        var mesh = new ArrayList<Quad>();
        var path = List.of(sample(0, 0, 0), new TrailRenderer.Sample(new Vec3(2, 0, 0), 1_000_000_000L));
        SpriteRibbon.emit(FULL, path, null, 1, 1_000_000_000L, cfg, 0.5, -1, false,
                (a, b) -> mesh.add(new Quad(a, b)));
        var strip = mesh.getFirst();
        assertEquals(0.25, strip.a.top().distanceTo(strip.a.bottom()), EPSILON);
        assertEquals(1, strip.b.top().distanceTo(strip.b.bottom()), EPSILON);
        assertEquals(0x00FFFFFF, strip.a.color());
        assertEquals(0x66FFFFFF, strip.b.color());
    }

    @Test
    void wholeFrameTilesRepeatByWorldDistanceOnBendsAndRestartAtBothUvSeams() {
        var cfg = flat();
        cfg.ribbonMapping = "tile";
        cfg.ribbonRepeatLength = 0.75;
        double scale = 4;  // Four preview pixels per block.
        var path = List.of(sample(0, 0, 0), sample(2, 0, 0), sample(2, 6, 0));
        var mesh = new ArrayList<Quad>();
        SpriteRibbon.emit(FULL, path, null, scale, 0, cfg, 1, -1, false, (a, b) -> mesh.add(new Quad(a, b)));
        assertEquals(4, mesh.size());
        assertEquals(mesh.get(0).b, mesh.get(1).a);
        assertEquals(2.0 / 3, mesh.get(1).a.u(), EPSILON);
        for (int i = 1; i <= 2; i++) {
            var before = mesh.get(i).b;
            var after = mesh.get(i + 1).a;
            assertEquals(before.top(), after.top());
            assertEquals(before.bottom(), after.bottom());
            assertEquals(1, before.u(), EPSILON);
            assertEquals(0, after.u(), EPSILON);
        }
        assertEquals(2.0 / 3, mesh.getLast().b.u(), EPSILON);
    }

    @Test
    void repeatedTrimmedFramesPreserveTransparentGapsAndReflowPhase() throws Exception {
        var sheet = croppedSheet();
        var cfg = flat();
        cfg.ribbonMapping = "tile";
        cfg.ribbonRepeatLength = 1;
        var mesh = new ArrayList<Quad>();
        var path = List.of(sample(0, 0, 0), sample(3, 0, 0));
        SpriteRibbon.emit(new SpriteRibbon.FrameMap(sheet, sheet.frameAt(0, true)), path, null, 1, 0,
                cfg, 1, 0.5, false, (a, b) -> mesh.add(new Quad(a, b)));
        assertEquals(2, mesh.size());
        assertEquals(1.5, mesh.getFirst().a.top().x, EPSILON);
        assertEquals(6.0 / 16, mesh.getFirst().a.u(), EPSILON);
        assertEquals(1.75, mesh.getFirst().b.top().x, EPSILON);
        assertEquals(2.25, mesh.getLast().a.top().x, EPSILON);
        assertEquals(2.75, mesh.getLast().b.top().x, EPSILON);
    }

    @Test
    void trimmedSegmentedStretchUsesOriginalCapPixelsAndOverridesWholeFrameTiling() {
        var frame = new SpriteSheet.Frame(4, 0, 8, 4, 16, 8, 2, 2, 8, 4);
        var map = new SpriteRibbon.FrameMap(frame, 16, 8, new SpriteSheet.Ribbon(4, 2, "stretch"));
        var mesh = new ArrayList<Quad>();
        var cfg = flat();
        cfg.ribbonMapping = "tile";
        var path = List.of(sample(0, 0, 0), sample(3.25, 0, 0));
        SpriteRibbon.emit(map, path, null, 1, 0, cfg, 1, -1, false, (a, b) -> mesh.add(new Quad(a, b)));
        assertEquals(2, mesh.size());
        assertEquals(0.25, mesh.getFirst().a.top().x, EPSILON);
        assertEquals(0.5, mesh.getFirst().b.top().x, EPSILON);
        assertEquals(5, (int) (mesh.getFirst().b.u() * 16));
        assertEquals(6, (int) (mesh.getLast().a.u() * 16));
        assertEquals(2, mesh.getLast().b.top().x, EPSILON);
        assertEquals(11, (int) (mesh.getLast().b.u() * 16));
    }

    @Test
    void limitsRepetitionsWhileCoveringTheFullLengthAndPreservingTheHead() throws Exception {
        var sheet = gridRibbon("tile");
        var path = List.of(sample(0, 0, 0), sample(1_000_000, 0, 0));
        var mesh = mesh(new SpriteRibbon.FrameMap(sheet, sheet.frameAt(0, false)), path, null, -1);
        assertTrue(mesh.size() <= 258);
        assertEquals(0.5, mesh.getFirst().b.top().x, EPSILON);
        for (int i = 1; i < mesh.size(); i++) assertEquals(mesh.get(i - 1).b.top(), mesh.get(i).a.top());
        assertEquals(999_999.75, mesh.getLast().a.top().x, EPSILON);
        assertEquals(1_000_000, mesh.getLast().b.top().x, EPSILON);
    }

    @Test
    void segmentedTilesKeepBothCapsAndClipTheLastRepetition() throws Exception {
        var sheet = gridRibbon("tile");
        var map = new SpriteRibbon.FrameMap(sheet, sheet.frameAt(0, false));
        var mesh = mesh(map, List.of(sample(0, 0, 0), sample(3, 0, 0)), null, -1);
        assertEquals(7, mesh.size());
        assertEquals(0.5, mesh.getFirst().b.top().x, EPSILON);
        assertEquals(3, (int) (mesh.getFirst().b.u() * 16));
        for (int i = 1; i <= 5; i++) {
            assertEquals(i * 0.5, mesh.get(i).a.top().x, EPSILON);
            assertEquals(4, (int) (mesh.get(i).a.u() * 16));
            assertEquals(i == 5 ? 9 : 13, (int) (mesh.get(i).b.u() * 16));
        }
        assertEquals(2.75, mesh.getLast().a.top().x, EPSILON);
        assertEquals(14, (int) (mesh.getLast().a.u() * 16));
        assertEquals(3, mesh.getLast().b.top().x, EPSILON);
    }

    @Test
    void atlasCapsKeepTheirPhysicalSizesWithEveryNewMiddleMapping() throws Exception {
        var path = List.of(new TrailRenderer.Sample(Vec3.ZERO, 0, 0.25, 1),
                new TrailRenderer.Sample(new Vec3(0.75, 0, 0), 100, 0.90, 1),
                new TrailRenderer.Sample(new Vec3(3, 0, 0), 200, 3.1, 1));
        var cfg = flat();
        cfg.ribbonRepeatLength = 1;
        for (String mode : List.of("distribute_per_segment", "repeat_per_segment", "static")) {
            var sheet = gridRibbon(mode);
            var mesh = mesh(new SpriteRibbon.FrameMap(sheet, sheet.frameAt(0, true)), path, cfg);
            assertEquals(0.5, mesh.getFirst().b.top().x, EPSILON);
            assertEquals(2.75, mesh.getLast().a.top().x, EPSILON);
            assertEquals(0, (int) (mesh.getFirst().a.u() * 16));
            assertEquals(15, (int) (mesh.getLast().b.u() * 16));
            double endPhase = switch (mode) {
                case "distribute_per_segment" -> (1.0 - 2.0 / 3) / (1.0 + 2.0 / 2.25 - 2.0 / 3);
                case "repeat_per_segment" -> 1;
                default -> 0.90;
            };
            double cornerPixel = edgeAt(mesh, path.get(1).position()).u() * 16;
            assertEquals(4.001 + 9.998 * endPhase, cornerPixel, 0.00001);
        }
    }

    @Test
    void scalesBothCapsProportionallyWhenThePathIsShorterThanTheirCombinedSize() throws Exception {
        var sheet = gridRibbon("stretch");
        var map = new SpriteRibbon.FrameMap(sheet, sheet.frameAt(0, false));
        var mesh = mesh(map, List.of(sample(0, 0, 0), sample(0.375, 0, 0)), null, -1);
        assertEquals(2, mesh.size());
        assertEquals(0.25, mesh.getFirst().b.top().x, EPSILON);
        assertEquals(mesh.getFirst().b.top(), mesh.getLast().a.top());
        assertEquals(3, (int) (mesh.getFirst().b.u() * 16));
        assertEquals(14, (int) (mesh.getLast().a.u() * 16));
    }

    private SpriteSheet gridRibbon(String middle) throws Exception {
        var folder = Files.createDirectories(config.resolve("xp_orb_trails/trail/sprites/grid"));
        assertTrue(ImageIO.write(new BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB), "png", folder.resolve("sheet.png").toFile()));
        Files.writeString(folder.resolve("sheet.json"), """
                {"frameWidth":16,"frameHeight":8,"ribbon":{"tailPixels":4,"headPixels":2,"middle":"%s"}}
                """.formatted(middle));
        var library = new SpriteLibrary(config.resolve("xp_orb_trails"));
        assertEquals(0, library.reload());
        return library.sheets().get("sprite:trail/grid");
    }

    @Test
    void stampSpacingAndSizeAreIndependentOfEachOtherAndOfTheRibbonWidth() throws Exception {
        var sheet = croppedSheet();
        var cfg = flat();
        cfg.width = 0.02;
        var path = List.of(sample(0, 0, 0), sample(2, 0, 0));
        var initial = stamps(sheet, cfg, path);
        assertEquals(11, initial.size());
        assertEquals(0.2, initial.get(1).x, EPSILON);
        assertEquals(0.3, initial.get(1).size, EPSILON);
        cfg.width = 1;
        assertEquals(initial, stamps(sheet, cfg, path));
        cfg.stampSpacing = 2;
        var spaced = stamps(sheet, cfg, path);
        assertEquals(6, spaced.size());
        assertEquals(0.4, spaced.get(1).x, EPSILON);
        assertEquals(0.3, spaced.get(1).size, EPSILON);
        cfg.stampScale = 2;
        var enlarged = stamps(sheet, cfg, path);
        assertEquals(spaced.size(), enlarged.size());
        assertEquals(spaced.get(1).x, enlarged.get(1).x, EPSILON);
        assertEquals(0.6, enlarged.get(1).size, EPSILON);
    }

    private record Stamp(double x, double size) { }

    private static List<Stamp> stamps(SpriteSheet sheet, TrailConfig cfg, List<TrailRenderer.Sample> path) {
        var stamps = new ArrayList<Stamp>();
        TrailRenderer.emitTrailStamps(sheet, path, 0, 0, cfg, 1, -1, false, 1,
                (frame, position, size, alpha) -> stamps.add(new Stamp(position.x, size)));
        return stamps;
    }

    private SpriteSheet croppedSheet() throws Exception {
        var folder = Files.createDirectories(config.resolve("xp_orb_trails/trail/sprites/cropped"));
        assertTrue(ImageIO.write(new BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB), "png", folder.resolve("sheet.png").toFile()));
        Files.writeString(folder.resolve("sheet.json"), """
                {"frames":[{"frame":{"x":4,"y":2,"w":4,"h":3},"trimmed":true,
                 "sourceSize":{"w":8,"h":8},"spriteSourceSize":{"x":2,"y":1,"w":4,"h":3}}],
                 "meta":{"image":"sheet.png","pixelfx":{"pivot":[4,4]}}}
                """);
        var library = new SpriteLibrary(config.resolve("xp_orb_trails"));
        assertEquals(0, library.reload());
        return library.sheets().get("sprite:trail/cropped");
    }

    private static TrailConfig flat() {
        var cfg = new TrailConfig();
        cfg.width = cfg.tailWidthScale = cfg.middleWidthScale = cfg.headWidthScale = 1;
        return cfg;
    }

    private static TrailRenderer.Sample sample(double x, double y, double z) {
        return new TrailRenderer.Sample(new Vec3(x, y, z), 0);
    }

    private record Quad(SpriteRibbon.Edge a, SpriteRibbon.Edge b) { }

    private static SpriteRibbon.FrameMap map(int left, int width) {
        var frame = new SpriteSheet.Frame(left, 0, width, 4096, 4096, 4096, left, 0, 2048, 2048);
        return new SpriteRibbon.FrameMap(frame, 4096, 4096, null);
    }

    private static List<Quad> mesh(SpriteRibbon.FrameMap map, List<TrailRenderer.Sample> path, Vec3 camera, double reflow) {
        var mesh = new ArrayList<Quad>();
        SpriteRibbon.emit(map, path, camera, 1, 0, flat(), 1, reflow, false, (a, b) -> mesh.add(new Quad(a, b)));
        return mesh;
    }

    private static List<Quad> mesh(SpriteRibbon.FrameMap map, List<TrailRenderer.Sample> path, TrailConfig cfg) {
        var mesh = new ArrayList<Quad>();
        SpriteRibbon.emit(map, path, null, 1, 0, cfg, 1, -1, false, (a, b) -> mesh.add(new Quad(a, b)));
        return mesh;
    }
}
