package dev.goldeneggovo.xporbtrails;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RadialShapeTest {
    private record Vertex(double x, double y, int color) {}

    private static CustomEffect effect(String radii, String options) throws IOException {
        return CustomEffect.read(new StringReader("""
                {"version":1,"layers":[{"shape":"radial","radii":%s,%s
                "frames":[{"t":0,"x":1,"y":2,"width":4,"height":2,"rotation":90},{"t":1}]}]}
                """.formatted(radii, options)), "My star");
    }

    @Test
    void alternatingRadiiDrawTheSpecifiedColoredPointsAroundTheAnimatedCenter() throws Exception {
        var star = effect("[1,0.25,1,0.25,1,0.25,1,0.25]", "");
        var vertices = new ArrayList<Vertex>();
        star.draw(0, 0.5, new TrailConfig(), 0xAA00FF, 0x00FFAA, (x,y,z,world,color) -> {
            assertFalse(world);
            assertEquals(0, z);
            vertices.add(new Vertex(x, y, color));
        });
        assertEquals(8 * 4, vertices.size());
        Vertex tip = vertices.get(1), next = vertices.get(2);
        assertEquals(0, tip.x, 1e-8);
        assertEquals(2, tip.y, 1e-8);
        assertEquals(1 - Math.sqrt(0.5) * 0.25, next.x, 1e-8);
        assertEquals(2 - Math.sqrt(0.5) * 0.5, next.y, 1e-8);
        for (int i = 0; i < vertices.size(); i += 4) {
            assertEquals(new Vertex(1, 2, 0x7F00FFAA), vertices.get(i));
            assertEquals(vertices.get(i + 2), vertices.get(i + 3));
            assertEquals(vertices.get((i + 5) % vertices.size()), vertices.get(i + 2));
        }
    }

    @Test
    void invalidOutlinesAndExcessiveCopyCountsAreRejectedOnLoad() {
        for (String invalid : List.of("[]", "[1,0.5]", "[1,-1,1]", "[1,2,1]", "[1,0,1,0]",
                "[1,\"1\",1]", "[" + "1,".repeat(32) + "1]")) {
            assertThrows(IOException.class, () -> effect(invalid, ""), invalid);
        }
        assertThrows(IOException.class, () -> effect("[1,0.25,1,0.25,1,0.25,1,0.25]", "\"count\":13,"));
    }
}
