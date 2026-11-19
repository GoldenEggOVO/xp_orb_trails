package dev.goldeneggovo.xporbtrails;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpriteLibraryTest {
    @TempDir Path config;

    @BeforeEach
    void isolateTrailFixtures() throws Exception {
        Files.createDirectories(config.resolve("xp_orb_trails/orb/sprites/luna-moth"));
        Files.createDirectories(config.resolve("xp_orb_trails/pickup/sprites/collect-burst"));
        folder("comet-pennant");
    }

    private static final String PIXELFX = """
            {"frames":[
              {"frame":{"x":0,"y":0,"w":3,"h":2},"rotated":false,"trimmed":false,
               "sourceSize":{"w":3,"h":2},"spriteSourceSize":{"x":0,"y":0,"w":3,"h":2},"duration":33},
              {"frame":{"x":3,"y":0,"w":2,"h":1},"rotated":false,"trimmed":true,
               "sourceSize":{"w":3,"h":2},"spriteSourceSize":{"x":1,"y":1,"w":2,"h":1},"duration":87}
            ],"meta":{"image":"collect-burst.png","size":{"w":6,"h":2},
                    "pixelfx":{"name":"Collect burst","fps":30,"loop":false,"pivot":[1.5,2]}}}
            """;

    private Path folder(String id) throws Exception {
        return Files.createDirectories(config.resolve("xp_orb_trails/trail/sprites").resolve(id));
    }

    private static void png(Path path, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x804080ff);
        assertTrue(ImageIO.write(image, "PNG", path.toFile()));
    }

    @Test
    void importsPixelFxWithRenamedPngAndPreservesTrimPivotAlphaAndVariableDurations() throws Exception {
        Path burst = folder("collect-burst");
        png(burst.resolve("sheet.png"), 6, 2);
        Files.writeString(burst.resolve("sheet.json"), PIXELFX);
        SpriteLibrary library = new SpriteLibrary(config.resolve("xp_orb_trails"));
        assertEquals(0, library.reload());
        SpriteSheet sheet = library.sheets().get("sprite:trail/collect-burst");
        assertEquals("Collect burst", sheet.displayName().getString());
        assertEquals(6, sheet.width());
        assertEquals(2, sheet.height());
        assertEquals(0x804080ff, sheet.image().getRGB(0, 0));
        assertEquals(120, sheet.durationMillis());
        assertFalse(sheet.loop());
        var first = new SpriteSheet.Frame(0, 0, 3, 2, 3, 2, 0, 0, 1.5, 2);
        var last = new SpriteSheet.Frame(3, 0, 2, 1, 3, 2, 1, 1, 1.5, 2);
        assertEquals(first, sheet.frameAt(0.03299, false));
        assertEquals(last, sheet.frameAt(0.033, false));
        assertEquals(last, sheet.frameAt(0.1199, false));
        assertNull(sheet.frameAt(0.120, false));
        assertEquals(first, sheet.frameAt(0.120, true));
    }

    @Test
    void preservesHashExportOrderAndUsesCenterOfUntrimmedFramesByDefault() throws Exception {
        Path folder = folder("aseprite");
        png(folder.resolve("export.png"), 6, 3);
        Files.writeString(folder.resolve("export.json"), """
                {"frames":{"z":{"frame":{"x":3,"y":0,"w":3,"h":3},"duration":10},
                           "a":{"frame":{"x":0,"y":0,"w":3,"h":3},"duration":20}},
                 "meta":{"image":"export.png"}}
                """);
        SpriteLibrary library = new SpriteLibrary(config.resolve("xp_orb_trails"));
        assertEquals(0, library.reload());
        var sheet = library.sheets().get("sprite:trail/aseprite");
        assertEquals("aseprite", sheet.displayName().getString());
        assertEquals(3, sheet.frameAt(0, false).x());
        assertEquals(1.5, sheet.frameAt(0, false).pivotY());
        assertEquals(0, sheet.frameAt(0.010, false).x());
    }

    @Test
    void readsBothStripOrientationsSquareImagesAndRowMajorGridWithEmptyTailCells() throws Exception {
        png(folder("vertical").resolve("v.png"), 2, 6);
        png(folder("horizontal").resolve("h.png"), 6, 2);
        png(folder("still").resolve("s.png"), 2, 2);
        Path grid = folder("grid");
        png(grid.resolve("sheet.png"), 6, 4);
        Files.writeString(grid.resolve("grid.json"), """
                {"image":"sheet.png","frameWidth":3,"frameHeight":2,"frames":3,"fps":10,
                 "name":"Test grid","pivot":[1,1.5],"loop":false}
                """);
        SpriteLibrary library = new SpriteLibrary(config.resolve("xp_orb_trails"));
        assertEquals(0, library.reload());
        var sheets = library.sheets();
        assertEquals(List.of("sprite:trail/grid", "sprite:trail/horizontal", "sprite:trail/still", "sprite:trail/vertical"),
                List.copyOf(sheets.keySet()));
        assertEquals(4, sheets.get("sprite:trail/vertical").frameAt(0.2, true).y());
        assertEquals(4, sheets.get("sprite:trail/horizontal").frameAt(0.2, true).x());
        assertEquals(2, sheets.get("sprite:trail/still").frameAt(1, true).width());
        var sheet = sheets.get("sprite:trail/grid");
        assertEquals("Test grid", sheet.displayName().getString());
        assertEquals(300, sheet.durationMillis());
        assertEquals(new SpriteSheet.Frame(0, 2, 3, 2, 3, 2, 0, 0, 1, 1.5),
                sheet.frameAt(0.2, true));
    }

    @Test
    void retainsLastGoodOnDamageAndDropsRemovedOrEmptiedFolders() throws Exception {
        Path a = folder("a"), b = folder("b");
        png(a.resolve("sheet.png"), 6, 2);
        png(b.resolve("sheet.png"), 2, 2);
        var library = new SpriteLibrary(config.resolve("xp_orb_trails"));
        assertEquals(0, library.reload());
        var before = library.sheets();
        Path json = a.resolve("sheet.json");
        Files.delete(b.resolve("sheet.png"));
        for (String invalid : List.of("{", PIXELFX.replace("collect-burst.png", "../escape.png"),
                PIXELFX.replace("collect-burst.png", "C:/escape.png"),
                PIXELFX.replace("collect-burst.png", "/escape.png"),
                PIXELFX.replace("\"rotated\":false", "\"rotated\":true"),
                PIXELFX.replace("\"x\":3", "\"x\":5"), PIXELFX.replace("\"x\":1,\"y\":1", "\"x\":2,\"y\":1"),
                PIXELFX.replace("\"w\":6", "\"w\":7"), PIXELFX.replace("\"duration\":33", "\"duration\":0"))) {
            Files.writeString(json, invalid);
            assertEquals(1, library.reload(), invalid);
            assertSame(before.get("sprite:trail/a"), library.sheets().get("sprite:trail/a"));
            assertFalse(library.sheets().containsKey("sprite:trail/b"));
        }
        Files.writeString(json, PIXELFX);
        Files.writeString(a.resolve("sheet.png"), "broken PNG");
        assertEquals(1, library.reload());
        assertSame(before.get("sprite:trail/a"), library.sheets().get("sprite:trail/a"));
        png(a.resolve("sheet.png"), 6, 2);
        assertEquals(0, library.reload());
        assertNotSame(before.get("sprite:trail/a"), library.sheets().get("sprite:trail/a"));
        Files.delete(a.resolve("sheet.png"));
        Files.delete(json);
        Files.delete(a);
        assertEquals(0, library.reload());
        assertTrue(library.sheets().isEmpty());
    }

    @Test
    void rejectsAmbiguousFilesNonDivisibleStripsAndOversizeOrTooManyFrames() throws Exception {
        Path multiple = folder("multiple");
        png(multiple.resolve("one.png"), 2, 2);
        png(multiple.resolve("two.png"), 2, 2);
        png(folder("ambiguous").resolve("image.png"), 5, 2);
        png(folder("oversized").resolve("long.png"), 8193, 1);
        Path grid = folder("overflow");
        png(grid.resolve("sheet.png"), 64, 64);
        Files.writeString(grid.resolve("grid.json"), "{\"frameWidth\":1,\"frameHeight\":1}");
        SpriteLibrary library = new SpriteLibrary(config.resolve("xp_orb_trails"));
        assertEquals(4, library.reload());
        assertTrue(library.sheets().isEmpty());
        assertFalse(SpriteLibrary.isSpriteId(null));
        for (String id : List.of("sprite:trail/", "sprite:trail/Big", "sprite:trail/a/b", "sprite:trail/" + "x".repeat(65))) {
            assertFalse(SpriteLibrary.isSpriteId(id), id);
        }
        assertTrue(SpriteLibrary.isSpriteId("sprite:trail/small_22-test"));
    }

    @Test
    void reservesTheCombinedPixelBudgetForExistingSheetsAndFreesItOnRemoval() throws Exception {
        Path first = folder("a").resolve("sheet.png");
        assertTrue(ImageIO.write(new BufferedImage(2048, 2048, BufferedImage.TYPE_BYTE_BINARY), "PNG", first.toFile()));
        Path orb = Files.createDirectories(config.resolve("xp_orb_trails/orb/sprites/b"));
        Path pickup = Files.createDirectories(config.resolve("xp_orb_trails/pickup/sprites/c"));
        for (Path dir : List.of(orb, pickup, folder("d"))) Files.copy(first, dir.resolve("sheet.png"));
        SpriteLibrary library = new SpriteLibrary(config.resolve("xp_orb_trails"));
        assertEquals(0, library.reload());
        assertEquals(4, library.sheets().size());
        var previous = library.sheets().get("sprite:orb/b");
        Files.writeString(orb.resolve("sheet.json"), "{");
        png(folder("0-new").resolve("sheet.png"), 2, 2);
        assertEquals(2, library.reload());
        assertSame(previous, library.sheets().get("sprite:orb/b"));
        assertFalse(library.sheets().containsKey("sprite:trail/0-new"));
        Files.delete(first);
        assertEquals(1, library.reload());
        assertTrue(library.sheets().containsKey("sprite:trail/0-new"));
        assertSame(previous, library.sheets().get("sprite:orb/b"));
    }

    @Test
    void limitsSheetCountAndKeepsTheSnapshotIfTheLibraryCannotBeListed() throws Exception {
        Path first = folder("a").resolve("s.png");
        png(first, 2, 2);
        for (int i = 0; i < 64; i++) Files.copy(first, folder("s" + i).resolve("s.png"));
        folder("z-empty");
        SpriteLibrary library = new SpriteLibrary(config.resolve("xp_orb_trails"));
        assertEquals(1, library.reload());
        assertEquals(64, library.sheets().size());
        Path root = first.getParent().getParent();
        Files.move(root, root.resolveSibling("old-sprites"));
        Files.writeString(root, "unavailable");
        var previous = library.sheets();
        assertEquals(1, library.reload());
        assertSame(previous, library.sheets());
    }

    @Test
    void millisecondBoundariesStillHoldWhenSecondsHaveNoExactBinaryRepresentation() throws Exception {
        Path folder = folder("timing");
        png(folder.resolve("sheet.png"), 2, 1);
        Files.writeString(folder.resolve("atlas.json"), """
                {"frames":[{"frame":{"x":0,"y":0,"w":1,"h":1},"duration":1001},
                           {"frame":{"x":1,"y":0,"w":1,"h":1},"duration":1000}]}
                """);
        SpriteLibrary library = new SpriteLibrary(config.resolve("xp_orb_trails"));
        assertEquals(0, library.reload());
        var sheet = library.sheets().get("sprite:trail/timing");
        assertEquals(0, sheet.frameAt(Math.nextDown(1.001), false).x());
        assertEquals(1, sheet.frameAt(1.001, false).x());
        assertNull(sheet.frameAt(2.001, false));
        assertEquals(0, sheet.frameAt(2.001, true).x());
    }

    @Test
    void installsBundledExampleOnFirstUseAndRespectsEditsAndDeletion() throws Exception {
        Path fresh = config.resolve("fresh");
        SpriteLibrary library = new SpriteLibrary(fresh);
        assertEquals(0, library.reload());
        assertTrue(library.sheets().containsKey("sprite:trail/comet-strip"));
        Path image = fresh.resolve("trail/sprites/comet-strip/comet.png");
        png(image, 3, 3);
        assertEquals(0, library.reload());
        assertEquals(3, library.sheets().get("sprite:trail/comet-strip").width());
        Files.delete(image);
        assertEquals(0, library.reload());
        assertFalse(library.sheets().containsKey("sprite:trail/comet-strip"));
        assertFalse(Files.exists(image));
    }
}
