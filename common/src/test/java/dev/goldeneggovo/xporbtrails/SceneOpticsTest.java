package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SceneOpticsTest {
    @Test
    void settingsLimitGpuWorkAndRejectUnknownEffects() {
        var config = JsonParser.parseString("""
                {"optics":{"type":"heat","radius":-9,"strength":100,"maxOrbs":400}}
                """).getAsJsonObject();
        var heat = SceneOptics.Settings.read(config);
        assertTrue(heat.heat());
        assertEquals(0.1f, heat.radius());
        assertEquals(12f, heat.strength());
        assertEquals(SceneOptics.MAX_SOURCES, heat.maxOrbs());
        config.getAsJsonObject("optics").addProperty("type", "light");
        config.getAsJsonObject("optics").addProperty("color", "#88dd66");
        var light = SceneOptics.Settings.read(config);
        assertFalse(light.heat());
        assertEquals(4f, light.strength());
        assertEquals(0xff88dd66, light.color());
        config.getAsJsonObject("optics").addProperty("type", "unknown");
        assertNull(SceneOptics.Settings.read(config));
        config.addProperty("optics", 4);
        assertNull(SceneOptics.Settings.read(config));
        assertNull(SceneOptics.Settings.read(null));
    }

    @Test
    void oversubscribedSourcesRetainNearestOrbsAndStableTies() {
        var sources = new ArrayList<SceneOptics.Source>();
        for (int x : new int[]{9, 5, -5, 6, 4, 8, 5}) {
            var point = new Vec3(x, 0, 0);
            SceneOptics.addNearest(sources, point, point.lengthSqr(), 3);
        }
        assertEquals(List.of(4.0, 5.0, -5.0), sources.stream().map(s -> s.position().x).toList());
    }
}
