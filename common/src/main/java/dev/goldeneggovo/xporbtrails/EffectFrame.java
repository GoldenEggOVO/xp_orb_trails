package dev.goldeneggovo.xporbtrails;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** A bounded set of world-space primitives, published after extraction. */
class EffectFrame {
    private static final int LIMIT = 16_384;
    private final List<Primitive> items = new ArrayList<>();

    private interface Primitive {
        void draw(VertexConsumer out, Vec3 camera);
    }

    int size() {
        return items.size();
    }

    boolean full() {
        return items.size() >= LIMIT;
    }

    void line(Vec3 a, Vec3 b, double width, int argb) {
        if (items.size() >= LIMIT || width <= 0 || argb >>> 24 == 0) return;
        items.add(
                (out, camera) -> {
                    Vec3 tangent = b.subtract(a);
                    if (tangent.lengthSqr() < 1e-15) return;
                    Vec3 side = tangent.cross(camera.subtract(a));
                    if (side.lengthSqr() < 1e-14)
                        side = tangent.cross(Math.abs(tangent.normalize().y) < .9 ? UP : EAST);
                    side = side.normalize().scale(width * .5);
                    vertices(
                            out,
                            camera,
                            a.add(side),
                            a.subtract(side),
                            b.subtract(side),
                            b.add(side),
                            argb);
                });
    }

    void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int argb) {
        if (items.size() < LIMIT && argb >>> 24 != 0)
            items.add((out, camera) -> vertices(out, camera, a, b, c, d, argb));
    }

    void sprite(SpriteSheet sheet, Vec3 center, double size, double ageSeconds, int argb) {
        sprite(sheet, center, size, ageSeconds, argb, 0);
    }

    void sprite(
            SpriteSheet sheet,
            Vec3 center,
            double size,
            double ageSeconds,
            int argb,
            double rotationRadians) {
        if (items.size() >= LIMIT || sheet == null || size <= 0 || argb >>> 24 == 0) return;
        SpriteSheet.Frame frame = sheet.frameAt(ageSeconds, sheet.loop());
        spriteFrame(sheet, frame, center, size, argb, rotationRadians);
    }

    void spriteFrame(SpriteSheet sheet, SpriteSheet.Frame frame, Vec3 center, double size,
                     int argb, double rotationRadians) {
        if (items.size() < LIMIT && frame != null && size > 0 && argb >>> 24 != 0)
            items.add(
                    (out, camera) ->
                            SpriteRenderer.worldTinted(
                                    sheet, frame, center, camera, size, argb, rotationRadians));
    }

    void decal(SpriteSheet sheet, SpriteSheet.Frame frame, Vec3 center, Vec3 normal, double size, int color) {
        if (frame == null || items.size() >= LIMIT || size <= 0 || color >>> 24 == 0) return;
        var right = OrbEffectOptions.perpendicular(normal).scale(size * .5);
        var up = normal.cross(right).scale((double)frame.height() / frame.width());
        items.add((out, camera) -> SpriteRenderer.decal(sheet, frame, center.subtract(camera), right, up, color));
    }

    void draw(VertexConsumer out, Vec3 camera) {
        for (var item : items) item.draw(out, camera);
    }

    private static final Vec3 UP = new Vec3(0, 1, 0), EAST = new Vec3(1, 0, 0);

    private static void vertices(
            VertexConsumer out, Vec3 camera, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int argb) {
        vertex(out, camera, a, argb);
        vertex(out, camera, b, argb);
        vertex(out, camera, c, argb);
        vertex(out, camera, d, argb);
    }

    private static void vertex(VertexConsumer out, Vec3 camera, Vec3 at, int color) {
        out.addVertex(
                        (float) (at.x - camera.x),
                        (float) (at.y - camera.y),
                        (float) (at.z - camera.z))
                .setColor(color);
    }
}
