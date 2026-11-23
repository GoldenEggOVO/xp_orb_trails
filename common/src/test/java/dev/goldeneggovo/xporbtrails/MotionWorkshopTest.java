package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MotionWorkshopTest {
    private static OrbEffectOptions profile(String type) {
        var obj = EffectCollectionFixture.profile("lab-" + type)
                .getAsJsonObject("advanced").getAsJsonArray("workshop").get(0).getAsJsonObject();
        MotionWorkshop.validate(obj);
        return new OrbEffectOptions(obj);
    }

    @Test
    void rigRotatesChildrenAroundParentSpacePivotsAndRejectsCycles() {
        var object = profile("rig").values.deepCopy();
        var joints = object.getAsJsonArray("joints");
        var parent = joints.get(0).getAsJsonObject();
        parent.addProperty("swingDegrees", 0);
        parent.addProperty("baseDegrees", 90);
        var rig = new MotionWorkshopRig(object);
        var pose = rig.poses(new Vec3(2, 3, 4), Vec3.Z_AXIS, 0);
        Vec3 headPivot = pose[1].pivot();
        assertEquals(2 - .045, headPivot.x, 1e-8);
        assertEquals(3, headPivot.y, 1e-8);
        assertEquals(4 + .16, headPivot.z, 1e-8);
        // Flapping changes the child wingtip's pivot but leaves the wing attachment in place.
        var later = rig.poses(new Vec3(2, 3, 4), Vec3.Z_AXIS, 3);
        assertTrue(pose[2].pivot().distanceTo(later[2].pivot()) < 1e-8);
        assertTrue(pose[3].pivot().distanceTo(later[3].pivot()) > .02);
        parent.addProperty("parent", "tail_tip");
        assertThrows(IllegalArgumentException.class, () -> MotionWorkshop.validate(object));
    }

    @Test
    void pressureAndTiltAreStampedAtBirthAndSpeedNarrowsTheNib() {
        var pen = profile("brush");
        var path = new MotionWorkshopPath();
        for (int i = 0; i <= 5; i++) path.sample(new Vec3(.10 * i, 0, 0), i, pen, OrbEffectOptions.EMPTY, 90);
        double steady = path.nodes.getLast().pressure();
        path.sample(new Vec3(.5, 0, .1), 6, pen, OrbEffectOptions.EMPTY, 90);
        var corner = path.nodes.getLast();
        assertTrue(corner.pressure() > steady + .25);
        assertTrue(Math.abs(corner.tilt()) > .2);
        for (int i = 1; i <= 5; i++) path.sample(new Vec3(.5, 0, .1 + .5 * i), 6 + i, pen, OrbEffectOptions.EMPTY, 90);
        assertSame(corner, path.nodes.get(6));
        assertTrue(path.nodes.getLast().pressure() < steady);
        var mesh = new EffectFrame();
        path.drawBrush(mesh, pen, new TrailConfig(), 11, null);
        assertEquals(path.nodes.size() - 1, mesh.size());
    }

    @Test
    void sectionsInterpolateAcrossOneConnectedTubeAndKeepTheSelectedEdgeCount() {
        var morph = profile("morph");
        for (int i = 0; i < 16; i++) {
            double theta = i * Math.TAU / 16;
            assertEquals(1, MotionWorkshopPath.section(theta, 0, 4, .28, .035, .5).length(), 1e-8);
        }
        assertEquals(.28, MotionWorkshopPath.section(Math.PI / 4, .5, 4, .28, .035, .5).length(), 1e-8);
        assertEquals(.035, MotionWorkshopPath.section(Math.PI / 2, 1, 4, .28, .035, .5).y, 1e-8);
        var cfg = new TrailConfig();
        var p = new MotionWorkshopPath();
        for (int i = 0; i < 6; i++) p.sample(Vec3.Z_AXIS.scale(i * .1), i, OrbEffectOptions.EMPTY, OrbEffectOptions.EMPTY, 90);
        for (int sides : List.of(2, 8, 16, 32)) {
            cfg.crossSectionSides = sides;
            var out = new EffectFrame();
            p.drawMorph(out, morph, cfg, 5, null);
            assertEquals((sides == 2 ? 1 : sides) * 5, out.size());
            assertEquals(sides, cfg.crossSectionSides);
        }
    }

    @Test
    void ringWakeUsesArcLengthAndLightsFromNewestToOldestAlongTheCurve() {
        var hoops = profile("hoops");
        hoops.values.addProperty("spacing", .5);
        hoops.values.addProperty("replayTicks", 20);
        var p = new MotionWorkshopPath();
        for (int i = 0; i <= 4; i++) p.sample(i <= 2 ? new Vec3(i * .5, 0, 0) : new Vec3(1, 0, (i - 2) * .5), i,
                OrbEffectOptions.EMPTY, hoops, 90);
        assertEquals(4, p.rings.size());
        var forward = p.sequence(JsonParser.parseString("\"forward\""));
        assertTrue(forward.arrival()[0] < forward.arrival()[3]);
        assertEquals(0, forward.distanceAt(0), 1e-8);
        var custom = p.sequence(JsonParser.parseString("[2, 0, -1]"));
        assertTrue(custom.arrival()[2] < custom.arrival()[0] && custom.arrival()[0] < custom.arrival()[3]);
        assertEquals(p.rings.get(2).distance(), custom.distanceAt(custom.arrival()[2]), 1e-8);
        assertEquals(p.rings.get(0).distance(), custom.distanceAt(custom.arrival()[0]), 1e-8);
        assertEquals(new Vec3(1, 0, .5), p.at(1.5));
        assertEquals(new Vec3(.5, 0, 0), p.at(.5));
        var early = new EffectFrame();
        p.drawHoops(early, hoops, .1);
        var colors = colors(early);
        int rest = hoops.color("color", 0) & 0xffffff, lit = hoops.color("litColor", 0) & 0xffffff;
        assertEquals(rest, colors.get(0));
        assertEquals(rest, colors.get(2 * 16 * 4));
        assertEquals(lit, colors.get(3 * 16 * 4));
        var later = new EffectFrame();
        p.drawHoops(later, hoops, 16);
        assertEquals(lit, colors(later).getFirst());
        var finished = new EffectFrame();
        p.drawHoops(finished, hoops, 20 + hoops.values.get("expandTicks").getAsInt());
        assertEquals(0, finished.size());
    }

    private static List<Integer> colors(EffectFrame frame) {
        var colors = new ArrayList<Integer>();
        var sink = (VertexConsumer) Proxy.newProxyInstance(MotionWorkshopTest.class.getClassLoader(), new Class[]{VertexConsumer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setColor")) colors.add((int)args[0] & 0xffffff);
                    return proxy;
                });
        frame.draw(sink, new Vec3(7, 11, 17));
        return colors;
    }

    @Test
    void handPathReachesTheLiveTargetAndStartsFromThePickup() {
        var o = profile("hand");
        Vec3 start = new Vec3(2, 2, 1), old = new Vec3(3, 1, 7), target = new Vec3(-3, 1, 5);
        assertEquals(start, MotionWorkshop.handPoint(start, target, 0, .5));
        assertTrue(target.distanceTo(MotionWorkshop.handPoint(start, target, 1, .5)) < 1e-8);
        assertTrue(MotionWorkshop.handPoint(start, target, .8, .5).distanceTo(MotionWorkshop.handPoint(start, old, .8, .5)) > 5);
        assertTrue(MotionWorkshop.handPoint(start, target, .5, .5).y > start.lerp(target, .5).y);
        var out = new EffectFrame();
        MotionWorkshop.drawHand(out, start, target, .8, o);
        assertEquals(11, out.size());
    }
}
