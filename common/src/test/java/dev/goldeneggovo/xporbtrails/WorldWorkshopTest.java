package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

class WorldWorkshopTest {
    @Test void crossingsRequireTransverseSegmentsCloseInAllThreeDimensions() {
        Vec3 left = new Vec3(-2, 0, 0), right = new Vec3(2, 0, 0);
        assertEquals(Vec3.ZERO, WorldWorkshopGeometry.crossing(left, right, new Vec3(0, 0, -2), new Vec3(0, 0, 2), .1));
        assertNull(WorldWorkshopGeometry.crossing(left, right, new Vec3(0, .2, -2), new Vec3(0, .2, 2), .1));
        assertNull(WorldWorkshopGeometry.crossing(left, right, new Vec3(0, 0, 0), new Vec3(3, 0, .01), .1));
        assertNull(WorldWorkshopGeometry.crossing(left, right, new Vec3(4, 0, -2), new Vec3(4, 0, 2), .1));
        assertNull(WorldWorkshopGeometry.crossing(left, left, new Vec3(0, 0, -2), new Vec3(0, 0, 2), .1));
        assertNotNull(WorldWorkshopGeometry.crossing(left, right, new Vec3(2.06, 0, -.04), new Vec3(2.06, 0, 2), .1));
    }

    @Test void fishTakeOppositeLanesAroundTheSameSolidObstacleAndRegroup() {
        var obstruction = new AABB(-.5, 0, 0, .5, 2, 1);
        BiPredicate<Vec3, Vec3> clear = (a,b) -> !obstruction.contains(b) && obstruction.clip(a,b).isEmpty();
        Vec3 right = new Vec3(1, 0, 0), heading = new Vec3(0, 0, 1);
        for (int lane : new int[]{-1, 1}) {
            var fish = new WorldWorkshopGeometry.Fish(new Vec3(lane * .18, 1, -.55), heading);
            Vec3 target = new Vec3(lane * .2, 1, 1.95);
            boolean skirted = false;
            for (int i = 0; i < 60; i++) {
                fish.swim(target, right, lane, .15, clear);
                assertFalse(obstruction.contains(fish.position));
                if (fish.position.z >= .10 && fish.position.z <= .9 && fish.position.x * lane >= .50) skirted = true;
            }
            assertTrue(skirted);
            assertTrue(fish.position.distanceTo(target) < .07);
        }
    }

    private record Mesh(List<Vec3> vertices, List<Integer> colors) {}

    private static Mesh capture(EffectFrame frame) {
        var points = new ArrayList<Vec3>();
        var colors = new ArrayList<Integer>();
        VertexConsumer target = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                new Class[]{VertexConsumer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("addVertex") && args.length == 3)
                        points.add(new Vec3((float) args[0], (float) args[1], (float) args[2]));
                    if (method.getName().equals("setColor") && args.length == 1) colors.add((Integer) args[0]);
                    return proxy;
                });
        frame.draw(target, Vec3.ZERO);
        return new Mesh(points, colors);
    }

    private static Mesh petals(int power, String response) {
        var options = JsonParser.parseString("{\"type\":\"redstone\"}").getAsJsonObject();
        options.addProperty("response", response);
        var frame = new EffectFrame();
        WorldWorkshopGeometry.redstone(frame, Vec3.ZERO, power, new WorldWorkshopStyle(options));
        return capture(frame);
    }

    @Test void allSixteenWireLevelsIndividuallyDriveTheSelectedVisualProperty() {
        int previousAlpha = 0;
        double previousSpread = 0;
        for (int power = 0; power <= 15; power++) {
            Mesh both = petals(power, "both");
            double spread = both.vertices().get(2).x;
            int alpha = both.colors().getFirst() >>> 24;
            assertTrue(spread > previousSpread && alpha > previousAlpha);
            previousSpread = spread;
            previousAlpha = alpha;
        }
        assertEquals(petals(0, "opening").colors(), petals(15, "opening").colors());
        assertEquals(petals(0, "brightness").vertices(), petals(15, "brightness").vertices());
    }

    @Test void fusionNarrowsInItsMiddleThenWithdrawsAtTheConfiguredDistance() {
        assertTrue(WorldWorkshopGeometry.waist(.3, 2, .2) > WorldWorkshopGeometry.waist(1.5, 2, .2));
        assertEquals(0, WorldWorkshopGeometry.waist(2, 2, .2));
        for (double distance : new double[]{.55, 1.65, 2, 2.5, 0}) {
            var frame = new EffectFrame();
            WorldWorkshopGeometry.fusion(frame, Vec3.ZERO, new Vec3(distance, 0, 0), 2, .2, -1, -1);
            if (distance == 0 || distance >= 2) {
                assertEquals(0, frame.size());
                continue;
            }
            var mesh = capture(frame);
            assertEquals(48, frame.size());
            assertTrue(mesh.vertices().stream().filter(v -> Math.abs(v.x - distance / 2) < 1e-5)
                    .anyMatch(v -> Math.abs(Math.hypot(v.y, v.z) - WorldWorkshopGeometry.waist(distance, 2, .2)) < .00001));
        }
    }

    @Test void validatesPerEffectBounds() {
        for (String bad : List.of("{\"type\":\"current\",\"speed\":-1}",
                "{\"type\":\"redstone\",\"response\":\"spin\"}", "{\"type\":\"school\",\"count\":2.5}",
                "{\"type\":\"fusion\",\"color\":\"green\"}", "{\"type\":\"current\",\"range\":2}"))
            assertThrows(IllegalArgumentException.class, () -> WorldWorkshop.validate(JsonParser.parseString(bad).getAsJsonObject()));
    }
}
