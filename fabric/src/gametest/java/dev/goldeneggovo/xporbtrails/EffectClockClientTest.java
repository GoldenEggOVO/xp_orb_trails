package dev.goldeneggovo.xporbtrails;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.world.phys.Vec3;

final class EffectClockClientTest {
    static int check(Minecraft mc, List<Integer> orbs, String phase, List<String> failures) {
        long time = mc.level.getGameTime();
        long frameNanos = System.nanoTime();
        var before = sample(mc, orbs, frameNanos);
        try {
            for (int delta : List.of(-1, 40, -120, 12000)) {
                mc.getConnection().handleSetTime(new ClientboundSetTimePacket(time + delta, Map.of()));
                if (mc.level.getGameTime() != time + delta) throw new AssertionError("Server time packet did not apply");
                var after = sample(mc, orbs, frameNanos);
                if (!before.equals(after)) failures.add(phase + " " + delta + " ticks (" + before.size() + " -> " + after.size() + ")");
            }
        } finally {
            mc.getConnection().handleSetTime(new ClientboundSetTimePacket(time, Map.of()));
        }
        System.out.println("XPORB_CLOCK " + phase + " samples=" + before.size());
        return before.size();
    }

    private static List<Object> sample(Minecraft mc, List<Integer> orbs, long frameNanos) {
        var values = new ArrayList<Object>();
        var frame = new EffectFrame() {
            @Override void line(Vec3 a, Vec3 b, double width, int color) {
                if (color >>> 24 != 0) values.add(List.of(a, b, width, color));
            }
            @Override void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color) {
                if (color >>> 24 != 0) values.add(List.of(a, b, c, d, color));
            }
            @Override void spriteFrame(SpriteSheet sheet, SpriteSheet.Frame uv, Vec3 p, double size, int color, double rotation) {
                if (color >>> 24 != 0) values.add(List.of(sheet, uv, p, size, color, rotation));
            }
            @Override void decal(SpriteSheet sheet, SpriteSheet.Frame uv, Vec3 p, Vec3 normal, double size, int color) {
                if (color >>> 24 != 0) values.add(List.of(sheet, uv, p, normal, size, color));
            }
        };
        BodyPickupEffects.extract(frame, mc.level, .5f);
        SceneEffects.extract(frame, mc.level, .5f);
        Workshop.extract(frame, mc.level, .5f, frameNanos);
        SceneOptics.extract(mc.level, mc.gameRenderer.mainCamera().position(), .5f, orbs);
        try {
            var field = SceneOptics.class.getDeclaredField("frame");
            field.setAccessible(true);
            var optics = field.get(null);
            if (optics != null) values.add(optics);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return values;
    }
}
