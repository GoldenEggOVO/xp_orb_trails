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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FixedStampHistoryTest {
    @Test
    void crossesCornersAtMeasuredDistancesAndKeepsTheRemainderAfterExpiry() {
        var history = new FixedStampHistory();
        history.append(Vec3.ZERO, 0, 1, 0, 2);
        history.append(new Vec3(0.75, 0, 0), 75, 1, 7.5, 2);
        history.append(new Vec3(0.75, 1.5, 0), 225, 1, 22.5, 5);
        var original = history.snapshot(0);
        assertEquals(List.of(new FixedStampHistory.Mark(new Vec3(0.75, 0.25, 0), 100, 10, 2),
                new FixedStampHistory.Mark(new Vec3(0.75, 1.25, 0), 200, 20, 2)), original);
        assertEquals(List.of(original.getLast()), history.snapshot(101));
        history.append(new Vec3(1.5, 1.5, 0), 300, 1, 30, 5);
        var trimmed = history.snapshot(101);
        assertSame(original.getLast(), trimmed.getFirst());
        assertEquals(new FixedStampHistory.Mark(new Vec3(1.5, 1.5, 0), 300, 30, 5), trimmed.getLast());
    }

    @Test
    void waitingRecordsNoNewMarksAndTheNextCrossingUsesItsActualTravelTime() {
        var history = new FixedStampHistory();
        history.append(Vec3.ZERO, 0, 0.2);
        history.append(new Vec3(0.4, 0, 0), 40, 0.2);
        var old = history.snapshot(0);
        for (int time = 50; time <= 500; time += 10) history.append(new Vec3(0.4, 0, 0), time, 0.2);
        assertSame(old, history.snapshot(0));
        assertTrue(history.snapshot(500).isEmpty());
        history.append(new Vec3(0.8, 0, 0), 540, 0.2);
        assertEquals(List.of(520L, 540L), history.snapshot(500).stream().map(FixedStampHistory.Mark::time).toList());
    }

    @Test
    void retainsOnlyTheNewestSixteenCrossingsEvenOnAVeryLargeMove() {
        var history = new FixedStampHistory();
        history.append(Vec3.ZERO, 0, 0.25);
        history.append(new Vec3(8_000_000, 0, 0), 50_000_000, 0.25);
        var marks = history.snapshot(0);
        assertEquals(16, marks.size());
        for (int i = 0; i < marks.size(); i++) assertEquals(8_000_000 - (15 - i) * 0.25, marks.get(i).position().x);
    }

    @Test
    void pickupUsesTheFrozenMarksAndStackedOrbRestartsAtItsNewOrigin() {
        var history = new FixedStampHistory();
        history.append(Vec3.ZERO, 0, 1);
        history.append(new Vec3(4, 0, 0), 400_000_000, 1);
        var pickup = history.snapshot(0);
        history.reset();
        history.append(new Vec3(12, 0, 0), 500_000_000, 1);
        assertTrue(history.snapshot(0).isEmpty());
        var cfg = new TrailConfig();
        var visible = new ArrayList<FixedStampHistory.Mark>();
        TrailRenderer.emitFixedStamps(pickup, 400_000_000, cfg, 1, 0.5, false,
                (mark, scale, alpha) -> visible.add(mark));
        assertEquals(pickup.subList(2, 4), visible);
        visible.clear();
        TrailRenderer.emitFixedStamps(pickup, 400_000_000, cfg, 0, -1, false,
                (mark, scale, alpha) -> visible.add(mark));
        TrailRenderer.emitFixedStamps(pickup, 11_000_000_000L, cfg, 1, -1, false,
                (mark, scale, alpha) -> visible.add(mark));
        assertTrue(visible.isEmpty());
    }

    @Test
    void bodyFramesStayAtCrossingPhaseWhileSeparateFootprintsPlayFromBirth(@TempDir Path dir) throws Exception {
        var folder = Files.createDirectories(dir.resolve("xp_orb_trails/orb/sprites/probe"));
        ImageIO.write(new BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB), "PNG", folder.resolve("body.png").toFile());
        Files.writeString(folder.resolve("body.json"), """
                {"frameWidth":1,"frameHeight":1,"fps":10,"loop":false}
                """);
        var library = new SpriteLibrary(dir.resolve("xp_orb_trails"));
        assertEquals(0, library.reload());
        var sheet = library.sheets().get("sprite:orb/probe");
        var mark = new FixedStampHistory.Mark(Vec3.ZERO, 200_000_000, 4, 2);
        assertEquals(2, mark.spriteFrame(sheet, 200_000_000, 0, true, true).x());
        assertEquals(2, mark.spriteFrame(sheet, 500_000_000, 0, true, true).x());
        assertEquals(0, mark.spriteFrame(sheet, 200_000_000, 0, false, false).x());
        assertEquals(1, mark.spriteFrame(sheet, 300_000_000, 0, false, false).x());
        assertNull(mark.spriteFrame(sheet, 500_000_000, 0, false, false));
        var late = new FixedStampHistory.Mark(Vec3.ZERO, 400_000_000, 8, 5);
        assertNull(late.spriteFrame(sheet, 700_000_000, 0, true, true));
        assertEquals(1, late.spriteFrame(sheet, 700_000_000, 0, true, false).x());

        var cfg = new TrailConfig();
        cfg.trailSprite = "body";
        cfg.flightEffect = "sprite:orb/probe";
        var selected = FixedStampHistory.Source.selected(cfg, library.sheets(), Map.of());
        assertTrue(selected.vanilla());
        assertSame(sheet, selected.sprite());
        assertEquals(0, library.reload());
        assertNotEquals(selected, FixedStampHistory.Source.selected(cfg, library.sheets(), Map.of()));
        cfg.showVanillaOrb = false;
        assertTrue(FixedStampHistory.Source.selected(cfg, Map.of(), Map.of()).vanilla());
        cfg.flightEffect = "none";
        assertNull(FixedStampHistory.Source.selected(cfg, Map.of(), Map.of()));
        cfg.trailSprite = "sprite:trail/comet-strip";
        assertNull(FixedStampHistory.Source.selected(cfg, library.sheets(), Map.of()));
        cfg.fixedStamps = true;
        assertNotNull(FixedStampHistory.Source.selected(cfg, library.sheets(), Map.of()));
        cfg.trailSpriteMode = "ribbon";
        assertNull(FixedStampHistory.Source.selected(cfg, library.sheets(), Map.of()));
    }
}
