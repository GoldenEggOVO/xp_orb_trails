package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.HashSet;

import static dev.goldeneggovo.xporbtrails.PixelWorkshopImage.*;
import static org.junit.jupiter.api.Assertions.*;

class PixelWorkshopTest {
    @Test void acceptsEditableReliefSamplingAndRejectsInvalidLimits() {
        var options = JsonParser.parseString("""
                {"type":"relief","minResolution":24,"maxResolution":24,"pixelBudget":1800}
                """).getAsJsonObject();
        assertDoesNotThrow(() -> PixelWorkshop.validate(options));
        assertEquals(24, PixelWorkshop.reliefResolution(new OrbEffectOptions(options), 64));
        var oldDefaults = OrbEffectOptions.EMPTY;
        assertEquals(32, PixelWorkshop.reliefResolution(oldDefaults, 0));
        assertEquals(20, PixelWorkshop.reliefResolution(oldDefaults, 3));
        options.addProperty("maxResolution", 23);
        assertThrows(IllegalArgumentException.class, () -> PixelWorkshop.validate(options));
        options.addProperty("maxResolution", 64);
        assertEquals(42, PixelWorkshop.reliefResolution(new OrbEffectOptions(options), 1));
        options.addProperty("pixelBudget", 0);
        assertThrows(IllegalArgumentException.class, () -> PixelWorkshop.validate(options));
        options.addProperty("pixelBudget", 1200.5);
        assertThrows(IllegalArgumentException.class, () -> PixelWorkshop.validate(options));
    }

    private static SpriteSheet.Frame frame(int w, int h) {
        return new SpriteSheet.Frame(0, 0, w, h, w, h, 0, 0, w * .5, h * .5);
    }

    @Test void currentFrameKeepsActualArgbTrimPivotAndSeparatesItsOutline() {
        var png = new BufferedImage(10, 5, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) {
            png.setRGB(x, y, 0xff112233);
            png.setRGB(5 + x, 1 + y, 0x8040c0ff);
        }
        var f = new SpriteSheet.Frame(5, 1, 3, 3, 7, 6, 2, 1, 3, 4);
        var source = new Image(png, f);
        assertEquals(9, cells(source).size());
        assertEquals(8, cells(source).stream().filter(Cell::edge).count());
        assertEquals(new Cell(-.5, 2.5, 1, 1, 0x8040c0ff, true), cells(source).getFirst());
        assertEquals(0x8040c0ff, source.relative(2.5 / 7, 1.5 / 6));
        assertEquals(0, source.relative(.5 / 7, .5 / 6));
        assertEquals(0x4040c0ff, tint(0x8040c0ff, -1, .5));
    }

    @Test void peeledSpansKeepOnlyIntactTexelsInTrimmedFramesWithPartialEdges() {
        var frame = new SpriteSheet.Frame(8, 3, 5, 3, 12, 8, 2, 1, 6, 4);
        boolean[] holes = {false, true, false, true, false, false};
        var spans = intactStrips(frame, 2, holes);
        assertEquals(3, spans.size());
        var seen = new HashSet<Integer>();
        for (var span : spans) {
            var uv = span.uv();
            int x = uv.x() - frame.x(), y = uv.y() - frame.y();
            assertEquals(frame.offsetX() + x + uv.width() * .5 - frame.pivotX(), span.x());
            assertEquals(frame.pivotY() - frame.offsetY() - y - uv.height() * .5, span.y());
            for (int row = y; row < y + uv.height(); row++)
                for (int col = x; col < x + uv.width(); col++)
                    assertTrue(seen.add(row * frame.width() + col));
        }
        for (int y = 0; y < 3; y++) for (int x = 0; x < 5; x++)
            assertEquals(!holes[y / 2 * 3 + x / 2], seen.contains(y * 5 + x));
    }

    @Test void cameraCirclesAllEightViewsAndFollowsTheTravelHeading() {
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            assertEquals(i, sector(-Math.sin(a), Math.cos(a), 0));
            assertEquals(Math.floorMod(i - 2, 8), sector(-Math.sin(a), Math.cos(a), Math.PI / 2));
        }
        assertEquals(0, sector(.001, 1, 2 * Math.PI));
    }

    @Test void drawnCrackCreatesRigidIslandsAndAllOriginalTexelsSurvive() {
        int width = 99, height = 51;
        var mask = new BufferedImage(33, 17, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 17; y++) mask.setRGB(16, y, 0xff000000);
        var f = new SpriteSheet.Frame(3, 4, width, height, width, height, 0, 0, 35, 24);
        var strips = fracture(f, new Image(mask, frame(33, 17)), 4, 31);
        var seen = new int[width * height];
        var groups = new HashSet<Integer>();
        for (var strip : strips) {
            groups.add(strip.shard());
            var uv = strip.uv();
            assertEquals(uv.x() - f.x() + uv.width() * .5 - f.pivotX(), strip.x());
            assertEquals(f.pivotY() - (uv.y() - f.y()) - uv.height() * .5, strip.y());
            for (int y = uv.y(); y < uv.y() + uv.height(); y++) {
                for (int x = uv.x(); x < uv.x() + uv.width(); x++) seen[(y - f.y()) * width + x - f.x()]++;
            }
        }
        assertEquals(2, groups.size());
        for (int hits : seen) assertEquals(1, hits);
    }

    @Test void quantizationStaysBoundedAndLuminanceRespondsToTheHeightMap() {
        var png = new BufferedImage(260, 190, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 190; y++) for (int x = 0; x < 260; x++) png.setRGB(x, y, -1);
        var source = new Image(png, frame(260, 190));
        assertTrue(cells(source).size() <= 32 * 32);
        assertEquals(1, brightness(-1), .000001);
        assertEquals(0, brightness(0x00ffffff));
        assertTrue(brightness(0xff00ff00) > brightness(0xffff0000));
        assertEquals(0, PixelWorkshop.assemblyProgress(0, 12));
        assertEquals(.5, PixelWorkshop.assemblyProgress(6, 12));
        assertEquals(1, PixelWorkshop.assemblyProgress(30, 12));
    }

    @Test void rejectsInvalidTimingsAndWrongSpriteRoleBeforeRuntime() {
        for (String json : new String[]{"{\"type\":\"assemble\",\"formTicks\":0}",
                "{\"type\":\"assemble\",\"mode\":\"edge\"}", "{\"type\":\"shed\",\"count\":1.5}",
                "{\"type\":\"fracture\",\"fractureMap\":\"sprite:pickup/nope\"}"}) {
            assertThrows(IllegalArgumentException.class, () -> PixelWorkshop.validate(JsonParser.parseString(json).getAsJsonObject()));
        }
    }
}
