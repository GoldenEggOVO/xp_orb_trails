package dev.goldeneggovo.xporbtrails;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CustomEffectTest {
    private static final int TAIL = 0x123456, HEAD = 0xFEDCBA;
    private static final String FRAMES = "\"frames\":[{\"t\":0},{\"t\":1}]";
    private static final String LAYER = "{" + FRAMES + "}";

    @Test
    void defaultsToOneCenteredHeadColoredSquareAndUsesTheFilename() throws IOException {
        CustomEffect effect = read(document(LAYER));
        assertEquals("from-filename", effect.name());
        assertEquals(List.of(new Piece(0, 0, 0, 0.125, 0.125, 0xFFFEDCBA)), pieces(effect, 0, 1));
        assertTrue(pieces(effect, -0.01, 1).isEmpty());
        assertTrue(pieces(effect, 1, 1).isEmpty());
        assertTrue(pieces(effect, 0.5, 0).isEmpty());
    }

    @Test
    void drawsTwoIndependentLayersWithRepeatedRotatedCopiesAndInheritedKeyframes() throws IOException {
        CustomEffect effect = read("""
                {"layers":[
                  {"frames":[{"width":2,"x":1,"t":0,"height":1,"y":-1},
                             {"radius":2,"t":0.5,"angle":180,"rotation":90,"opacity":0.5},
                             {"angle":360,"x":3,"t":1}],"count":2,"color":"tail"},
                  {"color":"#aBCd09","frames":[{"t":0,"radius":1},{"t":1,"y":2}]}
                ],"name":"  Double  ","version":1}
                """);
        assertEquals("Double", effect.name());
        List<Piece> result = pieces(effect, 0.25, 0.8);
        assertEquals(3, result.size());
        assertPiece(result.get(0), 1, 0, Math.PI / 4, 1, 0.5, 0x99123456);
        assertPiece(result.get(1), 1, -2, Math.PI * 1.25, 1, 0.5, 0x99123456);
        assertPiece(result.get(2), 1, 0.5, 0, 0.125, 0.125, 0xCCABCD09);
        assertPiece(pieces(effect, 0.5, 1).getFirst(), -1, -1, Math.PI / 2, 1, 0.5, 0x7F123456);
        assertPiece(pieces(effect, 0.75, 2).getFirst(), 2, -3, Math.PI / 2, 1, 0.5, 0xFF123456);
    }

    @Test
    void interpolatesBothDimensionsAndClampsStrengthToFullAlpha() throws IOException {
        CustomEffect effect = read(document("""
                {"color":"head","frames":[{"width":0,"height":4,"t":0},{"width":8,"height":0,"t":1}]}
                """));
        assertPiece(pieces(effect, 0.25, 4).getFirst(), 0, 0, 0, 1, 1.5, 0xFFFEDCBA);
    }

    @Test
    void animatesBothCopyColorsFromLayerDefaultsAndHoldsOmittedKeyframes() throws IOException {
        CustomEffect effect = read(document("""
                {"count":2,"easing":"out","frames":[{"t":0},{"t":0.5,"color":"tail","alternateColor":"head"},{"t":1}],
                 "color":"#000000","alternateColor":"#ffffff"}
                """));
        assertTrue(effect.usesTrailColors());
        assertEquals(List.of(0xFF000000, 0xFFFFFFFF), colors(effect, 0));
        assertEquals(List.of(0xFF071625, 0xFFFEEFE0), colors(effect, 0.125));
        assertEquals(List.of(0xFF123456, 0xFFFEDCBA), colors(effect, 0.5));
        assertEquals(List.of(0xFF123456, 0xFFFEDCBA), colors(effect, 0.9));
    }

    @Test
    void copiesFollowTheAnimatedPrimaryColorUntilGivenAnAlternate() throws IOException {
        CustomEffect effect = read(document("""
                {"count":2,"frames":[{"t":0,"color":"#ff0000"},{"t":0.5,"color":"#0000ff"},
                                     {"t":1,"alternateColor":"#00ff00"}]}
                """));
        assertFalse(effect.usesTrailColors());
        assertEquals(List.of(0xFF7F007F, 0xFF7F007F), colors(effect, 0.25));
        assertEquals(List.of(0xFF0000FF, 0xFF007F7F), colors(effect, 0.75));
    }

    @Test
    void onlyOffersSharedColorsWhenAVisibleCopyBindsToTheTrailPalette() throws IOException {
        String layer = """
                {"count":2,"color":"#666666","frames":[{"t":0},{"t":1,"alternateColor":"tail"}]}
                """;
        assertTrue(read(document(layer)).usesTrailColors());
        assertFalse(read(document(layer.replace("\"count\":2", "\"count\":1"))).usesTrailColors());
    }

    private static List<Integer> colors(CustomEffect effect, double progress) {
        return pieces(effect, progress, 1).stream().map(Piece::argb).toList();
    }

    @Test
    void includesEveryRadialPointAndCopyInTheGeometryBudget() throws IOException {
        String radii = String.join(",", Collections.nCopies(32, "1"));
        String layer = "{\"radii\":[" + radii + "],\"count\":3,\"shape\":\"radial\"," + FRAMES + "}";
        CustomEffect effect = read(document(layer));
        List<Integer> vertices = new ArrayList<>();
        effect.draw(0.5, 1, new TrailConfig(), TAIL, HEAD, (x, y, z, worldAxes, color) -> {
            assertFalse(worldAxes);
            vertices.add(color);
        });
        assertEquals(Collections.nCopies(96 * 4, 0xFFFEDCBA), vertices);
        assertThrows(IOException.class, () -> read(document(layer + "," + LAYER)));
    }

    @Test
    void requiresRadiiOnRadialShapesAndRejectsThemOnOtherShapes() {
        assertFieldError(document("{\"shape\":\"radial\"," + FRAMES + "}"), "radial", "radii");
        assertFieldError(document("{\"radii\":[1,1,1]," + FRAMES + "}"), "radial", "radii");
    }

    @Test
    void acceptsTheDocumentedLimitsIncludingNinetySixCopies() throws IOException {
        List<String> frames = new ArrayList<>();
        frames.add("{\"t\":0,\"x\":-8,\"y\":8,\"angle\":-3600,\"rotation\":3600,\"radius\":8,\"opacity\":0}");
        for (int i = 1; i < 15; i++) frames.add("{\"t\":" + i / 15.0 + "}");
        frames.add("{\"t\":1,\"x\":8,\"y\":-8,\"angle\":3600,\"rotation\":-3600,\"opacity\":1}");
        String many = "{\"count\":32,\"frames\":[" + String.join(",", frames) + "]}";
        assertEquals(96, pieces(read(document(String.join(",", Collections.nCopies(3, many)))), 0.99, 1).size());
        assertEquals(8, pieces(read(document(String.join(",", Collections.nCopies(8, LAYER)))), 0.25, 1).size());
        frames.add(15, "{\"t\":0.99}");
        String tooMany = document("{\"frames\":[" + String.join(",", frames) + "]}");
        assertThrows(IOException.class, () -> read(tooMany));
    }

    @Test
    void rejectsMalformedDocumentsAndCounts() {
        String valid = document(LAYER);
        List<String> invalid = List.of("", "{", "[]", "{}", "{\"layers\":[" + LAYER + "]}",
                valid.replace("\"version\":1", "\"version\":\"1\""), valid.replace("\"version\":1", "\"version\":2"),
                valid.replace("\"version\":1", "\"name\":\" \",\"version\":1"),
                valid.replace("\"layers\"", "\"layerz\""), valid + "{}", "{\"version\":1,\"layers\":[]}",
                document(String.join(",", Collections.nCopies(9, LAYER))),
                document(String.join(",", Collections.nCopies(4, "{\"count\":25," + FRAMES + "}"))),
                document("{\"count\":0," + FRAMES + "}"), document("{\"count\":33," + FRAMES + "}"),
                document("{\"count\":1.5," + FRAMES + "}"), document("{\"count\":1.00000000000000001," + FRAMES + "}"),
                document("{\"color\":12," + FRAMES + "}"), document("{\"color\":\"#FFF\"," + FRAMES + "}"),
                document("{\"color\":\"#zzzzzz\"," + FRAMES + "}"), document("{\"color\":\"other\"," + FRAMES + "}"),
                document("{}"), document("{\"frames\":[]}"), document("{\"frames\":[{\"t\":0}]}"));
        for (String value : invalid) assertThrows(IOException.class, () -> read(value), value);
    }

    @Test
    void rejectsBadFrameTimesAndGeometry() {
        for (String frames : List.of("{},{\"t\":1}", "{\"t\":0.1},{\"t\":1}",
                "{\"t\":0},{\"t\":0.9}", "{\"t\":0},{\"t\":0},{\"t\":1}",
                "{\"t\":0},{\"t\":0.5},{\"t\":0.25},{\"t\":1}")) {
            assertThrows(IOException.class, () -> read(document("{\"frames\":[" + frames + "]}")), frames);
        }
        for (String field : List.of("\"radius\":-1", "\"radius\":8.1", "\"x\":9", "\"y\":-9",
                "\"width\":-0.01", "\"height\":9", "\"rotation\":3601", "\"angle\":-3601",
                "\"opacity\":1.01", "\"x\":1e999", "\"y\":NaN", "\"width\":\"1\"", "\"angle\":null",
                "\"color\":\"#fff\"", "\"alternateColor\":\"#12345678\"")) {
            String layer = "{\"frames\":[{\"t\":0," + field + "},{\"t\":1}]}";
            assertThrows(IOException.class, () -> read(document(layer)), field);
        }
    }

    @Test
    void identifiesDuplicateAndUnknownFieldsAtEveryLevel() {
        String valid = document(LAYER);
        assertFieldError(valid.replace("\"version\":1", "\"version\":1,\"version\":1"), "Duplicate", "version");
        assertFieldError(document("{\"count\":2,\"count\":1," + FRAMES + "}"), "Duplicate", "count");
        assertFieldError(valid.replace("\"t\":0", "\"t\":0,\"t\":0"), "Duplicate", "t");
        assertFieldError(valid.replace("\"version\":1", "\"verzion\":1"), "Unknown", "verzion");
        assertFieldError(document("{\"copies\":1," + FRAMES + "}"), "Unknown", "copies");
        assertFieldError(valid.replace("\"t\":0", "\"t\":0,\"raduis\":1"), "Unknown", "raduis");
    }

    private static void assertFieldError(String value, String kind, String field) {
        String message = assertThrows(IOException.class, () -> read(value)).getMessage();
        assertTrue(message.contains(kind) && message.contains(field), message);
    }

    private static String document(String layers) {
        return "{\"version\":1,\"layers\":[" + layers + "]}";
    }

    private static CustomEffect read(String value) throws IOException {
        return CustomEffect.read(new StringReader(value), "from-filename");
    }

    private record Piece(double x, double y, double angle, double halfWidth, double halfHeight, int argb) {}

    private static List<Piece> pieces(CustomEffect effect, double progress, double strength) {
        List<Piece> pieces = new ArrayList<>();
        effect.draw(progress, strength, new TrailConfig(), TAIL, HEAD, new TrailEffects.PieceSink() {
            @Override public void rectangle(double x, double y, double angle, double w, double h, int color) {
                pieces.add(new Piece(x, y, angle, w, h, color));
            }
            @Override public void vertex(double x, double y, double z, boolean worldAxes, int color) {
                fail("Expected rectangle layer");
            }
        });
        return pieces;
    }

    private static void assertPiece(Piece actual, double x, double y, double angle, double width, double height, int argb) {
        assertEquals(x, actual.x, 1e-10);
        assertEquals(y, actual.y, 1e-10);
        assertEquals(angle, actual.angle, 1e-10);
        assertEquals(width, actual.halfWidth, 1e-10);
        assertEquals(height, actual.halfHeight, 1e-10);
        assertEquals(argb, actual.argb);
    }
}
