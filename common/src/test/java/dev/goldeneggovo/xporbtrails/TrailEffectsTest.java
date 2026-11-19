package dev.goldeneggovo.xporbtrails;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrailEffectsTest {
    @Test
    void reflowTravelsFromTailToHeadAndErasesThePassedTrail() {
        assertEquals(0, TrailEffects.reflowFront(0));
        assertEquals(1, TrailEffects.reflowFront(1));
        double front = TrailEffects.reflowFront(0.5);
        assertEquals(0, TrailEffects.reflowVisibility(0.4, front));
        assertEquals(1, TrailEffects.reflowVisibility(0.6, front));
        assertEquals(1, TrailEffects.band(front, front, 0.1));
        assertEquals(0, TrailEffects.band(0.8, front, 0.1));
    }

    @Test
    void flightPulseMovesForwardAndRepeatsAtTheConfiguredFrequency() {
        double atQuarterCycle = TrailEffects.pulseFront(0.25, 1);
        assertTrue(TrailEffects.pulseFront(0.5, 1) > atQuarterCycle);
        assertEquals(atQuarterCycle, TrailEffects.pulseFront(1.25, 1));
        assertEquals(atQuarterCycle, TrailEffects.pulseFront(0.125, 2));
    }

    @Test
    void shardsKeepTheirSquareShapeAsTheySeparateAndFade() throws IOException {
        TrailConfig c = new TrailConfig();
        c.shardCount = 9;
        List<Piece> early = pieces("shards", c, 0.1), late = pieces("shards", c, 0.8);
        assertEquals(c.shardCount, early.size());
        for (int i = 0; i < late.size(); i++) {
            Piece a = early.get(i), b = late.get(i);
            assertEquals(b.length, b.width);
            assertTrue(b.radius() > a.radius());
            assertTrue((b.color >>> 24) < (a.color >>> 24));
        }
        assertTrue(pieces("shards", c, 1).isEmpty());
    }

    @Test
    void spiralHasTwoOppositeArmsThatRotateAndContract() throws IOException {
        TrailConfig c = new TrailConfig();
        List<Piece> early = pieces("spiral", c, 0.2), late = pieces("spiral", c, 0.8);
        int half = early.size() / 2;
        assertTrue(half > 1);
        for (int i = 0; i < half; i++) {
            Piece a = early.get(i), b = early.get(i + half);
            assertEquals(a.x, -b.x, 1e-10);
            assertEquals(a.y, -b.y, 1e-10);
            assertNotEquals(a.color & 0xFFFFFF, b.color & 0xFFFFFF);
            assertTrue(late.get(i).radius() < a.radius());
        }
        c.spiralTurns *= 2;
        Piece turned = pieces("spiral", c, 0.2).getFirst();
        assertEquals(early.getFirst().radius(), turned.radius(), 1e-10);
        assertNotEquals(early.getFirst().x, turned.x, 1e-6);
        c.startColor = c.endColor = 0xFFFFFF;
        List<Piece> white = pieces("spiral", c, 0.2);
        assertNotEquals(white.getFirst().color & 0xFFFFFF, white.get(half).color & 0xFFFFFF);
    }

    private static List<Piece> pieces(String fixture, TrailConfig c, double progress) throws IOException {
        List<Piece> result = new ArrayList<>();
        try (var input = new InputStreamReader(TrailEffectsTest.class.getResourceAsStream(
                "/assets/xporbtrails/effects/pickup/" + fixture + ".json"), StandardCharsets.UTF_8)) {
            var effect = CustomEffect.read(input, fixture);
            effect.draw(progress, c.effectStrength * c.pickupFlashStrength, c, c.startColor, c.endColor, new TrailEffects.PieceSink() {
                @Override public void rectangle(double x, double y, double a, double length, double width, int color) {
                    result.add(new Piece(x, y, length, width, color));
                }
                @Override public void vertex(double x, double y, double z, boolean worldAxes, int color) {
                    fail("Expected rectangle geometry");
                }
            });
        }
        return result;
    }

    private record Piece(double x, double y, double length, double width, int color) {
        double radius() { return Math.hypot(x, y); }
    }
}
